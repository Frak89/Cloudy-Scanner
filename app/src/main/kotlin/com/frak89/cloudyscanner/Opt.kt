package com.frak89.cloudyscanner

/** Live options shared by UI, scanner and renderer. Plain volatile fields: last write wins. */
object Opt {
    // View
    @Volatile var orbit = false
    @Volatile var showCamera = false
    @Volatile var edgeOverlay = false
    @Volatile var showPoints = true
    @Volatile var showSolid = true
    @Volatile var showWire = true
    @Volatile var wireQuads = true
    @Volatile var cull = false
    @Volatile var lit = true
    @Volatile var flat = false
    @Volatile var vertexColor = true
    @Volatile var xray = false
    @Volatile var grid = true
    @Volatile var bbox = false
    @Volatile var axes = false
    @Volatile var ortho = false
    @Volatile var fov = 60f
    /** 0 white, 1 camera colour, 2 confidence, 3 distance, 4 height, 5 normals */
    @Volatile var colorMode = 0
    @Volatile var pointSize = 5f
    @Volatile var perspPoints = true

    // Animation
    @Volatile var autoRotate = false
    @Volatile var rotSpeed = 25f
    @Volatile var wave = false
    @Volatile var pulse = false

    // Scan
    @Volatile var guidance = true
    @Volatile var subjectHighlight = true
    @Volatile var adaptMotion = true
    @Volatile var maxRange = 4f
    @Volatile var minConf = 0.35f
    @Volatile var subjRadius = 0.6f

    // Object focus
    /** Fuse only the locked subject; everything else in view is ignored. */
    @Volatile var focusSubject = false
    @Volatile var palette256 = false
    @Volatile var photoAuto = true
    @Volatile var aiLive = false

    // Mesh
    @Volatile var realtimeMesh = false
    /** 0 low, 1 mid, 2 high, 3 NURBS (regular shapes) */
    @Volatile var meshLevel = 1
    @Volatile var smoothIters = 1
    /** Rebuild as few exact solids (clean low/mid/high-poly modelling) instead of a dense scanned skin. */
    @Volatile var smart = true
    /** Sides of circles in clean models; 0 = chosen from level and size. */
    @Volatile var circleSides = 0
    @Volatile var bevel = true
    /** Keep irregular parts as ordinary mesh next to the clean solids. */
    @Volatile var keepDetails = true
    /** What the AI says the object is; steers typical finishing (e.g. the rim of a can). */
    @Volatile var objectHint = ""

    // Erase
    @Volatile var eraseMode = false
    @Volatile var brush = 46f

    val levelCell = floatArrayOf(0.08f, 0.04f, 0.02f)
    val levelName = arrayOf("LOW", "MID", "HIGH", "NURBS")
    const val LEVEL_NURBS = 3
    val colorNames = arrayOf("Bianco", "Colore reale", "Affidabilità", "Distanza", "Altezza", "Normali")
}
