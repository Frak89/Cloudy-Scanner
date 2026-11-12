package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.core.SfmCloud
import com.frak89.cloudyscanner.core.SfmMath
import com.frak89.cloudyscanner.core.SfmView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SfmTest {
    /** Camera at angle [a] on a circle of radius 3 around the origin, looking at it. */
    private fun cam(a: Double): SfmView {
        val v = SfmView(1600, 1200, 1300.0)
        val cx = 3 * sin(a); val cz = -3 * cos(a)
        // forward = -centre/|centre|, x right, y down
        val fx = -sin(a); val fz = cos(a)
        val r = doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, fx, 0.0, fz)
        System.arraycopy(r, 0, v.r, 0, 9)
        v.t[0] = -(r[0] * cx + r[2] * cz); v.t[1] = 0.0; v.t[2] = -(r[6] * cx + r[8] * cz)
        v.registered = true
        return v
    }

    @Test fun twoViewsTriangulateAndThirdRefines() {
        val rnd = Random(2)
        val n = 400
        val truth = DoubleArray(n * 3) { rnd.nextDouble() - 0.5 }
        val views = listOf(cam(-0.25), cam(0.0), cam(0.3))
        for (v in views) {
            v.kp = FloatArray(n * 2); v.color = IntArray(n) { -1 }; v.pt3 = IntArray(n) { -1 }
            for (i in 0 until n) {
                val x = truth[i * 3]; val y = truth[i * 3 + 1]; val z = truth[i * 3 + 2]
                val pz = v.r[6] * x + v.r[7] * y + v.r[8] * z + v.t[2]
                v.kp[i * 2] = (v.f * (v.r[0] * x + v.r[1] * y + v.r[2] * z + v.t[0]) / pz + v.cx + rnd.nextGaussian() * 0.4).toFloat()
                v.kp[i * 2 + 1] = (v.f * (v.r[3] * x + v.r[4] * y + v.r[5] * z + v.t[1]) / pz + v.cy + rnd.nextGaussian() * 0.4).toFloat()
            }
        }
        val cloud = SfmCloud()
        val m = IntArray(n * 2) { it / 2 }                      // keypoint i matches keypoint i
        m[0] = 0; m[1] = 7                                       // one wrong match
        val made = SfmMath.triangulate(views[0], views[1], m, n, cloud)
        assertTrue("almost all correct matches become points, the wrong one does not", made in (n - 12)..(n - 1))
        assertEquals(-1, views[0].pt3[0])
        fun err(): Double { var s = 0.0; var c = 0
            for (i in 1 until n) { val k = views[0].pt3[i]; if (k < 0) continue
                s += sqrt((cloud.xyz[k * 3] - truth[i * 3]).let { it * it } + (cloud.xyz[k * 3 + 1] - truth[i * 3 + 1]).let { it * it } + (cloud.xyz[k * 3 + 2] - truth[i * 3 + 2]).let { it * it }); c++ }
            return s / c }
        val e2 = err()
        assertTrue("two-view error $e2", e2 < 0.02)
        // Third photo: its matches extend the existing tracks instead of duplicating points.
        val before = cloud.n
        SfmMath.triangulate(views[2], views[1], m, n, cloud)
        assertTrue(cloud.n - before <= 12)
        assertTrue(views[2].pt3.count { it >= 0 } > n - 15)
        assertTrue(SfmMath.intersect(views, cloud) > n - 15)
        val e3 = err()
        assertTrue("a wider baseline must tighten the points: $e3 vs $e2", e3 < e2)
        assertTrue(SfmMath.medianError(views, cloud) < 1.0)
    }

    @Test fun normalsOfASparsePlaneFaceTheCamera() {
        val rnd = Random(5); val n = 3000
        val pos = FloatArray(n * 3); val toward = FloatArray(n * 3)
        for (i in 0 until n) {
            pos[i * 3] = rnd.nextFloat(); pos[i * 3 + 2] = rnd.nextFloat(); pos[i * 3 + 1] = 0.3f * pos[i * 3] + (rnd.nextGaussian() * 0.002).toFloat()
            toward[i * 3] = 0.3f; toward[i * 3 + 1] = 0.8f; toward[i * 3 + 2] = 0.5f       // rough guess only
        }
        val nr = SfmMath.normals(n, pos, toward, 0.06f)
        val l = sqrt(0.3f * 0.3f + 1f)
        var ok = 0
        for (i in 0 until n) if (abs(nr[i * 3] * (-0.3f / l) + nr[i * 3 + 1] * (1f / l)) > 0.98f && nr[i * 3 + 1] > 0f) ok++
        assertTrue("$ok of $n normals correct", ok > n * 0.97)
    }
}
