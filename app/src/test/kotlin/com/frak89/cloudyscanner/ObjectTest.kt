package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.ai.VisionParse
import com.frak89.cloudyscanner.core.Palette
import com.frak89.cloudyscanner.scan.Candidate
import com.frak89.cloudyscanner.scan.PhotoPlanner
import com.frak89.cloudyscanner.scan.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ObjectTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun paletteReducesTo256AndStaysClose() {
        val rnd = Random(7)
        val cols = IntArray(60_000) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val p = Palette.build(cols, cols.size, 256)!!
        assertEquals(256, p.size)
        assertEquals(cols.size, p.weight.sum())
        var err = 0L
        for (c in cols) {
            val m = p.map(c)
            err += abs(((c shr 16) and 255) - ((m shr 16) and 255)) + abs(((c shr 8) and 255) - ((m shr 8) and 255)) + abs((c and 255) - (m and 255))
        }
        assertTrue("average error per channel should be small, was ${err / 3.0 / cols.size}", err / 3.0 / cols.size < 14.0)

        // An object with few colours keeps them (almost) exactly, and only those.
        val few = intArrayOf(rgb(120, 80, 40), rgb(30, 60, 200), rgb(240, 240, 240))
        val obj = IntArray(9000) { few[it % 3] }
        val q = Palette.build(obj, obj.size, 256)!!
        assertEquals(3, q.size)
        for (c in few) assertTrue(abs(((q.map(c) shr 16) and 255) - ((c shr 16) and 255)) <= 8)
        assertEquals(listOf("bianco", "blu", "marrone"), q.namedShares().map { it.first }.sorted())
        // Only picked points take part.
        assertEquals(1, Palette.build(obj, obj.size, 256) { it % 3 == 0 }!!.size)
        assertEquals("rosso", Palette.nameOf(rgb(200, 30, 30)))
        assertEquals("verde", Palette.nameOf(rgb(40, 160, 60)))
        assertEquals("nero", Palette.nameOf(rgb(10, 12, 9)))
    }

    @Test fun sharpnessSeparatesCrispBlurredAndFlat() {
        val w = 320; val h = 240
        val crisp = ByteArray(w * h) { i -> if (((i % w) / 6 + (i / w) / 6) % 2 == 0) 220.toByte() else 40.toByte() }
        // Motion blur: average 9 pixels horizontally and vertically.
        val blur = ByteArray(w * h)
        for (y in 4 until h - 4) for (x in 4 until w - 4) {
            var s = 0
            for (k in -4..4) s += (crisp[y * w + x + k].toInt() and 255) + (crisp[(y + k) * w + x].toInt() and 255)
            blur[y * w + x] = (s / 18).toByte()
        }
        val flat = ByteArray(w * h) { 128.toByte() }
        val a = PhotoPlanner.sharpness(crisp, w, w, h, 20, 20, 300, 220)
        val b = PhotoPlanner.sharpness(blur, w, w, h, 20, 20, 300, 220)
        val c = PhotoPlanner.sharpness(flat, w, w, h, 20, 20, 300, 220)
        assertTrue("crisp $a must beat blurred $b clearly", a > 3f * b)
        assertTrue(c < 0.01f)
    }

    private fun shot(p: PhotoPlanner, c: Candidate, slot: Int, detail: Boolean, q: Float) =
        Shot(p.newId(), slot, detail, c.az, c.el, c.dist, c.sharp, q, FloatArray(16), FloatArray(6), 0, "/tmp/none", false)

    private fun offer(p: PhotoPlanner, az: Float, el: Float, sharp: Float, blur: Float = 1f, dist: Float = 1.5f): String {
        val c = Candidate(az, el, dist, sharp, blur, 0.3f, true, 1.5f)
        val d = p.consider(c)
        if (d.accept) p.add(shot(p, c, d.slot, d.detail, d.quality), d.replace)
        return d.why
    }

    @Test fun plannerKeepsOneGoodPhotoPerViewAndDropsTheRest() {
        val p = PhotoPlanner()
        assertEquals(5, p.slots.size)
        // Walk once around the object, a frame every 5 degrees.
        var a = -180f
        while (a < 180f) { offer(p, a, 15f, 100f + (a.toInt() % 7)); a += 5f }
        assertEquals("one photo per side, not 72", 4, p.count())
        assertEquals(listOf("sopra"), p.missing())
        assertTrue(p.rejectedRedundant > 50)
        assertTrue(p.advice(0f, 15f, 1.5f, 1.5f).contains("sopra"))

        assertEquals("mossa", offer(p, 0f, 15f, 500f, blur = 12f))
        assertEquals("poco dettagliata", offer(p, 0f, 15f, 20f))
        val before = p.replaced
        assertEquals("migliore", offer(p, 2f, 15f, 300f))
        assertEquals(before + 1, p.replaced)
        assertEquals(4, p.count())

        assertEquals("nuova vista", offer(p, 30f, 70f, 110f))
        assertTrue(p.complete)
        assertTrue(p.advice(0f, 15f, 1.5f, 1.5f).contains("complete"))
        assertTrue(p.advice(0f, 15f, 4f, 1.5f).startsWith("Avvicinati"))

        // A simple object needs fewer views: the extra side photos are handed back for deletion.
        val dropped = p.setLevel(0, false)
        assertEquals(3, p.slots.size)
        assertEquals(5 - p.count(), dropped.size)
        assertTrue(p.count() <= 3 && p.complete)
        // Once the user fixes the level, automatic estimates no longer change it.
        p.setLevel(2, true)
        assertEquals(13, p.slots.size)
        p.setLevel(0, false)
        assertEquals(13, p.slots.size)
    }

    @Test fun plannerMeasuresTheViewRelativeToTheFirstSide() {
        val p = PhotoPlanner()
        val v0 = p.view(0f, 0f, 2f, 0f, 0f, 0f)
        assertEquals(0f, v0[0], 0.01f); assertEquals(2f, v0[2], 0.01f)
        val right = p.view(2f, 0f, 0f, 0f, 0f, 0f)
        assertEquals("moving to the user's right is a positive angle", 90f, right[0], 0.01f)
        val top = p.view(0f, 2f, 0.01f, 0f, 0f, 0f)
        assertTrue(top[1] > 85f)
    }

    @Test fun shapeComplexityFromNormals() {
        val rnd = Random(3)
        val box = FloatArray(3000 * 3)
        val axes = arrayOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        for (i in 0 until 3000) { val n = axes[if (i < 2000) 0 else if (i < 2700) 1 else 2]; for (c in 0 until 3) box[i * 3 + c] = n[c] + (rnd.nextGaussian() * 0.05).toFloat() }
        assertEquals(0, PhotoPlanner.complexity(box, 3000) { true })
        val ball = FloatArray(3000 * 3)
        for (i in 0 until 3000) { val z = rnd.nextFloat() * 2 - 1; val t = rnd.nextFloat() * 6.283f; val r = kotlin.math.sqrt(1 - z * z); ball[i * 3] = r * cos(t); ball[i * 3 + 1] = r * sin(t); ball[i * 3 + 2] = z }
        assertEquals(2, PhotoPlanner.complexity(ball, 3000) { true })
    }

    @Test fun recognitionAnswersAreParsed() {
        val r = VisionParse.parse("OGGETTO: Tavolo da pranzo.\n- SICUREZZA: 82%\n**COMPLESSITÀ**: media\nRICHIESTA: Nessuna\nMATERIALE: legno e metallo")
        assertEquals("Tavolo da pranzo", r.name); assertEquals(82, r.confidence); assertEquals(1, r.level); assertEquals("", r.ask)
        assertEquals("legno e metallo", r.material); assertTrue(r.known)
        val u = VisionParse.parse("OGGETTO: SCONOSCIUTO\nSICUREZZA: 20\nCOMPLESSITA: COMPLESSA\nRICHIESTA: mostrami il lato con l'etichetta, più da vicino")
        assertTrue(!u.known); assertEquals(2, u.level); assertTrue(u.ask.startsWith("mostrami"))
        assertTrue(!VisionParse.parse("non so").known)
    }
}
