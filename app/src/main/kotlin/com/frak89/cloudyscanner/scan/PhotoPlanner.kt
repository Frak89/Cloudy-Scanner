package com.frak89.cloudyscanner.scan

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** One kept photo of the subject, with everything needed to reuse it for 3D work. */
class Shot(
    val id: Int,
    var slot: Int,
    val detail: Boolean,
    val az: Float, val el: Float, val dist: Float,
    val sharp: Float, val quality: Float,
    val pose: FloatArray,
    /** fx, fy, cx, cy, width, height of the saved image */
    val intr: FloatArray,
    val rot: Int,
    val file: String,
    val manual: Boolean,
)

/** A viewpoint the plan wants covered: azimuth around the subject plus an elevation band. */
class ViewSlot(val name: String, val az: Float, val azTol: Float, val elMin: Float, val elMax: Float)

/** A frame offered to the planner. */
class Candidate(
    val az: Float, val el: Float, val dist: Float,
    val sharp: Float,
    /** Estimated motion blur in pixels during the exposure. */
    val blurPx: Float,
    /** Share of the frame covered by the subject, 0..1. */
    val fill: Float,
    /** True when the whole subject is inside the frame. */
    val inside: Boolean,
    val idealDist: Float,
)

class Decision(val accept: Boolean, val slot: Int, val detail: Boolean, val replace: Shot?, val quality: Float, val why: String)

/**
 * Decides which photos of the subject are worth keeping. The space around the subject is
 * divided into viewpoints (front, back, sides, above…); how many depends on how intricate
 * the object is. Each viewpoint keeps only its best photo, and blurred, low-detail or
 * redundant frames are refused or thrown away.
 */
class PhotoPlanner {
    /** 0 simple (3 views), 1 medium (5), 2 complex (13). */
    var level = 1
        private set
    /** True once the user picked the level by hand; automatic estimates then leave it alone. */
    var levelFixed = false
    var slots: List<ViewSlot> = plan(1)
        private set
    private val shots = ArrayList<Shot>()
    private var refAz = Float.NaN
    private val recentSharp = FloatArray(24)
    private var recentN = 0
    private var nextId = 1

    var rejectedBlur = 0; private set
    var rejectedSoft = 0; private set
    var rejectedRedundant = 0; private set
    var replaced = 0; private set

    @Synchronized fun shots(): List<Shot> = ArrayList(shots)
    @Synchronized fun count() = shots.size
    @Synchronized fun newId() = nextId++

    @Synchronized
    fun setLevel(l: Int, byUser: Boolean): List<Shot> {
        if (byUser) levelFixed = true else if (levelFixed) return emptyList()
        val nl = l.coerceIn(0, 2)
        if (nl == level) return emptyList()
        level = nl
        slots = plan(nl)
        for (s in shots) s.slot = slotFor(s.az, s.el)
        return keepBestPerSlot()
    }

    @Synchronized
    fun reset(): List<Shot> {
        val old = ArrayList(shots)
        shots.clear(); refAz = Float.NaN; recentN = 0
        rejectedBlur = 0; rejectedSoft = 0; rejectedRedundant = 0; replaced = 0
        return old
    }

    /**
     * Where the camera is relative to the subject: [azimuth°, elevation°, distance m].
     * Azimuth 0 is the side first photographed; positive is to the user's right.
     */
    @Synchronized
    fun view(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        val dx = cx - sx; val dy = cy - sy; val dz = cz - sz
        val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        val az = Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
        if (refAz.isNaN()) refAz = az
        return floatArrayOf(wrap(az - refAz), Math.toDegrees(asin((dy / d).coerceIn(-1f, 1f)).toDouble()).toFloat(), d)
    }

    private fun slotFor(az: Float, el: Float): Int {
        for ((i, s) in slots.withIndex()) if (el >= s.elMin && el < s.elMax && abs(wrap(az - s.az)) <= s.azTol) return i
        return -1
    }

    private fun median(): Float {
        val n = minOf(recentN, recentSharp.size)
        if (n < 5) return 0f
        val a = recentSharp.copyOf(n); a.sort()
        return a[n / 2]
    }

    /** Judges a frame. Nothing is stored until [add] is called with the saved shot. */
    @Synchronized
    fun consider(c: Candidate, manual: Boolean = false): Decision {
        val med = median()
        recentSharp[recentN % recentSharp.size] = c.sharp; recentN++
        val slot = slotFor(c.az, c.el)
        val detail = c.idealDist > 0f && (c.dist < 0.6f * c.idealDist || !c.inside)
        val framing = if (detail) 0.9f else if (c.fill in 0.08f..0.85f) 1f else 0.6f
        val quality = c.sharp * framing
        if (c.blurPx > MAX_BLUR_PX) { rejectedBlur++; return Decision(false, slot, detail, null, quality, "mossa") }
        if (med > 0f && c.sharp < SOFT_RATIO * med) { rejectedSoft++; return Decision(false, slot, detail, null, quality, "poco dettagliata") }
        if (manual) return Decision(true, slot, detail, null, quality, "manuale")
        if (slot < 0) return Decision(false, slot, detail, null, quality, "fuori piano")
        if (detail && (level == 0 || shots.count { it.detail } >= MAX_DETAIL)) { rejectedRedundant++; return Decision(false, slot, true, null, quality, "ridondante") }
        val cur = shots.firstOrNull { it.slot == slot && it.detail == detail && !it.manual }
        if (cur == null) return Decision(true, slot, detail, null, quality, "nuova vista")
        if (quality > cur.quality * 1.2f) return Decision(true, slot, detail, cur, quality, "migliore")
        rejectedRedundant++
        return Decision(false, slot, detail, null, quality, "ridondante")
    }

    @Synchronized
    fun add(s: Shot, replace: Shot?) {
        if (replace != null && shots.remove(replace)) replaced++
        shots.add(s)
    }

    /** Throws away soft photos and all but the best photo of each viewpoint. Returns what was dropped. */
    @Synchronized
    fun prune(): List<Shot> {
        val out = ArrayList<Shot>()
        if (shots.size >= 3) {
            val sorted = shots.map { it.sharp }.sorted()
            val med = sorted[sorted.size / 2]
            val it = shots.iterator()
            while (it.hasNext()) { val s = it.next(); if (s.sharp < SOFT_RATIO * med) { it.remove(); out.add(s); rejectedSoft++ } }
        }
        out.addAll(keepBestPerSlot())
        return out
    }

    private fun keepBestPerSlot(): List<Shot> {
        val out = ArrayList<Shot>()
        val best = HashMap<Int, Shot>()
        for (s in shots) {
            if (s.manual) continue
            val k = s.slot * 2 + (if (s.detail) 1 else 0)
            val b = best[k]
            if (s.slot < 0) out.add(s)
            else if (b == null) best[k] = s
            else if (s.quality > b.quality) { out.add(b); best[k] = s } else out.add(s)
        }
        shots.removeAll(out.toSet())
        rejectedRedundant += out.size
        return out
    }

    @Synchronized fun covered(i: Int) = shots.any { it.slot == i && !it.detail } || shots.any { it.slot == i }
    @Synchronized fun coveredCount() = slots.indices.count { covered(it) }
    @Synchronized fun missing(): List<String> = slots.indices.filter { !covered(it) }.map { slots[it].name }
    val complete: Boolean get() = coveredCount() == slots.size

    /** One short instruction: distance first, then where to move for the nearest missing view. */
    @Synchronized
    fun advice(az: Float, el: Float, dist: Float, ideal: Float): String {
        if (ideal > 0f) {
            if (dist > 1.6f * ideal) return String.format(Locale.US, "Avvicinati: ideale ~%.1f m (ora %.1f)", ideal, dist)
            if (dist < 0.5f * ideal && level == 0) return String.format(Locale.US, "Allontanati a ~%.1f m per inquadrarlo intero", ideal)
        }
        var best = -1; var bd = Float.MAX_VALUE
        for (i in slots.indices) if (!covered(i)) {
            val s = slots[i]
            val elMid = (maxOf(s.elMin, -10f) + minOf(s.elMax, 80f)) / 2
            val d = (if (s.azTol >= 180f) 0f else abs(wrap(s.az - az))) + abs(elMid - el) * 0.7f
            if (d < bd) { bd = d; best = i }
        }
        if (best < 0) return "Viste complete: puoi fermarti"
        val s = slots[best]
        if (el < s.elMin) return "Manca «${s.name}»: alza il telefono e inquadra dall'alto"
        if (el >= s.elMax) return "Manca «${s.name}»: abbassa il telefono"
        val turn = wrap(s.az - az)
        return if (abs(turn) <= s.azTol) "Tieni fermo: sto fotografando «${s.name}»"
        else "Manca «${s.name}»: gira attorno verso ${if (turn > 0) "destra" else "sinistra"} di ~${(abs(turn) / 5f).toInt() * 5}°"
    }

    companion object {
        /** Allowed motion blur, in pixels of a 1920-wide frame. */
        const val MAX_BLUR_PX = 6f
        const val SOFT_RATIO = 0.55f
        const val MAX_DETAIL = 6
        val LEVEL_NAMES = arrayOf("Semplice", "Media", "Complessa")

        fun wrap(a: Float): Float { var x = a % 360f; if (x > 180f) x -= 360f; if (x <= -180f) x += 360f; return x }

        fun plan(level: Int): List<ViewSlot> = when (level) {
            0 -> listOf(
                ViewSlot("davanti", 0f, 75f, -90f, 55f), ViewSlot("dietro", 180f, 75f, -90f, 55f),
                ViewSlot("sopra", 0f, 180f, 55f, 91f))
            1 -> listOf(
                ViewSlot("davanti", 0f, 45f, -90f, 52f), ViewSlot("lato destro", 90f, 45f, -90f, 52f),
                ViewSlot("dietro", 180f, 45f, -90f, 52f), ViewSlot("lato sinistro", -90f, 45f, -90f, 52f),
                ViewSlot("sopra", 0f, 180f, 52f, 91f))
            else -> {
                val low = arrayOf("davanti", "davanti-destra", "lato destro", "dietro-destra", "dietro", "dietro-sinistra", "lato sinistro", "davanti-sinistra")
                val high = arrayOf("dall'alto davanti", "dall'alto a destra", "dall'alto dietro", "dall'alto a sinistra")
                val l = ArrayList<ViewSlot>()
                for (i in 0 until 8) l.add(ViewSlot(low[i], wrap(i * 45f), 22.5f, -90f, 30f))
                for (i in 0 until 4) l.add(ViewSlot(high[i], wrap(i * 90f), 45f, 30f, 62f))
                l.add(ViewSlot("sopra", 0f, 180f, 62f, 91f))
                l
            }
        }

        /**
         * Sharpness of a luma plane inside a rectangle: variance of the Laplacian, sampled every
         * other pixel and normalised by brightness. Blurred or featureless frames score low.
         */
        fun sharpness(y: ByteArray, rowStride: Int, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
            val ax = x0.coerceIn(2, w - 3); val bx = x1.coerceIn(ax + 1, w - 2)
            val ay = y0.coerceIn(2, h - 3); val by = y1.coerceIn(ay + 1, h - 2)
            var sum = 0.0; var sq = 0.0; var lum = 0.0; var n = 0
            var yy = ay
            while (yy < by) {
                val row = yy * rowStride
                var xx = ax
                while (xx < bx) {
                    val i = row + xx
                    if (i + 2 * rowStride + 2 >= y.size) break
                    val c = y[i].toInt() and 0xFF
                    val lap = 4 * c - (y[i - 2].toInt() and 0xFF) - (y[i + 2].toInt() and 0xFF) -
                        (y[i - 2 * rowStride].toInt() and 0xFF) - (y[i + 2 * rowStride].toInt() and 0xFF)
                    sum += lap; sq += lap.toDouble() * lap; lum += c; n++
                    xx += 2
                }
                yy += 2
            }
            if (n < 16) return 0f
            val mean = sum / n
            return ((sq / n - mean * mean) / (lum / n + 24.0)).toFloat()
        }

        /** Dominant-direction test on unit normals: 0 simple (few flat faces), 1 medium, 2 complex. */
        fun complexity(nrm: FloatArray, n: Int, pick: (Int) -> Boolean): Int {
            val bins = IntArray(27); var total = 0
            for (i in 0 until n) {
                if (!pick(i)) continue
                val x = nrm[i * 3]; val y = nrm[i * 3 + 1]; val z = nrm[i * 3 + 2]
                val l = sqrt(x * x + y * y + z * z); if (l < 1e-6f) continue
                val bx = Math.round(x / l * 1.2f).coerceIn(-1, 1) + 1
                val by = Math.round(y / l * 1.2f).coerceIn(-1, 1) + 1
                val bz = Math.round(z / l * 1.2f).coerceIn(-1, 1) + 1
                bins[bx * 9 + by * 3 + bz]++; total++
            }
            if (total < 200) return 1
            bins.sortDescending()
            val top = (bins[0] + bins[1] + bins[2]).toFloat() / total
            return if (top > 0.62f) 0 else if (top > 0.4f) 1 else 2
        }
    }
}
