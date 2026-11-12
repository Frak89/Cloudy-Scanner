package com.frak89.cloudyscanner.ai

/**
 * Continuous recognition: keeps showing the current camera frame to the local model and
 * turns its answers into a label, a view plan and, when it is unsure, a request to the user
 * ("show me the underside"). "Real time" here means one look every few seconds, as fast as
 * the phone can run the model.
 */
class LiveVision(
    private val ai: LocalAi,
    private val kb: KnowledgeBuffer,
    /** Current frame, cropped to the subject when one is selected. */
    private val grab: () -> ByteArray?,
    /** Short facts for the prompt (distance, missing views…). */
    private val facts: () -> String,
    /** True while another AI job is using the model. */
    private val busy: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onResult: (Recognition, Float) -> Unit,
) {
    @Volatile var running = false
        private set
    @Volatile var last = Recognition()
        private set
    private var thread: Thread? = null

    fun reset() { last = Recognition() }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "live-vision").also { it.start() }
    }

    fun stop() { running = false; thread?.interrupt() }

    private fun loop() {
        try {
            if (!(ai.state == LocalAi.State.READY || ai.autoSelect())) { onStatus("IA live: nessun modello. Tocca «Modello»."); return }
            onStatus(ai.status)
            if (!ai.ensureLoaded()) { onStatus(ai.status); return }
            onStatus("IA live attiva")
            var asked = ""
            while (running) {
                if (busy()) { Thread.sleep(700); continue }
                val img = grab()
                if (img == null) { Thread.sleep(700); continue }
                val p = StringBuilder(facts())
                val prev = last
                if (prev.known) {
                    p.append("\nPrima pensavi: ${prev.name} (sicurezza ${prev.confidence}).")
                    if (asked.isNotEmpty()) p.append(" Avevi chiesto: «$asked». Questa è la nuova inquadratura.")
                }
                val t0 = System.nanoTime()
                val text = try { ai.generate(SYSTEM, p.toString(), listOf(img), 90) { } } catch (t: Throwable) {
                    onStatus("IA live: ${t.message ?: t.javaClass.simpleName}"); Thread.sleep(3000); continue
                }
                val r = VisionParse.parse(text)
                // Do not let one shaky frame overwrite a confident answer.
                if (r.known && !(prev.known && prev.confidence >= 75 && r.confidence < prev.confidence - 25 && !r.name.equals(prev.name, true))) {
                    last = r
                    asked = r.ask
                    onResult(r, (System.nanoTime() - t0) / 1e9f)
                } else if (!prev.known && r.ask.isNotEmpty()) {
                    last = r; asked = r.ask
                    onResult(r, (System.nanoTime() - t0) / 1e9f)
                }
                Thread.sleep(1200)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            onStatus("IA live interrotta: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            running = false
        }
    }

    companion object {
        const val SYSTEM = "Sei il riconoscitore di oggetti dell'app Cloudy Scanner. Vedi un fotogramma dell'oggetto che l'utente sta " +
            "scansionando in 3D con il telefono. Rispondi SOLO con queste quattro righe, senza altro testo:\n" +
            "OGGETTO: <nome breve in italiano, oppure SCONOSCIUTO>\n" +
            "SICUREZZA: <numero da 0 a 100>\n" +
            "COMPLESSITA: SEMPLICE oppure MEDIA oppure COMPLESSA (quanto è articolata la forma da ricostruire)\n" +
            "RICHIESTA: <se non sei sicuro, UNA cosa precisa che l'utente deve mostrarti per riconoscerlo meglio: un lato, " +
            "un dettaglio, più vicino o più lontano; se sei sicuro scrivi NESSUNA>"
    }
}
