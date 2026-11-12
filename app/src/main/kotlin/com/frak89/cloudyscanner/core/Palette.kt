package com.frak89.cloudyscanner.core

/**
 * Reduces a set of colours to at most [size] representative ones (median cut on a 15-bit
 * histogram) and maps any colour to its palette entry through a lookup table.
 */
class Palette(val colors: IntArray, val weight: IntArray, private val lut: ShortArray) {
    val size get() = colors.size

    fun indexOf(argb: Int): Int = lut[key(argb)].toInt() and 0xFFFF
    fun map(argb: Int): Int = colors[indexOf(argb)]

    /** Share of the object per plain colour name, biggest first, e.g. "marrone" to 0.46. */
    fun namedShares(): List<Pair<String, Float>> {
        val total = weight.sum().toFloat().coerceAtLeast(1f)
        val acc = HashMap<String, Float>()
        for (i in colors.indices) { val n = nameOf(colors[i]); acc[n] = (acc[n] ?: 0f) + weight[i] / total }
        return acc.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    companion object {
        fun key(c: Int): Int = (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)

        private class Box(val idx: IntArray, val from: Int, val to: Int, hist: IntArray) {
            var r0 = 31; var r1 = 0; var g0 = 31; var g1 = 0; var b0 = 31; var b1 = 0
            var count = 0L
            init {
                for (i in from until to) {
                    val k = idx[i]; val r = k shr 10; val g = (k shr 5) and 31; val b = k and 31
                    if (r < r0) r0 = r; if (r > r1) r1 = r; if (g < g0) g0 = g; if (g > g1) g1 = g; if (b < b0) b0 = b; if (b > b1) b1 = b
                    count += hist[k]
                }
            }
            val range get() = maxOf(r1 - r0, g1 - g0, b1 - b0)
            val splittable get() = to - from > 1 && range > 0
        }

        /** [n] colours from [argb]; [pick] chooses which entries take part (null = all). */
        fun build(argb: IntArray, n: Int, size: Int = 256, pick: ((Int) -> Boolean)? = null): Palette? {
            val hist = IntArray(32768)
            var used = 0
            for (i in 0 until n) if (pick == null || pick(i)) { hist[key(argb[i])]++; used++ }
            if (used == 0) return null
            var m = 0
            val idx = IntArray(32768)
            for (k in 0 until 32768) if (hist[k] > 0) idx[m++] = k
            val boxes = ArrayList<Box>()
            boxes.add(Box(idx, 0, m, hist))
            while (boxes.size < size) {
                // Split the box that holds the most colour spread weighted by how many points use it.
                var best = -1; var bestScore = -1.0
                for ((i, b) in boxes.withIndex()) if (b.splittable) {
                    val s = b.count.toDouble() * b.range
                    if (s > bestScore) { bestScore = s; best = i }
                }
                if (best < 0) break
                val b = boxes[best]
                val shift = when (b.range) { b.r1 - b.r0 -> 10; b.g1 - b.g0 -> 5; else -> 0 }
                val part = idx.copyOfRange(b.from, b.to).sortedBy { (it shr shift) and 31 }
                for (i in part.indices) idx[b.from + i] = part[i]
                var half = b.count / 2; var cut = b.from
                while (cut < b.to - 1) { half -= hist[idx[cut]]; cut++; if (half <= 0) break }
                boxes[best] = Box(idx, b.from, cut, hist)
                boxes.add(Box(idx, cut, b.to, hist))
            }
            val colors = IntArray(boxes.size); val weight = IntArray(boxes.size)
            val lut = ShortArray(32768) { -1 }
            for ((bi, b) in boxes.withIndex()) {
                var r = 0L; var g = 0L; var bl = 0L
                for (i in b.from until b.to) {
                    val k = idx[i]; val w = hist[k]
                    r += ((k shr 10) * 8 + 4L) * w; g += (((k shr 5) and 31) * 8 + 4L) * w; bl += ((k and 31) * 8 + 4L) * w
                    lut[k] = bi.toShort()
                }
                val c = b.count.coerceAtLeast(1)
                colors[bi] = (0xFF shl 24) or ((r / c).toInt() shl 16) or ((g / c).toInt() shl 8) or (bl / c).toInt()
                weight[bi] = b.count.toInt()
            }
            // Colours that never occurred map to the closest palette entry.
            for (k in 0 until 32768) if (lut[k].toInt() == -1) {
                val r = (k shr 10) * 8 + 4; val g = ((k shr 5) and 31) * 8 + 4; val b = (k and 31) * 8 + 4
                var best = 0; var bd = Int.MAX_VALUE
                for (i in colors.indices) {
                    val c = colors[i]
                    val dr = r - ((c shr 16) and 255); val dg = g - ((c shr 8) and 255); val db = b - (c and 255)
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) { bd = d; best = i }
                }
                lut[k] = best.toShort()
            }
            return Palette(colors, weight, lut)
        }

        /** Plain Italian colour name. */
        fun nameOf(c: Int): String {
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            val s = if (mx <= 0f) 0f else d / mx
            if (mx < 0.16f) return "nero"
            if (s < 0.14f) return if (mx > 0.82f) "bianco" else "grigio"
            var h = when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
            if (h < 0f) h += 360f
            return when {
                h < 18f || h >= 340f -> if (mx < 0.45f) "marrone" else if (s < 0.4f) "rosa" else "rosso"
                h < 48f -> if (mx < 0.62f || s < 0.45f) "marrone" else "arancione"
                h < 70f -> if (mx < 0.5f) "marrone" else if (s < 0.3f) "beige" else "giallo"
                h < 165f -> "verde"
                h < 200f -> "azzurro"
                h < 255f -> "blu"
                h < 295f -> "viola"
                else -> if (mx < 0.5f) "viola" else "rosa"
            }
        }
    }
}
