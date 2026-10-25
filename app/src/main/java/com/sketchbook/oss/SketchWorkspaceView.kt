package com.sketchbook.oss

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SketchWorkspaceView(
    context: Context,
    private val callbacks: HostCallbacks
) : View(context) {

    interface HostCallbacks {
        fun onRequestImportImage()
    }

    val modEngine = ModCoreEngine(context) { invalidate() }
    var project = SketchProjectState()
    val customPresets = ProjectStorageManager.loadCustomPresets(context)

    var popupState: TopPopupState = TopPopupState.NONE
    var newSketchTab: NewSketchTab = NewSketchTab.PLUS_CUSTOM

    var customWidthInput: Int = 1080
    var customHeightInput: Int = 2408
    var isRatioLocked: Boolean = false
    var lockedAspectRatio: Double = 1080.0 / 2408.0

    var editingPresetIndex: Int = -1
    var editingPresetName: String = "Letter"

    private var presetListScrollY: Float = 0f
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var lastTouchY: Float = 0f
    private var isDraggingPreset: Boolean = false
    private var draggedPresetIndex: Int = -1
    private var dragCurrentY: Float = 0f
    private var isScrollingPresets: Boolean = false
    private var longPressRunnable: Runnable? = null

    private var statusBannerText: String? = null
    private var statusBannerExpiry: Long = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class HitZone(val id: String, val rect: RectF, val payload: Int = -1)
    private val hitZones = mutableListOf<HitZone>()
    private var currentPopupBounds = RectF()
    private var presetScrollAreaBounds = RectF()
    private var presetRowHeightPx = 1f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        post {
            if (width > 0 && height > 0) {
                val canvasH = height
                project.width = width
                project.height = canvasH
                val last = ProjectStorageManager.loadLastDimensions(context, width, canvasH)
                customWidthInput = last.first
                customHeightInput = last.second
                lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
                invalidate()
            }
        }
    }

    fun showBanner(msg: String) {
        statusBannerText = msg
        statusBannerExpiry = System.currentTimeMillis() + 3000L
        invalidate()
        postDelayed(Runnable { invalidate() }, 3100L)
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

        val workspaceColor = modEngine.getWorkspaceColorOverride(Color.rgb(127, 127, 127))
        canvas.drawColor(workspaceColor)

        val availW = w - sideFrame * 2f
        val availH = h - topBarH
        val projW = max(1, project.width).toFloat()
        val projH = max(1, project.height).toFloat()
        val scale = min(availW / projW, availH / projH)
        val drawW = projW * scale
        val drawH = projH * scale
        val canvasLeft = sideFrame + (availW - drawW) / 2f
        val canvasTop = topBarH + (availH - drawH) / 2f
        val canvasRect = RectF(canvasLeft, canvasTop, canvasLeft + drawW, canvasTop + drawH)

        bgPaint.style = Paint.Style.FILL
        bgPaint.color = modEngine.getCanvasPaperTintOverride(Color.WHITE)
        canvas.drawRect(canvasRect, bgPaint)

        project.importedBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, canvasRect, null)
            }
        }

        modEngine.renderCanvasOverlays(canvas, canvasRect, project)
        drawTopMenuBar(canvas, w, topBarH, sideFrame)

        val dotY = h - 136f * sx
        val dotRadius = 15.5f * sx
        SketchIcons.drawBottomCenterDot(canvas, w / 2f, dotY, dotRadius)

        currentPopupBounds.setEmpty()
        when (popupState) {
            TopPopupState.NONE -> {}
            TopPopupState.BUTTON1_MAIN -> drawButton1MainMenu(canvas, sideFrame, topBarH, sx)
            TopPopupState.NEW_SKETCH -> drawNewSketchMenu(canvas, sideFrame, topBarH, sx)
            TopPopupState.EDIT_PRESET -> drawEditPresetMenu(canvas, sideFrame, topBarH, sx)
            TopPopupState.SAVE_MENU -> drawSaveMenu(canvas, sideFrame, topBarH, sx)
            TopPopupState.SHARE_EXPORT_MAIN -> drawShareExportMainMenu(canvas, sideFrame, topBarH, sx)
            TopPopupState.EXPORT_AS_MENU -> drawFormatListMenu(canvas, sideFrame, topBarH, sx, "Export As")
            TopPopupState.SHARE_AS_MENU -> drawFormatListMenu(canvas, sideFrame, topBarH, sx, "Share As")
            TopPopupState.MOD_MENU -> drawModMenu(canvas, sideFrame, topBarH, sx)
        }

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
            bgPaint.color = Color.argb(230, 38, 38, 38)
            canvas.drawRect(bx, by, bx + bw, by + bh, bgPaint)
            canvas.drawText(bannerMsg, w / 2f, by + bh * 0.64f, textPaint)
        }
    }

    private fun drawTopMenuBar(canvas: Canvas, w: Float, topBarH: Float, sideFrame: Float) {
        bgPaint.style = Paint.Style.FILL
        bgPaint.color = modEngine.getTopBarColorOverride(Color.rgb(239, 239, 239))
        canvas.drawRect(sideFrame, 0f, w - sideFrame, topBarH, bgPaint)

        borderPaint.color = Color.rgb(212, 212, 212)
        borderPaint.strokeWidth = 2f
        canvas.drawLine(sideFrame, topBarH, w - sideFrame, topBarH, borderPaint)

        val innerW = w - sideFrame * 2f
        val slotW = innerW / 8f
        val cy = topBarH / 2f

        if (popupState != TopPopupState.NONE) {
            bgPaint.color = Color.rgb(224, 224, 224)
            canvas.drawRect(sideFrame, 0f, sideFrame + slotW, topBarH, bgPaint)
        }

        for (i in 0 until 8) {
            val cx = sideFrame + slotW * (i + 0.5f)
            when (i) {
                0 -> {
                    SketchIcons.drawTopIcon1Menu(canvas, cx, cy, topBarH)
                    hitZones.add(HitZone("TOP_BTN_1", RectF(sideFrame, 0f, sideFrame + slotW, topBarH)))
                }
                1 -> SketchIcons.drawTopIconUndoRedo(canvas, cx, cy, topBarH, isRedo = false, enabled = true)
                2 -> SketchIcons.drawTopIconUndoRedo(canvas, cx, cy, topBarH, isRedo = true, enabled = false)
                3 -> SketchIcons.drawTopIcon4Grid(canvas, cx, cy, topBarH)
                4 -> SketchIcons.drawTopIcon5PencilPen(canvas, cx, cy, topBarH)
                5 -> SketchIcons.drawTopIcon6ColorWheel(canvas, cx, cy, topBarH)
                6 -> SketchIcons.drawTopIcon7Layers(canvas, cx, cy, topBarH)
                7 -> SketchIcons.drawTopIcon8Viewfinder(canvas, cx, cy, topBarH)
            }
        }
    }

    private fun drawButton1MainMenu(canvas: Canvas, left: Float, top: Float, sx: Float) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val totalRows = 7
        val panelH = rowH * totalRows
        val right = left + panelW
        val bottom = top + panelH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = Color.rgb(210, 210, 210)
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        val r0Top = top
        val r0Bot = r0Top + rowH
        bgPaint.color = Color.rgb(236, 236, 236)
        canvas.drawRect(left, r0Top, right, r0Bot, bgPaint)
        SketchIcons.drawProjectNameIcon(canvas, left + 58f * sx, r0Top + rowH / 2f, 44f * sx)

        val nameBoxRect = RectF(left + 118f * sx, r0Top + 22f * sx, right - 28f * sx, r0Bot - 22f * sx)
        bgPaint.color = Color.WHITE
        canvas.drawRect(nameBoxRect, bgPaint)
        borderPaint.color = Color.rgb(117, 117, 117)
        borderPaint.strokeWidth = 2.2f * sx
        canvas.drawRect(nameBoxRect, borderPaint)

        textPaint.color = Color.rgb(25, 25, 25)
        textPaint.textSize = 36f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        canvas.drawText(
            ellipsize(project.name, textPaint, nameBoxRect.width() - 36f * sx),
            nameBoxRect.left + 18f * sx,
            nameBoxRect.centerY() + 13f * sx,
            textPaint
        )
        hitZones.add(HitZone("EDIT_PROJECT_NAME", RectF(left, r0Top, right, r0Bot)))

        borderPaint.color = Color.rgb(220, 220, 220)
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
                "MENU_NEW_SKETCH" -> SketchIcons.drawPlusIcon(canvas, iconX, cy, iconSize)
                "MENU_SAVE" -> SketchIcons.drawSaveIcon(canvas, iconX, cy, iconSize)
                "MENU_IMPORT" -> SketchIcons.drawImportIcon(canvas, iconX, cy, iconSize)
                "MENU_PREFS" -> SketchIcons.drawGearIcon(canvas, iconX, cy, iconSize, filled = true)
                "MENU_SHARE" -> SketchIcons.drawShareIcon(canvas, iconX, cy, iconSize, withStar = true)
                "MENU_MODS" -> SketchIcons.drawModMenuIcon(canvas, iconX, cy, iconSize)
            }

            textPaint.color = Color.rgb(18, 18, 18)
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.label, left + 138f * sx, cy + 14f * sx, textPaint)

            if (item.id == "MENU_MODS") {
                val activeCount = modEngine.bundles.count { it.enabled }
                subTextPaint.color = if (activeCount > 0) Color.rgb(55, 116, 205) else Color.rgb(130, 130, 130)
                subTextPaint.textSize = 28f * sx
                subTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("${activeCount}/${modEngine.bundles.size} ON", right - 28f * sx, cy + 10f * sx, subTextPaint)
            }

            if (item.clickable) {
                hitZones.add(HitZone(item.id, RectF(left, ry, right, ry + rowH)))
            }
        }
    }

    private fun drawNewSketchMenu(canvas: Canvas, left: Float, top: Float, sx: Float) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val tabH = 116f * sx
        val bodyH = if (newSketchTab == NewSketchTab.PLUS_CUSTOM) 1040f * sx else 1170f * sx
        val right = left + panelW
        val bottom = top + headerH + tabH + bodyH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "New Sketch", "BACK_TO_MAIN")

        val tabTop = top + headerH
        val tabMid = left + panelW / 2f
        val tabBot = tabTop + tabH

        val leftTabRect = RectF(left, tabTop, tabMid, tabBot)
        val rightTabRect = RectF(tabMid, tabTop, right, tabBot)

        bgPaint.color = if (newSketchTab == NewSketchTab.PLUS_CUSTOM) Color.rgb(142, 142, 142) else Color.rgb(239, 239, 239)
        canvas.drawRect(leftTabRect, bgPaint)

        bgPaint.color = if (newSketchTab == NewSketchTab.LIST_PRESETS) Color.rgb(142, 142, 142) else Color.rgb(239, 239, 239)
        canvas.drawRect(rightTabRect, bgPaint)

        SketchIcons.drawPlusIcon(canvas, leftTabRect.centerX(), leftTabRect.centerY(), 46f * sx, Color.rgb(35, 35, 35))
        SketchIcons.drawListBarsIcon(canvas, rightTabRect.centerX(), rightTabRect.centerY(), 46f * sx, Color.rgb(35, 35, 35))

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
                showDeleteButton = false
            )
        } else {
            drawPresetsListTab(canvas, left, tabBot, panelW, bodyH, sx)
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
        showDeleteButton: Boolean
    ) {
        val right = left + panelW
        var cursorY = bodyTop + 28f * sx

        if (showPresetNameBox) {
            val nameBoxW = 492f * sx
            val nameBoxH = 78f * sx
            val nx = left + (panelW - nameBoxW) / 2f
            val nameRect = RectF(nx, cursorY, nx + nameBoxW, cursorY + nameBoxH)
            bgPaint.color = Color.rgb(248, 248, 248)
            canvas.drawRect(nameRect, bgPaint)
            borderPaint.color = Color.rgb(145, 145, 145)
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(nameRect, borderPaint)

            textPaint.color = Color.rgb(25, 25, 25)
            textPaint.textSize = 37f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(editingPresetName, nameRect.centerX(), nameRect.centerY() + 13f * sx, textPaint)
            hitZones.add(HitZone("EDIT_PRESET_NAME_FIELD", nameRect))

            cursorY += nameBoxH + 24f * sx
        }

        val isZeroDim = customWidthInput <= 0 || customHeightInput <= 0
        val mpxVal = if (isZeroDim) 0f else (customWidthInput.toLong() * customHeightInput.toLong()) / 1_000_000f
        val maxLayers = SketchProjectState.calculateMaxLayers(customWidthInput, customHeightInput)

        subTextPaint.textAlign = Paint.Align.RIGHT
        subTextPaint.textSize = 29f * sx
        subTextPaint.color = Color.rgb(110, 110, 110)
        subTextPaint.isFakeBoldText = false
        canvas.drawText(String.format("%.2fMpx", mpxVal), right - 26f * sx, cursorY + 24f * sx, subTextPaint)

        subTextPaint.textSize = 31f * sx
        subTextPaint.isFakeBoldText = true
        subTextPaint.color = if (isZeroDim) Color.rgb(190, 55, 55) else Color.rgb(65, 65, 65)
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

            bgPaint.color = Color.rgb(245, 245, 245)
            canvas.drawRect(cardRect, bgPaint)
            borderPaint.color = Color.rgb(200, 200, 200)
            borderPaint.strokeWidth = 2f * sx
            canvas.drawRect(cardRect, borderPaint)

            SketchIcons.drawCanvasRotateIcon(canvas, previewCx, previewCy, 56f * sx)
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

        bgPaint.color = Color.WHITE
        canvas.drawRect(widthBoxRect, bgPaint)
        borderPaint.color = if (customWidthInput <= 0) Color.rgb(195, 55, 55) else Color.rgb(117, 117, 117)
        borderPaint.strokeWidth = 2.4f * sx
        canvas.drawRect(widthBoxRect, borderPaint)

        textPaint.color = Color.rgb(20, 20, 20)
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(customWidthInput.toString(), widthBoxRect.centerX(), widthBoxRect.centerY() + 13f * sx, textPaint)
        hitZones.add(HitZone("INPUT_WIDTH_BOX", widthBoxRect))

        bgPaint.color = Color.WHITE
        canvas.drawRect(heightBoxRect, bgPaint)
        borderPaint.color = if (customHeightInput <= 0) Color.rgb(195, 55, 55) else Color.rgb(117, 117, 117)
        borderPaint.strokeWidth = 2.4f * sx
        canvas.drawRect(heightBoxRect, borderPaint)

        canvas.drawText(customHeightInput.toString(), heightBoxRect.centerX(), heightBoxRect.centerY() + 13f * sx, textPaint)
        hitZones.add(HitZone("INPUT_HEIGHT_BOX", heightBoxRect))

        SketchIcons.drawLinkRatioIcon(canvas, linkIconRect.centerX(), linkIconRect.centerY(), 50f * sx, isRatioLocked)
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
        bgPaint.color = if (isZeroDim) Color.rgb(165, 180, 205) else Color.rgb(55, 116, 205)
        canvas.drawRect(createRect, bgPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 39f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(buttonLabel, createRect.centerX(), createRect.centerY() + 14f * sx, textPaint)
        if (!isZeroDim) {
            hitZones.add(HitZone(buttonZoneId, createRect))
        }
    }

    private fun drawPresetsListTab(canvas: Canvas, left: Float, bodyTop: Float, panelW: Float, bodyH: Float, sx: Float) {
        val right = left + panelW
        val pinnedRowH = 166f * sx
        val addBtnH = 112f * sx

        val screenTop = bodyTop
        val screenBot = screenTop + pinnedRowH
        bgPaint.color = Color.rgb(250, 250, 250)
        canvas.drawRect(left, screenTop, right, screenBot, bgPaint)

        textPaint.color = Color.rgb(100, 100, 100)
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        canvas.drawText("Screen", left + 225f * sx, screenTop + 62f * sx, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 33f * sx
        canvas.drawText("${width} * ${height}", left + 225f * sx, screenTop + 132f * sx, textPaint)
        SketchIcons.drawPhoneScreenIcon(canvas, left + 530f * sx, screenTop + pinnedRowH / 2f, 84f * sx)
        hitZones.add(HitZone("PRESET_SCREEN", RectF(left, screenTop, right, screenBot)))

        val lastDims = ProjectStorageManager.loadLastDimensions(context, width, height)
        val lastTop = screenBot
        val lastBot = lastTop + pinnedRowH
        canvas.drawRect(left, lastTop, right, lastBot, bgPaint)

        textPaint.isFakeBoldText = true
        textPaint.textSize = 37f * sx
        canvas.drawText("Last", left + 225f * sx, lastTop + 62f * sx, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.textSize = 33f * sx
        canvas.drawText("${lastDims.first} * ${lastDims.second}", left + 225f * sx, lastTop + 132f * sx, textPaint)
        SketchIcons.drawLastCanvasIcon(canvas, left + 530f * sx, lastTop + pinnedRowH / 2f, 84f * sx)
        hitZones.add(HitZone("PRESET_LAST", RectF(left, lastTop, right, lastBot)))

        borderPaint.color = Color.rgb(198, 198, 198)
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
                drawSingleCustomPresetRow(canvas, customPresets[i], i, left, itemTop, panelW, rowH, sx, isFloating = false, isModInjected = false)
            }
        }

        for ((mIdx, mp) in injectedModPresets.withIndex()) {
            val visualIdx = customPresets.size + mIdx
            val itemTop = listTop + visualIdx * rowH - presetListScrollY
            if (itemTop + rowH >= listTop && itemTop <= listBot) {
                drawSingleCustomPresetRow(canvas, mp, -100 - mIdx, left, itemTop, panelW, rowH, sx, isFloating = false, isModInjected = true)
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
                isModInjected = false
            )
        }

        canvas.restore()

        val addTop = listBot
        val addBot = bodyTop + bodyH
        val addRect = RectF(left, addTop, right, addBot)
        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(addRect, bgPaint)
        borderPaint.color = Color.rgb(188, 188, 188)
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, addTop, right, addTop, borderPaint)

        textPaint.color = Color.rgb(55, 55, 55)
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
        isModInjected: Boolean
    ) {
        val right = left + panelW
        if (isFloating) {
            shadowPaint.color = Color.argb(60, 0, 0, 0)
            canvas.drawRect(left, rowTop - 4f * sx, right, rowTop + rowH + 6f * sx, shadowPaint)
            bgPaint.color = Color.rgb(232, 238, 248)
            canvas.drawRect(left, rowTop, right, rowTop + rowH, bgPaint)
        } else {
            bgPaint.color = Color.rgb(243, 243, 243)
            canvas.drawRect(left, rowTop, right, rowTop + rowH, bgPaint)
        }

        val cardLeft = left + 26f * sx
        val cardRight = left + 448f * sx
        val stripTop = rowTop + 22f * sx
        val stripBot = rowTop + 82f * sx
        bgPaint.color = if (isFloating) Color.WHITE else Color.rgb(247, 247, 247)
        canvas.drawRect(cardLeft, stripTop, cardRight, stripBot, bgPaint)

        textPaint.color = Color.rgb(95, 95, 95)
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
            SketchIcons.drawGearIcon(canvas, gearX, iconCy, 38f * sx, filled = false)
            SketchIcons.drawReorderIcon(canvas, reorderX, iconCy, 38f * sx, active = isFloating)

            if (!isFloating) {
                hitZones.add(HitZone("PRESET_SELECT", RectF(left, rowTop, left + 455f * sx, rowTop + rowH), index))
                hitZones.add(HitZone("PRESET_GEAR", RectF(left + 455f * sx, rowTop, left + 535f * sx, rowTop + rowH), index))
                hitZones.add(HitZone("PRESET_REORDER", RectF(left + 535f * sx, rowTop, right, rowTop + rowH), index))
            }
        } else if (isModInjected && !isFloating) {
            subTextPaint.color = Color.rgb(55, 116, 205)
            subTextPaint.textSize = 24f * sx
            subTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("MOD", left + 535f * sx, rowTop + 62f * sx, subTextPaint)
            hitZones.add(HitZone("PRESET_MOD_SELECT", RectF(left, rowTop, right, rowTop + rowH), index))
        }
    }

    private fun drawEditPresetMenu(canvas: Canvas, left: Float, top: Float, sx: Float) {
        val panelW = 622f * sx
        val headerH = 132f * sx
        val bodyH = 1190f * sx
        val right = left + panelW
        val bottom = top + headerH + bodyH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)

        val headerTitle = if (editingPresetIndex >= 0) "Edit Preset" else "Add Preset"
        drawPopupHeader(canvas, left, top, panelW, headerH, sx, headerTitle, "BACK_TO_PRESET_LIST")

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
            showDeleteButton = (editingPresetIndex >= 0)
        )
    }

    private fun drawSaveMenu(canvas: Canvas, left: Float, top: Float, sx: Float) {
        val panelW = if (project.isSavedOnce) 688f * sx else 580f * sx
        val headerH = 132f * sx
        val nameRowH = 118f * sx
        val itemRowH = 132f * sx
        val numActionRows = if (project.isSavedOnce) 2 else 1
        val totalH = headerH + nameRowH + itemRowH * numActionRows
        val right = left + panelW
        val bottom = top + totalH
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "Save", "BACK_TO_MAIN")

        val nameTop = top + headerH
        val nameBot = nameTop + nameRowH
        bgPaint.color = Color.rgb(238, 238, 238)
        canvas.drawRect(left, nameTop, right, nameBot, bgPaint)

        subTextPaint.color = Color.rgb(95, 95, 95)
        subTextPaint.textSize = 28f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Name:", left + 28f * sx, nameTop + nameRowH / 2f + 10f * sx, subTextPaint)

        val fileBox = RectF(left + 132f * sx, nameTop + 20f * sx, right - 26f * sx, nameBot - 20f * sx)
        bgPaint.color = Color.WHITE
        canvas.drawRect(fileBox, bgPaint)
        borderPaint.color = Color.rgb(117, 117, 117)
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(fileBox, borderPaint)

        textPaint.color = Color.rgb(20, 20, 20)
        textPaint.textSize = 34f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(project.name, textPaint, fileBox.width() - 30f * sx),
            fileBox.left + 16f * sx,
            fileBox.centerY() + 12f * sx,
            textPaint
        )
        hitZones.add(HitZone("EDIT_PROJECT_NAME", RectF(left, nameTop, right, nameBot)))

        borderPaint.color = Color.rgb(222, 222, 222)
        canvas.drawLine(left, nameBot, right, nameBot, borderPaint)

        val r1Top = nameBot
        val r1Bot = r1Top + itemRowH
        SketchIcons.drawPictureSaveIcon(canvas, left + 58f * sx, r1Top + itemRowH / 2f, 52f * sx, isCopy = false)
        textPaint.color = Color.rgb(18, 18, 18)
        textPaint.textSize = 39f * sx
        canvas.drawText("Save current sketch", left + 132f * sx, r1Top + itemRowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("ACTION_SAVE_CURRENT", RectF(left, r1Top, right, r1Bot)))

        if (project.isSavedOnce) {
            val r2Top = r1Bot
            val r2Bot = r2Top + itemRowH
            borderPaint.color = Color.rgb(225, 225, 225)
            canvas.drawLine(left, r2Top, right, r2Top, borderPaint)

            SketchIcons.drawPictureSaveIcon(canvas, left + 58f * sx, r2Top + itemRowH / 2f, 52f * sx, isCopy = true)
            canvas.drawText("Save current sketch as a copy", left + 132f * sx, r2Top + itemRowH / 2f + 14f * sx, textPaint)
            hitZones.add(HitZone("ACTION_SAVE_COPY", RectF(left, r2Top, right, r2Bot)))
        }
    }

    private fun drawShareExportMainMenu(canvas: Canvas, left: Float, top: Float, sx: Float) {
        val panelW = 622f * sx
        val rowH = 132f * sx
        val right = left + panelW
        val bottom = top + rowH * 3
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, rowH, sx, "Share / Export", "BACK_TO_MAIN")

        val r1Top = top + rowH
        val r1Bot = r1Top + rowH
        SketchIcons.drawFolderIcon(canvas, left + 58f * sx, r1Top + rowH / 2f, 52f * sx)
        textPaint.color = Color.rgb(18, 18, 18)
        textPaint.textSize = 39f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Export to Files", left + 132f * sx, r1Top + rowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("OPEN_EXPORT_AS", RectF(left, r1Top, right, r1Bot)))

        borderPaint.color = Color.rgb(225, 225, 225)
        canvas.drawLine(left, r1Bot, right, r1Bot, borderPaint)

        val r2Top = r1Bot
        val r2Bot = r2Top + rowH
        SketchIcons.drawShareIcon(canvas, left + 58f * sx, r2Top + rowH / 2f, 48f * sx, withStar = false)
        canvas.drawText("Share", left + 132f * sx, r2Top + rowH / 2f + 14f * sx, textPaint)
        hitZones.add(HitZone("OPEN_SHARE_AS", RectF(left, r2Top, right, r2Bot)))
    }

    private fun drawFormatListMenu(canvas: Canvas, left: Float, top: Float, sx: Float, title: String) {
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

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)

        drawPopupHeader(canvas, left, top, panelW, rowH, sx, title, "BACK_TO_SHARE_MAIN")

        for ((idx, fmt) in formats.withIndex()) {
            val ry = top + rowH * (idx + 1)
            val cy = ry + rowH / 2f
            SketchIcons.drawFileFormatIcon(
                c = canvas,
                cx = left + 82f * sx,
                cy = cy,
                size = 64f * sx,
                label = fmt.first,
                stacked = fmt.second,
                starred = (fmt.first == "PDF")
            )
            textPaint.color = Color.rgb(18, 18, 18)
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(fmt.third, left + 190f * sx, cy + 14f * sx, textPaint)

            if (idx < formats.size - 1) {
                borderPaint.color = Color.rgb(225, 225, 225)
                canvas.drawLine(left, ry + rowH, right, ry + rowH, borderPaint)
            }
        }
    }

    private fun drawModMenu(canvas: Canvas, left: Float, top: Float, sx: Float) {
        val panelW = 740f * sx
        val headerH = 132f * sx
        val toolbarH = 104f * sx
        val statusH = 66f * sx
        val bundleRowH = 148f * sx
        val numBundles = max(1, modEngine.bundles.size)
        val totalH = headerH + toolbarH + statusH + numBundles * bundleRowH + 20f * sx
        val right = left + panelW
        val bottom = min(height - 20f * sx, top + totalH)
        currentPopupBounds.set(left, top, right, bottom)

        bgPaint.color = Color.rgb(243, 243, 243)
        canvas.drawRect(currentPopupBounds, bgPaint)
        borderPaint.color = Color.rgb(200, 200, 200)
        borderPaint.strokeWidth = 2f * sx
        canvas.drawRect(currentPopupBounds, borderPaint)

        drawPopupHeader(canvas, left, top, panelW, headerH, sx, "Mods (Core Bundles)", "BACK_TO_MAIN")

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
            bgPaint.color = if (i == 0) Color.rgb(55, 116, 205) else Color.rgb(225, 225, 225)
            canvas.drawRect(bRect, bgPaint)
            borderPaint.color = Color.rgb(160, 160, 160)
            canvas.drawRect(bRect, borderPaint)

            textPaint.color = if (i == 0) Color.WHITE else Color.rgb(35, 35, 35)
            textPaint.textSize = 27f * sx
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(act.second, bRect.centerX(), bRect.centerY() + 10f * sx, textPaint)
            hitZones.add(HitZone(act.first, bRect))
        }

        val statTop = barBot
        val statBot = statTop + statusH
        bgPaint.color = Color.rgb(234, 234, 234)
        canvas.drawRect(left, statTop, right, statBot, bgPaint)
        subTextPaint.color = Color.rgb(80, 80, 80)
        subTextPaint.textSize = 25f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(modEngine.lastStatusMessage, subTextPaint, panelW - 40f * sx),
            left + 22f * sx,
            statTop + 42f * sx,
            subTextPaint
        )

        var ry = statBot
        for ((bIdx, bundle) in modEngine.bundles.withIndex()) {
            if (ry + bundleRowH > bottom) break
            val rRect = RectF(left, ry, right, ry + bundleRowH)
            bgPaint.color = if (bundle.enabled) Color.rgb(248, 251, 255) else Color.rgb(243, 243, 243)
            canvas.drawRect(rRect, bgPaint)

            textPaint.color = Color.rgb(20, 20, 20)
            textPaint.textSize = 33f * sx
            textPaint.isFakeBoldText = true
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(bundle.title, left + 26f * sx, ry + 50f * sx, textPaint)
            textPaint.isFakeBoldText = false

            subTextPaint.color = Color.rgb(100, 100, 100)
            subTextPaint.textSize = 24f * sx
            val descStr = "${bundle.functions.size} bundled fn(s) • ${bundle.description}"
            canvas.drawText(
                ellipsize(descStr, subTextPaint, panelW - 190f * sx),
                left + 26f * sx,
                ry + 92f * sx,
                subTextPaint
            )

            subTextPaint.color = Color.rgb(125, 125, 125)
            subTextPaint.textSize = 21f * sx
            val fnNames = bundle.functions.joinToString(", ") { it.type }
            canvas.drawText(
                ellipsize("Core hooks: $fnNames", subTextPaint, panelW - 190f * sx),
                left + 26f * sx,
                ry + 126f * sx,
                subTextPaint
            )

            val togW = 124f * sx
            val togH = 66f * sx
            val togX = right - togW - 26f * sx
            val togY = ry + (bundleRowH - togH) / 2f
            val togRect = RectF(togX, togY, togX + togW, togY + togH)

            bgPaint.color = if (bundle.enabled) Color.rgb(55, 116, 205) else Color.rgb(185, 185, 185)
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

            borderPaint.color = Color.rgb(220, 220, 220)
            canvas.drawLine(left, ry + bundleRowH, right, ry + bundleRowH, borderPaint)

            hitZones.add(HitZone("MOD_TOGGLE_BUNDLE", rRect, bIdx))
            ry += bundleRowH
        }
    }

    private fun drawPopupHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        panelW: Float,
        headerH: Float,
        sx: Float,
        title: String,
        backZoneId: String
    ) {
        val right = left + panelW
        val bot = top + headerH
        bgPaint.color = Color.rgb(239, 239, 239)
        canvas.drawRect(left, top, right, bot, bgPaint)

        SketchIcons.drawBackChevron(canvas, left + 56f * sx, top + headerH / 2f, 48f * sx)
        hitZones.add(HitZone(backZoneId, RectF(left, top, left + 135f * sx, bot)))

        textPaint.color = Color.rgb(35, 35, 35)
        textPaint.textSize = 40f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        canvas.drawText(title, left + panelW * 0.48f, top + headerH / 2f + 14f * sx, textPaint)

        borderPaint.color = Color.rgb(222, 222, 222)
        borderPaint.strokeWidth = 2f * sx
        canvas.drawLine(left, bot, right, bot, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val sx = width / 1080f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                lastTouchY = y
                isScrollingPresets = false
                isDraggingPreset = false
                draggedPresetIndex = -1

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

                if (abs(y - touchDownY) > 18f * sx || abs(x - touchDownX) > 18f * sx) {
                    cancelLongPress()
                    if (popupState == TopPopupState.NEW_SKETCH &&
                        newSketchTab == NewSketchTab.LIST_PRESETS &&
                        presetScrollAreaBounds.contains(touchDownX, touchDownY)
                    ) {
                        isScrollingPresets = true
                        presetListScrollY -= dy
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (isDraggingPreset) {
                    isDraggingPreset = false
                    draggedPresetIndex = -1
                    invalidate()
                    return true
                }
                if (!isScrollingPresets && abs(x - touchDownX) < 28f * sx && abs(y - touchDownY) < 28f * sx) {
                    handleTap(x, y)
                }
                isScrollingPresets = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                isDraggingPreset = false
                isScrollingPresets = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelLongPress() {
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
        if (zone == null) {
            if (popupState != TopPopupState.NONE && !currentPopupBounds.contains(x, y)) {
                popupState = TopPopupState.NONE
                invalidate()
            }
            return
        }

        when (zone.id) {
            "TOP_BTN_1" -> {
                popupState = if (popupState == TopPopupState.NONE) TopPopupState.BUTTON1_MAIN else TopPopupState.NONE
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

            "EDIT_PROJECT_NAME" -> {
                promptTextInput("Project / File Name", project.name, numericOnly = false) { newName ->
                    val cleaned = newName.trim()
                    if (cleaned.isNotEmpty()) {
                        project.name = cleaned
                        project.isDirty = true
                        invalidate()
                    }
                }
            }

            "MENU_NEW_SKETCH" -> {
                popupState = TopPopupState.NEW_SKETCH
                val last = ProjectStorageManager.loadLastDimensions(context, width, height)
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

            "INPUT_WIDTH_BOX" -> {
                promptTextInput("Width", customWidthInput.toString(), numericOnly = true) { text ->
                    val newW = text.toIntOrNull()?.coerceIn(0, 16384) ?: 0
                    customWidthInput = newW
                    if (isRatioLocked && lockedAspectRatio > 0.0001) {
                        customHeightInput = if (newW == 0) 0 else max(1, (newW / lockedAspectRatio).roundToInt())
                    }
                    invalidate()
                }
            }

            "INPUT_HEIGHT_BOX" -> {
                promptTextInput("Height", customHeightInput.toString(), numericOnly = true) { text ->
                    val newH = text.toIntOrNull()?.coerceIn(0, 16384) ?: 0
                    customHeightInput = newH
                    if (isRatioLocked && lockedAspectRatio > 0.0001) {
                        customWidthInput = if (newH == 0) 0 else max(1, (newH * lockedAspectRatio).roundToInt())
                    }
                    invalidate()
                }
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
                requestCreateNewProject(width, height)
            }

            "PRESET_LAST" -> {
                val last = ProjectStorageManager.loadLastDimensions(context, width, height)
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
                customWidthInput = width
                customHeightInput = height
                isRatioLocked = false
                lockedAspectRatio = customWidthInput.toDouble() / max(1, customHeightInput).toDouble()
                popupState = TopPopupState.EDIT_PRESET
                invalidate()
            }

            "EDIT_PRESET_NAME_FIELD" -> {
                promptTextInput("Preset Name", editingPresetName, numericOnly = false) { newName ->
                    val t = newName.trim()
                    if (t.isNotEmpty()) {
                        editingPresetName = t
                        invalidate()
                    }
                }
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
            project.importedBitmap?.recycle()
            project = SketchProjectState(
                name = project.name,
                width = targetW,
                height = targetH,
                importedBitmap = null,
                isDirty = false,
                isSavedOnce = false
            )
            showBanner("New Sketch: ${targetW} × ${targetH}")
            invalidate()
        }
    }

    fun applyImportedBitmapAsNewProject(bmp: android.graphics.Bitmap, suggestedName: String) {
        project.importedBitmap?.recycle()
        project = SketchProjectState(
            name = suggestedName.ifBlank { "Imported-Sketch" },
            width = bmp.width,
            height = bmp.height,
            importedBitmap = bmp,
            isDirty = true,
            isSavedOnce = false
        )
        ProjectStorageManager.saveLastDimensions(context, bmp.width, bmp.height)
        showBanner("Imported Project: ${bmp.width} × ${bmp.height}")
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
        val paperTint = modEngine.getCanvasPaperTintOverride(Color.WHITE)
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

    private fun promptTextInput(title: String, initialValue: String, numericOnly: Boolean, onConfirm: (String) -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 42, 52, 36)
            setBackgroundColor(Color.rgb(243, 243, 243))
        }

        val header = TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, 0, 0, 20)
        }
        root.addView(header)

        val input = EditText(context).apply {
            setText(initialValue)
            setSelection(text.length)
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 17f
            setPadding(28, 22, 28, 22)
            inputType = if (numericOnly) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        root.addView(input)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 32, 0, 0)
        }

        val dlg = AlertDialog.Builder(context).setView(root).create()

        val cancelBtn = TextView(context).apply {
            text = "CANCEL"
            setTextColor(Color.rgb(50, 50, 50))
            setBackgroundColor(Color.rgb(220, 220, 220))
            setPadding(40, 22, 40, 22)
            setOnClickListener { dlg.dismiss() }
        }

        val okBtn = TextView(context).apply {
            text = "OK"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(55, 116, 205))
            setPadding(52, 22, 52, 22)
            setOnClickListener {
                dlg.dismiss()
                onConfirm(input.text.toString())
            }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 20 }

        btnRow.addView(cancelBtn)
        btnRow.addView(okBtn, lp)
        root.addView(btnRow)
        dlg.show()
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
