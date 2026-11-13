package com.frak89.cloudyscanner.core

import kotlin.math.max
import kotlin.math.min

/**
 * The outline of the selected object as one camera saw it: a closed polygon in image pixels,
 * snapped to the object's edges, with the pose (camera to world, column major) and intrinsics
 * of that image. Points below [minY] (where the object was cut from its desk) are not judged.
 */
class Silhouette(
    val pose: FloatArray,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val width: Int, val height: Int,
    /** x0, y0, x1, y1, … in image pixels */
    val poly: FloatArray,
    val minY: Float = -Float.MAX_VALUE,
) {
    private val bx0: Float; private val by0: Float; private val bx1: Float; private val by1: Float
    init {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (k in 0 until poly.size / 2) { a = min(a, poly[k * 2]); b = min(b, poly[k * 2 + 1]); c = max(c, poly[k * 2]); d = max(d, poly[k * 2 + 1]) }
        bx0 = a; by0 = b; bx1 = c; by1 = d
    }

    /** Image position (u, v) of a world point, or null when it is behind the camera. */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val m = pose
        val dx = x - m[12]; val dy = y - m[13]; val dz = z - m[14]
        val px = m[0] * dx + m[1] * dy + m[2] * dz
        val py = m[4] * dx + m[5] * dy + m[6] * dz
        val pz = -(m[8] * dx + m[9] * dy + m[10] * dz)
        if (pz < 0.05f) return null
        return floatArrayOf(px / pz * fx + cx, cy - py / pz * fy)
    }

    /**
     * 1 inside the outline, 0 outside, -1 when this view says nothing about the point
     * (out of the picture, behind, or below the cut).
     */
    fun test(x: Float, y: Float, z: Float): Int {
        if (y < minY) return -1
        val p = project(x, y, z) ?: return -1
        val u = p[0]; val v = p[1]
        if (u < 1f || v < 1f || u > width - 2f || v > height - 2f) return -1
        if (u < bx0 || u > bx1 || v < by0 || v > by1) return 0
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]; val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > v) != (yj > v) && u < (xj - xi) * (v - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return if (inside) 1 else 0
    }

    companion object {
        /**
         * Outer boundary of a region in a w×h grid (Moore-neighbour trace), as cell indices in
         * order, or null when it is too small.
         */
        fun trace(inside: (Int) -> Boolean, w: Int, h: Int): IntArray? {
            var start = -1
            for (k in 0 until w * h) if (inside(k)) { start = k; break }
            if (start < 0) return null
            val dx = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1); val dy = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
            fun ins(x: Int, y: Int) = x in 0 until w && y in 0 until h && inside(y * w + x)
            val cont = ArrayList<Int>(); cont.add(start)
            var c = start; var back = 0; var steps = 0
            while (steps++ < 4 * w * h) {
                var found = -1
                for (k in 1..8) { val d = (back + k) % 8; if (ins(c % w + dx[d], c / w + dy[d])) { found = d; break } }
                if (found < 0) break
                val pd = (found + 7) % 8
                val bx = c % w + dx[pd]; val by = c / w + dy[pd]
                c = (c / w + dy[found]) * w + c % w + dx[found]
                var nb = 0
                for (d in 0 until 8) if (c % w + dx[d] == bx && c / w + dy[d] == by) { nb = d; break }
                back = nb
                if (c == start && back == 0) break
                cont.add(c)
                if (c == start) break
            }
            return if (cont.size >= 8) cont.toIntArray() else null
        }

        /**
         * Whether a world point lies inside the object according to all the views that see it:
         * true / false, or null when fewer than 3 views see it. One dissenting view is forgiven
         * (a blurred frame, a hand passing by).
         */
        fun agree(views: List<Silhouette>, x: Float, y: Float, z: Float): Boolean? {
            var seen = 0; var out = 0
            for (s in views) { val t = s.test(x, y, z); if (t < 0) continue; seen++; if (t == 0) out++ }
            if (seen < 3) return null
            return out <= (if (seen >= 6) 1 else 0)
        }
    }
}
