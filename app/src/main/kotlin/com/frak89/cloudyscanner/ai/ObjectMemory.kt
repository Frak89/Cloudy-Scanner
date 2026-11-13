package com.frak89.cloudyscanner.ai

import android.content.Context
import com.frak89.cloudyscanner.Storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Objects the user (or the AI) has named, kept next to the AI model in Download/CloudyScanner:
 * size, dominant colour, a photo of the scanned object and a few reference photos. Used to
 * recognise the same object again without the AI, and shown to the AI as examples.
 */
class ObjectMemory(private val ctx: Context) {
    class Entry(
        val name: String,
        /** Sizes in metres: the two sides seen from above (longer first) and the height. */
        var dims: FloatArray,
        var color: Int,
        var seen: Int,
        val photos: MutableList<String>,
        val refs: MutableList<String>,
    )

    val entries = ArrayList<Entry>()
    private fun dir(): File = File(Storage.appDir(ctx), "memoria_oggetti").also { it.mkdirs() }
    private fun index() = File(dir(), "oggetti.json")

    @Synchronized fun load() {
        entries.clear()
        try {
            val a = JSONArray(index().readText())
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val d = o.getJSONArray("dims")
                fun list(key: String) = o.optJSONArray(key)?.let { j -> MutableList(j.length()) { j.getString(it) } } ?: mutableListOf()
                entries.add(Entry(o.getString("name"), FloatArray(3) { d.getDouble(it).toFloat() }, o.getInt("color"), o.optInt("seen", 1), list("photos"), list("refs")))
            }
        } catch (_: Throwable) { }
    }

    @Synchronized fun save() {
        val a = JSONArray()
        for (e in entries) a.put(JSONObject().put("name", e.name).put("dims", JSONArray(e.dims.map { it.toDouble() })).put("color", e.color)
            .put("seen", e.seen).put("photos", JSONArray(e.photos)).put("refs", JSONArray(e.refs)))
        try { index().writeText(a.toString(1)) } catch (_: Throwable) { }
    }

    fun get(name: String): Entry? = synchronized(this) { entries.firstOrNull { it.name.equals(name, true) } }

    private fun safe(name: String) = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "oggetto" }

    /** Adds or updates an object; [photo] is a JPEG of it from the scan. */
    @Synchronized fun remember(name: String, dims: FloatArray?, color: Int?, photo: ByteArray?): Entry {
        var e = entries.firstOrNull { it.name.equals(name, true) }
        if (e == null) { e = Entry(name, dims ?: FloatArray(3), color ?: 0, 0, mutableListOf(), mutableListOf()); entries.add(e) }
        e.seen++
        if (dims != null && dims[0] > 0f) e.dims = if (e.dims[0] > 0f && e.seen > 1) FloatArray(3) { (e.dims[it] + dims[it]) / 2 } else dims
        if (color != null) e.color = color
        if (photo != null) {
            val f = File(dir(), "${safe(name)}_${System.currentTimeMillis()}.jpg")
            try { f.writeBytes(photo); e.photos.add(f.absolutePath); while (e.photos.size > 4) File(e.photos.removeAt(0)).delete() } catch (_: Throwable) { }
        }
        save()
        return e
    }

    /** Size and colour as measured on the clean model (more exact than from the raw points). */
    @Synchronized fun refine(name: String, dims: FloatArray, color: Int) {
        val e = entries.firstOrNull { it.name.equals(name, true) } ?: return
        if (e.dims[0] > 0f && (0..2).all { abs(e.dims[it] - dims[it]) < 0.003f }) return
        e.dims = if (e.dims[0] > 0f) FloatArray(3) { 0.5f * (e.dims[it] + dims[it]) } else dims
        e.color = color
        save()
    }

    @Synchronized fun addRefs(name: String, jpegs: List<ByteArray>) {
        val e = entries.firstOrNull { it.name.equals(name, true) } ?: return
        for ((k, j) in jpegs.withIndex()) {
            val f = File(dir(), "${safe(name)}_rif_${System.currentTimeMillis()}_$k.jpg")
            try { f.writeBytes(j); e.refs.add(f.absolutePath) } catch (_: Throwable) { }
        }
        while (e.refs.size > 4) File(e.refs.removeAt(0)).delete()
        save()
    }

    @Synchronized fun forget(name: String) {
        val e = entries.firstOrNull { it.name.equals(name, true) } ?: return
        (e.photos + e.refs).forEach { File(it).delete() }
        entries.remove(e); save()
    }

    /** How different a scanned thing is from a remembered one (0 = same), from size and colour. */
    fun distance(e: Entry, dims: FloatArray, color: Int): Float {
        if (e.dims[0] <= 0f) return Float.MAX_VALUE
        var d = 0f
        for (k in 0..2) { val a = e.dims[k]; val b = dims[k]; d += abs(a - b) / max(0.015f, 0.2f * max(a, b)) }
        val r = ((e.color shr 16) and 255) - ((color shr 16) and 255); val g = ((e.color shr 8) and 255) - ((color shr 8) and 255); val b = (e.color and 255) - (color and 255)
        return d / 3f + sqrt((r * r + g * g + b * b).toFloat()) / 120f
    }

    /** The remembered object this one most likely is, when the match is clear. */
    fun match(dims: FloatArray, color: Int): Entry? = synchronized(this) {
        val ranked = entries.map { it to distance(it, dims, color) }.sortedBy { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second > 1f) return null
        if (ranked.size > 1 && ranked[1].second < best.second * 1.3f) return null
        best.first
    }

    /** Up to [n] remembered objects closest to this size, for the AI to compare with. */
    fun closest(dims: FloatArray, color: Int, n: Int): List<Entry> = synchronized(this) {
        entries.filter { it.photos.isNotEmpty() || it.refs.isNotEmpty() }.sortedBy { distance(it, dims, color) }.take(n)
    }
}
