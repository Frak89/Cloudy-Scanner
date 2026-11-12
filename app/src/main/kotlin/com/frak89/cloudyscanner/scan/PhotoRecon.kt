package com.frak89.cloudyscanner.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.core.SfmCloud
import com.frak89.cloudyscanner.core.SfmMath
import com.frak89.cloudyscanner.core.SfmView
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 3D reconstruction from imported photos (structure from motion): finds the same details in
 * several photos, works out where each photo was taken from, and triangulates the details
 * into a coloured point cloud that grows photo by photo. Feature detection, matching and pose
 * solving come from OpenCV; the geometry is in [SfmMath].
 *
 * Photos carry no real-world scale, so the result is sized so that the subject sits about
 * 1.5 m from the first photo.
 */
class PhotoRecon(private val ctx: Context, private val map: PointMap) {
    @Volatile var running = false
        private set
    @Volatile var status = ""
    @Volatile var cancel = false

    private val thumbs = ArrayList<Bitmap>()
    /** Photos placed in the last run (0 when it failed). */
    @Volatile var usedPhotos = 0

    /** The first imported photos tiled into one JPEG, for the AI to look at. */
    fun contactSheet(): ByteArray? {
        if (thumbs.isEmpty()) return null
        val cell = 448
        val cols = if (thumbs.size == 1) 1 else 2
        val rows = (thumbs.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(sheet)
        cv.drawColor(0xFF101216.toInt())
        for ((i, b) in thumbs.withIndex()) {
            if (b.isRecycled) continue
            cv.drawBitmap(b, (i % cols) * cell + (cell - b.width) / 2f, (i / cols) * cell + (cell - b.height) / 2f, null)
        }
        val os = java.io.ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 88, os)
        sheet.recycle()
        return os.toByteArray()
    }

    private val views = ArrayList<SfmView>()
    private val descs = ArrayList<Mat>()
    private val cloud = SfmCloud()
    private val order = ArrayList<Int>()
    private var scale = 0.0
    private var ref = 0

    private fun load(uri: Uri): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        val opt = BitmapFactory.Options()
        opt.inSampleSize = 1
        while (max(o.outWidth, o.outHeight) / (opt.inSampleSize * 2) >= MAX_SIDE) opt.inSampleSize *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) } ?: return null
        val sc = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
        val m = Matrix()
        if (sc < 1f) m.postScale(sc, sc)
        try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                }
            }
        } catch (_: Throwable) {}
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Focal length in pixels from the photo's own data when present, else a typical phone lens. */
    private fun focal(uri: Uri, w: Int, h: Int): Double {
        val diag = sqrt((w.toDouble() * w + h.toDouble() * h))
        try {
            ctx.contentResolver.openInputStream(uri)?.use {
                val f35 = ExifInterface(it).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                if (f35 in 8..400) return f35 * diag / 43.27
            }
        } catch (_: Throwable) {}
        return 0.65 * diag
    }

    private fun kMat(v: SfmView): Mat = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, v.f, 0.0, v.cx, 0.0, v.f, v.cy, 0.0, 0.0, 1.0) }

    private val cache = HashMap<Long, IntArray>()

    /**
     * Matches as (keypoint in a, keypoint in b) pairs that pass the ratio test. Each pair of
     * photos is matched once and remembered. [loose] accepts more doubtful matches: only safe
     * once both photos are placed, because triangulation then rejects the wrong ones.
     */
    private fun match(matcher: BFMatcher, a: Int, b: Int, loose: Boolean = false): IntArray {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val key = (lo.toLong() shl 21 or hi.toLong()) shl 1 or (if (loose) 1L else 0L)
        var m = cache[key]
        if (m == null) {
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descs[lo], descs[hi], knn, 2)
            val out = IntArray(knn.size * 2); var n = 0
            val ratio = if (loose) 0.9f else 0.75f
            for (k in knn) {
                val d = k.toArray()
                if (d.size >= 2 && d[0].distance < ratio * d[1].distance) { out[n * 2] = d[0].queryIdx; out[n * 2 + 1] = d[0].trainIdx; n++ }
                k.release()
            }
            m = out.copyOf(n * 2)
            cache[key] = m
        }
        if (a == lo) return m
        return IntArray(m.size) { m[it xor 1] }      // swap the two columns
    }

    private fun setPose(v: SfmView, rvec: Mat, tvec: Mat) {
        val rm = Mat()
        Calib3d.Rodrigues(rvec, rm)
        rm.get(0, 0, v.r)
        tvec.get(0, 0, v.t)
        rm.release()
    }

    /** Re-solves one photo's position against all the points it sees. */
    private fun resect(v: SfmView) {
        val obj = ArrayList<Point3>(); val img = ArrayList<Point>()
        for (i in v.pt3.indices) {
            val k = v.pt3[i]; if (k < 0) continue
            obj.add(Point3(cloud.xyz[k * 3], cloud.xyz[k * 3 + 1], cloud.xyz[k * 3 + 2])); img.add(Point(v.kp[i * 2].toDouble(), v.kp[i * 2 + 1].toDouble()))
        }
        if (obj.size < 12) return
        val rm = Mat(3, 3, CvType.CV_64F); rm.put(0, 0, *v.r)
        val rvec = Mat(); Calib3d.Rodrigues(rm, rvec)
        val tvec = Mat(3, 1, CvType.CV_64F); tvec.put(0, 0, *v.t)
        val o = MatOfPoint3f(); o.fromList(obj); val p = MatOfPoint2f(); p.fromList(img); val k = kMat(v)
        if (Calib3d.solvePnP(o, p, k, MatOfDouble(), rvec, tvec, true, Calib3d.SOLVEPNP_ITERATIVE)) setPose(v, rvec, tvec)
        rm.release(); rvec.release(); tvec.release(); o.release(); p.release(); k.release()
    }

    private fun refine(rounds: Int) {
        repeat(rounds) {
            SfmMath.intersect(views, cloud)
            for (vi in order) if (vi != ref) resect(views[vi])
        }
        SfmMath.intersect(views, cloud)
    }

    /**
     * Tries two photos as the starting pair: relative position from the essential matrix, then
     * triangulation. Returns how many points came out; unless [commit], everything is undone.
     */
    private fun initPair(matcher: BFMatcher, a: Int, b: Int, commit: Boolean): Int {
        val buf = match(matcher, a, b)
        val n = buf.size / 2
        if (n < 30) return 0
        val va = views[a]; val vb = views[b]
        val l1 = ArrayList<Point>(n); val l2 = ArrayList<Point>(n)
        for (m in 0 until n) {
            l1.add(Point(va.kp[buf[m * 2] * 2].toDouble(), va.kp[buf[m * 2] * 2 + 1].toDouble()))
            l2.add(Point(vb.kp[buf[m * 2 + 1] * 2].toDouble(), vb.kp[buf[m * 2 + 1] * 2 + 1].toDouble()))
        }
        val p1 = MatOfPoint2f(); p1.fromList(l1); val p2 = MatOfPoint2f(); p2.fromList(l2)
        val k = kMat(va); val mask = Mat(); val r = Mat(); val t = Mat()
        var made = 0
        val e = Calib3d.findEssentialMat(p1, p2, k, Calib3d.RANSAC, 0.999, 1.5, 1000, mask)
        if (!e.empty() && e.rows() == 3 && Calib3d.recoverPose(e, p1, p2, k, r, t, mask) >= 20) {
            for (i in 0 until 9) va.r[i] = if (i % 4 == 0) 1.0 else 0.0
            va.t.fill(0.0)
            r.get(0, 0, vb.r); t.get(0, 0, vb.t)
            va.registered = true; vb.registered = true
            val keep = IntArray(n * 2); var kn = 0
            val mk = ByteArray(1)
            for (m in 0 until n) { mask.get(m, 0, mk); if (mk[0].toInt() != 0) { keep[kn * 2] = buf[m * 2]; keep[kn * 2 + 1] = buf[m * 2 + 1]; kn++ } }
            made = SfmMath.triangulate(va, vb, keep, kn, cloud)
        }
        p1.release(); p2.release(); k.release(); mask.release(); e.release(); r.release(); t.release()
        if (!commit || made < 20) {
            va.registered = false; vb.registered = false; va.pt3.fill(-1); vb.pt3.fill(-1); cloud.n = 0
            return if (made < 20) 0 else made
        }
        ref = a
        order.add(a); order.add(b)
        return made
    }

    /** Which placed photos a new one is compared with: all of them for small sets, the latest otherwise. */
    private fun targets(): List<Int> = if (views.size <= SMALL_SET) ArrayList(order) else order.takeLast(4)

    /** How many already-known 3D points photo [vi] shares with the placed photos. */
    private fun support(matcher: BFMatcher, vi: Int): Int {
        var c = 0
        for (ri in targets()) { val m = match(matcher, vi, ri); val r = views[ri]; for (q in 0 until m.size / 2) if (r.pt3[m[q * 2 + 1]] >= 0) c++ }
        return c
    }

    /** Places one more photo using the points already known, then adds the new points it reveals. */
    private fun register(matcher: BFMatcher, vi: Int): Boolean {
        val v = views[vi]
        val against = targets()
        val obj = ArrayList<Point3>(); val img = ArrayList<Point>(); val who = ArrayList<Int>(); val which = ArrayList<Int>()
        for (ri in against) {
            val r = views[ri]
            val m = match(matcher, vi, ri)
            for (q in 0 until m.size / 2) {
                val i = m[q * 2]; val k = r.pt3[m[q * 2 + 1]]
                if (k < 0) continue
                obj.add(Point3(cloud.xyz[k * 3], cloud.xyz[k * 3 + 1], cloud.xyz[k * 3 + 2])); img.add(Point(v.kp[i * 2].toDouble(), v.kp[i * 2 + 1].toDouble()))
                who.add(i); which.add(k)
            }
        }
        if (obj.size < 12) return false
        val o = MatOfPoint3f(); o.fromList(obj); val p = MatOfPoint2f(); p.fromList(img); val k = kMat(v)
        val rvec = Mat(); val tvec = Mat(); val inl = Mat()
        val ok = Calib3d.solvePnPRansac(o, p, k, MatOfDouble(), rvec, tvec, false, 400, 4f, 0.99, inl)
        val ni = inl.rows()
        if (!ok || ni < 10) { o.release(); p.release(); k.release(); rvec.release(); tvec.release(); inl.release(); return false }
        setPose(v, rvec, tvec)
        val one = IntArray(1)
        for (q in 0 until ni) { inl.get(q, 0, one); v.pt3[who[one[0]]] = which[one[0]] }
        v.registered = true
        resect(v)
        for (ri in against) { val m = match(matcher, vi, ri); SfmMath.triangulate(v, views[ri], m, m.size / 2, cloud) }
        order.add(vi)
        o.release(); p.release(); k.release(); rvec.release(); tvec.release(); inl.release()
        return true
    }

    /**
     * Copies the current cloud into the app's point map: y-up, metres-ish, normals facing the
     * cameras (and, when [fine], fitted to the neighbouring points).
     */
    private fun publish(fine: Boolean) {
        val n = cloud.n
        if (n == 0) return
        val rv = views[ref]
        if (scale == 0.0) {
            val depths = ArrayList<Double>()
            for (k in 0 until n) { val z = rv.r[6] * cloud.xyz[k * 3] + rv.r[7] * cloud.xyz[k * 3 + 1] + rv.r[8] * cloud.xyz[k * 3 + 2] + rv.t[2]; if (z > 0) depths.add(z) }
            if (depths.isEmpty()) return
            depths.sort()
            scale = 1.5 / depths[depths.size / 2]
        }
        val toward = FloatArray(n * 3); val seen = IntArray(n)
        val c = DoubleArray(3)
        for (v in views) {
            if (!v.registered) continue
            v.center(c)
            for (i in v.pt3.indices) {
                val k = v.pt3[i]; if (k < 0) continue
                val dx = c[0] - cloud.xyz[k * 3]; val dy = c[1] - cloud.xyz[k * 3 + 1]; val dz = c[2] - cloud.xyz[k * 3 + 2]
                val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-9)
                // camera frame of the first photo (x right, y down, z forward) -> app world (y up, -z forward)
                toward[k * 3] += (dx / l).toFloat(); toward[k * 3 + 1] -= (dy / l).toFloat(); toward[k * 3 + 2] -= (dz / l).toFloat()
                seen[k]++
            }
        }
        var m = 0
        val pos = FloatArray(n * 3); val tw = FloatArray(n * 3); val col = IntArray(n)
        for (k in 0 until n) {
            if (seen[k] < 2) continue
            val x = (cloud.xyz[k * 3] * scale).toFloat(); val y = (-cloud.xyz[k * 3 + 1] * scale).toFloat(); val z = (-cloud.xyz[k * 3 + 2] * scale).toFloat()
            if (x * x + y * y + z * z > 30f * 30f) continue          // a few wild points always exist
            pos[m * 3] = x; pos[m * 3 + 1] = y; pos[m * 3 + 2] = z
            tw[m * 3] = toward[k * 3]; tw[m * 3 + 1] = toward[k * 3 + 1]; tw[m * 3 + 2] = toward[k * 3 + 2]
            col[m] = cloud.rgb[k]; m++
        }
        val nr = if (fine) SfmMath.normals(m, pos, tw, 0.06f) else tw
        map.lock.lock()
        try {
            map.clear()
            for (i in 0 until m) map.insert(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], nr[i * 3], nr[i * 3 + 1], nr[i * 3 + 2], col[i], 1f, false, 0, 6f)
        } finally { map.lock.unlock() }
    }

    /** Blocking: call from a worker thread. [log] receives one line per step. */
    fun run(uris: List<Uri>, log: (String) -> Unit) {
        if (running) return
        running = true; cancel = false
        views.clear(); descs.clear(); cloud.n = 0; order.clear(); scale = 0.0; cache.clear()
        for (t in thumbs) t.recycle()
        thumbs.clear(); usedPhotos = 0
        try {
            if (!OpenCVLoader.initLocal()) { log("Impossibile avviare OpenCV su questo telefono."); return }
            // Few photos: squeeze more details out of each one.
            val sift = SIFT.create(if (uris.size <= 5) 8000 else FEATURES)
            val matcher = BFMatcher.create(Core.NORM_L2, false)
            val t0 = System.nanoTime()
            val label = ArrayList<Int>()          // photo number (1-based) of each usable view
            for ((n, uri) in uris.withIndex()) {
                if (cancel) return
                status = "Foto ${n + 1}/${uris.size}: cerco i dettagli…"
                val bmp = load(uri)
                if (bmp == null) { log("Foto ${n + 1}: non leggibile, saltata."); continue }
                val v = SfmView(bmp.width, bmp.height, focal(uri, bmp.width, bmp.height))
                val rgba = Mat(); val gray = Mat(); val kps = MatOfKeyPoint(); val d = Mat()
                Utils.bitmapToMat(bmp, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                sift.detectAndCompute(gray, Mat(), kps, d)
                val arr = kps.toArray()
                v.kp = FloatArray(arr.size * 2); v.color = IntArray(arr.size); v.pt3 = IntArray(arr.size) { -1 }
                for ((i, kp) in arr.withIndex()) {
                    v.kp[i * 2] = kp.pt.x.toFloat(); v.kp[i * 2 + 1] = kp.pt.y.toFloat()
                    v.color[i] = bmp.getPixel(kp.pt.x.toInt().coerceIn(0, bmp.width - 1), kp.pt.y.toInt().coerceIn(0, bmp.height - 1))
                }
                if (thumbs.size < 4) { val sc = 448f / max(bmp.width, bmp.height); thumbs.add(Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)) }
                rgba.release(); gray.release(); kps.release(); bmp.recycle()
                if (arr.size < 60) { log("Foto ${n + 1}: troppo pochi dettagli (${arr.size}), saltata."); d.release(); continue }
                views.add(v); descs.add(d); label.add(n + 1)
            }
            val nv = views.size
            if (nv < 2) { log("Servono almeno due foto con dettagli riconoscibili."); return }

            // Starting pair: with few photos every pair is tried and the most productive one wins.
            status = "Cerco la coppia di foto migliore da cui partire…"
            var bestA = -1; var bestB = -1; var bestMade = 0
            for (a in 0 until nv) for (b in a + 1 until nv) {
                if (cancel) return
                if (nv > SMALL_SET && b - a > 2) continue
                if (nv > SMALL_SET && bestMade > 150) break
                val made = initPair(matcher, a, b, false)
                if (made > bestMade) { bestMade = made; bestA = a; bestB = b }
            }
            if (bestA < 0 || initPair(matcher, bestA, bestB, true) == 0) {
                log("Non trovo due foto che inquadrino gli stessi dettagli da punti di vista diversi. Servono scatti dello stesso soggetto, spostandosi di lato tra uno e l'altro (non solo ruotando il telefono).")
                return
            }
            log("Partenza dalle foto ${label[bestA]} e ${label[bestB]}: ${cloud.n} punti.")
            publish(false)

            // Then always the photo that shares most with what is already built.
            val failed = HashSet<Int>()
            while (order.size < nv) {
                if (cancel) return
                var next = -1; var best = 0
                for (vi in 0 until nv) {
                    if (views[vi].registered || vi in failed) continue
                    if (nv > SMALL_SET && order.none { kotlin.math.abs(it - vi) <= 2 }) continue
                    val s = support(matcher, vi)
                    if (s > best) { best = s; next = vi }
                }
                if (next < 0) break
                status = "Foto ${label[next]}: calcolo la posizione di scatto…"
                if (register(matcher, next)) {
                    if (order.size % 3 == 0) refine(1)
                    publish(false)
                    log("Foto ${label[next]}: posizionata · ${cloud.n} punti")
                    failed.clear()          // a new photo may connect ones that failed before
                } else failed.add(next)
            }
            for (vi in 0 until nv) if (!views[vi].registered) log("Foto ${label[vi]}: non collegabile alle altre (poca sovrapposizione), lasciata fuori.")

            // Second pass with the positions known: doubtful matches can now be checked by geometry.
            status = "Infittisco la nuvola…"
            refine(1)
            val before = cloud.n
            for (x in order.indices) for (y in x + 1 until order.size) {
                if (cancel) return
                if (nv > SMALL_SET && y - x > 2) continue
                val m = match(matcher, order[x], order[y], true)
                SfmMath.triangulate(views[order[x]], views[order[y]], m, m.size / 2, cloud)
            }
            if (cloud.n > before) log("Infittimento: +${cloud.n - before} punti.")
            status = "Rifinisco posizioni e punti…"
            refine(3)
            publish(true)
            usedPhotos = order.size
            val secs = (System.nanoTime() - t0) / 1e9
            log(String.format(Locale.ITALY, "Ricostruzione da foto completata: %d foto su %d usate · %,d punti · errore mediano %.1f px · %.0f s.\nLa scala è stimata (le foto non contengono misure reali).",
                order.size, uris.size, map.size, SfmMath.medianError(views, cloud), secs))
        } catch (t: Throwable) {
            log("Ricostruzione interrotta: ${t.javaClass.simpleName} ${t.message ?: ""}")
        } finally {
            for (d in descs) d.release()
            descs.clear(); cache.clear()
            status = ""
            running = false
        }
    }

    companion object {
        const val MAX_SIDE = 1600
        const val FEATURES = 5000
        const val MAX_PHOTOS = 40
        /** Up to this many photos, every photo is compared with every other. */
        const val SMALL_SET = 8
    }
}
