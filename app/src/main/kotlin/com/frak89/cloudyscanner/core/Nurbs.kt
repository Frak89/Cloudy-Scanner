package com.frak89.cloudyscanner.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A rational B-spline (NURBS) surface. Control points are stored row by row in U, as
 * x, y, z, weight (positions are not pre-multiplied by the weight).
 */
class NurbsSurface(
    val name: String,
    val degU: Int, val degV: Int,
    val nu: Int, val nv: Int,
    val knotsU: DoubleArray, val knotsV: DoubleArray,
    /** index = (iv * nu + iu) * 4 */
    val ctrl: DoubleArray,
    /** Suggested tessellation steps. */
    val stepsU: Int, val stepsV: Int,
    val closedU: Boolean,
    val color: Int,
) {
    private fun span(n: Int, p: Int, u: Double, k: DoubleArray): Int {
        if (u >= k[n]) return n - 1
        var lo = p; var hi = n
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (u < k[mid]) hi = mid else lo = mid }
        return lo
    }

    private fun basis(i: Int, u: Double, p: Int, k: DoubleArray, out: DoubleArray) {
        val left = DoubleArray(p + 1); val right = DoubleArray(p + 1)
        out[0] = 1.0
        for (j in 1..p) {
            left[j] = u - k[i + 1 - j]; right[j] = k[i + j] - u
            var saved = 0.0
            for (r in 0 until j) {
                val t = out[r] / (right[r + 1] + left[j - r])
                out[r] = saved + right[r + 1] * t
                saved = left[j - r] * t
            }
            out[j] = saved
        }
    }

    /** Point on the surface for u, v in 0..1. */
    fun eval(u: Double, v: Double, out: DoubleArray) {
        val su = span(nu, degU, u, knotsU); val sv = span(nv, degV, v, knotsV)
        val bu = DoubleArray(degU + 1); val bv = DoubleArray(degV + 1)
        basis(su, u, degU, knotsU, bu); basis(sv, v, degV, knotsV, bv)
        var x = 0.0; var y = 0.0; var z = 0.0; var w = 0.0
        for (j in 0..degV) for (i in 0..degU) {
            val o = ((sv - degV + j) * nu + (su - degU + i)) * 4
            val b = bu[i] * bv[j] * ctrl[o + 3]
            x += b * ctrl[o]; y += b * ctrl[o + 1]; z += b * ctrl[o + 2]; w += b
        }
        out[0] = x / w; out[1] = y / w; out[2] = z / w
    }

    companion object {
        private val S = sqrt(0.5)
        private val CIRCLE_KNOTS = doubleArrayOf(0.0, 0.0, 0.0, 0.25, 0.25, 0.5, 0.5, 0.75, 0.75, 1.0, 1.0, 1.0)
        private val CX = doubleArrayOf(1.0, 1.0, 0.0, -1.0, -1.0, -1.0, 0.0, 1.0, 1.0)
        private val CY = doubleArrayOf(0.0, 1.0, 1.0, 1.0, 0.0, -1.0, -1.0, -1.0, 0.0)
        private val CW = doubleArrayOf(1.0, S, 1.0, S, 1.0, S, 1.0, S, 1.0)

        private fun put(c: DoubleArray, i: Int, x: Double, y: Double, z: Double, w: Double) { c[i * 4] = x; c[i * 4 + 1] = y; c[i * 4 + 2] = z; c[i * 4 + 3] = w }

        /** Two unit vectors perpendicular to [d] and to each other, with e1 x e2 = d. */
        fun frame(d: DoubleArray): Array<DoubleArray> {
            val h = if (kotlin.math.abs(d[0]) < 0.8) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            var e1 = cross(h, d); val l = norm(e1); e1 = doubleArrayOf(e1[0] / l, e1[1] / l, e1[2] / l)
            return arrayOf(e1, cross(d, e1))
        }

        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun norm(a: DoubleArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        /**
         * Tube with an exact circular section from [p0] to [p1]; radius may differ at the two
         * ends (a cone). The outside is the front side.
         */
        fun tube(name: String, p0: DoubleArray, p1: DoubleArray, r0: Double, r1: Double, color: Int, axis: DoubleArray? = null): NurbsSurface {
            // [axis] must be given when p0 == p1 (a flat ring between two radii).
            val d = axis?.copyOf() ?: doubleArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
            val dl = norm(d); d[0] /= dl; d[1] /= dl; d[2] /= dl
            val len = norm(doubleArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]))
            val (e1, e2) = frame(d)
            val c = DoubleArray(9 * 2 * 4)
            for (j in 0 until 2) {
                val p = if (j == 0) p0 else p1; val r = if (j == 0) r0 else r1
                for (i in 0 until 9) put(c, j * 9 + i,
                    p[0] + r * (CX[i] * e1[0] + CY[i] * e2[0]), p[1] + r * (CX[i] * e1[1] + CY[i] * e2[1]), p[2] + r * (CX[i] * e1[2] + CY[i] * e2[2]), CW[i])
            }
            val rr = max(r0, r1)
            val sv = (len / (2 * Math.PI * rr / 24)).toInt().coerceIn(1, 48)
            return NurbsSurface(name, 2, 1, 9, 2, CIRCLE_KNOTS, doubleArrayOf(0.0, 0.0, 1.0, 1.0), c, 24, sv, true, color)
        }

        /**
         * Pipe bend: a circle of radius [r] centred at [start], swept by [angle] radians around
         * the axis through [center] with direction [axis]. Exact torus patch for angles below 180°.
         */
        fun elbow(name: String, start: DoubleArray, tangent: DoubleArray, center: DoubleArray, axis: DoubleArray, angle: Double, r: Double, color: Int): NurbsSurface {
            // Section circle in the plane perpendicular to the path tangent at the start.
            val toC = doubleArrayOf(center[0] - start[0], center[1] - start[1], center[2] - start[2])
            val l = norm(toC)
            val e1 = doubleArrayOf(-toC[0] / l, -toC[1] / l, -toC[2] / l)   // pointing away from the bend centre
            val e2 = cross(tangent, e1)
            val ca = cos(angle); val sa = kotlin.math.sin(angle); val ch = cos(angle / 2)
            fun rot(p: DoubleArray): DoubleArray {
                // Rodrigues rotation of p about (center, axis) by angle
                val v = doubleArrayOf(p[0] - center[0], p[1] - center[1], p[2] - center[2])
                val k = axis; val kv = cross(k, v); val kd = dot(k, v) * (1 - ca)
                return doubleArrayOf(center[0] + v[0] * ca + kv[0] * sa + k[0] * kd, center[1] + v[1] * ca + kv[1] * sa + k[1] * kd, center[2] + v[2] * ca + kv[2] * sa + k[2] * kd)
            }
            val c = DoubleArray(9 * 3 * 4)
            for (i in 0 until 9) {
                val p = doubleArrayOf(start[0] + r * (CX[i] * e1[0] + CY[i] * e2[0]), start[1] + r * (CX[i] * e1[1] + CY[i] * e2[1]), start[2] + r * (CX[i] * e1[2] + CY[i] * e2[2]))
                val q = rot(p)
                // Foot of p on the bend axis, then the tangent-intersection point of the arc p -> q.
                val h = dot(doubleArrayOf(p[0] - center[0], p[1] - center[1], p[2] - center[2]), axis)
                val o = doubleArrayOf(center[0] + axis[0] * h, center[1] + axis[1] * h, center[2] + axis[2] * h)
                val f = 1.0 / (2 * ch * ch)
                val mid = doubleArrayOf(o[0] + (p[0] - o[0] + q[0] - o[0]) * f, o[1] + (p[1] - o[1] + q[1] - o[1]) * f, o[2] + (p[2] - o[2] + q[2] - o[2]) * f)
                put(c, i, p[0], p[1], p[2], CW[i])
                put(c, 9 + i, mid[0], mid[1], mid[2], CW[i] * ch)
                put(c, 18 + i, q[0], q[1], q[2], CW[i])
            }
            val sv = (angle / (Math.PI / 2) * 8).toInt().coerceIn(3, 16)
            return NurbsSurface(name, 2, 2, 9, 3, CIRCLE_KNOTS, doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), c, 24, sv, true, color)
        }

        /** Flat circular face: an exact NURBS circle of radius [r] joined to its centre. Front side faces [n]. */
        fun disc(name: String, center: DoubleArray, n: DoubleArray, r: Double, color: Int): NurbsSurface {
            val (e1, e2) = frame(n)
            val c = DoubleArray(9 * 2 * 4)
            // Rim first, centre second: with the circle running counter-clockwise around n this
            // makes the front side face n.
            for (i in 0 until 9) {
                put(c, i, center[0] + r * (CX[i] * e1[0] + CY[i] * e2[0]), center[1] + r * (CX[i] * e1[1] + CY[i] * e2[1]), center[2] + r * (CX[i] * e1[2] + CY[i] * e2[2]), CW[i])
                put(c, 9 + i, center[0], center[1], center[2], CW[i])
            }
            return NurbsSurface(name, 2, 1, 9, 2, CIRCLE_KNOTS, doubleArrayOf(0.0, 0.0, 1.0, 1.0), c, 32, 5, true, color)
        }

        /** Flat rectangle: centre, two in-plane unit axes and half sizes. Front side faces u x v. */
        fun rect(name: String, center: DoubleArray, u: DoubleArray, v: DoubleArray, hu: Double, hv: Double, color: Int): NurbsSurface {
            val c = DoubleArray(4 * 4)
            var k = 0
            for (sv in doubleArrayOf(-hv, hv)) for (su in doubleArrayOf(-hu, hu)) {
                put(c, k++, center[0] + u[0] * su + v[0] * sv, center[1] + u[1] * su + v[1] * sv, center[2] + u[2] * su + v[2] * sv, 1.0)
            }
            val k2 = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            return NurbsSurface(name, 1, 1, 2, 2, k2, k2, c, (hu * 2 / 0.05).toInt().coerceIn(1, 40), (hv * 2 / 0.05).toInt().coerceIn(1, 40), false, color)
        }

        /** Samples every surface into one quad mesh for display and OBJ export. */
        fun tessellate(surfaces: List<NurbsSurface>): MeshData {
            var nvTot = 0; var nqTot = 0
            for (s in surfaces) { val cu = if (s.closedU) s.stepsU else s.stepsU + 1; nvTot += cu * (s.stepsV + 1); nqTot += s.stepsU * s.stepsV }
            val pos = FloatArray(nvTot * 3); val nrm = FloatArray(nvTot * 3); val col = IntArray(nvTot); val quads = IntArray(nqTot * 4)
            var vb = 0; var q = 0
            val p = DoubleArray(3)
            for (s in surfaces) {
                val cu = if (s.closedU) s.stepsU else s.stepsU + 1
                for (j in 0..s.stepsV) for (i in 0 until cu) {
                    s.eval(i.toDouble() / s.stepsU, j.toDouble() / s.stepsV, p)
                    val o = (vb + j * cu + i) * 3
                    pos[o] = p[0].toFloat(); pos[o + 1] = p[1].toFloat(); pos[o + 2] = p[2].toFloat()
                    col[vb + j * cu + i] = s.color
                }
                for (j in 0 until s.stepsV) for (i in 0 until s.stepsU) {
                    val i1 = if (s.closedU) (i + 1) % cu else i + 1
                    val a = vb + j * cu + i; val b = vb + j * cu + i1; val c = vb + (j + 1) * cu + i1; val d = vb + (j + 1) * cu + i
                    // A collapsed edge (disc centre) is stored as a triangle: last two indices equal.
                    fun same(m: Int, n: Int) = kotlin.math.abs(pos[m * 3] - pos[n * 3]) + kotlin.math.abs(pos[m * 3 + 1] - pos[n * 3 + 1]) + kotlin.math.abs(pos[m * 3 + 2] - pos[n * 3 + 2]) < 1e-7f
                    if (same(a, b)) { quads[q] = a; quads[q + 1] = c; quads[q + 2] = d; quads[q + 3] = d }
                    else if (same(c, d)) { quads[q] = a; quads[q + 1] = b; quads[q + 2] = c; quads[q + 3] = c }
                    else { quads[q] = a; quads[q + 1] = b; quads[q + 2] = c; quads[q + 3] = d }
                    q += 4
                }
                vb += cu * (s.stepsV + 1)
            }
            // Smooth normals from the faces.
            for (f in 0 until nqTot) {
                val a = quads[f * 4]; val b = quads[f * 4 + 1]; val c = quads[f * 4 + 2]; val d = quads[f * 4 + 3]
                val e1x = pos[c * 3] - pos[a * 3]; val e1y = pos[c * 3 + 1] - pos[a * 3 + 1]; val e1z = pos[c * 3 + 2] - pos[a * 3 + 2]
                val e2x = pos[d * 3] - pos[b * 3]; val e2y = pos[d * 3 + 1] - pos[b * 3 + 1]; val e2z = pos[d * 3 + 2] - pos[b * 3 + 2]
                val x = e1y * e2z - e1z * e2y; val y = e1z * e2x - e1x * e2z; val z = e1x * e2y - e1y * e2x
                for (v in intArrayOf(a, b, c, d)) { nrm[v * 3] += x; nrm[v * 3 + 1] += y; nrm[v * 3 + 2] += z }
            }
            for (v in 0 until nvTot) {
                val l = max(1e-12f, sqrt(nrm[v * 3] * nrm[v * 3] + nrm[v * 3 + 1] * nrm[v * 3 + 1] + nrm[v * 3 + 2] * nrm[v * 3 + 2]))
                nrm[v * 3] /= l; nrm[v * 3 + 1] /= l; nrm[v * 3 + 2] /= l
            }
            return MeshData(pos, nrm, col, quads, 0f)
        }

        /** IGES 5.3 text with one entity 128 (rational B-spline surface) per surface, in metres. */
        fun toIges(surfaces: List<NurbsSurface>, title: String): String {
            fun num(d: Double): String {
                var s = String.format(java.util.Locale.US, "%.9g", d)
                if (!s.contains('.') && !s.contains('E') && !s.contains('e')) s += "."
                return s.replace("e", "E")
            }
            val sb = StringBuilder()
            fun line(body: String, sec: Char, seq: Int) { sb.append(body.padEnd(72).take(72)).append(sec).append(seq.toString().padStart(7)).append('\n') }
            line("Cloudy Scanner - $title", 'S', 1)
            fun h(s: String) = "${s.length}H$s"
            val g = listOf("1H,", "1H;", h("CloudyScanner"), h("scan.igs"), h("Cloudy Scanner"), h("1.2"), "32", "38", "6", "308", "15",
                h("scan"), "1.0", "6", h("M"), "1", "0.001", h("20260101.000000"), "1E-6", "100.0", h("user"), h("user"), "11", "0", h("20260101.000000")).joinToString(",") + ";"
            var gs = 0
            var rest = g
            while (rest.isNotEmpty()) { val cut = minOf(72, rest.length); line(rest.substring(0, cut), 'G', ++gs); rest = rest.substring(cut) }
            // Parameter data first (to know line counts), 64 columns of data per line.
            val pLines = ArrayList<List<String>>()
            for (s in surfaces) {
                val t = ArrayList<String>()
                t.add("128"); t.add((s.nu - 1).toString()); t.add((s.nv - 1).toString()); t.add(s.degU.toString()); t.add(s.degV.toString())
                t.add(if (s.closedU) "1" else "0"); t.add("0"); t.add("0"); t.add("0"); t.add("0")
                s.knotsU.forEach { t.add(num(it)) }; s.knotsV.forEach { t.add(num(it)) }
                for (i in 0 until s.nu * s.nv) t.add(num(s.ctrl[i * 4 + 3]))
                for (i in 0 until s.nu * s.nv) { t.add(num(s.ctrl[i * 4])); t.add(num(s.ctrl[i * 4 + 1])); t.add(num(s.ctrl[i * 4 + 2])) }
                t.add("0."); t.add("1."); t.add("0."); t.add("1.")
                val lines = ArrayList<String>(); var cur = StringBuilder()
                for ((k, tok) in t.withIndex()) {
                    val piece = tok + if (k == t.size - 1) ";" else ","
                    if (cur.length + piece.length > 64) { lines.add(cur.toString()); cur = StringBuilder() }
                    cur.append(piece)
                }
                lines.add(cur.toString())
                pLines.add(lines)
            }
            fun f8(v: Any) = v.toString().padStart(8)
            var pPtr = 1
            for ((i, s) in surfaces.withIndex()) {
                val de = i * 2 + 1
                line(f8(128) + f8(pPtr) + f8(0) + f8(0) + f8(0) + f8(0) + f8(0) + f8(0) + "00000000", 'D', de)
                line(f8(128) + f8(0) + f8(0) + f8(pLines[i].size) + f8(0) + f8("") + f8("") + s.name.take(8).padStart(8) + f8(0), 'D', de + 1)
                pPtr += pLines[i].size
            }
            var ps = 0
            for ((i, lines) in pLines.withIndex()) for (l in lines) {
                ps++
                sb.append(l.padEnd(64)).append(' ').append((i * 2 + 1).toString().padStart(7)).append('P').append(ps.toString().padStart(7)).append('\n')
            }
            line("S" + "1".padStart(7) + "G" + gs.toString().padStart(7) + "D" + (surfaces.size * 2).toString().padStart(7) + "P" + ps.toString().padStart(7), 'T', 1)
            return sb.toString()
        }
    }
}
