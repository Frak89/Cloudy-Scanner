package com.frak89.cloudyscanner.scan

import com.frak89.cloudyscanner.Opt
import com.frak89.cloudyscanner.core.LongIntMap
import com.frak89.cloudyscanner.core.PointMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One depth frame handed from the GL thread to the fusion thread. */
class FrameJob {
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
    /** False when only the subject should be tracked, without adding points. */
    var insert = true
    /** Intrinsics of the camera image (fx, fy, cx, cy), for reusing saved photos. */
    val imgIntr = FloatArray(4)
    var exposureMs = 0f
    /** Display rotation (Surface.ROTATION_*) when the frame was taken. */
    var rot = 0
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
    /** Subject rectangle in camera-image pixels (x0, y0, x1, y1). */
    val imgRect = IntArray(4)
    /** Share of the frame covered by the subject. */
    @Volatile var fill = 0f
    /** True when the subject does not touch the frame border. */
    @Volatile var inside = false
    /** Largest extent seen so far, in metres (slowly forgets). */
    @Volatile var extent = 0f
    /** Height of the desk / floor the subject stands on (cut away from it), NaN when none. */
    @Volatile var supportY = Float.NaN
}

/**
 * Turns depth frames into the point map: unprojection, normals, subject tracking, free-space
 * carving and the search for weakly observed areas. No Android or ARCore types, so it can be
 * exercised with synthetic frames in unit tests.
 */
class Fusion(private val map: PointMap) {
    @Volatile var integMs = 0f
    @Volatile var depthFps = 0f
    @Volatile var bgStride = 1
    @Volatile var uncertainCount = 0
    @Volatile var framesFused = 0

    val guidance = Guidance()
    val subject = SubjectState()
    private val anchor = FloatArray(3)
    private var lostCount = 0
    private var lastSubjNs = 0L
    private var lastFuseNs = 0L

    /** Called on the fusion thread after every frame (photo capture hooks in here). */
    @Volatile var frameHook: ((FrameJob) -> Unit)? = null

    fun lockAt(x: Float, y: Float, z: Float) {
        anchor[0] = x; anchor[1] = y; anchor[2] = z
        lostCount = 0
        subject.extent = 0f
        subject.locked = true
    }

    fun reset() {
        guidance.active = false; guidance.resolved = 0
        subject.valid = false; subject.locked = false
        framesFused = 0
    }

    private var camPts = FloatArray(0)
    private var wldPts = FloatArray(0)
    private var valid = ByteArray(0)
    private var mask = ByteArray(0)
    private var queue = IntArray(0)
    private var motionVotes = 0
    @Volatile var lastMotionVotes = 0
    @Volatile var lastSubjHits = 0
    @Volatile var lastSubjRays = 0
    private val skipCells = HashMap<Long, Long>()
    private var guideKey = 0L
    private var guideSinceNs = 0L

    fun fuse(j: FrameJob) {
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
        frameHook?.invoke(j)
        if (!j.insert) return
        // Focus mode: everything that is not the selected object is left out of the scan.
        val focus = Opt.focusSubject && subject.locked && subject.valid
        // The selected object is kept at 5 mm instead of 1.5 cm, so small things (a mouse) have a shape.
        val fine = Opt.fineObject && subject.locked && subject.valid && subject.extent < 0.6f

        val stride = bgStride
        val invVoxel = 1f / map.voxel
        var subjRays = 0; var subjHits = 0
        map.lock.lock()
        try {
            // 2. Fuse points with a normal from the local depth gradient; skip depth edges.
            for (v in 1 until h - 1) for (u in 1 until w - 1) {
                val i = v * w + u
                if (valid[i] == 0.toByte()) continue
                val subj = mask[i] != 0.toByte()
                if (!subj && (focus || (stride > 1 && ((u + v) % stride) != 0))) continue
                val o = i * 3
                val z = -camPts[o + 2]
                val tol = 0.04f * z + 0.01f
                // Normal from the depth gradient. Prefer a wide central difference (±2 px): it halves
                // the effect of depth noise. Fall back to the nearest neighbours next to depth edges.
                var ax: Float; var ay: Float; var az: Float; var bx: Float; var by: Float; var bz: Float
                val wide = u >= 2 && u < w - 2 && v >= 2 && v < h - 2 &&
                    valid[i - 2] != 0.toByte() && valid[i + 2] != 0.toByte() &&
                    valid[i - 2 * w] != 0.toByte() && valid[i + 2 * w] != 0.toByte() &&
                    abs(camPts[(i - 2) * 3 + 2] - camPts[o + 2]) < 2f * tol && abs(camPts[(i + 2) * 3 + 2] - camPts[o + 2]) < 2f * tol &&
                    abs(camPts[(i - 2 * w) * 3 + 2] - camPts[o + 2]) < 2f * tol && abs(camPts[(i + 2 * w) * 3 + 2] - camPts[o + 2]) < 2f * tol
                if (wide) {
                    val d0 = (i - 2 * w) * 3; val d1 = (i + 2 * w) * 3; val r0 = (i - 2) * 3; val r1 = (i + 2) * 3
                    ax = camPts[d1] - camPts[d0]; ay = camPts[d1 + 1] - camPts[d0 + 1]; az = camPts[d1 + 2] - camPts[d0 + 2]
                    bx = camPts[r1] - camPts[r0]; by = camPts[r1 + 1] - camPts[r0 + 1]; bz = camPts[r1 + 2] - camPts[r0 + 2]
                } else {
                    val ir = if (valid[i + 1] != 0.toByte()) i + 1 else i - 1
                    val id = if (valid[i + w] != 0.toByte()) i + w else i - w
                    if (valid[ir] == 0.toByte() || valid[id] == 0.toByte()) continue
                    val r = ir * 3; val d = id * 3
                    if (abs(camPts[r + 2] - camPts[o + 2]) > tol || abs(camPts[d + 2] - camPts[o + 2]) > tol) continue
                    ax = camPts[d] - camPts[o]; ay = camPts[d + 1] - camPts[o + 1]; az = camPts[d + 2] - camPts[o + 2]
                    bx = camPts[r] - camPts[o]; by = camPts[r + 1] - camPts[o + 1]; bz = camPts[r + 2] - camPts[o + 2]
                    if (id < i) { ax = -ax; ay = -ay; az = -az }
                    if (ir < i) { bx = -bx; by = -by; bz = -bz }
                }
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
                // Pixel footprint relative to the voxel: beyond ~2 m a voxel is only hit now and then.
                val fp = z * ifx * invVoxel
                val boost = if (fp <= 1f) 1f else min(6f, fp * fp)
                map.insert(wldPts[o], wldPts[o + 1], wldPts[o + 2], wx, wy, wz, color, wgt, subj, j.frame, boost, subj && fine)
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
                lastSubjHits = subjHits; lastSubjRays = subjRays
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
            lastMotionVotes = motionVotes
            subject.moving = motionVotes >= 2 || (subject.locked && subject.speed > 0.35f)

            if (j.frame % 8 == 0) analyseUncertainty(camX, camY, camZ, j.frame, j.timeNs)
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
        var cxw = 0f; var cyw = 0f; var czw = 0f; var cnt = 0
        val b = subject.bounds
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var pu0 = w; var pv0 = h; var pu1 = 0; var pv1 = 0
        // Region of continuous depth from the seed, optionally only above a height (the desk it stands on).
        fun grow(minY: Float) {
            java.util.Arrays.fill(mask, 0)
            cxw = 0f; cyw = 0f; czw = 0f; cnt = 0
            x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE; z0 = Float.MAX_VALUE; x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE; z1 = -Float.MAX_VALUE
            pu0 = w; pv0 = h; pu1 = 0; pv1 = 0
            var qh = 0; var qt = 0
            queue[qt++] = seed; mask[seed] = 1
            while (qh < qt) {
                val i = queue[qh++]
                val o = i * 3
                val pu = i % w; val pv = i / w
                if (pu < pu0) pu0 = pu; if (pu > pu1) pu1 = pu; if (pv < pv0) pv0 = pv; if (pv > pv1) pv1 = pv
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
                    if (wldPts[no + 1] < minY) continue
                    val ex = camPts[no] - sx; val ey = camPts[no + 1] - sy; val ez = camPts[no + 2] - sz
                    if (ex * ex + ey * ey + ez * ez > rad2) continue
                    mask[ni] = 1; queue[qt++] = ni
                }
            }
        }
        grow(-Float.MAX_VALUE)
        // An object on a desk is joined to the desk by continuous depth: find the desk (the most
        // common height clearly below the seed) and grow again above it, so the object is just the object.
        subject.supportY = Float.NaN
        if (Opt.cutSupport && cnt > 60) {
            val seedY = wldPts[s3 + 1]
            val bins = IntArray(200)
            var below = 0
            for (i in 0 until w * h) {
                if (mask[i] == 0.toByte()) continue
                val dy = seedY - wldPts[i * 3 + 1]
                if (dy < 0.012f || dy >= 2f) continue
                bins[(dy / 0.01f).toInt()]++; below++
            }
            var mb = -1
            for (k in bins.indices) if (mb < 0 || bins[k] > bins[mb]) mb = k
            if (mb >= 0 && bins[mb] >= 40 && bins[mb] >= 0.15f * cnt) {
                // refine the desk height from the pixels in that bin and its neighbours
                var sum = 0.0; var n = 0
                for (i in 0 until w * h) {
                    if (mask[i] == 0.toByte()) continue
                    val dy = seedY - wldPts[i * 3 + 1]
                    if (dy >= (mb - 1) * 0.01f && dy < (mb + 2) * 0.01f) { sum += wldPts[i * 3 + 1]; n++ }
                }
                val desk = (sum / n).toFloat()
                val zc = -camPts[s3 + 2]
                val margin = 0.006f + 0.012f * zc
                if (seedY > desk + margin) {
                    grow(desk + margin)
                    subject.supportY = desk
                }
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
        subject.fill = ((pu1 - pu0 + 1) * (pv1 - pv0 + 1)).toFloat() / (w * h)
        subject.inside = pu0 > 2 && pv0 > 2 && pu1 < w - 3 && pv1 < h - 3
        if (j.hasColor) {
            // Depth pixels -> camera-image pixels (the mapping is affine; take the bounding box).
            var ix0 = Int.MAX_VALUE; var iy0 = Int.MAX_VALUE; var ix1 = 0; var iy1 = 0
            for (k in 0 until 4) {
                val uu = (if (k and 1 == 0) pu0 else pu1 + 1).toFloat() / w
                val vv = (if (k and 2 == 0) pv0 else pv1 + 1).toFloat() / h
                val ix = (j.toImg[0] + j.toImg[1] * uu + j.toImg[2] * vv).toInt()
                val iy = (j.toImg[3] + j.toImg[4] * uu + j.toImg[5] * vv).toInt()
                if (ix < ix0) ix0 = ix; if (ix > ix1) ix1 = ix; if (iy < iy0) iy0 = iy; if (iy > iy1) iy1 = iy
            }
            subject.imgRect[0] = ix0.coerceIn(0, j.iw); subject.imgRect[1] = iy0.coerceIn(0, j.ih)
            subject.imgRect[2] = ix1.coerceIn(0, j.iw); subject.imgRect[3] = iy1.coerceIn(0, j.ih)
        }
        // with the desk cut away the bottom of the object is the desk itself
        if (!subject.supportY.isNaN()) { y0 = subject.supportY; b[1] = y0 }
        val ext = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0))
        // a sudden change of segmentation (desk cut or not) must not keep an old, larger size forever
        subject.extent = if (ext < 0.5f * subject.extent) ext else max(ext, subject.extent * 0.995f)
        subject.valid = true
    }

    // Uncertainty analysis scratch
    private val cellIdx = LongIntMap(4096)
    private var cellKey = LongArray(4096)
    private var cellU = IntArray(4096)
    private var cellAll = IntArray(4096)
    private var cellSum = FloatArray(4096 * 3)
    private var pointCell = IntArray(0)

    private fun cellScore(c: Int, camX: Float, camY: Float, camZ: Float, reach: Float): Float {
        val u = cellU[c]
        if (u < 30 || u < cellAll[c] * 0.45f) return 0f
        val iu = 1f / u
        val dx = cellSum[c * 3] * iu - camX; val dy = cellSum[c * 3 + 1] * iu - camY; val dz = cellSum[c * 3 + 2] * iu - camZ
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // Only places close enough to rescan properly; the far background is always sparse.
        return if (d > reach) 0f else u / (0.5f + d)
    }

    /**
     * Finds weakly observed patches and picks the best one as the place the user should scan
     * again. A point counts when it has low confidence, is no longer being observed (what is in
     * view right now is still settling) and is near enough to be worth a second pass. Only
     * points in a patch that is mostly weak are highlighted: lone weak points are just noise.
     * Caller holds the map lock.
     */
    private fun analyseUncertainty(camX: Float, camY: Float, camZ: Float, frame: Int, now: Long) {
        cellIdx.clear()
        var nc = 0
        val inv = 1f / GUIDE_CELL
        val n = map.size
        val reach = min(2.5f, Opt.maxRange * 0.6f)
        val weakReach2 = (reach * 1.25f) * (reach * 1.25f)
        if (pointCell.size < n) pointCell = IntArray(n + n / 2)
        for (i in 0 until n) {
            val o = i * 3
            val x = map.pos[o]; val y = map.pos[o + 1]; val z = map.pos[o + 2]
            val weak = map.conf[i] < UNCERTAIN_BELOW && map.seen[i] < frame - 12 &&
                (x - camX) * (x - camX) + (y - camY) * (y - camY) + (z - camZ) * (z - camZ) < weakReach2
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
            if (weak) { cellU[c]++; cellSum[c * 3] += x; cellSum[c * 3 + 1] += y; cellSum[c * 3 + 2] += z }
            pointCell[i] = if (weak) c else -1
        }
        // Highlight weak points only where they dominate their patch.
        val unc = PointMap.UNCERTAIN.toInt()
        var total = 0
        for (i in 0 until n) {
            val c = pointCell[i]
            val on = c >= 0 && cellU[c] >= 20 && cellU[c] >= cellAll[c] * 0.35f
            val f = map.flags[i].toInt()
            map.flags[i] = (if (on) f or unc else f and unc.inv()).toByte()
            if (on) total++
        }
        map.touch()
        uncertainCount = total

        val g = guidance
        var curScore = 0f
        if (g.active) {
            val c = cellIdx.get(guideKey)
            when {
                c < 0 -> g.active = false
                cellU[c] < 10 || cellU[c] <= cellAll[c] * 0.2f -> { g.active = false; g.resolved++ }
                // Ignored for a while: stop nagging about this spot.
                now - guideSinceNs > 20_000_000_000L -> { g.active = false; skipCells[guideKey] = now + 30_000_000_000L }
                else -> {
                    g.count = cellU[c]; val iu = 1f / cellU[c]
                    g.x = cellSum[c * 3] * iu; g.y = cellSum[c * 3 + 1] * iu; g.z = cellSum[c * 3 + 2] * iu
                    curScore = cellScore(c, camX, camY, camZ, reach)
                    // Out of reach now (the user walked on): let a nearer spot take over at once.
                    if (curScore == 0f) g.active = false
                }
            }
        }
        var best = -1; var bestScore = 0f
        for (c in 0 until nc) {
            val sc = cellScore(c, camX, camY, camZ, reach)
            if (sc <= bestScore) continue
            val exp = skipCells[cellKey[c]]
            if (exp != null) { if (exp > now) continue else skipCells.remove(cellKey[c]) }
            bestScore = sc; best = c
        }
        // Keep the current target unless a clearly better one shows up (avoids flip-flopping).
        if (best >= 0 && (!g.active || (cellKey[best] != guideKey && bestScore > 2f * curScore))) {
            val iu = 1f / cellU[best]
            g.x = cellSum[best * 3] * iu; g.y = cellSum[best * 3 + 1] * iu; g.z = cellSum[best * 3 + 2] * iu
            g.count = cellU[best]
            guideKey = cellKey[best]; guideSinceNs = now
            g.active = true
        }
    }

    companion object {
        const val GUIDE_CELL = 0.3f
        const val UNCERTAIN_BELOW = 0.42f
        private val NB_U = intArrayOf(1, -1, 0, 0)
        private val NB_V = intArrayOf(0, 0, 1, -1)
    }
}
