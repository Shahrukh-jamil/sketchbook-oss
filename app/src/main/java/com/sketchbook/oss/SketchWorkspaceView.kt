package com.sketchbook.oss

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class InlineEditField {
    NONE,
    WIDTH_BOX,
    HEIGHT_BOX,
    PRESET_NAME_BOX,
    PROJECT_NAME_BOX
}

class SketchWorkspaceView(
    context: Context,
    private val callbacks: HostCallbacks
) : View(context) {

    interface HostCallbacks {
        fun onRequestImportImage()
        fun onBeginInlineEdit(
            field: InlineEditField,
            bounds: RectF,
            initialText: String,
            numeric: Boolean,
            centered: Boolean,
            textSizePx: Float,
            textColor: Int,
            bgColor: Int
        )
        fun onEndInlineEdit()
    }

    val modEngine = ModCoreEngine(context) { invalidate() }
    val hwScreenSize: Pair<Int, Int> = SketchProjectState.getHardwareScreenResolution(context)
    var project = SketchProjectState(width = hwScreenSize.first, height = hwScreenSize.second)
    val customPresets = ProjectStorageManager.loadCustomPresets(context)
    val brushConfig = CoolBrushConfig()

    var popupState: TopPopupState = TopPopupState.NONE
    var newSketchTab: NewSketchTab = NewSketchTab.PLUS_CUSTOM
    var activeInlineField: InlineEditField = InlineEditField.NONE

    var customWidthInput: Int = hwScreenSize.first
    var customHeightInput: Int = hwScreenSize.second
    var isRatioLocked: Boolean = false
    var lockedAspectRatio: Double = hwScreenSize.first.toDouble() / max(1, hwScreenSize.second).toDouble()

    var editingPresetIndex: Int = -1
    var editingPresetName: String = "Letter"

    // Scroll & Reorder States
    private var presetListScrollY: Float = 0f
    private var modListScrollY: Float = 0f
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var lastTouchY: Float = 0f
    private var isDraggingPreset: Boolean = false
    private var draggedPresetIndex: Int = -1
    private var dragCurrentY: Float = 0f
    private var isScrollingPresets: Boolean = false
    private var isScrollingMods: Boolean = false
    private var longPressRunnable: Runnable? = null

    // Multi-Touch Canvas Zoom, Pan & 360 Rotation State
    private var canvasScale: Float = 1f
    private var canvasRotationDeg: Float = 0f
    private var canvasTransX: Float = 0f
    private var canvasTransY: Float = 0f
    private val viewMatrix = Matrix()
    private val inverseViewMatrix = Matrix()
    private var isTwoFingerGesture: Boolean = false
    private var prevPinchDist: Float = 0f
    private var prevPinchAngle: Float = 0f
    private var prevMidX: Float = 0f
    private var prevMidY: Float = 0f

    // 1-Finger / Stylus Cool Brush Stroke State
    private var isDrawingStroke: Boolean = false
    private var isDraggingBrushSlider: String? = null
    private var lastCanvasPt = PointF()
    private var prevCanvasPt = PointF()
    private var smoothX = 0f
    private var smoothY = 0f
    private var lastStrokeSpeed = 0f
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var statusBannerText: String? = null
    private var statusBannerExpiry: Long = 0L
    private val animStartNano = System.nanoTime()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class HitZone(val id: String, val rect: RectF, val payload: Int = -1)
    private val hitZones = mutableListOf<HitZone>()
    private var currentPopupBounds = RectF()
    private var presetScrollAreaBounds = RectF()
    private var modScrollAreaBounds = RectF()
    private var presetRowHeightPx = 1f
    private var brushSizeSliderRect = RectF()
    private var brushOpacitySliderRect = RectF()
    private var brushSmoothSliderRect = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val last = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
        customWidthInput = last.first
        customHeightInput = last.second
        lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
        project.ensureCanvasBitmap()
    }

    fun resetCanvasTransform() {
        canvasScale = 1f
        canvasRotationDeg = 0f
        canvasTransX = 0f
        canvasTransY = 0f
        invalidate()
    }

    fun onInlineTextUpdatedLive(field: InlineEditField, newText: String) {
        when (field) {
            InlineEditField.WIDTH_BOX -> {
                val newW = newText.toIntOrNull()?.coerceIn(0, 16384) ?: 0
                customWidthInput = newW
                if (isRatioLocked && lockedAspectRatio > 0.0001) {
                    customHeightInput = if (newW == 0) 0 else max(1, (newW / lockedAspectRatio).roundToInt())
                }
            }
            InlineEditField.HEIGHT_BOX -> {
                val newH = newText.toIntOrNull()?.coerceIn(0, 16384) ?: 0
                customHeightInput = newH
                if (isRatioLocked && lockedAspectRatio > 0.0001) {
                    customWidthInput = if (newH == 0) 0 else max(1, (newH * lockedAspectRatio).roundToInt())
                }
            }
            InlineEditField.PRESET_NAME_BOX -> {
                editingPresetName = newText
            }
            InlineEditField.PROJECT_NAME_BOX -> {
                if (newText.isNotBlank()) {
                    project.name = newText
                    project.isDirty = true
                }
            }
            InlineEditField.NONE -> {}
        }
        invalidate()
    }

    fun closeInlineEditor() {
        if (activeInlineField != InlineEditField.NONE) {
            activeInlineField = InlineEditField.NONE
            callbacks.onEndInlineEdit()
            invalidate()
        }
    }

    fun showBanner(msg: String) {
        statusBannerText = msg
        statusBannerExpiry = System.currentTimeMillis() + 2800L
        invalidate()
        postDelayed(Runnable { invalidate() }, 2900L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitZones.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sx = w / 1080f
        val topBarH = 131f * sx
        val sideFrame = 9f * sx
        val timeSec = (System.nanoTime() - animStartNano) / 1_000_000_000f
        val pal = modEngine.resolveUiPalette()

        // 1. Draw Workspace Backdrop
        canvas.drawColor(pal.workspaceBackdrop)

        // 2. Compute Base Fitted Canvas Rect & Apply Multi-Touch Zoom / Pan / Rotation Matrix
        val availW = w - sideFrame * 2f
        val availH = h - topBarH
        val bmp = project.ensureCanvasBitmap()
        val projW = max(1, project.width).toFloat()
        val projH = max(1, project.height).toFloat()
        val fitScale = min(availW / projW, availH / projH)
        val baseDrawW = projW * fitScale
        val baseDrawH = projH * fitScale
        val baseLeft = sideFrame + (availW - baseDrawW) / 2f
        val baseTop = topBarH + (availH - baseDrawH) / 2f
        val baseRect = RectF(baseLeft, baseTop, baseLeft + baseDrawW, baseTop + baseDrawH)

        viewMatrix.reset()
        viewMatrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        val bmpToScreenScaleX = baseDrawW / bmp.width.toFloat()
        val bmpToScreenScaleY = baseDrawH / bmp.height.toFloat()
        viewMatrix.postScale(bmpToScreenScaleX * canvasScale, bmpToScreenScaleY * canvasScale)
        viewMatrix.postRotate(canvasRotationDeg)
        viewMatrix.postTranslate(baseRect.centerX() + canvasTransX, baseRect.centerY() + canvasTransY)
        viewMatrix.invert(inverseViewMatrix)

        // Draw Transformed Canvas Paper + Bitmap + Mod Guides
        canvas.save()
        canvas.clipRect(sideFrame, topBarH, w - sideFrame, h)
        canvas.concat(viewMatrix)

        val bmpRect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        shadowPaint.color = Color.argb(65, 0, 0, 0)
        canvas.drawRect(-4f, -4f, bmp.width + 8f, bmp.height + 8f, shadowPaint)

        bgPaint.style = Paint.Style.FILL
        bgPaint.color = pal.paperTint
        canvas.drawRect(bmpRect, bgPaint)
        canvas.drawBitmap(bmp, 0f, 0f, null)

        modEngine.renderCanvasOverlays(canvas, bmpRect, project)
        canvas.restore()

        // 3. Draw Bottom Dot + Animated Workspace Mod Effects
        val dotY = h - 136f * sx
        val dotRadius = 15.5f * sx
        modEngine.renderWorkspaceAnimatedEffects(canvas, w, h, topBarH, w / 2f, dotY, dotRadius, timeSec)
        SketchIcons.drawBottomCenterDot(canvas, w / 2f, dotY, dotRadius)

        // 4. Draw Top Menu Bar (8 Exact Buttons + Animated Aurora / Laser FX)
        drawTopMenuBar(canvas, w, topBarH, sideFrame, pal, timeSec)

        // 5. Draw Active Popup Menu Directly Below Top Bar (0px Vertical Gap!)
        currentPopupBounds.setEmpty()
        when (popupState) {
            TopPopupState.NONE -> {}
            TopPopupState.BUTTON1_MAIN -> drawButton1MainMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.NEW_SKETCH -> drawNewSketchMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.EDIT_PRESET -> drawEditPresetMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.SAVE_MENU -> drawSaveMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.SHARE_EXPORT_MAIN -> drawShareExportMainMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.EXPORT_AS_MENU -> drawFormatListMenu(canvas, sideFrame, topBarH, sx, "Export As", pal)
            TopPopupState.SHARE_AS_MENU -> drawFormatListMenu(canvas, sideFrame, topBarH, sx, "Share As", pal)
            TopPopupState.MOD_MENU -> drawModMenu(canvas, sideFrame, topBarH, sx, pal)
            TopPopupState.BRUSH_MENU -> drawBrushMenu(canvas, sideFrame, topBarH, sx, pal)
        }

        if (!currentPopupBounds.isEmpty) {
            modEngine.renderPopupAnimatedBorder(canvas, currentPopupBounds, timeSec)
        }

        // 6. Status Banner
        val now = System.currentTimeMillis()
        val bannerMsg = statusBannerText
        if (bannerMsg != null && now < statusBannerExpiry) {
            textPaint.textSize = 30f * sx
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            val bw = min(w - 60f * sx, textPaint.measureText(bannerMsg) + 80f * sx)
            val bh = 78f * sx
            val bx = (w - bw) / 2f
            val by = h - 250f * sx
            bgPaint.color = Color.argb(230, 32, 35, 42)
            canvas.drawRect(bx, by, bx + bw, by + bh, bgPaint)
            borderPaint.color = pal.accentColor
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(bx, by, bx + bw, by + bh, borderPaint)
            canvas.drawText(bannerMsg, w / 2f, by + bh * 0.64f, textPaint)
        }

        // Keep 60fps loop running when any UI animation bundle is toggled ON
        if (modEngine.hasActiveAnimations()) {
            postInvalidateOnAnimation()
        }
    }

    // ==========================================================================
    // TOP BAR (Buttons 1, 2 [Undo], 3 [Redo], 5 [Brush Menu] Active!)
    // ==========================================================================
    private fun drawTopMenuBar(canvas: Canvas, w: Float, topBarH: Float, sideFrame: Float, pal: ModUiPalette, timeSec: Float) {
        bgPaint.style = Paint.Style.FILL
        bgPaint.color = pal.topBarBg
        canvas.drawRect(sideFrame, 0f, w - sideFrame, topBarH, bgPaint)

        modEngine.renderTopBarAnimatedEffects(canvas, sideFrame, 0f, w - sideFrame, topBarH, timeSec)

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f
        canvas.drawLine(sideFrame, topBarH, w - sideFrame, topBarH, borderPaint)

        val innerW = w - sideFrame * 2f
        val slotW = innerW / 8f
        val cy = topBarH / 2f

        val activeSlot = when (popupState) {
            TopPopupState.NONE -> -1
            TopPopupState.BRUSH_MENU -> 4
            else -> 0
        }
        if (activeSlot >= 0) {
            bgPaint.color = pal.topBarActiveSlot
            canvas.drawRect(sideFrame + slotW * activeSlot, 0f, sideFrame + slotW * (activeSlot + 1), topBarH, bgPaint)
        }

        for (i in 0 until 8) {
            val slotLeft = sideFrame + slotW * i
            val slotRect = RectF(slotLeft, 0f, slotLeft + slotW, topBarH)
            val cx = slotRect.centerX()
            when (i) {
                0 -> {
                    SketchIcons.drawTopIcon1Menu(canvas, cx, cy, topBarH, pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_1", slotRect))
                }
                1 -> {
                    SketchIcons.drawTopIconUndoRedo(canvas, cx, cy, topBarH, isRedo = false, enabled = project.canUndo(), tint = pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_UNDO", slotRect))
                }
                2 -> {
                    SketchIcons.drawTopIconUndoRedo(canvas, cx, cy, topBarH, isRedo = true, enabled = project.canRedo(), tint = pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_REDO", slotRect))
                }
                3 -> SketchIcons.drawTopIcon4Grid(canvas, cx, cy, topBarH, pal.iconTint)
                4 -> {
                    SketchIcons.drawTopIcon5PencilPen(canvas, cx, cy, topBarH, pal.iconTint)
                    hitZones.add(HitZone("TOP_BTN_BRUSH", slotRect))
                }
                5 -> SketchIcons.drawTopIcon6ColorWheel(canvas, cx, cy, topBarH)
                6 -> SketchIcons.drawTopIcon7Layers(canvas, cx, cy, topBarH, pal.iconTint, pal.topBarBg)
                7 -> SketchIcons.drawTopIcon8Viewfinder(canvas, cx, cy, topBarH, pal.iconTint)
            }
        }
    }

    // ==========================================================================
    // BUTTON 5: COOL BRUSH STUDIO POPUP MENU
    // ==========================================================================
    private fun drawBrushMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 660f * sx
        val panelH = 980f * sx
        val popLeft = (width - panelW) / 2f
        val right = popLeft + panelW
        val bottom = top + panelH
        currentPopupBounds.set(popLeft, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        // Header
        val headerH = 118f * sx
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(popLeft, top, right, top + headerH, bgPaint)
        textPaint.color = pal.textPrimary
        textPaint.textSize = 36f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        canvas.drawText(brushConfig.mode.title, popLeft + 28f * sx, top + 72f * sx, textPaint)
        textPaint.isFakeBoldText = false

        // Reset Zoom/Rotate View Button in Header Right
        val resetRect = RectF(right - 210f * sx, top + 22f * sx, right - 22f * sx, top + headerH - 22f * sx)
        bgPaint.color = pal.cardBg
        canvas.drawRect(resetRect, bgPaint)
        borderPaint.color = pal.borderColor
        canvas.drawRect(resetRect, borderPaint)
        subTextPaint.color = pal.textPrimary
        subTextPaint.textSize = 24f * sx
        subTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Reset View", resetRect.centerX(), resetRect.centerY() + 9f * sx, subTextPaint)
        hitZones.add(HitZone("BRUSH_RESET_VIEW", resetRect))

        var cy = top + headerH + 22f * sx

        // Live Sine-Wave Brush Stamp Preview Box
        val prevH = 150f * sx
        val prevRect = RectF(popLeft + 26f * sx, cy, right - 26f * sx, cy + prevH)
        bgPaint.color = pal.paperTint
        canvas.drawRect(prevRect, bgPaint)
        borderPaint.color = pal.borderColor
        canvas.drawRect(prevRect, borderPaint)

        drawLiveBrushSinePreview(canvas, prevRect, sx)
        cy += prevH + 24f * sx

        // 3 Cool Brush Mode Tabs (Sharp corners)
        val modes = CoolBrushMode.values()
        val tabW = (panelW - 52f * sx) / modes.size
        val tabH = 74f * sx
        for ((i, m) in modes.withIndex()) {
            val tx = popLeft + 26f * sx + i * tabW
            val tRect = RectF(tx, cy, tx + tabW, cy + tabH)
            val sel = (brushConfig.mode == m)
            bgPaint.color = if (sel) pal.accentColor else pal.cardBg
            canvas.drawRect(tRect, bgPaint)
            borderPaint.color = pal.borderColor
            canvas.drawRect(tRect, borderPaint)

            subTextPaint.color = if (sel) Color.WHITE else pal.textPrimary
            subTextPaint.textSize = 24f * sx
            subTextPaint.textAlign = Paint.Align.CENTER
            val shortName = when (m) {
                CoolBrushMode.STUDIO_INK -> "Studio Ink"
                CoolBrushMode.NEON_GLOW -> "Neon Glow"
                CoolBrushMode.CHISEL_PEN -> "Chisel Pen"
            }
            canvas.drawText(shortName, tRect.centerX(), tRect.centerY() + 9f * sx, subTextPaint)
            hitZones.add(HitZone("BRUSH_MODE_SELECT", tRect, i))
        }
        cy += tabH + 34f * sx

        // Sliders: Size, Opacity, Streamline Smoothing
        cy = drawSharpBrushSlider(
            canvas, popLeft + 28f * sx, right - 28f * sx, cy, sx, pal,
            label = "Size",
            valueText = "${brushConfig.sizePx.roundToInt()} px",
            fraction = ((brushConfig.sizePx - 2f) / 148f).coerceIn(0f, 1f),
            outRect = brushSizeSliderRect
        )
        cy = drawSharpBrushSlider(
            canvas, popLeft + 28f * sx, right - 28f * sx, cy, sx, pal,
            label = "Opacity",
            valueText = "${(brushConfig.opacity * 100 / 255)}%",
            fraction = (brushConfig.opacity / 255f).coerceIn(0f, 1f),
            outRect = brushOpacitySliderRect
        )
        cy = drawSharpBrushSlider(
            canvas, popLeft + 28f * sx, right - 28f * sx, cy, sx, pal,
            label = "Streamline",
            valueText = "${(brushConfig.smoothing * 100).roundToInt()}%",
            fraction = brushConfig.smoothing.coerceIn(0f, 1f),
            outRect = brushSmoothSliderRect
        )

        // Quick Color Swatches + Clear Canvas Button
        cy += 12f * sx
        val swatches = intArrayOf(
            Color.rgb(20, 24, 30),
            Color.rgb(37, 99, 235),
            Color.rgb(0, 200, 255),
            Color.rgb(225, 29, 72),
            Color.rgb(16, 185, 129),
            Color.rgb(245, 158, 11),
            Color.rgb(139, 92, 246),
            Color.WHITE
        )
        val swGap = 14f * sx
        val swSize = ((panelW - 56f * sx - swGap * (swatches.size - 1)) / swatches.size)
        for (i in swatches.indices) {
            val sxLeft = popLeft + 28f * sx + i * (swSize + swGap)
            val swRect = RectF(sxLeft, cy, sxLeft + swSize, cy + swSize)
            bgPaint.color = swatches[i]
            canvas.drawRect(swRect, bgPaint)
            borderPaint.color = if (brushConfig.color == swatches[i]) pal.accentColor else Color.rgb(140, 140, 140)
            borderPaint.strokeWidth = if (brushConfig.color == swatches[i]) 4.5f * sx else 2f * sx
            canvas.drawRect(swRect, borderPaint)
            hitZones.add(HitZone("BRUSH_SWATCH", swRect, swatches[i]))
        }
        cy += swSize + 28f * sx

        val clearRect = RectF(popLeft + 28f * sx, cy, right - 28f * sx, cy + 74f * sx)
        bgPaint.color = pal.cardBg
        canvas.drawRect(clearRect, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(clearRect, borderPaint)
        textPaint.color = pal.textPrimary
        textPaint.textSize = 29f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Clear Canvas Layer", clearRect.centerX(), clearRect.centerY() + 10f * sx, textPaint)
        hitZones.add(HitZone("BRUSH_CLEAR_CANVAS", clearRect))
    }

    private fun drawLiveBrushSinePreview(canvas: Canvas, rect: RectF, sx: Float) {
        canvas.save()
        canvas.clipRect(rect)
        val steps = 48
        val padX = 36f * sx
        val usableW = rect.width() - padX * 2f
        val cy = rect.centerY()
        val amp = rect.height() * 0.26f

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val x = rect.left + padX + t * usableW
            val y = cy + sin(t * Math.PI * 2.0).toFloat() * amp
            val taper = sin(t * Math.PI).toFloat().coerceIn(0.12f, 1f)
            val rad = (brushConfig.sizePx * 0.5f * sx * taper).coerceAtLeast(1.5f)

            if (brushConfig.mode == CoolBrushMode.NEON_GLOW) {
                strokePaint.color = brushConfig.color
                strokePaint.alpha = (brushConfig.opacity * 0.22f).toInt().coerceIn(10, 120)
                canvas.drawCircle(x, y, rad * 2.2f, strokePaint)
                strokePaint.color = Color.WHITE
                strokePaint.alpha = brushConfig.opacity
                canvas.drawCircle(x, y, rad * 0.65f, strokePaint)
            } else if (brushConfig.mode == CoolBrushMode.CHISEL_PEN) {
                strokePaint.color = brushConfig.color
                strokePaint.alpha = brushConfig.opacity
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(-35f)
                canvas.drawRect(-rad * 1.4f, -rad * 0.35f, rad * 1.4f, rad * 0.35f, strokePaint)
                canvas.restore()
            } else {
                strokePaint.color = brushConfig.color
                strokePaint.alpha = brushConfig.opacity
                canvas.drawCircle(x, y, rad, strokePaint)
            }
        }
        canvas.restore()
    }

    private fun drawSharpBrushSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        sx: Float,
        pal: ModUiPalette,
        label: String,
        valueText: String,
        fraction: Float,
        outRect: RectF
    ): Float {
        subTextPaint.color = pal.textPrimary
        subTextPaint.textSize = 27f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, left, top + 28f * sx, subTextPaint)

        subTextPaint.textAlign = Paint.Align.RIGHT
        subTextPaint.color = pal.textSecondary
        canvas.drawText(valueText, right, top + 28f * sx, subTextPaint)

        val trackTop = top + 44f * sx
        val trackH = 38f * sx
        outRect.set(left, trackTop, right, trackTop + trackH)

        bgPaint.color = pal.inputBg
        canvas.drawRect(outRect, bgPaint)

        val fillW = outRect.width() * fraction
        bgPaint.color = pal.accentColor
        canvas.drawRect(left, trackTop, left + fillW, trackTop + trackH, bgPaint)

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(outRect, borderPaint)

        return trackTop + trackH + 26f * sx
    }

    // ==========================================================================
    // 1. BUTTON 1 MAIN DROPDOWN MENU
    // ==========================================================================
    private fun drawButton1MainMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val totalRows = 7
        val panelH = rowH * totalRows
        val right = left + panelW
        val bottom = top + panelH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        val r0Top = top
        val r0Bot = r0Top + rowH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, r0Top, right, r0Bot, bgPaint)
        SketchIcons.drawProjectNameIcon(canvas, left + 58f * sx, r0Top + rowH / 2f, 44f * sx, pal.iconTint)

        val nameBoxRect = RectF(left + 118f * sx, r0Top + 22f * sx, right - 28f * sx, r0Bot - 22f * sx)
        bgPaint.color = pal.inputBg
        canvas.drawRect(nameBoxRect, bgPaint)
        borderPaint.color = if (activeInlineField == InlineEditField.PROJECT_NAME_BOX) pal.accentColor else pal.borderColor
        borderPaint.strokeWidth = 2.4f * sx
        canvas.drawRect(nameBoxRect, borderPaint)

        if (activeInlineField != InlineEditField.PROJECT_NAME_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 36f * sx
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.isFakeBoldText = false
            canvas.drawText(
                ellipsize(project.name, textPaint, nameBoxRect.width() - 36f * sx),
                nameBoxRect.left + 18f * sx,
                nameBoxRect.centerY() + 13f * sx,
                textPaint
            )
        }
        hitZones.add(HitZone("EDIT_PROJECT_NAME", nameBoxRect))

        borderPaint.color = pal.borderColor
        canvas.drawLine(left, r0Bot, right, r0Bot, borderPaint)

        data class MenuItem(val id: String, val label: String, val clickable: Boolean)
        val items = listOf(
            MenuItem("MENU_NEW_SKETCH", "New Sketch", true),
            MenuItem("MENU_SAVE", "Save", true),
            MenuItem("MENU_IMPORT", "Import from Files", true),
            MenuItem("MENU_PREFS", "Preferences", false),
            MenuItem("MENU_SHARE", "Share / Export", true),
            MenuItem("MENU_MODS", "Mods", true)
        )

        for ((idx, item) in items.withIndex()) {
            val ry = r0Bot + idx * rowH
            val cy = ry + rowH / 2f
            val iconX = left + 58f * sx
            val iconSize = 46f * sx

            when (item.id) {
                "MENU_NEW_SKETCH" -> SketchIcons.drawPlusIcon(canvas, iconX, cy, iconSize, pal.iconTint)
                "MENU_SAVE" -> SketchIcons.drawSaveIcon(canvas, iconX, cy, iconSize, pal.iconTint)
                "MENU_IMPORT" -> SketchIcons.drawImportIcon(canvas, iconX, cy, iconSize, pal.iconTint)
                "MENU_PREFS" -> SketchIcons.drawGearIcon(canvas, iconX, cy, iconSize, filled = true, color = pal.iconTint)
                "MENU_SHARE" -> SketchIcons.drawShareIcon(canvas, iconX, cy, iconSize, withStar = true, color = pal.iconTint)
                "MENU_MODS" -> SketchIcons.drawModMenuIcon(canvas, iconX, cy, iconSize, pal.iconTint)
            }

            textPaint.color = pal.textPrimary
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.label, left + 138f * sx, cy + 14f * sx, textPaint)

            if (item.id == "MENU_MODS") {
                val activeCount = modEngine.bundles.count { it.enabled }
                subTextPaint.color = if (activeCount > 0) pal.accentColor else pal.textSecondary
                subTextPaint.textSize = 28f * sx
                subTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("${activeCount}/${modEngine.bundles.size} ON", right - 28f * sx, cy + 10f * sx, subTextPaint)
            }

            if (item.clickable) {
                hitZones.add(HitZone(item.id, RectF(left, ry, right, ry + rowH)))
            }
        }
    }

    // ==========================================================================
    // 2. NEW SKETCH MENU ('+' Custom Size Tab & '≡' Presets List Tab)
    // ==========================================================================
    private fun drawNewSketchMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val tabH = 116f * sx
        val bodyH = if (newSketchTab == NewSketchTab.PLUS_CUSTOM) 1040f * sx else 1170f * sx
        val right = left + panelW
        val bottom = top + headerH + tabH + bodyH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "New Sketch", "BACK_TO_MAIN", pal)

        val tabTop = top + headerH
        val tabMid = left + panelW / 2f
        val tabBot = tabTop + tabH

        val leftTabRect = RectF(left, tabTop, tabMid, tabBot)
        val rightTabRect = RectF(tabMid, tabTop, right, tabBot)

        bgPaint.color = if (newSketchTab == NewSketchTab.PLUS_CUSTOM) Color.rgb(142, 142, 142) else pal.popupHeaderBg
        canvas.drawRect(leftTabRect, bgPaint)

        bgPaint.color = if (newSketchTab == NewSketchTab.LIST_PRESETS) Color.rgb(142, 142, 142) else pal.popupHeaderBg
        canvas.drawRect(rightTabRect, bgPaint)

        SketchIcons.drawPlusIcon(canvas, leftTabRect.centerX(), leftTabRect.centerY(), 46f * sx, pal.textPrimary)
        SketchIcons.drawListBarsIcon(canvas, rightTabRect.centerX(), rightTabRect.centerY(), 46f * sx, pal.textPrimary)

        hitZones.add(HitZone("TAB_PLUS", leftTabRect))
        hitZones.add(HitZone("TAB_LIST", rightTabRect))

        if (newSketchTab == NewSketchTab.PLUS_CUSTOM) {
            drawCustomDimensionBody(
                canvas = canvas,
                left = left,
                bodyTop = tabBot,
                panelW = panelW,
                bodyH = bodyH,
                sx = sx,
                buttonLabel = "Create",
                buttonZoneId = "BTN_CREATE_CUSTOM",
                showPresetNameBox = false,
                showDeleteButton = false,
                pal = pal
            )
        } else {
            drawPresetsListTab(canvas, left, tabBot, panelW, bodyH, sx, pal)
        }
    }

    private fun drawCustomDimensionBody(
        canvas: Canvas,
        left: Float,
        bodyTop: Float,
        panelW: Float,
        bodyH: Float,
        sx: Float,
        buttonLabel: String,
        buttonZoneId: String,
        showPresetNameBox: Boolean,
        showDeleteButton: Boolean,
        pal: ModUiPalette
    ) {
        val right = left + panelW
        var cursorY = bodyTop + 28f * sx

        if (showPresetNameBox) {
            val nameBoxW = 492f * sx
            val nameBoxH = 78f * sx
            val nx = left + (panelW - nameBoxW) / 2f
            val nameRect = RectF(nx, cursorY, nx + nameBoxW, cursorY + nameBoxH)
            bgPaint.color = pal.inputBg
            canvas.drawRect(nameRect, bgPaint)
            borderPaint.color = if (activeInlineField == InlineEditField.PRESET_NAME_BOX) pal.accentColor else pal.borderColor
            borderPaint.strokeWidth = 2.2f * sx
            canvas.drawRect(nameRect, borderPaint)

            if (activeInlineField != InlineEditField.PRESET_NAME_BOX) {
                textPaint.color = pal.textPrimary
                textPaint.textSize = 37f * sx
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(editingPresetName, nameRect.centerX(), nameRect.centerY() + 13f * sx, textPaint)
            }
            hitZones.add(HitZone("EDIT_PRESET_NAME_FIELD", nameRect))

            cursorY += nameBoxH + 24f * sx
        }

        val isZeroDim = customWidthInput <= 0 || customHeightInput <= 0
        val mpxVal = if (isZeroDim) 0f else (customWidthInput.toLong() * customHeightInput.toLong()) / 1_000_000f
        val maxLayers = SketchProjectState.calculateMaxLayers(customWidthInput, customHeightInput)

        subTextPaint.textAlign = Paint.Align.RIGHT
        subTextPaint.textSize = 29f * sx
        subTextPaint.color = pal.textSecondary
        subTextPaint.isFakeBoldText = false
        canvas.drawText(String.format("%.2fMpx", mpxVal), right - 26f * sx, cursorY + 24f * sx, subTextPaint)

        subTextPaint.textSize = 31f * sx
        subTextPaint.isFakeBoldText = true
        subTextPaint.color = if (isZeroDim) Color.rgb(190, 55, 55) else pal.textPrimary
        canvas.drawText("L$maxLayers", right - 26f * sx, cursorY + 66f * sx, subTextPaint)
        subTextPaint.isFakeBoldText = false

        val previewAreaTop = cursorY + 36f * sx
        val previewMaxBox = 300f * sx
        val previewCx = left + panelW / 2f
        val previewCy = previewAreaTop + previewMaxBox / 2f

        if (isZeroDim) {
            val zeroW = if (customWidthInput <= 0) 90f * sx else 220f * sx
            val zeroH = if (customHeightInput <= 0) 90f * sx else 220f * sx
            val zRect = RectF(previewCx - zeroW / 2f, previewCy - zeroH / 2f, previewCx + zeroW / 2f, previewCy + zeroH / 2f)

            bgPaint.color = Color.rgb(232, 226, 226)
            canvas.drawRect(zRect, bgPaint)

            borderPaint.color = Color.rgb(185, 70, 70)
            borderPaint.strokeWidth = 2.5f * sx
            borderPaint.pathEffect = DashPathEffect(floatArrayOf(12f * sx, 8f * sx), 0f)
            canvas.drawRect(zRect, borderPaint)
            canvas.drawLine(zRect.left, zRect.top, zRect.right, zRect.bottom, borderPaint)
            canvas.drawLine(zRect.right, zRect.top, zRect.left, zRect.bottom, borderPaint)
            borderPaint.pathEffect = null

            subTextPaint.textAlign = Paint.Align.CENTER
            subTextPaint.color = Color.rgb(175, 50, 50)
            subTextPaint.textSize = 26f * sx
            canvas.drawText("${customWidthInput} × ${customHeightInput}", previewCx, zRect.bottom + 36f * sx, subTextPaint)
        } else {
            val ratio = customWidthInput.toFloat() / customHeightInput.toFloat()
            val cardW: Float
            val cardH: Float
            if (ratio >= 1f) {
                cardW = min(previewMaxBox, previewMaxBox * 0.92f)
                cardH = (cardW / ratio).coerceIn(48f * sx, previewMaxBox)
            } else {
                cardH = min(previewMaxBox, previewMaxBox * 0.92f)
                cardW = (cardH * ratio).coerceIn(48f * sx, previewMaxBox)
            }
            val cardRect = RectF(previewCx - cardW / 2f, previewCy - cardH / 2f, previewCx + cardW / 2f, previewCy + cardH / 2f)

            shadowPaint.color = Color.argb(42, 0, 0, 0)
            canvas.drawRect(cardRect.left + 4f * sx, cardRect.top + 8f * sx, cardRect.right + 8f * sx, cardRect.bottom + 12f * sx, shadowPaint)

            bgPaint.color = pal.cardBg
            canvas.drawRect(cardRect, bgPaint)
            borderPaint.color = pal.borderColor
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(cardRect, borderPaint)

            SketchIcons.drawCanvasRotateIcon(canvas, previewCx, previewCy, 56f * sx, pal.iconTint)
            hitZones.add(HitZone("ROTATE_CANVAS_PREVIEW", cardRect))
        }

        val boxesY = previewAreaTop + previewMaxBox + 84f * sx
        val boxW = 216f * sx
        val boxH = 82f * sx
        val leftBoxX = left + 48f * sx
        val rightBoxX = right - 48f * sx - boxW

        val widthBoxRect = RectF(leftBoxX, boxesY, leftBoxX + boxW, boxesY + boxH)
        val heightBoxRect = RectF(rightBoxX, boxesY, rightBoxX + boxW, boxesY + boxH)
        val linkIconRect = RectF(widthBoxRect.right, boxesY, heightBoxRect.left, boxesY + boxH)

        // Width Box (True Inline Edit on tap!)
        bgPaint.color = pal.inputBg
        canvas.drawRect(widthBoxRect, bgPaint)
        borderPaint.color = when {
            activeInlineField == InlineEditField.WIDTH_BOX -> pal.accentColor
            customWidthInput <= 0 -> Color.rgb(195, 55, 55)
            else -> Color.rgb(117, 117, 117)
        }
        borderPaint.strokeWidth = if (activeInlineField == InlineEditField.WIDTH_BOX) 3.2f * sx else 2.4f * sx
        canvas.drawRect(widthBoxRect, borderPaint)

        if (activeInlineField != InlineEditField.WIDTH_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 37f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(customWidthInput.toString(), widthBoxRect.centerX(), widthBoxRect.centerY() + 13f * sx, textPaint)
        }
        hitZones.add(HitZone("INPUT_WIDTH_BOX", widthBoxRect))

        // Height Box (True Inline Edit on tap!)
        bgPaint.color = pal.inputBg
        canvas.drawRect(heightBoxRect, bgPaint)
        borderPaint.color = when {
            activeInlineField == InlineEditField.HEIGHT_BOX -> pal.accentColor
            customHeightInput <= 0 -> Color.rgb(195, 55, 55)
            else -> Color.rgb(117, 117, 117)
        }
        borderPaint.strokeWidth = if (activeInlineField == InlineEditField.HEIGHT_BOX) 3.2f * sx else 2.4f * sx
        canvas.drawRect(heightBoxRect, borderPaint)

        if (activeInlineField != InlineEditField.HEIGHT_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 37f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(customHeightInput.toString(), heightBoxRect.centerX(), heightBoxRect.centerY() + 13f * sx, textPaint)
        }
        hitZones.add(HitZone("INPUT_HEIGHT_BOX", heightBoxRect))

        SketchIcons.drawLinkRatioIcon(canvas, linkIconRect.centerX(), linkIconRect.centerY(), 50f * sx, isRatioLocked, pal.accentColor, pal.iconTint)
        hitZones.add(HitZone("TOGGLE_RATIO_LOCK", linkIconRect))

        val createBtnW = 376f * sx
        val createBtnH = 88f * sx
        val createBtnX = left + (panelW - createBtnW) / 2f
        val createBtnY = bodyTop + bodyH - createBtnH - 46f * sx

        if (showDeleteButton && editingPresetIndex in customPresets.indices) {
            val delW = 376f * sx
            val delH = 72f * sx
            val delX = left + (panelW - delW) / 2f
            val delY = createBtnY - delH - 24f * sx
            val delRect = RectF(delX, delY, delX + delW, delY + delH)

            bgPaint.color = Color.rgb(235, 225, 225)
            canvas.drawRect(delRect, bgPaint)
            borderPaint.color = Color.rgb(185, 65, 65)
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(delRect, borderPaint)

            SketchIcons.drawTrashIcon(canvas, delRect.left + 48f * sx, delRect.centerY(), 38f * sx)
            textPaint.color = Color.rgb(180, 45, 45)
            textPaint.textSize = 32f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Delete Preset", delRect.centerX() + 18f * sx, delRect.centerY() + 11f * sx, textPaint)
            hitZones.add(HitZone("BTN_DELETE_PRESET", delRect))
        }

        val createRect = RectF(createBtnX, createBtnY, createBtnX + createBtnW, createBtnY + createBtnH)
        bgPaint.color = if (isZeroDim) Color.rgb(165, 180, 205) else pal.accentColor
        canvas.drawRect(createRect, bgPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 39f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(buttonLabel, createRect.centerX(), createRect.centerY() + 14f * sx, textPaint)
        if (!isZeroDim) {
            hitZones.add(HitZone(buttonZoneId, createRect))
        }
    }

    private fun drawPresetsListTab(canvas: Canvas, left: Float, bodyTop: Float, panelW: Float, bodyH: Float, sx: Float, pal: ModUiPalette) {
        val right = left + panelW
        val pinnedRowH = 166f * sx
        val addBtnH = 112f * sx

        // 1. Pinned Top Row: "Screen" (Uses exact hardware screen resolution 1080 * 2408!)
        val screenTop = bodyTop
        val screenBot = screenTop + pinnedRowH
        bgPaint.color = pal.cardBg
        canvas.drawRect(left, screenTop, right, screenBot, bgPaint)

        textPaint.color = pal.textSecondary
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        canvas.drawText("Screen", left + 225f * sx, screenTop + 62f * sx, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 33f * sx
        canvas.drawText("${hwScreenSize.first} * ${hwScreenSize.second}", left + 225f * sx, screenTop + 132f * sx, textPaint)
        SketchIcons.drawPhoneScreenIcon(canvas, left + 530f * sx, screenTop + pinnedRowH / 2f, 84f * sx, pal.iconTint)
        hitZones.add(HitZone("PRESET_SCREEN", RectF(left, screenTop, right, screenBot)))

        // 2. Pinned Second Row: "Last"
        val lastDims = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
        val lastTop = screenBot
        val lastBot = lastTop + pinnedRowH
        canvas.drawRect(left, lastTop, right, lastBot, bgPaint)

        textPaint.isFakeBoldText = true
        textPaint.textSize = 37f * sx
        canvas.drawText("Last", left + 225f * sx, lastTop + 62f * sx, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 33f * sx
        canvas.drawText("${lastDims.first} * ${lastDims.second}", left + 225f * sx, lastTop + 132f * sx, textPaint)
        SketchIcons.drawLastCanvasIcon(canvas, left + 530f * sx, lastTop + pinnedRowH / 2f, 84f * sx, pal.iconTint)
        hitZones.add(HitZone("PRESET_LAST", RectF(left, lastTop, right, lastBot)))

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, lastBot, right, lastBot, borderPaint)

        val listTop = lastBot
        val listBot = bodyTop + bodyH - addBtnH
        presetScrollAreaBounds.set(left, listTop, right, listBot)

        val rowH = 160f * sx
        presetRowHeightPx = rowH

        val injectedModPresets = modEngine.getInjectedPresets()
        val totalRows = customPresets.size + injectedModPresets.size
        val contentH = totalRows * rowH
        val maxScroll = max(0f, contentH - presetScrollAreaBounds.height())
        presetListScrollY = presetListScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(presetScrollAreaBounds)

        for (i in customPresets.indices) {
            if (isDraggingPreset && i == draggedPresetIndex) continue
            val itemTop = listTop + i * rowH - presetListScrollY
            if (itemTop + rowH >= listTop && itemTop <= listBot) {
                drawSingleCustomPresetRow(canvas, customPresets[i], i, left, itemTop, panelW, rowH, sx, isFloating = false, isModInjected = false, pal = pal)
            }
        }

        for ((mIdx, mp) in injectedModPresets.withIndex()) {
            val visualIdx = customPresets.size + mIdx
            val itemTop = listTop + visualIdx * rowH - presetListScrollY
            if (itemTop + rowH >= listTop && itemTop <= listBot) {
                drawSingleCustomPresetRow(canvas, mp, -100 - mIdx, left, itemTop, panelW, rowH, sx, isFloating = false, isModInjected = true, pal = pal)
            }
        }

        if (isDraggingPreset && draggedPresetIndex in customPresets.indices) {
            val floatTop = (dragCurrentY - rowH / 2f).coerceIn(listTop, listBot - rowH)
            drawSingleCustomPresetRow(
                canvas,
                customPresets[draggedPresetIndex],
                draggedPresetIndex,
                left,
                floatTop,
                panelW,
                rowH,
                sx,
                isFloating = true,
                isModInjected = false,
                pal = pal
            )
        }

        canvas.restore()

        val addTop = listBot
        val addBot = bodyTop + bodyH
        val addRect = RectF(left, addTop, right, addBot)
        bgPaint.color = pal.popupBg
        canvas.drawRect(addRect, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, addTop, right, addTop, borderPaint)

        textPaint.color = pal.textPrimary
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Add Preset", addRect.centerX(), addRect.centerY() + 14f * sx, textPaint)
        hitZones.add(HitZone("BTN_ADD_PRESET", addRect))
    }

    private fun drawSingleCustomPresetRow(
        canvas: Canvas,
        preset: CanvasPreset,
        index: Int,
        left: Float,
        rowTop: Float,
        panelW: Float,
        rowH: Float,
        sx: Float,
        isFloating: Boolean,
        isModInjected: Boolean,
        pal: ModUiPalette
    ) {
        val right = left + panelW
        if (isFloating) {
            shadowPaint.color = Color.argb(60, 0, 0, 0)
            canvas.drawRect(left, rowTop - 4f * sx, right, rowTop + rowH + 6f * sx, shadowPaint)
            bgPaint.color = pal.topBarActiveSlot
            canvas.drawRect(left, rowTop, right, rowTop + rowH, bgPaint)
        } else {
            bgPaint.color = pal.popupBg
            canvas.drawRect(left, rowTop, right, rowTop + rowH, bgPaint)
        }

        val cardLeft = left + 26f * sx
        val cardRight = left + 448f * sx
        val stripTop = rowTop + 22f * sx
        val stripBot = rowTop + 82f * sx
        bgPaint.color = pal.cardBg
        canvas.drawRect(cardLeft, stripTop, cardRight, stripBot, bgPaint)

        textPaint.color = pal.textSecondary
        textPaint.textSize = 37f * sx
        textPaint.isFakeBoldText = true
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(preset.name, (cardLeft + cardRight) / 2f, stripTop + 44f * sx, textPaint)

        textPaint.isFakeBoldText = false
        textPaint.textSize = 36f * sx
        canvas.drawText("${preset.width} * ${preset.height}", (cardLeft + cardRight) / 2f, rowTop + 132f * sx, textPaint)

        if (!isModInjected && index >= 0) {
            val gearX = left + 494f * sx
            val reorderX = left + 575f * sx
            val iconCy = rowTop + 54f * sx
            SketchIcons.drawGearIcon(canvas, gearX, iconCy, 38f * sx, filled = false, color = pal.iconTint)
            SketchIcons.drawReorderIcon(canvas, reorderX, iconCy, 38f * sx, active = isFloating, tint = pal.iconTint)

            if (!isFloating) {
                hitZones.add(HitZone("PRESET_SELECT", RectF(left, rowTop, left + 455f * sx, rowTop + rowH), index))
                hitZones.add(HitZone("PRESET_GEAR", RectF(left + 455f * sx, rowTop, left + 535f * sx, rowTop + rowH), index))
                hitZones.add(HitZone("PRESET_REORDER", RectF(left + 535f * sx, rowTop, right, rowTop + rowH), index))
            }
        } else if (isModInjected && !isFloating) {
            subTextPaint.color = pal.accentColor
            subTextPaint.textSize = 24f * sx
            subTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("MOD", left + 535f * sx, rowTop + 62f * sx, subTextPaint)
            hitZones.add(HitZone("PRESET_MOD_SELECT", RectF(left, rowTop, right, rowTop + rowH), index))
        }
    }

    private fun drawEditPresetMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val bodyH = 1190f * sx
        val right = left + panelW
        val bottom = top + headerH + bodyH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        val headerTitle = if (editingPresetIndex >= 0) "Edit Preset" else "Add Preset"
        drawPopupHeader(canvas, left, top, panelW, headerH, sx, headerTitle, "BACK_TO_PRESET_LIST", pal)

        drawCustomDimensionBody(
            canvas = canvas,
            left = left,
            bodyTop = top + headerH,
            panelW = panelW,
            bodyH = bodyH,
            sx = sx,
            buttonLabel = "DONE",
            buttonZoneId = "BTN_DONE_EDIT_PRESET",
            showPresetNameBox = true,
            showDeleteButton = (editingPresetIndex >= 0),
            pal = pal
        )
    }

    private fun drawSaveMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = if (project.isSavedOnce) 688f * sx else 580f * sx
        val headerH = 132f * sx
        val nameRowH = 118f * sx
        val itemRowH = 132f * sx
        val numActionRows = if (project.isSavedOnce) 2 else 1
        val totalH = headerH + nameRowH + itemRowH * numActionRows
        val right = left + panelW
        val bottom = top + totalH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "Save", "BACK_TO_MAIN", pal)

        val nameTop = top + headerH
        val nameBot = nameTop + nameRowH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, nameTop, right, nameBot, bgPaint)

        subTextPaint.color = pal.textSecondary
        subTextPaint.textSize = 28f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Name:", left + 28f * sx, nameTop + nameRowH / 2f + 10f * sx, subTextPaint)

        val fileBox = RectF(left + 132f * sx, nameTop + 20f * sx, right - 26f * sx, nameBot - 20f * sx)
        bgPaint.color = pal.inputBg
        canvas.drawRect(fileBox, bgPaint)
        borderPaint.color = if (activeInlineField == InlineEditField.PROJECT_NAME_BOX) pal.accentColor else pal.borderColor
        borderPaint.strokeWidth = 2.2f * sx
        canvas.drawRect(fileBox, borderPaint)

        if (activeInlineField != InlineEditField.PROJECT_NAME_BOX) {
            textPaint.color = pal.textPrimary
            textPaint.textSize = 34f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                ellipsize(project.name, textPaint, fileBox.width() - 30f * sx),
                fileBox.left + 16f * sx,
                fileBox.centerY() + 12f * sx,
                textPaint
            )
        }
        hitZones.add(HitZone("EDIT_PROJECT_NAME", fileBox))

        borderPaint.color = pal.borderColor
        canvas.drawLine(left, nameBot, right, nameBot, borderPaint)

        val r1Top = nameBot
        val r1Bot = r1Top + itemRowH
        SketchIcons.drawPictureSaveIcon(canvas, left + 58f * sx, r1Top + itemRowH / 2f, 52f * sx, isCopy = false, color = pal.iconTint, bgMask = pal.popupBg)
        textPaint.color = pal.textPrimary
        textPaint.textSize = 39f * sx
        canvas.drawText("Save current sketch", left + 132f * sx, r1Top + itemRowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("ACTION_SAVE_CURRENT", RectF(left, r1Top, right, r1Bot)))

        if (project.isSavedOnce) {
            val r2Top = r1Bot
            val r2Bot = r2Top + itemRowH
            borderPaint.color = pal.borderColor
            canvas.drawLine(left, r2Top, right, r2Top, borderPaint)

            SketchIcons.drawPictureSaveIcon(canvas, left + 58f * sx, r2Top + itemRowH / 2f, 52f * sx, isCopy = true, color = pal.iconTint, bgMask = pal.popupBg)
            canvas.drawText("Save current sketch as a copy", left + 132f * sx, r2Top + itemRowH / 2f + 14f * sx, textPaint)
            hitZones.add(HitZone("ACTION_SAVE_COPY", RectF(left, r2Top, right, r2Bot)))
        }
    }

    private fun drawShareExportMainMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val right = left + panelW
        val bottom = top + rowH * 3
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, rowH, sx, "Share / Export", "BACK_TO_MAIN", pal)

        val r1Top = top + rowH
        val r1Bot = r1Top + rowH
        SketchIcons.drawFolderIcon(canvas, left + 58f * sx, r1Top + rowH / 2f, 52f * sx, pal.iconTint)
        textPaint.color = pal.textPrimary
        textPaint.textSize = 39f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Export to Files", left + 132f * sx, r1Top + rowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("OPEN_EXPORT_AS", RectF(left, r1Top, right, r1Bot)))

        borderPaint.color = pal.borderColor
        canvas.drawLine(left, r1Bot, right, r1Bot, borderPaint)

        val r2Top = r1Bot
        val r2Bot = r2Top + rowH
        SketchIcons.drawShareIcon(canvas, left + 58f * sx, r2Top + rowH / 2f, 48f * sx, withStar = false, color = pal.iconTint)
        canvas.drawText("Share", left + 132f * sx, r2Top + rowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("OPEN_SHARE_AS", RectF(left, r2Top, right, r2Bot)))
    }

    private fun drawFormatListMenu(canvas: Canvas, left: Float, top: Float, sx: Float, title: String, pal: ModUiPalette) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val formats = listOf(
            Triple("TIF", "Sketchbook TIFF", true),
            Triple("PSD", "PSD", true),
            Triple("PNG", "PNG", false),
            Triple("JPG", "JPG", false),
            Triple("PDF", "PDF", false)
        )
        val right = left + panelW
        val bottom = top + rowH * (1 + formats.size)
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, rowH, sx, title, "BACK_TO_SHARE_MAIN", pal)

        for ((idx, fmt) in formats.withIndex()) {
            val ry = top + rowH * (idx + 1)
            val cy = ry + rowH / 2f
            SketchIcons.drawFileFormatIcon(
                c = canvas,
                cx = left + 82f * sx,
                cy = cy,
                size = 64f * sx,
                label = fmt.first,
                stacked = fmt.third,
                starred = (fmt.first == "PDF"),
                color = pal.iconTint,
                bgMask = pal.popupBg
            )
            textPaint.color = pal.textPrimary
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(fmt.second, left + 190f * sx, cy + 14f * sx, textPaint)

            if (idx < formats.size - 1) {
                borderPaint.color = pal.borderColor
                canvas.drawLine(left, ry + rowH, right, ry + rowH, borderPaint)
            }
        }
    }

    // ==========================================================================
    // 6. SCROLLABLE MOD MENU (UI Color Themes + 60fps Animated UI FX + Core Bundles)
    // ==========================================================================
    private fun drawModMenu(canvas: Canvas, left: Float, top: Float, sx: Float, pal: ModUiPalette) {
        val panelW = 760f * sx
        val headerH = 132f * sx
        val toolbarH = 104f * sx
        val statusH = 66f * sx
        val bundleRowH = 148f * sx
        val maxPanelH = height - top - 30f * sx
        val idealH = headerH + toolbarH + statusH + modEngine.bundles.size * bundleRowH + 16f * sx
        val panelH = min(maxPanelH, idealH)
        val right = left + panelW
        val bottom = top + panelH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = pal.popupBg
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "Mods (Core Bundles)", "BACK_TO_MAIN", pal)

        val barTop = top + headerH
        val barBot = barTop + toolbarH
        val pad = 18f * sx
        val btnW = (panelW - pad * 4f) / 3f
        val btnH = toolbarH - 24f * sx
        val btnY = barTop + 12f * sx

        val actions = listOf(
            Pair("MOD_PASTE_CLIPBOARD", "Paste Script"),
            Pair("MOD_EDIT_SCRIPT", "Edit Script"),
            Pair("MOD_RESET_DEFAULT", "Reset Default")
        )
        for ((i, act) in actions.withIndex()) {
            val bx = left + pad + i * (btnW + pad)
            val bRect = RectF(bx, btnY, bx + btnW, btnY + btnH)
            bgPaint.color = if (i == 0) pal.accentColor else pal.cardBg
            canvas.drawRect(bRect, bgPaint)
            borderPaint.color = pal.borderColor
            canvas.drawRect(bRect, borderPaint)

            textPaint.color = if (i == 0) Color.WHITE else pal.textPrimary
            textPaint.textSize = 27f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(act.second, bRect.centerX(), bRect.centerY() + 10f * sx, textPaint)
            hitZones.add(HitZone(act.first, bRect))
        }

        val statTop = barBot
        val statBot = statTop + statusH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, statTop, right, statBot, bgPaint)
        subTextPaint.color = pal.textSecondary
        subTextPaint.textSize = 25f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(modEngine.lastStatusMessage, subTextPaint, panelW - 40f * sx),
            left + 22f * sx,
            statTop + 42f * sx,
            subTextPaint
        )

        modScrollAreaBounds.set(left, statBot, right, bottom)
        val contentH = modEngine.bundles.size * bundleRowH
        val maxScroll = max(0f, contentH - modScrollAreaBounds.height())
        modListScrollY = modListScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(modScrollAreaBounds)

        for ((bIdx, bundle) in modEngine.bundles.withIndex()) {
            val ry = statBot + bIdx * bundleRowH - modListScrollY
            if (ry + bundleRowH < statBot || ry > bottom) continue

            val rRect = RectF(left, ry, right, ry + bundleRowH)
            bgPaint.color = if (bundle.enabled) pal.cardBg else pal.popupBg
            canvas.drawRect(rRect, bgPaint)

            textPaint.color = pal.textPrimary
            textPaint.textSize = 32f * sx
            textPaint.isFakeBoldText = true
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(bundle.title, left + 26f * sx, ry + 48f * sx, textPaint)
            textPaint.isFakeBoldText = false

            subTextPaint.color = pal.textSecondary
            subTextPaint.textSize = 24f * sx
            val descStr = "${bundle.functions.size} fn(s) • ${bundle.description}"
            canvas.drawText(
                ellipsize(descStr, subTextPaint, panelW - 190f * sx),
                left + 26f * sx,
                ry + 90f * sx,
                subTextPaint
            )

            subTextPaint.textSize = 21f * sx
            val fnNames = bundle.functions.joinToString(", ") { it.type }
            canvas.drawText(
                ellipsize("Hooks: $fnNames", subTextPaint, panelW - 190f * sx),
                left + 26f * sx,
                ry + 124f * sx,
                subTextPaint
            )

            val togW = 124f * sx
            val togH = 66f * sx
            val togX = right - togW - 26f * sx
            val togY = ry + (bundleRowH - togH) / 2f
            val togRect = RectF(togX, togY, togX + togW, togY + togH)

            bgPaint.color = if (bundle.enabled) pal.accentColor else Color.rgb(160, 160, 160)
            canvas.drawRect(togRect, bgPaint)

            val knobW = togW * 0.44f
            val knobPad = 6f * sx
            val knobLeft = if (bundle.enabled) togRect.right - knobW - knobPad else togRect.left + knobPad
            bgPaint.color = Color.WHITE
            canvas.drawRect(knobLeft, togRect.top + knobPad, knobLeft + knobW, togRect.bottom - knobPad, bgPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 24f * sx
            textPaint.isFakeBoldText = true
            textPaint.textAlign = Paint.Align.CENTER
            val labelX = if (bundle.enabled) togRect.left + togW * 0.30f else togRect.right - togW * 0.30f
            canvas.drawText(if (bundle.enabled) "ON" else "OFF", labelX, togRect.centerY() + 9f * sx, textPaint)
            textPaint.isFakeBoldText = false

            borderPaint.color = pal.borderColor
            canvas.drawLine(left, ry + bundleRowH, right, ry + bundleRowH, borderPaint)

            val clippedHit = RectF(left, max(statBot, ry), right, min(bottom, ry + bundleRowH))
            if (clippedHit.height() > 20f * sx) {
                hitZones.add(HitZone("MOD_TOGGLE_BUNDLE", clippedHit, bIdx))
            }
        }
        canvas.restore()
    }

    private fun drawPopupHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        panelW: Float,
        headerH: Float,
        sx: Float,
        title: String,
        backZoneId: String,
        pal: ModUiPalette
    ) {
        val right = left + panelW
        val bot = top + headerH
        bgPaint.color = pal.popupHeaderBg
        canvas.drawRect(left, top, right, bot, bgPaint)

        SketchIcons.drawBackChevron(canvas, left + 56f * sx, top + headerH / 2f, 48f * sx, pal.iconTint)
        hitZones.add(HitZone(backZoneId, RectF(left, top, left + 135f * sx, bot)))

        textPaint.color = pal.textPrimary
        textPaint.textSize = 40f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        canvas.drawText(title, left + panelW * 0.48f, top + headerH / 2f + 14f * sx, textPaint)

        borderPaint.color = pal.borderColor
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, bot, right, bot, borderPaint)
    }

    // ==========================================================================
    // MULTI-TOUCH ZOOM/ROTATE/PAN + COOL BRUSH DRAWING + POPUP INTERACTION
    // ==========================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val sx = width / 1080f
        val topBarH = 131f * sx

        // 1. Handle Multi-Touch 2-Finger Pinch Zoom, Pan & 360 Rotation on Canvas
        if (event.pointerCount >= 2 && popupState == TopPopupState.NONE) {
            abortPresetLongPress()
            isDrawingStroke = false
            val x0 = event.getX(0)
            val y0 = event.getY(0)
            val x1 = event.getX(1)
            val y1 = event.getY(1)
            val dist = hypot(x1 - x0, y1 - y0).coerceAtLeast(10f)
            val angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
            val midX = (x0 + x1) * 0.5f
            val midY = (y0 + y1) * 0.5f

            if (!isTwoFingerGesture || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                isTwoFingerGesture = true
                prevPinchDist = dist
                prevPinchAngle = angle
                prevMidX = midX
                prevMidY = midY
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val scaleFactor = dist / prevPinchDist
                canvasScale = (canvasScale * scaleFactor).coerceIn(0.15f, 32f)

                var dAngle = angle - prevPinchAngle
                if (dAngle > 180f) dAngle -= 360f
                if (dAngle < -180f) dAngle += 360f
                canvasRotationDeg = (canvasRotationDeg + dAngle) % 360f

                canvasTransX += (midX - prevMidX)
                canvasTransY += (midY - prevMidY)

                prevPinchDist = dist
                prevPinchAngle = angle
                prevMidX = midX
                prevMidY = midY
                invalidate()
            }
            return true
        }

        if (isTwoFingerGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isTwoFingerGesture = false
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                lastTouchY = y
                isScrollingPresets = false
                isScrollingMods = false
                isDraggingPreset = false
                draggedPresetIndex = -1
                isDraggingBrushSlider = null

                // Check if user is dragging a slider inside the Brush Studio popup
                if (popupState == TopPopupState.BRUSH_MENU) {
                    when {
                        brushSizeSliderRect.contains(x, y) -> {
                            isDraggingBrushSlider = "SIZE"
                            updateBrushSliderFromX(x)
                            return true
                        }
                        brushOpacitySliderRect.contains(x, y) -> {
                            isDraggingBrushSlider = "OPACITY"
                            updateBrushSliderFromX(x)
                            return true
                        }
                        brushSmoothSliderRect.contains(x, y) -> {
                            isDraggingBrushSlider = "SMOOTH"
                            updateBrushSliderFromX(x)
                            return true
                        }
                    }
                }

                // If no popup is open and touching below the Top Bar, start drawing with the Cool Brush!
                if (popupState == TopPopupState.NONE && y > topBarH) {
                    closeInlineEditor()
                    val cPt = screenToCanvasPoint(x, y)
                    val bmp = project.ensureCanvasBitmap()
                    if (cPt.x in 0f..bmp.width.toFloat() && cPt.y in 0f..bmp.height.toFloat()) {
                        project.pushUndoSnapshot()
                        isDrawingStroke = true
                        smoothX = cPt.x
                        smoothY = cPt.y
                        lastCanvasPt.set(cPt.x, cPt.y)
                        prevCanvasPt.set(cPt.x, cPt.y)
                        lastStrokeSpeed = 0f
                        stampCoolBrushSegment(prevCanvasPt, lastCanvasPt, lastCanvasPt, event.pressure)
                        project.isDirty = true
                        invalidate()
                        return true
                    }
                }

                // If touching inside Presets List tab, arm Hold-to-Reorder timer
                if (popupState == TopPopupState.NEW_SKETCH &&
                    newSketchTab == NewSketchTab.LIST_PRESETS &&
                    presetScrollAreaBounds.contains(x, y)
                ) {
                    val relY = y - presetScrollAreaBounds.top + presetListScrollY
                    val idx = (relY / presetRowHeightPx).toInt()
                    if (idx in customPresets.indices) {
                        val hitReorderHandle = findHitZone(x, y)?.id == "PRESET_REORDER"
                        val delayMs = if (hitReorderHandle) 120L else 380L
                        val r = Runnable {
                            isDraggingPreset = true
                            draggedPresetIndex = idx
                            dragCurrentY = y
                            parent?.requestDisallowInterceptTouchEvent(true)
                            invalidate()
                        }
                        longPressRunnable = r
                        postDelayed(r, delayMs)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = y - lastTouchY
                lastTouchY = y

                if (isDraggingBrushSlider != null) {
                    updateBrushSliderFromX(x)
                    return true
                }

                if (isDrawingStroke) {
                    val rawPt = screenToCanvasPoint(x, y)
                    val alpha = (1f - brushConfig.smoothing * 0.82f).coerceIn(0.15f, 1f)
                    smoothX += (rawPt.x - smoothX) * alpha
                    smoothY += (rawPt.y - smoothY) * alpha
                    val curPt = PointF(smoothX, smoothY)
                    stampCoolBrushSegment(prevCanvasPt, lastCanvasPt, curPt, event.pressure)
                    prevCanvasPt.set(lastCanvasPt)
                    lastCanvasPt.set(curPt)
                    invalidate()
                    return true
                }

                if (isDraggingPreset && draggedPresetIndex in customPresets.indices) {
                    dragCurrentY = y
                    val relY = (y - presetScrollAreaBounds.top + presetListScrollY).coerceAtLeast(0f)
                    val targetIdx = (relY / presetRowHeightPx).toInt().coerceIn(0, customPresets.size - 1)
                    if (targetIdx != draggedPresetIndex) {
                        val moved = customPresets.removeAt(draggedPresetIndex)
                        customPresets.add(targetIdx, moved)
                        draggedPresetIndex = targetIdx
                        ProjectStorageManager.saveCustomPresets(context, customPresets)
                    }
                    invalidate()
                    return true
                }

                if (abs(y - touchDownY) > 16f * sx || abs(x - touchDownX) > 16f * sx) {
                    abortPresetLongPress()
                    if (popupState == TopPopupState.NEW_SKETCH &&
                        newSketchTab == NewSketchTab.LIST_PRESETS &&
                        presetScrollAreaBounds.contains(touchDownX, touchDownY)
                    ) {
                        isScrollingPresets = true
                        presetListScrollY -= dy
                        invalidate()
                        return true
                    }
                    if (popupState == TopPopupState.MOD_MENU &&
                        modScrollAreaBounds.contains(touchDownX, touchDownY)
                    ) {
                        isScrollingMods = true
                        modListScrollY -= dy
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                abortPresetLongPress()
                isDraggingBrushSlider = null
                if (isDrawingStroke) {
                    isDrawingStroke = false
                    invalidate()
                    return true
                }
                if (isDraggingPreset) {
                    isDraggingPreset = false
                    draggedPresetIndex = -1
                    invalidate()
                    return true
                }
                if (!isScrollingPresets && !isScrollingMods &&
                    abs(x - touchDownX) < 28f * sx && abs(y - touchDownY) < 28f * sx
                ) {
                    handleTap(x, y)
                }
                isScrollingPresets = false
                isScrollingMods = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                abortPresetLongPress()
                isDrawingStroke = false
                isDraggingPreset = false
                isScrollingPresets = false
                isScrollingMods = false
                isDraggingBrushSlider = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateBrushSliderFromX(x: Float) {
        when (isDraggingBrushSlider) {
            "SIZE" -> {
                val f = ((x - brushSizeSliderRect.left) / brushSizeSliderRect.width()).coerceIn(0f, 1f)
                brushConfig.sizePx = 2f + f * 148f
            }
            "OPACITY" -> {
                val f = ((x - brushOpacitySliderRect.left) / brushOpacitySliderRect.width()).coerceIn(0f, 1f)
                brushConfig.opacity = (f * 255f).roundToInt().coerceIn(8, 255)
            }
            "SMOOTH" -> {
                val f = ((x - brushSmoothSliderRect.left) / brushSmoothSliderRect.width()).coerceIn(0f, 1f)
                brushConfig.smoothing = f
            }
        }
        invalidate()
    }

    private fun screenToCanvasPoint(sx: Float, sy: Float): PointF {
        val pts = floatArrayOf(sx, sy)
        inverseViewMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    /**
     * Sub-pixel interpolated Catmull-Rom / Quadratic Bezier dab renderer for the Cool Brush.
     */
    private fun stampCoolBrushSegment(p0: PointF, p1: PointF, p2: PointF, pressure: Float) {
        val bmp = project.ensureCanvasBitmap()
        val c = Canvas(bmp)

        val mid1X = (p0.x + p1.x) * 0.5f
        val mid1Y = (p0.y + p1.y) * 0.5f
        val mid2X = (p1.x + p2.x) * 0.5f
        val mid2Y = (p1.y + p2.y) * 0.5f

        val segDist = hypot(mid2X - mid1X, mid2Y - mid1Y)
        lastStrokeSpeed = lastStrokeSpeed * 0.7f + segDist * 0.3f

        // Velocity + Pressure dynamic taper
        val speedFactor = (1f - (lastStrokeSpeed / 65f).coerceIn(0f, 0.55f))
        val pressFactor = if (pressure > 0.01f && pressure != 1.0f) pressure.coerceIn(0.2f, 1.3f) else 1f
        val baseRadius = (brushConfig.sizePx * 0.5f * speedFactor * pressFactor).coerceAtLeast(1.2f)

        val steps = max(1, (segDist / max(1f, baseRadius * 0.22f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val invT = 1f - t
            val x = invT * invT * mid1X + 2f * invT * t * p1.x + t * t * mid2X
            val y = invT * invT * mid1Y + 2f * invT * t * p1.y + t * t * mid2Y

            when (brushConfig.mode) {
                CoolBrushMode.STUDIO_INK -> {
                    strokePaint.color = brushConfig.color
                    strokePaint.alpha = brushConfig.opacity
                    c.drawCircle(x, y, baseRadius, strokePaint)
                }
                CoolBrushMode.NEON_GLOW -> {
                    strokePaint.color = brushConfig.color
                    strokePaint.alpha = (brushConfig.opacity * 0.16f).toInt().coerceIn(6, 70)
                    c.drawCircle(x, y, baseRadius * 2.3f, strokePaint)
                    strokePaint.alpha = (brushConfig.opacity * 0.38f).toInt().coerceIn(12, 130)
                    c.drawCircle(x, y, baseRadius * 1.35f, strokePaint)
                    strokePaint.color = Color.WHITE
                    strokePaint.alpha = brushConfig.opacity
                    c.drawCircle(x, y, baseRadius * 0.55f, strokePaint)
                }
                CoolBrushMode.CHISEL_PEN -> {
                    strokePaint.color = brushConfig.color
                    strokePaint.alpha = brushConfig.opacity
                    c.save()
                    c.translate(x, y)
                    c.rotate(-35f)
                    c.drawRect(-baseRadius * 1.45f, -baseRadius * 0.32f, baseRadius * 1.45f, baseRadius * 0.32f, strokePaint)
                    c.restore()
                }
            }
        }
    }

    private fun abortPresetLongPress() {
        val r = longPressRunnable
        if (r != null) {
            removeCallbacks(r)
            longPressRunnable = null
        }
    }

    private fun findHitZone(x: Float, y: Float): HitZone? {
        for (i in hitZones.size - 1 downTo 0) {
            if (hitZones[i].rect.contains(x, y)) return hitZones[i]
        }
        return null
    }

    private fun handleTap(x: Float, y: Float) {
        val zone = findHitZone(x, y)
        val sx = width / 1080f
        val pal = modEngine.resolveUiPalette()

        if (zone == null) {
            closeInlineEditor()
            if (popupState != TopPopupState.NONE && !currentPopupBounds.contains(x, y)) {
                popupState = TopPopupState.NONE
                invalidate()
            }
            return
        }

        // If tapping any zone other than the active inline box, commit & close the inline editor first
        if (!zone.id.startsWith("INPUT_") && !zone.id.startsWith("EDIT_")) {
            closeInlineEditor()
        }

        when (zone.id) {
            "TOP_BTN_1" -> {
                popupState = if (popupState == TopPopupState.BUTTON1_MAIN) TopPopupState.NONE else TopPopupState.BUTTON1_MAIN
                invalidate()
            }

            "TOP_BTN_UNDO" -> {
                if (project.undo()) invalidate()
            }

            "TOP_BTN_REDO" -> {
                if (project.redo()) invalidate()
            }

            "TOP_BTN_BRUSH" -> {
                popupState = if (popupState == TopPopupState.BRUSH_MENU) TopPopupState.NONE else TopPopupState.BRUSH_MENU
                invalidate()
            }

            "BRUSH_RESET_VIEW" -> {
                resetCanvasTransform()
                showBanner("Canvas View Reset (100% • 0°)")
            }

            "BRUSH_MODE_SELECT" -> {
                val modes = CoolBrushMode.values()
                if (zone.payload in modes.indices) {
                    brushConfig.mode = modes[zone.payload]
                    invalidate()
                }
            }

            "BRUSH_SWATCH" -> {
                brushConfig.color = zone.payload
                invalidate()
            }

            "BRUSH_CLEAR_CANVAS" -> {
                project.pushUndoSnapshot()
                val bmp = project.ensureCanvasBitmap()
                bmp.eraseColor(Color.TRANSPARENT)
                project.isDirty = true
                invalidate()
            }

            "BACK_TO_MAIN" -> {
                popupState = TopPopupState.BUTTON1_MAIN
                invalidate()
            }

            "BACK_TO_PRESET_LIST" -> {
                popupState = TopPopupState.NEW_SKETCH
                newSketchTab = NewSketchTab.LIST_PRESETS
                invalidate()
            }

            "BACK_TO_SHARE_MAIN" -> {
                popupState = TopPopupState.SHARE_EXPORT_MAIN
                invalidate()
            }

            // TRUE INLINE EDITING directly inside the original boxes (No popup dialog!)
            "EDIT_PROJECT_NAME" -> {
                activeInlineField = InlineEditField.PROJECT_NAME_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.PROJECT_NAME_BOX,
                    bounds = zone.rect,
                    initialText = project.name,
                    numeric = false,
                    centered = false,
                    textSizePx = 35f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }

            "INPUT_WIDTH_BOX" -> {
                activeInlineField = InlineEditField.WIDTH_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.WIDTH_BOX,
                    bounds = zone.rect,
                    initialText = customWidthInput.toString(),
                    numeric = true,
                    centered = true,
                    textSizePx = 37f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }

            "INPUT_HEIGHT_BOX" -> {
                activeInlineField = InlineEditField.HEIGHT_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.HEIGHT_BOX,
                    bounds = zone.rect,
                    initialText = customHeightInput.toString(),
                    numeric = true,
                    centered = true,
                    textSizePx = 37f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }

            "EDIT_PRESET_NAME_FIELD" -> {
                activeInlineField = InlineEditField.PRESET_NAME_BOX
                callbacks.onBeginInlineEdit(
                    field = InlineEditField.PRESET_NAME_BOX,
                    bounds = zone.rect,
                    initialText = editingPresetName,
                    numeric = false,
                    centered = true,
                    textSizePx = 37f * sx,
                    textColor = pal.textPrimary,
                    bgColor = pal.inputBg
                )
                invalidate()
            }

            "MENU_NEW_SKETCH" -> {
                popupState = TopPopupState.NEW_SKETCH
                val last = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
                customWidthInput = last.first
                customHeightInput = last.second
                lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
                invalidate()
            }

            "MENU_SAVE" -> {
                popupState = TopPopupState.SAVE_MENU
                invalidate()
            }

            "MENU_IMPORT" -> {
                popupState = TopPopupState.NONE
                invalidate()
                checkSaveOrDiscardIfDirty {
                    callbacks.onRequestImportImage()
                }
            }

            "MENU_SHARE" -> {
                popupState = TopPopupState.SHARE_EXPORT_MAIN
                invalidate()
            }

            "MENU_MODS" -> {
                popupState = TopPopupState.MOD_MENU
                invalidate()
            }

            "TAB_PLUS" -> {
                newSketchTab = NewSketchTab.PLUS_CUSTOM
                invalidate()
            }

            "TAB_LIST" -> {
                newSketchTab = NewSketchTab.LIST_PRESETS
                invalidate()
            }

            "ROTATE_CANVAS_PREVIEW" -> {
                val tmp = customWidthInput
                customWidthInput = customHeightInput
                customHeightInput = tmp
                if (customWidthInput > 0 && customHeightInput > 0) {
                    lockedAspectRatio = customWidthInput.toDouble() / customHeightInput.toDouble()
                }
                invalidate()
            }

            "TOGGLE_RATIO_LOCK" -> {
                isRatioLocked = !isRatioLocked
                if (isRatioLocked && customWidthInput > 0 && customHeightInput > 0) {
                    lockedAspectRatio = customWidthInput.toDouble() / customHeightInput.toDouble()
                }
                invalidate()
            }

            "BTN_CREATE_CUSTOM" -> {
                if (customWidthInput > 0 && customHeightInput > 0) {
                    val targetW = customWidthInput
                    val targetH = customHeightInput
                    ProjectStorageManager.saveLastDimensions(context, targetW, targetH)
                    requestCreateNewProject(targetW, targetH)
                }
            }

            "PRESET_SCREEN" -> {
                requestCreateNewProject(hwScreenSize.first, hwScreenSize.second)
            }

            "PRESET_LAST" -> {
                val last = ProjectStorageManager.loadLastDimensions(context, hwScreenSize.first, hwScreenSize.second)
                requestCreateNewProject(last.first, last.second)
            }

            "PRESET_SELECT" -> {
                val idx = zone.payload
                if (idx in customPresets.indices) {
                    val p = customPresets[idx]
                    ProjectStorageManager.saveLastDimensions(context, p.width, p.height)
                    requestCreateNewProject(p.width, p.height)
                }
            }

            "PRESET_MOD_SELECT" -> {
                val modIdx = -100 - zone.payload
                val modPresets = modEngine.getInjectedPresets()
                if (modIdx in modPresets.indices) {
                    val mp = modPresets[modIdx]
                    ProjectStorageManager.saveLastDimensions(context, mp.width, mp.height)
                    requestCreateNewProject(mp.width, mp.height)
                }
            }

            "PRESET_GEAR" -> {
                val idx = zone.payload
                if (idx in customPresets.indices) {
                    val p = customPresets[idx]
                    editingPresetIndex = idx
                    editingPresetName = p.name
                    customWidthInput = p.width
                    customHeightInput = p.height
                    isRatioLocked = false
                    lockedAspectRatio = p.width.toDouble() / max(1, p.height).toDouble()
                    popupState = TopPopupState.EDIT_PRESET
                    invalidate()
                }
            }

            "BTN_ADD_PRESET" -> {
                editingPresetIndex = -1
                editingPresetName = "Custom ${customPresets.size + 1}"
                customWidthInput = hwScreenSize.first
                customHeightInput = hwScreenSize.second
                isRatioLocked = false
                lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
                popupState = TopPopupState.EDIT_PRESET
                invalidate()
            }

            "BTN_DELETE_PRESET" -> {
                if (editingPresetIndex in customPresets.indices) {
                    customPresets.removeAt(editingPresetIndex)
                    ProjectStorageManager.saveCustomPresets(context, customPresets)
                    popupState = TopPopupState.NEW_SKETCH
                    newSketchTab = NewSketchTab.LIST_PRESETS
                    invalidate()
                }
            }

            "BTN_DONE_EDIT_PRESET" -> {
                if (customWidthInput > 0 && customHeightInput > 0) {
                    if (editingPresetIndex in customPresets.indices) {
                        val p = customPresets[editingPresetIndex]
                        p.name = editingPresetName.ifBlank { "Preset" }
                        p.width = customWidthInput
                        p.height = customHeightInput
                    } else {
                        customPresets.add(
                            CanvasPreset(
                                id = "p_${System.currentTimeMillis()}",
                                name = editingPresetName.ifBlank { "Preset" },
                                width = customWidthInput,
                                height = customHeightInput
                            )
                        )
                    }
                    ProjectStorageManager.saveCustomPresets(context, customPresets)
                    popupState = TopPopupState.NEW_SKETCH
                    newSketchTab = NewSketchTab.LIST_PRESETS
                    invalidate()
                }
            }

            "ACTION_SAVE_CURRENT" -> {
                performSave(asCopy = false)
            }

            "ACTION_SAVE_COPY" -> {
                performSave(asCopy = true)
            }

            "OPEN_EXPORT_AS" -> {
                popupState = TopPopupState.EXPORT_AS_MENU
                invalidate()
            }

            "OPEN_SHARE_AS" -> {
                popupState = TopPopupState.SHARE_AS_MENU
                invalidate()
            }

            "MOD_TOGGLE_BUNDLE" -> {
                val bIdx = zone.payload
                if (bIdx in modEngine.bundles.indices) {
                    modEngine.toggleBundle(modEngine.bundles[bIdx].id)
                    invalidate()
                }
            }

            "MOD_PASTE_CLIPBOARD" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val pasted = clip.getItemAt(0).coerceToText(context).toString()
                    val ok = modEngine.loadScript(pasted, saveToPrefs = true)
                    showBanner(if (ok) "Mod Script Applied!" else modEngine.lastStatusMessage)
                } else {
                    showBanner("Clipboard is empty")
                }
                invalidate()
            }

            "MOD_EDIT_SCRIPT" -> {
                showModScriptEditorDialog()
            }

            "MOD_RESET_DEFAULT" -> {
                modEngine.resetToDefaultScript()
                showBanner("Default Multi-Bundle Script Restored")
                invalidate()
            }
        }
    }

    private fun requestCreateNewProject(targetW: Int, targetH: Int) {
        popupState = TopPopupState.NONE
        invalidate()
        checkSaveOrDiscardIfDirty {
            val prevName = project.name
            project.releaseAll()
            project = SketchProjectState(
                name = prevName,
                width = targetW,
                height = targetH,
                isDirty = false,
                isSavedOnce = false
            )
            project.ensureCanvasBitmap()
            resetCanvasTransform()
            showBanner("New Sketch: ${targetW} × ${targetH}")
            invalidate()
        }
    }

    fun applyImportedBitmapAsNewProject(bmp: android.graphics.Bitmap, suggestedName: String) {
        project.releaseAll()
        project = SketchProjectState(
            name = suggestedName.ifBlank { "Imported-Sketch" },
            width = bmp.width,
            height = bmp.height,
            isDirty = true,
            isSavedOnce = false
        )
        val layerBmp = project.ensureCanvasBitmap()
        val c = Canvas(layerBmp)
        c.drawBitmap(bmp, null, RectF(0f, 0f, layerBmp.width.toFloat(), layerBmp.height.toFloat()), null)
        bmp.recycle()
        resetCanvasTransform()
        ProjectStorageManager.saveLastDimensions(context, project.width, project.height)
        showBanner("Imported Project: ${project.width} × ${project.height}")
        invalidate()
    }

    private fun checkSaveOrDiscardIfDirty(onProceed: () -> Unit) {
        if (!project.isDirty) {
            onProceed()
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 48, 56, 36)
            setBackgroundColor(Color.rgb(243, 243, 243))
        }

        val titleTv = TextView(context).apply {
            text = "Save changes to current sketch?"
            textSize = 18f
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(0, 0, 0, 36)
        }
        container.addView(titleTv)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val dialog = AlertDialog.Builder(context).setView(container).create()

        fun makeSharpBtn(label: String, bgColor: Int, txtColor: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(txtColor)
                setBackgroundColor(bgColor)
                setPadding(42, 24, 42, 24)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
        }

        val cancelBtn = makeSharpBtn("CANCEL", Color.rgb(220, 220, 220), Color.rgb(40, 40, 40)) {}
        val discardBtn = makeSharpBtn("DISCARD", Color.rgb(215, 75, 75), Color.WHITE) {
            onProceed()
        }
        val saveBtn = makeSharpBtn("SAVE", Color.rgb(55, 116, 205), Color.WHITE) {
            performSave(asCopy = false)
            onProceed()
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 20 }

        btnRow.addView(cancelBtn)
        btnRow.addView(discardBtn, lp)
        btnRow.addView(saveBtn, lp)
        container.addView(btnRow)
        dialog.show()
    }

    private fun performSave(asCopy: Boolean) {
        val paperTint = modEngine.resolveUiPalette().paperTint
        val res = ProjectStorageManager.saveProjectToDevice(context, project, asCopy, paperTint)
        res.onSuccess { path ->
            project.isSavedOnce = true
            project.isDirty = false
            project.lastSavedPath = path
            popupState = TopPopupState.NONE
            showBanner(if (asCopy) "Saved Copy -> $path" else "Saved -> $path")
            invalidate()
        }.onFailure { err ->
            showBanner("Save failed: ${err.message}")
            invalidate()
        }
    }

    private fun showModScriptEditorDialog() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 32)
            setBackgroundColor(Color.rgb(243, 243, 243))
        }

        val title = TextView(context).apply {
            text = "Unified Multi-Bundle Mod Script"
            textSize = 17f
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(0, 0, 0, 18)
        }
        root.addView(title)

        val editor = EditText(context).apply {
            setText(modEngine.activeScriptSource)
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.rgb(20, 20, 20))
            textSize = 13f
            minLines = 10
            maxLines = 16
            gravity = Gravity.TOP or Gravity.START
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(context).apply { addView(editor) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 680))

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 26, 0, 0)
        }

        val dlg = AlertDialog.Builder(context).setView(root).create()

        val copyBtn = TextView(context).apply {
            text = "COPY SCRIPT"
            setTextColor(Color.rgb(40, 40, 40))
            setBackgroundColor(Color.rgb(220, 220, 220))
            setPadding(32, 22, 32, 22)
            setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SketchBookMod", editor.text.toString()))
                showBanner("Script copied to clipboard")
            }
        }

        val applyBtn = TextView(context).apply {
            text = "APPLY BUNDLES"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(55, 116, 205))
            setPadding(40, 22, 40, 22)
            setOnClickListener {
                val ok = modEngine.loadScript(editor.text.toString(), saveToPrefs = true)
                dlg.dismiss()
                showBanner(if (ok) "Bundles Updated!" else modEngine.lastStatusMessage)
                invalidate()
            }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 20 }

        btnRow.addView(copyBtn)
        btnRow.addView(applyBtn, lp)
        root.addView(btnRow)
        dlg.show()
    }

    private fun ellipsize(str: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(str) <= maxW) return str
        var s = str
        while (s.length > 1 && paint.measureText("$s...") > maxW) {
            s = s.dropLast(1)
        }
        return "$s..."
    }
}
