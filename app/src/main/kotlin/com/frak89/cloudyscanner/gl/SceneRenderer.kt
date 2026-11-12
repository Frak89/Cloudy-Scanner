package com.frak89.cloudyscanner.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.frak89.cloudyscanner.Opt
import com.frak89.cloudyscanner.core.MeshData
import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.scan.ScanEngine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Screen-space positions the overlay needs; written by the GL thread, read by the UI thread. */
class HudState {
    @Volatile var guideActive = false
    @Volatile var guideX = 0f
    @Volatile var guideY = 0f
    @Volatile var guideBehind = false
    @Volatile var subjOn = false
    @Volatile var subjX = 0f
    @Volatile var subjY = 0f
    @Volatile var fps = 0f
    @Volatile var points = 0
    /** "tavolo · 82%" once the AI has recognised the subject. */
    @Volatile var objLabel = ""
}

/** Mesh buffers prepared off the GL thread, ready to hand to the GPU. */
class MeshGpu(mesh: MeshData) {
    val vertexCount = mesh.vertexCount
    val vertices: ByteBuffer = ByteBuffer.allocateDirect(max(1, vertexCount) * 28).order(ByteOrder.nativeOrder())
    val tris: IntBuffer
    val wireQuads: IntBuffer
    val wireTris: IntBuffer

    init {
        for (v in 0 until vertexCount) {
            vertices.putFloat(mesh.pos[v * 3]); vertices.putFloat(mesh.pos[v * 3 + 1]); vertices.putFloat(mesh.pos[v * 3 + 2])
            vertices.putFloat(mesh.nrm[v * 3]); vertices.putFloat(mesh.nrm[v * 3 + 1]); vertices.putFloat(mesh.nrm[v * 3 + 2])
            val c = mesh.col[v]
            vertices.put((c shr 16).toByte()); vertices.put((c shr 8).toByte()); vertices.put(c.toByte()); vertices.put(-1)
        }
        vertices.flip()
        // Faces sorted bottom-up so a partial draw looks like the model being built from the ground.
        val nf = mesh.faceCount
        val order = (0 until nf).sortedBy { mesh.pos[mesh.quads[it * 4] * 3 + 1] }
        val sorted = IntArray(nf * 4)
        for ((i, f) in order.withIndex()) System.arraycopy(mesh.quads, f * 4, sorted, i * 4, 4)
        val m = MeshData(mesh.pos, mesh.nrm, mesh.col, sorted, mesh.cell)
        tris = direct(m.triangleIndices())
        wireQuads = direct(m.wireIndices(false))
        wireTris = direct(m.wireIndices(true))
    }

    private fun direct(a: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(max(1, a.size) * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(a); flip() }
}

class SceneRenderer(
    private val engine: ScanEngine,
    private val map: PointMap,
    val hud: HudState,
    private val displayRotation: () -> Int,
) : GLSurfaceView.Renderer {

    // Orbit camera (UI thread writes, GL thread reads).
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.35f
    @Volatile var dist = 3f
    val target = FloatArray(3)
    @Volatile var turnAngle = 0f

    @Volatile private var pendingMesh: MeshGpu? = null
    @Volatile private var clearMesh = false
    @Volatile private var revealStartNs = 0L
    @Volatile var revealSeconds = 0f
    @Volatile var snapshotRequest: ((ByteArray?) -> Unit)? = null
    /** When set, points are drawn in this reduced palette (256-colour view). */
    @Volatile var palette: com.frak89.cloudyscanner.core.Palette? = null
    @Volatile var paletteSubjectOnly = false

    private var bgProg = 0; private var ptProg = 0; private var meshProg = 0; private var lineProg = 0
    private var camTex = 0
    private var bgVao = 0; private var bgUvBuf = 0
    private var ptVao = 0; private var ptBuf = 0
    private var meshVao = 0; private var meshBuf = 0
    private var triIbo = 0; private var wqIbo = 0; private var wtIbo = 0
    private var lineVao = 0; private var lineBuf = 0
    private var triCount = 0; private var wqCount = 0; private var wtCount = 0
    private var hasMesh = false
    private var lastMeshCpu: MeshGpu? = null

    private var ptCpu: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
    private var ptCount = 0
    private var uploadedVersion = -1L
    private var lastUploadNs = 0L
    private var lastBoundsNs = 0L
    val sceneBounds = FloatArray(6)
    @Volatile var hasBounds = false

    private var width = 1; private var height = 1
    private val view = FloatArray(16); private val proj = FloatArray(16); private val vp = FloatArray(16)
    private val model = FloatArray(16); private val mvp = FloatArray(16)
    private val camPos = FloatArray(3)
    private val pickMvp = FloatArray(16)
    private val pickCam = FloatArray(3)
    private val pickModel = FloatArray(16)
    private val uvFb: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val lineFb: FloatBuffer = ByteBuffer.allocateDirect(4096 * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var lastFrameNs = 0L
    private val t0 = System.nanoTime()

    fun setMesh(m: MeshGpu?, revealSec: Float) {
        if (m == null) { clearMesh = true; pendingMesh = null } else { revealSeconds = revealSec; pendingMesh = m }
    }

    /** Copies the matrices used for the last frame so the UI thread can project points. */
    fun getPick(outMvp: FloatArray, outCam: FloatArray, outModel: FloatArray): IntArray {
        synchronized(pickMvp) {
            System.arraycopy(pickMvp, 0, outMvp, 0, 16)
            System.arraycopy(pickCam, 0, outCam, 0, 3)
            System.arraycopy(pickModel, 0, outModel, 0, 16)
        }
        return intArrayOf(width, height)
    }

    fun recenter() {
        if (hasBounds) {
            val b = sceneBounds
            target[0] = (b[0] + b[3]) / 2; target[1] = (b[1] + b[4]) / 2; target[2] = (b[2] + b[5]) / 2
            val ext = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2]))
            dist = (ext * 1.3f).coerceIn(0.6f, 30f)
        } else {
            target[0] = engine.poseM[12]; target[1] = engine.poseM[13]; target[2] = engine.poseM[14] - 1.5f
            dist = 3f
        }
    }

    // ------------------------------------------------------------------ GL setup

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(8)
        GLES30.glGenTextures(1, ids, 0)
        camTex = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        engine.setTexture(camTex)

        bgProg = program(Shaders.BG_VS, Shaders.BG_FS)
        ptProg = program(Shaders.POINT_VS, Shaders.POINT_FS)
        meshProg = program(Shaders.MESH_VS, Shaders.MESH_FS)
        lineProg = program(Shaders.LINE_VS, Shaders.LINE_FS)

        GLES30.glGenVertexArrays(4, ids, 0)
        bgVao = ids[0]; ptVao = ids[1]; meshVao = ids[2]; lineVao = ids[3]
        GLES30.glGenBuffers(8, ids, 0)
        val bgPosBuf = ids[0]; bgUvBuf = ids[1]; ptBuf = ids[2]; meshBuf = ids[3]
        triIbo = ids[4]; wqIbo = ids[5]; wtIbo = ids[6]; lineBuf = ids[7]

        // Background quad
        GLES30.glBindVertexArray(bgVao)
        val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bgPosBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, quad, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bgUvBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)

        // Points
        GLES30.glBindVertexArray(ptVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ptBuf)
        val ps = PointMap.STRIDE
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, ps, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, ps, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_BYTE, true, ps, 16)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 4, GLES30.GL_UNSIGNED_BYTE, false, ps, 20)

        // Mesh
        GLES30.glBindVertexArray(meshVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshBuf)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 28, 24)

        // Helper lines
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineFb.capacity() * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)

        // A new context lost every buffer: push everything again.
        uploadedVersion = -1
        hasMesh = false
        if (pendingMesh == null) pendingMesh = lastMeshCpu
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GLES30.glViewport(0, 0, width, height)
        engine.setViewport(displayRotation(), width, height)
    }

    private fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("Cloudy", "Shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) android.util.Log.e("Cloudy", "Program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun u(p: Int, name: String) = GLES30.glGetUniformLocation(p, name)

    // ------------------------------------------------------------------ frame

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else min(0.1f, (now - lastFrameNs) / 1e9f)
        lastFrameNs = now
        if (dt > 0f) hud.fps = hud.fps * 0.9f + (1f / dt) * 0.1f
        val time = (now - t0) / 1e9f

        val arOk = engine.update()
        val orbit = Opt.orbit || !arOk

        GLES30.glClearColor(0.155f, 0.16f, 0.185f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Camera
        Matrix.setIdentityM(model, 0)
        if (orbit) {
            if (Opt.autoRotate) turnAngle = (turnAngle + Opt.rotSpeed * dt) % 360f
            val cp = cos(pitch); val ex = target[0] + dist * cp * sin(yaw); val ey = target[1] + dist * sin(pitch); val ez = target[2] + dist * cp * cos(yaw)
            Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], 0f, 1f, 0f)
            val aspect = width.toFloat() / height
            if (Opt.ortho) {
                val hh = dist * 0.5f
                Matrix.orthoM(proj, 0, -hh * aspect, hh * aspect, -hh, hh, -100f, 200f)
            } else Matrix.perspectiveM(proj, 0, Opt.fov, aspect, 0.03f, 200f)
            camPos[0] = ex; camPos[1] = ey; camPos[2] = ez
            Matrix.translateM(model, 0, target[0], target[1], target[2])
            Matrix.rotateM(model, 0, turnAngle, 0f, 1f, 0f)
            if (Opt.pulse) { val s = 1f + 0.035f * sin(time * 2.2f); Matrix.scaleM(model, 0, s, s, s) }
            Matrix.translateM(model, 0, -target[0], -target[1], -target[2])
        } else {
            System.arraycopy(engine.viewM, 0, view, 0, 16)
            System.arraycopy(engine.projM, 0, proj, 0, 16)
            camPos[0] = engine.poseM[12]; camPos[1] = engine.poseM[13]; camPos[2] = engine.poseM[14]
        }
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        synchronized(pickMvp) {
            System.arraycopy(mvp, 0, pickMvp, 0, 16); System.arraycopy(camPos, 0, pickCam, 0, 3); System.arraycopy(model, 0, pickModel, 0, 16)
        }

        if (Opt.showCamera && arOk && !Opt.orbit) drawBackground()

        uploadPoints(now)
        uploadMesh(now)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        drawHelpers(time)
        if (hasMesh) drawMesh(now)
        if (Opt.showPoints && ptCount > 0) drawPoints(time)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        updateHud()
        snapshotRequest?.let { cb -> snapshotRequest = null; cb(readSnapshot()) }
    }

    private fun drawBackground() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(bgProg)
        GLES30.glBindVertexArray(bgVao)
        uvFb.clear(); uvFb.put(engine.bgUv).flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bgUvBuf)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 32, uvFb)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glUniform1i(u(bgProg, "uTex"), 0)
        GLES30.glUniform1i(u(bgProg, "uMode"), if (Opt.edgeOverlay) 1 else 0)
        GLES30.glUniform2f(u(bgProg, "uTexel"), 1.5f / 1920f, 1.5f / 1080f)
        GLES30.glUniform1f(u(bgProg, "uDim"), if (Opt.edgeOverlay) 1f else 0.8f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDepthMask(true)
    }

    private fun uploadPoints(now: Long) {
        if (map.version == uploadedVersion || now - lastUploadNs < 110_000_000L) return
        if (!map.lock.tryLock()) return
        try {
            val need = map.size * PointMap.STRIDE
            if (ptCpu.capacity() < need) {
                ptCpu = ByteBuffer.allocateDirect(need + need / 2).order(ByteOrder.nativeOrder())
            }
            ptCount = map.writeRenderBuffer(ptCpu, palette, paletteSubjectOnly)
            uploadedVersion = map.version
            if (now - lastBoundsNs > 700_000_000L) { hasBounds = map.bounds(sceneBounds); lastBoundsNs = now }
        } finally { map.lock.unlock() }
        lastUploadNs = now
        hud.points = ptCount
        if (ptCount == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ptBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, ptCount * PointMap.STRIDE, ptCpu, GLES30.GL_DYNAMIC_DRAW)
    }

    private fun uploadMesh(now: Long) {
        if (clearMesh) { clearMesh = false; hasMesh = false; lastMeshCpu = null }
        val m = pendingMesh ?: return
        pendingMesh = null
        lastMeshCpu = m
        if (m.vertexCount == 0 || m.tris.limit() == 0) { hasMesh = false; return }
        m.vertices.position(0); m.tris.position(0); m.wireQuads.position(0); m.wireTris.position(0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.limit(), m.vertices, GLES30.GL_STATIC_DRAW)
        triCount = m.tris.limit(); wqCount = m.wireQuads.limit(); wtCount = m.wireTris.limit()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, triIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, triCount * 4, m.tris, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, wqIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, wqCount * 4, m.wireQuads, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, wtIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, wtCount * 4, m.wireTris, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        hasMesh = triCount > 0
        revealStartNs = if (revealSeconds > 0f) now else 0L
    }

    private fun drawMesh(now: Long) {
        var frac = 1f
        if (revealStartNs != 0L && revealSeconds > 0f) {
            frac = ((now - revealStartNs) / 1e9f / revealSeconds).coerceIn(0f, 1f)
            if (frac >= 1f) revealStartNs = 0L
        }
        GLES30.glUseProgram(meshProg)
        GLES30.glBindVertexArray(meshVao)
        GLES30.glUniformMatrix4fv(u(meshProg, "uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(u(meshProg, "uModel"), 1, false, model, 0)
        GLES30.glUniform3f(u(meshProg, "uCamPos"), camPos[0], camPos[1], camPos[2])
        val xray = Opt.xray
        if (Opt.showSolid) {
            if (Opt.cull) { GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK) }
            if (xray) {
                GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            }
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(1f, 1f)
            GLES30.glUniform4f(u(meshProg, "uColor"), 0.72f, 0.74f, 0.8f, if (xray) 0.35f else 1f)
            GLES30.glUniform1i(u(meshProg, "uUseVertexColor"), if (Opt.vertexColor) 1 else 0)
            GLES30.glUniform1i(u(meshProg, "uLit"), if (Opt.lit) 1 else 0)
            GLES30.glUniform1i(u(meshProg, "uFlat"), if (Opt.flat) 1 else 0)
            GLES30.glUniform1i(u(meshProg, "uDiscardBack"), 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, triIbo)
            val n = ((triCount / 3) * frac).toInt() * 3
            if (n > 0) GLES30.glDrawElements(GLES30.GL_TRIANGLES, n, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            if (xray) { GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true) }
        }
        if (Opt.showWire) {
            val solid = Opt.showSolid
            if (solid) GLES30.glUniform4f(u(meshProg, "uColor"), 0.25f, 0.9f, 1f, 1f)
            else GLES30.glUniform4f(u(meshProg, "uColor"), 0.9f, 0.93f, 1f, 1f)
            GLES30.glUniform1i(u(meshProg, "uUseVertexColor"), 0)
            GLES30.glUniform1i(u(meshProg, "uLit"), 0)
            GLES30.glUniform1i(u(meshProg, "uFlat"), 0)
            GLES30.glUniform1i(u(meshProg, "uDiscardBack"), if (Opt.cull) 1 else 0)
            GLES30.glLineWidth(1.5f)
            if (xray) GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            val quads = Opt.wireQuads
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, if (quads) wqIbo else wtIbo)
            val per = if (quads) 8 else 10
            val total = if (quads) wqCount else wtCount
            val n = ((total / per) * frac).toInt() * per
            if (n > 0) GLES30.glDrawElements(GLES30.GL_LINES, n, GLES30.GL_UNSIGNED_INT, 0)
            if (xray) GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawPoints(time: Float) {
        GLES30.glUseProgram(ptProg)
        GLES30.glBindVertexArray(ptVao)
        GLES30.glUniformMatrix4fv(u(ptProg, "uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(u(ptProg, "uModel"), 1, false, model, 0)
        GLES30.glUniform3f(u(ptProg, "uCamPos"), camPos[0], camPos[1], camPos[2])
        GLES30.glUniform1f(u(ptProg, "uPointSize"), Opt.pointSize)
        GLES30.glUniform1f(u(ptProg, "uPersp"), if (Opt.perspPoints && !Opt.ortho) 1f else 0f)
        GLES30.glUniform1i(u(ptProg, "uColorMode"), Opt.colorMode)
        GLES30.glUniform1i(u(ptProg, "uCull"), if (Opt.cull) 1 else 0)
        GLES30.glUniform1i(u(ptProg, "uShowUncertain"), if (Opt.guidance) 1 else 0)
        GLES30.glUniform1i(u(ptProg, "uShowSubject"), if (Opt.subjectHighlight && engine.subject.locked) 1 else 0)
        GLES30.glUniform1f(u(ptProg, "uTime"), time)
        GLES30.glUniform1f(u(ptProg, "uWave"), if (Opt.wave) 1f else 0f)
        GLES30.glUniform2f(u(ptProg, "uHeight"), sceneBounds[1], sceneBounds[4])
        if (Opt.xray) GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, ptCount)
        if (Opt.xray) GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(0)
    }

    private fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        if (lineFb.remaining() < 6) return
        lineFb.put(x0).put(y0).put(z0).put(x1).put(y1).put(z1)
    }

    private fun flushLines(r: Float, g: Float, b: Float, a: Float) {
        val n = lineFb.position() / 3
        if (n == 0) return
        lineFb.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuf)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 12, lineFb)
        GLES30.glUniform4f(u(lineProg, "uColor"), r, g, b, a)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, n)
        lineFb.clear()
    }

    private fun box(b: FloatArray) {
        val x0 = b[0]; val y0 = b[1]; val z0 = b[2]; val x1 = b[3]; val y1 = b[4]; val z1 = b[5]
        line(x0, y0, z0, x1, y0, z0); line(x1, y0, z0, x1, y0, z1); line(x1, y0, z1, x0, y0, z1); line(x0, y0, z1, x0, y0, z0)
        line(x0, y1, z0, x1, y1, z0); line(x1, y1, z0, x1, y1, z1); line(x1, y1, z1, x0, y1, z1); line(x0, y1, z1, x0, y1, z0)
        line(x0, y0, z0, x0, y1, z0); line(x1, y0, z0, x1, y1, z0); line(x1, y0, z1, x1, y1, z1); line(x0, y0, z1, x0, y1, z1)
    }

    private fun drawHelpers(time: Float) {
        GLES30.glUseProgram(lineProg)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glUniformMatrix4fv(u(lineProg, "uMVP"), 1, false, mvp, 0)
        GLES30.glLineWidth(1f)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        lineFb.clear()
        if (Opt.grid) {
            val y = if (hasBounds) sceneBounds[1] else engine.poseM[13] - 1.3f
            val gx = Math.round(camPos[0]).toFloat(); val gz = Math.round(camPos[2]).toFloat()
            var i = -6f
            while (i <= 6f) {
                line(gx + i, y, gz - 6f, gx + i, y, gz + 6f); line(gx - 6f, y, gz + i, gx + 6f, y, gz + i)
                i += 0.5f
            }
            flushLines(0.55f, 0.6f, 0.75f, 0.16f)
        }
        if (Opt.axes) {
            line(0f, 0f, 0f, 0.5f, 0f, 0f); flushLines(1f, 0.3f, 0.3f, 1f)
            line(0f, 0f, 0f, 0f, 0.5f, 0f); flushLines(0.3f, 1f, 0.4f, 1f)
            line(0f, 0f, 0f, 0f, 0f, 0.5f); flushLines(0.35f, 0.55f, 1f, 1f)
        }
        if (Opt.bbox && hasBounds) { box(sceneBounds); flushLines(1f, 0.85f, 0.3f, 0.8f) }
        GLES30.glLineWidth(2f)
        val s = engine.subject
        if (Opt.subjectHighlight && s.valid && s.locked) {
            box(s.bounds)
            if (s.moving) flushLines(1f, 0.45f, 0.3f, 0.95f) else flushLines(0.3f, 0.9f, 1f, 0.9f)
        }
        val g = engine.guidance
        if (Opt.guidance && g.active) {
            val r = 0.14f + 0.03f * sin(time * 5f)
            val seg = 28
            for (k in 0 until 3) for (i in 0 until seg) {
                val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg
                val c0 = cos(a0) * r; val s0 = sin(a0) * r; val c1 = cos(a1) * r; val s1 = sin(a1) * r
                when (k) {
                    0 -> line(g.x + c0, g.y + s0, g.z, g.x + c1, g.y + s1, g.z)
                    1 -> line(g.x + c0, g.y, g.z + s0, g.x + c1, g.y, g.z + s1)
                    else -> line(g.x, g.y + c0, g.z + s0, g.x, g.y + c1, g.z + s1)
                }
            }
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            flushLines(1f, 0.62f, 0.1f, 1f)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
    }

    private val clip = FloatArray(4)
    private val vec = FloatArray(4)

    private fun project(x: Float, y: Float, z: Float): Boolean {
        vec[0] = x; vec[1] = y; vec[2] = z; vec[3] = 1f
        Matrix.multiplyMV(clip, 0, mvp, 0, vec, 0)
        val w = clip[3]
        val behind = w <= 1e-4f
        // For points behind the camera divide by |w| so the result still points toward them.
        val iw = 1f / max(kotlin.math.abs(w), 1e-4f)
        clip[0] = (clip[0] * iw * 0.5f + 0.5f) * width
        clip[1] = (1f - (clip[1] * iw * 0.5f + 0.5f)) * height
        return !behind
    }

    private fun updateHud() {
        val g = engine.guidance
        if (Opt.guidance && g.active) {
            val front = project(g.x, g.y, g.z)
            hud.guideX = clip[0]; hud.guideY = clip[1]; hud.guideBehind = !front; hud.guideActive = true
        } else hud.guideActive = false
        val s = engine.subject
        if (s.valid) {
            val front = project(s.x, s.y, s.z)
            hud.subjX = clip[0]; hud.subjY = clip[1]; hud.subjOn = front
        } else hud.subjOn = false
    }

    private fun readSnapshot(): ByteArray? = try {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val scale = min(1f, 896f / max(width, height))
        val mtx = android.graphics.Matrix().apply { postScale(scale, -scale) }
        val out = Bitmap.createBitmap(bmp, 0, 0, width, height, mtx, true)
        val os = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, os)
        bmp.recycle(); out.recycle()
        os.toByteArray()
    } catch (e: Throwable) { null }
}
