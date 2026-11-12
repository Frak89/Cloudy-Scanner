package com.frak89.cloudyscanner.core

/** Open-addressing hash map from Long keys to Int values, without boxing. */
class LongIntMap(initialCapacity: Int = 1024) {
    private var keys: LongArray
    private var vals: IntArray
    private var mask: Int
    var size = 0
        private set

    init {
        var cap = 16
        while (cap < initialCapacity * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        vals = IntArray(cap)
        mask = cap - 1
    }

    private fun slot(k: Long): Int {
        val h = k * -7046029254386353131L
        return ((h ushr 32) xor h).toInt() and mask
    }

    fun get(k: Long): Int {
        var i = slot(k)
        while (true) {
            val kk = keys[i]
            if (kk == k) return vals[i]
            if (kk == EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    fun put(k: Long, v: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        var i = slot(k)
        while (true) {
            val kk = keys[i]
            if (kk == k) { vals[i] = v; return }
            if (kk == EMPTY) { keys[i] = k; vals[i] = v; size++; return }
            i = (i + 1) and mask
        }
    }

    fun remove(k: Long): Boolean {
        var i = slot(k)
        while (true) {
            val kk = keys[i]
            if (kk == EMPTY) return false
            if (kk == k) break
            i = (i + 1) and mask
        }
        // Backward-shift deletion keeps probe chains intact without tombstones.
        var j = i
        while (true) {
            j = (j + 1) and mask
            val kj = keys[j]
            if (kj == EMPTY) break
            val home = slot(kj)
            val inRange = if (i <= j) (home in (i + 1)..j) else (home > i || home <= j)
            if (!inRange) {
                keys[i] = kj; vals[i] = vals[j]; i = j
            }
        }
        keys[i] = EMPTY
        size--
        return true
    }

    fun clear() {
        keys.fill(EMPTY); size = 0
    }

    private fun grow() {
        val ok = keys; val ov = vals
        keys = LongArray(ok.size * 2) { EMPTY }
        vals = IntArray(ok.size * 2)
        mask = keys.size - 1
        size = 0
        for (i in ok.indices) if (ok[i] != EMPTY) put(ok[i], ov[i])
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE
        private const val OFF = 1 shl 20
        private const val M = (1L shl 21) - 1

        fun pack(x: Int, y: Int, z: Int): Long =
            (((x + OFF).toLong() and M) shl 42) or (((y + OFF).toLong() and M) shl 21) or ((z + OFF).toLong() and M)

        fun ux(k: Long): Int = ((k ushr 42) and M).toInt() - OFF
        fun uy(k: Long): Int = ((k ushr 21) and M).toInt() - OFF
        fun uz(k: Long): Int = (k and M).toInt() - OFF
    }
}
