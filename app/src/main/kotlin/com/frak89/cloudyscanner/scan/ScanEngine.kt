package com.frak89.cloudyscanner.scan

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.frak89.cloudyscanner.Opt
import com.frak89.cloudyscanner.core.LongIntMap
import com.frak89.cloudyscanner.core.PointMap
import com.google.ar.core.ArCoreApk
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One depth frame handed from the GL thread to the fusion thread. */
private class FrameJob {
    var w = 0; var h = 0
    var depth = ShortArray(0)
    var conf = ByteArray(0)
    var fx = 0f; var fy = 0f; var cx = 0f; var cy = 0f
    val pose = FloatArray(16)
    var hasColor = false
    var iw = 0; var ih = 0
    var yBuf = ByteArray(0); var uBuf = ByteArray(0); var vBuf = ByteArray(0)
    var yRow = 0; var uvRow = 0; var uvPix = 1
    /** depth-normalised (u,v) -> camera-image pixels: x = a0 + a1*u + a2*v, y = a3 + a4*u + a5*v */
    val toImg = FloatArray(6)
    var frame = 0
    var timeNs = 0L
}

/** What the scanner currently wants the user to look at again. */
class Guidance {
    @Volatile var active = false
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var z = 0f
    @Volatile var count = 0
    @Volatile var resolved = 0
}

class SubjectState {
    @Volatile var valid = false
    @Volatile var locked = false
    @Volatile var moving = false
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var z = 0f
    @Volatile var distance = 0f
    @Volatile var speed = 0f
    @Volatile var pixels = 0
    val bounds = FloatArray(6)
}

/**
 * Owns the ARCore session. The GL thread calls [update] every frame; depth frames are fused
 * into [map] on a single background thread so rendering never waits on fusion.
 */
class ScanEngine(private val activity: Activity, val map: PointMap) {
    private var session: Session? = null
    private var installRequested = false
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
    @Volatile var integMs = 0f
    @Volatile var depthFps = 0f
    @Volatile var bgStride = 1
    @Volatile var uncertainCount = 0
    @Volatile var framesFused = 0
    @Volatile var depthSize = ""

    val guidance = Guidance()
    val subject = SubjectState()
    private val anchor = FloatArray(3)
    private var lostCount = 0
    private var lastSubjNs = 0L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "fusion").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    private val job = FrameJob()
    private var frameCounter = 0
    private var lastFuseNs = 0L

    /** Set to receive one JPEG of the next camera image (called on the GL thread). */
    @Volatile var jpegRequest: ((ByteArray?) -> Unit)? = null

    // ---------------------------------------------------------------- lifecycle (UI thread)

    /** Returns null when running, or a message explaining why AR could not start. */
    fun resume(): String? {
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
                cameraId = try { s.cameraConfig.cameraId } catch (e: Throwable) { "?" }
                session = s
                textureBound = false
                geomDirty = true
                uvReady = false
            }
            session?.resume()
            return if (depthSupported) null else "Questo dispositivo non supporta la Depth API di ARCore."
        } catch (e: Throwable) {
            session = null
            return "AR non disponibile: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    fun pause() {
        scanning = false
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
        guidance.active = false; guidance.resolved = 0
        subject.valid = false; subject.locked = false
        framesFused = 0
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

            if (scanning && newDepth && busy.compareAndSet(false, true)) {
                try {
                    fillJob(frame)
                    worker.execute {
                        try { fuse(job) } catch (_: Throwable) {} finally { busy.set(false) }
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
        anchor[0] = m[0] * x + m[4] * y - m[8] * z + m[12]
        anchor[1] = m[1] * x + m[5] * y - m[9] * z + m[13]
        anchor[2] = m[2] * x + m[6] * y - m[10] * z + m[14]
        lostCount = 0
        subject.locked = true
        return true
    }

    fun unlockSubject() { subject.locked = false }

    // ---------------------------------------------------------------- fusion (worker thread)

    private var camPts = FloatArray(0)
    private var wldPts = FloatArray(0)
    private var valid = ByteArray(0)
    private var mask = ByteArray(0)
    private var queue = IntArray(0)
    private var motionVotes = 0
    private val skipCells = HashMap<Long, Long>()
    private var guideKey = 0L
    private var guideSinceNs = 0L

    private fun fuse(j: FrameJob) {
        val t0 = System.nanoTime()
        val w = j.w; val h = j.h; val n = w * h
        if (camPts.size != n * 3) {
            camPts = FloatArray(n * 3); wldPts = FloatArray(n * 3); valid = ByteArray(n); mask = ByteArray(n); queue = IntArray(n)
        }
        val m = j.pose
        val camX = m[12]; val camY = m[13]; val camZ = m[14]
        val maxR = Opt.maxRange; val minC = Opt.minConf
        val ifx = 1f / j.fx; val ify = 1f / j.fy

        // 1. Unproject every usable depth pixel to camera and world space.
        for (v in 0 until h) for (u in 0 until w) {
            val i = v * w + u
            val mm = j.depth[i].toInt() and 0xFFFF
            val c = (j.conf[i].toInt() and 0xFF) * (1f / 255f)
            val z = mm * 0.001f
            if (mm == 0 || z < 0.2f || z > maxR || c < minC) { valid[i] = 0; continue }
            valid[i] = 1
            val x = (u - j.cx) * ifx * z; val y = (j.cy - v) * ify * z
            val o = i * 3
            camPts[o] = x; camPts[o + 1] = y; camPts[o + 2] = -z
            wldPts[o] = m[0] * x + m[4] * y - m[8] * z + camX
            wldPts[o + 1] = m[1] * x + m[5] * y - m[9] * z + camY
            wldPts[o + 2] = m[2] * x + m[6] * y - m[10] * z + camZ
        }

        trackSubject(j, w, h)

        val stride = bgStride
        var subjRays = 0; var subjHits = 0
        map.lock.lock()
        try {
            // 2. Fuse points with a normal from the local depth gradient; skip depth edges.
            for (v in 1 until h - 1) for (u in 1 until w - 1) {
                val i = v * w + u
                if (valid[i] == 0.toByte()) continue
                val subj = mask[i] != 0.toByte()
                if (!subj && stride > 1 && ((u + v) % stride) != 0) continue
                val ir = if (valid[i + 1] != 0.toByte()) i + 1 else i - 1
                val id = if (valid[i + w] != 0.toByte()) i + w else i - w
                if (valid[ir] == 0.toByte() || valid[id] == 0.toByte()) continue
                val o = i * 3; val r = ir * 3; val d = id * 3
                val z = -camPts[o + 2]
                val tol = 0.04f * z + 0.01f
                if (abs(camPts[r + 2] - camPts[o + 2]) > tol || abs(camPts[d + 2] - camPts[o + 2]) > tol) continue
                var ax = camPts[d] - camPts[o]; var ay = camPts[d + 1] - camPts[o + 1]; var az = camPts[d + 2] - camPts[o + 2]
                var bx = camPts[r] - camPts[o]; var by = camPts[r + 1] - camPts[o + 1]; var bz = camPts[r + 2] - camPts[o + 2]
                if (id < i) { ax = -ax; ay = -ay; az = -az }
                if (ir < i) { bx = -bx; by = -by; bz = -bz }
                var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                if (nl < 1e-12f) continue
                nx /= nl; ny /= nl; nz /= nl
                val px = camPts[o]; val py = camPts[o + 1]; val pz = camPts[o + 2]
                val pl = sqrt(px * px + py * py + pz * pz)
                var cosv = -(nx * px + ny * py + nz * pz) / pl
                if (cosv < 0f) { nx = -nx; ny = -ny; nz = -nz; cosv = -cosv }
                if (cosv < 0.15f) continue
                val c = (j.conf[i].toInt() and 0xFF) * (1f / 255f)
                var wgt = c * (0.3f + 0.7f * cosv)
                if (subj) wgt = min(1f, wgt * 1.5f)
                val wx = m[0] * nx + m[4] * ny + m[8] * nz
                val wy = m[1] * nx + m[5] * ny + m[9] * nz
                val wz = m[2] * nx + m[6] * ny + m[10] * nz
                val color = if (j.hasColor) sampleColor(j, (u + 0.5f) / w, (v + 0.5f) / h) else -1
                map.insert(wldPts[o], wldPts[o + 1], wldPts[o + 2], wx, wy, wz, color, wgt, subj, j.frame)
            }

            // 3. Free-space carving: a point sitting in front of what we now see is stale
            //    (the subject moved, or it was noise). Lower its confidence until it goes.
            if (Opt.adaptMotion) {
                val step = map.voxel * 1.5f
                var v = 1
                while (v < h - 1) {
                    var u = 1
                    while (u < w - 1) {
                        val i = v * w + u
                        if (valid[i] != 0.toByte() && (j.conf[i].toInt() and 0xFF) > 128) {
                            val o = i * 3
                            val dx = wldPts[o] - camX; val dy = wldPts[o + 1] - camY; val dz = wldPts[o + 2] - camZ
                            val len = sqrt(dx * dx + dy * dy + dz * dz)
                            val ux = dx / len; val uy = dy / len; val uz = dz / len
                            val end = len - max(0.06f, 0.05f * len)
                            val subj = mask[i] != 0.toByte()
                            if (subj) subjRays++
                            var t = 0.25f
                            while (t < end) {
                                val k = map.keyOf(camX + ux * t, camY + uy * t, camZ + uz * t)
                                val p = map.indexOf(k)
                                if (p >= 0 && map.seen[p] != j.frame) {
                                    val q = p * 3
                                    val facing = abs(map.nrm[q] * ux + map.nrm[q + 1] * uy + map.nrm[q + 2] * uz)
                                    if (facing > 0.4f) {
                                        if (map.hasFlag(p, PointMap.SUBJECT)) subjHits++
                                        map.conf[p] -= 0.3f
                                        if (map.conf[p] <= 0f) map.removeAt(p) else map.touch()
                                    }
                                }
                                t += step
                            }
                        }
                        u += 3
                    }
                    v += 3
                }
                // Many stale subject points at once means the subject itself moved: drop its old shell.
                val movedNow = subjRays >= 20 && subjHits > subjRays * 0.25f
                motionVotes = if (movedNow) min(4, motionVotes + 2) else max(0, motionVotes - 1)
                if (movedNow && subject.locked && subject.valid) {
                    val f = j.frame
                    val b = subject.bounds
                    val pad = 0.25f
                    map.removeIf {
                        val q = it * 3
                        map.hasFlag(it, PointMap.SUBJECT) && map.seen[it] < f - 1 &&
                            map.pos[q] > b[0] - pad && map.pos[q] < b[3] + pad &&
                            map.pos[q + 1] > b[1] - pad && map.pos[q + 1] < b[4] + pad &&
                            map.pos[q + 2] > b[2] - pad && map.pos[q + 2] < b[5] + pad
                    }
                }
            } else motionVotes = 0
            subject.moving = motionVotes >= 2 || (subject.locked && subject.speed > 0.12f)

            if (j.frame % 8 == 0) analyseUncertainty(camX, camY, camZ)
        } finally {
            map.lock.unlock()
        }

        // 4. Bookkeeping and self-tuning: keep fusion under budget by thinning the background.
        val ms = (System.nanoTime() - t0) / 1e6f
        integMs = if (integMs == 0f) ms else integMs * 0.9f + ms * 0.1f
        if (integMs > 55f && bgStride < 3) bgStride++
        else if (integMs < 18f && bgStride > 1) bgStride--
        if (lastFuseNs != 0L) {
            val fps = 1e9f / (j.timeNs - lastFuseNs)
            depthFps = if (depthFps == 0f) fps else depthFps * 0.9f + fps * 0.1f
        }
        lastFuseNs = j.timeNs
        framesFused++
    }

    private fun sampleColor(j: FrameJob, u: Float, v: Float): Int {
        val x = (j.toImg[0] + j.toImg[1] * u + j.toImg[2] * v).toInt()
        val y = (j.toImg[3] + j.toImg[4] * u + j.toImg[5] * v).toInt()
        if (x < 0 || y < 0 || x >= j.iw || y >= j.ih) return -1
        val yi = y * j.yRow + x
        val ci = (y shr 1) * j.uvRow + (x shr 1) * j.uvPix
        if (yi >= j.yBuf.size || ci >= j.uBuf.size || ci >= j.vBuf.size) return -1
        val yy = (j.yBuf[yi].toInt() and 0xFF).toFloat()
        val cb = (j.uBuf[ci].toInt() and 0xFF) - 128f
        val cr = (j.vBuf[ci].toInt() and 0xFF) - 128f
        val r = (yy + 1.402f * cr).toInt().coerceIn(0, 255)
        val g = (yy - 0.344f * cb - 0.714f * cr).toInt().coerceIn(0, 255)
        val b = (yy + 1.772f * cb).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Segments the subject by growing a region of continuous depth from a seed pixel:
     * the tapped anchor when locked, otherwise the image centre. The anchor follows the
     * region's centroid, so a locked subject is re-acquired every frame even when it moves.
     */
    private fun trackSubject(j: FrameJob, w: Int, h: Int) {
        java.util.Arrays.fill(mask, 0)
        val m = j.pose
        var su = w / 2; var sv = h / 2
        val locked = subject.locked
        if (locked) {
            // world -> camera (pose is rigid: inverse = transpose rotation)
            val dx = anchor[0] - m[12]; val dy = anchor[1] - m[13]; val dz = anchor[2] - m[14]
            val x = m[0] * dx + m[1] * dy + m[2] * dz
            val y = m[4] * dx + m[5] * dy + m[6] * dz
            val z = -(m[8] * dx + m[9] * dy + m[10] * dz)
            if (z > 0.1f) {
                su = (x / z * j.fx + j.cx).toInt(); sv = (j.cy - y / z * j.fy).toInt()
            } else { su = -1 }
        }
        var seed = -1
        if (su in 0 until w && sv in 0 until h) {
            var best = Float.MAX_VALUE
            for (dv in -4..4) for (du in -4..4) {
                val x = su + du; val y = sv + dv
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val i = y * w + x
                if (valid[i] == 0.toByte()) continue
                var d = (du * du + dv * dv).toFloat()
                if (locked) {
                    val o = i * 3
                    val ex = wldPts[o] - anchor[0]; val ey = wldPts[o + 1] - anchor[1]; val ez = wldPts[o + 2] - anchor[2]
                    val e = ex * ex + ey * ey + ez * ez
                    if (e > 0.35f * 0.35f) continue
                    d += e * 400f
                }
                if (d < best) { best = d; seed = i }
            }
        }
        if (seed < 0) {
            if (locked && ++lostCount > 20) subject.locked = false
            subject.valid = false
            return
        }
        lostCount = 0
        val s3 = seed * 3
        val sx = camPts[s3]; val sy = camPts[s3 + 1]; val sz = camPts[s3 + 2]
        val rad2 = Opt.subjRadius * Opt.subjRadius
        var qh = 0; var qt = 0
        queue[qt++] = seed; mask[seed] = 1
        var cxw = 0f; var cyw = 0f; var czw = 0f; var cnt = 0
        val b = subject.bounds
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        while (qh < qt) {
            val i = queue[qh++]
            val o = i * 3
            val wx = wldPts[o]; val wy = wldPts[o + 1]; val wz = wldPts[o + 2]
            cxw += wx; cyw += wy; czw += wz; cnt++
            if (wx < x0) x0 = wx; if (wy < y0) y0 = wy; if (wz < z0) z0 = wz
            if (wx > x1) x1 = wx; if (wy > y1) y1 = wy; if (wz > z1) z1 = wz
            val zc = -camPts[o + 2]
            val tol = 0.03f * zc + 0.01f
            val u = i % w; val v = i / w
            for (k in 0 until 4) {
                val nu = u + NB_U[k]; val nv = v + NB_V[k]
                if (nu < 0 || nv < 0 || nu >= w || nv >= h) continue
                val ni = nv * w + nu
                if (mask[ni] != 0.toByte() || valid[ni] == 0.toByte()) continue
                val no = ni * 3
                if (abs(camPts[no + 2] - camPts[o + 2]) > tol) continue
                val ex = camPts[no] - sx; val ey = camPts[no + 1] - sy; val ez = camPts[no + 2] - sz
                if (ex * ex + ey * ey + ez * ez > rad2) continue
                mask[ni] = 1; queue[qt++] = ni
            }
        }
        val inv = 1f / cnt
        cxw *= inv; cyw *= inv; czw *= inv
        val now = j.timeNs
        if (subject.valid && lastSubjNs != 0L && locked) {
            val dt = (now - lastSubjNs) / 1e9f
            val dx = cxw - subject.x; val dy = cyw - subject.y; val dz = czw - subject.z
            val sp = sqrt(dx * dx + dy * dy + dz * dz) / max(dt, 1e-3f)
            subject.speed = subject.speed * 0.7f + min(sp, 3f) * 0.3f
        } else subject.speed = 0f
        lastSubjNs = now
        if (locked) {
            anchor[0] += (cxw - anchor[0]) * 0.6f; anchor[1] += (cyw - anchor[1]) * 0.6f; anchor[2] += (czw - anchor[2]) * 0.6f
        }
        subject.x = cxw; subject.y = cyw; subject.z = czw
        b[0] = x0; b[1] = y0; b[2] = z0; b[3] = x1; b[4] = y1; b[5] = z1
        val dx = cxw - m[12]; val dy = cyw - m[13]; val dz = czw - m[14]
        subject.distance = sqrt(dx * dx + dy * dy + dz * dz)
        subject.pixels = cnt
        subject.valid = true
    }

    // Uncertainty analysis scratch
    private val cellIdx = LongIntMap(4096)
    private var cellKey = LongArray(4096)
    private var cellU = IntArray(4096)
    private var cellAll = IntArray(4096)
    private var cellSum = FloatArray(4096 * 3)

    /**
     * Flags weakly observed points and picks the densest nearby patch of them as the place
     * the user should scan again. Caller holds the map lock.
     */
    private fun analyseUncertainty(camX: Float, camY: Float, camZ: Float) {
        cellIdx.clear()
        var nc = 0
        var total = 0
        val inv = 1f / GUIDE_CELL
        val n = map.size
        val unc = PointMap.UNCERTAIN.toInt()
        for (i in 0 until n) {
            val o = i * 3
            val x = map.pos[o]; val y = map.pos[o + 1]; val z = map.pos[o + 2]
            val isU = map.conf[i] < UNCERTAIN_BELOW
            val f = map.flags[i].toInt()
            map.flags[i] = (if (isU) f or unc else f and unc.inv()).toByte()
            if (isU) total++
            val k = LongIntMap.pack(floor(x * inv).toInt(), floor(y * inv).toInt(), floor(z * inv).toInt())
            var c = cellIdx.get(k)
            if (c < 0) {
                if (nc == cellKey.size) {
                    val s = nc * 2
                    cellKey = cellKey.copyOf(s); cellU = cellU.copyOf(s); cellAll = cellAll.copyOf(s); cellSum = cellSum.copyOf(s * 3)
                }
                c = nc++
                cellIdx.put(k, c); cellKey[c] = k; cellU[c] = 0; cellAll[c] = 0
                cellSum[c * 3] = 0f; cellSum[c * 3 + 1] = 0f; cellSum[c * 3 + 2] = 0f
            }
            cellAll[c]++
            if (isU) { cellU[c]++; cellSum[c * 3] += x; cellSum[c * 3 + 1] += y; cellSum[c * 3 + 2] += z }
        }
        map.touch()
        uncertainCount = total

        val now = System.nanoTime()
        val g = guidance
        if (g.active) {
            val c = cellIdx.get(guideKey)
            val still = c >= 0 && cellU[c] >= 10 && cellU[c] > cellAll[c] * 0.2f
            if (!still) { g.active = false; g.resolved++ }
            else if (now - guideSinceNs > 20_000_000_000L) { g.active = false; skipCells[guideKey] = now + 30_000_000_000L }
            else { g.count = cellU[c]; val iu = 1f / cellU[c]; g.x = cellSum[c * 3] * iu; g.y = cellSum[c * 3 + 1] * iu; g.z = cellSum[c * 3 + 2] * iu }
        }
        if (!g.active) {
            var best = -1; var bestScore = 0f
            for (c in 0 until nc) {
                val u = cellU[c]
                if (u < 30 || u < cellAll[c] * 0.45f) continue
                val exp = skipCells[cellKey[c]]
                if (exp != null) { if (exp > now) continue else skipCells.remove(cellKey[c]) }
                val iu = 1f / u
                val dx = cellSum[c * 3] * iu - camX; val dy = cellSum[c * 3 + 1] * iu - camY; val dz = cellSum[c * 3 + 2] * iu - camZ
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d > 4f) continue
                val score = u / (0.5f + d)
                if (score > bestScore) { bestScore = score; best = c }
            }
            if (best >= 0) {
                val iu = 1f / cellU[best]
                g.x = cellSum[best * 3] * iu; g.y = cellSum[best * 3 + 1] * iu; g.z = cellSum[best * 3 + 2] * iu
                g.count = cellU[best]
                guideKey = cellKey[best]; guideSinceNs = now
                g.active = true
            }
        }
    }

    companion object {
        const val GUIDE_CELL = 0.3f
        const val UNCERTAIN_BELOW = 0.42f
        private val NB_U = intArrayOf(1, -1, 0, 0)
        private val NB_V = intArrayOf(0, 0, 1, -1)
    }
}
