package com.frak89.cloudyscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import com.frak89.cloudyscanner.Opt
import com.frak89.cloudyscanner.gl.HudState
import android.graphics.RectF
import com.frak89.cloudyscanner.scan.PhotoPlanner
import com.frak89.cloudyscanner.scan.PhotoStore
import com.frak89.cloudyscanner.scan.ScanEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 2D overlay: reticle, subject bracket, "come back here" marker and the erase brush. */
class HudView(
    ctx: Context, private val hud: HudState, private val engine: ScanEngine,
    private val planner: PhotoPlanner, private val photos: PhotoStore,
) : View(ctx) {
    private val arc = RectF()
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setShadowLayer(4f * d, 0f, 0f, 0xCC000000.toInt()) }
    private val path = Path()

    @Volatile var brushX = -1f
    @Volatile var brushY = -1f
    var insetLeft = 0; var insetTop = 0; var insetRight = 0; var insetBottom = 0

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.nanoTime() / 1e9).toFloat()
        val ar = !Opt.orbit

        if (ar && !Opt.eraseMode) {
            stroke.color = 0x99FFFFFF.toInt(); stroke.strokeWidth = 1.5f * d
            val r = 9f * d
            c.drawLine(w / 2 - r, h / 2, w / 2 - 3 * d, h / 2, stroke); c.drawLine(w / 2 + 3 * d, h / 2, w / 2 + r, h / 2, stroke)
            c.drawLine(w / 2, h / 2 - r, w / 2, h / 2 - 3 * d, stroke); c.drawLine(w / 2, h / 2 + 3 * d, w / 2, h / 2 + r, stroke)
        }

        val s = engine.subject
        if (Opt.subjectHighlight && hud.subjOn && s.valid && (s.locked || ar)) {
            val x = hud.subjX; val y = hud.subjY
            val moving = s.moving
            val col = if (moving) 0xFFFF7A4D.toInt() else if (s.locked) 0xFF4DE6FF.toInt() else 0xB3FFFFFF.toInt()
            stroke.color = col; stroke.strokeWidth = 2f * d
            val b = (if (s.locked) 46f else 30f) * d; val k = 12f * d
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                c.drawLine(x + sx * b, y + sy * b, x + sx * (b - k), y + sy * b, stroke)
                c.drawLine(x + sx * b, y + sy * b, x + sx * b, y + sy * (b - k), stroke)
            }
            text.color = col
            val label = String.format(Locale.US, "%.2f m", s.distance) +
                (if (moving) "  · IN MOVIMENTO" else if (s.locked) "  · AGGANCIATO" else "")
            c.drawText(label, x - text.measureText(label) / 2, y + b + 18f * d, text)
            val name = hud.objLabel
            if (name.isNotEmpty()) {
                text.color = 0xFFFFFFFF.toInt()
                c.drawText(name, x - text.measureText(name) / 2, y - b - 10f * d, text)
            }
        }

        // Photo coverage, seen from above: the subject in the middle, one arc per viewpoint
        // (green once photographed), the white dot is where the phone is now.
        if ((s.locked || photos.liveValid) && (Opt.photoAuto || planner.count() > 0)) {
            val cx = w - insetRight - 62f * d; val cy = h - insetBottom - 128f * d
            val slots = planner.slots
            fill.color = 0x99191B22.toInt()
            c.drawCircle(cx, cy, 50f * d, fill)
            for ((i, sl) in slots.withIndex()) {
                val done = planner.covered(i)
                val col = if (done) 0xFF5CE08A.toInt() else 0x66FFFFFF
                if (sl.azTol >= 180f) { fill.color = col; c.drawCircle(cx, cy, 7f * d, fill); continue }
                val r = (if (sl.elMin >= 25f) 24f else 38f) * d
                stroke.color = col; stroke.strokeWidth = 6f * d
                arc.set(cx - r, cy - r, cx + r, cy + r)
                val gap = if (sl.azTol > 30f) 8f else 5f
                c.drawArc(arc, 90f - sl.az - sl.azTol + gap / 2, 2 * sl.azTol - gap, false, stroke)
            }
            if (photos.liveValid) {
                val a = Math.toRadians((90f - photos.live[0]).toDouble())
                val r = (if (photos.live[1] > 55f) 8f else if (photos.live[1] > 28f) 24f else 38f) * d
                fill.color = 0xFFFFFFFF.toInt()
                c.drawCircle(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r, 4.5f * d, fill)
            }
            text.color = 0xFFE8EAF0.toInt()
            val lab = "Foto ${planner.coveredCount()}/${slots.size}"
            c.drawText(lab, cx - text.measureText(lab) / 2, cy + 68f * d, text)
        }

        if (hud.guideActive) {
            val gx = hud.guideX; val gy = hud.guideY
            val pulse = 0.5f + 0.5f * sin(t * 6f)
            val col = 0xFFFF9E1A.toInt()
            val mx = 40f * d
            val on = !hud.guideBehind && gx > insetLeft + mx && gx < w - insetRight - mx && gy > insetTop + mx && gy < h - insetBottom - mx
            text.color = col
            if (on) {
                stroke.color = col; stroke.strokeWidth = 3f * d
                c.drawCircle(gx, gy, (26f + 8f * pulse) * d, stroke)
                stroke.strokeWidth = 1.5f * d
                c.drawCircle(gx, gy, (40f + 14f * pulse) * d, stroke)
                val label = "RIPASSA QUI"
                c.drawText(label, gx - text.measureText(label) / 2, gy - 52f * d, text)
            } else {
                // Arrow pinned to the screen edge, pointing toward the spot.
                var dx = gx - w / 2; var dy = gy - h / 2
                if (hud.guideBehind && abs(dx) < 1f && abs(dy) < 1f) dx = 1f
                val ang = atan2(dy, dx)
                val hx = w / 2 - mx - maxOf(insetLeft, insetRight); val hy = h / 2 - mx - maxOf(insetTop, insetBottom)
                val sc = min(hx / maxOf(abs(cos(ang)), 1e-3f), hy / maxOf(abs(sin(ang)), 1e-3f))
                val ax = w / 2 + cos(ang) * sc; val ay = h / 2 + sin(ang) * sc
                val size = (18f + 5f * pulse) * d
                path.reset()
                path.moveTo(ax + cos(ang) * size, ay + sin(ang) * size)
                path.lineTo(ax + cos(ang + 2.5f) * size, ay + sin(ang + 2.5f) * size)
                path.lineTo(ax + cos(ang - 2.5f) * size, ay + sin(ang - 2.5f) * size)
                path.close()
                fill.color = col
                c.drawPath(path, fill)
                val label = "TORNA QUI"
                val tx = (ax - cos(ang) * 30f * d - text.measureText(label) / 2).coerceIn(insetLeft + 8f * d, w - insetRight - text.measureText(label) - 8f * d)
                c.drawText(label, tx, ay - sin(ang) * 30f * d + 5f * d, text)
            }
        }

        if (Opt.eraseMode && brushX >= 0f) {
            stroke.color = 0xFFB84DFF.toInt(); stroke.strokeWidth = 2f * d
            c.drawCircle(brushX, brushY, Opt.brush * d, stroke)
            fill.color = 0x33B84DFF
            c.drawCircle(brushX, brushY, Opt.brush * d, fill)
        }
        postInvalidateOnAnimation()
    }
}
