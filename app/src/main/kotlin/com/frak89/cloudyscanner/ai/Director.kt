package com.frak89.cloudyscanner.ai

import com.frak89.cloudyscanner.Eraser
import com.frak89.cloudyscanner.MeshWorker
import com.frak89.cloudyscanner.Opt
import com.frak89.cloudyscanner.core.Clusters
import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.gl.SceneRenderer
import com.frak89.cloudyscanner.scan.ScanEngine
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Virtual classroom": cleans and rebuilds the scan step by step while you watch, explaining
 * each move. The plan comes from the local model when one is loaded, otherwise from rules.
 * Geometry is always done by the deterministic tools; the model only chooses and explains.
 */
class Director(
    private val map: PointMap,
    private val engine: ScanEngine,
    private val meshes: MeshWorker,
    private val renderer: SceneRenderer,
    private val ai: LocalAi,
    private val kb: KnowledgeBuffer,
    private val eraser: Eraser,
    private val ui: Ui,
) {
    interface Ui {
        fun say(line: String)
        /** Live text of the answer being generated; null removes the live block. */
        fun stream(text: String?)
        fun refreshControls()
        fun finished()
    }

    class Cmd(val op: String, val arg: String)

    @Volatile var running = false
        private set
    private var thread: Thread? = null

    fun start(useAi: Boolean) {
        if (running) return
        running = true
        thread = Thread({
            try { run(useAi) } catch (_: InterruptedException) { ui.say("Interrotto.") } catch (t: Throwable) { ui.say("Errore: ${t.message}") }
            finally { running = false; ui.stream(null); ui.finished() }
        }, "director").also { it.start() }
    }

    fun stop() { thread?.interrupt() }

    private fun pause(ms: Long) { Thread.sleep(ms) }

    private fun run(useAi: Boolean) {
        val tStart = System.nanoTime()
        engine.scanning = false
        Opt.eraseMode = false
        Opt.orbit = true; Opt.autoRotate = true; Opt.showPoints = true
        renderer.recenter()
        ui.refreshControls()

        // ---- 1. Look at the cloud
        val clusters = Clusters(map)
        val total: Int; var weak = 0
        val bounds = FloatArray(6)
        map.lock.lock()
        try {
            total = map.size
            if (total > 0) { clusters.compute(); map.bounds(bounds) }
            for (i in 0 until total) if (map.conf[i] < kb.lowConf) weak++
        } finally { map.lock.unlock() }
        if (total < 200) { ui.say("Servono più punti: fai prima una scansione."); return }
        val infos = clusters.infos
        ui.say("① Osservo la nuvola: ${fmt(total)} punti, ${infos.size} blocchi separati, ${weak * 100 / total}% di punti poco affidabili.")
        pause(900)

        // ---- 2. Decide
        val minPts = maxOf(30, (total * kb.minClusterFrac).toInt())
        val rule = ArrayList<Cmd>()
        for (c in infos) if (c.id != 0 && c.points < minPts && rule.size < 12) rule.add(Cmd("DELETE_CLUSTER", c.id.toString()))
        if (weak in 1 until total * 3 / 10) rule.add(Cmd("DELETE_LOWCONF", String.format(Locale.US, "%.2f", kb.lowConf)))
        rule.add(Cmd("LEVEL", Opt.levelName[kb.preferredLevel]))
        rule.add(Cmd("SMOOTH", kb.smooth.toString()))
        rule.add(Cmd("REMOVE_ISLANDS", kb.islands.toString()))

        var plan: List<Cmd> = rule
        var usedAi = false
        if (useAi && (ai.state == LocalAi.State.READY || ai.autoSelect())) {
            ui.say("② Chiedo al modello locale un piano di pulizia…")
            ui.stream(ai.status)
            if (ai.ensureLoaded()) {
                val sb = StringBuilder()
                sb.append("Nuvola di punti: $total punti. Ingombro ${f2(bounds[3] - bounds[0])} x ${f2(bounds[4] - bounds[1])} x ${f2(bounds[5] - bounds[2])} m.\n")
                sb.append("Punti poco affidabili (sotto ${f2(kb.lowConf)}): $weak.\nBlocchi (id: punti, affidabilita media, dimensione massima m):\n")
                for (c in infos.take(14)) sb.append("${c.id}: ${c.points}, ${f2(c.meanConf)}, ${f2(maxOf(c.size[0], c.size[1], c.size[2]))}\n")
                if (infos.size > 14) sb.append("... altri ${infos.size - 14} blocchi piccoli\n")
                val mem = kb.promptContext()
                if (mem.isNotEmpty()) sb.append(mem).append('\n')
                val quick = kb.experienced
                if (quick) {
                    sb.append("Piano proposto dalle regole apprese:\n")
                    rule.forEach { sb.append(it.op).append(' ').append(it.arg).append('\n') }
                    sb.append("Conferma o correggi il piano: una frase di spiegazione, poi COMANDI.")
                } else sb.append("L'immagine mostra la nuvola vista dall'esterno. Proponi il piano.")
                val shot = snapshot()
                try {
                    val text = ai.generate(SYSTEM, sb.toString(), listOfNotNull(shot), if (quick) 200 else 420) { ui.stream(it) }
                    ui.stream(null)
                    ui.say("IA: " + explanation(text))
                    val parsed = parse(text)
                    if (parsed.any { it.op != "NOTE" }) { plan = merge(parsed, rule); usedAi = true }
                    else ui.say("Il modello non ha dato comandi validi: uso le regole apprese.")
                } catch (t: Throwable) {
                    ui.stream(null)
                    ui.say("Modello non disponibile (${t.message ?: t.javaClass.simpleName}): uso le regole apprese.")
                }
            } else {
                ui.stream(null); ui.say(ai.status + " — uso le regole apprese.")
            }
        } else ui.say("② Piano dalle regole apprese (nessun modello IA caricato).")
        pause(700)

        // ---- 3. Clean, showing every deletion in purple first
        var level = kb.preferredLevel; var smooth = kb.smooth; var islands = kb.islands
        var usedLow: Float? = null
        var removed = 0
        for (c in plan) {
            if (Thread.interrupted()) throw InterruptedException()
            when (c.op) {
                "DELETE_CLUSTER" -> {
                    val id = c.arg.toIntOrNull() ?: continue
                    val info = infos.getOrNull(id) ?: continue
                    if (id == 0 || info.points > total * 3 / 10) { ui.say("   Blocco $id troppo grande: lo tengo."); continue }
                    select { clusters.clusterOf(it) == id }
                    ui.say("③ Evidenzio in viola il blocco $id (${fmt(info.points)} punti staccati dal resto)…")
                    pause(1300)
                    removed += eraser.deleteSelected()
                    pause(350)
                }
                "DELETE_LOWCONF" -> {
                    val t = (c.arg.replace(',', '.').toFloatOrNull() ?: continue).coerceIn(0.05f, 0.4f)
                    val n = select { map.conf[it] < t }
                    val left: Int
                    map.lock.lock(); try { left = map.size } finally { map.lock.unlock() }
                    if (n > left * 35 / 100) { eraser.clearSelection(); ui.say("   Troppi punti sotto ${f2(t)}: non li tocco."); continue }
                    ui.say("③ Evidenzio in viola ${fmt(n)} punti con affidabilità sotto ${f2(t)}…")
                    pause(1300)
                    removed += eraser.deleteSelected()
                    usedLow = t
                    pause(350)
                }
                "LEVEL" -> level = when (c.arg.uppercase()) { "LOW" -> 0; "HIGH" -> 2; else -> 1 }
                "SMOOTH" -> smooth = (c.arg.toIntOrNull() ?: smooth).coerceIn(0, 3)
                "REMOVE_ISLANDS" -> islands = (c.arg.toIntOrNull() ?: islands).coerceIn(0, 400)
                "NOTE" -> kb.addNote(c.arg)
            }
        }
        if (removed > 0) ui.say("   Rimossi ${fmt(removed)} punti (puoi annullare da Cancella → Annulla).")

        // ---- 4. Rebuild coarse to fine
        Opt.showSolid = true; Opt.showWire = true
        ui.refreshControls()
        if (level > 0) {
            ui.say("④ Abbozzo la forma in LOW poly…")
            meshes.buildBlocking(0, 0, 0, 1.4f)
            pause(1900)
        }
        ui.say("⑤ Ricostruisco in ${Opt.levelName[level]} poly" + (if (smooth > 0) ", smoothing ×$smooth" else "") + (if (islands > 0) ", via le isole sotto $islands facce" else "") + "…")
        val m = meshes.buildBlocking(level, smooth, islands, 2.8f)
        Opt.meshLevel = level; Opt.smoothIters = smooth
        ui.refreshControls()
        pause(3000)
        if (m != null) ui.say("✔ Fatto: ${fmt(m.vertexCount)} vertici · ${fmt(m.quadCount)} quads · ${fmt(m.triangleCount)} triangoli.")

        // ---- 5. Remember
        val secs = (System.nanoTime() - tStart) / 1e9f
        kb.recordRun(level, usedLow, smooth, islands, if (usedAi) secs else 0f)
        kb.recordPerf(engine.integMs, engine.bgStride)
        kb.save()
        ui.say("Memoria aggiornata: ${kb.runs} sessioni, ${kb.notes.size} note.")
    }

    private inline fun select(crossinline pred: (Int) -> Boolean): Int {
        var n = 0
        map.lock.lock()
        try {
            for (i in 0 until map.size) {
                val s = pred(i)
                map.setFlag(i, PointMap.SELECTED, s)
                if (s) n++
            }
        } finally { map.lock.unlock() }
        return n
    }

    private fun snapshot(): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        renderer.snapshotRequest = { out = it; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    /** Model commands first; anything it left unspecified falls back to the rule-based value. */
    private fun merge(aiPlan: List<Cmd>, rule: List<Cmd>): List<Cmd> {
        val out = ArrayList(aiPlan)
        for (op in arrayOf("LEVEL", "SMOOTH", "REMOVE_ISLANDS")) {
            if (aiPlan.none { it.op == op }) rule.firstOrNull { it.op == op }?.let { out.add(it) }
        }
        return out
    }

    private fun fmt(n: Int) = String.format(Locale.ITALY, "%,d", n)
    private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)

    companion object {
        const val SYSTEM = "Sei l'assistente di ricostruzione 3D dell'app Cloudy Scanner. Ricevi le statistiche di una nuvola di punti " +
            "acquisita con la fotocamera di un telefono e decidi come ripulirla e ricostruirla. Rispondi in italiano con al massimo " +
            "tre frasi di spiegazione, poi la riga COMANDI e sotto un comando per riga scelto tra:\n" +
            "DELETE_CLUSTER <id>  (elimina un blocco staccato; mai il blocco 0)\n" +
            "DELETE_LOWCONF <soglia tra 0.05 e 0.40>\n" +
            "LEVEL LOW|MID|HIGH\n" +
            "SMOOTH <0-3>\n" +
            "REMOVE_ISLANDS <numero minimo di facce, 0-200>\n" +
            "NOTE <una lezione breve e riutilizzabile per le prossime scansioni>\n" +
            "Non scrivere altro dopo i comandi."

        private val OPS = setOf("DELETE_CLUSTER", "DELETE_LOWCONF", "LEVEL", "SMOOTH", "REMOVE_ISLANDS", "NOTE")

        fun parse(text: String): List<Cmd> {
            val out = ArrayList<Cmd>()
            for (raw in text.lines()) {
                val line = raw.trim().trimStart('-', '*', '•', '`', ' ', '>').trimEnd('`').trim()
                val sp = line.indexOfFirst { it == ' ' || it == ':' || it == '\t' }
                val op = (if (sp < 0) line else line.substring(0, sp)).uppercase()
                if (op !in OPS) continue
                val arg = if (sp < 0) "" else line.substring(sp + 1).trim().trimStart(':', '<', ' ').trimEnd('>', '.', ' ')
                if (arg.isEmpty()) continue
                out.add(Cmd(op, if (op == "NOTE") arg else arg.split(' ', '(')[0]))
            }
            return out
        }

        /** The prose part of an answer, without the command block. */
        fun explanation(text: String): String {
            val keep = text.lines().takeWhile { l ->
                val t = l.trim().trimStart('-', '*', '`', ' ').uppercase()
                !(t.startsWith("COMANDI") || OPS.any { t.startsWith(it) })
            }.joinToString(" ") { it.trim() }.trim()
            return if (keep.isEmpty()) "(piano ricevuto)" else keep.take(500)
        }
    }
}
