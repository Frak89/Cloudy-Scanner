package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.core.CatalogPart
import com.frak89.cloudyscanner.core.MeshData
import com.frak89.cloudyscanner.core.PartLabel
import com.frak89.cloudyscanner.core.SmartModel
import com.frak89.cloudyscanner.core.SmartOptions
import com.frak89.cloudyscanner.core.SmartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic copies of real scenes: the side table with mat, plate and candle; a light bar; an hourglass; a jar. */
class SceneTest {
    class Cloud { val p = ArrayList<Float>(); val n = ArrayList<Float>(); val c = ArrayList<Int>()
        val rnd = Random(12)
        var noise = 0.002f
        fun g(s: Float) = (rnd.nextGaussian() * s).toFloat()
        fun add(x: Float, y: Float, z: Float, a: Float, b: Float, d: Float, col: Int, noise: Float = this.noise) {
            val l = max(1e-6f, sqrt(a * a + b * b + d * d))
            p.add(x + a / l * g(noise)); p.add(y + b / l * g(noise)); p.add(z + d / l * g(noise))
            n.add(a / l + g(0.08f)); n.add(b / l + g(0.08f)); n.add(d / l + g(0.08f)); c.add(jitter(col))
        }
        private fun ch(v: Int) = (v + g(10f).toInt()).coerceIn(0, 255)
        fun jitter(col: Int): Int = (0xFF shl 24) or (ch((col shr 16) and 255) shl 16) or (ch((col shr 8) and 255) shl 8) or ch(col and 255)
        val size get() = p.size / 3
        /** Surface of revolution about the vertical line (cx, cz): radius r(y) from y0 to y1, plus optional top/bottom discs. */
        fun turned(cx: Float, cz: Float, y0: Float, y1: Float, count: Int, col: (Float) -> Int, r: (Float) -> Float) {
            for (i in 0 until count) {
                val y = y0 + rnd.nextFloat() * (y1 - y0); val a = rnd.nextFloat() * 6.2832f; val rr = r(y)
                val dr = (r(y + 0.001f) - r(y - 0.001f)) / 0.002f      // slope of the profile
                add(cx + cos(a) * rr, y, cz + sin(a) * rr, cos(a), -dr, sin(a), col(y))
            }
        }
        fun disc(cx: Float, cz: Float, y: Float, r0: Float, r1: Float, up: Float, count: Int, col: Int) {
            for (i in 0 until count) { val a = rnd.nextFloat() * 6.2832f; val q = sqrt(r0 * r0 + rnd.nextFloat() * (r1 * r1 - r0 * r0))
                add(cx + q * cos(a), y, cz + q * sin(a), 0f, up, 0f, col) }
        }
        fun floor(y: Float, hx: Float, hz: Float, count: Int, col: Int) { for (i in 0 until count) add((rnd.nextFloat() - 0.5f) * 2 * hx, y, (rnd.nextFloat() - 0.5f) * 2 * hz, 0f, 1f, 0f, col) }
    }

    private fun build(c: Cloud, level: Int, labels: List<PartLabel> = emptyList(), sides: Int = 0): SmartResult =
        SmartModel.build(c.size, c.p.toFloatArray(), c.n.toFloatArray(), c.c.toIntArray(), null, SmartOptions(level, sides, true, true, "", labels))!!

    private fun show(name: String, s: SmartResult) {
        println("$name: ${s.info}\n  V=${s.mesh.vertexCount} Q=${s.mesh.quadCount} N=${s.mesh.ngonCount} T=${s.mesh.triangleCount}")
        for (p in s.parts) println("  - ${p.name} [${p.kind}] ${p.dims} at (%.2f, %.2f, %.2f) %s".format(p.center[0], p.center[1], p.center[2], p.note))
    }

    /** Signed volume of one object: positive when its faces look outward. */
    private fun volume(m: MeshData, group: Int): Float {
        val gr = m.groups[group]
        val sub = MeshData(m.pos, m.nrm, m.col, m.quads.copyOfRange(gr.faceStart * 4, gr.faceEnd * 4), 0f, m.ngons.subList(gr.ngonStart, gr.ngonEnd))
        val t = sub.triangleIndices()
        var v = 0.0
        for (k in 0 until t.size / 3) {
            val a = t[k * 3] * 3; val b = t[k * 3 + 1] * 3; val c = t[k * 3 + 2] * 3
            val p = m.pos
            v += (p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])).toDouble()
        }
        return (v / 6).toFloat()
    }

    private fun range(m: MeshData, group: Int, axis: Int): FloatArray {
        val gr = m.groups[group]; var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (f in gr.faceStart until gr.faceEnd) for (k in 0 until 4) { val v = m.quads[f * 4 + k]; lo = minOf(lo, m.pos[v * 3 + axis]); hi = maxOf(hi, m.pos[v * 3 + axis]) }
        return floatArrayOf(lo, hi)
    }

    private fun near(parts: List<CatalogPart>, x: Float, y: Float, z: Float): CatalogPart =
        parts.filter { it.kind == "revolution" || it.kind == "prism" }.minByOrNull { (it.center[0] - x) * (it.center[0] - x) + (it.center[1] - y) * (it.center[1] - y) + (it.center[2] - z) * (it.center[2] - z) }!!

    // Colours from the photos
    private val YELLOW = 0xFFE0A81C.toInt(); private val RED = 0xFFC8303A.toInt(); private val BLUE = 0xFF3E7C96.toInt()
    private val PINK = 0xFFD08A92.toInt(); private val GOLD = 0xFFC9A02E.toInt(); private val BLACK = 0xFF1E1E22.toInt(); private val RUG = 0xFF8A6A58.toInt()

    fun sideTable(c: Cloud) {
        val top = 0.45f
        c.floor(0f, 0.6f, 0.6f, 14000, RUG)
        c.turned(0f, 0f, top - 0.02f, top, 3500, { YELLOW }) { 0.20f }                    // yellow edge of the top
        c.disc(0f, 0f, top, 0.19f, 0.20f, 1f, 800, YELLOW)                                 // rim of the top left uncovered
        c.disc(0f, 0f, top + 0.003f, 0.11f, 0.19f, 1f, 9000, RED)                          // placemat around the plate
        c.turned(0f, 0f, top + 0.003f, top + 0.023f, 3000, { PINK }) { y -> 0.06f + (y - top - 0.003f) / 0.02f * 0.05f }   // plate outside
        c.disc(0f, 0f, top + 0.023f, 0.10f, 0.11f, 1f, 900, BLUE)                          // plate rim
        c.disc(0f, 0f, top + 0.008f, 0.036f, 0.06f, 1f, 900, BLUE)                         // plate well around the candle
        c.turned(0f, 0f, top + 0.008f, top + 0.088f, 6000, { GOLD }) { 0.036f }             // candle
        c.disc(0f, 0f, top + 0.088f, 0f, 0.036f, 1f, 900, GOLD)
        // three tapered legs, splayed outwards by about 9 degrees
        for (k in 0 until 3) {
            val a = k * 2 * PI.toFloat() / 3
            val tx = 0.13f * cos(a); val tz = 0.13f * sin(a); val bx = 0.20f * cos(a); val bz = 0.20f * sin(a)
            val len = sqrt((tx - bx) * (tx - bx) + (top - 0.02f) * (top - 0.02f) + (tz - bz) * (tz - bz))
            val dx = (tx - bx) / len; val dy = (top - 0.02f) / len; val dz = (tz - bz) / len
            val e1 = floatArrayOf(-dz, 0f, dx).let { v -> val l = sqrt(v[0] * v[0] + v[2] * v[2]); floatArrayOf(v[0] / l, 0f, v[2] / l) }
            val e2 = floatArrayOf(dy * e1[2], dz * e1[0] - dx * e1[2], -dy * e1[0])
            for (i in 0 until 3500) {
                val t = c.rnd.nextFloat() * len; val r = 0.009f + 0.005f * t / len; val q = c.rnd.nextFloat() * 6.2832f
                val nx = cos(q) * e1[0] + sin(q) * e2[0]; val ny = cos(q) * e1[1] + sin(q) * e2[1]; val nz = cos(q) * e1[2] + sin(q) * e2[2]
                c.add(bx + dx * t + nx * r, dy * t + ny * r, bz + dz * t + nz * r, nx, ny, nz, BLACK, 0.0015f)
            }
        }
    }

    @Test fun sideTableBecomesSeparateObjects() {
        val c = Cloud(); sideTable(c)
        for (level in 0..2) {
            val s = build(c, level)
            show("side table L$level", s)
            val obj = s.parts.filter { it.kind == "revolution" }
            val candle = obj.first { it.name.startsWith("Cilindro") }; val plate = near(obj, 0f, 0.468f, 0f)
            val mat = obj.first { it.name.startsWith("Disco sottile") }; val top = near(obj, 0f, 0.44f, 0f)
            assertTrue("candle, plate, mat and top are four objects", setOf(candle, plate, mat, top).size == 4)
            assertEquals(0.072f, candle.radius * 2, 0.008f); assertEquals(0.40f, top.radius * 2, 0.02f); assertEquals(0.38f, mat.radius * 2, 0.025f)
            assertTrue("mat is red", Palette0.isRed(mat.color)); assertTrue("candle is gold/yellow", Palette0.isYellow(candle.color))
            val legs = obj.filter { it.name.startsWith("Gamba") }
            assertEquals("three legs", 3, legs.size)
            for (g in legs) {
                val ys = range(s.mesh, g.group, 1)
                assertEquals("leg on the floor", 0f, ys[0], 0.006f)
                assertEquals("leg under the top", 0.43f, ys[1], 0.01f)
                assertTrue("legs are splayed, not straightened", abs(g.axis[1]) < 0.995f)
            }
            for (p in obj) assertTrue("${p.name} faces outward", volume(s.mesh, p.group) > 0f)
        }
        // With names from the AI, the typical shapes come in: hollow plate, candle with its well.
        val base = build(c, 1)
        val obj = base.parts.filter { it.kind == "revolution" }
        val candle = obj.first { it.name.startsWith("Cilindro") }; val plate = near(obj, 0f, 0.468f, 0f)
        val named = build(c, 1, listOf(PartLabel(candle.center[0], candle.center[1], candle.center[2], "candela"), PartLabel(plate.center[0], plate.center[1], plate.center[2], "piatto")))
        show("side table named", named)
        val nc = named.parts.first { it.name == "candela" }; val np = named.parts.first { it.name == "piatto" }
        assertTrue(nc.labelled && np.labelled && nc.note.contains("candela") && np.note.contains("cavo"))
        assertTrue(volume(named.mesh, nc.group) > 0f && volume(named.mesh, np.group) > 0f)
        assertTrue("the candle now stands in the plate's well", range(named.mesh, nc.group, 1)[0] < range(named.mesh, np.group, 1)[1] - 0.01f)
    }

    @Test fun lightBarIsAnExtrudedVOnARoundedBase() {
        val c = Cloud()
        c.floor(0f, 0.4f, 0.4f, 12000, 0xFFE8E0D0.toInt())
        // base: rounded square 9 x 9 cm, 1.2 cm high, black
        fun rsq(a: Float): Float { val cs = abs(cos(a)); val sn = abs(sin(a)); val p = 4f; return 0.045f / Math.pow((Math.pow(cs.toDouble(), p.toDouble()) + Math.pow(sn.toDouble(), p.toDouble())), 1.0 / p).toFloat() }
        for (i in 0 until 4000) { val a = c.rnd.nextFloat() * 6.2832f; val r = rsq(a); val y = c.rnd.nextFloat() * 0.012f; c.add(r * cos(a), y, r * sin(a), cos(a), 0f, sin(a), BLACK) }
        for (i in 0 until 2500) { val a = c.rnd.nextFloat() * 6.2832f; val q = rsq(a) * sqrt(c.rnd.nextFloat()); c.add(q * cos(a), 0.012f, q * sin(a), 0f, 1f, 0f, BLACK) }
        // bar: chevron outline (V), 2 cm thick arms, 30 cm tall, white faces
        val v = listOf(floatArrayOf(-0.03f, -0.02f), floatArrayOf(0f, 0.005f), floatArrayOf(0.03f, -0.02f), floatArrayOf(0.03f, 0.0f), floatArrayOf(0f, 0.025f), floatArrayOf(-0.03f, 0.0f))
        val per = FloatArray(v.size); var tot = 0f
        for (k in v.indices) { val a = v[k]; val b = v[(k + 1) % v.size]; per[k] = sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1])); tot += per[k] }
        var area = 0f; for (k in v.indices) { val a = v[k]; val b = v[(k + 1) % v.size]; area += a[0] * b[1] - b[0] * a[1] }
        val sg = if (area > 0) 1f else -1f
        for (k in v.indices) {
            val a = v[k]; val b = v[(k + 1) % v.size]
            val nx = sg * (b[1] - a[1]) / per[k]; val nz = -sg * (b[0] - a[0]) / per[k]
            repeat((9000 * per[k] / tot).toInt()) { val t = c.rnd.nextFloat(); c.add(a[0] + (b[0] - a[0]) * t, 0.012f + c.rnd.nextFloat() * 0.30f, a[1] + (b[1] - a[1]) * t, nx, 0f, nz, 0xFFF0F0F0.toInt()) }
        }
        for (level in intArrayOf(0, 2)) {
            val s = build(c, level)
            show("light bar L$level", s)
            val pr = s.parts.filter { it.kind == "prism" }
            assertEquals("base and bar", 2, pr.size)
            val base = pr.minByOrNull { it.center[1] }!!; val bar = pr.maxByOrNull { it.center[1] }!!
            assertEquals(0.09f, base.radius * 2, 0.012f)
            val bys = range(s.mesh, bar.group, 1); val gys = range(s.mesh, base.group, 1)
            assertEquals("base on the table", 0f, gys[0], 0.004f)
            assertEquals("bar on the base", gys[1], bys[0], 0.002f)
            assertEquals(0.312f, bys[1], 0.01f)
            assertTrue(volume(s.mesh, bar.group) > 0f && volume(s.mesh, base.group) > 0f)
            // The V is concave: its cross-section is clearly smaller than its bounding box.
            val barVol = volume(s.mesh, bar.group); val boxVol = 0.06f * 0.045f * (bys[1] - bys[0])
            assertTrue("concave V kept (${barVol / boxVol})", barVol < 0.75f * boxVol && barVol > 0.3f * boxVol)
            assertTrue("bar is white", Palette0.isWhite(bar.color))
        }
        assertTrue(build(c, 2).mesh.vertexCount > build(c, 0).mesh.vertexCount)
    }

    @Test fun hourglassIsOneTurnedObjectInTwoColours() {
        val c = Cloud()
        c.floor(0f, 0.35f, 0.35f, 10000, 0xFFE8E0D0.toInt())
        // two bulbs (radius 6 cm) joined by a narrow neck, 26 cm tall; lower half red, upper half clear grey
        fun r(y: Float): Float { val u = (y / 0.26f).coerceIn(0f, 1f); val s = sin(PI.toFloat() * u); return 0.008f + 0.052f * s * s * (if (u < 0.5f) 1f else 1f) * (1f - 0.9f * Math.pow(cos(PI * u), 40.0).toFloat()) }
        fun rr(y: Float): Float { val u = y / 0.13f; return if (u <= 1f) 0.008f + 0.052f * sin(PI.toFloat() * u.coerceIn(0f, 1f)) else 0.008f + 0.052f * sin(PI.toFloat() * (u - 1f).coerceIn(0f, 1f)) }
        c.turned(0f, 0f, 0.005f, 0.255f, 16000, { y -> if (y < 0.13f) RED else 0xFFB8B8B4.toInt() }) { y -> max(0.012f, rr(y)) }
        val lo = build(c, 0); val hi = build(c, 2)
        show("hourglass L0", lo); show("hourglass L2", hi)
        for (s in listOf(lo, hi)) {
            val obj = s.parts.filter { it.kind == "revolution" }
            assertEquals("one object", 1, obj.size)
            val h = obj[0]
            assertEquals(0.12f, h.radius * 2, 0.012f)
            assertTrue(volume(s.mesh, h.group) > 0f)
            // two colours along it, not one blend
            val gr = s.mesh.groups[h.group]
            val cols = (gr.faceStart until gr.faceEnd).map { s.mesh.col[s.mesh.quads[it * 4]] }.toSet()
            assertTrue(cols.any { Palette0.isRed(it) } && cols.any { !Palette0.isRed(it) })
        }
        assertTrue("high poly is rounder", hi.mesh.vertexCount > 2 * lo.mesh.vertexCount)
    }

    @Test fun jarNamedByTheAiBecomesHollow() {
        val c = Cloud()
        c.floor(0f, 0.3f, 0.3f, 9000, 0xFFE8E0D0.toInt())
        c.turned(0f, 0f, 0f, 0.07f, 7000, { 0xFFD89070.toInt() }) { 0.035f }
        c.disc(0f, 0f, 0.07f, 0.03f, 0.035f, 1f, 300, 0xFFD89070.toInt())
        val plain = build(c, 1, sides = 8)
        val jar = plain.parts.first { it.kind == "revolution" }
        val named = build(c, 1, listOf(PartLabel(jar.center[0], jar.center[1], jar.center[2], "barattolo di vetro")), 8)
        show("jar", named)
        val j = named.parts.first { it.name == "barattolo di vetro" }
        assertTrue(j.note.contains("cavo"))
        assertTrue("hollow: much less material than the full cylinder", volume(named.mesh, j.group) < 0.5f * volume(plain.mesh, jar.group))
        assertTrue(volume(named.mesh, j.group) > 0f)
        assertEquals(8, named.mesh.ngons[named.mesh.groups[j.group].ngonStart].size)
    }

    /** A computer mouse on a desk: egg-shaped footprint 12 x 6.4 cm, a dome 3.8 cm high at the back, turned by [ang]. */
    fun mouse(c: Cloud, cx: Float, cz: Float, ang: Float, col: Int = 0xFF202024.toInt()) {
        val a = 0.06f
        fun halfW(x: Float) = 0.032f * (1f - 0.15f * x / a)
        fun hgt(x: Float, z: Float): Float {
            val qx = x / a; val qz = z / halfW(x); val q = qx * qx + qz * qz
            if (q >= 1f) return 0f
            val top = 0.038f * (0.72f + 0.28f * (1f - qx) / 2f)
            return top * Math.pow((1f - q).toDouble(), 0.4).toFloat()
        }
        val ca = cos(ang); val sa = sin(ang)
        var made = 0
        while (made < 9000) {
            val x = (c.rnd.nextFloat() * 2 - 1) * a; val z = (c.rnd.nextFloat() * 2 - 1) * 0.04f
            val h = hgt(x, z); if (h <= 0f) continue
            val e = 0.0015f
            var gx = (hgt(x + e, z) - hgt(x - e, z)) / (2 * e); var gz = (hgt(x, z + e) - hgt(x, z - e)) / (2 * e)
            val gl = sqrt(gx * gx + gz * gz); if (gl > 25f) { gx *= 25f / gl; gz *= 25f / gl }
            // the steep rim is seen as a side: spread its points down the wall
            val y = if (gl > 6f) h * c.rnd.nextFloat() else h
            val nxl = -gx; val nzl = -gz
            c.add(cx + x * ca - z * sa, y, cz + x * sa + z * ca, nxl * ca - nzl * sa, 1f, nxl * sa + nzl * ca, col)
            made++
        }
    }

    private fun groupBox(m: MeshData, group: Int): FloatArray {
        val gr = m.groups[group]; val lo = floatArrayOf(1e9f, 1e9f, 1e9f); val hi = floatArrayOf(-1e9f, -1e9f, -1e9f)
        val vs = (gr.faceStart until gr.faceEnd).flatMap { f -> (0 until 4).map { m.quads[f * 4 + it] } } + (gr.ngonStart until gr.ngonEnd).flatMap { m.ngons[it].toList() }
        for (v in vs) for (k in 0..2) { lo[k] = minOf(lo[k], m.pos[v * 3 + k]); hi[k] = maxOf(hi[k], m.pos[v * 3 + k]) }
        return floatArrayOf(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], vs.toSet().size.toFloat())
    }

    @Test fun mouseBecomesOneSmoothFreeFormSolid() {
        val c = Cloud()
        c.floor(0f, 0.35f, 0.35f, 14000, 0xFFB08A60.toInt())
        mouse(c, 0.08f, -0.05f, 0.5f)
        var lastV = 0f
        for (level in 0..2) {
            val s = build(c, level)
            show("mouse L$level", s)
            val m = s.parts.single { it.kind == "freeform" }
            val box = groupBox(s.mesh, m.group)
            assertEquals("sits on the desk", 0f, box[1], 0.004f)
            assertEquals("3.8 cm high", 0.038f, box[4], 0.006f)
            assertEquals("footprint centre x", 0.08f, (box[0] + box[3]) / 2, 0.012f)
            assertTrue("length about 12 cm: ${m.length}", m.length in 0.105f..0.135f)
            assertTrue("width about 6.4 cm: ${m.radius * 2}", m.radius * 2 in 0.052f..0.076f)
            assertTrue("closed and outward", volume(s.mesh, m.group) > 0f)
            assertTrue("no dense skin left", s.parts.none { it.kind == "detail" })
            assertTrue(Palette0.isWhite(m.color).not() && ((m.color shr 16) and 255) < 70)
            if (level == 0) assertTrue("low poly: few vertices (${box[6]})", box[6] <= 40f)
            assertTrue("each level is finer", box[6] > lastV); lastV = box[6]
        }
    }

    @Test fun mouseNamedByTheAiKeepsItsName() {
        val c = Cloud()
        c.floor(0f, 0.35f, 0.35f, 14000, 0xFFB08A60.toInt())
        mouse(c, -0.05f, 0.04f, -1.1f)
        // a mug next to it must stay a turned object
        c.turned(0.18f, 0.1f, 0f, 0.09f, 6000, { 0xFFE8E8E8.toInt() }) { 0.04f }
        val s = build(c, 2, listOf(PartLabel(-0.05f, 0.03f, 0.04f, "mouse")))
        show("mouse named", s)
        val m = s.parts.single { it.name == "mouse" }
        assertEquals("freeform", m.kind)
        assertTrue(m.labelled)
        assertTrue(s.parts.any { it.kind == "revolution" && abs(it.center[0] - 0.18f) < 0.02f })
    }

    /** Like a real phone scan: 6 mm depth noise everywhere, the desk's noise touching the object, stray points. */
    private fun noisyDesk(c: Cloud, mx: Float, mz: Float) {
        c.noise = 0.006f
        c.floor(0f, 0.4f, 0.4f, 30000, 0xFFB08A60.toInt())
        mouse(c, mx, mz, 0.4f)
        repeat(400) { c.add((c.rnd.nextFloat() - 0.5f) * 0.8f, c.rnd.nextFloat() * 0.3f, (c.rnd.nextFloat() - 0.5f) * 0.8f, 0f, 1f, 0f, 0xFF808080.toInt(), 0.02f) }
        // a keyboard-like box and a mug elsewhere on the desk
        for (k in 0 until 6000) { val x = -0.25f + c.rnd.nextFloat() * 0.3f; val z = 0.15f + c.rnd.nextFloat() * 0.12f; c.add(x, 0.025f, z, 0f, 1f, 0f, 0xFF303030.toInt()) }
        c.turned(0.25f, 0.2f, 0f, 0.09f, 5000, { 0xFFE8E8E8.toInt() }) { 0.04f }
        c.noise = 0.002f
    }

    @Test fun noisyMouseNamedByTheAiIsStillBuilt() {
        val c = Cloud(); noisyDesk(c, 0.05f, -0.1f)
        for (level in intArrayOf(0, 2)) {
            val s = SmartModel.build(c.size, c.p.toFloatArray(), c.n.toFloatArray(), c.c.toIntArray(), null,
                SmartOptions(level, 0, true, false, "", listOf(PartLabel(0.05f, 0.035f, -0.1f, "mouse", 0.07f))))!!
            show("noisy mouse L$level", s)
            val m = s.parts.single { it.name == "mouse" }
            assertEquals("freeform", m.kind)
            val box = groupBox(s.mesh, m.group)
            assertEquals("on the desk", 0f, box[1], 0.008f)
            assertTrue("height ${box[4]}", box[4] in 0.025f..0.05f)
            assertTrue("length ${m.length}", m.length in 0.09f..0.15f)
            assertTrue("width ${m.radius * 2}", m.radius * 2 in 0.045f..0.09f)
            assertTrue(volume(s.mesh, m.group) > 0f)
        }
    }

    @Test fun anyNamedObjectGetsAModel() {
        // A name the program has no family for: whatever is there is still built, as a free form.
        val c = Cloud(); noisyDesk(c, 0.05f, -0.1f)
        val s = SmartModel.build(c.size, c.p.toFloatArray(), c.n.toFloatArray(), c.c.toIntArray(), null,
            SmartOptions(1, 0, true, false, "", listOf(PartLabel(0.05f, 0.03f, -0.1f, "spillatrice"))))!!
        show("named stapler", s)
        val m = s.parts.single { it.name == "spillatrice" }
        assertTrue(m.labelled)
        assertTrue(m.length in 0.08f..0.16f)
    }

    /** What the phone keeps: the app's own voxel map (1.5 cm, or 5 mm on the selected object). */
    private fun voxelize(c: Cloud, voxel: Float, fineNear: FloatArray? = null): Cloud {
        val map = com.frak89.cloudyscanner.core.PointMap(voxel)
        for (i in 0 until c.size) {
            val x = c.p[i * 3]; val y = c.p[i * 3 + 1]; val z = c.p[i * 3 + 2]
            val fine = fineNear != null && (x - fineNear[0]) * (x - fineNear[0]) + (z - fineNear[2]) * (z - fineNear[2]) < 0.1f * 0.1f && y > 0.004f
            var nx = c.n[i * 3]; var ny = c.n[i * 3 + 1]; var nz = c.n[i * 3 + 2]
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            map.insert(x, y, z, nx, ny, nz, c.c[i], 0.8f, fine, 1, 1f, fine)
        }
        val o = Cloud()
        for (i in 0 until map.size) { for (k in 0..2) { o.p.add(map.pos[i * 3 + k]); o.n.add(map.nrm[i * 3 + k]) }; o.c.add(map.col[i]) }
        return o
    }

    @Test fun phoneResolutionMouseIsBuilt() {
        val raw = Cloud(); noisyDesk(raw, 0.05f, -0.1f)
        for ((label, cl) in listOf("1.5 cm" to voxelize(raw, 0.015f), "5 mm sul mouse" to voxelize(raw, 0.015f, floatArrayOf(0.05f, 0f, -0.1f)))) {
            val n = (0 until cl.size).count { val x = cl.p[it * 3] - 0.05f; val z = cl.p[it * 3 + 2] + 0.1f; x * x + z * z < 0.07f * 0.07f && cl.p[it * 3 + 1] > 0.006f }
            for (level in intArrayOf(0, 2)) {
                val s = SmartModel.build(cl.size, cl.p.toFloatArray(), cl.n.toFloatArray(), cl.c.toIntArray(), null,
                    SmartOptions(level, 0, true, false, "", listOf(PartLabel(0.05f, 0.03f, -0.1f, "mouse", 0.07f))))!!
                show("phone $label ($n punti sul mouse) L$level", s)
                val m = s.parts.single { it.name == "mouse" }
                assertEquals("freeform", m.kind)
                val box = groupBox(s.mesh, m.group)
                assertTrue("height ${box[4]}", box[4] in 0.02f..0.055f)
                assertTrue("length ${m.length}", m.length in 0.08f..0.16f)
                assertTrue("width ${m.radius * 2}", m.radius * 2 in 0.04f..0.1f)
                assertTrue(volume(s.mesh, m.group) > 0f)
                val mug = s.parts.filter { it.kind == "revolution" && abs(it.center[0] - 0.25f) < 0.03f }
                assertTrue("the mug stays a mug: " + mug.joinToString { it.dims }, mug.isNotEmpty() && mug.all { it.radius < 0.06f })
            }
        }
    }

    @Test fun capturedObjectAloneStandsOnTheDeskItWasCutFrom() {
        // "Cattura oggetto": only the object is scanned, without its lowest centimetre (cut with the desk)
        val all = Cloud(); all.noise = 0.004f; mouse(all, 0f, 0f, 0.3f)
        val c = Cloud()
        for (i in 0 until all.size) if (all.p[i * 3 + 1] > 0.01f) { for (k in 0..2) { c.p.add(all.p[i * 3 + k]); c.n.add(all.n[i * 3 + k]) }; c.c.add(all.c[i]) }
        val cl = voxelize(c, 0.015f, floatArrayOf(0f, 0f, 0f))
        val s = SmartModel.build(cl.size, cl.p.toFloatArray(), cl.n.toFloatArray(), cl.c.toIntArray(), null,
            SmartOptions(1, 0, true, false, "", listOf(PartLabel(0f, 0.03f, 0f, "oggetto", 0.07f, 0f))))!!
        show("captured alone", s)
        val m = s.parts.single { it.name == "oggetto" }
        val box = groupBox(s.mesh, m.group)
        assertEquals("on the desk", 0f, box[1], 0.003f)
        assertTrue("length ${m.length}", m.length in 0.09f..0.14f)
        assertTrue("height ${box[4]}", box[4] in 0.025f..0.05f)
    }

    /** Camera at [eye] looking at [tgt] (y up): column-major camera-to-world, camera looks along -Z. */
    private fun lookAt(eye: FloatArray, tgt: FloatArray): FloatArray {
        var fx = tgt[0] - eye[0]; var fy = tgt[1] - eye[1]; var fz = tgt[2] - eye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var rx = -fz; var rz = fx; val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        return floatArrayOf(rx, 0f, rz, 0f, ux, uy, uz, 0f, -fx, -fy, -fz, 0f, eye[0], eye[1], eye[2], 1f)
    }

    /** Outlines of the true object seen from 12 sides (convex hull of its projected points). */
    private fun outlines(truth: Cloud, c: FloatArray, minY: Float): List<com.frak89.cloudyscanner.core.Silhouette> {
        val out = ArrayList<com.frak89.cloudyscanner.core.Silhouette>()
        for (k in 0 until 12) {
            val a = k * 2 * PI.toFloat() / 12
            val eye = floatArrayOf(c[0] + 0.32f * cos(a), c[1] + 0.2f, c[2] + 0.32f * sin(a))
            val pose = lookAt(eye, c)
            val probe = com.frak89.cloudyscanner.core.Silhouette(pose, 500f, 500f, 320f, 240f, 640, 480, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
            val pts = (0 until truth.size).mapNotNull { probe.project(truth.p[it * 3], truth.p[it * 3 + 1], truth.p[it * 3 + 2]) }
            // convex hull (monotone chain)
            val sorted = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
            fun cross(o: FloatArray, a1: FloatArray, b1: FloatArray) = (a1[0] - o[0]) * (b1[1] - o[1]) - (a1[1] - o[1]) * (b1[0] - o[0])
            val hull = ArrayList<FloatArray>()
            for (pass in 0..1) {
                val start = hull.size
                for (p in if (pass == 0) sorted else sorted.reversed()) {
                    while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
                    hull.add(p)
                }
                hull.removeAt(hull.size - 1)
            }
            out.add(com.frak89.cloudyscanner.core.Silhouette(pose, 500f, 500f, 320f, 240f, 640, 480, FloatArray(hull.size * 2) { hull[it / 2][it % 2] }, minY))
        }
        return out
    }

    @Test fun edgesOfTheObjectCorrectANoisyScan() {
        val truth = Cloud(); mouse(truth, 0.05f, -0.1f, 0.4f)
        val c = Cloud(); noisyDesk(c, 0.05f, -0.1f)
        val label = listOf(PartLabel(0.05f, 0.035f, -0.1f, "mouse", 0.07f, 0f))
        fun run(views: List<com.frak89.cloudyscanner.core.Silhouette>) = SmartModel.build(c.size, c.p.toFloatArray(), c.n.toFloatArray(), c.c.toIntArray(), null,
            SmartOptions(2, 0, true, false, "", label, views))!!
        val plain = run(emptyList()); val withEdges = run(outlines(truth, floatArrayOf(0.05f, 0.02f, -0.1f), 0.008f))
        show("noisy, depth only", plain); show("noisy + 12 outlines", withEdges)
        val a = plain.parts.single { it.name == "mouse" }; val b = withEdges.parts.single { it.name == "mouse" }
        val errA = abs(a.radius * 2 - 0.064f) + abs(a.length - 0.12f); val errB = abs(b.radius * 2 - 0.064f) + abs(b.length - 0.12f)
        println("  size error: depth only %.1f cm, with outlines %.1f cm".format(errA * 100, errB * 100))
        assertTrue("outlines make it closer to the true size", errB < errA * 0.6f)
        assertTrue("width ${b.radius * 2}", b.radius * 2 in 0.055f..0.075f)
        assertTrue(b.note.contains("contorni"))
        assertTrue(volume(withEdges.mesh, b.group) > 0f)
    }
}

object Palette0 {
    private fun rgb(c: Int) = intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
    fun isRed(c: Int) = rgb(c).let { it[0] > 150 && it[1] < 110 && it[2] < 120 }
    fun isYellow(c: Int) = rgb(c).let { it[0] > 150 && it[1] > 110 && it[2] < 100 }
    fun isWhite(c: Int) = rgb(c).let { it[0] > 200 && it[1] > 200 && it[2] > 200 }
}
