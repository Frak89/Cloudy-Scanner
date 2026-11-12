package com.frak89.cloudyscanner.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
)

class SmartResult(val mesh: MeshData, val surfaces: List<NurbsSurface>, val info: String, val solids: Int, val coverage: Float)

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

    // ------------------------------------------------------------------ solids of revolution

    private class Axis(val a: FloatArray, val d: FloatArray, var t0: Float, var t1: Float, var rMax: Float, var color: Int) {
        val discs = ArrayList<Int>()
        var tubes = 0
    }

    /** Radius along the axis as a short polyline of (t, radius), measured from the points. */
    private fun profile(fit: FitResult, ax: Axis, level: Int, used: BooleanArray): ArrayList<FloatArray>? {
        val n = fit.px.size
        val (e1, e2) = basis(ax.d)
        val bin = 0.01f
        val lo = ax.t0 - 0.06f; val hi = ax.t1 + 0.06f
        val nb = ((hi - lo) / bin).toInt() + 1
        if (nb > 2000) return null
        val reach = ax.rMax * 1.25f + 0.01f
        val lists = Array(nb) { ArrayList<Float>() }
        val sect = Array(nb) { IntArray(36) }
        val tLo = FloatArray(nb) { Float.MAX_VALUE }; val tHi = FloatArray(nb) { -Float.MAX_VALUE }
        val who = ArrayList<Int>(); val whoT = ArrayList<Float>(); val whoR = ArrayList<Float>()
        for (i in 0 until n) {
            val o = fit.owner[i]
            if (!(o == -1 || o == FitResult.ROUND || o in ax.discs)) continue
            val vx = fit.px[i] - ax.a[0]; val vy = fit.py[i] - ax.a[1]; val vz = fit.pz[i] - ax.a[2]
            val t = vx * ax.d[0] + vy * ax.d[1] + vz * ax.d[2]
            if (t < lo || t > hi) continue
            val u = vx * e1[0] + vy * e1[1] + vz * e1[2]; val v = vx * e2[0] + vy * e2[1] + vz * e2[2]
            val rho = sqrt(u * u + v * v)
            if (rho > reach) continue
            val b = ((t - lo) / bin).toInt().coerceIn(0, nb - 1)
            lists[b].add(rho)
            who.add(i); whoT.add(t); whoR.add(rho)
            sect[b][(((atan2(v, u) + Math.PI) / (2 * Math.PI) * 36).toInt()).coerceIn(0, 35)]++
            if (t < tLo[b]) tLo[b] = t; if (t > tHi[b]) tHi[b] = t
        }
        val rb = FloatArray(nb) { -1f }
        for (b in 0 until nb) {
            val l = lists[b]
            if (l.size < 8 || sect[b].count { it > 0 } < 6) continue
            l.sort()
            // A wall (points all near one radius): its middle. A flat end (points from the axis
            // outward): its rim, where 97% of an evenly covered disc lies within 98.5% of the radius.
            val p97 = l[(l.size * 0.97f).toInt().coerceAtMost(l.size - 1)]
            val p50 = l[l.size / 2]
            rb[b] = if (p50 > 0.8f * p97) p50 else p97 / 0.985f
        }
        // The run of rings that contains the seed range, bridging gaps of up to two bins.
        var s = ((ax.t0 + ax.t1) / 2 - lo).div(bin).toInt().coerceIn(0, nb - 1)
        if (rb[s] < 0f) { var k = 1; while (k < nb && rb.getOrElse(s + k) { -1f } < 0f && rb.getOrElse(s - k) { -1f } < 0f) k++; s = if (rb.getOrElse(s + k) { -1f } >= 0f) s + k else s - k }
        if (s !in 0 until nb || rb[s] < 0f) return null
        var b0 = s; var b1 = s
        while (true) { var k = b0 - 1; while (k >= 0 && b0 - k <= 3 && rb[k] < 0f) k--; if (k >= 0 && b0 - k <= 3 && rb[k] >= 0f) b0 = k else break }
        while (true) { var k = b1 + 1; while (k < nb && k - b1 <= 3 && rb[k] < 0f) k++; if (k < nb && k - b1 <= 3 && rb[k] >= 0f) b1 = k else break }
        for (b in b0..b1) if (rb[b] < 0f) { var p = b - 1; while (rb[p] < 0f) p--; var q = b + 1; while (rb[q] < 0f) q++; rb[b] = rb[p] + (rb[q] - rb[p]) * (b - p) / (q - p) }
        val m = b1 - b0 + 1
        if (m < 2) return null
        // Median of three removes single-bin spikes without rounding real steps.
        val sm = FloatArray(m) { k -> val a = rb[b0 + max(0, k - 1)]; val c = rb[b0 + k]; val e = rb[b0 + min(m - 1, k + 1)]; max(min(a, c), min(max(a, c), e)) }
        val pts = ArrayList<FloatArray>()
        // The solid ends where the points end, not at the bin boundary.
        pts.add(floatArrayOf(max(lo + b0 * bin, tLo[b0]), sm[0]))
        for (k in 0 until m) pts.add(floatArrayOf(lo + (b0 + k + 0.5f) * bin, sm[k]))
        pts.add(floatArrayOf(min(lo + (b1 + 1) * bin, tHi[b1]), sm[m - 1]))
        var rTop = 0f
        for (v in sm) rTop = max(rTop, v)
        val tol = max(floatArrayOf(0.008f, 0.004f, 0.002f)[level], floatArrayOf(0.03f, 0.015f, 0.007f)[level] * rTop)
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        simplify(pts, 0, pts.size - 1, tol, keep)
        val out = ArrayList<FloatArray>()
        for (k in pts.indices) if (keep[k]) out.add(pts[k])
        // Straight runs become exactly straight, abrupt changes become square steps.
        for (k in 0 until out.size - 1) {
            val p = out[k]; val q = out[k + 1]
            val dt = q[0] - p[0]; val dr = q[1] - p[1]
            if (abs(dr) < 0.12f * abs(dt) || abs(dr) < tol * 0.75f) {
                // exact cylinder: median of the measured radii across the run
                val vals = ArrayList<Float>()
                for (j in 0 until m) { val t = lo + (b0 + j + 0.5f) * bin; if (t >= p[0] && t <= q[0]) vals.add(sm[j]) }
                val r = if (vals.isEmpty()) (p[1] + q[1]) / 2 else { vals.sort(); vals[vals.size / 2] }
                p[1] = r; q[1] = r
            }
        }
        var k = 0
        while (k < out.size - 1) {
            val p = out[k]; val q = out[k + 1]
            if (q[0] - p[0] < 2.6f * bin && abs(q[1] - p[1]) > 0.8f * (q[0] - p[0]) && k > 0 && k + 1 < out.size - 1) {
                val t = (p[0] + q[0]) / 2; p[0] = t; q[0] = t
            }
            k++
        }
        // Drop points made redundant by the snapping.
        var j = 1
        while (j < out.size - 1) {
            val a = out[j - 1]; val b = out[j]; val c = out[j + 1]
            val same = abs(a[1] - b[1]) < 1e-5f && abs(b[1] - c[1]) < 1e-5f
            val dup = abs(a[0] - b[0]) < 1e-5f && abs(a[1] - b[1]) < 1e-5f
            if (same || dup) out.removeAt(j) else j++
        }
        ax.t0 = out[0][0]; ax.t1 = out[out.size - 1][0]
        // These points are now explained by the solid.
        for (q in who.indices) {
            val t = whoT[q]
            if (t < ax.t0 - 0.01f || t > ax.t1 + 0.01f) continue
            if (whoR[q] < radiusAt(out, t) + 0.02f) used[who[q]] = true
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

    /** Typical finishing by object type and option: rim chamfers, and the rolled rim of a can. */
    private fun finish(prof: ArrayList<FloatArray>, opt: SmartOptions, notes: ArrayList<String>) {
        val h = opt.hint.lowercase()
        val can = h.contains("lattin") || h.contains("barattol") || h.contains("can ")
        if (!opt.bevel && !can) return
        fun chamfer(atStart: Boolean, frac: Float) {
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
        chamfer(true, if (can) 0.1f else 0.07f)
        chamfer(false, if (can) 0.1f else 0.07f)
        if (can && prof.size >= 3) {
            // Rolled rim with the lid set slightly below it.
            val e = prof[prof.size - 1]
            val w = 0.06f * e[1]; val dp = 0.05f * e[1]
            prof.add(floatArrayOf(e[0], e[1] - w))
            prof.add(floatArrayOf(e[0] - dp, e[1] - w - dp))
            notes.add("rifiniture tipiche di una lattina (bordo e coperchio incassato) aggiunte dal tipo di oggetto, non misurate")
        }
    }

    private fun lathe(b: Builder, surf: ArrayList<NurbsSurface>, ax: Axis, prof: List<FloatArray>, sides: Int, capStart: Boolean, capEnd: Boolean, name: String) {
        val (e1, e2) = basis(ax.d)
        val rings = ArrayList<IntArray>()
        for (p in prof) {
            val ring = IntArray(sides)
            for (i in 0 until sides) {
                val a = (2 * Math.PI * i / sides).toFloat(); val ca = cos(a) * p[1]; val sa = sin(a) * p[1]
                ring[i] = b.v(ax.a[0] + ax.d[0] * p[0] + e1[0] * ca + e2[0] * sa, ax.a[1] + ax.d[1] * p[0] + e1[1] * ca + e2[1] * sa, ax.a[2] + ax.d[2] * p[0] + e1[2] * ca + e2[2] * sa, ax.color)
            }
            rings.add(ring)
        }
        // The profile may fold back (a recessed lid): faces must still look outward, so order by travel.
        for (k in 0 until rings.size - 1) {
            val r0 = rings[k]; val r1 = rings[k + 1]
            for (i in 0 until sides) { val j = (i + 1) % sides; b.quad(r0[i], r0[j], r1[j], r1[i]) }
            val p0 = prof[k]; val p1 = prof[k + 1]
            val c0 = dv(floatArrayOf(ax.a[0] + ax.d[0] * p0[0], ax.a[1] + ax.d[1] * p0[0], ax.a[2] + ax.d[2] * p0[0]))
            val c1 = dv(floatArrayOf(ax.a[0] + ax.d[0] * p1[0], ax.a[1] + ax.d[1] * p1[0], ax.a[2] + ax.d[2] * p1[0]))
            surf.add(NurbsSurface.tube("REV${surf.size + 1}", c0, c1, p0[1].toDouble(), p1[1].toDouble(), ax.color, dv(ax.d)))
        }
        if (capStart) {
            b.ngons.add(IntArray(sides) { rings[0][sides - 1 - it] })
            val p = prof[0]
            surf.add(NurbsSurface.disc("CAP${surf.size + 1}", dv(floatArrayOf(ax.a[0] + ax.d[0] * p[0], ax.a[1] + ax.d[1] * p[0], ax.a[2] + ax.d[2] * p[0])), dv(floatArrayOf(-ax.d[0], -ax.d[1], -ax.d[2])), p[1].toDouble(), ax.color))
        }
        if (capEnd) {
            b.ngons.add(rings[rings.size - 1].copyOf())
            val p = prof[prof.size - 1]
            surf.add(NurbsSurface.disc("CAP${surf.size + 1}", dv(floatArrayOf(ax.a[0] + ax.d[0] * p[0], ax.a[1] + ax.d[1] * p[0], ax.a[2] + ax.d[2] * p[0])), dv(ax.d), p[1].toDouble(), ax.color))
        }
        b.endGroup(name)
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
        val joined = HashSet<FitTube>()
        for (e in fit.elbows) { e.tubeA?.let { joined.add(it) }; e.tubeB?.let { joined.add(it) } }
        val squared = regularize(fit, joined)
        var squaredExtra = 0
        val used = BooleanArray(total) { fit.owner[it] != -1 }
        val b = Builder(); val surf = ArrayList<NurbsSurface>(); val notes = ArrayList<String>()
        var solids = 0; var nRev = 0; var nSlab = 0; var nFlat = 0; var nPipe = 0

        // ---- group everything that shares an axis
        val axes = ArrayList<Axis>()
        for (t in fit.tubes.sortedByDescending { it.length }) {
            if (t in joined) continue
            var g: Axis? = null; var sign = 1f
            for (a in axes) {
                val dd = a.d[0] * t.d[0] + a.d[1] * t.d[1] + a.d[2] * t.d[2]
                if (abs(dd) < 0.995f) continue
                val m = t.end(true)
                val vx = m[0] - a.a[0]; val vy = m[1] - a.a[1]; val vz = m[2] - a.a[2]
                val tt = vx * a.d[0] + vy * a.d[1] + vz * a.d[2]
                val off = sqrt(max(0f, vx * vx + vy * vy + vz * vz - tt * tt))
                if (off < max(0.015f, 0.3f * min(t.r, a.rMax))) { g = a; sign = if (dd > 0) 1f else -1f; break }
            }
            if (g == null) { g = Axis(t.a.copyOf(), t.d.copyOf(), t.t0, t.t1, t.r, t.color); axes.add(g) }
            else {
                for (first in booleanArrayOf(true, false)) { val e = t.end(first); val tt = (e[0] - g.a[0]) * g.d[0] + (e[1] - g.a[1]) * g.d[1] + (e[2] - g.a[2]) * g.d[2]; g.t0 = min(g.t0, tt); g.t1 = max(g.t1, tt) }
                g.rMax = max(g.rMax, t.r)
            }
            g.tubes++
        }
        val planeDone = BooleanArray(fit.planes.size)
        for ((pi, p) in fit.planes.withIndex()) {
            if (!p.disc) continue
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
        // Thickness of every rectangular face, needed now so round solids can stand exactly on
        // (or hang exactly under) the slabs.
        val thick = FloatArray(fit.planes.size) { if (fit.planes[it].disc) 0f else thickness(fit, it) }
        val revs = ArrayList<Pair<Axis, ArrayList<FloatArray>>>()
        for (a in axes) {
            if (cancel?.invoke() == true) return null
            var prof = profile(fit, a, opt.level, used)
            if (prof == null || prof.size < 2) prof = arrayListOf(floatArrayOf(a.t0, a.rMax), floatArrayOf(a.t1, a.rMax))
            // Ends that stop within a few centimetres of a face (or of a slab's underside) meet it exactly.
            for (endIdx in intArrayOf(0, prof.size - 1)) {
                val e = prof[endIdx]
                var bestMove = 0f; var bestAbs = max(0.04f, 1.2f * e[1])
                for ((pi, p) in fit.planes.withIndex()) {
                    if (pi in a.discs) continue
                    val nd = p.n[0] * a.d[0] + p.n[1] * a.d[1] + p.n[2] * a.d[2]
                    if (abs(nd) < 0.95f) continue
                    val x = a.a[0] + a.d[0] * e[0] - p.c[0]; val y = a.a[1] + a.d[1] * e[0] - p.c[1]; val z = a.a[2] + a.d[2] * e[0] - p.c[2]
                    val lu = x * p.u[0] + y * p.u[1] + z * p.u[2]; val lv = x * p.v[0] + y * p.v[1] + z * p.v[2]
                    val inside = if (p.disc) lu * lu + lv * lv < (p.radius + 0.05f) * (p.radius + 0.05f) else abs(lu) < p.hu + 0.05f && abs(lv) < p.hv + 0.05f
                    if (!inside) continue
                    val s0 = x * p.n[0] + y * p.n[1] + z * p.n[2]
                    for (off in if (thick[pi] > 0f) floatArrayOf(s0, s0 + thick[pi]) else floatArrayOf(s0)) {
                        if (abs(off) < bestAbs) { bestAbs = abs(off); bestMove = -off / nd }
                    }
                }
                if (bestMove != 0f) {
                    val other = prof[if (endIdx == 0) 1 else prof.size - 2]
                    val moved = e[0] + bestMove
                    // never fold the profile over its neighbour
                    if ((endIdx == 0 && moved < other[0] - 1e-4f) || (endIdx != 0 && moved > other[0] + 1e-4f)) { e[0] = moved; squaredExtra++ }
                }
            }
            revs.add(Pair(a, prof))
        }
        // Plain cylinders of nearly the same thickness (the legs of a table) get exactly the same one.
        val plain = revs.filter { it.second.size == 2 && abs(it.second[0][1] - it.second[1][1]) < 1e-5f }.sortedBy { it.second[0][1] }
        var pi0 = 0
        while (pi0 < plain.size) {
            var pj = pi0; var sum = 0f
            while (pj < plain.size && plain[pj].second[0][1] <= plain[pi0].second[0][1] * 1.12f) { sum += plain[pj].second[0][1]; pj++ }
            if (pj - pi0 > 1) { val r = sum / (pj - pi0); for (k in pi0 until pj) { plain[k].second[0][1] = r; plain[k].second[1][1] = r }; squaredExtra++ }
            pi0 = pj
        }
        for ((a, measured) in revs) {
            val prof = ArrayList(measured.map { it.copyOf() })
            finish(prof, opt, notes)
            var r = 0f; for (p in prof) r = max(r, p[1])
            lathe(b, surf, a, prof, sidesFor(opt.level, r, opt.sides), true, true, "Rotazione_${++nRev}")
            solids++
        }

        // ---- pipes: tubes joined by bends stay open where they meet
        for (t in fit.tubes) {
            if (t !in joined) continue
            val sides = sidesFor(opt.level, t.r, opt.sides)
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
            lathe(b, surf, ax, listOf(floatArrayOf(t.t0, t.r), floatArrayOf(t.t1, t.r)), sides, !meets(true), !meets(false), "Tubo_${++nPipe}")
            solids++
        }
        for (e in fit.elbows) {
            val sides = sidesFor(opt.level, e.r, opt.sides)
            val segs = max(2, Math.round(e.angle / (Math.PI.toFloat() / 2) * intArrayOf(2, 4, 8)[opt.level.coerceIn(0, 2)]))
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
            b.endGroup("Curva_${nPipe}_${surf.size}")
            solids++
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
            // A small flat face sitting inside a round solid is that solid's own end (a lid, a base).
            val lid = revs.any { (ax, prof) ->
                (0 until 4).all { k ->
                    val su = if (k and 1 == 0) -1f else 1f; val sv = if (k and 2 == 0) -1f else 1f
                    val x = p.c[0] + p.u[0] * su * p.hu + p.v[0] * sv * p.hv - ax.a[0]; val y = p.c[1] + p.u[1] * su * p.hu + p.v[1] * sv * p.hv - ax.a[1]; val z = p.c[2] + p.u[2] * su * p.hu + p.v[2] * sv * p.hv - ax.a[2]
                    val t = x * ax.d[0] + y * ax.d[1] + z * ax.d[2]
                    val rho = sqrt(max(0f, x * x + y * y + z * z - t * t))
                    t > prof[0][0] - 0.02f && t < prof[prof.size - 1][0] + 0.02f && rho < radiusAt(prof, t.coerceIn(prof[0][0], prof[prof.size - 1][0])) * 1.45f + 0.01f
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
            slab(b, surf, p, th, if (th > 0f) "Lastra_$nSlab" else "Faccia_$nFlat")
            solids++
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
            var free = Mesher.build(m, lp, ln, lc, floatArrayOf(0.06f, 0.03f, 0.02f)[opt.level.coerceIn(0, 2)], cancel = cancel) ?: return null
            free = Mesher.removeIslands(free, 12)
            if (free.faceCount > 0) { detailFaces = free.faceCount; mesh = merge(mesh, free, "Dettagli") }
        }

        val parts = ArrayList<String>()
        if (nRev > 0) parts.add("$nRev solid${if (nRev == 1) "o" else "i"} di rotazione")
        if (nPipe > 0) parts.add("$nPipe tub${if (nPipe == 1) "o" else "i"} con ${fit.elbows.size} curv${if (fit.elbows.size == 1) "a" else "e"}")
        if (nSlab > 0) parts.add(boxes.joinToString(", ") { (p, th) -> String.format(Locale.US, "lastra %.0f×%.0f×%.0f cm", p.hu * 200, p.hv * 200, th * 100) })
        if (nFlat > 0) parts.add("$nFlat facc${if (nFlat == 1) "ia piana" else "e piane"}")
        if (detailFaces > 0) parts.add("dettagli liberi: $detailFaces facce")
        var info = if (parts.isEmpty()) "nessuna forma regolare trovata" else parts.joinToString(" · ")
        if (squared + squaredExtra > 0) info += " · ${squared + squaredExtra} allineamenti (verticale, squadra, spessori uguali, appoggi)"
        for (s in notes.distinct()) info += " · $s"
        return SmartResult(mesh, surf, info, solids, if (total == 0) 0f else explained.toFloat() / total)
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
