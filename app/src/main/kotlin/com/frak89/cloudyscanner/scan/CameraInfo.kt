package com.frak89.cloudyscanner.scan

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import java.util.Locale
import kotlin.math.atan
import kotlin.math.sqrt

/** Reads what Camera2 exposes about every camera module on the phone. */
object CameraInfo {
    private fun f(v: Float, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)

    private fun one(cm: CameraManager, id: String, indent: String): String {
        val c = cm.getCameraCharacteristics(id)
        val sb = StringBuilder()
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "posteriore"
            CameraMetadata.LENS_FACING_FRONT -> "frontale"
            else -> "esterna"
        }
        sb.append("${indent}Camera $id · $facing\n")
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (size != null && px != null) {
            val diag = sqrt(size.width * size.width + size.height * size.height)
            sb.append("$indent  Sensore ${f(size.width)}×${f(size.height)} mm (diag ${f(diag)} mm) · ${px.width}×${px.height} px · ${f(px.width * px.height / 1e6f, 1)} MP\n")
            sb.append("$indent  Pixel ${f(size.width / px.width * 1000f)} µm\n")
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.forEach { fl ->
                val eq = fl * 43.27f / diag
                val hfov = Math.toDegrees(2.0 * atan((size.width / (2f * fl)).toDouble())).toFloat()
                sb.append("$indent  Focale ${f(fl)} mm (≈ ${f(eq, 0)} mm equiv. 35mm) · campo orizz. ${f(hfov, 1)}°\n")
            }
        }
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { a ->
            if (a.isNotEmpty()) sb.append("$indent  Apertura " + a.joinToString(", ") { "f/${f(it, 2)}" } + "\n")
        }
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        sb.append(if (minFocus > 0f) "$indent  Messa a fuoco minima ${f(100f / minFocus, 1)} cm (autofocus)\n" else "$indent  Fuoco fisso\n")
        val hyper = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
        if (hyper > 0f) sb.append("$indent  Iperfocale ${f(1f / hyper, 2)} m\n")
        val cal = when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrata (distanze in metri affidabili)"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approssimata"
            else -> "non calibrata"
        }
        sb.append("$indent  Distanza di fuoco: $cal\n")
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois != null && ois.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON }) sb.append("$indent  Stabilizzazione ottica (OIS)\n")
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val depth = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val depth16 = try { map?.getOutputSizes(ImageFormat.DEPTH16)?.firstOrNull() } catch (_: Throwable) { null }
        sb.append("$indent  Sensore di profondità dedicato: ${if (depth && depth16 != null) "sì, ${depth16.width}×${depth16.height}" else "no"}\n")
        return sb.toString()
    }

    fun describe(ctx: Context, arCameraId: String): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        try {
            for (id in cm.cameraIdList) {
                sb.append(one(cm, id, ""))
                if (id == arCameraId) sb.append("  ► usata da ARCore per la scansione\n")
                val c = cm.getCameraCharacteristics(id)
                val phys = c.physicalCameraIds
                if (phys.isNotEmpty()) {
                    sb.append("  Camera logica composta da ${phys.size} moduli fisici:\n")
                    for (p in phys) try { sb.append(one(cm, p, "    ")) } catch (_: Throwable) {}
                }
                sb.append('\n')
            }
        } catch (e: Throwable) {
            sb.append("Impossibile leggere le fotocamere: ${e.message}\n")
        }
        return sb.toString()
    }

    /** Short summary for AI prompts. */
    fun brief(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.joinToString("; ") { id ->
            val c = cm.getCameraCharacteristics(id)
            val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f
            "cam$id ${f(fl)}mm f/${f(ap, 1)}"
        }
    } catch (_: Throwable) { "" }
}
