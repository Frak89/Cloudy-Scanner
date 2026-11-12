package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.core.FitResult
import com.frak89.cloudyscanner.core.NurbsSurface
import com.frak89.cloudyscanner.core.PrimitiveFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NurbsTest {
    private class Cloud { val p = ArrayList<Float>(); val n = ArrayList<Float>()
        fun add(x: Float, y: Float, z: Float, a: Float, b: Float, c: Float) { p.add(x); p.add(y); p.add(z); n.add(a); n.add(b); n.add(c) }
        val size get() = p.size / 3 }

    private fun g(r: Random, s: Float) = (r.nextGaussian() * s).toFloat()

    /** Tube along +[d] from [o], seen over [arc] radians of its circumference. Noise in position and normal. */
    private fun tube(c: Cloud, r: Random, o: FloatArray, d: FloatArray, e1: FloatArray, e2: FloatArray, len: Float, rad: Float, count: Int, arc: Float, noise: Float) {
        for (i in 0 until count) {
            val t = r.nextFloat() * len; val a = (r.nextFloat() - 0.5f) * arc
            val ca = cos(a); val sa = sin(a); val rr = rad + g(r, noise)
            val nx = e1[0] * ca + e2[0] * sa; val ny = e1[1] * ca + e2[1] * sa; val nz = e1[2] * ca + e2[2] * sa
            c.add(o[0] + d[0] * t + nx * rr, o[1] + d[1] * t + ny * rr, o[2] + d[2] * t + nz * rr, nx + g(r, 0.12f), ny + g(r, 0.12f), nz + g(r, 0.12f))
        }
    }

    private fun fit(c: Cloud, seed: Long = 1): FitResult =
        PrimitiveFit.fit(c.size, c.p.toFloatArray(), c.n.toFloatArray(), IntArray(c.size) { -0x7f7f80 }, null, seed)

    @Test fun exactSurfaces() {
        val p = DoubleArray(3)
        val t = NurbsSurface.tube("T", doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(1.0, 2.5, 3.0), 0.04, 0.04, -1)
        for (i in 0..40) for (j in 0..5) {
            t.eval(i / 40.0, j / 5.0, p)
            assertEquals(0.04, sqrt((p[0] - 1) * (p[0] - 1) + (p[2] - 3) * (p[2] - 3)), 1e-9)
            assertEquals(2.0 + 0.5 * j / 5.0, p[1], 1e-9)
        }
        // Quarter bend in the XY plane: centre (0.1, 0, 0), start at the origin heading +Y, turning toward +X.
        val e = NurbsSurface.elbow("E", doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.1, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), Math.PI / 2, 0.03, -1)
        for (i in 0..40) for (j in 0..10) {
            e.eval(i / 40.0, j / 10.0, p)
            val rho = sqrt((p[0] - 0.1) * (p[0] - 0.1) + p[1] * p[1])
            assertEquals("point must lie on the torus", 0.03, sqrt((rho - 0.1) * (rho - 0.1) + p[2] * p[2]), 1e-9)
        }
        e.eval(0.0, 1.0, p)   // far end: section centred at (0.1, 0.1, 0)
        assertEquals(0.03, sqrt((p[0] - 0.1) * (p[0] - 0.1) + (p[1] - 0.1) * (p[1] - 0.1) + p[2] * p[2]), 1e-9)
        assertEquals(0.1, p[0], 1e-9)
        val d = NurbsSurface.disc("D", doubleArrayOf(0.0, 0.7, 0.0), doubleArrayOf(0.0, 1.0, 0.0), 0.45, -1)
        for (i in 0..40) { d.eval(i / 40.0, 0.0, p); assertEquals(0.45, sqrt(p[0] * p[0] + p[2] * p[2]), 1e-9); assertEquals(0.7, p[1], 1e-12) }
        // Tessellation: outward-facing, right counts.
        val m = NurbsSurface.tessellate(listOf(t, e, d))
        assertEquals(24 * t.stepsV + 24 * e.stepsV + 32 * 4, m.quadCount)
        assertEquals(32, m.faceCount - m.quadCount)
        for (v in 0 until 24) {   // first ring of the tube: normal points away from the axis
            val x = m.pos[v * 3] - 1f; val z = m.pos[v * 3 + 2] - 3f
            assertTrue(m.nrm[v * 3] * x + m.nrm[v * 3 + 2] * z > 0f)
        }
        val discFirst = m.vertexCount - 32 * 6
        assertTrue("disc faces its normal", m.nrm[discFirst * 3 + 1] > 0.99f)
        val iges = NurbsSurface.toIges(listOf(t, e, d), "test")
        assertTrue(iges.lines().filter { it.isNotEmpty() }.all { it.length == 80 })
        assertEquals(6, iges.lines().count { it.length == 80 && it[72] == 'D' })
    }

    @Test fun pipeWithNinetyDegreeBend() {
        val r = Random(4); val c = Cloud()
        val rad = 0.035f; val rb = 0.07f
        // Horizontal run along +X ending at x=0.5, quarter bend up, vertical run along +Y. Seen from the front (+Z side).
        tube(c, r, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), 0.5f, rad, 9000, 3.4f, 0.003f)
        tube(c, r, floatArrayOf(0.5f + rb, rb, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), 0.4f, rad, 7000, 3.4f, 0.003f)
        for (i in 0 until 2500) {   // the bend itself: centre (0.5, rb, 0)
            val phi = r.nextFloat() * 1.5708f; val a = (r.nextFloat() - 0.5f) * 3.4f
            val ox = sin(phi); val oy = -cos(phi)          // from bend centre to section centre
            val nx = ox * sin(a) * 1f; val ny = oy * sin(a); val nz = cos(a)
            val rr = rad + g(r, 0.003f)
            c.add(0.5f + ox * rb + nx * rr, rb + oy * rb + ny * rr, nz * rr, nx + g(r, 0.12f), ny + g(r, 0.12f), nz + g(r, 0.12f))
        }
        val f = fit(c)
        println("pipe: " + f.describe())
        for (t in f.tubes) println("  tube r=%.4f d=(%.4f,%.4f,%.4f) len=%.3f".format(t.r, t.d[0], t.d[1], t.d[2], t.length))
        for (e in f.elbows) println("  elbow angle=%.1f rb=%.3f c=(%.3f,%.3f,%.3f)".format(Math.toDegrees(e.angle.toDouble()), e.bendRadius, e.center[0], e.center[1], e.center[2]))
        assertEquals(2, f.tubes.size); assertEquals(1, f.elbows.size); assertEquals(0, f.planes.size)
        for (t in f.tubes) assertEquals(rad, t.r, 0.004f)
        val h = f.tubes.first { abs(it.d[0]) > 0.9f }; val v = f.tubes.first { abs(it.d[1]) > 0.9f }
        assertTrue("axes within 3 degrees", abs(h.d[0]) > 0.9986f && abs(v.d[1]) > 0.9986f)
        val e = f.elbows[0]
        assertEquals(Math.PI.toFloat() / 2, e.angle, 0.06f)
        assertEquals(rb, e.bendRadius, 0.025f)
        assertEquals(0.5f, e.center[0], 0.03f); assertEquals(rb, e.center[1], 0.03f)
        assertTrue(f.coverage > 0.9f)
        // The three surfaces join: end of the horizontal tube = start of the bend.
        val s = f.surfaces(); assertEquals(3, s.size)
        val m = NurbsSurface.tessellate(s)
        assertTrue(m.quadCount > 200)
    }

    @Test fun roundTableWithSteppedLeg() {
        val r = Random(8); val c = Cloud()
        // Top: disc of radius 0.45 at y = 0.72, seen from above.
        for (i in 0 until 20000) {
            val a = r.nextFloat() * 6.2832f; val q = 0.45f * sqrt(r.nextFloat())
            c.add(q * cos(a), 0.72f + g(r, 0.003f), q * sin(a), g(r, 0.1f), 1f, g(r, 0.1f))
        }
        // Leg: thick upper part (r 0.05, y 0.30..0.70), thin lower part (r 0.025, y 0.02..0.30), seen all around.
        val up = floatArrayOf(0f, 1f, 0f); val ex = floatArrayOf(1f, 0f, 0f); val ez = floatArrayOf(0f, 0f, 1f)
        tube(c, r, floatArrayOf(0f, 0.30f, 0f), up, ex, ez, 0.40f, 0.05f, 9000, 6.2832f, 0.003f)
        tube(c, r, floatArrayOf(0f, 0.02f, 0f), up, ex, ez, 0.28f, 0.025f, 5000, 6.2832f, 0.002f)
        // Floor patch: rectangle 1.2 x 0.8.
        for (i in 0 until 12000) c.add((r.nextFloat() - 0.5f) * 1.2f, g(r, 0.003f), (r.nextFloat() - 0.5f) * 0.8f, g(r, 0.1f), 1f, g(r, 0.1f))
        val f = fit(c)
        println("table: " + f.describe())
        val disc = f.planes.single { it.disc }
        assertEquals(0.45f, disc.radius, 0.02f); assertEquals(0.72f, disc.c[1], 0.006f)
        assertTrue(abs(disc.c[0]) < 0.02f && abs(disc.c[2]) < 0.02f && disc.n[1] > 0.995f)
        val floor = f.planes.single { !it.disc }
        assertEquals(0.6f, maxOf(floor.hu, floor.hv), 0.03f); assertEquals(0.4f, minOf(floor.hu, floor.hv), 0.03f)
        assertEquals("thick and thin part of the leg", 2, f.tubes.size)
        val thick = f.tubes.maxByOrNull { it.r }!!; val thin = f.tubes.minByOrNull { it.r }!!
        assertEquals(0.05f, thick.r, 0.005f); assertEquals(0.025f, thin.r, 0.004f)
        assertTrue(thick.length > 0.33f && thin.length > 0.2f)
        assertEquals(0, f.elbows.size)
        assertTrue(f.coverage > 0.9f)
    }

    @Test fun ballOnFloorIsNotMistakenForATube() {
        val r = Random(11); val c = Cloud()
        for (i in 0 until 15000) {
            val z = r.nextFloat() * 2 - 1; val a = r.nextFloat() * 6.2832f; val q = sqrt(1 - z * z)
            val rr = 0.3f + g(r, 0.003f)
            c.add(q * cos(a) * rr, 0.3f + z * rr, q * sin(a) * rr, q * cos(a) + g(r, 0.1f), z + g(r, 0.1f), q * sin(a) + g(r, 0.1f))
        }
        for (i in 0 until 15000) c.add((r.nextFloat() - 0.5f) * 2f, g(r, 0.003f), (r.nextFloat() - 0.5f) * 2f, g(r, 0.1f), 1f, g(r, 0.1f))
        val f = fit(c)
        println("ball: " + f.describe())
        assertEquals(0, f.tubes.size)
        assertEquals("only the floor is flat", 1, f.planes.size)
        assertTrue(!f.planes[0].disc && f.planes[0].count > 10000)
    }

    @Test fun survivesPhoneLevelNoise() {
        // Centimetre-level depth noise and rough normals, half the circumference visible.
        val r = Random(21); val c = Cloud()
        tube(c, r, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), 0.8f, 0.06f, 9000, 3.0f, 0.007f)
        val f = fit(c)
        println("noisy: " + f.describe())
        assertEquals(1, f.tubes.size)
        assertEquals(0.06f, f.tubes[0].r, 0.012f)
        assertTrue(abs(f.tubes[0].d[0]) > 0.99f)
    }
}
