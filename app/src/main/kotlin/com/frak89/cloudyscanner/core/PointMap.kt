package com.frak89.cloudyscanner.core

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sparse voxel point cloud: one averaged, oriented, coloured point per voxel.
 * All access must hold [lock].
 */
class PointMap(val voxel: Float = 0.015f, val maxPoints: Int = 1_500_000) {
    val lock = java.util.concurrent.locks.ReentrantLock()
    private val index = LongIntMap(1 shl 16)
    private val inv = 1f / voxel

    var size = 0
        private set
    var version = 0L
        private set

    var keys = LongArray(CAP0)
    var pos = FloatArray(CAP0 * 3)
    var nrm = FloatArray(CAP0 * 3)
    var col = IntArray(CAP0)
    var conf = FloatArray(CAP0)
    var wsum = FloatArray(CAP0)
    var flags = ByteArray(CAP0)
    var seen = IntArray(CAP0)

    private fun ensure(n: Int) {
        if (n <= keys.size) return
        val c = min(maxPoints, max(n, keys.size + keys.size / 2))
        keys = keys.copyOf(c); pos = pos.copyOf(c * 3); nrm = nrm.copyOf(c * 3)
        col = col.copyOf(c); conf = conf.copyOf(c); wsum = wsum.copyOf(c)
        flags = flags.copyOf(c); seen = seen.copyOf(c)
    }

    fun keyOf(x: Float, y: Float, z: Float): Long =
        LongIntMap.pack(floor(x * inv).toInt(), floor(y * inv).toInt(), floor(z * inv).toInt())

    /** Finer grid (a third of [voxel]) for the selected object, in its own key space. */
    val fineVoxel get() = voxel / 3f
    private val invFine = 3f / voxel
    fun fineKeyOf(x: Float, y: Float, z: Float): Long =
        LongIntMap.pack(floor(x * invFine).toInt(), floor(y * invFine).toInt(), floor(z * invFine).toInt()) or FINE_BIT

    fun indexOf(key: Long): Int = index.get(key)

    /**
     * Fuses one observation. [w] in 0..1 is its reliability; [boost] scales how much confidence it
     * adds (far away one depth pixel covers several voxels, so each voxel is hit less often).
     * Returns the point index or -1 when full.
     */
    fun insert(
        x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float,
        color: Int, w: Float, subject: Boolean, frame: Int, boost: Float = 1f, fine: Boolean = false,
    ): Int {
        val key = if (fine) fineKeyOf(x, y, z) else keyOf(x, y, z)
        val vs = if (fine) fineVoxel else voxel
        fun look(px: Float, py: Float, pz: Float) = index.get(if (fine) fineKeyOf(px, py, pz) else keyOf(px, py, pz))
        var i = index.get(key)
        if (i < 0 && fine) {
            // The coarse point this fine one replaces (scanned before the object was selected) goes.
            val c = index.get(keyOf(x, y, z))
            if (c >= 0) removeAt(c)
        }
        if (i < 0) {
            // Depth noise scatters a surface over a few voxels along its normal. If this surface
            // already has a sample just in front or behind, refine that one instead of thickening
            // the shell. Opposite-facing samples (the other side of a thin object) are left alone.
            var k = 1
            while (k <= SNAP_VOXELS && i < 0) {
                val s = vs * k
                var j = look(x + nx * s, y + ny * s, z + nz * s)
                if (j < 0 || nrm[j * 3] * nx + nrm[j * 3 + 1] * ny + nrm[j * 3 + 2] * nz < 0.5f) {
                    j = look(x - nx * s, y - ny * s, z - nz * s)
                    if (j >= 0 && nrm[j * 3] * nx + nrm[j * 3 + 1] * ny + nrm[j * 3 + 2] * nz < 0.5f) j = -1
                }
                if (j >= 0) i = j
                k++
            }
        }
        if (i < 0) {
            if (size >= maxPoints) return -1
            i = size
            ensure(size + 1)
            size++
            keys[i] = key
            pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z
            nrm[i * 3] = nx; nrm[i * 3 + 1] = ny; nrm[i * 3 + 2] = nz
            col[i] = color
            conf[i] = min(1f, CONF_GAIN * w * boost)
            wsum[i] = w
            flags[i] = if (subject) SUBJECT else 0
            seen[i] = frame
            index.put(key, i)
        } else {
            val ws = wsum[i]
            val t = w / (ws + w)
            val p = i * 3
            pos[p] += (x - pos[p]) * t; pos[p + 1] += (y - pos[p + 1]) * t; pos[p + 2] += (z - pos[p + 2]) * t
            nrm[p] += (nx - nrm[p]) * t; nrm[p + 1] += (ny - nrm[p + 1]) * t; nrm[p + 2] += (nz - nrm[p + 2]) * t
            col[i] = lerpColor(col[i], color, t)
            conf[i] = min(1f, conf[i] + CONF_GAIN * w * boost)
            // Capped weight: old data fades, so the map follows things that move.
            wsum[i] = min(ws + w, MAX_WEIGHT)
            val f = flags[i].toInt()
            flags[i] = ((f and (SELECTED.toInt() or UNCERTAIN.toInt())) or (if (subject) SUBJECT.toInt() else 0)).toByte()
            seen[i] = frame
        }
        version++
        return i
    }

    /** Re-inserts a point exactly as it was (used by undo). */
    fun restore(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Int, c: Float, w: Float) {
        val key = keyOf(x, y, z)
        if (index.get(key) >= 0 || size >= maxPoints) return
        val i = size
        ensure(size + 1); size++
        keys[i] = key
        pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z
        nrm[i * 3] = nx; nrm[i * 3 + 1] = ny; nrm[i * 3 + 2] = nz
        col[i] = color; conf[i] = c; wsum[i] = w; flags[i] = 0; seen[i] = 0
        index.put(key, i)
        version++
    }

    fun removeAt(i: Int) {
        val last = size - 1
        index.remove(keys[i])
        if (i != last) {
            keys[i] = keys[last]
            System.arraycopy(pos, last * 3, pos, i * 3, 3)
            System.arraycopy(nrm, last * 3, nrm, i * 3, 3)
            col[i] = col[last]; conf[i] = conf[last]; wsum[i] = wsum[last]
            flags[i] = flags[last]; seen[i] = seen[last]
            index.put(keys[i], i)
        }
        size = last
        version++
    }

    /** Removes every point matching [pred]; returns how many were removed. */
    inline fun removeIf(pred: (Int) -> Boolean): Int {
        var removed = 0
        var i = size - 1
        while (i >= 0) {
            if (pred(i)) { removeAt(i); removed++ }
            i--
        }
        return removed
    }

    fun setFlag(i: Int, flag: Byte, on: Boolean) {
        val f = flags[i].toInt()
        val nf = if (on) f or flag.toInt() else f and flag.toInt().inv()
        if (nf != f) { flags[i] = nf.toByte(); version++ }
    }

    fun hasFlag(i: Int, flag: Byte) = (flags[i].toInt() and flag.toInt()) != 0

    fun touch() { version++ }

    fun clear() {
        index.clear(); size = 0; version++
    }

    fun countFlag(flag: Byte): Int {
        var n = 0
        for (i in 0 until size) if ((flags[i].toInt() and flag.toInt()) != 0) n++
        return n
    }

    /** Axis-aligned bounds as [minX,minY,minZ,maxX,maxY,maxZ]; false when empty. */
    fun bounds(out: FloatArray): Boolean {
        if (size == 0) return false
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = Float.MAX_VALUE
        var d = -Float.MAX_VALUE; var e = -Float.MAX_VALUE; var f = -Float.MAX_VALUE
        for (i in 0 until size) {
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            if (x < a) a = x; if (y < b) b = y; if (z < c) c = z
            if (x > d) d = x; if (y > e) e = y; if (z > f) f = z
        }
        out[0] = a; out[1] = b; out[2] = c; out[3] = d; out[4] = e; out[5] = f
        return true
    }

    /**
     * Packs the cloud for the GPU: xyz floats, RGBA bytes, normal xyz + confidence as bytes,
     * flags + 3 pad bytes. [STRIDE] bytes per point. Returns the number of points written.
     * With a [palette], colours are reduced to it (for subject points only when asked).
     */
    fun writeRenderBuffer(buf: ByteBuffer, palette: Palette? = null, paletteSubjectOnly: Boolean = false): Int {
        val n = min(size, buf.capacity() / STRIDE)
        buf.clear()
        for (i in 0 until n) {
            val p = i * 3
            buf.putFloat(pos[p]); buf.putFloat(pos[p + 1]); buf.putFloat(pos[p + 2])
            var c = col[i]
            // 256-colour view: swap in the palette colour and tell the shader to show it as is.
            val pal = palette != null && (!paletteSubjectOnly || (flags[i].toInt() and SUBJECT.toInt()) != 0)
            if (pal) c = palette!!.map(c)
            buf.put((c shr 16).toByte()); buf.put((c shr 8).toByte()); buf.put(c.toByte()); buf.put(-1)
            val nx = nrm[p]; val ny = nrm[p + 1]; val nz = nrm[p + 2]
            val l = 127f / max(1e-6f, sqrt(nx * nx + ny * ny + nz * nz))
            buf.put((nx * l).toInt().toByte()); buf.put((ny * l).toInt().toByte()); buf.put((nz * l).toInt().toByte())
            buf.put((conf[i] * 127f).toInt().toByte())
            buf.put(if (pal) (flags[i].toInt() or PALETTE_BIT).toByte() else flags[i]); buf.put(0); buf.put(0); buf.put(0)
        }
        buf.flip()
        return n
    }

    companion object {
        const val CAP0 = 1 shl 15
        const val STRIDE = 24
        const val SELECTED: Byte = 1
        const val SUBJECT: Byte = 2
        const val UNCERTAIN: Byte = 4
        /** Render-buffer only: this point carries a palette colour. */
        const val PALETTE_BIT = 8
        const val CONF_GAIN = 0.16f
        const val MAX_WEIGHT = 12f
        const val SNAP_VOXELS = 2
        const val FINE_BIT = 1L shl 63  // the one bit pack() leaves free

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val ar = (a shr 16) and 255; val ag = (a shr 8) and 255; val ab = a and 255
            val br = (b shr 16) and 255; val bg = (b shr 8) and 255; val bb = b and 255
            val r = (ar + (br - ar) * t).toInt(); val g = (ag + (bg - ag) * t).toInt(); val bl = (ab + (bb - ab) * t).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
