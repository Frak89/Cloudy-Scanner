package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.ai.Director
import com.frak89.cloudyscanner.core.Clusters
import com.frak89.cloudyscanner.core.LongIntMap
import com.frak89.cloudyscanner.core.Mesher
import com.frak89.cloudyscanner.core.PointMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CoreTest {
    private fun sphere(map: PointMap, r: Float, n: Int, rnd: Random) {
        for (i in 0 until n) {
            val z = rnd.nextFloat() * 2 - 1
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val q = sqrt(1 - z * z)
            val nx = q * cos(a); val ny = q * sin(a)
            val e = r + (rnd.nextGaussian() * 0.004).toFloat()
            map.insert(1f + nx * e, -0.3f + ny * e, 2f + z * e, nx, ny, z, -1, 0.8f, false, 1)
        }
    }

    @Test fun hashMapMatchesReference() {
        val hm = LongIntMap(4); val ref = HashMap<Long, Int>(); val rnd = Random(1)
        repeat(200_000) {
            val k = LongIntMap.pack(rnd.nextInt(60) - 30, rnd.nextInt(60) - 30, rnd.nextInt(8) - 4)
            if (rnd.nextInt(3) == 0) assertEquals(ref.remove(k) != null, hm.remove(k))
            else { val v = rnd.nextInt(); hm.put(k, v); ref[k] = v }
        }
        assertEquals(ref.size, hm.size)
        for ((k, v) in ref) assertEquals(v, hm.get(k))
        assertEquals(-900, LongIntMap.uz(LongIntMap.pack(-5, 7, -900)))
    }

    @Test fun sphereMeshIsClosedOrientedAndAccurate() {
        val map = PointMap(0.015f)
        sphere(map, 0.5f, 300_000, Random(2))
        var previous = 0
        for (h in floatArrayOf(0.08f, 0.04f, 0.02f)) {
            val m = Mesher.build(map.size, map.pos, map.nrm, map.col, h)!!
            assertTrue("finer level must have more faces", m.faceCount > previous)
            previous = m.faceCount
            assertEquals(m.quadCount * 2 + (m.faceCount - m.quadCount), m.triangleCount)
            assertEquals(m.triangleCount * 3, m.triangleIndices().size)
            val edges = HashMap<Long, Int>()
            for (q in 0 until m.faceCount) {
                val idx = IntArray(4) { m.quads[q * 4 + it] }
                val p = m.pos
                val a = idx[0]; val b = idx[1]; val c = idx[2]; val d = idx[3]
                val e1 = floatArrayOf(p[c * 3] - p[a * 3], p[c * 3 + 1] - p[a * 3 + 1], p[c * 3 + 2] - p[a * 3 + 2])
                val e2 = floatArrayOf(p[d * 3] - p[b * 3], p[d * 3 + 1] - p[b * 3 + 1], p[d * 3 + 2] - p[b * 3 + 2])
                val nx = e1[1] * e2[2] - e1[2] * e2[1]; val ny = e1[2] * e2[0] - e1[0] * e2[2]; val nz = e1[0] * e2[1] - e1[1] * e2[0]
                if (c != d) assertTrue("face must point outward", nx * (p[a * 3] - 1f) + ny * (p[a * 3 + 1] + 0.3f) + nz * (p[a * 3 + 2] - 2f) > 0)
                for (j in 0 until 4) {
                    val u = idx[j]; val w = idx[(j + 1) % 4]
                    if (u == w) continue
                    val k = minOf(u, w).toLong() * 1_000_000L + maxOf(u, w)
                    edges[k] = (edges[k] ?: 0) + 1
                }
            }
            assertEquals("no edge shared by more than two faces", 0, edges.values.count { it > 2 })
            assertTrue("almost watertight", edges.values.count { it == 1 } < m.faceCount / 50 + 20)
            for (v in 0 until m.vertexCount) {
                val x = m.pos[v * 3] - 1f; val y = m.pos[v * 3 + 1] + 0.3f; val z = m.pos[v * 3 + 2] - 2f
                val r = sqrt(x * x + y * y + z * z)
                assertTrue("vertex on the sphere", abs(r - 0.5f) < h)
                assertTrue("normal outward", (m.nrm[v * 3] * x + m.nrm[v * 3 + 1] * y + m.nrm[v * 3 + 2] * z) / r > 0.7f)
            }
            assertEquals(m.vertexCount, Mesher.smooth(m, 2).vertexCount)
        }
    }

    @Test fun strayFragmentIsFoundAndRemoved() {
        val map = PointMap(0.015f); val rnd = Random(3)
        sphere(map, 0.5f, 100_000, rnd)
        for (i in 0 until 300) map.insert(3f + rnd.nextFloat() * 0.1f, 0f, rnd.nextFloat() * 0.1f, 0f, 1f, 0f, -1, 0.3f, false, 1)
        val cl = Clusters(map); cl.compute()
        assertEquals(2, cl.infos.size)
        val before = map.size
        val removed = map.removeIf { cl.clusterOf(it) == 1 }
        assertEquals(cl.infos[1].points, removed)
        assertEquals(before - removed, map.size)
        for (i in 0 until map.size) assertEquals(i, map.indexOf(map.keys[i]))
    }

    @Test fun directorParsesModelAnswers() {
        val cmds = Director.parse("Ho trovato frammenti.\nCOMANDI\n- DELETE_CLUSTER 3\nDELETE_LOWCONF: 0.15\n`LEVEL HIGH`\nSMOOTH 2 (leggero)\nNOTE Le superfici lucide vanno riprese di lato.\nciao")
        assertEquals(listOf("DELETE_CLUSTER", "DELETE_LOWCONF", "LEVEL", "SMOOTH", "NOTE"), cmds.map { it.op })
        assertEquals("3", cmds[0].arg); assertEquals("0.15", cmds[1].arg); assertEquals("HIGH", cmds[2].arg); assertEquals("2", cmds[3].arg)
        assertEquals("Ho trovato frammenti.", Director.explanation("Ho trovato frammenti.\nCOMANDI\nLEVEL MID"))
    }
}
