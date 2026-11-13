package com.frak89.cloudyscanner.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** "This thing here is a …": a name for whatever object sits at (x, y, z), from the AI or the user. */
class PartLabel(val x: Float, val y: Float, val z: Float, val name: String)

/** One object of the clean model, as listed in the catalogue and exported (one named object each). */
class CatalogPart(
    val name: String,
    val labelled: Boolean,
    /** revolution, prism, pipe, slab, face, detail */
    val kind: String,
    val center: FloatArray,
    /** rough half size, for matching labels and framing photos */
    val size: Float,
    val dims: String,
    val color: Int,
    /** index into the mesh's groups */
    val group: Int,
    val note: String,
    val axis: FloatArray,
    val length: Float,
    val radius: Float,
)

class SmartOptions(
    /** 0 low, 1 mid, 2 high poly */
    val level: Int,
    /** Sides used for circles; 0 picks from level and size. */
    val sides: Int = 0,
    /** Chamfer the sharp rims of round solids. */
    val bevel: Boolean = true,
    /** Keep whatever is not a regular shape as ordinary scanned mesh. */
    val details: Boolean = true,
    /** What the object is, if known (from the AI): steers typical finishing touches. */
    val hint: String = "",
    /** Names given to objects (by the AI or by hand); steer each object's typical shape. */
    val labels: List<PartLabel> = emptyList(),
)

class SmartResult(val mesh: MeshData, val surfaces: List<NurbsSurface>, val info: String, val solids: Int, val coverage: Float, val parts: List<CatalogPart> = emptyList())

/**
 * Rebuilds a scan the way a modeller would: few, exact solids instead of a dense skin.
 *
 *  - Everything round about an axis (can, bottle, round table with its leg…) becomes one
 *    solid of revolution: the radius is measured along the axis, reduced to straight runs,
 *    cones and steps, and swept with an N-sided circle. Flat ends are single N-gons.
 *  - Flat rectangular faces become slabs with the thickness seen at their edges.
 *  - Tubes joined by bends stay tubes and bends.
 *  - Shapes are squared up: axes and faces within a few degrees of vertical, horizontal,
 *    parallel or perpendicular are made exactly so; near-equal radii are made equal; tube
 *    ends are brought exactly onto the face they stand on.
 *  - The rest can be kept as ordinary mesh so no detail is lost.
 */
object SmartModel {
    private class Builder {
        var pos = FloatArray(3 * 1024); var col = IntArray(1024); var nv = 0
        var quads = IntArray(4 * 1024); var nq = 0
        val ngons = ArrayList<IntArray>()
        val groups = ArrayList<MeshGroup>()
        private var gFace = 0; private var gNgon = 0
        fun v(x: Float, y: Float, z: Float, c: Int): Int {
            if (nv == col.size) { pos = pos.copyOf(pos.size * 2); col = col.copyOf(col.size * 2) }
            pos[nv * 3] = x; pos[nv * 3 + 1] = y; pos[nv * 3 + 2] = z; col[nv] = c
            return nv++
        }
        fun quad(a: Int, b: Int, c: Int, d: Int) {
            if (nq * 4 + 4 > quads.size) quads = quads.copyOf(quads.size * 2)
            quads[nq * 4] = a; quads[nq * 4 + 1] = b; quads[nq * 4 + 2] = c; quads[nq * 4 + 3] = d; nq++
        }
        fun tri(a: Int, b: Int, c: Int) = quad(a, b, c, c)
        fun endGroup(name: String) { groups.add(MeshGroup(name, gFace, nq, gNgon, ngons.size)); gFace = nq; gNgon = ngons.size }
        fun mesh(): MeshData {
            val p = pos.copyOf(nv * 3); val q = quads.copyOf(nq * 4)
            val n = FloatArray(nv * 3)
            fun acc(a: Int, b: Int, c: Int) {
                val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
                val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
                val x = uy * vz - uz * vy; val y = uz * vx - ux * vz; val z = ux * vy - uy * vx
                for (k in intArrayOf(a, b, c)) { n[k * 3] += x; n[k * 3 + 1] += y; n[k * 3 + 2] += z }
            }
            for (f in 0 until nq) { acc(q[f * 4], q[f * 4 + 1], q[f * 4 + 2]); if (q[f * 4 + 2] != q[f * 4 + 3]) acc(q[f * 4], q[f * 4 + 2], q[f * 4 + 3]) }
            for (g in ngons) for (k in 1 until g.size - 1) acc(g[0], g[k], g[k + 1])
            for (i in 0 until nv) { val l = max(1e-12f, sqrt(n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2])); n[i * 3] /= l; n[i * 3 + 1] /= l; n[i * 3 + 2] /= l }
            return MeshData(p, n, col.copyOf(nv), q, 0f, ArrayList(ngons), ArrayList(groups))
        }
    }

    private fun basis(d: FloatArray): Array<FloatArray> {
        val h = if (abs(d[1]) < 0.8f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        var ex = h[1] * d[2] - h[2] * d[1]; var ey = h[2] * d[0] - h[0] * d[2]; var ez = h[0] * d[1] - h[1] * d[0]
        val l = sqrt(ex * ex + ey * ey + ez * ez); ex /= l; ey /= l; ez /= l
        return arrayOf(floatArrayOf(ex, ey, ez), floatArrayOf(d[1] * ez - d[2] * ey, d[2] * ex - d[0] * ez, d[0] * ey - d[1] * ex))
    }

    private fun dv(a: FloatArray) = doubleArrayOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())

    fun sidesFor(level: Int, radius: Float, wanted: Int): Int {
        if (wanted >= 3) return wanted
        var n = intArrayOf(8, 16, 32)[level.coerceIn(0, 2)]
        if (radius < 0.02f) n /= 2 else if (radius > 0.25f) n = n * 3 / 2
        return max(6, n)
    }

    // ------------------------------------------------------------------ squaring up

    private const val SNAP = 0.9925f      // cos 7 degrees
    private const val SNAP_SIN = 0.122f

    /** Returns the horizontal reference angle (most common wall/edge direction), or NaN. */
    private fun dominant(fit: FitResult): Float {
        var c = 0.0; var s = 0.0
        fun add(x: Float, z: Float, w: Float) { if (x * x + z * z < 0.5f) return; val a = 4.0 * atan2(z, x); c += cos(a) * w; s += sin(a) * w }
        for (p in fit.planes) {
            if (abs(p.n[1]) < SNAP_SIN) add(p.n[0], p.n[2], p.count.toFloat())
            else if (abs(p.n[1]) > SNAP && !p.disc) add(p.u[0], p.u[2], p.count.toFloat())
        }
        for (t in fit.tubes) if (abs(t.d[1]) < SNAP_SIN) add(t.d[0], t.d[2], t.count.toFloat())
        return if (c * c + s * s < 1e-6) Float.NaN else (atan2(s, c) / 4.0).toFloat()
    }

    private fun snap(d: FloatArray, dom: Float): FloatArray {
        if (abs(d[1]) > SNAP) return floatArrayOf(0f, if (d[1] > 0) 1f else -1f, 0f)
        if (abs(d[1]) >= SNAP_SIN) return d
        var a = atan2(d[2], d[0])
        if (!dom.isNaN()) {
            val q = (Math.PI / 2).toFloat()
            val k = Math.round((a - dom) / q)
            if (abs(a - (dom + k * q)) < 0.122f) a = dom + k * q
        }
        return floatArrayOf(cos(a), 0f, sin(a))
    }

    /** Makes nearly vertical / horizontal / parallel / perpendicular things exactly so, in place. */
    private fun regularize(fit: FitResult, joined: Set<FitTube>): Int {
        val dom = dominant(fit)
        var changed = 0
        for (p in fit.planes) {
            val n = snap(p.n, dom)
            if (n === p.n) continue
            changed++
            p.n = n
            val u: FloatArray = if (abs(n[1]) > 0.5f) {
                val s = snap(floatArrayOf(p.u[0], 0f, p.u[2]).also { val l = max(1e-6f, sqrt(it[0] * it[0] + it[2] * it[2])); it[0] /= l; it[2] /= l }, dom)
                s
            } else {
                // vertical face: one axis horizontal, the other straight up
                val h = floatArrayOf(-n[2], 0f, n[0])
                if (abs(p.u[0] * h[0] + p.u[2] * h[2]) < abs(p.v[0] * h[0] + p.v[2] * h[2])) { val t = p.hu; p.hu = p.hv; p.hv = t }
                h
            }
            p.u = u
            p.v = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
        }
        for (t in fit.tubes) {
            if (t in joined) continue
            val d = snap(t.d, dom)
            if (d === t.d) continue
            changed++
            val mid = (t.t0 + t.t1) / 2; val half = (t.t1 - t.t0) / 2
            t.a = floatArrayOf(t.a[0] + t.d[0] * mid, t.a[1] + t.d[1] * mid, t.a[2] + t.d[2] * mid)
            t.d = d; t.t0 = -half; t.t1 = half
        }
        // Equal thickness for tubes that are within 10% of each other (table legs, rails…).
        val free = fit.tubes.filter { it !in joined }.sortedBy { it.r }
        var i = 0
        while (i < free.size) {
            var j = i; var sw = 0f; var sr = 0f
            while (j < free.size && free[j].r <= free[i].r * 1.1f) { sw += free[j].count; sr += free[j].r * free[j].count; j++ }
            if (j - i > 1) { val r = sr / sw; for (k in i until j) free[k].r = r; changed++ }
            i = j
        }
        // Tube ends that stop just short of (or poke through) a face they stand on go exactly onto it.
        for (t in fit.tubes) {
            if (t in joined) continue
            for (first in booleanArrayOf(true, false)) for (p in fit.planes) {
                val nd = p.n[0] * t.d[0] + p.n[1] * t.d[1] + p.n[2] * t.d[2]
                if (abs(nd) < 0.95f) continue
                val e = t.end(first)
                val s = p.n[0] * (e[0] - p.c[0]) + p.n[1] * (e[1] - p.c[1]) + p.n[2] * (e[2] - p.c[2])
                if (abs(s) > max(0.04f, 1.2f * t.r)) continue
                val lu = (e[0] - p.c[0]) * p.u[0] + (e[1] - p.c[1]) * p.u[1] + (e[2] - p.c[2]) * p.u[2]
                val lv = (e[0] - p.c[0]) * p.v[0] + (e[1] - p.c[1]) * p.v[1] + (e[2] - p.c[2]) * p.v[2]
                val inside = if (p.disc) lu * lu + lv * lv < (p.radius + 0.05f) * (p.radius + 0.05f) else abs(lu) < p.hu + 0.05f && abs(lv) < p.hv + 0.05f
                if (!inside) continue
                if (first) t.t0 -= s / nd else t.t1 -= s / nd
                changed++
            }
        }
        return changed
    }

    // ------------------------------------------------------------------ colours

    private fun cdist(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255); val dg = ((a shr 8) and 255) - ((b shr 8) and 255); val db = (a and 255) - (b and 255)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private const val COLOR_T = 60f

    /**
     * The colour most of the points actually have (not the average, which mixes a red mat with
     * the yellow edge under it into orange): the fullest bucket of a coarse colour histogram,
     * then the mean of the points in it.
     */
    fun dominantColor(cols: IntArray, idx: List<Int>): Int {
        if (idx.isEmpty()) return -0x333334
        val cnt = HashMap<Int, Int>()
        for (i in idx) { val c = cols[i]; val k = (((c shr 16) and 255) / 48) * 36 + (((c shr 8) and 255) / 48) * 6 + (c and 255) / 48; cnt[k] = (cnt[k] ?: 0) + 1 }
        val best = cnt.maxByOrNull { it.value }!!.key
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in idx) { val c = cols[i]; val k = (((c shr 16) and 255) / 48) * 36 + (((c shr 8) and 255) / 48) * 6 + (c and 255) / 48
            if (k != best) continue; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    // ------------------------------------------------------------------ solids of revolution

    private class Axis(var a: FloatArray, var d: FloatArray, var t0: Float, var t1: Float, var rMax: Float, var color: Int) {
        val discs = ArrayList<Int>()
        val tubeSet = HashSet<FitTube>()
        /** Points it may use, when found from the shape alone (no tube or disc to start from). */
        var pool: IntArray? = null
        /** Near-vertical axes point up, so a part's "top" is its upper end. */
        fun orient() {
            if (d[1] < -0.5f) { d = floatArrayOf(-d[0], -d[1], -d[2]); val o = t0; t0 = -t1; t1 = -o }
        }
        fun at(t: Float) = floatArrayOf(a[0] + d[0] * t, a[1] + d[1] * t, a[2] + d[2] * t)
    }

    /** Measured profile: simplified polyline plus per-ring (1 cm) colour and flat-surface statistics. */
    private class Prof(
        val tStart: Float, val tEnd: Float, val bin: Float, val bn: IntArray,
        val bt: FloatArray, val br: FloatArray, val bc: IntArray,
        /** share of points facing along the axis, their median height, radius and colour (flat layers) */
        val bup: FloatArray, val bupT: FloatArray, val bupR: FloatArray, val bupC: IntArray,
    )

    private class Part(val ax: Axis, var prof: ArrayList<FloatArray>, var color: Int) {
        /** (from t, to t, colour) bands along the axis, for parts that change colour without a break */
        var bands: List<FloatArray> = emptyList()
        var thin = false
        var name = ""
        var labelled = false
        var template: String? = null
        var restStart = false; var restEnd = false
        var floorT = Float.NaN; var innerR = 0f
        var note = ""
        val t0 get() = prof[0][0]
        val t1 get() = prof[prof.size - 1][0]
        val tMin: Float get() { var v = Float.MAX_VALUE; for (p in prof) v = min(v, p[0]); return v }
        val tMax: Float get() { var v = -Float.MAX_VALUE; for (p in prof) v = max(v, p[0]); return v }
        fun rMax(): Float { var r = 0f; for (p in prof) r = max(r, p[1]); return r }
        fun height() = tMax - tMin
        fun center() = ax.at((tMin + tMax) / 2)
        fun colorAt(t: Float): Int {
            if (bands.size <= 1) return color
            var best = color; var bd = Float.MAX_VALUE
            for (b in bands) { val d = if (t < b[0]) b[0] - t else if (t > b[1]) t - b[1] else 0f; if (d < bd) { bd = d; best = java.lang.Float.floatToRawIntBits(b[2]) } }
            return best
        }
    }

    private fun axial(fit: FitResult, i: Int, d: FloatArray) = abs(fit.nx[i] * d[0] + fit.ny[i] * d[1] + fit.nz[i] * d[2])

    /** Two-colour split of a set of points; returns the bigger group's colour and its members. */
    private fun twoMeans(fit: FitResult, idx: List<Int>): Pair<Int, List<Int>> {
        var a = fit.col[idx[0]]
        var b = idx.maxByOrNull { cdist(fit.col[it], a) }.let { fit.col[it!!] }
        var ga: List<Int> = idx; var gb: List<Int> = emptyList()
        repeat(4) {
            ga = idx.filter { cdist(fit.col[it], a) <= cdist(fit.col[it], b) }; gb = idx.filter { cdist(fit.col[it], a) > cdist(fit.col[it], b) }
            if (ga.isNotEmpty()) a = dominantColor(fit.col, ga)
            if (gb.isNotEmpty()) b = dominantColor(fit.col, gb)
        }
        return if (ga.size >= gb.size) Pair(a, ga) else Pair(b, gb)
    }

    /** Radius along the axis as a short polyline of (t, radius), measured from the points. */
    private fun profile(fit: FitResult, ax: Axis, level: Int, used: BooleanArray): Prof? {
        val (e1, e2) = basis(ax.d)
        val bin = 0.01f
        val lo = ax.t0 - 0.06f; val hi = ax.t1 + 0.06f
        val nb = ((hi - lo) / bin).toInt() + 1
        if (nb < 2 || nb > 2000) return null
        val cI = ArrayList<Int>(); val cT = ArrayList<Float>(); val cR = ArrayList<Float>(); val cA = ArrayList<Float>()
        fun consider(i: Int) {
            val vx = fit.px[i] - ax.a[0]; val vy = fit.py[i] - ax.a[1]; val vz = fit.pz[i] - ax.a[2]
            val t = vx * ax.d[0] + vy * ax.d[1] + vz * ax.d[2]
            if (t < lo || t > hi) return
            val u = vx * e1[0] + vy * e1[1] + vz * e1[2]; val v = vx * e2[0] + vy * e2[1] + vz * e2[2]
            val rho = sqrt(u * u + v * v)
            if (rho > 0.6f) return
            cI.add(i); cT.add(t); cR.add(rho); cA.add(atan2(v, u))
        }
        val pool = ax.pool
        if (pool != null) { for (i in pool) if (!used[i]) consider(i) }
        else for (i in fit.px.indices) {
            val o = fit.owner[i]
            // Only points nobody else explains, this axis' own discs and tubes: never another
            // object's legs or faces.
            val ok = (o == -1 && !used[i]) || o in ax.discs || (o == FitResult.ROUND && fit.tubeOf[i]?.let { it in ax.tubeSet } == true)
            if (ok) consider(i)
        }
        // Rings can be wider than the shape the axis came from (a plate under a candle): widen
        // the search while rings keep touching its edge.
        var reach = ax.rMax * 1.25f + 0.01f
        val rb = FloatArray(nb)
        var members = Array(nb) { ArrayList<Int>() }
        for (iter in 0 until 4) {
            members = Array(nb) { ArrayList<Int>() }
            for (q in cI.indices) if (cR[q] <= reach) members[((cT[q] - lo) / bin).toInt().coerceIn(0, nb - 1)].add(q)
            var cut = 0
            for (b in 0 until nb) {
                rb[b] = -1f
                val l = members[b]
                if (l.size < 8) continue
                val sect = BooleanArray(36)
                for (q in l) sect[(((cA[q] + Math.PI) / (2 * Math.PI) * 36).toInt()).coerceIn(0, 35)] = true
                if (sect.count { it } < 6) continue
                val side = ArrayList<Float>(); val all = ArrayList<Float>(l.size)
                for (q in l) { all.add(cR[q]); if (axial(fit, cI[q], ax.d) < 0.5f) side.add(cR[q]) }
                // A wall is measured from the points facing sideways; a flat end, or a wider sloping
                // surface around something narrow (a plate under a candle), from all of them.
                all.sort(); side.sort()
                val sideTop = if (side.size >= 8) side[(side.size * 0.97f).toInt().coerceAtMost(side.size - 1)] else 0f
                val wider = all.count { it > sideTop * 1.3f + 0.004f }
                val use = if (side.size >= 8 && (wider < 8 || wider < 0.15f * all.size)) side else all
                val p97 = use[(use.size * 0.97f).toInt().coerceAtMost(use.size - 1)]
                val p50 = use[use.size / 2]
                // A wall: its middle. A flat end: its rim (97% of an evenly covered disc lies within 98.5% of the radius).
                rb[b] = if (p50 > 0.8f * p97) p50 else p97 / 0.985f
                if (p97 > 0.85f * reach) cut++
            }
            if (cut == 0 || reach >= 0.6f) break
            reach = min(0.6f, reach * 1.6f)
        }
        val tLo = FloatArray(nb) { Float.MAX_VALUE }; val tHi = FloatArray(nb) { -Float.MAX_VALUE }
        for (b in 0 until nb) for (q in members[b]) { if (cT[q] < tLo[b]) tLo[b] = cT[q]; if (cT[q] > tHi[b]) tHi[b] = cT[q] }
        // The run of rings that contains the seed range, bridging gaps of up to two bins.
        var s = ((ax.t0 + ax.t1) / 2 - lo).div(bin).toInt().coerceIn(0, nb - 1)
        if (rb[s] < 0f) { var k = 1; while (k < nb && rb.getOrElse(s + k) { -1f } < 0f && rb.getOrElse(s - k) { -1f } < 0f) k++; s = if (rb.getOrElse(s + k) { -1f } >= 0f) s + k else s - k }
        if (s !in 0 until nb || rb[s] < 0f) return null
        var b0 = s; var b1 = s
        while (true) { var k = b0 - 1; while (k >= 0 && b0 - k <= 3 && rb[k] < 0f) k--; if (k >= 0 && b0 - k <= 3 && rb[k] >= 0f) b0 = k else break }
        while (true) { var k = b1 + 1; while (k < nb && k - b1 <= 3 && rb[k] < 0f) k++; if (k < nb && k - b1 <= 3 && rb[k] >= 0f) b1 = k else break }
        val filled = BooleanArray(nb)
        for (b in b0..b1) if (rb[b] < 0f) { var p = b - 1; while (rb[p] < 0f) p--; var q = b + 1; while (rb[q] < 0f) q++; rb[b] = rb[p] + (rb[q] - rb[p]) * (b - p) / (q - p); filled[b] = true }
        val m = b1 - b0 + 1
        if (m < 2) return null
        // Median of three removes single-bin spikes without rounding real steps.
        val sm = FloatArray(m) { k -> val a = rb[b0 + max(0, k - 1)]; val c = rb[b0 + k]; val e = rb[b0 + min(m - 1, k + 1)]; max(min(a, c), min(max(a, c), e)) }
        val tStart = max(lo + b0 * bin, tLo[b0]); val tEnd = min(lo + (b1 + 1) * bin, tHi[b1])
        // Per-ring colour and flat-layer statistics.
        val bn = IntArray(m) { members[b0 + it].size }
        val bt = FloatArray(m); val bc = IntArray(m); val bup = FloatArray(m); val bupT = FloatArray(m) { Float.NaN }; val bupR = FloatArray(m); val bupC = IntArray(m)
        for (q in 0 until m) {
            val b = b0 + q
            bt[q] = lo + (b + 0.5f) * bin
            val l = members[b]
            if (l.isEmpty()) { bc[q] = if (q > 0) bc[q - 1] else ax.color; continue }
            val outer = l.filter { cR[it] >= 0.75f * sm[q] }.map { cI[it] }
            bc[q] = dominantColor(fit.col, if (outer.size >= 4) outer else l.map { cI[it] })
            val ups = l.filter { axial(fit, cI[it], ax.d) > 0.8f }
            bup[q] = ups.size.toFloat() / l.size
            if (ups.size >= 6) {
                val ts = ups.map { cT[it] }.sorted(); bupT[q] = ts[ts.size / 2]
                val (c, grp) = twoMeans(fit, ups.map { cI[it] })
                bupC[q] = c
                val set = grp.toHashSet()
                val rs = ups.filter { cI[it] in set }.map { cR[it] }.sorted()
                bupR[q] = rs[(rs.size * 0.97f).toInt().coerceAtMost(rs.size - 1)] / 0.985f
            }
        }
        ax.t0 = tStart; ax.t1 = tEnd
        // These points are now explained by the solid.
        for (q in cI.indices) {
            val t = cT[q]
            if (t < ax.t0 - 0.01f || t > ax.t1 + 0.01f) continue
            val k = ((t - bt[0]) / bin).roundToInt().coerceIn(0, m - 1)
            if (cR[q] < sm[k] + 0.02f) used[cI[q]] = true
        }
        return Prof(tStart, tEnd, bin, bn, bt, sm, bc, bup, bupT, bupR, bupC)
    }

    /**
     * One object's outline from its own rings: simplified with a tolerance that suits its own
     * size, straight runs made exactly straight, abrupt changes made square steps, and (high
     * poly) curved stretches given extra rings on a smooth curve.
     */
    private fun shape(pts: ArrayList<FloatArray>, level: Int, bin: Float): ArrayList<FloatArray> {
        if (pts.size <= 2) return pts
        var rTop = 0f
        for (v in pts) rTop = max(rTop, v[1])
        val tol = max(floatArrayOf(0.006f, 0.003f, 0.0015f)[level], floatArrayOf(0.06f, 0.025f, 0.008f)[level] * rTop)
        // A long part that is a straight cone or cylinder within the noise stays exactly that at
        // every level (legs, rods): more rings would only trace the noise.
        val len = pts[pts.size - 1][0] - pts[0][0]
        if (len > 3f * rTop) {
            var st = 0.0; var sr = 0.0; var stt = 0.0; var str = 0.0
            for (q in pts) { st += q[0]; sr += q[1]; stt += q[0].toDouble() * q[0]; str += q[0].toDouble() * q[1] }
            val nn = pts.size.toDouble(); val den = nn * stt - st * st
            if (abs(den) > 1e-12) {
                val k = (nn * str - st * sr) / den; val c0 = (sr - k * st) / nn
                var worst = 0.0
                for (q in pts) worst = max(worst, abs(q[1] - (c0 + k * q[0])))
                if (worst < max(0.002, 0.15 * rTop)) {
                    val t0 = pts[0][0]; val t1 = pts[pts.size - 1][0]
                    var r0 = (c0 + k * t0).toFloat(); var r1 = (c0 + k * t1).toFloat()
                    // a taper smaller than the noise is no taper: an exact cylinder
                    if (abs(r1 - r0) < max(0.0015f, 0.04f * rTop)) { val r = pts.map { it[1] }.sorted()[pts.size / 2]; r0 = r; r1 = r }
                    return arrayListOf(floatArrayOf(t0, r0), floatArrayOf(t1, r1))
                }
            }
        }
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        simplify(pts, 0, pts.size - 1, tol, keep)
        var out = ArrayList<FloatArray>()
        for (k in pts.indices) if (keep[k]) out.add(pts[k].copyOf())
        val straight = BooleanArray(out.size)
        for (k in 0 until out.size - 1) {
            val p = out[k]; val q = out[k + 1]
            val dt = q[0] - p[0]; val dr = q[1] - p[1]
            if (abs(dr) < 0.12f * abs(dt) || abs(dr) < tol * 0.75f) {
                val vals = pts.filter { it[0] >= p[0] && it[0] <= q[0] }.map { it[1] }.sorted()
                val r = if (vals.isEmpty()) (p[1] + q[1]) / 2 else vals[vals.size / 2]
                p[1] = r; q[1] = r; straight[k] = true
            }
        }
        var k = 0
        while (k < out.size - 1) {
            val p = out[k]; val q = out[k + 1]
            if (q[0] - p[0] < 2.6f * bin && abs(q[1] - p[1]) > 0.8f * (q[0] - p[0]) && k > 0 && k + 1 < out.size - 1) {
                val t = (p[0] + q[0]) / 2; p[0] = t; q[0] = t; straight[k] = true
            }
            k++
        }
        if (level >= 2 && out.size >= 4) {
            val dense = ArrayList<FloatArray>()
            for (j in 0 until out.size - 1) {
                dense.add(out[j])
                if (straight[j] || j == 0 || j + 2 >= out.size || straight.getOrElse(j - 1) { true } || straight.getOrElse(j + 1) { true }) continue
                val p0 = out[j - 1]; val p1 = out[j]; val p2 = out[j + 1]; val p3 = out[j + 2]
                for (s2 in 1..2) {
                    val u = s2 / 3f; val u2 = u * u; val u3 = u2 * u
                    fun cr(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3)
                    dense.add(floatArrayOf(cr(p0[0], p1[0], p2[0], p3[0]), max(0.001f, cr(p0[1], p1[1], p2[1], p3[1]))))
                }
            }
            dense.add(out[out.size - 1])
            out = dense
        }
        var j = 1
        while (j < out.size - 1) {
            val a = out[j - 1]; val b = out[j]; val c = out[j + 1]
            val same = abs(a[1] - b[1]) < 1e-5f && abs(b[1] - c[1]) < 1e-5f
            val dup = abs(a[0] - b[0]) < 1e-5f && abs(a[1] - b[1]) < 1e-5f
            if (same || dup) out.removeAt(j) else j++
        }
        return out
    }

    private fun radiusAt(p: List<FloatArray>, t: Float): Float {
        if (t <= p[0][0]) return p[0][1]
        for (k in 0 until p.size - 1) if (t <= p[k + 1][0]) {
            val d = p[k + 1][0] - p[k][0]
            return if (d < 1e-6f) max(p[k][1], p[k + 1][1]) else p[k][1] + (p[k + 1][1] - p[k][1]) * (t - p[k][0]) / d
        }
        return p[p.size - 1][1]
    }

    /** Douglas-Peucker on (t, radius). */
    private fun simplify(p: List<FloatArray>, a: Int, b: Int, tol: Float, keep: BooleanArray) {
        if (b - a < 2) return
        val ax = p[a][0]; val ay = p[a][1]; val bx = p[b][0]; val by = p[b][1]
        val l = max(1e-9f, sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)))
        var worst = -1; var wd = tol
        for (k in a + 1 until b) {
            val d = abs((p[k][0] - ax) * (by - ay) - (p[k][1] - ay) * (bx - ax)) / l
            if (d > wd) { wd = d; worst = k }
        }
        if (worst < 0) return
        keep[worst] = true
        simplify(p, a, worst, tol, keep); simplify(p, worst, b, tol, keep)
    }

    private fun slice(out: List<FloatArray>, ta: Float, tb: Float): ArrayList<FloatArray> {
        val r = ArrayList<FloatArray>()
        r.add(floatArrayOf(ta, radiusAt(out, ta + 1e-4f)))
        for (q in out) if (q[0] > ta + 1e-4f && q[0] < tb - 1e-4f) r.add(q.copyOf())
        r.add(floatArrayOf(tb, radiusAt(out, tb - 1e-4f)))
        return r
    }

    /**
     * Splits one turned profile into the separate objects stacked on it (table top, mat, plate,
     * candle…). A change of colour becomes a cut only where the shape also breaks there (a
     * step in the radius) or where it is a thin flat layer; otherwise it is one object in two
     * colours (an hourglass).
     */
    private fun split(ax: Axis, p: Prof, level: Int): ArrayList<Part> {
        val m = p.bt.size
        class Run(var s: Int, var e: Int) { val len get() = e - s + 1 }
        // The colour of the ring with the most points: a dominant colour, never a blend.
        fun runColor(r: Run): Int = p.bc[(r.s..r.e).maxByOrNull { p.bn[it] }!!]
        fun runR(r: Run): Float { val v = (r.s..r.e).map { p.br[it] }.sorted(); return v[v.size / 2] }
        val runs = ArrayList<Run>()
        for (k in 0 until m) {
            val last = runs.lastOrNull()
            if (last != null && cdist(runColor(last), p.bc[k]) < COLOR_T) last.e = k else runs.add(Run(k, k))
        }
        fun thin(i: Int): Boolean {
            val r = runs[i]
            if (r.len > 2) return false
            var up = 0f; var anyT = false
            for (k in r.s..r.e) { up += p.bup[k]; if (!p.bupT[k].isNaN()) anyT = true }
            if (up / r.len < 0.55f || !anyT) return false
            // a mat is wider than what stands on it
            val above = runs.getOrNull(i + 1) ?: return false
            return runR(r) > 1.25f * runR(above)
        }
        var changed = true
        while (changed && runs.size > 1) {
            changed = false
            for (i in runs.indices) {
                if (runs[i].len != 1 || thin(i)) continue
                // A lone ring joins a neighbour, never a thin layer (a mat stays a mat).
                val a = runs.getOrNull(i - 1)?.takeIf { !thin(i - 1) }; val b = runs.getOrNull(i + 1)?.takeIf { !thin(i + 1) }
                if (a == null && b == null) continue
                val c = runColor(runs[i])
                val into = if (a == null) b!! else if (b == null) a else if (cdist(runColor(a), c) <= cdist(runColor(b), c)) a else b
                into.s = min(into.s, runs[i].s); into.e = max(into.e, runs[i].e)
                runs.removeAt(i); changed = true; break
            }
            if (changed) continue
            for (i in 0 until runs.size - 1) {
                if (thin(i) || thin(i + 1)) continue
                if (cdist(runColor(runs[i]), runColor(runs[i + 1])) < COLOR_T) { runs[i].e = runs[i + 1].e; runs.removeAt(i + 1); changed = true; break }
            }
        }
        // Group runs into parts.
        val groups = ArrayList<MutableList<Int>>(); groups.add(mutableListOf(0))
        val cutAt = ArrayList<Float>()
        for (i in 0 until runs.size - 1) {
            val tb = p.bt[runs[i].e] + p.bin / 2
            // the radius jumps where one object ends and the next begins
            val ja = p.br[runs[i].e]; val jb = p.br[runs[i + 1].s]
            val step = abs(ja - jb) > max(0.006f, 0.2f * min(ja, jb))
            val ra = runR(runs[i]); val rbb = runR(runs[i + 1])
            val cut = thin(i) || thin(i + 1) || step || max(ra, rbb) > 1.25f * min(ra, rbb)
            if (cut) { cutAt.add(tb); groups.add(mutableListOf(i + 1)) } else groups.last().add(i + 1)
        }
        val bounds = FloatArray(groups.size + 1)
        bounds[0] = p.tStart; bounds[groups.size] = p.tEnd
        for (g in 1 until groups.size) bounds[g] = cutAt[g - 1]
        val isThin = BooleanArray(groups.size) { g -> groups[g].size == 1 && thin(groups[g][0]) }
        val thinTop = FloatArray(groups.size)
        for (g in groups.indices) if (isThin[g]) {
            val r = runs[groups[g][0]]
            val k = (r.s..r.e).filter { !p.bupT[it].isNaN() }.maxByOrNull { p.bup[it] }!!
            thinTop[g] = p.bupT[k]
            bounds[g] = thinTop[g] - THIN; bounds[g + 1] = thinTop[g]
        }
        val parts = ArrayList<Part>()
        for (g in groups.indices) {
            val ta = bounds[g]; val tb = bounds[g + 1]
            if (tb - ta < 1e-4f) continue
            val rs = groups[g].map { runs[it] }
            if (isThin[g]) {
                val r = rs[0]
                val k = (r.s..r.e).filter { !p.bupT[it].isNaN() }.maxByOrNull { p.bup[it] }!!
                val rad = if (p.bupR[k] > 0f) p.bupR[k] else runR(r)
                parts.add(Part(ax, arrayListOf(floatArrayOf(ta, rad), floatArrayOf(tb, rad)), p.bupC[k]).also { it.thin = true })
                continue
            }
            // Dominant colour: the band covering most surface (length × radius).
            val main = rs.maxByOrNull { it.len * runR(it) }!!
            // Its own rings only: neighbours' radii never leak in at the joins.
            val ks = rs.first().s; val ke = rs.last().e
            val pts = ArrayList<FloatArray>()
            pts.add(floatArrayOf(ta, p.br[ks]))
            for (k in ks..ke) if (p.bt[k] > ta + 1e-4f && p.bt[k] < tb - 1e-4f) pts.add(floatArrayOf(p.bt[k], p.br[k]))
            pts.add(floatArrayOf(tb, p.br[ke]))
            val part = Part(ax, shape(pts, level, p.bin), runColor(main))
            if (rs.size > 1) part.bands = rs.map { floatArrayOf(p.bt[it.s] - p.bin / 2, p.bt[it.e] + p.bin / 2, java.lang.Float.intBitsToFloat(runColor(it))) }
            parts.add(part)
        }
        for (i in 0 until parts.size - 1) { parts[i].restEnd = true; parts[i + 1].restStart = true }
        return parts
    }

    private const val THIN = 0.003f

    // ------------------------------------------------------------------ names and templates

    private fun kindName(part: Part): String {
        val h = part.height(); val r = part.rMax(); val pr = part.prof
        val two = pr.size == 2
        val plain = two && abs(pr[0][1] - pr[1][1]) < 1e-5f
        return when {
            part.thin || h <= 0.008f -> "Disco sottile"
            h < 0.35f * r -> "Disco"
            h > 5f * r -> if (part.restStart || part.restEnd || !plain) "Gamba" else "Asta"
            plain -> "Cilindro"
            two -> "Tronco di cono"
            else -> "Solido tornito"
        }
    }

    /** Things with no axis and no straight sides: modelled as a measured dome over their footprint. */
    private val FREEFORM = listOf("mouse", "topo", "telecomando", "controller", "gamepad", "joypad", "joystick", "cuffi", "auricolar",
        "scarp", "ciabatt", "sasso", "pietra", "ciottol", "saponett", "spugn", "peluche", "pupazz", "cuscin", "borsa", "zaino",
        "portafogli", "astucci", "banana", "patata", "pane", "cornett", "croissant", "guant", "casco", "trackball", "caricabatt", "powerbank")

    /** Object families whose usual shape the scan cannot resolve (a candle's hollow top…). */
    fun templateOf(name: String): String? {
        val h = name.lowercase()
        return when {
            FREEFORM.any { h.contains(it) } -> "freeform"
            h.contains("lattin") -> "can"
            h.contains("candel") || h.contains("cero") -> "candle"
            listOf("tovagliett", "sottopiatt", "centrin", "tappetin", "sottobicchier", "presina").any { h.contains(it) } -> "thin"
            listOf("piatt", "ciotol", "scodell", "tazz", "bicchier", "vaso", "vasett", "barattol", "coppa", "insalatier", "pentol", "secchi", "boccal", "portapenne", "barattolo").any { h.contains(it) } -> "container"
            else -> null
        }
    }

    private fun fits(type: String, part: Part): Boolean {
        val r = part.rMax(); val h = part.height(); val pr = part.prof
        val plain = pr.size == 2 && abs(pr[0][1] - pr[1][1]) < 1e-5f
        return when (type) {
            "can" -> plain && r in 0.02f..0.06f && h > 2f * r && h < 7f * r
            "candle" -> r in 0.01f..0.1f && h > 0.6f * r && h < 6f * r
            "thin" -> r > 0.03f
            "container" -> r in 0.015f..0.35f && h > 0.006f
            else -> false
        }
    }

    private fun chamfer(prof: ArrayList<FloatArray>, atStart: Boolean, frac: Float) {
        val n = prof.size
        if (n < 2) return
        val e = if (atStart) prof[0] else prof[n - 1]
        val nb = if (atStart) prof[1] else prof[n - 2]
        if (abs(e[1] - nb[1]) > 1e-4f) return                     // the scan already shows a shaped end
        val len = abs(nb[0] - e[0])
        val b = min((frac * e[1]).coerceIn(0.002f, 0.012f), len / 3)
        if (b < 0.0015f) return
        val t = e[0]; val r = e[1]
        if (atStart) { e[1] = r - b; prof.add(1, floatArrayOf(t + b, r)) } else { e[1] = r - b; prof.add(n - 1, floatArrayOf(t - b, r)) }
    }

    /** Rim chamfers on free ends, then the typical shape of the object's family. */
    private fun finish(part: Part, opt: SmartOptions, notes: ArrayList<String>) {
        val type = part.template
        val prof = part.prof
        if (opt.bevel && type != "container" && type != "thin" && !part.thin) {
            if (!part.restStart) chamfer(prof, true, 0.07f)
            if (!part.restEnd) chamfer(prof, false, 0.07f)
        }
        val label = part.name.lowercase()
        when (type) {
            "can" -> {
                if (!opt.bevel) { chamfer(prof, true, 0.1f); chamfer(prof, false, 0.1f) }
                val e = prof[prof.size - 1]
                val w = 0.06f * e[1]; val dp = 0.05f * e[1]
                prof.add(floatArrayOf(e[0], e[1] - w)); prof.add(floatArrayOf(e[0] - dp, e[1] - w - dp))
                notes.add("rifiniture tipiche di una lattina (bordo e coperchio incassato) aggiunte dal tipo di oggetto, non misurate")
            }
            "candle" -> {
                val r = part.rMax(); val e = prof[prof.size - 1]
                val rin = 0.45f * r; val depth = min(0.3f * r, 0.2f * part.height()).coerceAtLeast(0.003f)
                prof.add(floatArrayOf(e[0], rin)); prof.add(floatArrayOf(e[0] - depth, rin))
                part.note = "incavo in cima tipico di una candela, non misurato"
            }
            "thin" -> {
                val r = part.rMax(); val t1 = part.t1
                part.prof = arrayListOf(floatArrayOf(t1 - THIN, r), floatArrayOf(t1, r)); part.thin = true
                part.note = "spessore tipico di 3 mm"
            }
            "container" -> {
                val r = part.rMax()
                val shallow = part.height() < 0.4f * r
                val w = max(0.0025f, (if (shallow) 0.035f else 0.06f) * r)
                val floor = part.t0 + max(w, 0.003f)
                val top = prof[prof.size - 1]
                val inner = ArrayList<FloatArray>()
                inner.add(floatArrayOf(top[0], top[1] - w))
                for (k in prof.size - 2 downTo 1) {
                    val q = prof[k]
                    if (q[0] <= floor + 0.001f) break
                    val t = min(q[0], inner.last()[0])
                    inner.add(floatArrayOf(t, max(q[1] - w, 0.25f * r)))
                }
                inner.add(floatArrayOf(min(floor, inner.last()[0]), max(prof[0][1] - w, 0.25f * r)))
                prof.addAll(inner)
                part.floorT = floor; part.innerR = top[1] - w
                part.note = "interno cavo tipico di «$label» (spessore stimato)"
            }
        }
    }

    private fun lathe(b: Builder, surf: ArrayList<NurbsSurface>, part: Part, sides: Int, name: String) {
        val ax = part.ax; val prof = part.prof
        val (e1, e2) = basis(ax.d)
        val rings = ArrayList<IntArray>()
        for (p in prof) {
            val ring = IntArray(sides)
            val c = part.colorAt(p[0])
            for (i in 0 until sides) {
                val a = (2 * Math.PI * i / sides).toFloat(); val ca = cos(a) * p[1]; val sa = sin(a) * p[1]
                ring[i] = b.v(ax.a[0] + ax.d[0] * p[0] + e1[0] * ca + e2[0] * sa, ax.a[1] + ax.d[1] * p[0] + e1[1] * ca + e2[1] * sa, ax.a[2] + ax.d[2] * p[0] + e1[2] * ca + e2[2] * sa, c)
            }
            rings.add(ring)
        }
        // The profile may fold back (a recessed lid, a hollow): faces still look outward, by travel order.
        for (k in 0 until rings.size - 1) {
            val r0 = rings[k]; val r1 = rings[k + 1]
            for (i in 0 until sides) { val j = (i + 1) % sides; b.quad(r0[i], r0[j], r1[j], r1[i]) }
            val p0 = prof[k]; val p1 = prof[k + 1]
            surf.add(NurbsSurface.tube("REV${surf.size + 1}", dv(ax.at(p0[0])), dv(ax.at(p1[0])), p0[1].toDouble(), p1[1].toDouble(), part.color, dv(ax.d)))
        }
        b.ngons.add(IntArray(sides) { rings[0][sides - 1 - it] })
        surf.add(NurbsSurface.disc("CAP${surf.size + 1}", dv(ax.at(prof[0][0])), dv(floatArrayOf(-ax.d[0], -ax.d[1], -ax.d[2])), prof[0][1].toDouble(), part.color))
        b.ngons.add(rings[rings.size - 1].copyOf())
        val pl = prof[prof.size - 1]
        surf.add(NurbsSurface.disc("CAP${surf.size + 1}", dv(ax.at(pl[0])), dv(ax.d), pl[1].toDouble(), part.color))
        b.endGroup(name)
    }

    // ------------------------------------------------------------------ objects found from their shape alone

    /** Connected groups (2 cm grid) of the points nothing has explained yet. */
    private fun components(fit: FitResult, used: BooleanArray): List<IntArray> =
        groups(fit, (fit.px.indices).filter { !used[it] && fit.owner[it] == -1 }).filter { it.size >= 150 }

    /** Connected groups (2 cm grid) of the given points. */
    private fun groups(fit: FitResult, idx: List<Int>): List<IntArray> {
        val cell = 0.02f; val inv = 1f / cell
        if (idx.isEmpty()) return emptyList()
        val grid = LongIntMap(idx.size)
        val cellOf = LongArray(idx.size)
        val keys = ArrayList<Long>()
        for ((q, i) in idx.withIndex()) {
            val k = LongIntMap.pack(floor(fit.px[i] * inv).toInt(), floor(fit.py[i] * inv).toInt(), floor(fit.pz[i] * inv).toInt())
            cellOf[q] = k
            if (grid.get(k) < 0) { grid.put(k, keys.size); keys.add(k) }
        }
        val label = IntArray(keys.size) { -1 }
        var nl = 0
        val stack = IntArray(keys.size)
        for (s in keys.indices) {
            if (label[s] >= 0) continue
            var sp = 0; stack[sp++] = s; label[s] = nl
            while (sp > 0) {
                val c = stack[--sp]; val k = keys[c]
                val x = LongIntMap.ux(k); val y = LongIntMap.uy(k); val z = LongIntMap.uz(k)
                for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                    val o = grid.get(LongIntMap.pack(x + dx, y + dy, z + dz))
                    if (o >= 0 && label[o] < 0) { label[o] = nl; stack[sp++] = o }
                }
            }
            nl++
        }
        val lists = Array(nl) { ArrayList<Int>() }
        for ((q, i) in idx.withIndex()) lists[label[grid.get(cellOf[q])]].add(i)
        return lists.map { it.toIntArray() }
    }

    /**
     * Upright turned objects that offered no cylinder to start from (an hourglass, a vase):
     * the sideways normals of such a shape all point at one vertical line, and around that line
     * every 1 cm ring has a single radius.
     */
    private fun uprightAxis(fit: FitResult, pts: IntArray): Axis? {
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0; var n = 0
        val dirs = BooleanArray(12)
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in pts) {
            y0 = min(y0, fit.py[i]); y1 = max(y1, fit.py[i])
            val hx = fit.nx[i]; val hz = fit.nz[i]
            val l = sqrt(hx * hx + hz * hz)
            if (l < 0.6f) continue
            val ux = (hx / l).toDouble(); val uz = (hz / l).toDouble()
            // line through the point along its normal: accumulate (I - u u^T)
            val m11 = 1 - ux * ux; val m12 = -ux * uz; val m22 = 1 - uz * uz
            a11 += m11; a12 += m12; a22 += m22
            b1 += m11 * fit.px[i] + m12 * fit.pz[i]; b2 += m12 * fit.px[i] + m22 * fit.pz[i]
            dirs[(((atan2(uz, ux) + Math.PI) / (2 * Math.PI) * 12).toInt()).coerceIn(0, 11)] = true
            n++
        }
        if (n < 60 || dirs.count { it } < 4 || y1 - y0 < 0.03f) return null
        val det = a11 * a22 - a12 * a12
        if (det < 1e-6 * n * n) return null
        val cx = ((b1 * a22 - b2 * a12) / det).toFloat(); val cz = ((a11 * b2 - a12 * b1) / det).toFloat()
        // Every ring must be round about that line.
        // Thin (5 mm) rings; within each, the radius may still change with height (a bulb), so
        // judge the spread around a straight-line trend.
        val bins = HashMap<Int, ArrayList<FloatArray>>()
        for (i in pts) {
            val hx = fit.nx[i]; val hz = fit.nz[i]
            if (hx * hx + hz * hz < 0.36f) continue
            val r = sqrt((fit.px[i] - cx) * (fit.px[i] - cx) + (fit.pz[i] - cz) * (fit.pz[i] - cz))
            bins.getOrPut(((fit.py[i] - y0) / 0.005f).toInt()) { ArrayList() }.add(floatArrayOf(fit.py[i], r))
        }
        var good = 0; var tot = 0; var rMax = 0f
        for ((_, l) in bins) {
            if (l.size < 8) continue
            tot++
            var sy = 0.0; var sr = 0.0; var syy = 0.0; var syr = 0.0
            for (q in l) { sy += q[0]; sr += q[1]; syy += q[0].toDouble() * q[0]; syr += q[0].toDouble() * q[1] }
            val nn = l.size.toDouble(); val den = nn * syy - sy * sy
            val k = if (abs(den) > 1e-12) (nn * syr - sy * sr) / den else 0.0
            val c0 = (sr - k * sy) / nn
            val res = l.map { (it[1] - (c0 + k * it[0])).toFloat() }.sorted()
            val med = (sr / nn).toFloat()
            val spread = res[res.size * 3 / 4] - res[res.size / 4]
            if (med > 0.005f && spread < max(0.004f, 0.12f * med)) good++
            val rs = l.map { it[1] }.sorted()
            rMax = max(rMax, rs[(rs.size * 0.95f).toInt().coerceAtMost(rs.size - 1)])
        }
        if (tot < 3 || good < 0.7f * tot || rMax > 0.5f) return null
        return Axis(floatArrayOf(cx, y0, cz), floatArrayOf(0f, 1f, 0f), 0f, y1 - y0, rMax, dominantColor(fit.col, pts.toList())).also { it.pool = pts }
    }

    /** A straight-sided object seen from above: outline, from y0 up to y1. */
    private class Prism(var poly: List<FloatArray>, var y0: Float, var y1: Float, val color: Int) {
        var name = ""; var labelled = false
        fun center(): FloatArray { var x = 0f; var z = 0f; for (p in poly) { x += p[0]; z += p[1] }; return floatArrayOf(x / poly.size, (y0 + y1) / 2, z / poly.size) }
        fun width(): Float { var a = Float.MAX_VALUE; var b = -Float.MAX_VALUE; for (p in poly) { a = min(a, p[0]); b = max(b, p[0]) }; return b - a }
        fun depth(): Float { var a = Float.MAX_VALUE; var b = -Float.MAX_VALUE; for (p in poly) { a = min(a, p[1]); b = max(b, p[1]) }; return b - a }
    }

    /** Outline of a set of points seen from above, as a simple polygon (x, z), or null. */
    private fun outline(fit: FitResult, idx: List<Int>, level: Int): List<FloatArray>? {
        val cell = 0.005f
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in idx) { x0 = min(x0, fit.px[i]); x1 = max(x1, fit.px[i]); z0 = min(z0, fit.pz[i]); z1 = max(z1, fit.pz[i]) }
        val pad = 4
        val w = ((x1 - x0) / cell).toInt() + 1 + 2 * pad; val h = ((z1 - z0) / cell).toInt() + 1 + 2 * pad
        if (w * h > 400_000 || w < 4 || h < 4) return null
        val occ = BooleanArray(w * h)
        for (i in idx) occ[(((fit.pz[i] - z0) / cell).toInt() + pad) * w + ((fit.px[i] - x0) / cell).toInt() + pad] = true
        // Close small gaps (2 cells), fill the inside from the outside in, open back.
        fun dilate(src: BooleanArray, r: Int): BooleanArray { val o = BooleanArray(src.size); for (y in 0 until h) for (x in 0 until w) if (src[y * w + x]) for (dy in -r..r) for (dx in -r..r) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) o[yy * w + xx] = true }; return o }
        fun erode(src: BooleanArray, r: Int): BooleanArray { val o = BooleanArray(src.size); for (y in r until h - r) for (x in r until w - r) { var all = true; loop@ for (dy in -r..r) for (dx in -r..r) if (!src[(y + dy) * w + x + dx]) { all = false; break@loop }; o[y * w + x] = all }; return o }
        val closed = erode(dilate(occ, 1), 1)
        val outside = BooleanArray(w * h)
        val st = IntArray(w * h); var sp = 0
        st[sp++] = 0; outside[0] = true
        while (sp > 0) { val c = st[--sp]; val x = c % w; val y = c / w
            for (k in 0 until 4) { val xx = x + intArrayOf(1, -1, 0, 0)[k]; val yy = y + intArrayOf(0, 0, 1, -1)[k]
                if (xx in 0 until w && yy in 0 until h) { val q = yy * w + xx; if (!outside[q] && !closed[q]) { outside[q] = true; st[sp++] = q } } } }
        val inside = BooleanArray(w * h) { !outside[it] }
        var area = 0; var band = 0
        for (k in inside.indices) { if (inside[k]) area++; if (occ[k]) band++ }
        if (area < 12) return null
        // Moore-neighbour boundary trace, clockwise in the grid (y down).
        val dx = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1); val dy = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
        var start = -1
        for (k in inside.indices) if (inside[k]) { start = k; break }
        val cont = ArrayList<Int>(); cont.add(start)
        var c = start; var back = 0
        var steps = 0
        while (steps++ < 4 * w * h) {
            var found = -1
            for (k in 1..8) { val d = (back + k) % 8; val xx = c % w + dx[d]; val yy = c / w + dy[d]; if (xx in 0 until w && yy in 0 until h && inside[yy * w + xx]) { found = d; break } }
            if (found < 0) break
            val pd = (found + 7) % 8
            val bx = c % w + dx[pd]; val by = c / w + dy[pd]
            c = (c / w + dy[found]) * w + c % w + dx[found]
            var nb = 0
            for (d in 0 until 8) if (c % w + dx[d] == bx && c / w + dy[d] == by) { nb = d; break }
            back = nb
            if (c == start && back == 0) break
            cont.add(c)
        }
        if (cont.size < 6) return null
        // Smooth the stair steps, push out by half a cell (the trace runs through cell centres), simplify.
        val n = cont.size
        val raw = Array(n) { floatArrayOf(x0 + (cont[it] % w - pad + 0.5f) * cell, z0 + (cont[it] / w - pad + 0.5f) * cell) }
        val sm = Array(n) { k -> val a = raw[(k + n - 1) % n]; val b = raw[k]; val d = raw[(k + 1) % n]; floatArrayOf((a[0] + 2 * b[0] + d[0]) / 4, (a[1] + 2 * b[1] + d[1]) / 4) }
        var areaS = 0f
        for (k in 0 until n) { val a = sm[k]; val b = sm[(k + 1) % n]; areaS += a[0] * b[1] - b[0] * a[1] }
        val sgn = if (areaS > 0) 1f else -1f
        val pushed = Array(n) { k ->
            val a = sm[(k + n - 1) % n]; val b = sm[(k + 1) % n]
            var tx = b[0] - a[0]; var tz = b[1] - a[1]; val l = max(1e-6f, sqrt(tx * tx + tz * tz)); tx /= l; tz /= l
            // The traced cells are the outermost ones touched by the noisy wall: the wall itself
            // runs about 0.7 cell further in.
            val inward = -0.7f * cell
            floatArrayOf(sm[k][0] + sgn * tz * inward, sm[k][1] - sgn * tx * inward)
        }
        // Never coarser than a small share of the object's own thinnest side (a 2 cm arm stays an arm).
        val tol = min(floatArrayOf(0.01f, 0.005f, 0.0025f)[level.coerceIn(0, 2)], floatArrayOf(0.08f, 0.05f, 0.03f)[level.coerceIn(0, 2)] * min(x1 - x0, z1 - z0))
        // closed Douglas-Peucker: split at the point farthest from the first
        var far = 0; var fd = 0f
        for (k in 0 until n) { val d = (pushed[k][0] - pushed[0][0]).let { it * it } + (pushed[k][1] - pushed[0][1]).let { it * it }; if (d > fd) { fd = d; far = k } }
        val seq = (0..n).map { pushed[it % n] }
        val keep = BooleanArray(seq.size); keep[0] = true; keep[far] = true; keep[n] = true
        simplify(seq, 0, far, tol, keep); simplify(seq, far, n, tol, keep)
        val poly = (0 until n).filter { keep[it] }.map { seq[it] }
        return if (poly.size >= 3) poly else null
    }

    /**
     * Straight-sided objects (a light bar on its base): the outline seen from above, extruded,
     * one layer per height band where the outline stays the same.
     */
    private fun prisms(fit: FitResult, pts: IntArray, level: Int): List<Prism>? {
        var side = 0
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in pts) { if (abs(fit.ny[i]) < 0.5f) side++; y0 = min(y0, fit.py[i]); y1 = max(y1, fit.py[i]) }
        if (side < 0.35f * pts.size || y1 - y0 < 0.02f) return null
        val bin = 0.01f
        val nb = ((y1 - y0) / bin).toInt() + 1
        val members = Array(nb) { ArrayList<Int>() }
        for (i in pts) members[((fit.py[i] - y0) / bin).toInt().coerceIn(0, nb - 1)].add(i)
        val wb = FloatArray(nb) { -1f }; val db = FloatArray(nb) { -1f }
        for (b in 0 until nb) {
            val l = members[b]; if (l.size < 6) continue
            val xs = l.map { fit.px[it] }.sorted(); val zs = l.map { fit.pz[it] }.sorted()
            wb[b] = xs[(xs.size * 0.98f).toInt().coerceAtMost(xs.size - 1)] - xs[(xs.size * 0.02f).toInt()]
            db[b] = zs[(zs.size * 0.98f).toInt().coerceAtMost(zs.size - 1)] - zs[(zs.size * 0.02f).toInt()]
        }
        // Layers: a new one starts where the footprint changes clearly and stays changed.
        val layers = ArrayList<IntArray>()
        var s = -1; var mw = 0f; var md = 0f
        for (b in 0 until nb) {
            if (wb[b] < 0f) continue
            if (s < 0) { s = b; mw = wb[b]; md = db[b]; continue }
            fun differs(k: Int) = wb[k] >= 0f && (abs(wb[k] - mw) > max(0.015f, 0.25f * mw) || abs(db[k] - md) > max(0.015f, 0.25f * md))
            if (differs(b) && (b + 1 >= nb || differs(b + 1) || wb[b + 1] < 0f)) {
                layers.add(intArrayOf(s, b - 1)); s = b; mw = wb[b]; md = db[b]
            }
        }
        if (s >= 0) layers.add(intArrayOf(s, nb - 1))
        // A one-bin layer is a transition, not an object: give it to the neighbour below.
        var i = 0
        while (i < layers.size) { if (layers.size > 1 && layers[i][1] - layers[i][0] < 1) { if (i > 0) layers[i - 1][1] = layers[i][1] else layers[1][0] = layers[0][0]; layers.removeAt(i) } else i++ }
        val out = ArrayList<Prism>()
        for ((k, l) in layers.withIndex()) {
            val idx = ArrayList<Int>()
            for (b in l[0]..l[1]) idx.addAll(members[b])
            if (idx.size < 20) continue
            val poly = outline(fit, idx, level) ?: continue
            val ya = if (k == 0) y0 else y0 + l[0] * bin
            val yb = if (k == layers.size - 1) y1 else y0 + (l[1] + 1) * bin
            out.add(Prism(poly, ya, yb, dominantColor(fit.col, idx)))
        }
        return out.ifEmpty { null }
    }

    private fun prism(b: Builder, surf: ArrayList<NurbsSurface>, p: Prism, name: String) {
        var poly = p.poly
        // Counter-clockwise seen from above (x right, z towards the viewer means clockwise in x,z).
        var a = 0f
        for (k in poly.indices) { val u = poly[k]; val v = poly[(k + 1) % poly.size]; a += u[0] * v[1] - v[0] * u[1] }
        if (a > 0f) poly = poly.reversed()
        val n = poly.size
        val bot = IntArray(n) { b.v(poly[it][0], p.y0, poly[it][1], p.color) }
        val top = IntArray(n) { b.v(poly[it][0], p.y1, poly[it][1], p.color) }
        for (k in 0 until n) {
            val j = (k + 1) % n
            b.quad(bot[k], bot[j], top[j], top[k])
            val mx = (poly[k][0] + poly[j][0]) / 2; val mz = (poly[k][1] + poly[j][1]) / 2
            var ux = poly[j][0] - poly[k][0]; var uz = poly[j][1] - poly[k][1]; val l = max(1e-6f, sqrt(ux * ux + uz * uz)); ux /= l; uz /= l
            surf.add(NurbsSurface.rect("SIDE${surf.size + 1}", doubleArrayOf(mx.toDouble(), ((p.y0 + p.y1) / 2).toDouble(), mz.toDouble()),
                doubleArrayOf(ux.toDouble(), 0.0, uz.toDouble()), doubleArrayOf(0.0, 1.0, 0.0), (l / 2).toDouble(), ((p.y1 - p.y0) / 2).toDouble(), p.color))
        }
        b.ngons.add(top.copyOf())
        b.ngons.add(bot.reversedArray())
        b.endGroup(name)
    }

    // ------------------------------------------------------------------ free-form objects

    /**
     * Something with no axis and no straight sides (a mouse, a remote, a stone): the footprint
     * seen from above, as a radius every 360/M degrees around its centre, and a dome of measured
     * heights over it in K rings. Mirror-symmetric shapes are made exactly symmetric.
     */
    private class Blob(val cx: Float, val cz: Float, val ux: Float, val uz: Float, val y0: Float,
                       val rad: FloatArray, val h: Array<FloatArray>, val hc: Float, val color: Int, val sym: Boolean) {
        var name = ""; var labelled = false
        fun dir(j: Int): FloatArray { val a = 2.0 * Math.PI * j / rad.size; val c = cos(a).toFloat(); val s = sin(a).toFloat(); return floatArrayOf(ux * c - uz * s, uz * c + ux * s) }
        fun height(): Float { var t = hc; for (r in h) for (v in r) t = max(t, v); return t }
        fun scale(k: Int) = ringScale(k, h.size)
        fun center() = floatArrayOf(cx, y0 + height() / 2, cz)
        private fun extent(c: Boolean): Float {
            var lo = 0f; var hi = 0f
            for (j in rad.indices) { val a = 2.0 * Math.PI * j / rad.size; val q = rad[j] * (if (c) cos(a) else sin(a)).toFloat(); lo = min(lo, q); hi = max(hi, q) }
            return hi - lo
        }
        fun length() = extent(true)
        fun width() = extent(false)
    }

    /** Rings crowd toward the edge, where a rounded object curves most. */
    private fun ringScale(k: Int, K: Int) = cos(k.toDouble() / K * Math.PI / 2).toFloat()

    /** Height of the face something stands on: the highest horizontal face just under it, or its own lowest points. */
    private fun support(fit: FitResult, pts: IntArray, cx: Float, cz: Float): Float {
        val ys = FloatArray(pts.size) { fit.py[pts[it]] }; ys.sort()
        val low = ys[(ys.size * 0.02f).toInt()]
        var best = -Float.MAX_VALUE
        for (p in fit.planes) {
            if (abs(p.n[1]) < 0.9f) continue
            val py = p.c[1]
            if (py > low + 0.012f || py < low - 0.04f) continue
            val x = cx - p.c[0]; val z = cz - p.c[2]
            val under = if (p.disc) x * x + z * z < (p.radius + 0.02f) * (p.radius + 0.02f)
                else abs(x * p.u[0] + z * p.u[2]) < p.hu + 0.02f && abs(x * p.v[0] + z * p.v[2]) < p.hv + 0.02f
            if (under && py > best) best = py
        }
        return if (best > -Float.MAX_VALUE) best else low
    }

    private fun blob(fit: FitResult, comp: IntArray, level: Int): Blob? {
        if (comp.size < 150) return null
        var mx = 0f; var mz = 0f
        for (i in comp) { mx += fit.px[i]; mz += fit.pz[i] }
        mx /= comp.size; mz /= comp.size
        val y0 = support(fit, comp, mx, mz)
        val idx = comp.filter { fit.py[it] > y0 + 0.004f }
        if (idx.size < 100) return null
        val ys = idx.map { fit.py[it] - y0 }.sorted()
        val hTop = ys[(ys.size * 0.98f).toInt().coerceAtMost(ys.size - 1)]
        if (hTop < 0.008f) return null
        val poly = outline(fit, idx, 2) ?: return null
        // centre of the footprint and its long direction
        var a2 = 0f; var gx = 0f; var gz = 0f
        for (k in poly.indices) { val p = poly[k]; val q = poly[(k + 1) % poly.size]; val cr = p[0] * q[1] - q[0] * p[1]; a2 += cr; gx += (p[0] + q[0]) * cr; gz += (p[1] + q[1]) * cr }
        if (abs(a2) < 1e-6f) return null
        val cx = gx / (3 * a2); val cz = gz / (3 * a2)
        var sxx = 0.0; var szz = 0.0; var sxz = 0.0
        for (i in idx) { val x = fit.px[i] - cx; val z = fit.pz[i] - cz; sxx += x * x; szz += z * z; sxz += x * z }
        val th = 0.5 * atan2(2 * sxz, sxx - szz)
        val ux = cos(th).toFloat(); val uz = sin(th).toFloat()
        val M = intArrayOf(8, 12, 24)[level]; val K = intArrayOf(2, 3, 5)[level]
        // footprint radius every 360/M degrees: farthest crossing of the outline
        val rad = FloatArray(M)
        for (j in 0 until M) {
            val a = 2.0 * Math.PI * j / M; val c = cos(a).toFloat(); val sn = sin(a).toFloat()
            val dx = ux * c - uz * sn; val dz = uz * c + ux * sn
            var best = -1f
            for (k in poly.indices) {
                val p = poly[k]; val q = poly[(k + 1) % poly.size]
                val ex = q[0] - p[0]; val ez = q[1] - p[1]
                val den = dx * ez - dz * ex
                if (abs(den) < 1e-9f) continue
                val wx = p[0] - cx; val wz = p[1] - cz
                val t = (wx * ez - wz * ex) / den; val u = (wx * dz - wz * dx) / den
                if (t > 0f && u >= 0f && u <= 1f) best = max(best, t)
            }
            if (best <= 0f) return null
            rad[j] = best
        }
        val meanR = rad.average().toFloat()
        // heights: high percentile of the points around each vertex
        val cell = 0.01f
        val grid = HashMap<Long, ArrayList<Int>>()
        for (i in idx) grid.getOrPut(LongIntMap.pack(floor(fit.px[i] / cell).toInt(), 0, floor(fit.pz[i] / cell).toInt())) { ArrayList() }.add(i)
        fun heightAt(x: Float, z: Float, r: Float, pct: Float): Float {
            val l = ArrayList<Float>()
            val n = (r / cell).toInt() + 1
            val bx = floor(x / cell).toInt(); val bz = floor(z / cell).toInt()
            for (dz in -n..n) for (dx in -n..n) {
                val cl = grid[LongIntMap.pack(bx + dx, 0, bz + dz)] ?: continue
                for (i in cl) { val ox = fit.px[i] - x; val oz = fit.pz[i] - z; if (ox * ox + oz * oz <= r * r) l.add(fit.py[i] - y0) }
            }
            if (l.size < 3) return Float.NaN
            l.sort()
            return l[(l.size * pct).toInt().coerceAtMost(l.size - 1)]
        }
        val h = Array(K) { FloatArray(M) }
        for (k in 0 until K) {
            // the rim is read just inside the outline, where the top of the side wall is
            val sc = if (k == 0) 0.985f else ringScale(k, K)
            val gap = meanR * (ringScale(max(0, k - 1), K) - ringScale(k + 1, K)) / 2
            for (j in 0 until M) {
                val a = 2.0 * Math.PI * j / M; val c = cos(a).toFloat(); val sn = sin(a).toFloat()
                val rr = rad[j] * sc
                val r = (0.4f * min(max(gap, 0.004f), (2 * Math.PI * rr / M).toFloat().coerceAtLeast(0.006f))).coerceIn(0.004f, 0.015f)
                h[k][j] = heightAt(cx + (ux * c - uz * sn) * rr, cz + (uz * c + ux * sn) * rr, r, if (k == 0) 0.85f else 0.6f)
            }
        }
        var hc = heightAt(cx, cz, (0.3f * meanR * ringScale(K - 1, K)).coerceIn(0.005f, 0.015f), 0.6f)
        // fill what was not seen from its neighbours
        repeat(12) {
            for (k in 0 until K) for (j in 0 until M) if (h[k][j].isNaN()) {
                var s = 0f; var n = 0
                for (v in floatArrayOf(h[k][(j + 1) % M], h[k][(j + M - 1) % M], if (k > 0) h[k - 1][j] else Float.NaN, if (k < K - 1) h[k + 1][j] else hc))
                    if (!v.isNaN()) { s += v; n++ }
                if (n > 0) h[k][j] = s / n
            }
            if (hc.isNaN()) { val l = h[K - 1].filter { !it.isNaN() }; if (l.isNotEmpty()) hc = l.average().toFloat() }
        }
        if (hc.isNaN()) hc = hTop
        for (k in 0 until K) for (j in 0 until M) if (h[k][j].isNaN()) h[k][j] = hc * (k + 1f) / (K + 1f)
        // mirror symmetry about the long direction
        var dr = 0f; var dh = 0f
        for (j in 0 until M) { val m = (M - j) % M; dr += abs(rad[j] - rad[m]); for (k in 0 until K) dh += abs(h[k][j] - h[k][m]) }
        val sym = dr / M < 0.1f * meanR && dh / (M * K) < max(0.004f, 0.15f * hTop)
        if (sym) for (j in 0..M / 2) {
            val m = (M - j) % M
            val r = (rad[j] + rad[m]) / 2; rad[j] = r; rad[m] = r
            for (k in 0 until K) { val v = (h[k][j] + h[k][m]) / 2; h[k][j] = v; h[k][m] = v }
        }
        // smooth: a little at low poly (noise only), more at high poly (rounder)
        repeat(intArrayOf(1, 1, 2)[level]) {
            // the outline only at high poly, where samples are close enough not to shorten the ends
            val nr = FloatArray(M) { j -> 0.7f * rad[j] + 0.15f * (rad[(j + 1) % M] + rad[(j + M - 1) % M]) }
            if (level == 2) nr.copyInto(rad)
            val nh = Array(K) { k -> FloatArray(M) { j ->
                val inner = if (k < K - 1) h[k + 1][j] else hc
                val outer = if (k > 0) h[k - 1][j] else h[k][j]
                if (k == 0) 0.6f * h[k][j] + 0.2f * (h[k][(j + 1) % M] + h[k][(j + M - 1) % M])
                else 0.5f * h[k][j] + 0.125f * (h[k][(j + 1) % M] + h[k][(j + M - 1) % M] + inner + outer)
            } }
            for (k in 0 until K) nh[k].copyInto(h[k])
        }
        for (k in 0 until K) for (j in 0 until M) h[k][j] = h[k][j].coerceIn(0.002f, hTop * 1.02f)
        return Blob(cx, cz, ux, uz, y0, rad, h, hc.coerceIn(0.002f, hTop * 1.02f), dominantColor(fit.col, idx), sym)
    }

    /** One face, turned to look away from [o]: triangle, quad or n-gon. */
    private fun outward(b: Builder, ids: IntArray, o: FloatArray) {
        var nx = 0f; var ny = 0f; var nz = 0f; var gx = 0f; var gy = 0f; var gz = 0f
        val p = b.pos
        for (k in ids.indices) {
            val a = ids[k] * 3; val c = ids[(k + 1) % ids.size] * 3
            nx += (p[a + 1] - p[c + 1]) * (p[a + 2] + p[c + 2]); ny += (p[a + 2] - p[c + 2]) * (p[a] + p[c]); nz += (p[a] - p[c]) * (p[a + 1] + p[c + 1])
            gx += p[a]; gy += p[a + 1]; gz += p[a + 2]
        }
        val n = ids.size
        val f = if ((gx / n - o[0]) * nx + (gy / n - o[1]) * ny + (gz / n - o[2]) * nz < 0f) ids.reversedArray() else ids
        when (f.size) { 3 -> b.tri(f[0], f[1], f[2]); 4 -> b.quad(f[0], f[1], f[2], f[3]); else -> b.ngons.add(f) }
    }

    private fun blobMesh(b: Builder, bl: Blob, level: Int, name: String) {
        val M = bl.rad.size; val K = bl.h.size; val c = bl.color
        val o = floatArrayOf(bl.cx, bl.y0 + 0.4f * bl.height(), bl.cz)
        fun at(j: Int, sc: Float, y: Float): Int { val d = bl.dir(j); return b.v(bl.cx + d[0] * bl.rad[j] * sc, y, bl.cz + d[1] * bl.rad[j] * sc, c) }
        val top = Array(K) { k -> IntArray(M) { j -> at(j, bl.scale(k), bl.y0 + bl.h[k][j]) } }
        val centre = b.v(bl.cx, bl.y0 + bl.hc, bl.cz, c)
        val base = IntArray(M) { j -> at(j, if (level == 2) 0.94f else 1f, bl.y0) }
        // high poly: the side bulges out a little above the base, so the bottom edge reads round
        val mid = if (level == 2) IntArray(M) { j -> at(j, 1.0f, bl.y0 + 0.35f * bl.h[0][j]) } else null
        for (k in 0 until K - 1) for (j in 0 until M) { val q = (j + 1) % M; outward(b, intArrayOf(top[k][j], top[k][q], top[k + 1][q], top[k + 1][j]), o) }
        for (j in 0 until M) { val q = (j + 1) % M; outward(b, intArrayOf(top[K - 1][j], top[K - 1][q], centre), o) }
        val lower = mid ?: base
        for (j in 0 until M) { val q = (j + 1) % M; outward(b, intArrayOf(lower[j], lower[q], top[0][q], top[0][j]), o) }
        if (mid != null) for (j in 0 until M) { val q = (j + 1) % M; outward(b, intArrayOf(base[j], base[q], mid[q], mid[j]), o) }
        outward(b, base.copyOf(), floatArrayOf(bl.cx, bl.y0 + 1f, bl.cz))
        b.endGroup(name)
    }

    /**
     * Points that make up the object at [seed] (or, with no seed, the biggest loose object):
     * everything connected to it except large faces (the desk) and what lies flat on them.
     */
    private fun gather(fit: FitResult, taken: BooleanArray, seed: FloatArray?): IntArray? {
        fun big(pi: Int): Boolean { val p = fit.planes[pi]; return if (p.disc) 3.1416f * p.radius * p.radius >= 0.06f else 4f * p.hu * p.hv >= 0.06f }
        val floors = fit.planes.indices.filter { big(it) && abs(fit.planes[it].n[1]) > 0.9f }.map { fit.planes[it] }
        val cand = fit.px.indices.filter { i ->
            if (taken[i]) return@filter false
            val o = fit.owner[i]
            if (o >= 0 && big(o)) return@filter false
            floors.none { p ->
                val x = fit.px[i] - p.c[0]; val y = fit.py[i] - p.c[1]; val z = fit.pz[i] - p.c[2]
                val d = x * p.n[0] + y * p.n[1] + z * p.n[2]
                abs(d) < 0.006f && (p.disc || (abs(x * p.u[0] + y * p.u[1] + z * p.u[2]) < p.hu + 0.02f && abs(x * p.v[0] + y * p.v[1] + z * p.v[2]) < p.hv + 0.02f))
            }
        }
        val gs = groups(fit, cand).filter { g ->
            if (g.size < 150) return@filter false
            var lo0 = Float.MAX_VALUE; var hi0 = -Float.MAX_VALUE; var lo2 = Float.MAX_VALUE; var hi2 = -Float.MAX_VALUE
            for (i in g) { lo0 = min(lo0, fit.px[i]); hi0 = max(hi0, fit.px[i]); lo2 = min(lo2, fit.pz[i]); hi2 = max(hi2, fit.pz[i]) }
            hi0 - lo0 < 0.6f && hi2 - lo2 < 0.6f
        }
        if (seed == null) return gs.maxByOrNull { it.size }
        var best: IntArray? = null; var bd = 0.05f * 0.05f
        for (g in gs) for (i in g) {
            val d = (fit.px[i] - seed[0]).let { it * it } + (fit.py[i] - seed[1]).let { it * it } + (fit.pz[i] - seed[2]).let { it * it }
            if (d < bd) { bd = d; best = g }
        }
        return best
    }

    // ------------------------------------------------------------------ catalogue

    private fun prismKind(p: Prism): String {
        val h = p.y1 - p.y0; val s = max(p.width(), p.depth())
        return when { h < 0.4f * s -> "Base"; h > 2.5f * s -> "Barra"; else -> "Blocco" }
    }

    private fun uniqueName(base: String, taken: HashSet<String>): String {
        val clean = base.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "Oggetto" }
        var n = clean; var k = 2
        while (!taken.add(n)) n = "${clean}_${k++}"
        return n
    }

    /** Assigns every label to the one object it is closest to (relative to that object's size). */
    private fun <T> matchLabels(labels: List<PartLabel>, items: List<T>, center: (T) -> FloatArray, size: (T) -> Float): Map<T, PartLabel> {
        val res = HashMap<T, PartLabel>()
        for (l in labels) {
            var best: T? = null; var bs = Float.MAX_VALUE
            for (it in items) {
                val c = center(it); val s = size(it)
                val d = sqrt((c[0] - l.x) * (c[0] - l.x) + (c[1] - l.y) * (c[1] - l.y) + (c[2] - l.z) * (c[2] - l.z))
                if (d > s + 0.04f) continue
                val score = d / (s + 0.02f)
                if (score < bs) { bs = score; best = it }
            }
            if (best != null) res[best] = l
        }
        return res
    }

    // ------------------------------------------------------------------ slabs

    /** Thickness seen along the edges of a flat rectangular face, 0 when its sides were not scanned. */
    private fun thickness(fit: FitResult, pi: Int): Float {
        val p = fit.planes[pi]
        val hist = IntArray(30)
        for (i in fit.px.indices) {
            if (fit.owner[i] == pi) continue
            val x = fit.px[i] - p.c[0]; val y = fit.py[i] - p.c[1]; val z = fit.pz[i] - p.c[2]
            val d = -(x * p.n[0] + y * p.n[1] + z * p.n[2])
            if (d < 0.008f || d >= 0.3f) continue
            val lu = abs(x * p.u[0] + y * p.u[1] + z * p.u[2]); val lv = abs(x * p.v[0] + y * p.v[1] + z * p.v[2])
            if (lu > p.hu + 0.02f || lv > p.hv + 0.02f) continue
            if (p.hu - lu > 0.035f && p.hv - lv > 0.035f) continue
            if (abs(fit.nx[i] * p.n[0] + fit.ny[i] * p.n[1] + fit.nz[i] * p.n[2]) > 0.5f) continue
            hist[(d / 0.01f).toInt().coerceIn(0, 29)]++
        }
        val peak = max(hist[0], max(hist[1], hist[2]))
        if (peak < 8 || hist.sum() < 40) return 0f
        var last = 0; var gap = 0
        for (k in 0 until 30) { if (hist[k] >= peak / 4) { last = k; gap = 0 } else if (++gap > 1) break }
        return (last + 1) * 0.01f
    }

    private fun slab(b: Builder, surf: ArrayList<NurbsSurface>, p: FitPlane, th: Float, name: String) {
        fun corner(su: Float, sv: Float, d: Float) = b.v(
            p.c[0] + p.u[0] * su * p.hu + p.v[0] * sv * p.hv - p.n[0] * d, p.c[1] + p.u[1] * su * p.hu + p.v[1] * sv * p.hv - p.n[1] * d,
            p.c[2] + p.u[2] * su * p.hu + p.v[2] * sv * p.hv - p.n[2] * d, p.color)
        val t = intArrayOf(corner(-1f, -1f, 0f), corner(1f, -1f, 0f), corner(1f, 1f, 0f), corner(-1f, 1f, 0f))
        b.quad(t[0], t[1], t[2], t[3])
        surf.add(NurbsSurface.rect("FACE${surf.size + 1}", dv(p.c), dv(p.u), dv(p.v), p.hu.toDouble(), p.hv.toDouble(), p.color))
        if (th > 0f) {
            val q = intArrayOf(corner(-1f, -1f, th), corner(1f, -1f, th), corner(1f, 1f, th), corner(-1f, 1f, th))
            b.quad(q[3], q[2], q[1], q[0])
            for (k in 0 until 4) { val j = (k + 1) % 4; b.quad(t[j], t[k], q[k], q[j]) }
            val nn = floatArrayOf(-p.n[0], -p.n[1], -p.n[2])
            val cb = floatArrayOf(p.c[0] + nn[0] * th, p.c[1] + nn[1] * th, p.c[2] + nn[2] * th)
            surf.add(NurbsSurface.rect("FACE${surf.size + 1}", dv(cb), dv(p.v), dv(p.u), p.hv.toDouble(), p.hu.toDouble(), p.color))
            val cm = floatArrayOf(p.c[0] + nn[0] * th / 2, p.c[1] + nn[1] * th / 2, p.c[2] + nn[2] * th / 2)
            for (sgn in floatArrayOf(-1f, 1f)) {
                val cu = floatArrayOf(cm[0] + p.u[0] * sgn * p.hu, cm[1] + p.u[1] * sgn * p.hu, cm[2] + p.u[2] * sgn * p.hu)
                surf.add(NurbsSurface.rect("FACE${surf.size + 1}", dv(cu), dv(if (sgn > 0) p.v else nn), dv(if (sgn > 0) nn else p.v), (if (sgn > 0) p.hv else th / 2).toDouble(), (if (sgn > 0) th / 2 else p.hv).toDouble(), p.color))
                val cv = floatArrayOf(cm[0] + p.v[0] * sgn * p.hv, cm[1] + p.v[1] * sgn * p.hv, cm[2] + p.v[2] * sgn * p.hv)
                surf.add(NurbsSurface.rect("FACE${surf.size + 1}", dv(cv), dv(if (sgn > 0) nn else p.u), dv(if (sgn > 0) p.u else nn), (if (sgn > 0) th / 2 else p.hu).toDouble(), (if (sgn > 0) p.hu else th / 2).toDouble(), p.color))
            }
        }
        b.endGroup(name)
    }

    // ------------------------------------------------------------------ main

    fun build(
        n: Int, pos: FloatArray, nrm: FloatArray, col: IntArray, pick: ((Int) -> Boolean)?, opt: SmartOptions,
        cancel: (() -> Boolean)? = null,
    ): SmartResult? {
        val fit = PrimitiveFit.fit(n, pos, nrm, col, pick, 1L, cancel)
        if (cancel?.invoke() == true) return null
        val total = fit.px.size
        val level = opt.level.coerceIn(0, 2)
        val joined = HashSet<FitTube>()
        for (e in fit.elbows) { e.tubeA?.let { joined.add(it) }; e.tubeB?.let { joined.add(it) } }
        val squared = regularize(fit, joined)
        var squaredExtra = 0
        val used = BooleanArray(total) { fit.owner[it] != -1 }
        val b = Builder(); val surf = ArrayList<NurbsSurface>(); val notes = ArrayList<String>()
        val catalog = ArrayList<CatalogPart>(); val taken = HashSet<String>()
        var nRev = 0; var nSlab = 0; var nFlat = 0; var nPipe = 0; var nPrism = 0; var nFree = 0

        // ---- objects the AI named as free-form (a mouse…): all their points, whatever the fit made of them
        val blobs = ArrayList<Blob>()
        val freeTubes = HashSet<FitTube>(); val freePlanes = HashSet<Int>()
        run {
            val seeds = ArrayList<Pair<FloatArray?, String>>()
            for (l in opt.labels) if (templateOf(l.name) == "freeform") seeds.add(Pair(floatArrayOf(l.x, l.y, l.z), l.name))
            if (seeds.isEmpty() && opt.hint.isNotBlank() && templateOf(opt.hint) == "freeform") seeds.add(Pair(null, opt.hint))
            if (seeds.isEmpty()) return@run
            val taken0 = BooleanArray(total)
            val tubeTotal = HashMap<FitTube, Int>(); val planeTotal = HashMap<Int, Int>()
            for (i in 0 until total) { fit.tubeOf.getOrNull(i)?.let { tubeTotal[it] = (tubeTotal[it] ?: 0) + 1 }; if (fit.owner[i] >= 0) planeTotal[fit.owner[i]] = (planeTotal[fit.owner[i]] ?: 0) + 1 }
            for ((seed, nm) in seeds) {
                val g = gather(fit, taken0, seed) ?: continue
                val bl = blob(fit, g, level) ?: continue
                bl.name = nm; bl.labelled = true; blobs.add(bl)
                val tc = HashMap<FitTube, Int>(); val pc = HashMap<Int, Int>()
                for (i in g) { taken0[i] = true; used[i] = true; fit.tubeOf.getOrNull(i)?.let { tc[it] = (tc[it] ?: 0) + 1 }; if (fit.owner[i] >= 0) pc[fit.owner[i]] = (pc[fit.owner[i]] ?: 0) + 1 }
                for ((t, k) in tc) if (k > 0.5f * (tubeTotal[t] ?: 1)) freeTubes.add(t)
                for ((pi, k) in pc) if (k > 0.5f * (planeTotal[pi] ?: 1)) freePlanes.add(pi)
            }
        }

        // ---- group everything that shares an axis
        val axes = ArrayList<Axis>()
        for (t in fit.tubes.sortedByDescending { it.length }) {
            if (t in joined || t in freeTubes) continue
            var g: Axis? = null
            for (a in axes) {
                val dd = a.d[0] * t.d[0] + a.d[1] * t.d[1] + a.d[2] * t.d[2]
                if (abs(dd) < 0.995f) continue
                val m = t.end(true)
                val vx = m[0] - a.a[0]; val vy = m[1] - a.a[1]; val vz = m[2] - a.a[2]
                val tt = vx * a.d[0] + vy * a.d[1] + vz * a.d[2]
                val off = sqrt(max(0f, vx * vx + vy * vy + vz * vz - tt * tt))
                if (off < max(0.015f, 0.3f * min(t.r, a.rMax))) { g = a; break }
            }
            if (g == null) { g = Axis(t.a.copyOf(), t.d.copyOf(), t.t0, t.t1, t.r, t.color); axes.add(g) }
            else {
                for (first in booleanArrayOf(true, false)) { val e = t.end(first); val tt = (e[0] - g.a[0]) * g.d[0] + (e[1] - g.a[1]) * g.d[1] + (e[2] - g.a[2]) * g.d[2]; g.t0 = min(g.t0, tt); g.t1 = max(g.t1, tt) }
                g.rMax = max(g.rMax, t.r)
            }
            g.tubeSet.add(t)
        }
        val planeDone = BooleanArray(fit.planes.size) { it in freePlanes }
        for ((pi, p) in fit.planes.withIndex()) {
            if (!p.disc || planeDone[pi]) continue
            var g: Axis? = null
            for (a in axes) {
                if (abs(a.d[0] * p.n[0] + a.d[1] * p.n[1] + a.d[2] * p.n[2]) < 0.98f) continue
                val vx = p.c[0] - a.a[0]; val vy = p.c[1] - a.a[1]; val vz = p.c[2] - a.a[2]
                val tt = vx * a.d[0] + vy * a.d[1] + vz * a.d[2]
                val off = sqrt(max(0f, vx * vx + vy * vy + vz * vz - tt * tt))
                if (off < max(0.03f, 0.2f * p.radius) && tt > a.t0 - 0.12f && tt < a.t1 + 0.12f) { g = a; a.t0 = min(a.t0, tt); a.t1 = max(a.t1, tt); break }
            }
            if (g == null) { g = Axis(p.c.copyOf(), p.n.copyOf(), -0.02f, 0f, p.radius, p.color); axes.add(g) }
            g.rMax = max(g.rMax, p.radius); g.discs.add(pi); planeDone[pi] = true
        }
        // Thickness of every rectangular face, so other things can stand on (or hang under) slabs.
        val thick = FloatArray(fit.planes.size) { if (fit.planes[it].disc) 0f else thickness(fit, it) }
        // Whatever lies inside a slab belongs to it (its edges, faces seen on its sides).
        for ((pi, p) in fit.planes.withIndex()) {
            if (planeDone[pi] || thick[pi] <= 0f) continue
            for (i in 0 until total) if (!used[i] || (fit.owner[i] >= 0 && fit.owner[i] != pi)) {
                val x = fit.px[i] - p.c[0]; val y = fit.py[i] - p.c[1]; val z = fit.pz[i] - p.c[2]
                val d = -(x * p.n[0] + y * p.n[1] + z * p.n[2])
                if (d > -0.015f && d < thick[pi] + 0.015f && abs(x * p.u[0] + y * p.u[1] + z * p.u[2]) < p.hu + 0.015f && abs(x * p.v[0] + y * p.v[1] + z * p.v[2]) < p.hv + 0.015f) {
                    val o = fit.owner[i]
                    if (o >= 0 && o != pi && !fit.planes[o].disc && fit.planes[o].hu * fit.planes[o].hv < p.hu * p.hv) planeDone[o] = true
                    if (o == -1) used[i] = true
                }
            }
        }
        // Small faces are often just one side of a small object (a bar, a box, a bulb): their points
        // go back to the pool so the object can be read as a whole. Faces nobody claims come back.
        val soft = ArrayList<Int>()
        for ((pi, p) in fit.planes.withIndex()) {
            if (planeDone[pi] || p.disc || thick[pi] > 0f || 4f * p.hu * p.hv >= 0.25f) continue
            soft.add(pi)
            for (i in 0 until total) if (fit.owner[i] == pi) { fit.owner[i] = -1; used[i] = false }
        }
        val softPts = HashMap<Int, IntArray>()
        for (pi in soft) {
            val c = fit.planes[pi]
            softPts[pi] = (0 until total).filter { i ->
                val x = fit.px[i] - c.c[0]; val y = fit.py[i] - c.c[1]; val z = fit.pz[i] - c.c[2]
                fit.owner[i] == -1 && abs(x * c.n[0] + y * c.n[1] + z * c.n[2]) < 0.012f &&
                    abs(x * c.u[0] + y * c.u[1] + z * c.u[2]) < c.hu + 0.01f && abs(x * c.v[0] + y * c.v[1] + z * c.v[2]) < c.hv + 0.01f
            }.toIntArray()
        }

        // ---- turned objects, split into the separate things stacked on each axis
        val stacks = ArrayList<ArrayList<Part>>()
        val sunk = HashSet<Axis>()
        for (a in axes) {
            // A lying "cylinder" that would sink into the face under it is the round back of
            // something else (a mouse): give its points back so the object is read whole.
            if (abs(a.d[1]) > 0.7f || a.discs.isNotEmpty()) continue
            val m = a.at((a.t0 + a.t1) / 2)
            var below = -Float.MAX_VALUE
            for (p in fit.planes) {
                if (abs(p.n[1]) < 0.9f || p.c[1] > m[1]) continue
                val x = m[0] - p.c[0]; val z = m[2] - p.c[2]
                val under = if (p.disc) x * x + z * z < p.radius * p.radius else abs(x * p.u[0] + z * p.u[2]) < p.hu && abs(x * p.v[0] + z * p.v[2]) < p.hv
                if (under) below = max(below, p.c[1])
            }
            if (below == -Float.MAX_VALUE) continue
            val reach = a.rMax * sqrt(max(0f, 1f - a.d[1] * a.d[1]))
            if (m[1] - reach < below - 0.3f * a.rMax) {
                sunk.add(a)
                for (i in 0 until total) if (fit.tubeOf.getOrNull(i)?.let { it in a.tubeSet } == true) { fit.owner[i] = -1; used[i] = false }
            }
        }
        axes.removeAll(sunk)
        for (a in axes) {
            if (cancel?.invoke() == true) return null
            a.orient()
            val pr = profile(fit, a, level, used)
            stacks.add(if (pr != null) split(a, pr, level) else arrayListOf(Part(a, arrayListOf(floatArrayOf(a.t0, a.rMax), floatArrayOf(a.t1, a.rMax)), a.color)))
        }
        // ---- what is left: upright turned shapes found from their outline, or straight-sided ones
        val prismsAll = ArrayList<Prism>()
        for (comp in components(fit, used)) {
            if (cancel?.invoke() == true) return null
            val ax = uprightAxis(fit, comp)
            if (ax != null) {
                val pr = profile(fit, ax, level, used)
                if (pr != null) { stacks.add(split(ax, pr, level)); continue }
            }
            val ps = prisms(fit, comp, level)
            if (ps != null) { prismsAll.addAll(ps); for (i in comp) used[i] = true; continue }
            // no axis and no straight sides: a measured dome over its footprint
            val bl = blob(fit, comp, level) ?: continue
            blobs.add(bl)
            for (i in comp) used[i] = true
        }
        for (pi in soft) {
            val pts = softPts[pi]!!
            val taken2 = pts.count { used[it] }
            if (taken2 > 0.5f * pts.size) planeDone[pi] = true
            else for (i in pts) { used[i] = true; fit.owner[i] = pi }
        }

        // ---- resting: an end within a few centimetres of a face, a slab's underside or another
        //      object's end meets it exactly
        class Cap(val c: FloatArray, val n: FloatArray, val r: Float, val owner: Any)
        val caps = ArrayList<Cap>()
        for (st in stacks) {
            val f = st.first(); val l = st.last(); val d = f.ax.d
            caps.add(Cap(f.ax.at(f.t0), floatArrayOf(-d[0], -d[1], -d[2]), f.prof[0][1], st))
            caps.add(Cap(l.ax.at(l.t1), d, l.prof[l.prof.size - 1][1], st))
        }
        for (p in prismsAll) {
            val c = p.center(); val r = max(p.width(), p.depth()) / 2
            caps.add(Cap(floatArrayOf(c[0], p.y1, c[2]), floatArrayOf(0f, 1f, 0f), r, p))
            caps.add(Cap(floatArrayOf(c[0], p.y0, c[2]), floatArrayOf(0f, -1f, 0f), r, p))
        }
        val discOfAxis = HashSet<Int>(); for (a in axes) discOfAxis.addAll(a.discs)
        fun snapMove(pt: FloatArray, dir: FloatArray, r: Float, self: Any): Float {
            var bestMove = 0f; var bestAbs = max(0.04f, 1.2f * r)
            for ((pi, p) in fit.planes.withIndex()) {
                if (pi in discOfAxis) continue
                val nd = p.n[0] * dir[0] + p.n[1] * dir[1] + p.n[2] * dir[2]
                if (abs(nd) < 0.95f) continue
                val x = pt[0] - p.c[0]; val y = pt[1] - p.c[1]; val z = pt[2] - p.c[2]
                val lu = x * p.u[0] + y * p.u[1] + z * p.u[2]; val lv = x * p.v[0] + y * p.v[1] + z * p.v[2]
                val inside = if (p.disc) lu * lu + lv * lv < (p.radius + 0.05f) * (p.radius + 0.05f) else abs(lu) < p.hu + 0.05f && abs(lv) < p.hv + 0.05f
                if (!inside) continue
                val s0 = x * p.n[0] + y * p.n[1] + z * p.n[2]
                for (off in if (thick[pi] > 0f) floatArrayOf(s0, s0 + thick[pi]) else floatArrayOf(s0)) if (abs(off) < bestAbs) { bestAbs = abs(off); bestMove = -off / nd }
            }
            for (c in caps) {
                if (c.owner === self) continue
                val nd = c.n[0] * dir[0] + c.n[1] * dir[1] + c.n[2] * dir[2]
                if (abs(nd) < 0.94f) continue
                val x = pt[0] - c.c[0]; val y = pt[1] - c.c[1]; val z = pt[2] - c.c[2]
                val s0 = x * c.n[0] + y * c.n[1] + z * c.n[2]
                val lat = sqrt(max(0f, x * x + y * y + z * z - s0 * s0))
                if (lat > c.r + 0.03f) continue
                if (abs(s0) < bestAbs) { bestAbs = abs(s0); bestMove = -s0 / nd }
            }
            return bestMove
        }
        for (st in stacks) {
            val f = st.first(); val l = st.last(); val d = f.ax.d
            val mv0 = snapMove(f.ax.at(f.t0), d, f.prof[0][1], st)
            if (mv0 != 0f && f.prof[0][0] + mv0 < f.prof[1][0] - 1e-4f) { f.prof[0][0] += mv0; f.restStart = true; squaredExtra++ }
            val mv1 = snapMove(l.ax.at(l.t1), d, l.prof[l.prof.size - 1][1], st)
            val e = l.prof[l.prof.size - 1]
            if (mv1 != 0f && e[0] + mv1 > l.prof[l.prof.size - 2][0] + 1e-4f) { e[0] += mv1; l.restEnd = true; squaredExtra++ }
        }
        for (p in prismsAll) {
            val c = p.center()
            val mv = snapMove(floatArrayOf(c[0], p.y0, c[2]), floatArrayOf(0f, 1f, 0f), max(p.width(), p.depth()) / 2, p)
            if (mv != 0f && p.y0 + mv < p.y1 - 0.002f) { p.y0 += mv; squaredExtra++ }
        }
        // Stacked layers of a straight-sided object follow on exactly.
        for (i in 1 until prismsAll.size) {
            val a = prismsAll[i - 1]; val c = prismsAll[i]
            if (abs(c.y0 - a.y1) < 0.012f && abs(c.center()[0] - a.center()[0]) < 0.1f && abs(c.center()[2] - a.center()[2]) < 0.1f) c.y0 = a.y1
        }

        // ---- legs and posts of nearly the same size get exactly the same one
        val legs = stacks.filter { it.size == 1 && it[0].prof.size == 2 && it[0].height() > 4f * it[0].rMax() }.map { it[0] }.sortedBy { it.rMax() }
        var li = 0
        while (li < legs.size) {
            var lj = li; var sMin = 0f; var sMax = 0f
            while (lj < legs.size && legs[lj].rMax() <= legs[li].rMax() * 1.12f) {
                sMin += min(legs[lj].prof[0][1], legs[lj].prof[1][1]); sMax += legs[lj].rMax(); lj++
            }
            if (lj - li > 1) {
                val rMin = sMin / (lj - li); val rMax = sMax / (lj - li)
                for (k in li until lj) { val p = legs[k].prof; if (p[0][1] >= p[1][1]) { p[0][1] = rMax; p[1][1] = rMin } else { p[0][1] = rMin; p[1][1] = rMax } }
                squaredExtra++
            }
            li = lj
        }

        // ---- names: from the AI / user labels when there are any, else from the shape
        val allParts = stacks.flatten()
        val partLabel = matchLabels(opt.labels, allParts, { it.center() }, { max(it.rMax(), it.height() / 2) })
        for (p in allParts) {
            val l = partLabel[p]
            if (l != null) { p.name = l.name; p.labelled = true; p.template = templateOf(l.name)?.takeIf { fits(it, p) } }
        }
        if (opt.hint.isNotBlank()) {
            val type = templateOf(opt.hint)
            if (type != null) {
                val cand = allParts.filter { !it.labelled && fits(type, it) }
                if (cand.size == 1) { cand[0].template = type; cand[0].name = opt.hint; cand[0].labelled = true }
            }
        }
        for (p in allParts) if (p.name.isEmpty()) p.name = "${kindName(p)} ${Palette.nameOf(p.color)}"
        val prismLabel = matchLabels(opt.labels, prismsAll, { it.center() }, { max(max(it.width(), it.depth()) / 2, (it.y1 - it.y0) / 2) })
        for (p in prismsAll) { val l = prismLabel[p]; if (l != null) { p.name = l.name; p.labelled = true } else p.name = "${prismKind(p)} ${Palette.nameOf(p.color)}" }
        val blobLabel = matchLabels(opt.labels, blobs.filter { !it.labelled }, { it.center() }, { max(max(it.length(), it.width()) / 2, it.height() / 2) })
        for (p in blobs) if (!p.labelled) { val l = blobLabel[p]; if (l != null) { p.name = l.name; p.labelled = true } else p.name = "Forma libera ${Palette.nameOf(p.color)}" }

        // ---- build the turned objects
        for (st in stacks) {
            for (p in st) finish(p, opt, notes)
            // Something standing in a container sits on its floor, not on its rim.
            for (k in 0 until st.size - 1) {
                val c = st[k]; val nx = st[k + 1]
                if (c.floorT.isNaN() || nx.rMax() >= c.innerR) continue
                if (c.floorT < nx.prof[1][0] - 1e-4f) nx.prof[0][0] = c.floorT
            }
            for (p in st) {
                val gname = uniqueName(p.name, taken)
                lathe(b, surf, p, sidesFor(level, p.rMax(), opt.sides), gname)
                nRev++
                val c = p.center()
                catalog.add(CatalogPart(gname.replace('_', ' '), p.labelled, "revolution", c, max(p.rMax(), p.height() / 2),
                    String.format(Locale.US, if (p.height() < 0.01f) "Ø %.0f × %.1f cm" else "Ø %.0f × %.0f cm", p.rMax() * 200, p.height() * 100), p.color, b.groups.size - 1, p.note,
                    p.ax.d.copyOf(), p.height(), p.rMax()))
            }
        }

        // ---- pipes: tubes joined by bends stay open where they meet
        for (t in fit.tubes) {
            if (t !in joined || t in freeTubes) continue
            val ax = Axis(t.a, t.d, t.t0, t.t1, t.r, t.color)
            fun meets(first: Boolean): Boolean {
                val e = t.end(first)
                return fit.elbows.any { el ->
                    val s = el.start; val q = elbowEnd(el)
                    val d1 = (s[0] - e[0]) * (s[0] - e[0]) + (s[1] - e[1]) * (s[1] - e[1]) + (s[2] - e[2]) * (s[2] - e[2])
                    val d2 = (q[0] - e[0]) * (q[0] - e[0]) + (q[1] - e[1]) * (q[1] - e[1]) + (q[2] - e[2]) * (q[2] - e[2])
                    min(d1, d2) < (0.5f * t.r) * (0.5f * t.r)
                }
            }
            val part = Part(ax, arrayListOf(floatArrayOf(t.t0, t.r), floatArrayOf(t.t1, t.r)), t.color)
            val gname = uniqueName("Tubo ${Palette.nameOf(t.color)}", taken)
            pipeLathe(b, surf, part, sidesFor(level, t.r, opt.sides), !meets(true), !meets(false), gname)
            nPipe++
            catalog.add(CatalogPart(gname.replace('_', ' '), false, "pipe", part.center(), max(t.r, t.length / 2),
                String.format(Locale.US, "Ø %.0f × %.0f cm", t.r * 200, t.length * 100), t.color, b.groups.size - 1, "", t.d.copyOf(), t.length, t.r))
        }
        for (e in fit.elbows) {
            val sides = sidesFor(level, e.r, opt.sides)
            val segs = max(2, Math.round(e.angle / (Math.PI.toFloat() / 2) * intArrayOf(2, 4, 8)[level]))
            val e1 = floatArrayOf(e.start[0] - e.center[0], e.start[1] - e.center[1], e.start[2] - e.center[2]).also { val l = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); it[0] /= l; it[1] /= l; it[2] /= l }
            val e2 = floatArrayOf(e.tangent[1] * e1[2] - e.tangent[2] * e1[1], e.tangent[2] * e1[0] - e.tangent[0] * e1[2], e.tangent[0] * e1[1] - e.tangent[1] * e1[0])
            var prev: IntArray? = null
            for (k in 0..segs) {
                val phi = e.angle * k / segs
                val ring = IntArray(sides)
                for (i in 0 until sides) {
                    val a = (2 * Math.PI * i / sides).toFloat()
                    val p = floatArrayOf(e.start[0] + e.r * (cos(a) * e1[0] + sin(a) * e2[0]), e.start[1] + e.r * (cos(a) * e1[1] + sin(a) * e2[1]), e.start[2] + e.r * (cos(a) * e1[2] + sin(a) * e2[2]))
                    val q = rotate(p, e.center, e.axis, phi)
                    ring[i] = b.v(q[0], q[1], q[2], e.color)
                }
                prev?.let { r0 -> for (i in 0 until sides) { val j = (i + 1) % sides; b.quad(r0[i], r0[j], ring[j], ring[i]) } }
                prev = ring
            }
            surf.add(NurbsSurface.elbow("BEND${surf.size + 1}", dv(e.start), dv(e.tangent), dv(e.center), dv(e.axis), e.angle.toDouble(), e.r.toDouble(), e.color))
            val gname = uniqueName("Curva", taken)
            b.endGroup(gname)
            catalog.add(CatalogPart(gname.replace('_', ' '), false, "pipe", e.center.copyOf(), e.bendRadius + e.r,
                String.format(Locale.US, "Ø %.0f cm, %.0f°", e.r * 200, Math.toDegrees(e.angle.toDouble())), e.color, b.groups.size - 1, "", e.axis.copyOf(), 0f, e.r))
        }

        // ---- straight-sided objects
        for (p in prismsAll) {
            val gname = uniqueName(p.name, taken)
            prism(b, surf, p, gname)
            nPrism++
            catalog.add(CatalogPart(gname.replace('_', ' '), p.labelled, "prism", p.center(), max(max(p.width(), p.depth()) / 2, (p.y1 - p.y0) / 2),
                String.format(Locale.US, "%.0f × %.0f × %.0f cm", p.width() * 100, p.depth() * 100, (p.y1 - p.y0) * 100), p.color, b.groups.size - 1, "",
                floatArrayOf(0f, 1f, 0f), p.y1 - p.y0, max(p.width(), p.depth()) / 2))
        }

        // ---- free-form objects
        for (p in blobs) {
            val gname = uniqueName(p.name, taken)
            blobMesh(b, p, level, gname)
            nFree++
            catalog.add(CatalogPart(gname.replace('_', ' '), p.labelled, "freeform", p.center(), max(max(p.length(), p.width()) / 2, p.height() / 2),
                String.format(Locale.US, "%.0f × %.0f × %.0f cm", p.length() * 100, p.width() * 100, p.height() * 100), p.color, b.groups.size - 1,
                if (p.sym) "simmetrico" else "", floatArrayOf(p.ux, 0f, p.uz), p.length(), p.width() / 2))
        }

        // ---- flat faces: slabs where the edge shows a thickness, single faces otherwise
        val boxes = ArrayList<Pair<FitPlane, Float>>()
        for (pi in fit.planes.indices.sortedByDescending { fit.planes[it].hu * fit.planes[it].hv }) {
            val p = fit.planes[pi]
            if (planeDone[pi]) continue
            // A face lying inside a slab already built is one of that slab's sides.
            val inside = boxes.any { (q, th) ->
                (0 until 4).all { k ->
                    val su = if (k and 1 == 0) -1f else 1f; val sv = if (k and 2 == 0) -1f else 1f
                    val x = p.c[0] + p.u[0] * su * p.hu + p.v[0] * sv * p.hv - q.c[0]; val y = p.c[1] + p.u[1] * su * p.hu + p.v[1] * sv * p.hv - q.c[1]; val z = p.c[2] + p.u[2] * su * p.hu + p.v[2] * sv * p.hv - q.c[2]
                    val d = -(x * q.n[0] + y * q.n[1] + z * q.n[2])
                    d > -0.03f && d < th + 0.03f && abs(x * q.u[0] + y * q.u[1] + z * q.u[2]) < q.hu + 0.03f && abs(x * q.v[0] + y * q.v[1] + z * q.v[2]) < q.hv + 0.03f
                }
            }
            if (inside) continue
            // A small flat face sitting inside a turned object is that object's own end (a lid, a base).
            val lid = allParts.any { part ->
                val ax = part.ax; val prof = part.prof
                (0 until 4).all { k ->
                    val su = if (k and 1 == 0) -1f else 1f; val sv = if (k and 2 == 0) -1f else 1f
                    val x = p.c[0] + p.u[0] * su * p.hu + p.v[0] * sv * p.hv - ax.a[0]; val y = p.c[1] + p.u[1] * su * p.hu + p.v[1] * sv * p.hv - ax.a[1]; val z = p.c[2] + p.u[2] * su * p.hu + p.v[2] * sv * p.hv - ax.a[2]
                    val t = x * ax.d[0] + y * ax.d[1] + z * ax.d[2]
                    val rho = sqrt(max(0f, x * x + y * y + z * z - t * t))
                    val lo = min(prof[0][0], prof[prof.size - 1][0]); val hi = max(prof[0][0], prof[prof.size - 1][0])
                    t > lo - 0.02f && t < hi + 0.02f && rho < radiusAt(prof, t.coerceIn(lo, hi)) * 1.45f + 0.01f
                }
            }
            if (lid) continue
            val th = thick[pi]
            if (th > 0f) {
                boxes.add(Pair(p, th)); nSlab++
                for (i in 0 until total) if (!used[i]) {
                    val x = fit.px[i] - p.c[0]; val y = fit.py[i] - p.c[1]; val z = fit.pz[i] - p.c[2]
                    val d = -(x * p.n[0] + y * p.n[1] + z * p.n[2])
                    if (d > -0.02f && d < th + 0.02f && abs(x * p.u[0] + y * p.u[1] + z * p.u[2]) < p.hu + 0.02f && abs(x * p.v[0] + y * p.v[1] + z * p.v[2]) < p.hv + 0.02f) used[i] = true
                }
            } else nFlat++
            val horizontal = abs(p.n[1]) > 0.9f
            val base = if (th > 0f) "Lastra" else if (horizontal) "Piano" else "Parete"
            val gname = uniqueName("$base ${Palette.nameOf(p.color)}", taken)
            slab(b, surf, p, th, gname)
            catalog.add(CatalogPart(gname.replace('_', ' '), false, if (th > 0f) "slab" else "face",
                floatArrayOf(p.c[0] - p.n[0] * th / 2, p.c[1] - p.n[1] * th / 2, p.c[2] - p.n[2] * th / 2), max(p.hu, p.hv),
                if (th > 0f) String.format(Locale.US, "%.0f × %.0f × %.0f cm", p.hu * 200, p.hv * 200, th * 100) else String.format(Locale.US, "%.0f × %.0f cm", p.hu * 200, p.hv * 200),
                p.color, b.groups.size - 1, "", p.n.copyOf(), th, max(p.hu, p.hv)))
        }

        // ---- everything else stays as ordinary mesh, so no detail is thrown away
        var explained = 0
        for (u in used) if (u) explained++
        var detailFaces = 0
        var mesh = b.mesh()
        if (opt.details && total - explained >= 300) {
            val m = total - explained
            val lp = FloatArray(m * 3); val ln = FloatArray(m * 3); val lc = IntArray(m)
            var k = 0
            for (i in 0 until total) if (!used[i]) {
                lp[k * 3] = fit.px[i]; lp[k * 3 + 1] = fit.py[i]; lp[k * 3 + 2] = fit.pz[i]
                ln[k * 3] = fit.nx[i]; ln[k * 3 + 1] = fit.ny[i]; ln[k * 3 + 2] = fit.nz[i]; lc[k] = fit.col[i]; k++
            }
            var free = Mesher.build(m, lp, ln, lc, floatArrayOf(0.06f, 0.03f, 0.02f)[level], cancel = cancel) ?: return null
            free = Mesher.removeIslands(free, 12)
            if (free.faceCount > 0) {
                detailFaces = free.faceCount; mesh = merge(mesh, free, "Dettagli")
                var cx = 0f; var cy = 0f; var cz = 0f
                for (v in 0 until free.vertexCount) { cx += free.pos[v * 3]; cy += free.pos[v * 3 + 1]; cz += free.pos[v * 3 + 2] }
                val nv = max(1, free.vertexCount)
                catalog.add(CatalogPart("Dettagli", false, "detail", floatArrayOf(cx / nv, cy / nv, cz / nv), 0.5f, "${free.faceCount} facce", -0x7f7f80, mesh.groups.size - 1, "", floatArrayOf(0f, 1f, 0f), 0f, 0f))
            }
        }

        val parts = ArrayList<String>()
        if (nRev > 0) parts.add("$nRev oggett${if (nRev == 1) "o tornito" else "i torniti"}")
        if (nFree > 0) parts.add("$nFree form${if (nFree == 1) "a libera" else "e libere"}")
        if (nPrism > 0) parts.add("$nPrism estrusion${if (nPrism == 1) "e" else "i"}")
        if (nPipe > 0) parts.add("$nPipe tub${if (nPipe == 1) "o" else "i"} con ${fit.elbows.size} curv${if (fit.elbows.size == 1) "a" else "e"}")
        if (nSlab > 0) parts.add(boxes.joinToString(", ") { (p, th) -> String.format(Locale.US, "lastra %.0f×%.0f×%.0f cm", p.hu * 200, p.hv * 200, th * 100) })
        if (nFlat > 0) parts.add("$nFlat facc${if (nFlat == 1) "ia piana" else "e piane"}")
        if (detailFaces > 0) parts.add("dettagli liberi: $detailFaces facce")
        var info = if (parts.isEmpty()) "nessuna forma regolare trovata" else parts.joinToString(" · ")
        if (squared + squaredExtra > 0) info += " · ${squared + squaredExtra} allineamenti (verticale, squadra, spessori uguali, appoggi)"
        for (s in notes.distinct()) info += " · $s"
        return SmartResult(mesh, surf, info, catalog.count { it.kind != "detail" }, if (total == 0) 0f else explained.toFloat() / total, catalog)
    }

    /** Tube of a pipe run: open where a bend continues it. */
    private fun pipeLathe(b: Builder, surf: ArrayList<NurbsSurface>, part: Part, sides: Int, capStart: Boolean, capEnd: Boolean, name: String) {
        val ax = part.ax; val prof = part.prof
        val (e1, e2) = basis(ax.d)
        val rings = prof.map { p -> IntArray(sides) { i -> val a = (2 * Math.PI * i / sides).toFloat(); val ca = cos(a) * p[1]; val sa = sin(a) * p[1]
            b.v(ax.a[0] + ax.d[0] * p[0] + e1[0] * ca + e2[0] * sa, ax.a[1] + ax.d[1] * p[0] + e1[1] * ca + e2[1] * sa, ax.a[2] + ax.d[2] * p[0] + e1[2] * ca + e2[2] * sa, part.color) } }
        for (k in 0 until rings.size - 1) for (i in 0 until sides) { val j = (i + 1) % sides; b.quad(rings[k][i], rings[k][j], rings[k + 1][j], rings[k + 1][i]) }
        surf.add(NurbsSurface.tube("TUBE${surf.size + 1}", dv(ax.at(prof[0][0])), dv(ax.at(prof[1][0])), prof[0][1].toDouble(), prof[1][1].toDouble(), part.color))
        if (capStart) b.ngons.add(IntArray(sides) { rings[0][sides - 1 - it] })
        if (capEnd) b.ngons.add(rings[rings.size - 1].copyOf())
        b.endGroup(name)
    }

    private fun rotate(p: FloatArray, c: FloatArray, k: FloatArray, a: Float): FloatArray {
        val vx = p[0] - c[0]; val vy = p[1] - c[1]; val vz = p[2] - c[2]
        val ca = cos(a); val sa = sin(a)
        val kx = k[1] * vz - k[2] * vy; val ky = k[2] * vx - k[0] * vz; val kz = k[0] * vy - k[1] * vx
        val kd = (k[0] * vx + k[1] * vy + k[2] * vz) * (1 - ca)
        return floatArrayOf(c[0] + vx * ca + kx * sa + k[0] * kd, c[1] + vy * ca + ky * sa + k[1] * kd, c[2] + vz * ca + kz * sa + k[2] * kd)
    }

    private fun elbowEnd(e: FitElbow) = rotate(e.start, e.center, e.axis, e.angle)

    /** Appends [b] to [a] as one more named object. */
    fun merge(a: MeshData, b: MeshData, name: String): MeshData {
        val off = a.vertexCount
        val q = IntArray(a.quads.size + b.quads.size)
        System.arraycopy(a.quads, 0, q, 0, a.quads.size)
        for (i in b.quads.indices) q[a.quads.size + i] = b.quads[i] + off
        val ng = ArrayList(a.ngons); for (g in b.ngons) ng.add(IntArray(g.size) { g[it] + off })
        val groups = ArrayList(a.groups); groups.add(MeshGroup(name, a.faceCount, a.faceCount + b.faceCount, a.ngons.size, ng.size))
        return MeshData(a.pos + b.pos, a.nrm + b.nrm, a.col + b.col, q, b.cell, ng, groups)
    }
}
