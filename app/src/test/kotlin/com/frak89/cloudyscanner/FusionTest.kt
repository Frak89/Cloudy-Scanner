package com.frak89.cloudyscanner

import org.junit.Test
import com.frak89.cloudyscanner.core.*
import com.frak89.cloudyscanner.scan.*
import kotlin.math.*

// Synthetic scene: floor y=-1, sphere radius R at centre C (movable). Camera orbits looking at the sphere.
private val W = 160; private val H = 90
private val FX = 120f; private val FY = 120f; private val CX = 80f; private val CY = 45f

private fun lookAtPose(eye: FloatArray, tgt: FloatArray): FloatArray {
    // camera looks along -Z, Y up. Column-major pose (camera -> world).
    var fx = tgt[0] - eye[0]; var fy = tgt[1] - eye[1]; var fz = tgt[2] - eye[2]
    val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
    var rx = fy * 0f - fz * 1f; var ry = fz * 0f - fx * 0f; var rz = fx * 1f - fy * 0f  // f x up(0,1,0)
    val rl = sqrt(rx * rx + ry * ry + rz * rz); rx /= rl; ry /= rl; rz /= rl
    val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
    return floatArrayOf(rx, ry, rz, 0f, ux, uy, uz, 0f, -fx, -fy, -fz, 0f, eye[0], eye[1], eye[2], 1f)
}

private fun render(job: FrameJob, pose: FloatArray, c: FloatArray, R: Float, frame: Int, rnd: java.util.Random, withSphere: Boolean = true) {
    job.w = W; job.h = H
    if (job.depth.size != W * H) { job.depth = ShortArray(W * H); job.conf = ByteArray(W * H) }
    job.fx = FX; job.fy = FY; job.cx = CX; job.cy = CY
    System.arraycopy(pose, 0, job.pose, 0, 16)
    job.frame = frame; job.timeNs = frame * 100_000_000L; job.hasColor = false; job.insert = true
    val ex = pose[12]; val ey = pose[13]; val ez = pose[14]
    for (v in 0 until H) for (u in 0 until W) {
        val x = (u - CX) / FX; val y = (CY - v) / FY
        // ray dir in world for camera-space (x, y, -1)
        val dx = pose[0] * x + pose[4] * y - pose[8]; val dy = pose[1] * x + pose[5] * y - pose[9]; val dz = pose[2] * x + pose[6] * y - pose[10]
        var t = Float.MAX_VALUE
        if (dy < -1e-4f) t = (-1f - ey) / dy
        if (withSphere) {
            val ox = ex - c[0]; val oy = ey - c[1]; val oz = ez - c[2]
            val a = dx * dx + dy * dy + dz * dz; val b = 2 * (ox * dx + oy * dy + oz * dz); val cc = ox * ox + oy * oy + oz * oz - R * R
            val disc = b * b - 4 * a * cc
            if (disc > 0) { val ts = (-b - sqrt(disc)) / (2 * a); if (ts > 0 && ts < t) t = ts }
        }
        // t is along a ray with camera z = -1, so t is exactly the depth
        val depth = if (t == Float.MAX_VALUE) 0f else t + (rnd.nextGaussian() * 0.004 * t).toFloat()
        job.depth[v * W + u] = (depth * 1000f).toInt().coerceIn(0, 65535).toShort()
        job.conf[v * W + u] = (if (depth > 0) 230 else 0).toByte()
    }
}

class FusionTest {
@Test fun smallObjectIsCutFromTheFloorAndScannedFine() {
    val map = PointMap(0.015f)
    val fusion = Fusion(map)
    val job = FrameJob()
    val rnd = java.util.Random(9)
    // a ball of 4 cm radius resting on the floor (y = -1), seen from 35 cm
    val C = floatArrayOf(0f, -0.96f, -0.5f); val R = 0.04f
    Opt.maxRange = 5f; Opt.focusSubject = true; Opt.fineObject = true; Opt.cutSupport = true
    fusion.lockAt(C[0], C[1] + R, C[2])
    for (k in 0 until 60) {
        val a = k * 2 * PI.toFloat() / 60
        val eye = floatArrayOf(C[0] + 0.3f * sin(a), C[1] + 0.2f, C[2] + 0.3f * cos(a))
        render(job, lookAtPose(eye, C), C, R, k + 1, rnd)
        fusion.fuse(job)
    }
    Opt.focusSubject = false
    val s = fusion.subject
    var onBall = 0; var other = 0
    for (i in 0 until map.size) {
        val dx = map.pos[i * 3] - C[0]; val dy = map.pos[i * 3 + 1] - C[1]; val dz = map.pos[i * 3 + 2] - C[2]
        if (abs(sqrt(dx * dx + dy * dy + dz * dz) - R) < 0.012f) onBall++ else other++
    }
    println("  small object: extent=%.3f support=%.3f ball=$onBall other=$other".format(s.extent, s.supportY))
    assert(s.locked) { "still locked" }
    assert(abs(s.supportY + 1f) < 0.02f) { "floor found under it: ${s.supportY}" }
    assert(s.extent < 0.2f) { "the object alone, not the floor: ${s.extent}" }
    assert(onBall > 150) { "fine points on a 4 cm ball: $onBall" }
    assert(other < onBall) { "the rest of the scene is left out: $other" }
}

@Test fun orbitScanTracksSubjectAdaptsToMotionAndGuidesRescans() {
    val map = PointMap(0.015f)
    val fusion = Fusion(map)
    val job = FrameJob()
    val rnd = java.util.Random(5)
    val C = floatArrayOf(0f, -0.6f, -2f); val R = 0.4f
    Opt.maxRange = 5f
    // lock subject on the sphere
    fusion.lockAt(C[0], C[1] + R, C[2])
    var frame = 0
    for (k in 0 until 120) {
        val a = k * 2 * PI.toFloat() / 120
        val eye = floatArrayOf(C[0] + 1.6f * sin(a), 0.2f, C[2] + 1.6f * cos(a))
        render(job, lookAtPose(eye, C), C, R, ++frame, rnd)
        fusion.fuse(job)
        if (k % 30 == 29) println("  orbit k=$k votes=${fusion.lastMotionVotes} hits=${fusion.lastSubjHits}/${fusion.lastSubjRays} speed=%.2f moving=${fusion.subject.moving}".format(fusion.subject.speed))
    }
    run { val tot = IntArray(8); val un = IntArray(8)
      for (i in 0 until map.size) { val d = sqrt((map.pos[i*3]-C[0]).pow(2) + (map.pos[i*3+2]-C[2]).pow(2)); val b = min(7, (d / 0.5f).toInt()); tot[b]++; if (map.conf[i] < 0.42f) un[b]++ }
      println("  weak ratio by radius(0.5m bands): " + (0 until 8).joinToString { "${un[it] * 100 / max(1, tot[it])}%/${tot[it]}" }) }
    println("uncertain after orbit=${fusion.uncertainCount} guidance=${fusion.guidance.active}")
    println("points=${map.size} integMs=%.1f stride=${fusion.bgStride}".format(fusion.integMs))
    // every point must be on the sphere or on the floor, with outward normals
    var onS = 0; var onF = 0; var off = 0; var badN = 0; var subjFlag = 0; var subjOnFloorFar = 0
    for (i in 0 until map.size) {
        val x = map.pos[i * 3]; val y = map.pos[i * 3 + 1]; val z = map.pos[i * 3 + 2]
        var nx = map.nrm[i * 3]; var ny = map.nrm[i * 3 + 1]; var nz = map.nrm[i * 3 + 2]
        val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
        val dx = x - C[0]; val dy = y - C[1]; val dz = z - C[2]; val r = sqrt(dx * dx + dy * dy + dz * dz)
        if (abs(r - R) < 0.03f) { onS++; if ((nx * dx + ny * dy + nz * dz) / r < 0.5f) badN++ }
        else if (abs(y + 1f) < 0.03f) { onF++; if (ny < 0.5f) badN++ }
        else off++
        if (map.hasFlag(i, PointMap.SUBJECT)) { subjFlag++; if (r > R + 0.7f) subjOnFloorFar++ }
    }
    println("onSphere=$onS onFloor=$onF off=$off badNormals=$badN subjectFlag=$subjFlag farSubject=$subjOnFloorFar")
    val s = fusion.subject
    println("subject valid=${s.valid} locked=${s.locked} dist=%.2f center=(%.2f,%.2f,%.2f) moving=${s.moving} px=${s.pixels}".format(s.distance, s.x, s.y, s.z))
    check(off < map.size / 100) { "too many stray points" }
    check(badN < map.size / 100) { "normals wrong" }
    check(onS > 2000 && onF > 5000)
    check(s.valid && s.locked && abs(s.x - C[0]) < 0.25f && abs(s.z - C[2]) < 0.45f)

    // mesh it
    val m = Mesher.build(map.size, map.pos, map.nrm, map.col, 0.04f)!!
    println("mesh V=${m.vertexCount} Q=${m.quadCount} T=${m.triangleCount}")

    // Now the sphere jumps 0.5 m sideways. The old shell must disappear and the subject must follow.
    val C2 = floatArrayOf(C[0] + 0.25f, C[1], C[2])
    var movingSeen = false
    for (k in 0 until 40) {
        val eye = floatArrayOf(C[0] + 0.3f * sin(k * 0.1f), 0.2f, C[2] + 1.6f)
        // move gradually over the first 10 frames
        val f = min(1f, k / 10f)
        val cc = floatArrayOf(C[0] + 0.25f * f, C[1], C[2])
        render(job, lookAtPose(eye, C2), cc, R, ++frame, rnd)
        fusion.fuse(job)
        if (fusion.subject.moving) movingSeen = true
    }
    var stale = 0; var fresh = 0
    for (i in 0 until map.size) {
        val x = map.pos[i * 3]; val y = map.pos[i * 3 + 1]; val z = map.pos[i * 3 + 2]
        val r1 = sqrt((x - C[0]).pow(2) + (y - C[1]).pow(2) + (z - C[2]).pow(2))
        val r2 = sqrt((x - C2[0]).pow(2) + (y - C2[1]).pow(2) + (z - C2[2]).pow(2))
        // old-shell points visible from the front (z > centre) that are not on the new sphere
        if (abs(r1 - R) < 0.02f && abs(r2 - R) > 0.04f && z > C[2] + 0.1f && abs(y + 1f) > 0.05f) stale++
        if (abs(r2 - R) < 0.03f && z > C2[2]) fresh++
    }
    println("after move: staleFront=$stale freshFront=$fresh movingSeen=$movingSeen subject=(%.2f,%.2f,%.2f) locked=${fusion.subject.locked}".format(fusion.subject.x, fusion.subject.y, fusion.subject.z))
    check(fresh > 500); check(stale < fresh / 10) { "old shell not carved" }
    check(movingSeen) { "motion not detected" }
    check(abs(fusion.subject.x - C2[0]) < 0.2f) { "subject did not follow" }

    // Uncertainty: glance briefly at a new area (2 frames), look away, expect guidance to point there.
    val spot = floatArrayOf(0f, -1f, 2.5f)
    for (k in 0 until 2) { render(job, lookAtPose(floatArrayOf(0f, 0.2f, 1.2f), spot), C2, R, ++frame, rnd); fusion.fuse(job) }
    for (k in 0 until 40) { render(job, lookAtPose(floatArrayOf(0.2f, 0.2f, 0.9f), C2), C2, R, ++frame, rnd); fusion.fuse(job) }
    val g = fusion.guidance
    println("guidance active=${g.active} at (%.2f,%.2f,%.2f) count=${g.count} uncertain=${fusion.uncertainCount}".format(g.x, g.y, g.z))
    check(g.active && g.z > 1.0f) { "guidance should point to the briefly seen area" }
    // Go back and dwell: it must resolve.
    var cleared = -1; val before = g.resolved
    for (k in 0 until 80) { render(job, lookAtPose(floatArrayOf(0.3f * sin(k * 0.2f), 0.2f, 1.2f), floatArrayOf(g.x, g.y, g.z)), C2, R, ++frame, rnd); fusion.fuse(job); if (cleared < 0 && g.resolved > before) cleared = k }
    println("after revisit: resolvedAfterFrames=$cleared active=${g.active} resolved=${g.resolved} uncertain=${fusion.uncertainCount}")
    check(cleared >= 0) { "revisiting must clear the target" }
    println("FUSION OK")
}

@Test fun focusModeScansOnlyTheSelectedObject() {
    val map = PointMap(0.015f); val fusion = Fusion(map); val job = FrameJob(); val rnd = java.util.Random(9)
    val C = floatArrayOf(0f, -0.6f, -2f); val R = 0.4f
    val oldFocus = Opt.focusSubject; val oldRadius = Opt.subjRadius
    try {
        Opt.focusSubject = true; Opt.subjRadius = 0.6f; Opt.maxRange = 5f
        fusion.lockAt(C[0], C[1] + R, C[2])
        var frames = 0
        fusion.frameHook = { frames++ }
        for (k in 0 until 40) { val a = k * 2 * PI.toFloat() / 40
            render(job, lookAtPose(floatArrayOf(C[0] + 1.6f * sin(a), 0.2f, C[2] + 1.6f * cos(a)), C), C, R, k + 1, rnd); fusion.fuse(job) }
        check(frames == 40) { "hook must see every frame" }
        check(map.size > 3000) { "the object itself must be scanned, got ${map.size}" }
        var far = 0; var notSubject = 0
        for (i in 0 until map.size) {
            val d = sqrt((map.pos[i * 3] - C[0]).pow(2) + (map.pos[i * 3 + 1] - C[1]).pow(2) + (map.pos[i * 3 + 2] - C[2]).pow(2))
            if (d > R + 0.6f + 0.25f) far++
            if (!map.hasFlag(i, PointMap.SUBJECT)) notSubject++
        }
        println("focus: points=${map.size} far=$far notSubject=$notSubject extent=%.2f fill=%.2f inside=${fusion.subject.inside}".format(fusion.subject.extent, fusion.subject.fill))
        check(far == 0) { "background must be left out in focus mode" }
        check(notSubject < map.size / 50)
        check(fusion.subject.extent > 0.5f && fusion.subject.fill > 0.02f)
    } finally { Opt.focusSubject = oldFocus; Opt.subjRadius = oldRadius }
}
}
