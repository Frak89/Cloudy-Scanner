package com.frak89.cloudyscanner.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.frak89.cloudyscanner.Storage
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device multimodal model (Gemma 4 in .litertlm format) run with LiteRT-LM.
 * The model file stays in the public Downloads folder: pick it once, and it is found again
 * automatically after a reinstall.
 */
class LocalAi(private val ctx: Context) {
    enum class State { NONE, FOUND, LOADING, READY, ERROR }

    @Volatile var state = State.NONE
    @Volatile var status = "Nessun modello selezionato"
    @Volatile var backendName = ""
    @Volatile var modelFile: File? = null
    private var engine: Engine? = null
    private val prefs = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    /** Model files in Downloads (and one level of sub-folders). Needs all-files access. */
    fun findModels(): List<File> {
        if (!Storage.hasAllFiles()) return emptyList()
        val out = ArrayList<File>()
        fun scan(dir: File, depth: Int) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) { if (depth < 2) scan(f, depth + 1) }
                else if (f.name.endsWith(".litertlm", true) && f.length() > 50_000_000L) out.add(f)
            }
        }
        scan(Storage.downloads(), 0)
        return out.sortedByDescending { it.length() }
    }

    private fun pointerFile() = File(Storage.appDir(ctx), "model_path.txt")

    fun select(f: File) {
        if (modelFile?.absolutePath != f.absolutePath) close()
        modelFile = f
        state = State.FOUND
        status = "${f.name} (${"%.2f".format(f.length() / 1e9)} GB)"
        prefs.edit().putString("path", f.absolutePath).apply()
        try { pointerFile().writeText(f.absolutePath) } catch (_: Throwable) {}
    }

    /**
     * Restores the remembered model, or adopts the biggest one in Downloads.
     * The choice is also written next to the model, so it outlives app data.
     */
    fun autoSelect(): Boolean {
        if (modelFile?.exists() == true) return true
        val saved = prefs.getString("path", null)
            ?: try { pointerFile().takeIf { it.exists() }?.readText()?.trim() } catch (_: Throwable) { null }
        if (saved != null && File(saved).canRead()) { select(File(saved)); return true }
        val found = findModels()
        if (found.isNotEmpty()) { select(found[0]); return true }
        state = State.NONE
        status = if (Storage.hasAllFiles()) "Nessun file .litertlm in Download" else "Serve l'accesso ai file per cercare in Download"
        return false
    }

    /** Blocking; call off the UI thread. GPU first, CPU as a fallback. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (engine != null && state == State.READY) return true
        val f = modelFile ?: return false
        state = State.LOADING
        var lastError = ""
        for (gpu in booleanArrayOf(true, false)) {
            status = "Carico ${f.name} su ${if (gpu) "GPU" else "CPU"}… (può richiedere fino a un minuto)"
            try {
                val cfg = EngineConfig(
                    modelPath = f.absolutePath,
                    backend = if (gpu) Backend.GPU() else Backend.CPU(),
                    visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
                    cacheDir = ctx.cacheDir.absolutePath,
                )
                val e = Engine(cfg)
                e.initialize()
                engine = e
                backendName = if (gpu) "GPU" else "CPU"
                state = State.READY
                status = "${f.name} pronto ($backendName)"
                return true
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(160)
            }
        }
        state = State.ERROR
        status = "Caricamento fallito. $lastError"
        return false
    }

    /**
     * Runs one stateless request. [onText] receives the text generated so far, as it grows.
     * Returns the full answer; throws when the model fails.
     */
    @Synchronized
    fun generate(system: String, prompt: String, images: List<ByteArray>, maxTokens: Int, onText: (String) -> Unit): String {
        val e = engine ?: throw IllegalStateException("modello non caricato")
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.5),
        )
        e.createConversation(cfg).use { conv ->
            val parts = ArrayList<Content>()
            for (img in images) parts.add(Content.ImageBytes(img))
            parts.add(Content.Text(prompt))
            val sb = StringBuilder()
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            conv.sendMessageAsync(Contents.of(parts), object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.toString())
                    onText(sb.toString())
                }
                override fun onDone() { done.countDown() }
                override fun onError(throwable: Throwable) { failure = throwable; done.countDown() }
            }, maxOutputToken = maxTokens)
            if (!done.await(240, TimeUnit.SECONDS)) {
                try { conv.cancelProcess() } catch (_: Throwable) {}
                if (sb.isEmpty()) throw RuntimeException("tempo scaduto")
            }
            failure?.let { if (sb.isEmpty()) throw it }
            return sb.toString()
        }
    }

    /** Queues a system download into the public Downloads folder. Returns the download id. */
    fun startDownload(big: Boolean): Long {
        val name = if (big) MODEL_BIG else MODEL_SMALL
        val repo = if (big) "gemma-4-E4B-it-litert-lm" else "gemma-4-E2B-it-litert-lm"
        val url = "https://huggingface.co/litert-community/$repo/resolve/main/$name?download=true"
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("Modello IA locale per Cloudy Scanner")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /** Returns [bytesSoFar, totalBytes, status] for a download id, or null when unknown. */
    fun downloadProgress(id: Long): LongArray? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                return longArrayOf(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).toLong(),
                )
            }
        }
        return null
    }

    @Synchronized
    fun close() {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        if (state == State.READY || state == State.LOADING) state = if (modelFile != null) State.FOUND else State.NONE
    }

    companion object {
        const val MODEL_BIG = "gemma-4-E4B-it.litertlm"
        const val MODEL_SMALL = "gemma-4-E2B-it.litertlm"
    }
}
