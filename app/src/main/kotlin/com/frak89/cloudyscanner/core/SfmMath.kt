package com.frak89.cloudyscanner.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sqrt

/** One photo in a reconstruction: pinhole camera (x right, y down, z forward) with pose X_cam = R X + t. */
class SfmView(val width: Int, val height: Int, val f: Double) {
    val cx = width / 2.0
    val cy = height / 2.0
    val r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)   // row-major
    val t = DoubleArray(3)
    var registered = false
    /** Keypoint pixel positions (x0, y0, x1, y1, …), their colours and the 3D point each one sees (-1 = none). */
    var kp = FloatArray(0)
    var color = IntArray(0)
    var pt3 = IntArray(0)

    fun center(out: DoubleArray) {
        out[0] = -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2]); out[1] = -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2]); out[2] = -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2])
    }

    /** Unit direction in the world of the ray through keypoint [i]. */
    fun ray(i: Int, out: DoubleArray) {
        val x = (kp[i * 2] - cx) / f; val y = (kp[i * 2 + 1] - cy) / f
        val dx = r[0] * x + r[3] * y + r[6]; val dy = r[1] * x + r[4] * y + r[7]; val dz = r[2] * x + r[5] * y + r[8]
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        out[0] = dx / l; out[1] = dy / l; out[2] = dz / l
    }

    /** Distance in pixels between keypoint [i] and the projection of a world point; huge when behind the camera. */
    fun reprojError(i: Int, x: Double, y: Double, z: Double): Double {
        val pz = r[6] * x + r[7] * y + r[8] * z + t[2]
        if (pz <= 1e-9) return 1e9
        val u = f * (r[0] * x + r[1] * y + r[2] * z + t[0]) / pz + cx
        val v = f * (r[3] * x + r[4] * y + r[5] * z + t[1]) / pz + cy
        return sqrt((u - kp[i * 2]) * (u - kp[i * 2]) + (v - kp[i * 2 + 1]) * (v - kp[i * 2 + 1]))
    }
}

/** Growing set of 3D points with colours. */
class SfmCloud {
    var n = 0
    var xyz = DoubleArray(3 * 4096)
    var rgb = IntArray(4096)
    fun add(x: Double, y: Double, z: Double, c: Int): Int {
        if (n == rgb.size) { xyz = xyz.copyOf(xyz.size * 2); rgb = rgb.copyOf(rgb.size * 2) }
        xyz[n * 3] = x; xyz[n * 3 + 1] = y; xyz[n * 3 + 2] = z; rgb[n] = c
        return n++
    }
}

object SfmMath {
    const val MAX_REPROJ = 3.0
    const val MAX_LINK = 4.0
    const val MIN_ANGLE = 0.026   // 1.5 degrees

    /**
     * Uses feature matches (keypoint index in [a], keypoint index in [b]) between two placed
     * photos: matches where one side already has a 3D point extend that point's track when it
     * reprojects well; the rest are triangulated, kept only when both rays agree, the point is
     * in front of both cameras and the viewing angle is wide enough. Returns the number of new points.
     */
    fun triangulate(a: SfmView, b: SfmView, matches: IntArray, nMatches: Int, cloud: SfmCloud): Int {
        val ca = DoubleArray(3); val cb = DoubleArray(3); val da = DoubleArray(3); val db = DoubleArray(3)
        a.center(ca); b.center(cb)
        var made = 0
        for (m in 0 until nMatches) {
            val i = matches[m * 2]; val j = matches[m * 2 + 1]
            val pa = a.pt3[i]; val pb = b.pt3[j]
            if (pa >= 0 || pb >= 0) {
                if (pa >= 0 && pb < 0) { if (b.reprojError(j, cloud.xyz[pa * 3], cloud.xyz[pa * 3 + 1], cloud.xyz[pa * 3 + 2]) < MAX_LINK) b.pt3[j] = pa }
                else if (pb >= 0 && pa < 0) { if (a.reprojError(i, cloud.xyz[pb * 3], cloud.xyz[pb * 3 + 1], cloud.xyz[pb * 3 + 2]) < MAX_LINK) a.pt3[i] = pb }
                continue
            }
            a.ray(i, da); b.ray(j, db)
            val wx = ca[0] - cb[0]; val wy = ca[1] - cb[1]; val wz = ca[2] - cb[2]
            val bb = da[0] * db[0] + da[1] * db[1] + da[2] * db[2]
            val d = da[0] * wx + da[1] * wy + da[2] * wz; val e = db[0] * wx + db[1] * wy + db[2] * wz
            val den = 1 - bb * bb
            if (den < 1e-9) continue
            val s1 = (bb * e - d) / den; val s2 = (e - bb * d) / den
            if (s1 <= 0 || s2 <= 0 || acos(bb.coerceIn(-1.0, 1.0)) < MIN_ANGLE) continue
            val x = (ca[0] + s1 * da[0] + cb[0] + s2 * db[0]) / 2; val y = (ca[1] + s1 * da[1] + cb[1] + s2 * db[1]) / 2; val z = (ca[2] + s1 * da[2] + cb[2] + s2 * db[2]) / 2
            if (a.reprojError(i, x, y, z) > MAX_REPROJ || b.reprojError(j, x, y, z) > MAX_REPROJ) continue
            val id = cloud.add(x, y, z, a.color[i])
            a.pt3[i] = id; b.pt3[j] = id; made++
        }
        return made
    }

    /**
     * Moves every point to the least-squares meeting place of all the rays that see it, then
     * drops observations that no longer reproject within [dropPx]. Returns how many points have
     * at least two observations.
     */
    fun intersect(views: List<SfmView>, cloud: SfmCloud, dropPx: Double = 6.0): Int {
        val n = cloud.n
        val a = DoubleArray(n * 6); val b = DoubleArray(n * 3); val cnt = IntArray(n)
        val c = DoubleArray(3); val d = DoubleArray(3)
        for (v in views) {
            if (!v.registered) continue
            v.center(c)
            for (i in v.pt3.indices) {
                val k = v.pt3[i]; if (k < 0) continue
                v.ray(i, d)
                // M = I - d d^T (symmetric: xx xy xz yy yz zz)
                val xx = 1 - d[0] * d[0]; val xy = -d[0] * d[1]; val xz = -d[0] * d[2]; val yy = 1 - d[1] * d[1]; val yz = -d[1] * d[2]; val zz = 1 - d[2] * d[2]
                a[k * 6] += xx; a[k * 6 + 1] += xy; a[k * 6 + 2] += xz; a[k * 6 + 3] += yy; a[k * 6 + 4] += yz; a[k * 6 + 5] += zz
                b[k * 3] += xx * c[0] + xy * c[1] + xz * c[2]; b[k * 3 + 1] += xy * c[0] + yy * c[1] + yz * c[2]; b[k * 3 + 2] += xz * c[0] + yz * c[1] + zz * c[2]
                cnt[k]++
            }
        }
        var good = 0
        for (k in 0 until n) {
            if (cnt[k] < 2) continue
            val xx = a[k * 6]; val xy = a[k * 6 + 1]; val xz = a[k * 6 + 2]; val yy = a[k * 6 + 3]; val yz = a[k * 6 + 4]; val zz = a[k * 6 + 5]
            val det = xx * (yy * zz - yz * yz) - xy * (xy * zz - yz * xz) + xz * (xy * yz - yy * xz)
            if (abs(det) < 1e-12) continue
            val bx = b[k * 3]; val by = b[k * 3 + 1]; val bz = b[k * 3 + 2]
            cloud.xyz[k * 3] = (bx * (yy * zz - yz * yz) - xy * (by * zz - yz * bz) + xz * (by * yz - yy * bz)) / det
            cloud.xyz[k * 3 + 1] = (xx * (by * zz - yz * bz) - bx * (xy * zz - yz * xz) + xz * (xy * bz - by * xz)) / det
            cloud.xyz[k * 3 + 2] = (xx * (yy * bz - by * yz) - xy * (xy * bz - by * xz) + bx * (xy * yz - yy * xz)) / det
            good++
        }
        for (v in views) {
            if (!v.registered) continue
            for (i in v.pt3.indices) {
                val k = v.pt3[i]; if (k < 0) continue
                if (v.reprojError(i, cloud.xyz[k * 3], cloud.xyz[k * 3 + 1], cloud.xyz[k * 3 + 2]) > dropPx) v.pt3[i] = -1
            }
        }
        return good
    }

    /** Median reprojection error in pixels over every observation (quality readout). */
    fun medianError(views: List<SfmView>, cloud: SfmCloud): Double {
        val e = ArrayList<Double>()
        for (v in views) if (v.registered) for (i in v.pt3.indices) {
            val k = v.pt3[i]; if (k >= 0) e.add(v.reprojError(i, cloud.xyz[k * 3], cloud.xyz[k * 3 + 1], cloud.xyz[k * 3 + 2]))
        }
        if (e.isEmpty()) return 0.0
        e.sort(); return e[e.size / 2]
    }

    /**
     * Surface normals for a sparse cloud: the direction of least spread among each point's
     * neighbours within [radius], flipped to face [toward] (a rough outward direction per
     * point, e.g. toward the cameras that saw it). Points with too few neighbours keep [toward].
     */
    fun normals(n: Int, pos: FloatArray, toward: FloatArray, radius: Float): FloatArray {
        val out = toward.copyOf(n * 3)
        val inv = 1f / radius
        val head = LongIntMap(n); val next = IntArray(n)
        for (i in 0 until n) {
            val k = LongIntMap.pack(floor(pos[i * 3] * inv).toInt(), floor(pos[i * 3 + 1] * inv).toInt(), floor(pos[i * 3 + 2] * inv).toInt())
            next[i] = head.get(k); head.put(k, i)
        }
        val cov = DoubleArray(9); val w = DoubleArray(3); val v = DoubleArray(9)
        val r2 = radius * radius
        for (i in 0 until n) {
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            val gx = floor(x * inv).toInt(); val gy = floor(y * inv).toInt(); val gz = floor(z * inv).toInt()
            var m = 0; var sx = 0.0; var sy = 0.0; var sz = 0.0
            cov.fill(0.0)
            for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                var j = head.get(LongIntMap.pack(gx + dx, gy + dy, gz + dz))
                while (j >= 0) {
                    val ax = pos[j * 3] - x; val ay = pos[j * 3 + 1] - y; val az = pos[j * 3 + 2] - z
                    if (ax * ax + ay * ay + az * az < r2) {
                        m++; sx += ax; sy += ay; sz += az
                        cov[0] += ax * ax; cov[1] += ax * ay; cov[2] += ax * az; cov[4] += ay * ay; cov[5] += ay * az; cov[8] += az * az
                    }
                    j = next[j]
                }
            }
            if (m < 6) continue
            cov[0] -= sx * sx / m; cov[1] -= sx * sy / m; cov[2] -= sx * sz / m; cov[4] -= sy * sy / m; cov[5] -= sy * sz / m; cov[8] -= sz * sz / m
            cov[3] = cov[1]; cov[6] = cov[2]; cov[7] = cov[5]
            PrimitiveFit.eigen3(cov, w, v)
            val k = if (w[0] <= w[1] && w[0] <= w[2]) 0 else if (w[1] <= w[2]) 1 else 2
            var nx = v[k].toFloat(); var ny = v[3 + k].toFloat(); var nz = v[6 + k].toFloat()
            if (nx * toward[i * 3] + ny * toward[i * 3 + 1] + nz * toward[i * 3 + 2] < 0f) { nx = -nx; ny = -ny; nz = -nz }
            out[i * 3] = nx; out[i * 3 + 1] = ny; out[i * 3 + 2] = nz
        }
        return out
    }
}
