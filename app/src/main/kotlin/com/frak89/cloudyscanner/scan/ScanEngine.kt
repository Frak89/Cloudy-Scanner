package com.frak89.cloudyscanner.scan

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.frak89.cloudyscanner.core.PointMap
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Owns the ARCore session. The GL thread calls [update] every frame; depth frames are fused
 * into [map] on a single background thread so rendering never waits on fusion.
 */
class ScanEngine(private val activity: Activity, val map: PointMap) {
    private var session: Session? = null
    private var installRequested = false
    private var running = false
    private var runMessage: String? = null
    /** Last failure inside the fusion thread, shown in the stats so problems are not silent. */
    @Volatile var lastError = ""
    private var textureId = -1
    private var textureBound = false
    private var geomDirty = true
    private var rotation = 0
    private var viewW = 1
    private var viewH = 1

    @Volatile var scanning = false
    @Volatile var tracking = false
    @Volatile var trackingText = "Avvio…"
    @Volatile var depthSupported = true
    @Volatile var cameraId = "?"

    val viewM = FloatArray(16)
    val projM = FloatArray(16)
    val poseM = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    /** Camera background quad texture coordinates (4 corners, triangle-strip order). */
    val bgUv = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val viewToTex = floatArrayOf(0f, 1f, 0f, 0f, 0f, 1f)
    private var uvReady = false

    // Latest depth frame (GL thread writes, UI thread reads for taps).
    private var curDepth = ShortArray(0)
    private var curConf = ByteArray(0)
    @Volatile private var dW = 0
    @Volatile private var dH = 0
    private var lastDepthTs = 0L
    private var fx = 0f; private var fy = 0f; private var cx = 0f; private var cy = 0f

    // Live readouts
    @Volatile var centerDistance = 0f
    @Volatile var focusDistance = 0f
    @Volatile var focalLength = 0f
    @Volatile var aperture = 0f
    @Volatile var iso = 0
    @Volatile var exposureMs = 0f
    @Volatile var depthSize = ""
    @Volatile var imageSize = ""

    val fusion = Fusion(map)
    val guidance get() = fusion.guidance
    val subject get() = fusion.subject
    val integMs get() = fusion.integMs
    val depthFps get() = fusion.depthFps
    val uncertainCount get() = fusion.uncertainCount
    val framesFused get() = fusion.framesFused
    var bgStride: Int
        get() = fusion.bgStride
        set(v) { fusion.bgStride = v }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "fusion").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    private val job = FrameJob()
    private var frameCounter = 0

    /** Set to receive one JPEG of the next camera image (called on the GL thread). */
    @Volatile var jpegRequest: ((ByteArray?) -> Unit)? = null

    // ---------------------------------------------------------------- lifecycle (UI thread)

    /** Returns null when running, or a message explaining why AR could not start. */
    fun resume(): String? {
        if (running) return runMessage
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return "Installazione di Google Play Services per AR…" }
                    else -> {}
                }
                val s = Session(activity)
                val c = Config(s)
                depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                c.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                c.focusMode = Config.FocusMode.AUTO
                c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                c.planeFindingMode = Config.PlaneFindingMode.DISABLED
                c.lightEstimationMode = Config.LightEstimationMode.DISABLED
                s.configure(c)
                // Largest CPU image the phone offers: sharper photos and better point colours.
                try {
                    val best = s.getSupportedCameraConfigs(CameraConfigFilter(s)).maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                    if (best != null) { s.cameraConfig = best; imageSize = "${best.imageSize.width}x${best.imageSize.height}" }
                } catch (_: Throwable) {}
                cameraId = try { s.cameraConfig.cameraId } catch (e: Throwable) { "?" }
                session = s
                textureBound = false
                geomDirty = true
                uvReady = false
            }
            session?.resume()
            running = true
            runMessage = if (depthSupported) null else "Questo dispositivo non supporta la Depth API di ARCore."
            return runMessage
        } catch (e: Throwable) {
            session = null
            running = false
            return "AR non disponibile: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    fun pause() {
        scanning = false
        running = false
        try { session?.pause() } catch (_: Throwable) {}
    }

    fun destroy() {
        worker.shutdownNow()
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    fun setTexture(id: Int) { textureId = id; textureBound = false }

    fun setViewport(rot: Int, w: Int, h: Int) { rotation = rot; viewW = w; viewH = h; geomDirty = true }

    fun reset() {
        map.lock.lock()
        try { map.clear() } finally { map.lock.unlock() }
        fusion.reset()
    }

    // ---------------------------------------------------------------- per frame (GL thread)

    /** Advances ARCore. Returns true when camera matrices and background are valid. */
    fun update(): Boolean {
        val s = session ?: return false
        if (textureId < 0) return false
        try {
            if (!textureBound) { s.setCameraTextureName(textureId); textureBound = true }
            if (geomDirty) { s.setDisplayGeometry(rotation, viewW, viewH); geomDirty = false }
            val frame = s.update()
            val cam = frame.camera
            if (frame.hasDisplayGeometryChanged() || !uvReady) computeUv(frame)
            cam.getViewMatrix(viewM, 0)
            cam.getProjectionMatrix(projM, 0, 0.05f, 80f)
            tracking = cam.trackingState == TrackingState.TRACKING
            trackingText = when {
                tracking -> "Tracking OK"
                cam.trackingState == TrackingState.PAUSED -> when (cam.trackingFailureReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Poca luce"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Movimento troppo rapido"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Pochi dettagli: inquadra superfici con texture"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Fotocamera occupata"
                    else -> "Inizializzazione: muovi lentamente il telefono"
                }
                else -> "Tracking perso"
            }
            readMetadata(frame)
            jpegRequest?.let { cb -> jpegRequest = null; cb(captureJpeg(frame)) }
            if (!tracking) return true
            cam.pose.toMatrix(poseM, 0)

            var newDepth = false
            if (depthSupported) {
                try {
                    frame.acquireRawDepthImage16Bits().use { img ->
                        if (img.timestamp != lastDepthTs) {
                            lastDepthTs = img.timestamp
                            val w = img.width; val h = img.height
                            if (curDepth.size != w * h) { curDepth = ShortArray(w * h); curConf = ByteArray(w * h) }
                            val p = img.planes[0]
                            val sb = p.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val rs = p.rowStride / 2
                            if (rs == w) sb.get(curDepth, 0, min(w * h, sb.remaining()))
                            else for (r in 0 until h) { sb.position(r * rs); sb.get(curDepth, r * w, w) }
                            dW = w; dH = h
                            newDepth = true
                        }
                    }
                    if (newDepth) frame.acquireRawDepthConfidenceImage().use { img ->
                        val p = img.planes[0]
                        val b = p.buffer
                        val w = img.width; val h = img.height
                        if (w * h == curConf.size) {
                            if (p.rowStride == w) b.get(curConf, 0, min(w * h, b.remaining()))
                            else for (r in 0 until h) { b.position(r * p.rowStride); b.get(curConf, r * w, w) }
                        }
                    }
                } catch (_: Throwable) {
                    // NotYetAvailableException until depth warms up.
                }
            }
            if (dW > 0) {
                val intr = cam.textureIntrinsics
                val dim = intr.imageDimensions
                val f = intr.focalLength; val pp = intr.principalPoint
                fx = f[0] * dW / dim[0]; fy = f[1] * dH / dim[1]
                cx = pp[0] * dW / dim[0]; cy = pp[1] * dH / dim[1]
                depthSize = "${dW}x$dH"
                centerDistance = depthAtView(viewW * 0.5f, viewH * 0.5f)
            }

            // While paused, a locked subject is still tracked (no points are added).
            if ((scanning || subject.locked) && newDepth && busy.compareAndSet(false, true)) {
                try {
                    fillJob(frame)
                    job.insert = scanning
                    worker.execute {
                        try { fusion.fuse(job); lastError = "" } catch (t: Throwable) {
                            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(90)
                            android.util.Log.e("Cloudy", "fusion failed", t)
                        } finally { busy.set(false) }
                    }
                } catch (e: Throwable) {
                    busy.set(false)
                }
            }
            return true
        } catch (e: Throwable) {
            trackingText = "Errore AR: ${e.javaClass.simpleName}"
            return false
        }
    }

    private fun computeUv(frame: Frame) {
        val ndc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, bgUv)
        val vin = floatArrayOf(0f, 0f, viewW.toFloat(), 0f, 0f, viewH.toFloat())
        val vout = FloatArray(6)
        frame.transformCoordinates2d(Coordinates2d.VIEW, vin, Coordinates2d.TEXTURE_NORMALIZED, vout)
        viewToTex[0] = vout[0]; viewToTex[1] = (vout[2] - vout[0]) / viewW; viewToTex[2] = (vout[4] - vout[0]) / viewH
        viewToTex[3] = vout[1]; viewToTex[4] = (vout[3] - vout[1]) / viewW; viewToTex[5] = (vout[5] - vout[1]) / viewH
        uvReady = true
    }

    private fun readMetadata(frame: Frame) {
        try {
            val md = frame.imageMetadata
            try { val d = md.getFloat(ImageMetadata.LENS_FOCUS_DISTANCE); focusDistance = if (d > 0.01f) 1f / d else 0f } catch (_: Throwable) {}
            try { focalLength = md.getFloat(ImageMetadata.LENS_FOCAL_LENGTH) } catch (_: Throwable) {}
            try { aperture = md.getFloat(ImageMetadata.LENS_APERTURE) } catch (_: Throwable) {}
            try { iso = md.getInt(ImageMetadata.SENSOR_SENSITIVITY) } catch (_: Throwable) {}
            try { exposureMs = md.getLong(ImageMetadata.SENSOR_EXPOSURE_TIME) / 1e6f } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun copyPlane(p: Image.Plane, dst: ByteArray): ByteArray {
        val b = p.buffer
        val n = b.remaining()
        val out = if (dst.size >= n) dst else ByteArray(n)
        b.get(out, 0, n)
        return out
    }

    private fun fillJob(frame: Frame) {
        val j = job
        val n = dW * dH
        if (j.depth.size != n) { j.depth = ShortArray(n); j.conf = ByteArray(n) }
        System.arraycopy(curDepth, 0, j.depth, 0, n)
        System.arraycopy(curConf, 0, j.conf, 0, n)
        j.w = dW; j.h = dH; j.fx = fx; j.fy = fy; j.cx = cx; j.cy = cy
        System.arraycopy(poseM, 0, j.pose, 0, 16)
        j.frame = ++frameCounter
        j.timeNs = System.nanoTime()
        j.hasColor = false
        try {
            frame.acquireCameraImage().use { img ->
                j.iw = img.width; j.ih = img.height
                val pl = img.planes
                j.yBuf = copyPlane(pl[0], j.yBuf); j.uBuf = copyPlane(pl[1], j.uBuf); j.vBuf = copyPlane(pl[2], j.vBuf)
                j.yRow = pl[0].rowStride; j.uvRow = pl[1].rowStride; j.uvPix = pl[1].pixelStride
                val tin = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
                val tout = FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, tin, Coordinates2d.IMAGE_PIXELS, tout)
                j.toImg[0] = tout[0]; j.toImg[1] = tout[2] - tout[0]; j.toImg[2] = tout[4] - tout[0]
                j.toImg[3] = tout[1]; j.toImg[4] = tout[3] - tout[1]; j.toImg[5] = tout[5] - tout[1]
                val ii = frame.camera.imageIntrinsics
                j.imgIntr[0] = ii.focalLength[0]; j.imgIntr[1] = ii.focalLength[1]
                j.imgIntr[2] = ii.principalPoint[0]; j.imgIntr[3] = ii.principalPoint[1]
                j.exposureMs = exposureMs
                j.rot = rotation
                j.hasColor = true
            }
        } catch (_: Throwable) {}
    }

    private fun captureJpeg(frame: Frame): ByteArray? = try {
        frame.acquireCameraImage().use { img ->
            val w = img.width; val h = img.height
            val pl = img.planes
            val nv21 = ByteArray(w * h * 3 / 2)
            val yb = pl[0].buffer; val ub = pl[1].buffer; val vb = pl[2].buffer
            val yr = pl[0].rowStride; val cr = pl[1].rowStride; val cp = pl[1].pixelStride
            for (r in 0 until h) { yb.position(r * yr); yb.get(nv21, r * w, w) }
            var o = w * h
            for (r in 0 until h / 2) for (c in 0 until w / 2) {
                val i = r * cr + c * cp
                nv21[o++] = vb.get(i); nv21[o++] = ub.get(i)
            }
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, out)
            out.toByteArray()
        }
    } catch (e: Throwable) { null }

    // ---------------------------------------------------------------- depth queries

    /** Depth in metres under a view pixel (median of a small window), 0 when unknown. */
    fun depthAtView(vx: Float, vy: Float): Float {
        val w = dW; val h = dH
        if (w == 0 || !uvReady) return 0f
        val u = ((viewToTex[0] + viewToTex[1] * vx + viewToTex[2] * vy) * w).toInt()
        val v = ((viewToTex[3] + viewToTex[4] * vx + viewToTex[5] * vy) * h).toInt()
        val d = curDepth
        if (d.size != w * h) return 0f
        val tmp = IntArray(25); var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val x = u + dx; val y = v + dy
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val mm = d[y * w + x].toInt() and 0xFFFF
            if (mm > 0) tmp[n++] = mm
        }
        if (n == 0) return 0f
        java.util.Arrays.sort(tmp, 0, n)
        return tmp[n / 2] * 0.001f
    }

    /** Locks the subject on whatever is under the tapped view pixel. */
    fun lockSubjectAt(vx: Float, vy: Float): Boolean {
        val z = depthAtView(vx, vy)
        if (z <= 0f) return false
        val u = (viewToTex[0] + viewToTex[1] * vx + viewToTex[2] * vy) * dW
        val v = (viewToTex[3] + viewToTex[4] * vx + viewToTex[5] * vy) * dH
        val x = (u - cx) / fx * z; val y = (cy - v) / fy * z
        val m = poseM
        fusion.lockAt(
            m[0] * x + m[4] * y - m[8] * z + m[12],
            m[1] * x + m[5] * y - m[9] * z + m[13],
            m[2] * x + m[6] * y - m[10] * z + m[14])
        return true
    }

    fun unlockSubject() { subject.locked = false }
}
