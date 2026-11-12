package com.frak89.cloudyscanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.frak89.cloudyscanner.core.MeshData
import com.frak89.cloudyscanner.core.PointMap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/** Files live in the public Downloads folder so they survive uninstalling the app. */
object Storage {
    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Downloads/CloudyScanner when allowed, otherwise the app's own (uninstall-wiped) folder. */
    fun appDir(ctx: Context): File {
        if (hasAllFiles()) {
            val d = File(downloads(), "CloudyScanner")
            if (d.isDirectory || d.mkdirs()) return d
        }
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    fun requestAllFiles(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName)))
        } catch (e: Throwable) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Binary PLY with positions, normals and colours. Caller holds the map lock. */
    fun exportPly(ctx: Context, map: PointMap): File {
        val f = File(appDir(ctx), "scan_${stamp()}.ply")
        val n = map.size
        BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { os ->
            os.write(("ply\nformat binary_little_endian 1.0\ncomment Cloudy Scanner\nelement vertex $n\n" +
                "property float x\nproperty float y\nproperty float z\nproperty float nx\nproperty float ny\nproperty float nz\n" +
                "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n").toByteArray(Charsets.US_ASCII))
            val bb = ByteBuffer.allocate(27 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val p = i * 3
                val nx = map.nrm[p]; val ny = map.nrm[p + 1]; val nz = map.nrm[p + 2]
                val l = 1f / max(1e-6f, sqrt(nx * nx + ny * ny + nz * nz))
                bb.putFloat(map.pos[p]).putFloat(map.pos[p + 1]).putFloat(map.pos[p + 2])
                bb.putFloat(nx * l).putFloat(ny * l).putFloat(nz * l)
                val c = map.col[i]
                bb.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte())
                if (bb.remaining() < 27) { os.write(bb.array(), 0, bb.position()); bb.clear() }
            }
            os.write(bb.array(), 0, bb.position())
        }
        return f
    }

    /** IGES file with one rational B-spline surface per shape; opens in FreeCAD, Rhino, Fusion and similar. */
    fun exportIges(ctx: Context, surfaces: List<com.frak89.cloudyscanner.core.NurbsSurface>): File {
        val f = File(appDir(ctx), "nurbs_${stamp()}.igs")
        f.writeText(com.frak89.cloudyscanner.core.NurbsSurface.toIges(surfaces, "scan ${stamp()}"), Charsets.US_ASCII)
        return f
    }

    /** Wavefront OBJ with vertex colours; faces as quads or split into triangles. */
    fun exportObj(ctx: Context, m: MeshData, asQuads: Boolean): File {
        val f = File(appDir(ctx), "mesh_${stamp()}_${if (asQuads) "quads" else "tris"}.obj")
        f.bufferedWriter().use { w ->
            w.write("# Cloudy Scanner - ${m.vertexCount} vertices, ${m.quadCount} quads, ${m.ngonCount} n-gons, ${m.triangleCount} triangles\n")
            for (v in 0 until m.vertexCount) {
                val c = m.col[v]
                w.write(String.format(Locale.US, "v %.5f %.5f %.5f %.3f %.3f %.3f\n",
                    m.pos[v * 3], m.pos[v * 3 + 1], m.pos[v * 3 + 2],
                    ((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f))
            }
            for (v in 0 until m.vertexCount) {
                w.write(String.format(Locale.US, "vn %.4f %.4f %.4f\n", m.nrm[v * 3], m.nrm[v * 3 + 1], m.nrm[v * 3 + 2]))
            }
            fun face(q: Int) {
                val a = m.quads[q * 4] + 1; val b = m.quads[q * 4 + 1] + 1; val c = m.quads[q * 4 + 2] + 1; val d = m.quads[q * 4 + 3] + 1
                if (c == d) w.write("f $a//$a $b//$b $c//$c\n")
                else if (asQuads) w.write("f $a//$a $b//$b $c//$c $d//$d\n")
                else w.write("f $a//$a $b//$b $c//$c\nf $a//$a $c//$c $d//$d\n")
            }
            fun ngon(g: IntArray) {
                // Kept as one N-sided face (Blender reads it as such); split into a fan for triangle export.
                if (asQuads) w.write("f " + g.joinToString(" ") { "${it + 1}//${it + 1}" } + "\n")
                else for (k in 1 until g.size - 1) w.write("f ${g[0] + 1}//${g[0] + 1} ${g[k] + 1}//${g[k] + 1} ${g[k + 1] + 1}//${g[k + 1] + 1}\n")
            }
            if (m.groups.isEmpty()) {
                for (q in 0 until m.faceCount) face(q)
                for (g in m.ngons) ngon(g)
            } else for (gr in m.groups) {
                // One named object per solid, flat-shaded.
                w.write("o ${gr.name}\ns off\n")
                for (q in gr.faceStart until gr.faceEnd) face(q)
                for (k in gr.ngonStart until gr.ngonEnd) ngon(m.ngons[k])
            }
        }
        return f
    }
}
