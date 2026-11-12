package com.frak89.cloudyscanner.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import com.frak89.cloudyscanner.Opt
import com.frak89.cloudyscanner.Storage
import com.frak89.cloudyscanner.core.PointMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Keeps the photos chosen by [PhotoPlanner] in the app cache, each with its camera pose and
 * intrinsics, and reuses them: recolouring the cloud, contact sheets for the AI, export.
 */
class PhotoStore(ctx: Context, val planner: PhotoPlanner, private val subject: SubjectState) {
    private val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
    private var lastFwd = FloatArray(3)
    private var lastNs = 0L
    private var lastShotNs = 0L
    /** Set to take the next usable frame regardless of the plan. */
    @Volatile var manualRequest = false
    @Volatile var lastEvent = ""
    /** Live view of the camera around the subject: azimuth, elevation, distance, ideal distance. */
    val live = FloatArray(4)
    @Volatile var liveValid = false

    init { dir.listFiles()?.forEach { it.delete() } }

    /** Fusion-thread hook: judge this frame and keep it if it adds something. */
    fun onFrame(j: FrameJob) {
        val s = subject
        val m = j.pose
        // Angular speed of the camera -> motion blur in pixels over the exposure.
        val fx = -m[8]; val fy = -m[9]; val fz = -m[10]
        var blur = 0f
        if (lastNs != 0L) {
            val dt = ((j.timeNs - lastNs) / 1e9f).coerceAtLeast(1e-3f)
            val dot = (fx * lastFwd[0] + fy * lastFwd[1] + fz * lastFwd[2]).coerceIn(-1f, 1f)
            // Expressed in pixels of a 1920-wide frame so the limit does not depend on resolution.
            blur = acos(dot) / dt * (j.exposureMs.coerceIn(1f, 66f) / 1000f) * j.imgIntr[0] * 1920f / j.iw.coerceAtLeast(1)
        }
        lastFwd[0] = fx; lastFwd[1] = fy; lastFwd[2] = fz; lastNs = j.timeNs
        if (!j.hasColor || !s.valid || !s.locked) { liveValid = false; return }

        val v = planner.view(m[12], m[13], m[14], s.x, s.y, s.z)
        // Distance at which the whole object fills about three quarters of the frame height.
        val half = atan(j.ih / 2f / j.imgIntr[1].coerceAtLeast(1f))
        val ideal = if (s.extent > 0.05f) (s.extent / 2f) / tan(half) / 0.75f else 0f
        live[0] = v[0]; live[1] = v[1]; live[2] = v[2]; live[3] = ideal
        liveValid = true

        val manual = manualRequest
        if (!manual && (!Opt.photoAuto || !j.insert)) return
        if (!manual && j.timeNs - lastShotNs < 400_000_000L) return
        val r = s.imgRect
        val sharp = PhotoPlanner.sharpness(j.yBuf, j.yRow, j.iw, j.ih, r[0], r[1], r[2], r[3])
        val d = planner.consider(Candidate(v[0], v[1], v[2], sharp, blur, s.fill, s.inside, ideal), manual)
        if (!d.accept) {
            if (manual) { manualRequest = false; lastEvent = "Foto scartata: ${d.why}. Tieni fermo e riprova." }
            return
        }
        manualRequest = false
        val id = planner.newId()
        val f = File(dir, String.format(Locale.US, "shot_%04d.jpg", id))
        try {
            save(j, f)
        } catch (t: Throwable) { lastEvent = "Foto non salvata: ${t.message}"; return }
        d.replace?.let { File(it.file).delete() }
        planner.add(
            Shot(id, d.slot, d.detail, v[0], v[1], v[2], sharp, d.quality, j.pose.copyOf(),
                floatArrayOf(j.imgIntr[0], j.imgIntr[1], j.imgIntr[2], j.imgIntr[3], j.iw.toFloat(), j.ih.toFloat()),
                j.rot, f.absolutePath, manual), d.replace)
        lastShotNs = j.timeNs
        val name = if (d.slot >= 0) planner.slots[d.slot].name else "extra"
        lastEvent = "Foto «$name»" + (if (d.detail) " (dettaglio)" else "") + (if (d.replace != null) " sostituita con una più nitida" else " salvata")
    }

    private fun save(j: FrameJob, f: File) {
        val w = j.iw; val h = j.ih
        val nv21 = ByteArray(w * h * 3 / 2)
        for (r in 0 until h) System.arraycopy(j.yBuf, r * j.yRow, nv21, r * w, w)
        var o = w * h
        for (r in 0 until h / 2) {
            val row = r * j.uvRow
            for (c in 0 until w / 2) {
                val i = row + c * j.uvPix
                nv21[o++] = j.vBuf[i]; nv21[o++] = j.uBuf[i]
            }
        }
        FileOutputStream(f).use { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, it) }
    }

    fun delete(shots: List<Shot>) { shots.forEach { File(it.file).delete() } }

    fun clear() { delete(planner.reset()); lastEvent = "" }

    private fun upright(s: Shot, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inSampleSize = 1
        while (s.intr[4] / (o.inSampleSize * 2) >= maxSide) o.inSampleSize *= 2
        val b = BitmapFactory.decodeFile(s.file, o) ?: return null
        val deg = when (s.rot) { 0 -> 90f; 2 -> 270f; 3 -> 180f; else -> 0f }
        if (deg == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }

    /** Up to four of the best photos from different sides, tiled into one JPEG for the model. */
    fun contactSheet(): ByteArray? {
        val pick = planner.shots().filter { !it.detail }.sortedByDescending { it.quality }.distinctBy { it.slot }.take(4)
        if (pick.isEmpty()) return null
        val cell = 448
        val cols = if (pick.size == 1) 1 else 2
        val rows = (pick.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val cv = Canvas(sheet)
        cv.drawColor(0xFF101216.toInt())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((i, s) in pick.withIndex()) {
            val b = upright(s, cell * 2) ?: continue
            val sc = minOf(cell.toFloat() / b.width, cell.toFloat() / b.height)
            val dw = (b.width * sc).toInt(); val dh = (b.height * sc).toInt()
            val x = (i % cols) * cell + (cell - dw) / 2; val y = (i / cols) * cell + (cell - dh) / 2
            cv.drawBitmap(b, null, Rect(x, y, x + dw, y + dh), paint)
            b.recycle()
        }
        val os = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 88, os)
        sheet.recycle()
        return os.toByteArray()
    }

    /**
     * Recolours points from the kept photos: each point takes its colour from the photo that
     * sees it most squarely and from closest. Returns how many points changed.
     * Occlusion is judged only by the point's normal facing the camera.
     */
    fun recolor(map: PointMap, subjectOnly: Boolean): Int {
        val shots = planner.shots().sortedByDescending { it.quality }
        if (shots.isEmpty()) return 0
        var best = FloatArray(0)
        var changed = 0
        var px = IntArray(0)
        for (s in shots) {
            val b = BitmapFactory.decodeFile(s.file) ?: continue
            val w = b.width; val h = b.height
            if (px.size < w * h) px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            b.recycle()
            val m = s.pose
            val fx = s.intr[0] * w / s.intr[4]; val fy = s.intr[1] * h / s.intr[5]
            val cx = s.intr[2] * w / s.intr[4]; val cy = s.intr[3] * h / s.intr[5]
            map.lock.lock()
            try {
                val n = map.size
                if (best.size < n) best = best.copyOf(n + n / 4)
                for (i in 0 until n) {
                    if (subjectOnly && !map.hasFlag(i, PointMap.SUBJECT)) continue
                    val o = i * 3
                    val dx = map.pos[o] - m[12]; val dy = map.pos[o + 1] - m[13]; val dz = map.pos[o + 2] - m[14]
                    val x = m[0] * dx + m[1] * dy + m[2] * dz
                    val y = m[4] * dx + m[5] * dy + m[6] * dz
                    val z = -(m[8] * dx + m[9] * dy + m[10] * dz)
                    if (z < 0.1f) continue
                    val u = (x / z * fx + cx).toInt(); val v = (cy - y / z * fy).toInt()
                    if (u < 1 || v < 1 || u >= w - 1 || v >= h - 1) continue
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    var nx = map.nrm[o]; var ny = map.nrm[o + 1]; var nz = map.nrm[o + 2]
                    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                    nx /= nl; ny /= nl; nz /= nl
                    val facing = -(nx * dx + ny * dy + nz * dz) / dist
                    if (facing < 0.25f) continue
                    val score = facing / (0.3f + dist)
                    if (score <= best[i]) continue
                    if (best[i] == 0f) changed++
                    best[i] = score
                    map.col[i] = px[v * w + u] or (0xFF shl 24)
                }
                map.touch()
            } finally { map.lock.unlock() }
        }
        return changed
    }

    /** Copies the photos plus a poses.json (pose matrices and intrinsics) to the public folder. */
    fun export(ctx: Context): File? {
        val shots = planner.shots()
        if (shots.isEmpty()) return null
        val out = File(Storage.appDir(ctx), "photos_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        if (!out.mkdirs() && !out.isDirectory) return null
        val arr = JSONArray()
        for (s in shots) {
            val name = File(s.file).name
            File(s.file).copyTo(File(out, name), overwrite = true)
            arr.put(JSONObject()
                .put("file", name)
                .put("view", if (s.slot >= 0) planner.slots[s.slot].name else "extra")
                .put("detail", s.detail)
                .put("azimuth_deg", s.az.toDouble()).put("elevation_deg", s.el.toDouble()).put("distance_m", s.dist.toDouble())
                .put("sharpness", s.sharp.toDouble())
                .put("camera_to_world_column_major", JSONArray().apply { s.pose.forEach { put(it.toDouble()) } })
                .put("fx", s.intr[0].toDouble()).put("fy", s.intr[1].toDouble()).put("cx", s.intr[2].toDouble()).put("cy", s.intr[3].toDouble())
                .put("width", s.intr[4].toInt()).put("height", s.intr[5].toInt()))
        }
        File(out, "poses.json").writeText(JSONObject().put("convention", "OpenGL camera: x right, y up, looking along -z").put("photos", arr).toString(1))
        return out
    }
}
