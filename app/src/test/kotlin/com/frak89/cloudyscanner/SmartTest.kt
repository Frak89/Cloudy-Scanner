package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.core.MeshData
import com.frak89.cloudyscanner.core.SmartModel
import com.frak89.cloudyscanner.core.SmartOptions
import com.frak89.cloudyscanner.core.SmartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SmartTest {
    private class Cloud { val p = ArrayList<Float>(); val n = ArrayList<Float>()
        fun add(x: Float, y: Float, z: Float, a: Float, b: Float, c: Float) { p.add(x); p.add(y); p.add(z); n.add(a); n.add(b); n.add(c) }
        val size get() = p.size / 3 }
    private fun g(r: Random, s: Float) = (r.nextGaussian() * s).toFloat()

    /** Vertical cylinder wall around (cx, cz), tilted by [tilt] radians about Z to imitate an imperfect scan. */
    private fun wall(c: Cloud, r: Random, cx: Float, cz: Float, y0: Float, y1: Float, rad: Float, count: Int, noise: Float, tilt: Float = 0f) {
        for (i in 0 until count) {
            val a = r.nextFloat() * 6.2832f; val y = y0 + r.nextFloat() * (y1 - y0); val rr = rad + g(r, noise)
            put(c, r, cx + cos(a) * rr, y, cz + sin(a) * rr, cos(a), 0f, sin(a), tilt)
        }
    }
    private fun put(c: Cloud, r: Random, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, tilt: Float) {
        val ct = cos(tilt); val st = sin(tilt)
        c.add(x * ct - y * st, x * st + y * ct, z, nx * ct - ny * st + g(r, 0.08f), nx * st + ny * ct + g(r, 0.08f), nz + g(r, 0.08f))
    }
    private fun flat(c: Cloud, r: Random, y: Float, count: Int, up: Float, tilt: Float, sample: () -> FloatArray) {
        for (i in 0 until count) { val q = sample(); put(c, r, q[0], y + g(r, 0.002f), q[1], 0f, up, 0f, tilt) }
    }

    private fun build(c: Cloud, level: Int, sides: Int = 0, bevel: Boolean = false, hint: String = "", details: Boolean = true): SmartResult =
        SmartModel.build(c.size, c.p.toFloatArray(), c.n.toFloatArray(), IntArray(c.size) { -0x7f7f80 }, null, SmartOptions(level, sides, bevel, details, hint))!!

    /** Every edge of faces [from, to) and n-gons must be shared by exactly two faces, in opposite directions. */
    private fun closed(m: MeshData, groupIndex: Int): Boolean {
        val gr = m.groups[groupIndex]
        val e = HashMap<Long, Int>()
        fun edge(a: Int, b: Int) { if (a == b) return; val k = a.toLong() * 10_000_000L + b; e[k] = (e[k] ?: 0) + 1 }
        for (f in gr.faceStart until gr.faceEnd) { val q = IntArray(4) { m.quads[f * 4 + it] }; val k = if (q[2] == q[3]) 3 else 4; for (i in 0 until k) edge(q[i], q[(i + 1) % k]) }
        for (gi in gr.ngonStart until gr.ngonEnd) { val q = m.ngons[gi]; for (i in q.indices) edge(q[i], q[(i + 1) % q.size]) }
        return e.all { (k, c) -> c == 1 && e[(k % 10_000_000L) * 10_000_000L + k / 10_000_000L] == 1 }
    }

    @Test fun canBecomesOneCleanSolidWithChosenSides() {
        val r = Random(3); val c = Cloud()
        val rad = 0.033f; val h = 0.12f
        wall(c, r, 0f, 0f, 0f, h, rad, 9000, 0.0015f)
        flat(c, r, h, 2500, 1f, 0f) { val a = r.nextFloat() * 6.2832f; val q = rad * sqrt(r.nextFloat()); floatArrayOf(q * cos(a), q * sin(a)) }
        for (sides in intArrayOf(6, 8, 12)) {
            val s = build(c, 0, sides)
            println("can N=$sides: ${s.info}  V=${s.mesh.vertexCount} Q=${s.mesh.quadCount} Ngon=${s.mesh.ngonCount}")
            assertEquals(1, s.solids)
            assertEquals("a plain cylinder: two rings", sides * 2, s.mesh.vertexCount)
            assertEquals(sides, s.mesh.quadCount); assertEquals(2, s.mesh.ngonCount)
            assertTrue("watertight", closed(s.mesh, 0))
            for (v in 0 until s.mesh.vertexCount) {
                assertEquals(rad, sqrt(s.mesh.pos[v * 3] * s.mesh.pos[v * 3] + s.mesh.pos[v * 3 + 2] * s.mesh.pos[v * 3 + 2]), 0.003f)
                val y = s.mesh.pos[v * 3 + 1]; assertTrue(abs(y) < 0.008f || abs(y - h) < 0.008f)
            }
            assertTrue(s.coverage > 0.95f)
        }
        // Auto sides grow with the level; chamfers add one ring per end; the can finish adds its rim.
        assertEquals(8, build(c, 0).mesh.ngons[0].size); assertEquals(16, build(c, 1).mesh.ngons[0].size); assertEquals(32, build(c, 2).mesh.ngons[0].size)
        val bev = build(c, 1, 12, bevel = true)
        assertEquals(12 * 4, bev.mesh.vertexCount); assertTrue(closed(bev.mesh, 0))
        val can = build(c, 1, 12, hint = "Lattina di bibita")
        println("can hint: ${can.info}")
        assertEquals(12 * 6, can.mesh.vertexCount); assertTrue(closed(can.mesh, 0)); assertTrue(can.info.contains("lattina"))
        assertTrue("exact surfaces come with it", can.surfaces.size >= 6)
    }

    @Test fun roundTableIsOneSolidSquaredUpOnTheFloor() {
        val r = Random(6); val c = Cloud()
        val tilt = 0.01f       // the scan leans slightly (ARCore keeps gravity well)
        flat(c, r, 0.75f, 20000, 1f, tilt) { val a = r.nextFloat() * 6.2832f; val q = 0.45f * sqrt(r.nextFloat()); floatArrayOf(q * cos(a), q * sin(a)) }
        wall(c, r, 0f, 0f, 0.72f, 0.75f, 0.45f, 5000, 0.002f, tilt)          // rim: the top is 3 cm thick
        wall(c, r, 0f, 0f, 0.30f, 0.72f, 0.05f, 9000, 0.002f, tilt)          // thick part of the leg
        wall(c, r, 0f, 0f, 0.012f, 0.30f, 0.025f, 5000, 0.002f, tilt)        // thin part, stops 1 cm above the floor
        flat(c, r, 0f, 14000, 1f, tilt) { floatArrayOf((r.nextFloat() - 0.5f) * 1.4f, (r.nextFloat() - 0.5f) * 1.0f) }
        val s = build(c, 1, 16)
        println("round table: ${s.info}  V=${s.mesh.vertexCount} Q=${s.mesh.quadCount} Ngon=${s.mesh.ngonCount}")
        val m = s.mesh
        val table = m.groups.indexOfFirst { it.name.startsWith("Rotazione") }
        assertEquals("top, leg and foot are one solid", 1, m.groups.count { it.name.startsWith("Rotazione") })
        assertTrue(closed(m, table))
        val gr = m.groups[table]
        val vs = HashSet<Int>(); for (f in gr.faceStart until gr.faceEnd) for (k in 0 until 4) vs.add(m.quads[f * 4 + k])
        // Squared up: rings are exactly horizontal, so heights take only a few distinct values.
        val heights = vs.map { Math.round(m.pos[it * 3 + 1] * 2000f) / 2000f }.distinct().sorted()
        println("  ring heights: $heights")
        assertTrue("few clean rings, got ${heights.size}", heights.size in 4..7)
        var ax = 0f; var az = 0f; for (v in vs) { ax += m.pos[v * 3]; az += m.pos[v * 3 + 2] }; ax /= vs.size; az /= vs.size
        for (v in vs) assertEquals("axis exactly vertical", ax, m.pos[v * 3] - (m.pos[v * 3] - ax), 1e-6f)
        val radii = vs.map { Math.round(sqrt((m.pos[it * 3] - ax) * (m.pos[it * 3] - ax) + (m.pos[it * 3 + 2] - az) * (m.pos[it * 3 + 2] - az)) * 200f) / 200f }.distinct().sorted()
        println("  ring radii: $radii")
        assertTrue(radii.any { abs(it - 0.45f) < 0.012f } && radii.any { abs(it - 0.05f) < 0.006f } && radii.any { abs(it - 0.025f) < 0.006f })
        assertTrue("the floor is a single face", m.groups.any { it.name.startsWith("Faccia") && it.faceEnd - it.faceStart == 1 })
        assertTrue(s.info.contains("allineamenti"))
        assertTrue(m.quadCount < 200)
    }

    @Test fun rectangularTableIsASlabOnFourEqualLegs() {
        val r = Random(9); val c = Cloud()
        val hx = 0.5f; val hz = 0.3f; val top = 0.74f; val th = 0.04f
        flat(c, r, top, 22000, 1f, 0f) { floatArrayOf((r.nextFloat() - 0.5f) * 2 * hx, (r.nextFloat() - 0.5f) * 2 * hz) }
        for (i in 0 until 9000) {      // the four edges of the top
            val y = top - r.nextFloat() * th
            when (i % 4) {
                0 -> put(c, r, (r.nextFloat() - 0.5f) * 2 * hx, y, hz + g(r, 0.002f), 0f, 0f, 1f, 0f)
                1 -> put(c, r, (r.nextFloat() - 0.5f) * 2 * hx, y, -hz + g(r, 0.002f), 0f, 0f, -1f, 0f)
                2 -> put(c, r, hx + g(r, 0.002f), y, (r.nextFloat() - 0.5f) * 2 * hz, 1f, 0f, 0f, 0f)
                else -> put(c, r, -hx + g(r, 0.002f), y, (r.nextFloat() - 0.5f) * 2 * hz, -1f, 0f, 0f, 0f)
            }
        }
        val legR = floatArrayOf(0.024f, 0.025f, 0.026f, 0.025f)
        var k = 0
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) wall(c, r, sx * 0.44f, sz * 0.24f, 0.01f, top - th, legR[k++], 5000, 0.0015f)
        flat(c, r, 0f, 16000, 1f, 0f) { floatArrayOf((r.nextFloat() - 0.5f) * 1.6f, (r.nextFloat() - 0.5f) * 1.2f) }
        val s = build(c, 0, 8)
        println("table: ${s.info}  V=${s.mesh.vertexCount} Q=${s.mesh.quadCount} Ngon=${s.mesh.ngonCount}")
        val m = s.mesh
        for (gr in m.groups) { val v0 = if (gr.faceEnd > gr.faceStart) m.quads[gr.faceStart * 4] else 0
            println("  ${gr.name}: faces=${gr.faceEnd - gr.faceStart} ngons=${gr.ngonEnd - gr.ngonStart} at (%.2f,%.2f,%.2f)".format(m.pos[v0 * 3], m.pos[v0 * 3 + 1], m.pos[v0 * 3 + 2])) }
        val slab = m.groups.indexOfFirst { it.name.startsWith("Lastra") }
        assertTrue("the top is a slab", slab >= 0)
        assertEquals(6, m.groups[slab].faceEnd - m.groups[slab].faceStart); assertTrue(closed(m, slab))
        assertTrue(s.info.contains("lastra 100×60×4 cm"))
        val legs = m.groups.filter { it.name.startsWith("Rotazione") }
        assertEquals(4, legs.size)
        // Legs: 8-sided, same radius on all four, standing exactly on the floor and reaching the top's underside.
        val rr = ArrayList<Float>()
        for (gr in legs) {
            val ring = m.ngons[gr.ngonStart]
            assertEquals(8, ring.size)
            var cx = 0f; var cz = 0f; for (v in ring) { cx += m.pos[v * 3]; cz += m.pos[v * 3 + 2] }; cx /= 8; cz /= 8
            rr.add(sqrt((m.pos[ring[0] * 3] - cx) * (m.pos[ring[0] * 3] - cx) + (m.pos[ring[0] * 3 + 2] - cz) * (m.pos[ring[0] * 3 + 2] - cz)))
            val ys = (gr.faceStart until gr.faceEnd).flatMap { f -> (0 until 4).map { m.pos[m.quads[f * 4 + it] * 3 + 1] } }
            assertEquals("leg stands on the floor", 0f, ys.minOrNull()!!, 0.004f)
            assertEquals("leg meets the top", top - th, ys.maxOrNull()!!, 0.012f)
        }
        assertTrue("equal radii: $rr", rr.maxOrNull()!! - rr.minOrNull()!! < 1e-4f)
        assertEquals(0.025f, rr[0], 0.003f)
        assertTrue("low poly: ${m.quadCount} quads", m.quadCount <= 6 + 4 * 8 * 2 + 4)
        assertTrue(s.coverage > 0.9f)
    }
}
