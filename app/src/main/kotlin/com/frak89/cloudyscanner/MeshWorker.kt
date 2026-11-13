package com.frak89.cloudyscanner

import com.frak89.cloudyscanner.core.MeshData
import com.frak89.cloudyscanner.core.Mesher
import com.frak89.cloudyscanner.core.NurbsSurface
import com.frak89.cloudyscanner.core.PrimitiveFit
import com.frak89.cloudyscanner.core.SmartModel
import com.frak89.cloudyscanner.core.SmartOptions
import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.gl.MeshGpu
import com.frak89.cloudyscanner.gl.SceneRenderer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Builds meshes off the UI and GL threads; a newer request cancels the one in flight. */
class MeshWorker(private val map: PointMap, private val renderer: SceneRenderer) {
    @Volatile var mesh: MeshData? = null
    @Volatile var level = -1
    @Volatile var busy = false
    @Volatile var progress = 0f
    @Volatile var lastMs = 0f
    @Volatile var builtVersion = -1L
    /** Exact surfaces behind the current mesh when it was built in NURBS mode. */
    @Volatile var nurbs: List<NurbsSurface>? = null
    @Volatile var nurbsInfo = ""
    /** Objects of the last clean model, for the catalogue. */
    @Volatile var catalog: List<com.frak89.cloudyscanner.core.CatalogPart> = emptyList()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "mesher").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val gen = AtomicInteger()

    fun request(lvl: Int, smooth: Int, islands: Int, revealSec: Float, onDone: ((MeshData?) -> Unit)? = null) {
        val g = gen.incrementAndGet()
        busy = true
        exec.execute {
            val m = try { build(lvl, smooth, islands, revealSec, g) } catch (t: Throwable) { null }
            if (g == gen.get()) busy = false
            onDone?.invoke(m)
        }
    }

    /** Same as [request] but on the caller's thread. */
    fun buildBlocking(lvl: Int, smooth: Int, islands: Int, revealSec: Float): MeshData? {
        val g = gen.incrementAndGet()
        busy = true
        return try { build(lvl, smooth, islands, revealSec, g) } finally { if (g == gen.get()) busy = false }
    }

    private fun build(lvl: Int, smooth: Int, islands: Int, revealSec: Float, g: Int): MeshData? {
        val t0 = System.nanoTime()
        val n: Int; val pos: FloatArray; val nrm: FloatArray; val col: IntArray; val ver: Long
        var subject: BooleanArray? = null
        map.lock.lock()
        try {
            n = map.size; ver = map.version
            pos = map.pos.copyOf(n * 3); nrm = map.nrm.copyOf(n * 3); col = map.col.copyOf(n)
            if ((lvl == Opt.LEVEL_NURBS || Opt.smart) && map.countFlag(PointMap.SUBJECT) > 200) subject = BooleanArray(n) { map.hasFlag(it, PointMap.SUBJECT) }
        } finally { map.lock.unlock() }
        progress = 0f
        if (lvl == Opt.LEVEL_NURBS) {
            // Regular shapes only: tubes, bends, discs and flat faces, each an exact NURBS surface.
            progress = 0.2f
            val sel = subject
            val fit = PrimitiveFit.fit(n, pos, nrm, col, if (sel == null) null else { i -> sel[i] }, 1L) { gen.get() != g }
            if (gen.get() != g) return null
            val surf = fit.surfaces()
            val m = NurbsSurface.tessellate(surf)
            val gpu = MeshGpu(m)
            if (gen.get() != g) return null
            nurbs = surf; nurbsInfo = fit.describe()
            mesh = m; level = lvl; builtVersion = ver
            lastMs = (System.nanoTime() - t0) / 1e6f
            progress = 1f
            renderer.forceFlat = false
            renderer.setMesh(gpu, revealSec)
            return m
        }
        if (Opt.smart) {
            // Clean model: exact solids with few faces, squared up; irregular parts optionally kept as mesh.
            progress = 0.2f
            val sel = subject
            val res = SmartModel.build(n, pos, nrm, col, if (sel == null) null else { i -> sel[i] },
                SmartOptions(lvl, Opt.circleSides, Opt.bevel, Opt.keepDetails, Opt.objectHint, Opt.labels, Opt.silhouettes)) { gen.get() != g } ?: return null
            if (gen.get() != g) return null
            val gpu = MeshGpu(res.mesh)
            if (gen.get() != g) return null
            nurbs = res.surfaces; nurbsInfo = res.info; catalog = res.parts
            mesh = res.mesh; level = lvl; builtVersion = ver
            lastMs = (System.nanoTime() - t0) / 1e6f
            progress = 1f
            renderer.forceFlat = true
            renderer.setMesh(gpu, revealSec)
            return res.mesh
        }
        var m = Mesher.build(n, pos, nrm, col, Opt.levelCell[lvl], cancel = { gen.get() != g }, progress = { progress = it }) ?: return null
        if (islands > 0) m = Mesher.removeIslands(m, islands)
        if (smooth > 0) m = Mesher.smooth(m, smooth)
        if (gen.get() != g) return null
        val gpu = MeshGpu(m)
        if (gen.get() != g) return null
        nurbs = null; nurbsInfo = ""; catalog = emptyList()
        mesh = m; level = lvl; builtVersion = ver
        lastMs = (System.nanoTime() - t0) / 1e6f
        renderer.forceFlat = false
        renderer.setMesh(gpu, revealSec)
        return m
    }

    fun clear() {
        gen.incrementAndGet()
        busy = false
        mesh = null; level = -1; nurbs = null; nurbsInfo = ""; catalog = emptyList()
        renderer.setMesh(null, 0f)
    }

    fun shutdown() { exec.shutdownNow() }
}
