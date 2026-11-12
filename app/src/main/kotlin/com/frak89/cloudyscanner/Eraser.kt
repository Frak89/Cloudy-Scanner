package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.gl.SceneRenderer

/** Paint-to-select (shown in purple), delete, and undo for parts of the cloud. */
class Eraser(private val map: PointMap, private val renderer: SceneRenderer) {
    private class Batch(val f: FloatArray, val c: IntArray)
    private val undo = ArrayList<Batch>()
    private val mvp = FloatArray(16); private val cam = FloatArray(3); private val model = FloatArray(16)

    val undoDepth get() = undo.size

    /** Selects every point drawn within [radius] pixels of the view position. Returns how many were added. */
    fun paint(x: Float, y: Float, radius: Float): Int {
        val vpSize = renderer.getPick(mvp, cam, model)
        val w = vpSize[0]; val h = vpSize[1]
        val r2 = radius * radius
        val cull = Opt.cull
        var added = 0
        map.lock.lock()
        try {
            val pos = map.pos; val nrm = map.nrm
            for (i in 0 until map.size) {
                val o = i * 3
                val px = pos[o]; val py = pos[o + 1]; val pz = pos[o + 2]
                val cw = mvp[3] * px + mvp[7] * py + mvp[11] * pz + mvp[15]
                if (cw <= 0.01f) continue
                val sx = ((mvp[0] * px + mvp[4] * py + mvp[8] * pz + mvp[12]) / cw * 0.5f + 0.5f) * w
                val dx = sx - x
                if (dx > radius || dx < -radius) continue
                val sy = (1f - ((mvp[1] * px + mvp[5] * py + mvp[9] * pz + mvp[13]) / cw * 0.5f + 0.5f)) * h
                val dy = sy - y
                if (dx * dx + dy * dy > r2) continue
                if (cull) {
                    val wx = model[0] * px + model[4] * py + model[8] * pz + model[12]
                    val wy = model[1] * px + model[5] * py + model[9] * pz + model[13]
                    val wz = model[2] * px + model[6] * py + model[10] * pz + model[14]
                    val nx = model[0] * nrm[o] + model[4] * nrm[o + 1] + model[8] * nrm[o + 2]
                    val ny = model[1] * nrm[o] + model[5] * nrm[o + 1] + model[9] * nrm[o + 2]
                    val nz = model[2] * nrm[o] + model[6] * nrm[o + 1] + model[10] * nrm[o + 2]
                    if (nx * (cam[0] - wx) + ny * (cam[1] - wy) + nz * (cam[2] - wz) < 0f) continue
                }
                if (!map.hasFlag(i, PointMap.SELECTED)) { map.setFlag(i, PointMap.SELECTED, true); added++ }
            }
        } finally { map.lock.unlock() }
        return added
    }

    /** Replaces the selection with every point matching [pred]. Returns how many are selected. */
    fun select(pred: (Int) -> Boolean): Int {
        var n = 0
        map.lock.lock()
        try {
            for (i in 0 until map.size) { val s = pred(i); map.setFlag(i, PointMap.SELECTED, s); if (s) n++ }
        } finally { map.lock.unlock() }
        return n
    }

    fun selectedCount(): Int {
        map.lock.lock()
        try { return map.countFlag(PointMap.SELECTED) } finally { map.lock.unlock() }
    }

    fun clearSelection() {
        map.lock.lock()
        try { for (i in 0 until map.size) map.setFlag(i, PointMap.SELECTED, false) } finally { map.lock.unlock() }
    }

    /** Deletes the purple points; the batch is kept so it can be undone. */
    fun deleteSelected(): Int {
        map.lock.lock()
        try {
            val n = map.countFlag(PointMap.SELECTED)
            if (n == 0) return 0
            val f = FloatArray(n * 8); val c = IntArray(n)
            var k = 0
            map.removeIf { i ->
                val sel = map.hasFlag(i, PointMap.SELECTED)
                if (sel) {
                    val o = i * 3
                    f[k * 8] = map.pos[o]; f[k * 8 + 1] = map.pos[o + 1]; f[k * 8 + 2] = map.pos[o + 2]
                    f[k * 8 + 3] = map.nrm[o]; f[k * 8 + 4] = map.nrm[o + 1]; f[k * 8 + 5] = map.nrm[o + 2]
                    f[k * 8 + 6] = map.conf[i]; f[k * 8 + 7] = map.wsum[i]
                    c[k] = map.col[i]
                    k++
                }
                sel
            }
            undo.add(Batch(f, c))
            while (undo.size > 12) undo.removeAt(0)
            return n
        } finally { map.lock.unlock() }
    }

    fun undoLast(): Int {
        if (undo.isEmpty()) return 0
        val b = undo.removeAt(undo.size - 1)
        map.lock.lock()
        try {
            for (k in b.c.indices) {
                map.restore(b.f[k * 8], b.f[k * 8 + 1], b.f[k * 8 + 2], b.f[k * 8 + 3], b.f[k * 8 + 4], b.f[k * 8 + 5], b.c[k], b.f[k * 8 + 6], b.f[k * 8 + 7])
            }
        } finally { map.lock.unlock() }
        return b.c.size
    }
}
