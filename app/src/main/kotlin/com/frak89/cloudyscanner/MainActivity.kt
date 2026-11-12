package com.frak89.cloudyscanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.frak89.cloudyscanner.ai.Director
import com.frak89.cloudyscanner.ai.KnowledgeBuffer
import com.frak89.cloudyscanner.ai.LocalAi
import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.gl.HudState
import com.frak89.cloudyscanner.gl.SceneRenderer
import com.frak89.cloudyscanner.scan.CameraInfo
import com.frak89.cloudyscanner.scan.ScanEngine
import com.frak89.cloudyscanner.ui.HudView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MainActivity : Activity(), Director.Ui {
    private val map = PointMap()
    private val hud = HudState()
    private lateinit var engine: ScanEngine
    private lateinit var renderer: SceneRenderer
    private lateinit var meshes: MeshWorker
    private lateinit var eraser: Eraser
    private lateinit var kb: KnowledgeBuffer
    private lateinit var ai: LocalAi
    private lateinit var director: Director

    private lateinit var glView: GLSurfaceView
    private lateinit var hudView: HudView
    private lateinit var overlay: FrameLayout
    private lateinit var stats: TextView
    private lateinit var banner: TextView
    private lateinit var scanBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var eraseBtn: TextView
    private lateinit var optionsPanel: ScrollView
    private lateinit var aiPanel: LinearLayout
    private lateinit var aiStatus: TextView
    private lateinit var aiLog: TextView
    private lateinit var aiLive: TextView
    private lateinit var aiScroll: ScrollView
    private lateinit var eraseBar: LinearLayout
    private lateinit var eraseCount: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val refreshers = ArrayList<() -> Unit>()
    private var arMessage: String? = "Avvio…"
    private var lastRealtimeNs = 0L
    private var downloadId = -1L
    private var aiBusy = false
    private var d = 1f

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = ScanEngine(this, map)
        renderer = SceneRenderer(engine, map, hud) { display?.rotation ?: 0 }
        meshes = MeshWorker(map, renderer)
        eraser = Eraser(map, renderer)
        kb = KnowledgeBuffer(this).also { it.load() }
        ai = LocalAi(this)
        director = Director(map, engine, meshes, renderer, ai, kb, eraser, this)
        if (kb.bgStride in 1..3) engine.bgStride = kb.bgStride
        if (kb.runs > 0) { Opt.meshLevel = kb.preferredLevel; Opt.smoothIters = kb.smooth }

        buildUi()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            arMessage = "Serve il permesso fotocamera"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            arMessage = engine.resume()
        }
        glView.onResume()
        if (ai.state == LocalAi.State.NONE) { ai.autoSelect(); aiStatus.text = ai.status }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        engine.pause()
        scanBtn.text = "●  Scan"
        kb.recordPerf(engine.integMs, engine.bgStride)
        kb.save()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        director.stop()
        meshes.shutdown()
        engine.destroy()
        thread { ai.close() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            arMessage = "Permesso fotocamera negato: abilitalo nelle impostazioni dell'app"
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Float) = (v * d).toInt()

    private fun rounded(color: Int, radius: Float = 14f) = GradientDrawable().apply { setColor(color); cornerRadius = radius * d }

    private fun pill(label: String, color: Int = BTN, onClick: (TextView) -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(TEXT)
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = rounded(color, 22f)
        setPadding(dp(16f), 0, dp(16f), 0)
        minWidth = dp(64f)
        isClickable = true
        setOnClickListener { onClick(this) }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(44f)).apply { marginEnd = dp(8f) }
    }

    private fun header(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title.uppercase(Locale.ITALY)
            setTextColor(ACCENT); textSize = 11f; letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(14f), 0, dp(4f))
        })
    }

    private fun sw(parent: LinearLayout, label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = label; s.setTextColor(TEXT); s.textSize = 14f
        s.isChecked = get()
        s.setOnCheckedChangeListener { _, v -> if (v != get()) set(v) }
        parent.addView(s, LinearLayout.LayoutParams(MATCH_PARENT, dp(42f)))
        refreshers.add { if (s.isChecked != get()) s.isChecked = get() }
    }

    private fun slider(parent: LinearLayout, label: String, min: Float, max: Float, get: () -> Float, fmt: (Float) -> String, set: (Float) -> Unit) {
        val t = TextView(this).apply { setTextColor(TEXT); textSize = 13f; setPadding(0, dp(8f), 0, 0) }
        val sb = SeekBar(this)
        sb.max = 1000
        fun show() { t.text = "$label: ${fmt(get())}" }
        sb.progress = ((get() - min) / (max - min) * 1000f).toInt()
        show()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { set(min + (max - min) * p / 1000f); show() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(t)
        parent.addView(sb, LinearLayout.LayoutParams(MATCH_PARENT, dp(34f)))
        refreshers.add { sb.progress = ((get() - min) / (max - min) * 1000f).toInt(); show() }
    }

    private fun choice(parent: LinearLayout, labels: Array<String>, get: () -> Int, set: (Int) -> Unit) {
        val row = LinearLayout(this)
        val views = ArrayList<TextView>()
        fun paint() { views.forEachIndexed { i, v -> v.background = rounded(if (i == get()) ACCENT_DIM else BTN, 10f) } }
        labels.forEachIndexed { i, l ->
            val v = TextView(this).apply {
                text = l; setTextColor(TEXT); textSize = 13f; gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setOnClickListener { set(i); paint() }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginEnd = if (i < labels.size - 1) dp(6f) else 0 })
        }
        paint()
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4f) })
        refreshers.add { paint() }
    }

    private fun action(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = label; setTextColor(TEXT); textSize = 14f; gravity = Gravity.CENTER
            background = rounded(BTN, 10f)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(40f)).apply { topMargin = dp(8f) })
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        hudView = HudView(this, hud, engine)
        root.addView(hudView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Stats, top-left
        stats = TextView(this).apply {
            setTextColor(TEXT); textSize = 11f; typeface = Typeface.MONOSPACE
            background = rounded(PANEL, 10f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setLineSpacing(dp(2f).toFloat(), 1f)
        }
        overlay.addView(stats, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(8f), dp(8f), 0, 0) })

        // Banner, bottom-centre above the bar
        banner = TextView(this).apply {
            setTextColor(0xFF14161C.toInt()); textSize = 14f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(WARN, 18f)
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            visibility = View.GONE
        }
        overlay.addView(banner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(12f), 0, dp(12f), dp(64f)) })

        // Bottom bar
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8f), 0, 0, 0) }
        scanBtn = pill("●  Scan", REC) { toggleScan() }
        viewBtn = pill("Vista: AR") { setOrbit(!Opt.orbit) }
        eraseBtn = pill("Cancella") { setErase(!Opt.eraseMode) }
        bar.addView(scanBtn)
        bar.addView(viewBtn)
        bar.addView(pill("Mesh ▾") { showMeshMenu(it) })
        bar.addView(eraseBtn)
        bar.addView(pill("IA") { toggle(aiPanel, optionsPanel) })
        bar.addView(pill("Fotocamere") { showCameras() })
        bar.addView(pill("Opzioni") { toggle(optionsPanel, aiPanel) })
        val barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
        overlay.addView(barScroll, FrameLayout.LayoutParams(MATCH_PARENT, dp(52f), Gravity.BOTTOM).apply { bottomMargin = dp(6f) })

        // Erase bar
        eraseBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL, 22f)
            setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
            visibility = View.GONE
        }
        eraseCount = TextView(this).apply { setTextColor(PURPLE); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setPadding(0, 0, dp(10f), 0) }
        eraseBar.addView(eraseCount)
        eraseBar.addView(pill("Elimina", PURPLE_DIM) { deleteSelection() })
        eraseBar.addView(pill("Deseleziona") { eraser.clearSelection(); updateEraseCount() })
        eraseBar.addView(pill("Annulla") {
            val n = eraser.undoLast()
            toast(if (n > 0) "Ripristinati ${fmt(n)} punti" else "Niente da annullare")
            if (n > 0) remeshIfAny()
        })
        val brush = SeekBar(this).apply {
            max = 100; progress = ((Opt.brush - 14f) / 106f * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { Opt.brush = 14f + 106f * p / 100f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        eraseBar.addView(brush, LinearLayout.LayoutParams(dp(110f), dp(40f)))
        overlay.addView(eraseBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8f), dp(8f), 0) })

        buildOptions()
        buildAiPanel()

        root.setOnApplyWindowInsetsListener { _, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            overlay.setPadding(i.left, i.top, i.right, i.bottom)
            hudView.insetLeft = i.left; hudView.insetTop = i.top; hudView.insetRight = i.right; hudView.insetBottom = i.bottom
            ins
        }
        setContentView(root)
        setupTouch()
    }

    private fun buildOptions() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(4f), dp(14f), dp(16f)) }

        header(col, "Vista")
        sw(col, "Fotocamera sullo sfondo", { Opt.showCamera }) { Opt.showCamera = it }
        sw(col, "Analisi contorni (edge)", { Opt.edgeOverlay }) { Opt.edgeOverlay = it; if (it) { Opt.showCamera = true; refreshControls() } }
        sw(col, "Nuvola di punti", { Opt.showPoints }) { Opt.showPoints = it }
        sw(col, "Mesh solida", { Opt.showSolid }) { Opt.showSolid = it }
        sw(col, "Wireframe", { Opt.showWire }) { Opt.showWire = it }
        choice(col, arrayOf("Quads", "Triangoli"), { if (Opt.wireQuads) 0 else 1 }) { Opt.wireQuads = it == 0 }
        sw(col, "Backface culling", { Opt.cull }) { Opt.cull = it }
        sw(col, "Illuminazione", { Opt.lit }) { Opt.lit = it }
        sw(col, "Flat shading", { Opt.flat }) { Opt.flat = it }
        sw(col, "Colori dei vertici", { Opt.vertexColor }) { Opt.vertexColor = it }
        sw(col, "X-ray (trasparenza)", { Opt.xray }) { Opt.xray = it }
        sw(col, "Griglia a terra", { Opt.grid }) { Opt.grid = it }
        sw(col, "Bounding box", { Opt.bbox }) { Opt.bbox = it }
        sw(col, "Assi XYZ", { Opt.axes }) { Opt.axes = it }
        sw(col, "Proiezione ortografica (orbita)", { Opt.ortho }) { Opt.ortho = it }
        slider(col, "Campo visivo (orbita)", 25f, 100f, { Opt.fov }, { "${it.toInt()}°" }) { Opt.fov = it }

        header(col, "Punti")
        choice(col, arrayOf("Bianco", "Colore", "Affidab."), { if (Opt.colorMode < 3) Opt.colorMode else -1 }) { Opt.colorMode = it }
        choice(col, arrayOf("Distanza", "Altezza", "Normali"), { if (Opt.colorMode >= 3) Opt.colorMode - 3 else -1 }) { Opt.colorMode = it + 3; refreshControls() }
        slider(col, "Dimensione", 1f, 16f, { Opt.pointSize }, { String.format(Locale.US, "%.1f px", it) }) { Opt.pointSize = it }
        sw(col, "Dimensione prospettica", { Opt.perspPoints }) { Opt.perspPoints = it }

        header(col, "Animazione (vista Orbita)")
        sw(col, "Rotazione automatica", { Opt.autoRotate }) { Opt.autoRotate = it; if (it && !Opt.orbit) setOrbit(true) }
        slider(col, "Velocità", -90f, 90f, { Opt.rotSpeed }, { "${it.toInt()}°/s" }) { Opt.rotSpeed = it }
        sw(col, "Onda sui punti", { Opt.wave }) { Opt.wave = it }
        sw(col, "Pulsazione", { Opt.pulse }) { Opt.pulse = it; if (it && !Opt.orbit) setOrbit(true) }
        action(col, "Ricentra la vista") { renderer.recenter(); renderer.turnAngle = 0f }

        header(col, "Scansione")
        sw(col, "Guida sulle zone incerte", { Opt.guidance }) { Opt.guidance = it }
        sw(col, "Evidenzia il soggetto", { Opt.subjectHighlight }) { Opt.subjectHighlight = it }
        sw(col, "Riadatta se il soggetto si muove", { Opt.adaptMotion }) { Opt.adaptMotion = it }
        slider(col, "Portata massima", 0.5f, 8f, { Opt.maxRange }, { String.format(Locale.US, "%.1f m", it) }) { Opt.maxRange = it }
        slider(col, "Affidabilità minima", 0.05f, 0.9f, { Opt.minConf }, { "${(it * 100).toInt()}%" }) { Opt.minConf = it }
        slider(col, "Raggio del soggetto", 0.15f, 2f, { Opt.subjRadius }, { String.format(Locale.US, "%.2f m", it) }) { Opt.subjRadius = it }
        action(col, "Sgancia il soggetto") { engine.unlockSubject() }

        header(col, "Ricostruzione")
        choice(col, arrayOf("Low", "Mid", "High"), { Opt.meshLevel }) { rebuild(it) }
        slider(col, "Smoothing", 0f, 3f, { Opt.smoothIters.toFloat() }, { "${Math.round(it)}×" }) { Opt.smoothIters = Math.round(it) }
        sw(col, "Ricostruzione in tempo reale", { Opt.realtimeMesh }) { Opt.realtimeMesh = it }

        optionsPanel = ScrollView(this).apply { background = rounded(PANEL, 16f); addView(col); visibility = View.GONE }
        overlay.addView(optionsPanel, FrameLayout.LayoutParams(dp(300f), MATCH_PARENT, Gravity.END).apply { setMargins(0, dp(8f), dp(8f), dp(64f)) })
    }

    private fun buildAiPanel() {
        aiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL, 16f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            visibility = View.GONE
        }
        aiPanel.addView(TextView(this).apply {
            text = "IA LOCALE · AULA VIRTUALE"; setTextColor(ACCENT); textSize = 11f; letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        aiStatus = TextView(this).apply { setTextColor(MUTED); textSize = 12f; text = ai.status; setPadding(0, dp(2f), 0, dp(6f)) }
        aiPanel.addView(aiStatus)
        val row = LinearLayout(this)
        row.addView(pill("Modello") { showModelDialog() })
        row.addView(pill("Analizza scena") { analyseScene() })
        row.addView(pill("▶ Aula virtuale", ACCENT_DIM) { startClassroom() })
        row.addView(pill("Memoria") { showMemory() })
        aiPanel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48f)))
        aiLog = TextView(this).apply { setTextColor(TEXT); textSize = 13f; setLineSpacing(dp(3f).toFloat(), 1f); setTextIsSelectable(true) }
        aiLive = TextView(this).apply { setTextColor(ACCENT); textSize = 13f; setTypeface(null, Typeface.ITALIC); visibility = View.GONE }
        val logCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(aiLog); addView(aiLive) }
        aiScroll = ScrollView(this).apply { addView(logCol) }
        aiPanel.addView(aiScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        aiLog.text = "Qui l'IA spiega passo passo cosa fa mentre ripulisce e ricostruisce la scena.\nFunziona anche senza modello, con le regole apprese."
        overlay.addView(aiPanel, FrameLayout.LayoutParams(dp(360f), MATCH_PARENT, Gravity.START).apply { setMargins(dp(8f), dp(84f), 0, dp(64f)) })
    }

    private fun toggle(show: View, hide: View) {
        hide.visibility = View.GONE
        show.visibility = if (show.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (show.visibility == View.VISIBLE) refreshControls()
    }

    override fun refreshControls() {
        ui.post {
            refreshers.forEach { it() }
            viewBtn.text = if (Opt.orbit) "Vista: Orbita" else "Vista: AR"
            scanBtn.text = if (engine.scanning) "■  Stop" else "●  Scan"
            eraseBtn.background = rounded(if (Opt.eraseMode) PURPLE_DIM else BTN, 22f)
            eraseBar.visibility = if (Opt.eraseMode) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------ actions

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun fmt(n: Int) = String.format(Locale.ITALY, "%,d", n)

    private fun toggleScan() {
        if (director.running) { toast("L'aula virtuale è in corso"); return }
        if (!engine.scanning) {
            if (arMessage != null) { arMessage = engine.resume(); if (arMessage != null) { toast(arMessage!!); return } }
            if (Opt.orbit && map.size == 0) setOrbit(false)
            engine.scanning = true
        } else {
            engine.scanning = false
            kb.recordPerf(engine.integMs, engine.bgStride)
            kb.save()
            if (meshes.mesh == null && map.size > 500 && !Opt.realtimeMesh) toast("Scansione in pausa. Mesh ▾ per ricostruire, Vista per orbitare.")
        }
        refreshControls()
    }

    private fun setOrbit(on: Boolean) {
        Opt.orbit = on
        if (on) renderer.recenter()
        refreshControls()
    }

    private fun setErase(on: Boolean) {
        Opt.eraseMode = on
        if (!on) hudView.brushX = -1f
        if (on) { updateEraseCount(); toast("Passa il dito sulle parti da eliminare: diventano viola") }
        refreshControls()
    }

    private fun updateEraseCount() { eraseCount.text = "${fmt(eraser.selectedCount())} selezionati" }

    private fun deleteSelection() {
        val n = eraser.deleteSelected()
        updateEraseCount()
        if (n == 0) { toast("Nessun punto selezionato"); return }
        toast("Eliminati ${fmt(n)} punti")
        remeshIfAny()
    }

    private fun remeshIfAny() {
        if (meshes.mesh != null && meshes.level >= 0) meshes.request(meshes.level, Opt.smoothIters, 0, 0f)
    }

    private fun rebuild(level: Int) {
        Opt.meshLevel = level
        if (map.size < 100) { toast("Prima acquisisci dei punti con Scan"); return }
        meshes.request(level, Opt.smoothIters, 0, 1.2f) { m ->
            if (m != null) ui.post { toast("${Opt.levelName[level]}: ${fmt(m.vertexCount)} vertici · ${fmt(m.quadCount)} quads · ${fmt(m.triangleCount)} triangoli") }
        }
        refreshControls()
    }

    private fun showMeshMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val items = arrayOf(
            "Ricostruisci LOW poly", "Ricostruisci MID poly", "Ricostruisci HIGH poly",
            if (Opt.realtimeMesh) "Tempo reale: ON → spegni" else "Tempo reale: OFF → accendi",
            "Rimuovi mesh", "Esporta mesh OBJ (quads)", "Esporta mesh OBJ (triangoli)", "Esporta nuvola PLY", "Nuova scansione (azzera tutto)",
        )
        items.forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                0, 1, 2 -> rebuild(it.itemId)
                3 -> { Opt.realtimeMesh = !Opt.realtimeMesh; refreshControls() }
                4 -> meshes.clear()
                5, 6 -> export { val m = meshes.mesh; if (m == null) null else Storage.exportObj(this, m, it.itemId == 5) }
                7 -> export {
                    map.lock.lock()
                    try { if (map.size == 0) null else Storage.exportPly(this, map) } finally { map.lock.unlock() }
                }
                8 -> AlertDialog.Builder(this).setMessage("Cancellare punti e mesh della scansione corrente?")
                    .setPositiveButton("Azzera") { _, _ -> engine.reset(); meshes.clear(); updateEraseCount() }
                    .setNegativeButton("Annulla", null).show()
            }
            true
        }
        pm.show()
    }

    private fun export(write: () -> File?) {
        val go = {
            thread {
                val msg = try {
                    val f = write()
                    if (f == null) "Niente da esportare" else "Salvato: ${f.absolutePath}"
                } catch (t: Throwable) { "Esportazione fallita: ${t.message}" }
                ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
            Unit
        }
        if (Storage.hasAllFiles()) go()
        else AlertDialog.Builder(this)
            .setMessage("Per salvare in Download/CloudyScanner (cartella che resta anche se disinstalli l'app) serve il permesso \"Accesso a tutti i file\".")
            .setPositiveButton("Concedi") { _, _ -> Storage.requestAllFiles(this) }
            .setNegativeButton("Salva nella cartella dell'app") { _, _ -> go() }
            .show()
    }

    private fun showCameras() {
        val live = StringBuilder()
        live.append("IN QUESTO MOMENTO\n")
        live.append(String.format(Locale.US, "  Focale %.2f mm · f/%.2f · ISO %d · posa %.1f ms\n", engine.focalLength, engine.aperture, engine.iso, engine.exposureMs))
        live.append(if (engine.focusDistance > 0f) String.format(Locale.US, "  Distanza di fuoco (autofocus laser/PDAF): %.2f m\n", engine.focusDistance) else "  Distanza di fuoco: all'infinito o non disponibile\n")
        live.append(if (engine.centerDistance > 0f) String.format(Locale.US, "  Profondità ARCore al centro: %.2f m\n", engine.centerDistance) else "  Profondità ARCore al centro: —\n")
        live.append("  Mappa di profondità: ${engine.depthSize.ifEmpty { "—" }}\n\n")
        live.append("MODULI FOTOCAMERA\n")
        live.append(CameraInfo.describe(this, engine.cameraId))
        val tv = TextView(this).apply { text = live; setTextColor(TEXT); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dp(18f), dp(12f), dp(18f), dp(12f)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Fotocamere del telefono").setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Chiudi", null).show()
    }

    // ------------------------------------------------------------------ AI

    override fun say(line: String) {
        ui.post {
            aiLog.append("\n\n" + line)
            aiScroll.post { aiScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun stream(text: String?) {
        ui.post {
            if (text == null) aiLive.visibility = View.GONE
            else { aiLive.visibility = View.VISIBLE; aiLive.text = text; aiScroll.post { aiScroll.fullScroll(View.FOCUS_DOWN) } }
            aiStatus.text = ai.status
        }
    }

    override fun finished() { ui.post { aiBusy = false; aiStatus.text = ai.status; refreshControls() } }

    private fun startClassroom() {
        if (aiBusy || director.running) { toast("L'IA sta già lavorando"); return }
        if (map.size < 200) { toast("Prima acquisisci dei punti con Scan"); return }
        aiBusy = true
        aiLog.text = "AULA VIRTUALE"
        director.start(true)
    }

    private fun analyseScene() {
        if (aiBusy || director.running) { toast("L'IA sta già lavorando"); return }
        aiBusy = true
        thread(name = "ai-analyse") {
            try {
                if (!(ai.state == LocalAi.State.READY || ai.autoSelect())) { say("Nessun modello: tocca «Modello» per sceglierlo o scaricarlo."); return@thread }
                stream(ai.status)
                if (!ai.ensureLoaded()) { stream(null); say(ai.status); return@thread }
                stream("Preparo l'immagine…")
                val fromCamera = !Opt.orbit
                val image = if (fromCamera) grabCamera() ?: grabSnapshot() else grabSnapshot()
                val s = engine.subject
                val p = StringBuilder()
                p.append(if (fromCamera) "L'immagine è il fotogramma attuale della fotocamera.\n" else "L'immagine è la nuvola di punti vista dall'esterno.\n")
                p.append("Punti acquisiti: ${map.size}. Punti poco affidabili: ${engine.uncertainCount}. Zone già ripassate: ${engine.guidance.resolved}.\n")
                if (s.valid) p.append(String.format(Locale.US, "Soggetto a %.2f m%s.\n", s.distance, if (s.moving) ", in movimento" else ""))
                if (engine.focusDistance > 0f) p.append(String.format(Locale.US, "Autofocus a %.2f m. ", engine.focusDistance))
                p.append(String.format(Locale.US, "Ottica %.1f mm f/%.1f, ISO %d, posa %.0f ms.\n", engine.focalLength, engine.aperture, engine.iso, engine.exposureMs))
                val mem = kb.promptContext()
                if (mem.isNotEmpty()) p.append(mem).append('\n')
                val text = ai.generate(SYSTEM_ANALYSE, p.toString(), listOfNotNull(image), 380) { stream(it) }
                stream(null)
                say("IA: " + text.trim())
                text.lines().firstOrNull { it.trim().uppercase().startsWith("NOTA") }?.let { kb.addNote(it.substringAfter(':').trim()); kb.save() }
            } catch (t: Throwable) {
                stream(null); say("Analisi non riuscita: ${t.message ?: t.javaClass.simpleName}")
            } finally { finished() }
        }
    }

    private fun grabSnapshot(): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        renderer.snapshotRequest = { out = it; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun grabCamera(): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        engine.jpegRequest = { out = it; latch.countDown() }
        if (!latch.await(2, TimeUnit.SECONDS)) { engine.jpegRequest = null; return null }
        val jpeg = out ?: return null
        // The sensor image is landscape; turn it upright for the way the phone is held.
        val deg = when (display?.rotation ?: 0) { 0 -> 90f; 2 -> 270f; 3 -> 180f; else -> 0f }
        if (deg == 0f) return jpeg
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
            ByteArrayOutputStream().also { rot.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } catch (t: Throwable) { jpeg }
    }

    private fun showMemory() {
        val tv = TextView(this).apply { text = kb.describe(); setTextColor(TEXT); textSize = 13f; setPadding(dp(18f), dp(12f), dp(18f), dp(12f)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Memoria dell'IA").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Svuota") { _, _ -> kb.clear(); toast("Memoria svuotata") }
            .show()
    }

    private fun showModelDialog() {
        if (!Storage.hasAllFiles()) {
            AlertDialog.Builder(this)
                .setTitle("Accesso alla cartella Download")
                .setMessage("Il modello IA resta in Download, così non viene cancellato se disinstalli l'app. Per trovarlo e leggerlo serve il permesso \"Accesso a tutti i file\".")
                .setPositiveButton("Concedi") { _, _ -> Storage.requestAllFiles(this) }
                .setNegativeButton("Annulla", null).show()
            return
        }
        val found = ai.findModels()
        val labels = ArrayList<String>()
        found.forEach { labels.add((if (it.absolutePath == ai.modelFile?.absolutePath) "✔ " else "") + it.name + String.format(Locale.US, "  (%.2f GB)", it.length() / 1e9)) }
        labels.add("⬇ Scarica Gemma 4 E4B — il più capace (3,65 GB)")
        labels.add("⬇ Scarica Gemma 4 E2B — più leggero e veloce (2,58 GB)")
        AlertDialog.Builder(this)
            .setTitle(if (found.isEmpty()) "Nessun modello in Download" else "Modelli trovati in Download")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < found.size -> { ai.select(found[i]); aiStatus.text = ai.status; toast("Modello selezionato") }
                    else -> startDownload(i == found.size)
                }
            }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun startDownload(big: Boolean) {
        val name = if (big) LocalAi.MODEL_BIG else LocalAi.MODEL_SMALL
        if (File(Storage.downloads(), name).exists()) { toast("$name è già in Download"); ai.autoSelect(); aiStatus.text = ai.status; return }
        try {
            downloadId = ai.startDownload(big)
            aiStatus.text = "Download di $name avviato…"
            ui.postDelayed(pollDownload, 1500)
        } catch (t: Throwable) { toast("Download non avviato: ${t.message}") }
    }

    private val pollDownload = object : Runnable {
        override fun run() {
            val p = ai.downloadProgress(downloadId) ?: return
            when (p[2].toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    ai.autoSelect()
                    ai.findModels().firstOrNull()?.let { ai.select(it) }
                    aiStatus.text = "Download completato · " + ai.status
                }
                DownloadManager.STATUS_FAILED -> aiStatus.text = "Download fallito: riprova o scarica il file .litertlm a mano in Download"
                else -> {
                    aiStatus.text = if (p[1] > 0) String.format(Locale.US, "Download modello: %.2f / %.2f GB (%d%%)", p[0] / 1e9, p[1] / 1e9, p[0] * 100 / p[1]) else "Download modello in corso…"
                    ui.postDelayed(this, 1500)
                }
            }
        }
    }

    // ------------------------------------------------------------------ periodic UI refresh

    private val tick = object : Runnable {
        override fun run() {
            val sb = StringBuilder()
            val s = engine.subject
            sb.append(if (engine.scanning) "● REC  " else "       ")
            sb.append("Punti ${fmt(hud.points)}   ${hud.fps.toInt()} fps")
            if (engine.framesFused > 0) sb.append(String.format(Locale.US, "   fusione %.0f ms · %.0f Hz · passo %d", engine.integMs, engine.depthFps, engine.bgStride))
            sb.append('\n')
            val m = meshes.mesh
            if (meshes.busy) sb.append("Mesh: ricostruzione ${(meshes.progress * 100).toInt()}%")
            else if (m != null) sb.append("Mesh ${Opt.levelName[meshes.level.coerceIn(0, 2)]}  V ${fmt(m.vertexCount)}  Q ${fmt(m.quadCount)}  T ${fmt(m.triangleCount)}  (${meshes.lastMs.toInt()} ms)")
            else sb.append("Mesh: —")
            sb.append('\n')
            sb.append(if (s.valid) String.format(Locale.US, "Soggetto %.2f m", s.distance) else "Soggetto —")
            if (s.locked) sb.append(if (s.moving) String.format(Locale.US, " ⟲ %.2f m/s", s.speed) else " ⌖")
            sb.append(if (engine.focusDistance > 0f) String.format(Locale.US, "   AF/laser %.2f m", engine.focusDistance) else "   AF/laser ∞")
            if (engine.focalLength > 0f) sb.append(String.format(Locale.US, "   %.1f mm f/%.2f ISO %d", engine.focalLength, engine.aperture, engine.iso))
            sb.append('\n')
            sb.append("Incerti ${fmt(engine.uncertainCount)} · ripassati ${engine.guidance.resolved} · ${engine.trackingText}")
            stats.text = sb

            val msg = when {
                arMessage != null -> arMessage
                !engine.tracking -> engine.trackingText
                director.running -> null
                s.locked && s.moving -> "Soggetto in movimento: ricalcolo la posizione…"
                engine.scanning && Opt.guidance && engine.guidance.active -> "Zona incerta: torna sul punto arancione e inquadralo lentamente"
                !engine.scanning && hud.points == 0 -> "Tocca ● Scan e gira lentamente attorno al soggetto · tocca lo schermo per agganciarlo"
                else -> null
            }
            if (msg == null) banner.visibility = View.GONE
            else {
                banner.text = msg; banner.visibility = View.VISIBLE
                banner.background = rounded(if (arMessage != null || !engine.tracking) 0xFFFF6B5E.toInt() else if (engine.guidance.active && engine.scanning) WARN else 0xFFE8EAF0.toInt(), 18f)
            }

            val now = System.nanoTime()
            if (Opt.realtimeMesh && engine.scanning && !meshes.busy && map.version != meshes.builtVersion && now - lastRealtimeNs > 900_000_000L && hud.points > 300) {
                lastRealtimeNs = now
                meshes.request(Opt.meshLevel, 0, 0, 0f)
            }
            ui.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ touch

    private var lastX = 0f; private var lastY = 0f
    private var pinch = 0f
    private var midX = 0f; private var midY = 0f
    private var lastPaintNs = 0L

    private fun setupTouch() {
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!Opt.orbit && !Opt.eraseMode) {
                    if (engine.lockSubjectAt(e.x, e.y)) toast("Soggetto agganciato · tieni premuto per sganciare")
                    else toast("Nessuna profondità in quel punto")
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!Opt.orbit && !Opt.eraseMode && engine.subject.locked) { engine.unlockSubject(); toast("Soggetto sganciato") }
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (Opt.orbit) { renderer.recenter(); renderer.turnAngle = 0f }
                return true
            }
        })
        glView.setOnTouchListener { v, e ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y
                    if (Opt.eraseMode) paintAt(e.x, e.y, true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    pinch = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    midX = (e.getX(0) + e.getX(1)) / 2; midY = (e.getY(0) + e.getY(1)) / 2
                    hudView.brushX = -1f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val dd = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                        if (Opt.orbit && pinch > 10f && dd > 10f) {
                            renderer.dist = (renderer.dist * pinch / dd).coerceIn(0.15f, 60f)
                            val k = renderer.dist / v.height
                            val yaw = renderer.yaw
                            val dx = -(mx - midX) * k; val dy = (my - midY) * k
                            renderer.target[0] += dx * cos(yaw); renderer.target[2] += -dx * sin(yaw); renderer.target[1] += dy
                        }
                        pinch = dd; midX = mx; midY = my
                    } else if (Opt.eraseMode) {
                        paintAt(e.x, e.y, false)
                    } else if (Opt.orbit) {
                        renderer.yaw -= (e.x - lastX) * 0.006f
                        renderer.pitch = (renderer.pitch + (e.y - lastY) * 0.006f).coerceIn(-1.5f, 1.5f)
                    }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Continue with the finger that stays down without a jump.
                    val keep = if (e.actionIndex == 0) 1 else 0
                    if (keep < e.pointerCount) { lastX = e.getX(keep); lastY = e.getY(keep) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (Opt.eraseMode) updateEraseCount() }
            }
            true
        }
    }

    private fun paintAt(x: Float, y: Float, force: Boolean) {
        hudView.brushX = x; hudView.brushY = y
        val now = System.nanoTime()
        if (!force && now - lastPaintNs < 30_000_000L) return
        lastPaintNs = now
        eraser.paint(x, y, Opt.brush * d)
    }

    companion object {
        const val TEXT = 0xFFE8EAF0.toInt()
        const val MUTED = 0xFF9BA1B0.toInt()
        const val PANEL = 0xE6191B22.toInt()
        const val BTN = 0xE62B2E39.toInt()
        const val ACCENT = 0xFF4DE6FF.toInt()
        const val ACCENT_DIM = 0xFF1F6F85.toInt()
        const val PURPLE = 0xFFC77DFF.toInt()
        const val PURPLE_DIM = 0xFF7B2FBF.toInt()
        const val REC = 0xFFD9392B.toInt()
        const val WARN = 0xFFFF9E1A.toInt()

        const val SYSTEM_ANALYSE = "Sei l'assistente di scansione 3D dell'app Cloudy Scanner su un telefono. Ricevi un'immagine e alcuni dati " +
            "della scansione in corso. Rispondi in italiano, in modo concreto, in 4-6 frasi: che cos'è il soggetto, quali superfici " +
            "saranno difficili da ricostruire (lucide, trasparenti, scure, uniformi, sottili), da quali lati e a che distanza conviene " +
            "ripassare. Chiudi con una riga che inizia con NOTA: e contiene una lezione breve, riutilizzabile nelle prossime scansioni."
    }
}
