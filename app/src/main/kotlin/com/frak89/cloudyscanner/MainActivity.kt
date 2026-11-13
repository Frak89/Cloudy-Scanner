package com.frak89.cloudyscanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import android.widget.EditText
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
import com.frak89.cloudyscanner.ai.LiveVision
import com.frak89.cloudyscanner.ai.LocalAi
import com.frak89.cloudyscanner.ai.ObjectCrops
import com.frak89.cloudyscanner.ai.PosedImage
import com.frak89.cloudyscanner.ai.Recognition
import com.frak89.cloudyscanner.ai.VisionParse
import com.frak89.cloudyscanner.core.CatalogPart
import com.frak89.cloudyscanner.core.Palette
import com.frak89.cloudyscanner.core.PartLabel
import com.frak89.cloudyscanner.core.PointMap
import com.frak89.cloudyscanner.gl.HudState
import com.frak89.cloudyscanner.gl.SceneRenderer
import com.frak89.cloudyscanner.scan.CameraInfo
import com.frak89.cloudyscanner.scan.PhotoPlanner
import com.frak89.cloudyscanner.scan.PhotoRecon
import com.frak89.cloudyscanner.scan.PhotoStore
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
    private val planner = PhotoPlanner()
    private lateinit var photos: PhotoStore
    private lateinit var live: LiveVision
    private lateinit var liveBtn: TextView
    private lateinit var recon: PhotoRecon
    private lateinit var objBtn: TextView
    private lateinit var meshBtn: TextView
    private lateinit var aiBtn: TextView
    private lateinit var optBtn: TextView
    private lateinit var importBtn: TextView
    private var lastComplexityNs = 0L
    private var lastPaletteSize = 0
    private var aiLevelKnown = false
    private var lastPhotoEvent = ""

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
        photos = PhotoStore(this, planner, engine.subject)
        recon = PhotoRecon(this, map)
        engine.fusion.frameHook = { photos.onFrame(it) }
        live = LiveVision(ai, kb, { grabCamera(true) }, { liveFacts() }, { aiBusy || director.running },
            { msg -> ui.post { aiStatus.text = msg; if (!live.running) { Opt.aiLive = false; refreshControls() } } },
            { r, secs -> ui.post { onRecognition(r, secs) } })
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
        recon.cancel = true
        live.stop()
        engine.fusion.frameHook = null
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
        fun paint() { views.forEachIndexed { i, v -> v.background = rounded(if (i == get()) ACTIVE else BTN, 10f); v.setTextColor(if (i == get()) 0xFF10131A.toInt() else TEXT) } }
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
        hudView = HudView(this, hud, engine, planner, photos)
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
        scanBtn = pill("●  Scan") { toggleScan() }
        viewBtn = pill("Vista: AR") { setOrbit(!Opt.orbit) }
        eraseBtn = pill("Cancella") { setErase(!Opt.eraseMode) }
        objBtn = pill("Oggetto ▾") { showObjectMenu(it) }
        meshBtn = pill("Mesh ▾") { showMeshMenu(it) }
        aiBtn = pill("IA") { toggle(aiPanel, optionsPanel) }
        optBtn = pill("Opzioni") { toggle(optionsPanel, aiPanel) }
        importBtn = pill("Foto → 3D") { importPhotos() }
        bar.addView(scanBtn)
        bar.addView(viewBtn)
        bar.addView(objBtn)
        bar.addView(meshBtn)
        bar.addView(eraseBtn)
        bar.addView(importBtn)
        bar.addView(aiBtn)
        bar.addView(pill("Fotocamere") { showCameras() })
        bar.addView(optBtn)
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
        choice(col, arrayOf("Low", "Mid", "High", "NURBS"), { Opt.meshLevel }) { rebuild(it) }
        sw(col, "Modello pulito (solidi semplici)", { Opt.smart }) { Opt.smart = it; rebuildIfShown() }
        col.addView(TextView(this).apply { text = "Lati dei cerchi"; setTextColor(MUTED); textSize = 12f; setPadding(0, dp(6f), 0, 0) })
        val sideValues = intArrayOf(0, 6, 8, 12, 16, 24, 32)
        choice(col, arrayOf("Auto", "6", "8", "12"), { sideValues.indexOf(Opt.circleSides).let { if (it in 0..3) it else -1 } }) { Opt.circleSides = sideValues[it]; refreshControls(); rebuildIfShown() }
        choice(col, arrayOf("16", "24", "32"), { sideValues.indexOf(Opt.circleSides).let { if (it >= 4) it - 4 else -1 } }) { Opt.circleSides = sideValues[it + 4]; refreshControls(); rebuildIfShown() }
        sw(col, "Smussi sui bordi tondi", { Opt.bevel }) { Opt.bevel = it; rebuildIfShown() }
        sw(col, "Tieni i dettagli irregolari come mesh", { Opt.keepDetails }) { Opt.keepDetails = it; rebuildIfShown() }
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
        liveBtn = pill("IA live") { toggleLive() }
        row.addView(liveBtn)
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
        refreshControls()
    }

    override fun refreshControls() {
        ui.post {
            refreshers.forEach { it() }
            viewBtn.text = if (Opt.orbit) "Vista: Orbita" else "Vista: AR"
            scanBtn.text = if (engine.scanning) "■  Stop" else "●  Scan"
            // Every button that is switched on, or whose panel is open, gets the highlight colour.
            fun mark(b: TextView, on: Boolean, onColor: Int = ACTIVE) { b.background = rounded(if (on) onColor else BTN, 22f); b.setTextColor(if (on && onColor == ACTIVE) 0xFF10131A.toInt() else TEXT) }
            mark(scanBtn, engine.scanning, REC)
            mark(viewBtn, Opt.orbit)
            mark(objBtn, Opt.focusSubject || Opt.palette256)
            mark(meshBtn, Opt.realtimeMesh || meshes.mesh != null)
            mark(importBtn, recon.running)
            mark(aiBtn, aiPanel.visibility == View.VISIBLE)
            mark(optBtn, optionsPanel.visibility == View.VISIBLE)
            mark(eraseBtn, Opt.eraseMode, PURPLE_DIM)
            eraseBar.visibility = if (Opt.eraseMode) View.VISIBLE else View.GONE
            mark(liveBtn, Opt.aiLive)
            liveBtn.text = if (Opt.aiLive) "IA live ●" else "IA live"
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

    /** Options of the clean model changed: redo the mesh that is on screen. */
    private fun rebuildIfShown() {
        if (meshes.mesh != null && meshes.level in 0..2 && map.size >= 100) meshes.request(meshes.level, Opt.smoothIters, 0, 0.6f) { ui.post { refreshControls() } }
    }

    private fun remeshIfAny() {
        if (meshes.mesh != null && meshes.level >= 0) meshes.request(meshes.level, Opt.smoothIters, 0, 0f)
    }

    private fun rebuild(level: Int) {
        Opt.meshLevel = level
        if (map.size < 100) { toast("Prima acquisisci dei punti con Scan"); return }
        meshes.request(level, Opt.smoothIters, 0, 1.2f) { m ->
            ui.post {
                if (level == Opt.LEVEL_NURBS) {
                    aiLog.append("\n\nNURBS: " + meshes.nurbsInfo)
                    Toast.makeText(this, "NURBS: " + meshes.nurbsInfo, Toast.LENGTH_LONG).show()
                } else if (m != null) {
                    val counts = "${fmt(m.vertexCount)} vertici · ${fmt(m.quadCount)} quads" + (if (m.ngonCount > 0) " · ${m.ngonCount} n-goni" else "") + " · ${fmt(m.triangleCount)} triangoli"
                    if (Opt.smart) {
                        aiLog.append("\n\nModello pulito ${Opt.levelName[level]}: ${meshes.nurbsInfo}\n$counts")
                        val cat = meshes.catalog.filter { c -> c.kind != "face" }
                        if (cat.isNotEmpty()) aiLog.append("\nCatalogo: " + cat.joinToString(" · ") { c -> c.name } + "\n(menu Mesh → Catalogo oggetti per rinominarli o farli riconoscere all'IA)")
                        Toast.makeText(this, meshes.nurbsInfo, Toast.LENGTH_LONG).show()
                    }
                    else toast("${Opt.levelName[level]}: $counts")
                }
                refreshControls()
            }
        }
        refreshControls()
    }

    private fun showMeshMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val items = arrayOf(
            "Ricostruisci LOW poly", "Ricostruisci MID poly", "Ricostruisci HIGH poly",
            "Ricostruisci NURBS (tubi, curve, dischi, piani)", "Esporta superfici esatte in IGES (per CAD)",
            if (Opt.realtimeMesh) "Tempo reale: ON → spegni" else "Tempo reale: OFF → accendi",
            "Rimuovi mesh", "Esporta OBJ per Blender (quads e n-goni)", "Esporta OBJ (triangoli)", "Esporta nuvola PLY", "Nuova scansione (azzera tutto)",
        )
        items.forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
        pm.menu.add(0, 20, 0, "Catalogo oggetti (nomi, misure, colori, IA)")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                20 -> showCatalog()
                0, 1, 2 -> rebuild(it.itemId)
                3 -> rebuild(Opt.LEVEL_NURBS)
                4 -> export { val n = meshes.nurbs; if (n.isNullOrEmpty()) null else Storage.exportIges(this, n) }
                5 -> { Opt.realtimeMesh = !Opt.realtimeMesh; refreshControls() }
                6 -> { meshes.clear(); refreshControls() }
                7, 8 -> export { val m = meshes.mesh; if (m == null) null else Storage.exportObj(this, m, it.itemId == 7) }
                9 -> export {
                    map.lock.lock()
                    try { if (map.size == 0) null else Storage.exportPly(this, map) } finally { map.lock.unlock() }
                }
                10 -> AlertDialog.Builder(this).setMessage("Cancellare punti e mesh della scansione corrente?")
                    .setPositiveButton("Azzera") { _, _ -> engine.reset(); meshes.clear(); Opt.labels = emptyList(); photos.sceneTarget = null; updateEraseCount(); refreshControls() }
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

    // ------------------------------------------------------------------ reconstruction from imported photos

    private fun importPhotos() {
        if (recon.running) {
            AlertDialog.Builder(this).setMessage("Interrompere la ricostruzione dalle foto?")
                .setPositiveButton("Interrompi") { _, _ -> recon.cancel = true }.setNegativeButton("Continua", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ricostruzione 3D da foto")
            .setMessage("Scegli da 3 a ${PhotoRecon.MAX_PHOTOS} foto dello stesso soggetto. Bastano 3 foto se inquadrano le stesse parti " +
                "spostandoti di lato di un passo tra una e l'altra; con più foto il risultato è più completo.\n\nLa scansione attuale viene sostituita.")
            .setPositiveButton("Scegli le foto") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                try { startActivityForResult(i, REQ_PHOTOS) } catch (t: Throwable) { toast("Nessuna app per scegliere le foto") }
            }
            .setNegativeButton("Annulla", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTOS || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) else data.data?.let { uris.add(it) }
        if (uris.size < 2) { toast("Servono almeno 2 foto (meglio 8 o più)"); return }
        // Keep shooting order: file names from the camera sort by time.
        uris.sortBy { it.lastPathSegment ?: "" }
        val use = uris.take(PhotoRecon.MAX_PHOTOS)
        engine.scanning = false
        engine.reset(); meshes.clear(); photos.clear()
        Opt.orbit = true; Opt.colorMode = 1; Opt.showPoints = true
        aiPanel.visibility = View.VISIBLE; optionsPanel.visibility = View.GONE
        aiLog.text = "RICOSTRUZIONE DA ${use.size} FOTO"
        refreshControls()
        thread(name = "photo-recon") {
            var first = true
            recon.run(use) { line ->
                say(line)
                if (first && map.size > 0) { first = false; ui.post { renderer.recenter() } }
            }
            ui.post { renderer.recenter(); refreshControls() }
            // Whatever came out, turn it into something usable: a model, and the AI's reading of the photos.
            if (map.size >= 200) {
                Opt.showSolid = true; Opt.showWire = true
                val lvl = Opt.meshLevel.coerceIn(0, 2)
                val m = meshes.buildBlocking(lvl, 0, 0, 1.5f)
                if (m != null && m.faceCount + m.ngonCount > 0) say("Modello ${Opt.levelName[lvl]}: " + (if (Opt.smart) meshes.nurbsInfo + " · " else "") + "${fmt(m.vertexCount)} vertici, ${fmt(m.quadCount)} quads.")
                else say("I punti sono troppo radi per una mesh: resta la nuvola. Con più foto, o più vicine tra loro, migliora.")
                ui.post { refreshControls() }
            }
            val sheet = recon.contactSheet()
            if (sheet != null && !aiBusy && (ai.state == LocalAi.State.READY || ai.autoSelect())) {
                aiBusy = true
                try {
                    stream(ai.status)
                    if (ai.ensureLoaded()) {
                        val p = "L'immagine è un mosaico delle foto importate dall'utente per ricostruire un oggetto in 3D. " +
                            (if (recon.usedPhotos >= 2) "La ricostruzione ha usato ${recon.usedPhotos} foto e ha prodotto ${map.size} punti." else "La ricostruzione automatica NON è riuscita a collegare le foto: spiega anche perché potrebbe essere successo (poca sovrapposizione, superfici lisce o lucide, sfondo che cambia).") +
                            " Di' quali altre foto servirebbero."
                        val text = ai.generate(SYSTEM_OBJECT, p, listOf(sheet), 300) { stream(it) }
                        stream(null)
                        say("IA: " + text.trim())
                        val r = VisionParse.parse(text)
                        if (r.known) ui.post { onRecognition(r, 0f) }
                    } else { stream(null); say(ai.status) }
                } catch (t: Throwable) { stream(null); say("Analisi IA non riuscita: ${t.message ?: t.javaClass.simpleName}") }
                finally { finished() }
            } else if (sheet != null && ai.state == LocalAi.State.NONE) say("Con il modello IA caricato (pannello IA → Modello) analizzo anche le foto per dirti che oggetto è e quali scatti mancano.")
        }
        ui.postDelayed({ refreshControls() }, 300)
    }

    // ------------------------------------------------------------------ selected object

    private fun needSubject(): Boolean {
        if (engine.subject.locked) return true
        toast("Prima tocca l'oggetto nella vista AR per selezionarlo")
        return false
    }

    private fun onSubjectLocked() {
        photos.clear()
        live.reset()
        Opt.objectHint = ""
        hud.objLabel = ""
        aiLevelKnown = false
        if (Opt.palette256) rebuildPalette()
    }

    private fun hasSubjectPoints(): Boolean {
        map.lock.lock()
        try { return map.countFlag(PointMap.SUBJECT) > 50 } finally { map.lock.unlock() }
    }

    private fun showObjectMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val lvl = if (planner.levelFixed) PhotoPlanner.LEVEL_NAMES[planner.level] else "Auto (${PhotoPlanner.LEVEL_NAMES[planner.level]})"
        val items = arrayOf(
            (if (Opt.focusSubject) "✔ " else "") + "Priorità all'oggetto (scansiona solo lui)",
            "Elimina il superfluo (tieni solo l'oggetto)",
            (if (Opt.palette256) "✔ " else "") + "Colora a 256 colori",
            "Analizza l'oggetto",
            (if (Opt.photoAuto) "✔ " else "") + "Foto automatiche dalle varie angolazioni",
            "Scatta una foto adesso",
            "Viste richieste: $lvl",
            "Pulisci foto (mosse, poco dettagliate, doppie)",
            "Ricolora la scansione dalle foto migliori",
            "Esporta foto + pose in Download",
            "Svuota le foto (${planner.count()})",
        )
        items.forEachIndexed { i, t -> pm.menu.add(0, i, i, t) }
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> if (Opt.focusSubject || needSubject()) {
                    Opt.focusSubject = !Opt.focusSubject
                    toast(if (Opt.focusSubject) "Da ora acquisisco solo l'oggetto selezionato" else "Acquisisco di nuovo tutta la scena")
                }
                1 -> isolateSubject()
                2 -> { Opt.palette256 = !Opt.palette256; if (Opt.palette256) rebuildPalette() else { renderer.palette = null; touchMap() } }
                3 -> analyseObject()
                4 -> { Opt.photoAuto = !Opt.photoAuto; toast(if (Opt.photoAuto) "Foto automatiche attive: seleziona un oggetto e avvia Scan" else "Foto automatiche spente") }
                5 -> if (needSubject()) { photos.manualRequest = true }
                6 -> AlertDialog.Builder(this).setTitle("Quante viste servono")
                    .setItems(arrayOf("Automatico (lo decide la forma o l'IA)", "Semplice · 3 foto: davanti, dietro, sopra", "Media · 5 foto: 4 lati + sopra", "Complessa · 13 foto: giro completo a due altezze + sopra")) { _, i ->
                        if (i == 0) { planner.levelFixed = false; lastComplexityNs = 0L }
                        else photos.delete(planner.setLevel(i - 1, true))
                    }.show()
                7 -> {
                    val gone = planner.prune(); photos.delete(gone)
                    toast(if (gone.isEmpty()) "Nessuna foto da scartare" else "Scartate ${gone.size} foto · restano ${planner.count()}")
                }
                8 -> thread {
                    val n = photos.recolor(map, hasSubjectPoints())
                    ui.post { toast(if (n > 0) "Ricolorati ${fmt(n)} punti da ${planner.count()} foto" else "Servono prima delle foto dell'oggetto"); if (Opt.palette256) rebuildPalette() }
                }
                9 -> export { photos.export(this) }
                10 -> { photos.clear(); toast("Foto eliminate") }
            }
            true
        }
        pm.show()
    }

    private fun touchMap() {
        map.lock.lock()
        try { map.touch() } finally { map.lock.unlock() }
    }

    /** Everything that was not observed as part of the selected object turns purple, then goes. */
    private fun isolateSubject() {
        if (!hasSubjectPoints()) { toast("Seleziona l'oggetto e scansionalo un po': non ho ancora punti suoi"); return }
        val n = eraser.select { !map.hasFlag(it, PointMap.SUBJECT) }
        if (n == 0) { toast("Non c'è nulla fuori dall'oggetto"); return }
        AlertDialog.Builder(this)
            .setMessage("Ho evidenziato in viola ${fmt(n)} punti che non fanno parte dell'oggetto. Li elimino? (Si può annullare da Cancella → Annulla)")
            .setPositiveButton("Elimina") { _, _ -> val k = eraser.deleteSelected(); toast("Eliminati ${fmt(k)} punti"); remeshIfAny() }
            .setNegativeButton("Tieni") { _, _ -> eraser.clearSelection() }
            .setOnCancelListener { eraser.clearSelection() }
            .show()
    }

    private fun buildPalette(): Palette? {
        map.lock.lock()
        try {
            val subj = map.countFlag(PointMap.SUBJECT) > 50
            renderer.paletteSubjectOnly = subj
            lastPaletteSize = map.size
            return Palette.build(map.col, map.size, 256) { !subj || map.hasFlag(it, PointMap.SUBJECT) }
        } finally { map.lock.unlock() }
    }

    private fun rebuildPalette() {
        thread {
            val p = buildPalette()
            if (Opt.palette256) { renderer.palette = p; touchMap() }
            if (p == null) ui.post { toast("Servono punti con colore: fai prima una scansione") }
        }
    }

    private fun cm(v: Float) = String.format(Locale.US, "%.0f", v * 100f)

    /** Measurements and colours of the selected object (or of the whole cloud when nothing is selected). */
    private fun objectReport(): String? {
        val sb = StringBuilder()
        val pal: Palette?
        val level: Int
        map.lock.lock()
        try {
            val subj = map.countFlag(PointMap.SUBJECT) > 50
            val pick = { i: Int -> !subj || map.hasFlag(i, PointMap.SUBJECT) }
            var n = 0; var conf = 0f
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in 0 until map.size) if (pick(i)) {
                n++; conf += map.conf[i]
                for (c in 0 until 3) { val v = map.pos[i * 3 + c]; if (v < lo[c]) lo[c] = v; if (v > hi[c]) hi[c] = v }
            }
            if (n < 100) return null
            level = PhotoPlanner.complexity(map.nrm, map.size, pick)
            pal = Palette.build(map.col, map.size, 256, pick)
            sb.append(if (subj) "Oggetto selezionato" else "Intera scena (nessun oggetto selezionato)").append('\n')
            sb.append("Ingombro: ${cm(hi[0] - lo[0])} × ${cm(hi[1] - lo[1])} × ${cm(hi[2] - lo[2])} cm (larghezza × altezza × profondità)\n")
            sb.append("Punti: ${fmt(n)} · affidabilità media ${(conf / n * 100).toInt()}%\n")
        } finally { map.lock.unlock() }
        sb.append("Forma stimata: ${PhotoPlanner.LEVEL_NAMES[level].lowercase()}\n")
        if (pal != null) sb.append("Colori (${pal.size} in palette): " + pal.namedShares().take(5).joinToString(", ") { "${it.first} ${(it.second * 100).toInt()}%" } + "\n")
        val miss = planner.missing()
        sb.append("Foto: ${planner.coveredCount()}/${planner.slots.size} viste" + (if (miss.isEmpty()) "" else " · mancano: " + miss.joinToString(", ")) + "\n")
        if (photos.liveValid) sb.append(String.format(Locale.US, "Distanza %.2f m · ideale ~%.2f m · angolo dall'alto %.0f°\n", photos.live[2], photos.live[3], photos.live[1]))
        return sb.toString()
    }

    private fun analyseObject() {
        if (aiBusy || director.running) { toast("L'IA sta già lavorando"); return }
        aiBusy = true
        aiPanel.visibility = View.VISIBLE; optionsPanel.visibility = View.GONE
        thread(name = "ai-object") {
            try {
                val report = objectReport()
                if (report == null) { say("Servono più punti: seleziona l'oggetto e fai una scansione."); return@thread }
                say("ANALISI DELL'OGGETTO\n$report")
                if (!(ai.state == LocalAi.State.READY || ai.autoSelect())) { say("Per farmi dire che oggetto è serve il modello IA: tocca «Modello»."); return@thread }
                stream(ai.status)
                if (!ai.ensureLoaded()) { stream(null); say(ai.status); return@thread }
                val sheet = photos.contactSheet()
                val image = sheet ?: grabCamera(true) ?: grabSnapshot()
                val p = StringBuilder()
                p.append(if (sheet != null) "L'immagine è un mosaico di foto dello stesso oggetto da lati diversi.\n" else "L'immagine mostra l'oggetto.\n")
                p.append("Misure dalla scansione 3D:\n").append(report)
                val mem = kb.promptContext()
                if (mem.isNotEmpty()) p.append(mem).append('\n')
                val text = ai.generate(SYSTEM_OBJECT, p.toString(), listOfNotNull(image), 300) { stream(it) }
                stream(null)
                say("IA: " + text.trim())
                val r = VisionParse.parse(text)
                if (r.known) ui.post { onRecognition(r, 0f) }
                text.lines().firstOrNull { it.trim().uppercase().startsWith("NOTA") }?.let { kb.addNote(it.substringAfter(':').trim()); kb.save() }
            } catch (t: Throwable) {
                stream(null); say("Analisi non riuscita: ${t.message ?: t.javaClass.simpleName}")
            } finally { finished() }
        }
    }

    // ------------------------------------------------------------------ live recognition

    private fun toggleLive() {
        Opt.aiLive = !Opt.aiLive
        if (Opt.aiLive) { live.start(); toast("IA live: inquadra l'oggetto, ti dico cosa vedo ogni pochi secondi") } else live.stop()
        refreshControls()
    }

    private fun liveFacts(): String {
        val s = engine.subject
        val sb = StringBuilder()
        if (s.valid && s.locked) sb.append(String.format(Locale.US, "L'utente ha selezionato questo oggetto, a %.2f m, largo circa %.0f cm. ", s.distance, s.extent * 100f))
        else sb.append("Nessun oggetto selezionato: considera ciò che sta al centro. ")
        if (photos.liveValid) sb.append(String.format(Locale.US, "Lo sta guardando da %.0f° di lato e %.0f° dall'alto. ", photos.live[0], photos.live[1]))
        val mem = kb.promptContext()
        if (mem.isNotEmpty()) sb.append('\n').append(mem)
        return sb.toString()
    }

    private fun onRecognition(r: Recognition, secs: Float) {
        val before = hud.objLabel
        hud.objLabel = if (r.known) "${r.name} · ${r.confidence}%" else ""
        if (r.level >= 0) { aiLevelKnown = true; photos.delete(planner.setLevel(r.level, false)) }
        if (r.known && r.confidence >= 60 && engine.subject.valid && engine.subject.locked) {
            val s = engine.subject
            putLabel(s.x, s.y, s.z, r.name.lowercase(Locale.ITALIAN))
        }
        if (r.known && r.confidence >= 60 && !r.name.equals(Opt.objectHint, true)) {
            // AI boost: knowing what the object is lets the clean model add what such objects always have.
            Opt.objectHint = r.name
            if (Opt.smart && meshes.mesh != null && meshes.level in 0..2) { say("IA boost: «${r.name}» → rifaccio il modello pulito con le rifiniture tipiche di questo oggetto."); rebuildIfShown() }
        }
        val nameChanged = !before.startsWith(r.name + " ·")
        if (r.known && nameChanged) {
            say("IA live: vedo «${r.name}» (${r.confidence}%)" + (if (r.level >= 0) ", forma ${PhotoPlanner.LEVEL_NAMES[r.level].lowercase()} → ${planner.slots.size} viste" else "") +
                (if (secs > 0f) String.format(Locale.US, " · %.0f s", secs) else ""))
            if (r.confidence >= 80) { kb.addNote("«${r.name}»: forma ${PhotoPlanner.LEVEL_NAMES[planner.level].lowercase()}, bastano ${planner.slots.size} viste."); kb.save() }
        }
        if (r.ask.isNotEmpty() && r.confidence < 75) say("IA chiede: ${r.ask}")
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
                val image = if (fromCamera) grabCamera(false) ?: grabSnapshot() else grabSnapshot()
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

    // ------------------------------------------------------------------ catalogue

    private var sceneTargetAt = 0L

    /** Without a selected object, auto photos aim at the scanned scene (robust centre and size). */
    private fun updateSceneTarget() {
        if (engine.subject.locked) return
        val now = System.currentTimeMillis()
        if (now - sceneTargetAt < 2000) return
        sceneTargetAt = now
        if (!map.lock.tryLock()) return
        try {
            val n = map.size
            if (n < 2000) { photos.sceneTarget = null; return }
            val step = (n / 3000).coerceAtLeast(1)
            val m = n / step
            val xs = FloatArray(m); val ys = FloatArray(m); val zs = FloatArray(m)
            for (k in 0 until m) { val o = k * step * 3; xs[k] = map.pos[o]; ys[k] = map.pos[o + 1]; zs[k] = map.pos[o + 2] }
            xs.sort(); ys.sort(); zs.sort()
            val lo = m / 20; val hi = m - 1 - m / 20
            val ex = xs[hi] - xs[lo]; val ey = ys[hi] - ys[lo]; val ez = zs[hi] - zs[lo]
            photos.sceneTarget = floatArrayOf((xs[lo] + xs[hi]) / 2, (ys[lo] + ys[hi]) / 2, (zs[lo] + zs[hi]) / 2,
                kotlin.math.sqrt(ex * ex + ey * ey + ez * ez).coerceIn(0.2f, 4f))
        } finally { map.lock.unlock() }
    }

    /** Names the object nearest to a point (replacing an older name at the same spot) and rebuilds the clean model. */
    private fun putLabel(x: Float, y: Float, z: Float, name: String, rebuild: Boolean = false) {
        val keep = Opt.labels.filter { val dx = it.x - x; val dy = it.y - y; val dz = it.z - z; dx * dx + dy * dy + dz * dz > 0.05f * 0.05f }
        Opt.labels = keep + PartLabel(x, y, z, name)
        if (rebuild) rebuildIfShown()
    }

    private fun colorName(c: Int): String {
        return String.format(Locale.US, "#%06X", c and 0xFFFFFF)
    }

    private fun showCatalog() {
        val cat = meshes.catalog
        if (cat.isEmpty()) {
            toast(if (Opt.smart) "Ricostruisci prima un modello (LOW, MID o HIGH)" else "Attiva «Modello pulito» e ricostruisci: il catalogo nasce dal modello pulito")
            return
        }
        val shots = planner.shots()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val dlg = AlertDialog.Builder(this).setTitle("Catalogo · ${cat.size} oggetti")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Riconosci tutti con IA") { _, _ -> recogniseParts(cat.filter { it.kind != "face" }) }
            .setNeutralButton("Esporta") { _, _ -> export { Storage.exportCatalog(this, cat, shots) } }
            .setNegativeButton("Chiudi", null).create()
        for (p in cat) {
            val cov = ObjectCrops.coverage(p, shots)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14, 0, 14); gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(View(this).apply { setBackgroundColor(p.color or (0xFF shl 24)) }, LinearLayout.LayoutParams(56, 56).apply { rightMargin = 24 })
            val tv = TextView(this).apply {
                setTextColor(0xFFEEEEEE.toInt()); textSize = 14f
                text = "${p.name}${if (p.labelled) "  ✓IA" else ""}\n${p.dims} · ${colorName(p.color)}" +
                    (if (p.note.isNotEmpty()) "\n${p.note}" else "") +
                    "\nFoto ${cov[0]} · lati ${cov[1]}/8" + (if (cov[2] > 0) " · dall'alto" else "") +
                    (if (cov[0] == 0) "  → fotografalo" else if (cov[1] < 3) "  → mancano lati" else "")
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                AlertDialog.Builder(this).setTitle(p.name)
                    .setItems(arrayOf("Rinomina (es. piatto, candela, barattolo…)", "Riconosci con IA")) { _, w ->
                        if (w == 0) renamePart(p) else { dlg.dismiss(); recogniseParts(listOf(p)) }
                    }.show()
            }
            list.addView(row)
        }
        dlg.show()
    }

    private fun renamePart(p: CatalogPart) {
        val ed = EditText(this).apply { setText(if (p.labelled) p.name else ""); hint = "nome dell'oggetto" }
        AlertDialog.Builder(this).setTitle("Che cos'è?").setView(ed)
            .setPositiveButton("OK") { _, _ ->
                val n = ed.text.toString().trim().lowercase(Locale.ITALIAN)
                if (n.isNotEmpty()) {
                    putLabel(p.center[0], p.center[1], p.center[2], n, true)
                    kb.addNote("Oggetto «$n»: ${p.dims}, colore ${colorName(p.color)}."); kb.save()
                    toast("«$n»: rifaccio il modello con la forma tipica")
                }
            }.setNegativeButton("Annulla", null).show()
    }

    /** One posed camera frame from the live view (for objects no stored photo shows). */
    private fun grabPosed(): PosedImage? {
        val latch = CountDownLatch(1)
        var out: PosedImage? = null
        engine.posedRequest = { out = it; latch.countDown() }
        if (!latch.await(2, TimeUnit.SECONDS)) { engine.posedRequest = null; return null }
        return out
    }

    /** Shows each object, cut out of the best photo, to the AI and uses the answer as its name. */
    private fun recogniseParts(parts: List<CatalogPart>) {
        if (parts.isEmpty()) return
        if (aiBusy || director.running) { toast("L'IA sta già lavorando"); return }
        aiBusy = true
        thread(name = "ai-catalog") {
            try {
                if (!(ai.state == LocalAi.State.READY || ai.autoSelect())) { say("Per riconoscere gli oggetti serve il modello IA: tocca «Modello»."); return@thread }
                stream(ai.status)
                if (!ai.ensureLoaded()) { stream(null); say(ai.status); return@thread }
                val images = planner.shots().map { ObjectCrops.fromShot(it) }.toMutableList()
                grabPosed()?.let { if (it.jpeg != null) images.add(it) }
                var named = 0
                for ((k, p) in parts.withIndex()) {
                    stream("Oggetto ${k + 1}/${parts.size}: ${p.name}…")
                    val crop = ObjectCrops.crop(p, images)
                    if (crop == null) { say("«${p.name}»: nessuna foto lo inquadra, fotografalo e riprova."); continue }
                    val prompt = "L'immagine è ritagliata attorno a UN oggetto della scena, al centro. " +
                        "Dalla scansione 3D: ${p.name}, ${p.dims}, colore dominante ${colorName(p.color)}." +
                        (if (p.note.isNotEmpty()) " ${p.note}." else "") +
                        "\nChe oggetto è quello al centro? Rispondi con le righe OGGETTO: e SICUREZZA:, poi una frase."
                    val text = ai.generate(SYSTEM_OBJECT, prompt, listOf(crop), 120) { stream(it) }
                    val r = VisionParse.parse(text)
                    if (r.known && r.confidence >= 40) {
                        val n = r.name.lowercase(Locale.ITALIAN)
                        putLabel(p.center[0], p.center[1], p.center[2], n)
                        kb.addNote("Oggetto «$n»: ${p.dims}, colore ${colorName(p.color)}.")
                        say("IA: «${p.name}» è «$n» (${r.confidence}%)")
                        named++
                    } else say("IA: «${p.name}» non riconosciuto" + (if (r.ask.isNotEmpty()) " — ${r.ask}" else ""))
                }
                stream(null)
                kb.save()
                if (named > 0) { say("Rifaccio il modello pulito con $named oggetti riconosciuti (forme tipiche: piatti cavi, candele, barattoli…)."); ui.post { rebuildIfShown() } }
            } catch (t: Throwable) { stream(null); say("Riconoscimento non riuscito: ${t.message ?: t.javaClass.simpleName}") }
            finally { finished() }
        }
    }

    private fun grabSnapshot(): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        renderer.snapshotRequest = { out = it; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    /**
     * Current camera frame as an upright JPEG (longest side at most 1024 px). With [crop], it
     * is cut to the selected object plus a margin, so the model looks at the object only.
     */
    private fun grabCamera(crop: Boolean = false): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        engine.jpegRequest = { out = it; latch.countDown() }
        if (!latch.await(2, TimeUnit.SECONDS)) { engine.jpegRequest = null; return null }
        val jpeg = out ?: return null
        return try {
            var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val s = engine.subject
            if (crop && s.valid && s.locked) {
                val r = s.imgRect
                val mx = (r[2] - r[0]) * 0.2f; val my = (r[3] - r[1]) * 0.2f
                val x0 = (r[0] - mx).toInt().coerceIn(0, bmp.width - 2); val y0 = (r[1] - my).toInt().coerceIn(0, bmp.height - 2)
                val x1 = (r[2] + mx).toInt().coerceIn(x0 + 1, bmp.width); val y1 = (r[3] + my).toInt().coerceIn(y0 + 1, bmp.height)
                if (x1 - x0 >= 48 && y1 - y0 >= 48) bmp = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
            }
            // The sensor image is landscape; turn it upright for the way the phone is held.
            val deg = when (display?.rotation ?: 0) { 0 -> 90f; 2 -> 270f; 3 -> 180f; else -> 0f }
            val sc = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
            val mtx = android.graphics.Matrix().apply { postScale(sc, sc); postRotate(deg) }
            val fin = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
            ByteArrayOutputStream().also { fin.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
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
            else if (m != null) {
                sb.append("Mesh ${Opt.levelName[meshes.level.coerceIn(0, 3)]}  V ${fmt(m.vertexCount)}  Q ${fmt(m.quadCount)}" + (if (m.ngonCount > 0) "  N-goni ${m.ngonCount}" else "") + "  T ${fmt(m.triangleCount)}  (${meshes.lastMs.toInt()} ms)")
                if (meshes.nurbsInfo.isNotEmpty()) sb.append("\n").append(meshes.nurbsInfo)
            }
            else sb.append("Mesh: —")
            sb.append('\n')
            sb.append(if (s.valid) String.format(Locale.US, "Soggetto %.2f m", s.distance) else "Soggetto —")
            if (s.locked) sb.append(if (s.moving) String.format(Locale.US, " ⟲ %.2f m/s", s.speed) else " ⌖")
            sb.append(if (engine.focusDistance > 0f) String.format(Locale.US, "   AF/laser %.2f m", engine.focusDistance) else "   AF/laser ∞")
            if (engine.focalLength > 0f) sb.append(String.format(Locale.US, "   %.1f mm f/%.2f ISO %d", engine.focalLength, engine.aperture, engine.iso))
            sb.append('\n')
            sb.append("Incerti ${fmt(engine.uncertainCount)} · ripassati ${engine.guidance.resolved} · ${engine.trackingText}")
            updateSceneTarget()
            if (s.locked || (photos.sceneTarget != null && planner.count() > 0)) {
                sb.append("\nFoto ${planner.coveredCount()}/${planner.slots.size} viste (${PhotoPlanner.LEVEL_NAMES[planner.level].lowercase()})")
                val rej = planner.rejectedBlur + planner.rejectedSoft
                if (rej + planner.replaced > 0) sb.append(" · scartate ${planner.rejectedBlur} mosse, ${planner.rejectedSoft} poco nitide · ${planner.replaced} migliorate")
                if (Opt.focusSubject) sb.append(" · SOLO OGGETTO")
            }
            if (hud.objLabel.isNotEmpty()) sb.append("\nIA: ").append(hud.objLabel)
            if (engine.lastError.isNotEmpty()) sb.append("\n! ").append(engine.lastError)
            stats.text = sb

            val msg = when {
                recon.running -> recon.status.ifEmpty { "Ricostruzione dalle foto in corso…" }
                arMessage != null -> arMessage
                !engine.tracking -> engine.trackingText
                director.running -> null
                s.locked && s.moving -> "Soggetto in movimento: ricalcolo la posizione…"
                Opt.aiLive && live.last.ask.isNotEmpty() && live.last.confidence < 75 -> "IA: " + live.last.ask
                engine.scanning && s.locked && Opt.photoAuto && photos.liveValid && !planner.complete ->
                    planner.advice(photos.live[0], photos.live[1], photos.live[2], photos.live[3])
                engine.scanning && Opt.guidance && engine.guidance.active -> "Zona incerta: torna sul punto arancione e inquadralo lentamente"
                !engine.scanning && hud.points == 0 -> "Tocca ● Scan e gira lentamente attorno al soggetto · tocca lo schermo per agganciarlo"
                else -> null
            }
            if (msg == null) banner.visibility = View.GONE
            else {
                banner.text = msg; banner.visibility = View.VISIBLE
                banner.background = rounded(if (recon.running) ACTIVE else if (arMessage != null || !engine.tracking) 0xFFFF6B5E.toInt() else if (engine.guidance.active && engine.scanning) WARN else 0xFFE8EAF0.toInt(), 18f)
            }

            val now = System.nanoTime()
            val ev = photos.lastEvent
            if (ev != lastPhotoEvent) { lastPhotoEvent = ev; if (ev.isNotEmpty()) toast(ev) }
            // Without an AI verdict, estimate from the shape how many views the object needs.
            if (s.locked && !planner.levelFixed && !aiLevelKnown && now - lastComplexityNs > 4_000_000_000L && map.lock.tryLock()) {
                try {
                    lastComplexityNs = now
                    if (map.countFlag(PointMap.SUBJECT) > 400) {
                        photos.delete(planner.setLevel(PhotoPlanner.complexity(map.nrm, map.size) { map.hasFlag(it, PointMap.SUBJECT) }, false))
                    }
                } finally { map.lock.unlock() }
            }
            if (Opt.palette256 && engine.scanning && kotlin.math.abs(map.size - lastPaletteSize) > lastPaletteSize / 5 + 500) { lastPaletteSize = map.size; rebuildPalette() }
            if (Opt.realtimeMesh && (engine.scanning || recon.running) && !meshes.busy && map.version != meshes.builtVersion && now - lastRealtimeNs > 900_000_000L && hud.points > 300) {
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
                    if (engine.lockSubjectAt(e.x, e.y)) { onSubjectLocked(); toast("Oggetto selezionato · tieni premuto per sganciare") }
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
        const val REQ_PHOTOS = 41
        const val TEXT = 0xFFE8EAF0.toInt()
        const val MUTED = 0xFF9BA1B0.toInt()
        const val PANEL = 0xE6191B22.toInt()
        const val BTN = 0xE62B2E39.toInt()
        const val ACCENT = 0xFF4DE6FF.toInt()
        const val ACCENT_DIM = 0xFF1F6F85.toInt()
        /** Background of a button that is switched on. */
        const val ACTIVE = 0xFF4DE6FF.toInt()
        const val PURPLE = 0xFFC77DFF.toInt()
        const val PURPLE_DIM = 0xFF7B2FBF.toInt()
        const val REC = 0xFFD9392B.toInt()
        const val WARN = 0xFFFF9E1A.toInt()

        const val SYSTEM_OBJECT = "Sei l'assistente dell'app Cloudy Scanner. Ricevi immagini di un oggetto e le misure ricavate dalla " +
            "sua scansione 3D. Rispondi in italiano con queste righe:\n" +
            "OGGETTO: <che cos'è, nome breve>\nSICUREZZA: <0-100>\nMATERIALE: <materiali probabili>\n" +
            "COMPLESSITA: SEMPLICE oppure MEDIA oppure COMPLESSA\n" +
            "RICHIESTA: <cosa mostrarti per esserne più sicuro, oppure NESSUNA>\n" +
            "Poi due o tre frasi: descrivi l'oggetto usando anche misure e colori, e di' quali lati o dettagli conviene " +
            "ancora fotografare e da che distanza. Chiudi con una riga NOTA: con una lezione breve e riutilizzabile."

        const val SYSTEM_ANALYSE = "Sei l'assistente di scansione 3D dell'app Cloudy Scanner su un telefono. Ricevi un'immagine e alcuni dati " +
            "della scansione in corso. Rispondi in italiano, in modo concreto, in 4-6 frasi: che cos'è il soggetto, quali superfici " +
            "saranno difficili da ricostruire (lucide, trasparenti, scure, uniformi, sottili), da quali lati e a che distanza conviene " +
            "ripassare. Chiudi con una riga che inizia con NOTA: e contiene una lezione breve, riutilizzabile nelle prossime scansioni."
    }
}
