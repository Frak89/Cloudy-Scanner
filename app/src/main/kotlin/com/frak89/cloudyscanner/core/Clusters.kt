package com.frak89.cloudyscanner.core

import kotlin.math.floor

/** A connected blob of points (connectivity on a coarse grid). */
class ClusterInfo(
    val id: Int,
    val points: Int,
    val meanConf: Float,
    val center: FloatArray,
    val size: FloatArray,
)

/** Splits the cloud into connected pieces so stray fragments can be found and removed. */
class Clusters(private val map: PointMap, private val cell: Float = 0.08f) {
    private val cellLabel = LongIntMap(1024)
    var infos: List<ClusterInfo> = emptyList()
        private set

    private fun cellKey(i: Int): Long {
        val ic = 1f / cell
        return LongIntMap.pack(
            floor(map.pos[i * 3] * ic).toInt(), floor(map.pos[i * 3 + 1] * ic).toInt(), floor(map.pos[i * 3 + 2] * ic).toInt())
    }

    /** Caller holds map.lock. Result is sorted by size, biggest first; ids are positions in that list. */
    fun compute() {
        cellLabel.clear()
        val n = map.size
        val cellKeys = ArrayList<Long>()
        for (i in 0 until n) {
            val k = cellKey(i)
            if (cellLabel.get(k) < 0) { cellLabel.put(k, cellKeys.size); cellKeys.add(k) }
        }
        val m = cellKeys.size
        val label = IntArray(m) { -1 }
        var nl = 0
        val stack = IntArray(m)
        for (s in 0 until m) {
            if (label[s] >= 0) continue
            var sp = 0
            stack[sp++] = s; label[s] = nl
            while (sp > 0) {
                val c = stack[--sp]
                val k = cellKeys[c]
                val x = LongIntMap.ux(k); val y = LongIntMap.uy(k); val z = LongIntMap.uz(k)
                for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val o = cellLabel.get(LongIntMap.pack(x + dx, y + dy, z + dz))
                    if (o >= 0 && label[o] < 0) { label[o] = nl; stack[sp++] = o }
                }
            }
            nl++
        }
        val cnt = IntArray(nl); val conf = FloatArray(nl)
        val mn = FloatArray(nl * 3) { Float.MAX_VALUE }; val mx = FloatArray(nl * 3) { -Float.MAX_VALUE }
        for (i in 0 until n) {
            val l = label[cellLabel.get(cellKey(i))]
            cnt[l]++; conf[l] += map.conf[i]
            for (c in 0 until 3) {
                val v = map.pos[i * 3 + c]
                if (v < mn[l * 3 + c]) mn[l * 3 + c] = v
                if (v > mx[l * 3 + c]) mx[l * 3 + c] = v
            }
        }
        val order = (0 until nl).sortedByDescending { cnt[it] }
        val rank = IntArray(nl)
        order.forEachIndexed { r, l -> rank[l] = r }
        for (c in 0 until m) cellLabel.put(cellKeys[c], rank[label[c]])
        infos = order.mapIndexed { r, l ->
            ClusterInfo(
                r, cnt[l], conf[l] / cnt[l],
                floatArrayOf((mn[l * 3] + mx[l * 3]) / 2, (mn[l * 3 + 1] + mx[l * 3 + 1]) / 2, (mn[l * 3 + 2] + mx[l * 3 + 2]) / 2),
                floatArrayOf(mx[l * 3] - mn[l * 3], mx[l * 3 + 1] - mn[l * 3 + 1], mx[l * 3 + 2] - mn[l * 3 + 2]),
            )
        }
    }

    /** Cluster id of point [i], or -1 if the point appeared after [compute]. */
    fun clusterOf(i: Int): Int = cellLabel.get(cellKey(i))
}
