package com.frak89.cloudyscanner.ai

import android.content.Context
import com.frak89.cloudyscanner.Storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small persistent memory that grows a little with every session: short lessons written by
 * the AI, the editing choices that worked, and how fast this phone fuses depth frames.
 * It is a bounded notebook fed back into prompts and defaults, not model fine-tuning.
 */
class KnowledgeBuffer(private val ctx: Context) {
    class Note(val text: String, var uses: Int, val time: Long)

    val notes = ArrayList<Note>()
    var runs = 0
    var lowConf = 0.2f
    var minClusterFrac = 0.005f
    var smooth = 1
    var islands = 20
    val levelVotes = IntArray(3)
    var integMs = 0f
    var bgStride = 1
    var aiSeconds = 0f

    private fun file(): File = File(Storage.appDir(ctx), "knowledge.json")

    val location: String get() = file().absolutePath

    @Synchronized
    fun load() {
        try {
            val f = file()
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            runs = o.optInt("runs")
            lowConf = o.optDouble("lowConf", 0.2).toFloat()
            minClusterFrac = o.optDouble("minClusterFrac", 0.005).toFloat()
            smooth = o.optInt("smooth", 1)
            islands = o.optInt("islands", 20)
            integMs = o.optDouble("integMs", 0.0).toFloat()
            bgStride = o.optInt("bgStride", 1)
            aiSeconds = o.optDouble("aiSeconds", 0.0).toFloat()
            o.optJSONArray("levelVotes")?.let { for (i in 0 until minOf(3, it.length())) levelVotes[i] = it.optInt(i) }
            notes.clear()
            o.optJSONArray("notes")?.let {
                for (i in 0 until it.length()) {
                    val n = it.getJSONObject(i)
                    notes.add(Note(n.optString("text"), n.optInt("uses"), n.optLong("time")))
                }
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun save() {
        try {
            val o = JSONObject()
            o.put("version", 1); o.put("runs", runs)
            o.put("lowConf", lowConf.toDouble()); o.put("minClusterFrac", minClusterFrac.toDouble())
            o.put("smooth", smooth); o.put("islands", islands)
            o.put("integMs", integMs.toDouble()); o.put("bgStride", bgStride); o.put("aiSeconds", aiSeconds.toDouble())
            o.put("levelVotes", JSONArray().apply { levelVotes.forEach { put(it) } })
            o.put("notes", JSONArray().apply {
                notes.forEach { put(JSONObject().put("text", it.text).put("uses", it.uses).put("time", it.time)) }
            })
            file().writeText(o.toString(1))
        } catch (_: Throwable) {}
    }

    /** Adds a lesson; near-duplicates only bump the usage counter. Oldest, least-used notes fall out. */
    @Synchronized
    fun addNote(raw: String) {
        val text = raw.trim().replace('\n', ' ').take(MAX_NOTE_CHARS)
        if (text.length < 8) return
        val key = text.lowercase().filter { it.isLetterOrDigit() }
        val same = notes.firstOrNull { n ->
            val k = n.text.lowercase().filter { it.isLetterOrDigit() }
            k == key || (k.length > 20 && key.length > 20 && k.take(24) == key.take(24))
        }
        if (same != null) { same.uses++; return }
        notes.add(Note(text, 1, System.currentTimeMillis()))
        while (notes.size > MAX_NOTES) {
            val drop = notes.withIndex().minByOrNull { it.value.uses * 1_000_000_000_000L + it.value.time }!!.index
            notes.removeAt(drop)
        }
    }

    /** Folds one finished editing session into the remembered defaults. */
    @Synchronized
    fun recordRun(level: Int, usedLowConf: Float?, usedSmooth: Int, usedIslands: Int, seconds: Float) {
        runs++
        levelVotes[level.coerceIn(0, 2)]++
        if (usedLowConf != null) lowConf += (usedLowConf - lowConf) * 0.3f
        smooth = Math.round(smooth + (usedSmooth - smooth) * 0.5f)
        islands = Math.round(islands + (usedIslands - islands) * 0.3f)
        if (seconds > 0f) aiSeconds = if (aiSeconds == 0f) seconds else aiSeconds * 0.7f + seconds * 0.3f
    }

    @Synchronized
    fun recordPerf(ms: Float, stride: Int) {
        if (ms <= 0f) return
        integMs = if (integMs == 0f) ms else integMs * 0.7f + ms * 0.3f
        bgStride = stride
    }

    val preferredLevel: Int get() = if (levelVotes.sum() == 0) 1 else levelVotes.indices.maxByOrNull { levelVotes[it] }!!

    /** Enough history to act on remembered choices without asking the model for a full plan. */
    val experienced: Boolean get() = runs >= 3

    @Synchronized
    fun promptContext(): String {
        if (notes.isEmpty() && runs == 0) return ""
        val sb = StringBuilder("MEMORIA (sessioni precedenti: $runs).")
        if (runs > 0) sb.append(" Scelte abituali: livello ${arrayOf("LOW", "MID", "HIGH")[preferredLevel]}, soglia affidabilita ${"%.2f".format(lowConf)}, smooth $smooth.")
        notes.sortedByDescending { it.uses * 1_000_000_000_000L + it.time }.take(8).forEach { sb.append("\n- ").append(it.text) }
        return sb.toString()
    }

    @Synchronized
    fun describe(): String {
        val sb = StringBuilder()
        sb.append("Sessioni registrate: $runs\n")
        sb.append("Note apprese: ${notes.size}/$MAX_NOTES\n")
        sb.append("Livello preferito: ${arrayOf("LOW", "MID", "HIGH")[preferredLevel]}  (voti ${levelVotes.joinToString("/")})\n")
        sb.append("Soglia punti deboli: ${"%.2f".format(lowConf)}   smooth: $smooth   isole < $islands facce\n")
        sb.append("Fusione media: ${"%.1f".format(integMs)} ms   passo sfondo: $bgStride\n")
        if (aiSeconds > 0f) sb.append("Tempo medio piano IA: ${"%.0f".format(aiSeconds)} s\n")
        sb.append("File: $location\n")
        if (notes.isNotEmpty()) sb.append("\n")
        notes.sortedByDescending { it.time }.forEach { sb.append("• ").append(it.text).append(if (it.uses > 1) "  (×${it.uses})" else "").append('\n') }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        notes.clear(); runs = 0; levelVotes.fill(0); lowConf = 0.2f; smooth = 1; islands = 20; aiSeconds = 0f
        save()
    }

    companion object {
        const val MAX_NOTES = 48
        const val MAX_NOTE_CHARS = 200
    }
}
