package com.sketchbook.oss

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.sin

object SketchIcons {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // --------------------------------------------------------------------------
    // TOP BAR 8 ICONS (Exact Match to Screenshot_20261025_084435.jpg)
    // --------------------------------------------------------------------------

    /** Button 1: Outlined box with 3 dots + 3 horizontal lines */
    fun drawTopIcon1Menu(c: Canvas, cx: Float, cy: Float, u: Float) {
        val w = u * 0.48f
        val h = u * 0.36f
        val left = cx - w / 2f
        val top = cy - h / 2f
        strokePaint.color = Color.rgb(108, 108, 108)
        strokePaint.strokeWidth = u * 0.032f
        c.drawRect(left, top, left + w, top + h, strokePaint)

        fillPaint.color = Color.rgb(108, 108, 108)
        for (i in 0..2) {
            val ry = top + h * (0.25f + i * 0.25f)
            val dotR = u * 0.022f
            c.drawRect(left + w * 0.16f - dotR, ry - dotR, left + w * 0.16f + dotR, ry + dotR, fillPaint)
            c.drawLine(left + w * 0.34f, ry, left + w * 0.84f, ry, strokePaint)
        }
    }

    /** Button 2 & 3: Sweeping curved Undo / Redo arrow */
    fun drawTopIconUndoRedo(c: Canvas, cx: Float, cy: Float, u: Float, isRedo: Boolean, enabled: Boolean) {
        c.save()
        c.translate(cx, cy)
        if (isRedo) c.scale(-1f, 1f)

        fillPaint.color = if (enabled) Color.rgb(118, 118, 118) else Color.rgb(200, 200, 200)
        val s = u * 0.34f
        val p = Path()
        // Arrowhead pointing left
        p.moveTo(-s * 0.95f, s * 0.05f)
        p.lineTo(-s * 0.20f, -s * 0.52f)
        p.lineTo(-s * 0.20f, -s * 0.16f)
        // Upper sweeping curve to right tail tip
        p.cubicTo(s * 0.45f, -s * 0.16f, s * 0.88f, -s * 0.32f, s * 0.95f, -s * 0.62f)
        // Lower curve returning to arrowhead bottom neck
        p.cubicTo(s * 0.90f, s * 0.18f, s * 0.42f, s * 0.28f, -s * 0.20f, s * 0.25f)
        p.lineTo(-s * 0.20f, s * 0.56f)
        p.close()
        c.drawPath(p, fillPaint)
        c.restore()
    }

    /** Button 4: 3x3 Grid of 9 outlined small squares */
    fun drawTopIcon4Grid(c: Canvas, cx: Float, cy: Float, u: Float) {
        strokePaint.color = Color.rgb(108, 108, 108)
        strokePaint.strokeWidth = u * 0.025f
        val cell = u * 0.11f
        val gap = u * 0.042f
        val total = cell * 3 + gap * 2
        val startX = cx - total / 2f
        val startY = cy - total / 2f
        for (r in 0..2) {
            for (col in 0..2) {
                val x = startX + col * (cell + gap)
                val y = startY + r * (cell + gap)
                c.drawRect(x, y, x + cell, y + cell, strokePaint)
            }
        }
    }

    /** Button 5: Side-by-side Sharpened Pencil + Fountain Pen Nib */
    fun drawTopIcon5PencilPen(c: Canvas, cx: Float, cy: Float, u: Float) {
        val color = Color.rgb(104, 104, 104)
        strokePaint.color = color
        strokePaint.strokeWidth = u * 0.028f
        fillPaint.color = color

        // Left: Pencil
        val px = cx - u * 0.15f
        val topY = cy - u * 0.25f
        val botY = cy + u * 0.28f
        val pw = u * 0.095f
        val pPath = Path().apply {
            moveTo(px, topY)
            lineTo(px - pw, cy + u * 0.04f)
            lineTo(px - pw, botY)
            moveTo(px, topY)
            lineTo(px + pw, cy + u * 0.04f)
            lineTo(px + pw, botY)
            // Wavy collar
            moveTo(px - pw, cy + u * 0.04f)
            quadTo(px, cy + u * 0.09f, px + pw, cy + u * 0.04f)
            // Inner body lines
            moveTo(px - pw * 0.35f, cy + u * 0.06f)
            lineTo(px - pw * 0.35f, botY)
            moveTo(px + pw * 0.35f, cy + u * 0.06f)
            lineTo(px + pw * 0.35f, botY)
        }
        c.drawPath(pPath, strokePaint)
        // Filled graphite tip cone
        val tipPath = Path().apply {
            moveTo(px, topY)
            lineTo(px - pw * 0.45f, topY + u * 0.12f)
            lineTo(px + pw * 0.45f, topY + u * 0.12f)
            close()
        }
        c.drawPath(tipPath, fillPaint)

        // Right: Fountain Pen Nib
        val nx = cx + u * 0.16f
        val nTop = cy - u * 0.29f
        val nw = u * 0.13f
        val nibPath = Path().apply {
            moveTo(nx, nTop)
            lineTo(nx - nw, cy + u * 0.06f)
            lineTo(nx - nw * 0.62f, cy + u * 0.19f)
            lineTo(nx + nw * 0.62f, cy + u * 0.19f)
            lineTo(nx + nw, cy + u * 0.06f)
            close()
        }
        c.drawPath(nibPath, strokePaint)
        // Nib slit & breather circle
        c.drawLine(nx, nTop, nx, cy + u * 0.02f, strokePaint)
        c.drawCircle(nx, cy + u * 0.05f, u * 0.028f, strokePaint)
        // Nib base collar
        c.drawRect(nx - nw * 0.72f, cy + u * 0.19f, nx + nw * 0.72f, cy + u * 0.25f, strokePaint)
    }

    /** Button 6: Rainbow Color Ring */
    fun drawTopIcon6ColorWheel(c: Canvas, cx: Float, cy: Float, u: Float) {
        val radius = u * 0.23f
        val ringWidth = u * 0.075f
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
        strokePaint.shader = SweepGradient(cx, cy, colors, null)
        strokePaint.strokeWidth = ringWidth
        c.drawCircle(cx, cy, radius, strokePaint)
        strokePaint.shader = null

        strokePaint.color = Color.rgb(180, 180, 180)
        strokePaint.strokeWidth = 1.5f
        c.drawCircle(cx, cy, radius + ringWidth / 2f, strokePaint)
        c.drawCircle(cx, cy, radius - ringWidth / 2f, strokePaint)
    }

    /** Button 7: Stacked Layers Diamond Icon */
    fun drawTopIcon7Layers(c: Canvas, cx: Float, cy: Float, u: Float) {
        val color = Color.rgb(108, 108, 108)
        val rx = u * 0.25f
        val ry = u * 0.11f

        // Bottom filled diamond
        val by = cy + u * 0.075f
        val botPath = Path().apply {
            moveTo(cx, by - ry)
            lineTo(cx + rx, by)
            lineTo(cx, by + ry)
            lineTo(cx - rx, by)
            close()
        }
        fillPaint.color = color
        c.drawPath(botPath, fillPaint)

        // White gap mask + Top stroked diamond
        val ty = cy - u * 0.065f
        val topPath = Path().apply {
            moveTo(cx, ty - ry)
            lineTo(cx + rx, ty)
            lineTo(cx, ty + ry)
            lineTo(cx - rx, ty)
            close()
        }
        fillPaint.color = Color.rgb(239, 239, 239)
        c.drawPath(topPath, fillPaint)
        strokePaint.color = color
        strokePaint.strokeWidth = u * 0.028f
        c.drawPath(topPath, strokePaint)
    }

    /** Button 8: Fullscreen Viewfinder Frame with 4 inner corner ticks */
    fun drawTopIcon8Viewfinder(c: Canvas, cx: Float, cy: Float, u: Float) {
        val w = u * 0.46f
        val h = u * 0.33f
        val l = cx - w / 2f
        val t = cy - h / 2f
        val r = cx + w / 2f
        val b = cy + h / 2f

        strokePaint.color = Color.rgb(108, 108, 108)
        strokePaint.strokeWidth = u * 0.026f
        c.drawRect(l, t, r, b, strokePaint)

        val p = u * 0.06f
        val tick = u * 0.065f
        // Top-left tick
        c.drawLine(l + p, t + p, l + p + tick, t + p, strokePaint)
        c.drawLine(l + p, t + p, l + p, t + p + tick, strokePaint)
        // Top-right tick
        c.drawLine(r - p - tick, t + p, r - p, t + p, strokePaint)
        c.drawLine(r - p, t + p, r - p, t + p + tick, strokePaint)
        // Bottom-left tick
        c.drawLine(l + p, b - p, l + p + tick, b - p, strokePaint)
        c.drawLine(l + p, b - p - tick, l + p, b - p, strokePaint)
        // Bottom-right tick
        c.drawLine(r - p - tick, b - p, r - p, b - p, strokePaint)
        c.drawLine(r - p, b - p - tick, r - p, b - p, strokePaint)
    }

    // --------------------------------------------------------------------------
    // BOTTOM LAGOON DOT & POPUP MENU ICONS
    // --------------------------------------------------------------------------

    fun drawBottomCenterDot(c: Canvas, cx: Float, cy: Float, radius: Float) {
        strokePaint.color = Color.argb(185, 222, 222, 222)
        strokePaint.strokeWidth = radius * 0.28f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        c.drawArc(rect, 195f, 150f, false, strokePaint)
        c.drawArc(rect, 15f, 150f, false, strokePaint)
        strokePaint.strokeWidth = radius * 0.14f
        c.drawCircle(cx, cy, radius * 0.42f, strokePaint)
    }

    fun drawBackChevron(c: Canvas, cx: Float, cy: Float, size: Float, color: Int = Color.rgb(95, 95, 95)) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.13f
        val p = Path().apply {
            moveTo(cx + size * 0.25f, cy - size * 0.45f)
            lineTo(cx - size * 0.25f, cy)
            lineTo(cx + size * 0.25f, cy + size * 0.45f)
        }
        c.drawPath(p, strokePaint)
    }

    fun drawRightChevron(c: Canvas, cx: Float, cy: Float, size: Float, color: Int = Color.rgb(95, 95, 95)) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.13f
        val p = Path().apply {
            moveTo(cx - size * 0.25f, cy - size * 0.45f)
            lineTo(cx + size * 0.25f, cy)
            lineTo(cx - size * 0.25f, cy + size * 0.45f)
        }
        c.drawPath(p, strokePaint)
    }

    fun drawPlusIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int = Color.rgb(90, 90, 90)) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.16f
        val half = size * 0.45f
        c.drawLine(cx - half, cy, cx + half, cy, strokePaint)
        c.drawLine(cx, cy - half, cx, cy + half, strokePaint)
    }

    fun drawListBarsIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int = Color.rgb(55, 55, 55)) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.11f
        fillPaint.color = color
        val w = size * 0.95f
        val h = size * 0.65f
        val left = cx - w / 2f
        for (i in -1..1) {
            val y = cy + i * (h * 0.42f)
            val d = size * 0.06f
            c.drawRect(left, y - d, left + d * 2, y + d, fillPaint)
            c.drawLine(left + w * 0.26f, y, left + w, y, strokePaint)
        }
    }

    /** Save Icon: tray with downward arrow and indicator dot */
    fun drawSaveIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val color = Color.rgb(95, 95, 95)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.10f
        val w = size * 0.78f
        val h = size * 0.36f
        c.drawRect(cx - w / 2f, cy + size * 0.05f, cx + w / 2f, cy + size * 0.05f + h, strokePaint)
        // Down arrow above tray
        c.drawLine(cx, cy - size * 0.44f, cx, cy - size * 0.02f, strokePaint)
        c.drawLine(cx - size * 0.18f, cy - size * 0.18f, cx, cy - size * 0.02f, strokePaint)
        c.drawLine(cx + size * 0.18f, cy - size * 0.18f, cx, cy - size * 0.02f, strokePaint)
        fillPaint.color = color
        c.drawCircle(cx + w * 0.25f, cy + size * 0.23f, size * 0.045f, fillPaint)
    }

    /** Import Icon: open tray with downward arrow */
    fun drawImportIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val color = Color.rgb(95, 95, 95)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.11f
        val w = size * 0.78f
        val bot = cy + size * 0.38f
        c.drawLine(cx - w / 2f, cy + size * 0.08f, cx - w / 2f, bot, strokePaint)
        c.drawLine(cx - w / 2f, bot, cx + w / 2f, bot, strokePaint)
        c.drawLine(cx + w / 2f, cy + size * 0.08f, cx + w / 2f, bot, strokePaint)
        c.drawLine(cx, cy - size * 0.42f, cx, cy + size * 0.16f, strokePaint)
        c.drawLine(cx - size * 0.20f, cy - size * 0.04f, cx, cy + size * 0.16f, strokePaint)
        c.drawLine(cx + size * 0.20f, cy - size * 0.04f, cx, cy + size * 0.16f, strokePaint)
    }

    /** Gear Icon for Preferences & Preset Settings */
    fun drawGearIcon(c: Canvas, cx: Float, cy: Float, size: Float, filled: Boolean = true) {
        val color = Color.rgb(98, 98, 98)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.10f
        val outerR = size * 0.42f
        val innerR = size * 0.20f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            val x1 = cx + (outerR * 0.7f * cos(a)).toFloat()
            val y1 = cy + (outerR * 0.7f * sin(a)).toFloat()
            val x2 = cx + (outerR * 1.06f * cos(a)).toFloat()
            val y2 = cy + (outerR * 1.06f * sin(a)).toFloat()
            strokePaint.strokeWidth = size * 0.14f
            c.drawLine(x1, y1, x2, y2, strokePaint)
        }
        strokePaint.strokeWidth = size * 0.11f
        c.drawCircle(cx, cy, outerR * 0.76f, strokePaint)
        if (filled) {
            c.drawCircle(cx, cy, innerR, strokePaint)
        }
    }

    /** Share Icon with optional orange star badge */
    fun drawShareIcon(c: Canvas, cx: Float, cy: Float, size: Float, withStar: Boolean) {
        val color = Color.rgb(95, 95, 95)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.10f
        val w = size * 0.74f
        val top = cy - size * 0.06f
        val bot = cy + size * 0.42f
        c.drawLine(cx - w / 2f, top, cx - w / 2f, bot, strokePaint)
        c.drawLine(cx - w / 2f, bot, cx + w / 2f, bot, strokePaint)
        c.drawLine(cx + w / 2f, top, cx + w / 2f, bot, strokePaint)
        c.drawLine(cx - w / 2f, top, cx - w * 0.18f, top, strokePaint)
        c.drawLine(cx + w * 0.18f, top, cx + w / 2f, top, strokePaint)

        // Upward arrow
        c.drawLine(cx, cy + size * 0.18f, cx, cy - size * 0.44f, strokePaint)
        c.drawLine(cx - size * 0.20f, cy - size * 0.24f, cx, cy - size * 0.44f, strokePaint)
        c.drawLine(cx + size * 0.20f, cy - size * 0.24f, cx, cy - size * 0.44f, strokePaint)

        if (withStar) {
            drawOrangeStar(c, cx + size * 0.36f, cy - size * 0.36f, size * 0.24f)
        }
    }

    fun drawOrangeStar(c: Canvas, cx: Float, cy: Float, r: Float) {
        fillPaint.color = Color.rgb(243, 123, 33)
        val p = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.42f
            val angle = Math.toRadians((i * 36 - 90).toDouble())
            val x = cx + (rad * cos(angle)).toFloat()
            val y = cy + (rad * sin(angle)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        c.drawPath(p, fillPaint)
    }

    /** Mods Menu Icon: Sharp Isometric Sliders / Module Grid */
    fun drawModMenuIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val color = Color.rgb(90, 90, 90)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.10f
        fillPaint.color = color
        val w = size * 0.80f
        for (i in -1..1) {
            val y = cy + i * size * 0.28f
            c.drawLine(cx - w / 2f, y, cx + w / 2f, y, strokePaint)
            val knobX = cx + (if (i == 0) -w * 0.18f else w * 0.20f)
            val kr = size * 0.09f
            c.drawRect(knobX - kr, y - kr, knobX + kr, y + kr, fillPaint)
        }
    }

    /** Project Name Tag / Rename Icon */
    fun drawProjectNameIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val color = Color.rgb(95, 95, 95)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.10f
        val w = size * 0.72f
        val h = size * 0.84f
        c.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, strokePaint)
        c.drawLine(cx - w * 0.25f, cy - h * 0.18f, cx + w * 0.25f, cy - h * 0.18f, strokePaint)
        c.drawLine(cx - w * 0.25f, cy + h * 0.05f, cx + w * 0.25f, cy + h * 0.05f, strokePaint)
        c.drawLine(cx - w * 0.25f, cy + h * 0.28f, cx + w * 0.05f, cy + h * 0.28f, strokePaint)
    }

    /** Canvas Preview Rotate Icon (Square with arrow on right side, matching 091729.jpg) */
    fun drawCanvasRotateIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val color = Color.rgb(110, 110, 110)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.09f
        val s = size * 0.36f
        val p = Path().apply {
            moveTo(cx + s * 0.35f, cy + s)
            lineTo(cx - s, cy + s)
            lineTo(cx - s, cy - s)
            lineTo(cx + s * 0.65f, cy - s)
            lineTo(cx + s * 0.65f, cy + s * 0.15f)
        }
        c.drawPath(p, strokePaint)
        // Downward arrowhead at (cx + s*0.65, cy + s*0.20)
        val ax = cx + s * 0.65f
        val ay = cy + s * 0.22f
        val aw = size * 0.18f
        c.drawLine(ax - aw, ay - aw, ax, ay, strokePaint)
        c.drawLine(ax + aw, ay - aw, ax, ay, strokePaint)
    }

    /**
     * Aspect Ratio Link Icon between Width & Height boxes:
     * Unlocked: () curved open link brackets
     * Locked:   (-) curved link brackets with solid center lock line
     */
    fun drawLinkRatioIcon(c: Canvas, cx: Float, cy: Float, size: Float, locked: Boolean) {
        val color = if (locked) Color.rgb(55, 116, 205) else Color.rgb(96, 96, 96)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.13f
        strokePaint.strokeCap = Paint.Cap.ROUND

        val w = size * 0.46f
        val h = size * 0.28f
        // Left curved bracket '('
        val leftRect = RectF(cx - w, cy - h, cx - w * 0.12f, cy + h)
        c.drawArc(leftRect, 85f, 190f, false, strokePaint)

        // Right curved bracket ')'
        val rightRect = RectF(cx + w * 0.12f, cy - h, cx + w, cy + h)
        c.drawArc(rightRect, -95f, 190f, false, strokePaint)

        if (locked) {
            // Center horizontal bar '-' making it '(-)'
            c.drawLine(cx - w * 0.42f, cy, cx + w * 0.42f, cy, strokePaint)
        }
        strokePaint.strokeCap = Paint.Cap.SQUARE
    }

    /** Reorder Handle Icon (in place of old Delete icon on preset rows) */
    fun drawReorderIcon(c: Canvas, cx: Float, cy: Float, size: Float, active: Boolean = false) {
        val color = if (active) Color.rgb(55, 116, 205) else Color.rgb(105, 105, 105)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.09f
        val w = size * 0.48f
        // Three grip bars
        c.drawLine(cx - w / 2f, cy - size * 0.14f, cx + w / 2f, cy - size * 0.14f, strokePaint)
        c.drawLine(cx - w / 2f, cy, cx + w / 2f, cy, strokePaint)
        c.drawLine(cx - w / 2f, cy + size * 0.14f, cx + w / 2f, cy + size * 0.14f, strokePaint)
        // Up arrow tip
        val aw = size * 0.16f
        val topY = cy - size * 0.42f
        c.drawLine(cx - aw, topY + aw, cx, topY, strokePaint)
        c.drawLine(cx + aw, topY + aw, cx, topY, strokePaint)
        // Down arrow tip
        val botY = cy + size * 0.42f
        c.drawLine(cx - aw, botY - aw, cx, botY, strokePaint)
        c.drawLine(cx + aw, botY - aw, cx, botY, strokePaint)
    }

    /** Trash / Delete Icon (used inside Edit Preset settings menu) */
    fun drawTrashIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int = Color.rgb(190, 50, 50)) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.09f
        val w = size * 0.56f
        val h = size * 0.66f
        c.drawRect(cx - w / 2f, cy - h * 0.30f, cx + w / 2f, cy + h * 0.52f, strokePaint)
        c.drawLine(cx - w * 0.65f, cy - h * 0.30f, cx + w * 0.65f, cy - h * 0.30f, strokePaint)
        c.drawRect(cx - w * 0.22f, cy - h * 0.48f, cx + w * 0.22f, cy - h * 0.30f, strokePaint)
        c.drawLine(cx - w * 0.16f, cy - h * 0.12f, cx - w * 0.16f, cy + h * 0.34f, strokePaint)
        c.drawLine(cx + w * 0.16f, cy - h * 0.12f, cx + w * 0.16f, cy + h * 0.34f, strokePaint)
    }

    /** Screen Phone Icon & Last Dimension Icon in Presets Tab */
    fun drawPhoneScreenIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(95, 95, 95)
        strokePaint.strokeWidth = size * 0.08f
        val w = size * 0.54f
        val h = size * 0.88f
        c.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, strokePaint)
        c.drawLine(cx - w * 0.18f, cy + h * 0.36f, cx + w * 0.18f, cy + h * 0.36f, strokePaint)
    }

    fun drawLastCanvasIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(95, 95, 95)
        strokePaint.strokeWidth = size * 0.075f
        val s = size * 0.64f
        c.drawRect(cx - s * 0.55f, cy - s * 0.50f, cx + s * 0.35f, cy + s * 0.30f, strokePaint)
        // Return arrow inside
        c.drawLine(cx - s * 0.32f, cy - s * 0.10f, cx + s * 0.12f, cy - s * 0.10f, strokePaint)
        c.drawLine(cx - s * 0.32f, cy - s * 0.10f, cx - s * 0.14f, cy - s * 0.28f, strokePaint)
        c.drawLine(cx - s * 0.32f, cy - s * 0.10f, cx - s * 0.14f, cy + s * 0.08f, strokePaint)
        // Dimension T-bars on right & bottom
        c.drawLine(cx + s * 0.52f, cy - s * 0.50f, cx + s * 0.52f, cy + s * 0.30f, strokePaint)
        c.drawLine(cx - s * 0.55f, cy + s * 0.46f, cx + s * 0.35f, cy + s * 0.46f, strokePaint)
    }

    /** Save Menu Picture Icons (Single & Copy) */
    fun drawPictureSaveIcon(c: Canvas, cx: Float, cy: Float, size: Float, isCopy: Boolean) {
        val color = Color.rgb(98, 98, 98)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.08f
        fillPaint.color = color

        val s = size * 0.68f
        val ox = if (isCopy) size * 0.08f else 0f
        val oy = if (isCopy) size * 0.08f else 0f

        if (isCopy) {
            // Back card outline
            c.drawRect(cx - s / 2f - ox, cy - s / 2f - oy, cx + s / 2f - ox, cy + s / 2f - oy, strokePaint)
            fillPaint.color = Color.rgb(243, 243, 243)
            c.drawRect(cx - s / 2f + ox, cy - s / 2f + oy, cx + s / 2f + ox, cy + s / 2f + oy, fillPaint)
            fillPaint.color = color
        }

        val l = cx - s / 2f + ox
        val t = cy - s / 2f + oy
        val r = cx + s / 2f + ox
        val b = cy + s / 2f + oy
        c.drawRect(l, t, r, b, strokePaint)

        // Mountain silhouette inside frame
        val mPath = Path().apply {
            moveTo(l, b)
            lineTo(l + (r - l) * 0.32f, t + (b - t) * 0.48f)
            lineTo(l + (r - l) * 0.58f, t + (b - t) * 0.72f)
            lineTo(l + (r - l) * 0.80f, t + (b - t) * 0.54f)
            lineTo(r, b)
            close()
        }
        c.drawPath(mPath, fillPaint)
    }

    /** Folder Icon for Export to Files */
    fun drawFolderIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(98, 98, 98)
        strokePaint.strokeWidth = size * 0.09f
        val w = size * 0.82f
        val h = size * 0.58f
        val l = cx - w / 2f
        val t = cy - h / 2f
        val p = Path().apply {
            moveTo(l, t)
            lineTo(l + w * 0.36f, t)
            lineTo(l + w * 0.46f, t + h * 0.18f)
            lineTo(l + w, t + h * 0.18f)
            lineTo(l + w, t + h)
            lineTo(l, t + h)
            close()
        }
        c.drawPath(p, strokePaint)
    }

    /** File Format Icons (TIF, PSD, PNG, JPG, PDF) matching 094850.jpg */
    fun drawFileFormatIcon(c: Canvas, cx: Float, cy: Float, size: Float, label: String, stacked: Boolean, starred: Boolean) {
        val color = Color.rgb(92, 92, 92)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.055f
        val w = size * 0.64f
        val h = size * 0.76f

        if (stacked) {
            for (k in 2 downTo 1) {
                val off = k * size * 0.07f
                c.drawRect(cx - w / 2f + off, cy - h / 2f + off, cx + w / 2f + off, cy + h / 2f + off, strokePaint)
            }
            fillPaint.color = Color.rgb(243, 243, 243)
            c.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, fillPaint)
            c.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, strokePaint)
            textPaint.color = color
            textPaint.textSize = size * 0.25f
            c.drawText(label, cx, cy + size * 0.09f, textPaint)
        } else {
            val l = cx - w / 2f
            val t = cy - h / 2f
            val r = cx + w / 2f
            val b = cy + h / 2f
            val fold = w * 0.28f
            val p = Path().apply {
                moveTo(l, t)
                lineTo(r - fold, t)
                lineTo(r, t + fold)
                lineTo(r, b)
                lineTo(l, b)
                close()
            }
            fillPaint.color = color
            c.drawPath(p, fillPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = size * 0.24f
            c.drawText(label, cx, cy + size * 0.12f, textPaint)
        }

        if (starred) {
            drawOrangeStar(c, cx + w * 0.50f, cy - h * 0.46f, size * 0.26f)
        }
    }
}
