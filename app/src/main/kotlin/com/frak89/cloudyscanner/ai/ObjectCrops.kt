package com.frak89.cloudyscanner.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import com.frak89.cloudyscanner.core.CatalogPart
import com.frak89.cloudyscanner.scan.Shot
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A camera frame with its pose (camera to world, column major) and intrinsics (fx, fy, cx, cy, w, h). */
class PosedImage(val jpeg: ByteArray?, val file: String?, val pose: FloatArray, val intr: FloatArray, val rot: Int)

/** Finds and cuts out each catalogued object from the photos, so the AI can look at one thing at a time. */
object ObjectCrops {
    /** Pixel position (u, v) and depth of a world point, or null when it is behind the camera. */
    fun project(pose: FloatArray, intr: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val dx = x - pose[12]; val dy = y - pose[13]; val dz = z - pose[14]
        val cx = pose[0] * dx + pose[1] * dy + pose[2] * dz
        val cy = pose[4] * dx + pose[5] * dy + pose[6] * dz
        val cz = -(pose[8] * dx + pose[9] * dy + pose[10] * dz)
        if (cz < 0.08f) return null
        return floatArrayOf(cx / cz * intr[0] + intr[2], intr[3] - cy / cz * intr[1], cz)
    }

    /** Where the object would sit in this image: (left, top, right, bottom, score) or null when it is not in view. */
    private fun frame(p: CatalogPart, img: PosedImage): FloatArray? {
        val c = project(img.pose, img.intr, p.center[0], p.center[1], p.center[2]) ?: return null
        val w = img.intr[4]; val h = img.intr[5]
        if (c[0] < 0 || c[1] < 0 || c[0] >= w || c[1] >= h) return null
        val pr = (p.size / c[2] * img.intr[0] * 1.5f + 32f).coerceAtMost(max(w, h))
        val l = max(0f, c[0] - pr); val t = max(0f, c[1] - pr); val r = min(w, c[0] + pr); val b = min(h, c[1] + pr)
        if (r - l < 48 || b - t < 48) return null
        // prefer close, whole views: big on screen and not cut by the edge
        val cut = ((r - l) * (b - t)) / (4 * pr * pr)
        return floatArrayOf(l, t, r, b, pr * cut)
    }

    /** The best upright JPEG crop of [p] among [images], or null when no image shows it. */
    fun crop(p: CatalogPart, images: List<PosedImage>): ByteArray? {
        var best: PosedImage? = null; var bf: FloatArray? = null
        for (img in images) { val f = frame(p, img) ?: continue; if (bf == null || f[4] > bf[4]) { best = img; bf = f } }
        val img = best ?: return null; val f = bf!!
        return try {
            val dec = if (img.file != null) BitmapRegionDecoder.newInstance(img.file) else BitmapRegionDecoder.newInstance(img.jpeg!!, 0, img.jpeg.size)
            val sx = dec.width / img.intr[4]; val sy = dec.height / img.intr[5]
            val rect = Rect((f[0] * sx).toInt(), (f[1] * sy).toInt(), (f[2] * sx).toInt().coerceAtMost(dec.width), (f[3] * sy).toInt().coerceAtMost(dec.height))
            val opt = BitmapFactory.Options()
            opt.inSampleSize = 1
            while (max(rect.width(), rect.height()) / (opt.inSampleSize * 2) >= 640) opt.inSampleSize *= 2
            val bmp = dec.decodeRegion(rect, opt)
            dec.recycle()
            val deg = when (img.rot) { 0 -> 90f; 2 -> 270f; 3 -> 180f; else -> 0f }
            val up = if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            ByteArrayOutputStream().also { up.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        } catch (t: Throwable) { null }
    }

    fun fromShot(s: Shot) = PosedImage(null, s.file, s.pose, s.intr, s.rot)

    /** How well the photos cover an object: [photos that show it, sides seen out of 8, seen from above 0/1]. */
    fun coverage(p: CatalogPart, shots: List<Shot>): IntArray {
        var n = 0; val sides = BooleanArray(8); var top = 0
        for (s in shots) {
            if (frame(p, fromShot(s)) == null) continue
            n++
            val dx = s.pose[12] - p.center[0]; val dy = s.pose[13] - p.center[1]; val dz = s.pose[14] - p.center[2]
            val horiz = sqrt(dx * dx + dz * dz)
            if (atan2(dy, horiz) > 0.96f) top = 1
            else sides[(((atan2(dz, dx) + Math.PI) / (2 * Math.PI) * 8).toInt()).coerceIn(0, 7)] = true
        }
        return intArrayOf(n, sides.count { it }, top)
    }
}
