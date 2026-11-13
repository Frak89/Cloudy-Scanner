package com.frak89.cloudyscanner.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

class FitPlane(var c: FloatArray, var n: FloatArray, var u: FloatArray, var v: FloatArray,
               val disc: Boolean, val radius: Float, var hu: Float, var hv: Float, val color: Int, val count: Int)

/** Straight tube: axis point [a] + t * [d], for t in [t0, t1]. */
class FitTube(var a: FloatArray, var d: FloatArray, var t0: Float, var t1: Float, var r: Float, val color: Int, val count: Int) {
    fun end(first: Boolean) = floatArrayOf(a[0] + d[0] * (if (first) t0 else t1), a[1] + d[1] * (if (first) t0 else t1), a[2] + d[2] * (if (first) t0 else t1))
    val length get() = t1 - t0
}

/** Bend joining two tubes: starts at [start] heading along [tangent], turns by [angle] around [center]. */
class FitElbow(val start: FloatArray, val tangent: FloatArray, val center: FloatArray, val axis: FloatArray,
               val angle: Float, val bendRadius: Float, val r: Float, val color: Int) {
    /** The two tubes this bend joins (set by the fit). */
    var tubeA: FitTube? = null
    var tubeB: FitTube? = null
}

class FitResult(val planes: MutableList<FitPlane>, val tubes: MutableList<FitTube>, val elbows: List<FitElbow>, val explained: Int, val total: Int) {
    /** The (thinned) points the fit worked on: positions, unit normals, colours. */
    var px = FloatArray(0); var py = FloatArray(0); var pz = FloatArray(0)
    var nx = FloatArray(0); var ny = FloatArray(0); var nz = FloatArray(0)
    var col = IntArray(0)
    /** Per point: index of the plane that explains it, [ROUND] for tubes and bends, -1 for none. */
    var owner = IntArray(0)
    /** Per point: the tube that explains it (null for planes, bends and unexplained points). */
    var tubeOf: Array<FitTube?> = arrayOf()

    val coverage get() = if (total == 0) 0f else explained.toFloat() / total

    companion object { const val ROUND = -2 }

    fun surfaces(): List<NurbsSurface> {
        fun dv(a: FloatArray) = doubleArrayOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())
        val out = ArrayList<NurbsSurface>()
        var k = 0
        for (p in planes) out.add(
            if (p.disc) NurbsSurface.disc("DISC${++k}", dv(p.c), dv(p.n), p.radius.toDouble(), p.color)
            else NurbsSurface.rect("PLANE${++k}", dv(p.c), dv(p.u), dv(p.v), p.hu.toDouble(), p.hv.toDouble(), p.color))
        for (t in tubes) out.add(NurbsSurface.tube("TUBE${++k}", dv(t.end(true)), dv(t.end(false)), t.r.toDouble(), t.r.toDouble(), t.color))
        for (e in elbows) out.add(NurbsSurface.elbow("BEND${++k}", dv(e.start), dv(e.tangent), dv(e.center), dv(e.axis), e.angle.toDouble(), e.r.toDouble(), e.color))
        return out
    }

    fun describe(): String {
        val parts = ArrayList<String>()
        val discs = planes.count { it.disc }; val rects = planes.size - discs
        if (tubes.isNotEmpty()) parts.add("${tubes.size} tub${if (tubes.size == 1) "o" else "i"} (Ø " + tubes.sortedByDescending { it.r }.take(4).joinToString(", ") { "${Math.round(it.r * 200)} cm" } + ")")
        if (elbows.isNotEmpty()) parts.add("${elbows.size} curv${if (elbows.size == 1) "a" else "e"} (" + elbows.joinToString(", ") { "${Math.round(Math.toDegrees(it.angle.toDouble()) / 5) * 5}°" } + ")")
        if (discs > 0) parts.add("$discs disc${if (discs == 1) "o" else "hi"} (Ø " + planes.filter { it.disc }.joinToString(", ") { "${Math.round(it.radius * 200)} cm" } + ")")
        if (rects > 0) parts.add("$rects pian${if (rects == 1) "o" else "i"}")
        return if (parts.isEmpty()) "nessuna forma regolare trovata" else parts.joinToString(" · ") + " · spiega il ${(coverage * 100).toInt()}% dei punti"
    }
}

/**
 * Finds regular shapes in an oriented point cloud and describes them exactly: flat faces
 * (round or rectangular), straight tubes with a circular section (split where the thickness
 * changes) and the bends that join them. Detection is RANSAC over oriented points; every
 * shape is then refined by least squares.
 */
object PrimitiveFit {
    private const val EPS = 0.012f

    /** Eigenvectors of a symmetric 3x3 matrix (Jacobi). Returns values in [w], vectors as columns of [v]. */
    fun eigen3(m: DoubleArray, w: DoubleArray, v: DoubleArray) {
        val a = m.copyOf()
        for (i in 0 until 9) v[i] = if (i % 4 == 0) 1.0 else 0.0
        repeat(24) {
            var p = 0; var q = 1; var big = abs(a[1])
            if (abs(a[2]) > big) { big = abs(a[2]); p = 0; q = 2 }
            if (abs(a[5]) > big) { big = abs(a[5]); p = 1; q = 2 }
            if (big < 1e-14) return@repeat
            val th = (a[q * 3 + q] - a[p * 3 + p]) / (2 * a[p * 3 + q])
            val t = (if (th >= 0) 1.0 else -1.0) / (abs(th) + sqrt(th * th + 1))
            val c = 1 / sqrt(t * t + 1); val s = t * c
            for (k in 0 until 3) { val akp = a[k * 3 + p]; val akq = a[k * 3 + q]; a[k * 3 + p] = c * akp - s * akq; a[k * 3 + q] = s * akp + c * akq }
            for (k in 0 until 3) { val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]; a[p * 3 + k] = c * apk - s * aqk; a[q * 3 + k] = s * apk + c * aqk }
            for (k in 0 until 3) { val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]; v[k * 3 + p] = c * vkp - s * vkq; v[k * 3 + q] = s * vkp + c * vkq }
        }
        w[0] = a[0]; w[1] = a[4]; w[2] = a[8]
    }

    private fun smallestAxis(cov: DoubleArray): FloatArray {
        val w = DoubleArray(3); val v = DoubleArray(9)
        eigen3(cov, w, v)
        val k = if (w[0] <= w[1] && w[0] <= w[2]) 0 else if (w[1] <= w[2]) 1 else 2
        return floatArrayOf(v[k].toFloat(), v[3 + k].toFloat(), v[6 + k].toFloat())
    }

    private fun basis(d: FloatArray): Array<FloatArray> {
        val h = if (abs(d[0]) < 0.8f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        var ex = h[1] * d[2] - h[2] * d[1]; var ey = h[2] * d[0] - h[0] * d[2]; var ez = h[0] * d[1] - h[1] * d[0]
        val l = sqrt(ex * ex + ey * ey + ez * ez); ex /= l; ey /= l; ez /= l
        return arrayOf(floatArrayOf(ex, ey, ez), floatArrayOf(d[1] * ez - d[2] * ey, d[2] * ex - d[0] * ez, d[0] * ey - d[1] * ex))
    }

    private fun meanColor(col: IntArray, idx: IntArray, n: Int): Int {
        var r = 0L; var g = 0L; var b = 0L
        for (k in 0 until n) { val c = col[idx[k]]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
        val m = max(1, n)
        return (0xFF shl 24) or ((r / m).toInt() shl 16) or ((g / m).toInt() shl 8) or (b / m).toInt()
    }

    fun fit(
        nAll: Int, posAll: FloatArray, nrmAll: FloatArray, colAll: IntArray,
        pick: ((Int) -> Boolean)? = null, seed: Long = 1L, cancel: (() -> Boolean)? = null,
    ): FitResult {
        // ---- gather (and thin) the working set
        var cnt = 0
        for (i in 0 until nAll) if (pick == null || pick(i)) cnt++
        val stride = max(1, cnt / 60000)
        val n = (cnt + stride - 1) / stride
        val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
        val nx = FloatArray(n); val ny = FloatArray(n); val nz = FloatArray(n); val col = IntArray(n)
        var k = 0; var seen = 0
        for (i in 0 until nAll) {
            if (pick != null && !pick(i)) continue
            if (seen++ % stride != 0) continue
            if (k >= n) break
            val l = max(1e-6f, sqrt(nrmAll[i * 3] * nrmAll[i * 3] + nrmAll[i * 3 + 1] * nrmAll[i * 3 + 1] + nrmAll[i * 3 + 2] * nrmAll[i * 3 + 2]))
            px[k] = posAll[i * 3]; py[k] = posAll[i * 3 + 1]; pz[k] = posAll[i * 3 + 2]
            nx[k] = nrmAll[i * 3] / l; ny[k] = nrmAll[i * 3 + 1] / l; nz[k] = nrmAll[i * 3 + 2] / l
            col[k] = colAll[i]; k++
        }
        val total = k
        val alive = BooleanArray(total) { true }
        val owner = IntArray(total) { -1 }
        val tubeOf = arrayOfNulls<FitTube>(total)
        var aliveCount = total
        val rnd = Random(seed)
        val planes = ArrayList<FitPlane>(); val tubes = ArrayList<FitTube>()
        val minInliers = max(120, total / 40)
        val idx = IntArray(total); val tmp = IntArray(total)
        var fails = 0
        // Points of candidates that were tried and rejected: left in place, but no longer used to
        // propose shapes, so one stubborn impostor cannot keep everything else from being found.
        val tried = BooleanArray(total)

        while (aliveCount >= minInliers && planes.size + tubes.size < 24 && fails < 6) {
            if (cancel?.invoke() == true) break
            var na = 0
            for (i in 0 until total) if (alive[i] && !tried[i]) idx[na++] = i
            if (na < minInliers / 2) break
            val ns = min(na, 2500)
            val sub = IntArray(ns) { idx[if (na <= 2500) it else rnd.nextInt(na)] }

            // -- best plane on the subset: one oriented point defines a candidate
            var bestP = -1; var bestPc = 0
            repeat(200) {
                val i = sub[rnd.nextInt(ns)]
                var c = 0
                for (j in sub) {
                    val d = nx[i] * (px[j] - px[i]) + ny[i] * (py[j] - py[i]) + nz[i] * (pz[j] - pz[i])
                    if (abs(d) < EPS && abs(nx[i] * nx[j] + ny[i] * ny[j] + nz[i] * nz[j]) > 0.75f) c++
                }
                if (c > bestPc) { bestPc = c; bestP = i }
            }
            // -- best cylinder on the subset: two oriented points define a candidate
            var bestCc = 0
            val bc = FloatArray(7)   // axis point, direction, radius
            val cand = FloatArray(7)
            repeat(600) {
                val i = sub[rnd.nextInt(ns)]
                var j = sub[rnd.nextInt(ns)]
                var tries = 0
                while (tries++ < 6 && ((px[i] - px[j]) * (px[i] - px[j]) + (py[i] - py[j]) * (py[i] - py[j]) + (pz[i] - pz[j]) * (pz[i] - pz[j]) > 0.09f || i == j)) j = sub[rnd.nextInt(ns)]
                if (!cylinderFrom(px[i], py[i], pz[i], nx[i], ny[i], nz[i], px[j], py[j], pz[j], nx[j], ny[j], nz[j], cand)) return@repeat
                var c = 0
                for (q in sub) if (onCylinder(px[q], py[q], pz[q], nx[q], ny[q], nz[q], cand, tubeEps(cand[6]))) c++
                if (c > bestCc) { bestCc = c; System.arraycopy(cand, 0, bc, 0, 7) }
            }

            var tube: List<FitTube>? = null
            var tubeCount = 0
            if (bestCc >= 12) {
                val r = refineTube(total, px, py, pz, nx, ny, nz, col, alive, bc, tmp)
                if (r != null) { tube = r.first; tubeCount = r.second }
            }
            var plane: FitPlane? = null
            var planeIdx: IntArray? = null
            if (bestP >= 0) {
                val r = refinePlane(total, px, py, pz, nx, ny, nz, col, alive, bestP, tmp)
                if (r != null) { plane = r.first; planeIdx = r.second }
            }
            val planeCount = plane?.count ?: 0
            if (tube != null && tubeCount >= minInliers / 2 && tubeCount > planeCount * 0.8f) {
                // Remove everything the tube sections explain.
                for (t in tube) for (i in 0 until total) if (alive[i]) {
                    val tt = (px[i] - t.a[0]) * t.d[0] + (py[i] - t.a[1]) * t.d[1] + (pz[i] - t.a[2]) * t.d[2]
                    if (tt < t.t0 - 0.01f || tt > t.t1 + 0.01f) continue
                    val rx = px[i] - t.a[0] - t.d[0] * tt; val ry = py[i] - t.a[1] - t.d[1] * tt; val rz = pz[i] - t.a[2] - t.d[2] * tt
                    if (abs(sqrt(rx * rx + ry * ry + rz * rz) - t.r) < 2.5f * tubeEps(t.r)) { alive[i] = false; aliveCount--; owner[i] = FitResult.ROUND; tubeOf[i] = t }
                }
                tubes.addAll(tube); fails = 0
            } else if (plane != null && planeCount >= minInliers) {
                for (i in planeIdx!!) if (alive[i]) { alive[i] = false; aliveCount--; owner[i] = planes.size }
                planes.add(plane); fails = 0
            } else {
                fails++
                for (i in 0 until total) if (alive[i] && !tried[i]) {
                    if (bestCc >= 12 && onCylinder(px[i], py[i], pz[i], nx[i], ny[i], nz[i], bc, tubeEps(bc[6]) * 1.5f)) tried[i] = true
                    else if (bestP >= 0) {
                        val dd = nx[bestP] * (px[i] - px[bestP]) + ny[bestP] * (py[i] - py[bestP]) + nz[bestP] * (pz[i] - pz[bestP])
                        if (abs(dd) < EPS && abs(nx[bestP] * nx[i] + ny[bestP] * ny[i] + nz[bestP] * nz[i]) > 0.75f) tried[i] = true
                    }
                }
            }
        }

        val elbows = joinTubes(tubes, px, py, pz, total)
        // A bend's own points can pass for a short stub of tube: drop stubs that sit inside a bend.
        tubes.removeAll { t ->
            elbows.any { e ->
                (0..2).all { k ->
                    val tt = t.t0 + (t.t1 - t.t0) * k / 2f
                    val vx = t.a[0] + t.d[0] * tt - e.center[0]; val vy = t.a[1] + t.d[1] * tt - e.center[1]; val vz = t.a[2] + t.d[2] * tt - e.center[2]
                    val h = vx * e.axis[0] + vy * e.axis[1] + vz * e.axis[2]
                    val rho = sqrt((vx - e.axis[0] * h) * (vx - e.axis[0] * h) + (vy - e.axis[1] * h) * (vy - e.axis[1] * h) + (vz - e.axis[2] * h) * (vz - e.axis[2] * h))
                    sqrt((rho - e.bendRadius) * (rho - e.bendRadius) + h * h) < 1.3f * e.r
                }
            }
        }
        // Points lying on a bend count as explained too.
        for (e in elbows) for (i in 0 until total) if (alive[i]) {
            val vx = px[i] - e.center[0]; val vy = py[i] - e.center[1]; val vz = pz[i] - e.center[2]
            val h = vx * e.axis[0] + vy * e.axis[1] + vz * e.axis[2]
            val qx = vx - e.axis[0] * h; val qy = vy - e.axis[1] * h; val qz = vz - e.axis[2] * h
            val rho = sqrt(qx * qx + qy * qy + qz * qz)
            if (abs(sqrt((rho - e.bendRadius) * (rho - e.bendRadius) + h * h) - e.r) < 2.5f * tubeEps(e.r)) {
                val sx = e.start[0] - e.center[0]; val sy = e.start[1] - e.center[1]; val sz = e.start[2] - e.center[2]
                val cs = (qx * sx + qy * sy + qz * sz) / max(1e-6f, rho * e.bendRadius)
                val sn = (qx * e.tangent[0] + qy * e.tangent[1] + qz * e.tangent[2]) / max(1e-6f, rho)
                val ang = atan2(sn, cs)
                if (ang > -0.1f && ang < e.angle + 0.1f) { alive[i] = false; aliveCount--; owner[i] = FitResult.ROUND }
            }
        }
        return FitResult(planes, tubes, elbows, total - aliveCount, total).also {
            it.px = px; it.py = py; it.pz = pz; it.nx = nx; it.ny = ny; it.nz = nz; it.col = col; it.owner = owner; it.tubeOf = tubeOf
        }
    }

    private fun tubeEps(r: Float) = max(0.005f, min(EPS, 0.4f * r))

    private fun cylinderFrom(x1: Float, y1: Float, z1: Float, a1: Float, b1: Float, c1: Float,
                             x2: Float, y2: Float, z2: Float, a2: Float, b2: Float, c2: Float, out: FloatArray): Boolean {
        var dx = b1 * c2 - c1 * b2; var dy = c1 * a2 - a1 * c2; var dz = a1 * b2 - b1 * a2
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 0.25f) return false
        dx /= l; dy /= l; dz /= l
        // Work in the plane perpendicular to the axis: the two normals are lines meeting at the centre.
        val d = floatArrayOf(dx, dy, dz)
        val (e1, e2) = basis(d)
        val u1 = x1 * e1[0] + y1 * e1[1] + z1 * e1[2]; val v1 = x1 * e2[0] + y1 * e2[1] + z1 * e2[2]
        val u2 = x2 * e1[0] + y2 * e1[1] + z2 * e1[2]; val v2 = x2 * e2[0] + y2 * e2[1] + z2 * e2[2]
        val m1u = a1 * e1[0] + b1 * e1[1] + c1 * e1[2]; val m1v = a1 * e2[0] + b1 * e2[1] + c1 * e2[2]
        val m2u = a2 * e1[0] + b2 * e1[1] + c2 * e1[2]; val m2v = a2 * e2[0] + b2 * e2[1] + c2 * e2[2]
        val det = m1u * (-m2v) - (-m2u) * m1v
        if (abs(det) < 1e-4f) return false
        val s = ((u2 - u1) * (-m2v) - (-m2u) * (v2 - v1)) / det
        val cu = u1 + m1u * s; val cv = v1 + m1v * s
        val r = (abs(s) + sqrt((u2 - cu) * (u2 - cu) + (v2 - cv) * (v2 - cv))) / 2
        if (r < 0.008f || r > 0.6f) return false
        out[0] = e1[0] * cu + e2[0] * cv; out[1] = e1[1] * cu + e2[1] * cv; out[2] = e1[2] * cu + e2[2] * cv
        out[3] = dx; out[4] = dy; out[5] = dz; out[6] = r
        return true
    }

    private fun onCylinder(x: Float, y: Float, z: Float, a: Float, b: Float, c: Float, cy: FloatArray, eps: Float): Boolean {
        val vx = x - cy[0]; val vy = y - cy[1]; val vz = z - cy[2]
        val t = vx * cy[3] + vy * cy[4] + vz * cy[5]
        val rx = vx - cy[3] * t; val ry = vy - cy[4] * t; val rz = vz - cy[5] * t
        val rho = sqrt(rx * rx + ry * ry + rz * rz)
        if (abs(rho - cy[6]) > eps) return false
        if (abs(a * cy[3] + b * cy[4] + c * cy[5]) > 0.4f) return false
        return abs(a * rx + b * ry + c * rz) > 0.7f * rho
    }

    /**
     * Least-squares axis and radius, then a walk along the axis measuring the radius every
     * 2 cm: runs of steady radius become separate tube sections (thick part, thin part…).
     */
    private fun refineTube(total: Int, px: FloatArray, py: FloatArray, pz: FloatArray, nx: FloatArray, ny: FloatArray, nz: FloatArray,
                           col: IntArray, alive: BooleanArray, cy: FloatArray, tmp: IntArray): Pair<List<FitTube>, Int>? {
        repeat(2) {
            var m = 0
            for (i in 0 until total) if (alive[i] && onCylinder(px[i], py[i], pz[i], nx[i], ny[i], nz[i], cy, tubeEps(cy[6]) * 1.5f)) tmp[m++] = i
            if (m < 30) return null
            val cov = DoubleArray(9)
            for (q in 0 until m) { val i = tmp[q]; val v = doubleArrayOf(nx[i].toDouble(), ny[i].toDouble(), nz[i].toDouble()); for (a in 0 until 3) for (b in 0 until 3) cov[a * 3 + b] += v[a] * v[b] }
            val d = smallestAxis(cov)
            val (e1, e2) = basis(d)
            // Algebraic circle fit in the plane across the axis.
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0; var sxz = 0.0; var syz = 0.0; var sz = 0.0
            for (q in 0 until m) {
                val i = tmp[q]
                val x = (px[i] * e1[0] + py[i] * e1[1] + pz[i] * e1[2]).toDouble(); val y = (px[i] * e2[0] + py[i] * e2[1] + pz[i] * e2[2]).toDouble()
                val z = x * x + y * y
                sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y; sxz += x * z; syz += y * z; sz += z
            }
            val sol = solve3(doubleArrayOf(sxx, sxy, sx, sxy, syy, sy, sx, sy, m.toDouble()), doubleArrayOf(-sxz, -syz, -sz)) ?: return null
            val cu = -sol[0] / 2; val cv = -sol[1] / 2
            val r2 = cu * cu + cv * cv - sol[2]
            if (r2 <= 0) return null
            cy[0] = (e1[0] * cu + e2[0] * cv).toFloat(); cy[1] = (e1[1] * cu + e2[1] * cv).toFloat(); cy[2] = (e1[2] * cu + e2[2] * cv).toFloat()
            cy[3] = d[0]; cy[4] = d[1]; cy[5] = d[2]; cy[6] = sqrt(r2).toFloat()
            if (cy[6] < 0.008f || cy[6] > 0.6f) return null
        }
        val d = floatArrayOf(cy[3], cy[4], cy[5]); val r0 = cy[6]
        val (e1, e2) = basis(d)
        // Everything near the axis, binned along it.
        val reach = max(2.5f * r0, r0 + 0.06f)
        var tmin = Float.MAX_VALUE; var tmax = -Float.MAX_VALUE; var m = 0
        for (i in 0 until total) if (alive[i]) {
            val vx = px[i] - cy[0]; val vy = py[i] - cy[1]; val vz = pz[i] - cy[2]
            val t = vx * d[0] + vy * d[1] + vz * d[2]
            val rx = vx - d[0] * t; val ry = vy - d[1] * t; val rz = vz - d[2] * t
            if (rx * rx + ry * ry + rz * rz < reach * reach) { tmp[m++] = i; if (t < tmin) tmin = t; if (t > tmax) tmax = t }
        }
        if (m < 30) return null
        val bin = 0.02f
        val nb = min(600, ((tmax - tmin) / bin).toInt() + 1)
        val lists = Array(nb) { ArrayList<Float>() }
        val angles = Array(nb) { BooleanArray(36) }
        val tilt = FloatArray(nb)   // how much the normals lean along the axis (0 on a true tube)
        val align = FloatArray(nb)  // how well the normals point away from the axis (1 on a true tube)
        val near = IntArray(nb)
        for (q in 0 until m) {
            val i = tmp[q]
            val vx = px[i] - cy[0]; val vy = py[i] - cy[1]; val vz = pz[i] - cy[2]
            val t = vx * d[0] + vy * d[1] + vz * d[2]
            val b = ((t - tmin) / bin).toInt().coerceIn(0, nb - 1)
            val u = vx * e1[0] + vy * e1[1] + vz * e1[2]; val v = vx * e2[0] + vy * e2[1] + vz * e2[2]
            val rho = sqrt(u * u + v * v)
            lists[b].add(rho)
            // Judge the wall only by points that face sideways: a lid or a base lies at the right
            // distance too, but its normals run along the axis and say nothing about the wall.
            val along = abs(nx[i] * d[0] + ny[i] * d[1] + nz[i] * d[2])
            if (abs(rho - r0) < 0.5f * r0 + 0.004f && along < 0.7f) {
                tilt[b] += along
                align[b] += abs((nx[i] * e1[0] + ny[i] * e1[1] + nz[i] * e1[2]) * u + (nx[i] * e2[0] + ny[i] * e2[1] + nz[i] * e2[2]) * v) / max(1e-6f, rho)
                near[b]++
            }
            angles[b][(((atan2(v, u) + Math.PI) / (2 * Math.PI) * 36).toInt()).coerceIn(0, 35)] = true
        }
        // A bin is a ring when the distances to the axis agree with each other.
        val rb = FloatArray(nb) { -1f }
        for (b in 0 until nb) {
            val l = lists[b]
            if (l.size < 6) continue
            l.sort()
            val med = l[l.size / 2]
            val spread = l[l.size * 3 / 4] - l[l.size / 4]
            if (med > 0.006f && spread < max(0.008f, 0.3f * med)) rb[b] = med
        }
        val out = ArrayList<FitTube>()
        var count = 0
        var b = 0
        while (b < nb) {
            if (rb[b] < 0f) { b++; continue }
            var e = b; var sum = rb[b]; var cnt = 1; var gap = 0
            val vals = arrayListOf(rb[b])
            while (e + 1 < nb) {
                val nxt = rb[e + 1]
                if (nxt < 0f) { if (++gap > 1) break; e++; continue }
                val mean = sum / cnt
                if (abs(nxt - mean) > max(0.2f * mean, 0.006f)) break
                gap = 0; e++; sum += nxt; cnt++; vals.add(nxt)
            }
            while (e > b && rb[e] < 0f) e--
            if (cnt >= 3) {
                vals.sort()
                val r = vals[vals.size / 2]
                val seen = BooleanArray(36); var pts = 0; var lean = 0f; var radial = 0f; var nn = 0
                for (q in b..e) { pts += lists[q].size; lean += tilt[q]; radial += align[q]; nn += near[q]; for (a in 0 until 36) if (angles[q][a]) seen[a] = true }
                val t0 = tmin + b * bin; val t1 = tmin + (e + 1) * bin
                // A real tube shows at least a quarter of its circumference and is longer than it is thin.
                // Its surface must also run parallel to the axis (this is what rejects balls and domes).
                // (at least ~70 degrees of arc, in 10-degree sectors: a flat strip never shows that much)
                // Normals must fan out around the axis too: the edge of a slab has the right distances
                // but its normals only point two ways.
                // And the points must curve: a flat strip can lie within tolerance of a wide cylinder
                // that merely touches it, but then it has no depth.
                if (seen.count { it } >= 7 && t1 - t0 >= max(0.05f, 1.2f * r) && lean / max(1, nn) < 0.3f && radial / max(1, nn) > 0.93f &&
                    depthOf(px, py, pz, tmp, m, cy, d, e1, e2, t0, t1, r) > 0.05f * r) {
                    out.add(sectionAxis(px, py, pz, tmp, m, cy, d, e1, e2, t0, t1, r, tubeColor(px, py, pz, col, tmp, m, cy, d, t0, t1), pts))
                    count += pts
                }
            }
            b = e + 1
        }
        return if (out.isEmpty()) null else Pair(out, count)
    }

    /**
     * Final axis of one tube section: a straight line through the centres of thin slices
     * (each centre from a circle fit with the known radius band), which is much less sensitive
     * to noisy normals than the first estimate.
     */
    private fun sectionAxis(px: FloatArray, py: FloatArray, pz: FloatArray, idx: IntArray, m: Int, cy: FloatArray, d: FloatArray,
                            e1: FloatArray, e2: FloatArray, t0: Float, t1: Float, r: Float, color: Int, pts: Int): FitTube {
        val slices = ((t1 - t0) / 0.03f).toInt().coerceIn(2, 40)
        val acc = Array(slices) { DoubleArray(9) }   // sxx sxy syy sx sy sxz syz sz n
        val eps = tubeEps(r) * 2.5f
        val lo = t0 + 0.08f * (t1 - t0); val hi = t1 - 0.08f * (t1 - t0)   // stay clear of the ends (bends, joints)
        for (q in 0 until m) {
            val i = idx[q]
            val vx = px[i] - cy[0]; val vy = py[i] - cy[1]; val vz = pz[i] - cy[2]
            val t = vx * d[0] + vy * d[1] + vz * d[2]
            if (t < lo || t > hi) continue
            val x = (vx * e1[0] + vy * e1[1] + vz * e1[2]).toDouble(); val y = (vx * e2[0] + vy * e2[1] + vz * e2[2]).toDouble()
            if (abs(sqrt(x * x + y * y) - r) > eps) continue
            val a = acc[((t - lo) / (hi - lo) * slices).toInt().coerceIn(0, slices - 1)]
            val z = x * x + y * y
            a[0] += x * x; a[1] += x * y; a[2] += y * y; a[3] += x; a[4] += y; a[5] += x * z; a[6] += y * z; a[7] += z; a[8] += 1.0
        }
        // Slice centres (u, v) against position along the axis: fit u(t), v(t) as straight lines.
        var n = 0.0; var st = 0.0; var stt = 0.0; var su = 0.0; var sv = 0.0; var stu = 0.0; var stv = 0.0
        for (k in 0 until slices) {
            val a = acc[k]
            if (a[8] < 12) continue
            val sol = solve3(doubleArrayOf(a[0], a[1], a[3], a[1], a[2], a[4], a[3], a[4], a[8]), doubleArrayOf(-a[5], -a[6], -a[7])) ?: continue
            val u = -sol[0] / 2; val v = -sol[1] / 2
            if (u * u + v * v > (0.6 * r) * (0.6 * r)) continue      // a wild slice
            val t = (lo + (k + 0.5f) * (hi - lo) / slices).toDouble()
            n += 1; st += t; stt += t * t; su += u; sv += v; stu += t * u; stv += t * v
        }
        val den = n * stt - st * st
        if (n < 3 || abs(den) < 1e-12) return FitTube(floatArrayOf(cy[0], cy[1], cy[2]), d.copyOf(), t0, t1, r, color, pts)
        val bu = (n * stu - st * su) / den; val au = (su - bu * st) / n
        val bv = (n * stv - st * sv) / den; val av = (sv - bv * st) / n
        var dx = d[0] + (e1[0] * bu + e2[0] * bv).toFloat(); var dy = d[1] + (e1[1] * bu + e2[1] * bv).toFloat(); var dz = d[2] + (e1[2] * bu + e2[2] * bv).toFloat()
        val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
        // New axis passes through the fitted centre at the middle of the section.
        val tm = ((t0 + t1) / 2).toDouble()
        val um = au + bu * tm; val vm = av + bv * tm
        val mx = cy[0] + d[0] * tm.toFloat() + (e1[0] * um + e2[0] * vm).toFloat()
        val my = cy[1] + d[1] * tm.toFloat() + (e1[1] * um + e2[1] * vm).toFloat()
        val mz = cy[2] + d[2] * tm.toFloat() + (e1[2] * um + e2[2] * vm).toFloat()
        val half = (t1 - t0) / 2
        return FitTube(floatArrayOf(mx, my, mz), floatArrayOf(dx, dy, dz), -half, half, r, color, pts)
    }

    private fun bestBendRadius(px: FloatArray, py: FloatArray, pz: FloatArray, total: Int, corner: FloatArray,
                               ua: FloatArray, ub: FloatArray, turn: Float, r: Float, guess: Float): Float {
        val reach = max(8f * r, 0.25f)
        // Even sample of the points around the corner (not just the first ones in the array).
        var inReach = 0
        for (i in 0 until total) {
            val dx = px[i] - corner[0]; val dy = py[i] - corner[1]; val dz = pz[i] - corner[2]
            if (dx * dx + dy * dy + dz * dz < reach * reach) inReach++
        }
        val step = max(1, inReach / 4000)
        val near = IntArray(inReach / step + 1); var m = 0; var seen = 0
        for (i in 0 until total) {
            val dx = px[i] - corner[0]; val dy = py[i] - corner[1]; val dz = pz[i] - corner[2]
            if (dx * dx + dy * dy + dz * dz < reach * reach) { if (seen % step == 0 && m < near.size) near[m++] = i; seen++ }
        }
        if (m < 40) return guess
        val th = tan(turn / 2); val eps = tubeEps(r)
        val k = -(ub[0] * ua[0] + ub[1] * ua[1] + ub[2] * ua[2])
        var mx = -ub[0] - k * ua[0]; var my = -ub[1] - k * ua[1]; var mz = -ub[2] - k * ua[2]
        val ml = sqrt(mx * mx + my * my + mz * mz); mx /= ml; my /= ml; mz /= ml
        val ax = ua[1] * mz - ua[2] * my; val ay = ua[2] * mx - ua[0] * mz; val az = ua[0] * my - ua[1] * mx
        var best = guess; var bestCost = Float.MAX_VALUE
        var rb = 1.15f * r
        val rbMax = min(reach * 0.8f / th, 8f * r)
        while (rb <= rbMax) {
            val t = rb * th
            val cx = corner[0] - ua[0] * t + mx * rb; val cy = corner[1] - ua[1] * t + my * rb; val cz = corner[2] - ua[2] * t + mz * rb
            var cost = 0f
            for (q in 0 until m) {
                val p = near[q]
                val x = px[p] - corner[0]; val y = py[p] - corner[1]; val z = pz[p] - corner[2]
                val sa = -(x * ua[0] + y * ua[1] + z * ua[2]); val sb = -(x * ub[0] + y * ub[1] + z * ub[2])
                var d = Float.MAX_VALUE
                if (sa >= t) { val rx = x + ua[0] * sa; val ry = y + ua[1] * sa; val rz = z + ua[2] * sa; d = sqrt(rx * rx + ry * ry + rz * rz) }
                if (sb >= t) { val rx = x + ub[0] * sb; val ry = y + ub[1] * sb; val rz = z + ub[2] * sb; d = min(d, sqrt(rx * rx + ry * ry + rz * rz)) }
                val vx = px[p] - cx; val vy = py[p] - cy; val vz = pz[p] - cz
                val h = vx * ax + vy * ay + vz * az
                val qx = vx - ax * h; val qy = vy - ay * h; val qz = vz - az * h
                val rho = sqrt(qx * qx + qy * qy + qz * qz)
                val ang = atan2((qx * ua[0] + qy * ua[1] + qz * ua[2]) / max(1e-6f, rho), -(qx * mx + qy * my + qz * mz) / max(1e-6f, rho))
                if (ang >= 0f && ang <= turn) d = min(d, sqrt((rho - rb) * (rho - rb) + h * h))
                // Truncated squared distance: points on other objects cost the same for every radius.
                val e = min(abs(d - r), eps)
                cost += e * e
            }
            if (cost < bestCost) { bestCost = cost; best = rb }
            rb += 0.1f * r
        }
        return best
    }

    /** Thickness (standard deviation along the thinnest direction) of the points on one tube section. */
    private fun depthOf(px: FloatArray, py: FloatArray, pz: FloatArray, idx: IntArray, m: Int, cy: FloatArray, d: FloatArray,
                        e1: FloatArray, e2: FloatArray, t0: Float, t1: Float, r: Float): Float {
        var n = 0; var su = 0.0; var sv = 0.0; var suu = 0.0; var suv = 0.0; var svv = 0.0
        for (q in 0 until m) {
            val i = idx[q]
            val vx = px[i] - cy[0]; val vy = py[i] - cy[1]; val vz = pz[i] - cy[2]
            val t = vx * d[0] + vy * d[1] + vz * d[2]
            if (t < t0 || t > t1) continue
            val u = (vx * e1[0] + vy * e1[1] + vz * e1[2]).toDouble(); val v = (vx * e2[0] + vy * e2[1] + vz * e2[2]).toDouble()
            if (abs(sqrt(u * u + v * v) - r) > 0.5 * r + 0.004) continue
            n++; su += u; sv += v; suu += u * u; suv += u * v; svv += v * v
        }
        if (n < 10) return 0f
        val a = suu / n - (su / n) * (su / n); val b = suv / n - (su / n) * (sv / n); val c = svv / n - (sv / n) * (sv / n)
        // Across the axis the points of a real tube spread in two directions; take the smaller one.
        val lmin = (a + c) / 2 - sqrt(max(0.0, (a - c) * (a - c) / 4 + b * b))
        return sqrt(max(0.0, lmin)).toFloat()
    }

    private fun tubeColor(px: FloatArray, py: FloatArray, pz: FloatArray, col: IntArray, idx: IntArray, m: Int, cy: FloatArray, d: FloatArray, t0: Float, t1: Float): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (q in 0 until m) {
            val i = idx[q]
            val t = (px[i] - cy[0]) * d[0] + (py[i] - cy[1]) * d[1] + (pz[i] - cy[2]) * d[2]
            if (t < t0 || t > t1) continue
            val c = col[i]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
        }
        if (n == 0) return -0x333334
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        fun det(m: DoubleArray) = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        val dd = det(a)
        if (abs(dd) < 1e-18) return null
        val out = DoubleArray(3)
        for (c in 0 until 3) { val m = a.copyOf(); for (r in 0 until 3) m[r * 3 + c] = b[r]; out[c] = det(m) / dd }
        return out
    }

    /** Least-squares plane through the inliers, its largest connected patch, and its outline: round or rectangular. */
    private fun refinePlane(total: Int, px: FloatArray, py: FloatArray, pz: FloatArray, nx: FloatArray, ny: FloatArray, nz: FloatArray,
                            col: IntArray, alive: BooleanArray, seed: Int, tmp: IntArray): Pair<FitPlane, IntArray>? {
        var n = floatArrayOf(nx[seed], ny[seed], nz[seed])
        var c = floatArrayOf(px[seed], py[seed], pz[seed])
        var m = 0
        repeat(2) {
            m = 0
            for (i in 0 until total) if (alive[i]) {
                val d = n[0] * (px[i] - c[0]) + n[1] * (py[i] - c[1]) + n[2] * (pz[i] - c[2])
                if (abs(d) < EPS * 1.3f && abs(n[0] * nx[i] + n[1] * ny[i] + n[2] * nz[i]) > 0.7f) tmp[m++] = i
            }
            if (m < 30) return null
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (q in 0 until m) { cx += px[tmp[q]]; cy += py[tmp[q]]; cz += pz[tmp[q]] }
            cx /= m; cy /= m; cz /= m
            val cov = DoubleArray(9); var sn = 0f
            for (q in 0 until m) {
                val i = tmp[q]
                val v = doubleArrayOf(px[i] - cx, py[i] - cy, pz[i] - cz)
                for (a in 0 until 3) for (b in 0 until 3) cov[a * 3 + b] += v[a] * v[b]
                sn += n[0] * nx[i] + n[1] * ny[i] + n[2] * nz[i]
            }
            val nn = smallestAxis(cov)
            val keep = nn[0] * n[0] + nn[1] * n[1] + nn[2] * n[2]
            // Face the side the camera saw (the points' own normals).
            val flip = if ((keep >= 0f) == (sn >= 0f)) 1f else -1f
            n = floatArrayOf(nn[0] * flip, nn[1] * flip, nn[2] * flip)
            c = floatArrayOf(cx.toFloat(), cy.toFloat(), cz.toFloat())
        }
        val (e1, e2) = basis(n)
        // Largest connected patch on a 2.5 cm grid.
        val cell = 0.025f
        val grid = LongIntMap(m)
        val cu = IntArray(m); val cv = IntArray(m)
        val cells = ArrayList<Long>()
        for (q in 0 until m) {
            val i = tmp[q]
            cu[q] = floor(((px[i] - c[0]) * e1[0] + (py[i] - c[1]) * e1[1] + (pz[i] - c[2]) * e1[2]) / cell).toInt()
            cv[q] = floor(((px[i] - c[0]) * e2[0] + (py[i] - c[1]) * e2[1] + (pz[i] - c[2]) * e2[2]) / cell).toInt()
            val key = LongIntMap.pack(cu[q], cv[q], 0)
            if (grid.get(key) < 0) { grid.put(key, cells.size); cells.add(key) }
        }
        val label = IntArray(cells.size) { -1 }; val weight = IntArray(cells.size)
        for (q in 0 until m) weight[grid.get(LongIntMap.pack(cu[q], cv[q], 0))]++
        var bestL = -1; var bestW = 0; var nl = 0
        val stack = IntArray(cells.size)
        for (s in cells.indices) {
            if (label[s] >= 0) continue
            var sp = 0; stack[sp++] = s; label[s] = nl; var w = 0
            while (sp > 0) {
                val cc = stack[--sp]; w += weight[cc]
                val x = LongIntMap.ux(cells[cc]); val y = LongIntMap.uy(cells[cc])
                for (dy in -1..1) for (dx in -1..1) {
                    val o = grid.get(LongIntMap.pack(x + dx, y + dy, 0))
                    if (o >= 0 && label[o] < 0) { label[o] = nl; stack[sp++] = o }
                }
            }
            if (w > bestW) { bestW = w; bestL = nl }
            nl++
        }
        val keep = IntArray(bestW); var kk = 0
        var occupied = 0
        for (s in cells.indices) if (label[s] == bestL) occupied++
        var su = 0.0; var sv = 0.0
        val uu = FloatArray(bestW); val vv = FloatArray(bestW)
        for (q in 0 until m) if (label[grid.get(LongIntMap.pack(cu[q], cv[q], 0))] == bestL) {
            val i = tmp[q]
            uu[kk] = (px[i] - c[0]) * e1[0] + (py[i] - c[1]) * e1[1] + (pz[i] - c[2]) * e1[2]
            vv[kk] = (px[i] - c[0]) * e2[0] + (py[i] - c[1]) * e2[1] + (pz[i] - c[2]) * e2[2]
            keep[kk++] = i
        }
        if (kk < 30) return null
        // Flat means flat everywhere: on a real plane the normals agree; on a curved patch (a bulb,
        // a strip along a leg) they swing by tens of degrees.
        run {
            var agree = 0.0
            for (q in 0 until kk) { val i = keep[q]; agree += abs(n[0] * nx[i] + n[1] * ny[i] + n[2] * nz[i]) }
            if (agree / kk < 0.95) return null
        }
        // Flatness: on a bulging surface (ball, wide column) the distance from the plane grows with
        // the square of the distance from the middle. Reject patches curved tighter than ~1.5 m.
        run {
            var gu = 0.0; var gv = 0.0
            for (q in 0 until kk) { gu += uu[q]; gv += vv[q] }
            gu /= kk; gv /= kk
            var sq = 0.0; var sd = 0.0; var sqq = 0.0; var sqd = 0.0
            for (q in 0 until kk) {
                val i = keep[q]
                val dd = (n[0] * (px[i] - c[0]) + n[1] * (py[i] - c[1]) + n[2] * (pz[i] - c[2])).toDouble()
                val r2 = (uu[q] - gu) * (uu[q] - gu) + (vv[q] - gv) * (vv[q] - gv)
                sq += r2; sd += dd; sqq += r2 * r2; sqd += r2 * dd
            }
            val den = kk * sqq - sq * sq
            if (den > 1e-12 && abs((kk * sqd - sq * sd) / den) > 0.33) return null
        }
        // Centre from the occupied cells so uneven point density does not shift it.
        var cells2 = 0
        for (s in cells.indices) if (label[s] == bestL) { su += (LongIntMap.ux(cells[s]) + 0.5) * cell; sv += (LongIntMap.uy(cells[s]) + 0.5) * cell; cells2++ }
        val mu = (su / cells2).toFloat(); val mv = (sv / cells2).toFloat()
        val area = occupied * cell * cell
        // Round outline?
        val rho = FloatArray(kk) { sqrt((uu[it] - mu) * (uu[it] - mu) + (vv[it] - mv) * (vv[it] - mv)) }
        rho.sort()
        val rad = rho[(kk * 0.985f).toInt().coerceAtMost(kk - 1)] / 0.9925f   // 98.5% of the points lie within 99.25% of the radius
        val discFill = area / (Math.PI.toFloat() * rad * rad)
        // Rectangular outline along the principal directions.
        var a = 0.0; var b = 0.0; var d = 0.0
        for (q in 0 until kk) { val x = uu[q] - mu; val y = vv[q] - mv; a += x * x; b += x * y; d += y * y }
        val ang = 0.5 * atan2(2 * b, a - d)
        val ca = kotlin.math.cos(ang).toFloat(); val sa = kotlin.math.sin(ang).toFloat()
        val pu = FloatArray(kk) { (uu[it] - mu) * ca + (vv[it] - mv) * sa }; val pv = FloatArray(kk) { -(uu[it] - mu) * sa + (vv[it] - mv) * ca }
        pu.sort(); pv.sort()
        val lo = (kk * 0.01f).toInt(); val hi = (kk * 0.99f).toInt().coerceAtMost(kk - 1)
        // 1st..99th percentile of an evenly covered side spans 98% of it: stretch back to the full side.
        val um = (pu[lo] + pu[hi]) / 2; val vm = (pv[lo] + pv[hi]) / 2
        val u0 = um - (pu[hi] - pu[lo]) / 1.96f; val u1 = um + (pu[hi] - pu[lo]) / 1.96f
        val v0 = vm - (pv[hi] - pv[lo]) / 1.96f; val v1 = vm + (pv[hi] - pv[lo]) / 1.96f
        if (max(u1 - u0, v1 - v0) < 0.06f) return null
        val color = meanColor(col, keep, kk)
        // Round or rectangular, from how the points spread (independent of size and density):
        // a disc has equal spread both ways and (variance sum) * 2 / R^2 = 1; any rectangle gives 2/3.
        val varU = a / kk; val varV = d / kk
        val tr = varU + varV
        val disc2 = sqrt(max(0.0, (varU - varV) * (varU - varV) + 4 * (b / kk) * (b / kk)))
        val vMin = (tr - disc2) / 2; val vMax = (tr + disc2) / 2
        val roundness = tr * 2 / max(1e-9, (rad * rad).toDouble())
        val isDisc = roundness > 0.88 && vMin > 0.75 * vMax && rad > 0.02f && discFill > 0.45f
        val plane = if (isDisc) {
            val cc = floatArrayOf(c[0] + e1[0] * mu + e2[0] * mv, c[1] + e1[1] * mu + e2[1] * mv, c[2] + e1[2] * mu + e2[2] * mv)
            FitPlane(cc, n, e1, e2, true, rad, rad, rad, color, kk)
        } else {
            val mcu = (u0 + u1) / 2; val mcv = (v0 + v1) / 2
            val ax = floatArrayOf(e1[0] * ca + e2[0] * sa, e1[1] * ca + e2[1] * sa, e1[2] * ca + e2[2] * sa)
            val ay = floatArrayOf(-e1[0] * sa + e2[0] * ca, -e1[1] * sa + e2[1] * ca, -e1[2] * sa + e2[2] * ca)
            val ox = mu + mcu * ca - mcv * sa; val oy = mv + mcu * sa + mcv * ca
            val cc = floatArrayOf(c[0] + e1[0] * ox + e2[0] * oy, c[1] + e1[1] * ox + e2[1] * oy, c[2] + e1[2] * ox + e2[2] * oy)
            FitPlane(cc, n, ax, ay, false, 0f, (u1 - u0) / 2, (v1 - v0) / 2, color, kk)
        }
        return Pair(plane, keep)
    }

    /**
     * Joins tube ends that point at each other with a bend: the two axes must (almost) meet,
     * the thickness must match, and the turn must be between 20° and 135°. The tubes are
     * trimmed or extended so the bend is tangent to both.
     */
    private fun joinTubes(tubes: List<FitTube>, px: FloatArray, py: FloatArray, pz: FloatArray, total: Int): List<FitElbow> {
        val out = ArrayList<FitElbow>()
        val used = HashSet<Int>()   // tube index * 2 + end
        for (i in tubes.indices) for (j in i + 1 until tubes.size) {
            val a = tubes[i]; val b = tubes[j]
            val r = (a.r + b.r) / 2
            if (abs(a.r - b.r) > 0.25f * r) continue
            var best: FloatArray? = null; var bestD = Float.MAX_VALUE; var bi = 0; var bj = 0
            for (ea in 0 until 2) for (eb in 0 until 2) {
                if (i * 2 + ea in used || j * 2 + eb in used) continue
                val pa = a.end(ea == 0); val pb = b.end(eb == 0)
                // Unit vectors leaving each tube through this end.
                val sa = if (ea == 0) -1f else 1f; val sb = if (eb == 0) -1f else 1f
                val ua = floatArrayOf(a.d[0] * sa, a.d[1] * sa, a.d[2] * sa); val ub = floatArrayOf(b.d[0] * sb, b.d[1] * sb, b.d[2] * sb)
                // Closest points of the two axis lines.
                val wx = pa[0] - pb[0]; val wy = pa[1] - pb[1]; val wz = pa[2] - pb[2]
                val bq = ua[0] * ub[0] + ua[1] * ub[1] + ua[2] * ub[2]
                val dq = ua[0] * wx + ua[1] * wy + ua[2] * wz; val eq = ub[0] * wx + ub[1] * wy + ub[2] * wz
                val den = 1f - bq * bq
                if (den < 0.1f) continue                          // parallel: no corner
                val ta = (bq * eq - dq) / den; val tb = (eq - bq * dq) / den
                if (ta < -r || tb < -r) continue                  // the corner must be ahead of both ends
                if (ta > max(6f * r, 0.3f) || tb > max(6f * r, 0.3f)) continue
                val qa = floatArrayOf(pa[0] + ua[0] * ta, pa[1] + ua[1] * ta, pa[2] + ua[2] * ta)
                val qb = floatArrayOf(pb[0] + ub[0] * tb, pb[1] + ub[1] * tb, pb[2] + ub[2] * tb)
                val miss = sqrt((qa[0] - qb[0]) * (qa[0] - qb[0]) + (qa[1] - qb[1]) * (qa[1] - qb[1]) + (qa[2] - qb[2]) * (qa[2] - qb[2]))
                if (miss > 1.5f * r) continue
                val turn = acos((-bq).coerceIn(-1f, 1f))          // 0 = straight on
                if (turn < 0.35f || turn > 2.36f) continue
                val score = ta + tb + miss
                if (score < bestD) {
                    bestD = score; bi = ea; bj = eb
                    best = floatArrayOf((qa[0] + qb[0]) / 2, (qa[1] + qb[1]) / 2, (qa[2] + qb[2]) / 2, ua[0], ua[1], ua[2], ub[0], ub[1], ub[2], max(0f, ta), max(0f, tb), turn)
                }
            }
            val q = best ?: continue
            val ua = floatArrayOf(q[3], q[4], q[5]); val ub = floatArrayOf(q[6], q[7], q[8]); val turn = q[11]
            val th = tan(turn / 2)
            // Bend radius: try a range and keep the one whose tube-bend-tube shape passes through
            // the most scanned points around the corner. (Where the tubes appear to stop is only
            // a first guess: the start of a bend still looks like tube.)
            val guess = max(min(q[9], q[10]) / th, 1.15f * r)
            val rb = bestBendRadius(px, py, pz, total, q, ua, ub, turn, r, guess)
            val t = rb * th
            val start = floatArrayOf(q[0] - ua[0] * t, q[1] - ua[1] * t, q[2] - ua[2] * t)
            val endB = floatArrayOf(q[0] - ub[0] * t, q[1] - ub[1] * t, q[2] - ub[2] * t)
            // Trim or extend both tubes to the tangent points.
            val ta = (start[0] - a.a[0]) * a.d[0] + (start[1] - a.a[1]) * a.d[1] + (start[2] - a.a[2]) * a.d[2]
            val tb = (endB[0] - b.a[0]) * b.d[0] + (endB[1] - b.a[1]) * b.d[1] + (endB[2] - b.a[2]) * b.d[2]
            if (bi == 0) { if (ta >= a.t1 - 0.01f) continue; a.t0 = ta } else { if (ta <= a.t0 + 0.01f) continue; a.t1 = ta }
            if (bj == 0) { if (tb >= b.t1 - 0.01f) continue; b.t0 = tb } else { if (tb <= b.t0 + 0.01f) continue; b.t1 = tb }
            // The path leaves along -ub; the bend centre is on that side of the start.
            val k = -(ub[0] * ua[0] + ub[1] * ua[1] + ub[2] * ua[2])
            var mx = -ub[0] - k * ua[0]; var my = -ub[1] - k * ua[1]; var mz = -ub[2] - k * ua[2]
            val ml = sqrt(mx * mx + my * my + mz * mz); mx /= ml; my /= ml; mz /= ml
            val center = floatArrayOf(start[0] + mx * rb, start[1] + my * rb, start[2] + mz * rb)
            val axis = floatArrayOf(ua[1] * mz - ua[2] * my, ua[2] * mx - ua[0] * mz, ua[0] * my - ua[1] * mx)
            out.add(FitElbow(start, ua, center, axis, turn, rb, r, a.color).also { it.tubeA = a; it.tubeB = b })
            used.add(i * 2 + bi); used.add(j * 2 + bj)
        }
        return out
    }
}
