package com.sketchbook.oss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class BrushAndColorModal(private val hostView: SketchWorkspaceView) {

    val brushSets: MutableList<SketchBrushSet> = DefaultBrushCatalog.createDefaultSets()
    var selectedSetIndex: Int = 0
    var selectedBrushIndex: Int = 14 // "Duplicate" in Basic set by default
    val colorState = ColorStudioState()

    var brushTopTab: BrushModalTopTab = BrushModalTopTab.LIBRARY_GRID
    var settingsSubTab: BrushSettingsSubTab = BrushSettingsSubTab.BASIC
    var subOverlay: BrushSubOverlay = BrushSubOverlay.NONE
    var expandedAdvancedSection: Int = -1 // -1 = none, 0 = Pressure, 1 = Stamp, 2 = Nib, 3 = Randomness

    var libraryScrollY: Float = 0f
    var advancedScrollY: Float = 0f

    // Touch / Drag tracking inside modal
    private var draggingSliderId: String? = null
    private var isDraggingHueRing: Boolean = false
    private var isDraggingSlDiamond: Boolean = false
    private var isScrollingLibrary: Boolean = false
    private var isScrollingAdvanced: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastY: Float = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    data class ModalHit(val id: String, val rect: RectF, val arg1: Int = -1, val arg2: Int = -1)
    private val hits = mutableListOf<ModalHit>()

    val outerCardRect = RectF()
    private val libraryScrollBounds = RectF()
    private val advancedScrollBounds = RectF()
    private val sliderRects = mutableMapOf<String, RectF>()

    private var wheelCx = 540f
    private var wheelCy = 925f
    private var wheelOuterR = 325f
    private var wheelInnerR = 220f
    private var diamondHalfDiag = 192f

    fun selectedSet(): SketchBrushSet {
        if (brushSets.isEmpty()) brushSets.addAll(DefaultBrushCatalog.createDefaultSets())
        selectedSetIndex = selectedSetIndex.coerceIn(0, brushSets.lastIndex)
        return brushSets[selectedSetIndex]
    }

    fun selectedBrush(): SketchBrushItem {
        val set = selectedSet()
        if (set.brushes.isEmpty()) {
            set.brushes.add(SketchBrushItem("b_fallback", "Standard Pen", BrushIconStyle.TECHNICAL_PEN))
        }
        selectedBrushIndex = selectedBrushIndex.coerceIn(0, set.brushes.lastIndex)
        return set.brushes[selectedBrushIndex]
    }

    fun isTouchInsideModalOrBottomBar(x: Float, y: Float, viewW: Float, viewH: Float): Boolean {
        val sx = viewW / 1080f
        if (outerCardRect.contains(x, y)) return true
        // Check bottom 3 floating circular buttons area
        if (y >= viewH - 220f * sx && x in (190f * sx)..(890f * sx)) return true
        return false
    }

    // ==========================================================================
    // MAIN MODAL RENDER DISPATCHER
    // ==========================================================================
    fun draw(canvas: Canvas, viewW: Float, viewH: Float, isColorMode: Boolean) {
        hits.clear()
        sliderRects.clear()

        val sx = viewW / 1080f
        val sy = viewH / 2408f

        // Outer White Sheet Frame matching 115925.jpg (x=23..1057, y=75..viewH)
        val sheetLeft = 22f * sx
        val sheetRight = viewW - 22f * sx
        val sheetTop = 72f * sy
        outerCardRect.set(sheetLeft, sheetTop, sheetRight, viewH)

        fillPaint.color = Color.WHITE
        canvas.drawRect(outerCardRect, fillPaint)

        val innerLeft = 124f * sx
        val innerRight = viewW - 124f * sx
        val innerTop = 124f * sy
        val innerBottom = viewH - 224f * sy

        if (isColorMode) {
            drawColorModalContent(canvas, innerLeft, innerTop, innerRight, innerBottom, sx, sy)
        } else {
            drawBrushModalContent(canvas, innerLeft, innerTop, innerRight, innerBottom, sx, sy)
        }

        // Draw Bottom 3 Circular Navigation Buttons (Color Wheel | X Close | Brush Studio)
        drawBottomThreeCircles(canvas, viewW, viewH, sx, sy, isColorMode)
    }

    // ==========================================================================
    // 1-TO-1 BRUSH MODAL (Library Grid, Pinned Preview, Set Settings, Brush Settings)
    // ==========================================================================
    private fun drawBrushModalContent(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        val topTabH = 132f * sy

        // 1. Top 2 Sub-Menu Tabs (Brush Library Grid vs Brush Settings Sliders)
        val midX = left + panelW * 0.5f
        val leftTabRect = RectF(left, top, midX, top + topTabH)
        val rightTabRect = RectF(midX, top, right, top + topTabH)

        fillPaint.color = if (brushTopTab == BrushModalTopTab.LIBRARY_GRID) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
        canvas.drawRect(leftTabRect, fillPaint)

        fillPaint.color = if (brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
        canvas.drawRect(rightTabRect, fillPaint)

        drawLibraryTabIcon(canvas, leftTabRect.centerX(), leftTabRect.centerY(), 58f * sx)
        drawSlidersTabIcon(canvas, rightTabRect.centerX(), rightTabRect.centerY(), 58f * sx)

        hits.add(ModalHit("TAB_BRUSH_LIBRARY", leftTabRect))
        hits.add(ModalHit("TAB_BRUSH_SETTINGS", rightTabRect))

        // 2. Pinned Live Contained Brush Stroke Preview Area (y = top+132 .. top+298)
        val prevTop = top + topTabH
        val prevH = 166f * sy
        val prevRect = RectF(left, prevTop, right, prevTop + prevH)
        fillPaint.color = Color.WHITE
        canvas.drawRect(prevRect, fillPaint)

        val activeBrush = selectedBrush()
        val activeSet = selectedSet()
        drawContainedBrushPreview(canvas, prevRect, activeBrush, sx)

        // 3. Pinned Selected Brush Info Bar (y = prevBot .. prevBot+158, #F0F0F0)
        val infoTop = prevRect.bottom
        val infoH = 158f * sy
        val infoBot = infoTop + infoH
        val infoRect = RectF(left, infoTop, right, infoBot)
        fillPaint.color = Color.rgb(240, 240, 240)
        canvas.drawRect(infoRect, fillPaint)

        // Brush Icon on left of Info Bar
        val iconRect = RectF(left + 28f * sx, infoTop + 16f * sy, left + 124f * sx, infoBot - 14f * sy)
        drawRealisticBrushIcon(canvas, iconRect, activeBrush.iconStyle, sx)

        // Brush Name + Set Name subtitle
        textPaint.color = Color.rgb(90, 90, 90)
        textPaint.textSize = 42f * sx
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = false
        canvas.drawText(activeBrush.name, left + 150f * sx, infoTop + 76f * sy, textPaint)

        subTextPaint.color = Color.rgb(118, 118, 118)
        subTextPaint.textSize = 31f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(activeSet.name, left + 150f * sx, infoTop + 128f * sy, subTextPaint)

        // In Brush Settings tab (or when Set/Brush overlay is open), show the '≡' box icon on the right of Info Bar!
        if (brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS || subOverlay == BrushSubOverlay.SET_SETTINGS || subOverlay == BrushSubOverlay.BRUSH_ACTIONS) {
            val listBtnRect = RectF(right - 120f * sx, infoTop + 26f * sy, right - 24f * sx, infoBot - 26f * sy)
            SketchIcons.drawTopIcon1Menu(canvas, listBtnRect.centerX(), listBtnRect.centerY(), 125f * sx, Color.rgb(95, 95, 95))
            hits.add(ModalHit("BTN_PINNED_BRUSH_ACTIONS", listBtnRect))
        }

        // 4. Body Area Below Pinned Header (infoBot .. bottom)
        val bodyTop = infoBot
        when {
            subOverlay == BrushSubOverlay.SET_SETTINGS -> {
                drawSetSettingsOverlay(canvas, left, bodyTop, right, bottom, sx, sy)
            }
            subOverlay == BrushSubOverlay.BRUSH_ACTIONS -> {
                drawBrushActionsOverlay(canvas, left, bodyTop, right, bottom, sx, sy)
            }
            brushTopTab == BrushModalTopTab.LIBRARY_GRID -> {
                drawScrollableBrushLibraryGrid(canvas, left, bodyTop, right, bottom, sx, sy)
            }
            else -> {
                drawBrushSettingsSlidersPanel(canvas, left, bodyTop, right, bottom, sx, sy, activeBrush)
            }
        }
    }

    /**
     * Renders a rich, realistic, contained S-curve stroke preview matching 115925.jpg & 115938.jpg.
     */
    private fun drawContainedBrushPreview(canvas: Canvas, rect: RectF, brush: SketchBrushItem, sx: Float) {
        canvas.save()
        canvas.clipRect(rect)

        val steps = 85
        val cy = rect.centerY()
        val amp = rect.height() * 0.14f
        val maxRad = (brush.size.coerceIn(3f, 200f) * 0.34f * sx).coerceIn(3f * sx, rect.height() * 0.36f)
        val baseAlpha = ((brush.opacity / 100f) * 255f).roundToInt().coerceIn(8, 255)

        val drawColor = when {
            brush.brushType == SketchBrushType.ERASER || colorState.isColorlessEraser -> Color.rgb(210, 215, 222)
            brush.name == "Duplicate" && brush.grainStrength > 50f -> Color.rgb(132, 92, 74) // Brown textured stroke like 115938.jpg!
            else -> colorState.currentColor
        }

        val r = Color.red(drawColor)
        val g = Color.green(drawColor)
        val b = Color.blue(drawColor)

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val x = rect.left + t * rect.width()
            val wave = sin(t * Math.PI * 2.0 + Math.PI * 0.9).toFloat() * amp
            val y = cy + wave

            // Natural pressure bell curve along the preview stroke
            val envelope = (0.38f + 0.62f * sin(t * Math.PI).toFloat()).coerceIn(0.2f, 1f)
            val rad = (maxRad * envelope).coerceAtLeast(2f)

            if (brush.brushType == SketchBrushType.GLOW) {
                previewPaint.color = Color.argb((baseAlpha * 0.12f).toInt().coerceAtLeast(4), r, g, b)
                canvas.drawCircle(x, y, rad * 1.9f, previewPaint)
                previewPaint.color = Color.argb((baseAlpha * 0.45f).toInt().coerceAtLeast(10), 255, 255, 255)
                canvas.drawCircle(x, y, rad * 0.55f, previewPaint)
            } else if (brush.grainStrength > 25f) {
                // Multi-ribbed overlay texture appearance matching 115938.jpg
                previewPaint.color = Color.argb((baseAlpha * 0.26f).toInt().coerceAtLeast(6), r, g, b)
                canvas.drawCircle(x, y, rad, previewPaint)
                strokePaint.color = Color.argb((baseAlpha * 0.48f).toInt().coerceAtLeast(12), (r * 0.7f).toInt(), (g * 0.7f).toInt(), (b * 0.7f).toInt())
                strokePaint.strokeWidth = 1.6f * sx
                for (rib in -3..3) {
                    val ry = y + rib * (rad * 0.24f)
                    canvas.drawPoint(x, ry, strokePaint)
                }
            } else if (brush.roundness < 75f || brush.brushType == SketchBrushType.MARKER) {
                previewPaint.color = Color.argb((baseAlpha * 0.42f).toInt().coerceAtLeast(12), r, g, b)
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(brush.rotation)
                val rx = rad
                val ry = rad * (brush.roundness / 100f).coerceIn(0.2f, 1f)
                canvas.drawOval(RectF(-rx, -ry, rx, ry), previewPaint)
                canvas.restore()
            } else {
                previewPaint.color = Color.argb(baseAlpha, r, g, b)
                canvas.drawCircle(x, y, rad, previewPaint)
            }
        }
        canvas.restore()
    }

    // ==========================================================================
    // SCROLLABLE BRUSH SETS & 5-COLUMN ICON GRID (115922, 115925, 115929)
    // ==========================================================================
    private fun drawScrollableBrushLibraryGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        fillPaint.color = Color.rgb(240, 240, 240)
        libraryScrollBounds.set(left, top, right, bottom)
        canvas.drawRect(libraryScrollBounds, fillPaint)

        val setHeaderH = 92f * sy
        val cellW = (panelW - 40f * sx) / 5f
        val cellH = 148f * sy

        var totalContentH = 24f * sy
        for (set in brushSets) {
            val rows = (set.brushes.size + 4) / 5
            totalContentH += setHeaderH + rows * cellH + 28f * sy
        }
        val maxScroll = max(0f, totalContentH - libraryScrollBounds.height())
        libraryScrollY = libraryScrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(libraryScrollBounds)

        var curY = top + 12f * sy - libraryScrollY
        for ((sIdx, set) in brushSets.withIndex()) {
            val isThisSetSelected = (sIdx == selectedSetIndex)

            // Set Header Row: Set Name on left, Pin Circle + (if active set) '≡' Set Settings button on right!
            if (curY + setHeaderH >= top && curY <= bottom) {
                textPaint.color = Color.rgb(92, 92, 92)
                textPaint.textSize = 37f * sx
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.isFakeBoldText = false
                canvas.drawText(set.name, left + 28f * sx, curY + 58f * sy, textPaint)

                val pinCx = if (isThisSetSelected) right - 154f * sx else right - 58f * sx
                val pinCy = curY + 46f * sy
                drawPinCircleIcon(canvas, pinCx, pinCy, 27f * sx, active = set.isPinned)
                hits.add(ModalHit("SET_TOGGLE_PIN", RectF(pinCx - 38f * sx, pinCy - 38f * sy, pinCx + 38f * sx, pinCy + 38f * sy), sIdx))

                if (isThisSetSelected) {
                    val menuCx = right - 58f * sx
                    SketchIcons.drawTopIcon1Menu(canvas, menuCx, pinCy, 110f * sx, Color.rgb(95, 95, 95))
                    hits.add(ModalHit("BTN_OPEN_SET_SETTINGS", RectF(menuCx - 48f * sx, pinCy - 42f * sy, menuCx + 48f * sx, pinCy + 42f * sy), sIdx))
                }
            }
            curY += setHeaderH

            // 5-Column Grid of Brushes in this Set
            val rows = (set.brushes.size + 4) / 5
            for (r in 0 until rows) {
                val rowTop = curY + r * cellH
                if (rowTop + cellH >= top && rowTop <= bottom) {
                    for (c in 0 until 5) {
                        val bIdx = r * 5 + c
                        if (bIdx >= set.brushes.size) break
                        val brush = set.brushes[bIdx]
                        val cellLeft = left + 20f * sx + c * cellW
                        val cellRect = RectF(cellLeft + 6f * sx, rowTop + 6f * sy, cellLeft + cellW - 6f * sx, rowTop + cellH - 6f * sy)

                        val isSelectedBrush = (sIdx == selectedSetIndex && bIdx == selectedBrushIndex)
                        if (isSelectedBrush) {
                            fillPaint.color = Color.WHITE
                            canvas.drawRoundRect(cellRect, 12f * sx, 12f * sx, fillPaint)
                            strokePaint.color = Color.rgb(42, 130, 215)
                            strokePaint.strokeWidth = 4.2f * sx
                            canvas.drawRoundRect(cellRect, 12f * sx, 12f * sx, strokePaint)
                        }

                        val iconBox = RectF(
                            cellRect.left + 16f * sx,
                            cellRect.top + 14f * sy,
                            cellRect.right - 16f * sx,
                            cellRect.bottom - 14f * sy
                        )
                        drawRealisticBrushIcon(canvas, iconBox, brush.iconStyle, sx)

                        val hitRect = RectF(cellRect.left, max(top, cellRect.top), cellRect.right, min(bottom, cellRect.bottom))
                        if (hitRect.height() > 15f * sy) {
                            hits.add(ModalHit("SELECT_BRUSH_CELL", hitRect, sIdx, bIdx))
                        }
                    }
                }
            }
            curY += rows * cellH + 28f * sy
        }

        // Small bottom-right scroll indicator diamond matching 115925.jpg
        drawCornerScrollArrows(canvas, right - 24f * sx, bottom - 24f * sy, 18f * sx)
        canvas.restore()
    }

    // ==========================================================================
    // SET SETTINGS MENU (120549.jpg — Filtered to Rename Set, Create New Set,
    // Duplicate Brush, and Delete Set with "confirm" prompt)
    // ==========================================================================
    private fun drawSetSettingsOverlay(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        fillPaint.color = Color.rgb(235, 235, 235)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val rowH = 148f * sy
        val items = listOf(
            Pair("SET_ACT_RENAME", "Rename Set"),
            Pair("SET_ACT_CREATE_NEW", "Create New Set"),
            Pair("SET_ACT_DUPLICATE_BRUSH", "Duplicate Brush"),
            Pair("SET_ACT_DELETE_SET", "Delete Set")
        )

        for ((i, item) in items.withIndex()) {
            val ry = top + i * rowH
            val cy = ry + rowH * 0.5f
            val iconCx = left + 108f * sx

            when (item.first) {
                "SET_ACT_RENAME" -> drawRenameBoxIcon(canvas, iconCx, cy, 72f * sx)
                "SET_ACT_CREATE_NEW" -> drawCreateSetIcon(canvas, iconCx, cy, 62f * sx)
                "SET_ACT_DUPLICATE_BRUSH" -> drawDuplicateNibIcon(canvas, iconCx, cy, 60f * sx)
                "SET_ACT_DELETE_SET" -> SketchIcons.drawTrashIcon(canvas, iconCx, cy, 56f * sx, Color.rgb(92, 92, 92))
            }

            textPaint.color = Color.rgb(88, 88, 88)
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.second, left + 205f * sx, cy + 14f * sy, textPaint)

            strokePaint.color = Color.rgb(130, 130, 130)
            strokePaint.strokeWidth = 2f
            canvas.drawLine(left, ry + rowH, right, ry + rowH, strokePaint)

            hits.add(ModalHit(item.first, RectF(left, ry, right, ry + rowH)))
        }
    }

    // ==========================================================================
    // BRUSH ACTION MENU (121020.jpg — Rename, Duplicate, Change Icon, Export Icon, Delete)
    // ==========================================================================
    private fun drawBrushActionsOverlay(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        fillPaint.color = Color.rgb(235, 235, 235)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        val rowH = 148f * sy
        val items = listOf(
            Pair("BRUSH_ACT_RENAME", "Rename"),
            Pair("BRUSH_ACT_DUPLICATE", "Duplicate"),
            Pair("BRUSH_ACT_CHANGE_ICON", "Change Icon"),
            Pair("BRUSH_ACT_EXPORT_ICON", "Export Icon"),
            Pair("BRUSH_ACT_DELETE", "Delete")
        )

        for ((i, item) in items.withIndex()) {
            val ry = top + i * rowH
            val cy = ry + rowH * 0.5f
            val iconCx = left + 108f * sx

            when (item.first) {
                "BRUSH_ACT_RENAME" -> drawRenameBoxIcon(canvas, iconCx, cy, 72f * sx)
                "BRUSH_ACT_DUPLICATE" -> drawDuplicateNibIcon(canvas, iconCx, cy, 60f * sx)
                "BRUSH_ACT_CHANGE_ICON" -> drawChangeIconBox(canvas, iconCx, cy, 62f * sx)
                "BRUSH_ACT_EXPORT_ICON" -> drawExportIconBox(canvas, iconCx, cy, 62f * sx)
                "BRUSH_ACT_DELETE" -> SketchIcons.drawTrashIcon(canvas, iconCx, cy, 56f * sx, Color.rgb(92, 92, 92))
            }

            textPaint.color = Color.rgb(88, 88, 88)
            textPaint.textSize = 39f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(item.second, left + 205f * sx, cy + 14f * sy, textPaint)

            if (i < items.size - 1) {
                strokePaint.color = Color.rgb(130, 130, 130)
                strokePaint.strokeWidth = 2f
                canvas.drawLine(left, ry + rowH, right, ry + rowH, strokePaint)
            }

            hits.add(ModalHit(item.first, RectF(left, ry, right, ry + rowH)))
        }
    }

    // ==========================================================================
    // BRUSH SETTINGS PANEL (115938.jpg, 121144.jpg, 121151.jpg)
    // ==========================================================================
    private fun drawBrushSettingsSlidersPanel(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        brush: SketchBrushItem
    ) {
        val panelW = right - left
        fillPaint.color = Color.rgb(48, 48, 48)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        // 1. "Brush Types:    Standard >" Row
        val typeRowH = 148f * sy
        val typeRowBot = top + typeRowH
        textPaint.color = Color.WHITE
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Brush Types:", left + 40f * sx, top + 90f * sy, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(brush.brushType.title, right - 102f * sx, top + 90f * sy, textPaint)

        // '>' or 'v' chevron
        val chevX = right - 54f * sx
        val chevY = top + 76f * sy
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 4.5f * sx
        if (subOverlay == BrushSubOverlay.BRUSH_TYPES_DROPDOWN) {
            canvas.drawLine(chevX - 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
            canvas.drawLine(chevX + 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
        } else {
            canvas.drawLine(chevX - 8f * sx, chevY - 18f * sy, chevX + 12f * sx, chevY, strokePaint)
            canvas.drawLine(chevX - 8f * sx, chevY + 18f * sy, chevX + 12f * sx, chevY, strokePaint)
        }
        hits.add(ModalHit("TOGGLE_BRUSH_TYPES_DROPDOWN", RectF(left, top, right, typeRowBot)))

        // 2. "BASIC | ADVANCED" Sub-Tabs Bar (y = typeRowBot .. typeRowBot + 128)
        val subTabH = 128f * sy
        val subTabBot = typeRowBot + subTabH
        val midX = left + panelW * 0.5f
        fillPaint.color = Color.rgb(41, 41, 41)
        canvas.drawRect(left, typeRowBot, right, subTabBot, fillPaint)
        strokePaint.color = Color.rgb(32, 32, 32)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(midX, typeRowBot + 16f * sy, midX, subTabBot - 16f * sy, strokePaint)

        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (settingsSubTab == BrushSettingsSubTab.BASIC) Color.rgb(65, 148, 238) else Color.rgb(115, 115, 115)
        canvas.drawText("BASIC", (left + midX) * 0.5f, typeRowBot + 78f * sy, textPaint)

        textPaint.color = if (settingsSubTab == BrushSettingsSubTab.ADVANCED) Color.rgb(65, 148, 238) else Color.rgb(115, 115, 115)
        canvas.drawText("ADVANCED", (midX + right) * 0.5f, typeRowBot + 78f * sy, textPaint)

        hits.add(ModalHit("SUBTAB_BASIC", RectF(left, typeRowBot, midX, subTabBot)))
        hits.add(ModalHit("SUBTAB_ADVANCED", RectF(midX, typeRowBot, right, subTabBot)))

        // 3. Pinned Bottom "Reset" Button (bottom - 130 .. bottom)
        val resetH = 130f * sy
        val resetTop = bottom - resetH
        val contentBottom = resetTop

        if (settingsSubTab == BrushSettingsSubTab.BASIC) {
            var syCur = subTabBot + 36f * sy
            syCur = drawDarkSettingsSlider(
                canvas, left, right, syCur, sx, sy,
                id = "SLIDER_BASIC_SIZE",
                label = "Size",
                valueStr = String.format("%.1f", brush.size),
                fraction = ((brush.size - 1f) / 299f).coerceIn(0f, 1f)
            )
            strokePaint.color = Color.rgb(38, 38, 38)
            canvas.drawLine(left, syCur, right, syCur, strokePaint)
            syCur += 28f * sy
            drawDarkSettingsSlider(
                canvas, left, right, syCur, sx, sy,
                id = "SLIDER_BASIC_OPACITY",
                label = "Opacity",
                valueStr = "${brush.opacity.roundToInt()}%",
                fraction = (brush.opacity / 100f).coerceIn(0f, 1f)
            )
        } else {
            drawAdvancedAccordionSections(canvas, left, subTabBot, right, contentBottom, sx, sy, brush)
        }

        // Draw Pinned Reset Button
        fillPaint.color = Color.rgb(48, 48, 48)
        canvas.drawRect(left, resetTop, right, bottom, fillPaint)
        strokePaint.color = Color.rgb(190, 190, 190)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(left, resetTop, right, resetTop, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 38f * sx
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Reset", (left + right) * 0.5f, resetTop + 80f * sy, textPaint)
        hits.add(ModalHit("BTN_RESET_BRUSH", RectF(left, resetTop, right, bottom)))

        // If Brush Types Dropdown is open, render it overlaying the settings area (matching 121144.jpg)
        if (subOverlay == BrushSubOverlay.BRUSH_TYPES_DROPDOWN) {
            drawBrushTypesDropdownOverlay(canvas, left + 22f * sx, subTabBot - 18f * sy, right - 22f * sx, bottom, sx, sy, brush)
        }
    }

    private fun drawAdvancedAccordionSections(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        brush: SketchBrushItem
    ) {
        advancedScrollBounds.set(left, top, right, bottom)
        val sections = listOf("Pressure", "Stamp", "Nib", "Randomness")
        val rowH = 128f * sy

        canvas.save()
        canvas.clipRect(advancedScrollBounds)

        var curY = top - advancedScrollY
        for ((idx, title) in sections.withIndex()) {
            val rRect = RectF(left, curY, right, curY + rowH)
            fillPaint.color = Color.rgb(48, 48, 48)
            canvas.drawRect(rRect, fillPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 38f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(title, left + 40f * sx, curY + 78f * sy, textPaint)

            val chevX = right - 54f * sx
            val chevY = curY + 64f * sy
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = 4.5f * sx
            if (expandedAdvancedSection == idx) {
                canvas.drawLine(chevX - 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
                canvas.drawLine(chevX + 18f * sx, chevY - 8f * sy, chevX, chevY + 12f * sy, strokePaint)
            } else {
                canvas.drawLine(chevX - 8f * sx, chevY - 18f * sy, chevX + 12f * sx, chevY, strokePaint)
                canvas.drawLine(chevX - 8f * sx, chevY + 18f * sy, chevX + 12f * sx, chevY, strokePaint)
            }

            strokePaint.color = Color.rgb(38, 38, 38)
            strokePaint.strokeWidth = 2f
            canvas.drawLine(left, curY + rowH, right, curY + rowH, strokePaint)

            val clippedHit = RectF(left, max(top, curY), right, min(bottom, curY + rowH))
            if (clippedHit.height() > 20f * sy) {
                hits.add(ModalHit("TOGGLE_ADV_SECTION", clippedHit, idx))
            }
            curY += rowH

            if (expandedAdvancedSection == idx) {
                curY += 18f * sy
                when (idx) {
                    0 -> { // Pressure
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_PRESS_SIZE_HEAVY", "Size Heavy Pressure", "${brush.sizeHeavyPressure.roundToInt()}%", brush.sizeHeavyPressure / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_PRESS_SIZE_LIGHT", "Size Light Pressure", "${brush.sizeLightPressure.roundToInt()}%", brush.sizeLightPressure / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_PRESS_OPAC_LIGHT", "Min Opacity Pressure", "${brush.opacityLightPressure.roundToInt()}%", brush.opacityLightPressure / 100f)
                    }
                    1 -> { // Stamp
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_STAMP_SPACING", "Spacing", "${brush.spacing.roundToInt()}%", (brush.spacing - 2f) / 98f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_STAMP_ROUND", "Roundness", "${brush.roundness.roundToInt()}%", (brush.roundness - 10f) / 90f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_STAMP_ROT", "Rotation", "${brush.rotation.roundToInt()}°", (brush.rotation + 180f) / 360f)
                    }
                    2 -> { // Nib
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_NIB_HARDNESS", "Hardness", "${brush.hardness.roundToInt()}%", brush.hardness / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_NIB_GRAIN", "Texture Grain", "${brush.grainStrength.roundToInt()}%", brush.grainStrength / 100f)
                    }
                    3 -> { // Randomness
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_RAND_SIZE", "Size Jitter", "${brush.sizeJitter.roundToInt()}%", brush.sizeJitter / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_RAND_OPAC", "Opacity Jitter", "${brush.opacityJitter.roundToInt()}%", brush.opacityJitter / 100f)
                        curY = drawDarkSettingsSlider(canvas, left, right, curY, sx, sy, "ADV_RAND_SCATTER", "Scatter", "${brush.scatterJitter.roundToInt()}%", brush.scatterJitter / 100f)
                    }
                }
            }
        }
        canvas.restore()
    }

    private fun drawDarkSettingsSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        sx: Float,
        sy: Float,
        id: String,
        label: String,
        valueStr: String,
        fraction: Float
    ): Float {
        val f = fraction.coerceIn(0f, 1f)
        textPaint.color = Color.WHITE
        textPaint.textSize = 36f * sx
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, left + 40f * sx, top + 46f * sy, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(valueStr, right - 40f * sx, top + 46f * sy, textPaint)

        val trackLeft = left + 40f * sx
        val trackRight = right - 40f * sx
        val trackY = top + 124f * sy
        val thumbX = trackLeft + (trackRight - trackLeft) * f

        // Unfilled right track (#A8A8A8)
        strokePaint.color = Color.rgb(168, 168, 168)
        strokePaint.strokeWidth = 4.2f * sx
        canvas.drawLine(thumbX, trackY, trackRight, trackY, strokePaint)

        // Filled left track (#509AE3)
        strokePaint.color = Color.rgb(80, 154, 227)
        strokePaint.strokeWidth = 5.2f * sx
        canvas.drawLine(trackLeft, trackY, thumbX, trackY, strokePaint)

        // White circular thumb knob
        val knobR = 26f * sx
        fillPaint.color = Color.WHITE
        canvas.drawCircle(thumbX, trackY, knobR, fillPaint)

        val hitRect = RectF(trackLeft - 30f * sx, trackY - 48f * sy, trackRight + 30f * sx, trackY + 48f * sy)
        sliderRects[id] = hitRect
        return top + 195f * sy
    }

    private fun drawBrushTypesDropdownOverlay(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float,
        brush: SketchBrushItem
    ) {
        val types = SketchBrushType.values()
        val rowH = 132f * sy
        val boxBot = min(bottom - 16f * sy, top + types.size * rowH + 24f * sy)
        val dropRect = RectF(left, top, right, boxBot)

        fillPaint.color = Color.rgb(39, 39, 39)
        canvas.drawRoundRect(dropRect, 14f * sx, 14f * sx, fillPaint)

        for ((i, t) in types.withIndex()) {
            val ry = top + 12f * sy + i * rowH
            val isSel = (brush.brushType == t)
            textPaint.color = if (isSel) Color.rgb(65, 148, 238) else Color.WHITE
            textPaint.textSize = 38f * sx
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(t.title, left + 42f * sx, ry + 80f * sy, textPaint)

            if (isSel) {
                // Blue checkmark matching 121144.jpg
                strokePaint.color = Color.rgb(55, 140, 235)
                strokePaint.strokeWidth = 5f * sx
                val cx = right - 95f * sx
                val cy = ry + 66f * sy
                canvas.drawLine(cx - 18f * sx, cy, cx - 4f * sx, cy + 14f * sy, strokePaint)
                canvas.drawLine(cx - 4f * sx, cy + 14f * sy, cx + 24f * sx, cy - 14f * sy, strokePaint)
            }

            hits.add(ModalHit("SELECT_BRUSH_TYPE", RectF(left, ry, right, ry + rowH), i))
        }
    }

    // ==========================================================================
    // 1-TO-1 SKETCHBOOK COLOR MENU (115958.jpg)
    // ==========================================================================
    private fun drawColorModalContent(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sx: Float,
        sy: Float
    ) {
        val panelW = right - left
        val topTabH = 132f * sy

        // 1. Top 3 Tabs (Color Wheel | Swatch Grid | Image Reference Picker)
        val tabW = panelW / 3f
        for (i in 0..2) {
            val tLeft = left + i * tabW
            val tRect = RectF(tLeft, top, tLeft + tabW, top + topTabH)
            fillPaint.color = if (colorState.topTabIndex == i) Color.rgb(20, 120, 216) else Color.rgb(142, 142, 142)
            canvas.drawRect(tRect, fillPaint)

            when (i) {
                0 -> SketchIcons.drawTopIcon6ColorWheel(canvas, tRect.centerX(), tRect.centerY(), topTabH * 0.95f)
                1 -> SketchIcons.drawTopIcon4Grid(canvas, tRect.centerX(), tRect.centerY(), topTabH * 0.95f, Color.rgb(225, 225, 225))
                2 -> SketchIcons.drawPictureSaveIcon(canvas, tRect.centerX(), tRect.centerY(), 54f * sx, isCopy = false, color = Color.rgb(225, 225, 225), bgMask = Color.rgb(142, 142, 142))
            }
            hits.add(ModalHit("COLOR_TOP_TAB", tRect, i))
        }

        // Dark #343434 Body
        val bodyTop = top + topTabH
        fillPaint.color = Color.rgb(52, 52, 52)
        canvas.drawRect(left, bodyTop, right, bottom, fillPaint)

        if (colorState.topTabIndex == 1) {
            drawFullCopicSwatchGridTab(canvas, left, bodyTop, right, bottom, sx, sy)
            return
        }

        // 2. Top Swatch Box + Complementary Strip + Color History Strip (y = bodyTop..bodyTop+290)
        val swatchBox = RectF(left + 28f * sx, bodyTop + 48f * sy, left + 220f * sx, bodyTop + 236f * sy)
        val halfSwW = swatchBox.width() * 0.5f
        // Left half: Previous Color; Right half: Current Color
        fillPaint.color = colorState.previousColor
        canvas.drawRoundRect(RectF(swatchBox.left, swatchBox.top, swatchBox.left + halfSwW + 6f * sx, swatchBox.bottom), 8f * sx, 8f * sx, fillPaint)
        fillPaint.color = colorState.currentColor
        canvas.drawRoundRect(RectF(swatchBox.left + halfSwW, swatchBox.top, swatchBox.right, swatchBox.bottom), 8f * sx, 8f * sx, fillPaint)
        hits.add(ModalHit("REVERT_PREV_COLOR", RectF(swatchBox.left, swatchBox.top, swatchBox.left + halfSwW, swatchBox.bottom)))

        // Complementary Label & 6-Chip Strip
        val stripLeft = left + 246f * sx
        val stripRight = right - 168f * sx
        subTextPaint.color = Color.WHITE
        subTextPaint.textSize = 27f * sx
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Complementary", stripLeft, bodyTop + 62f * sy, subTextPaint)

        val compColors = colorState.computeHarmonyStrip()
        val compTop = bodyTop + 82f * sy
        val compBot = bodyTop + 128f * sy
        val compChipW = (stripRight - stripLeft) / compColors.size
        for ((idx, cc) in compColors.withIndex()) {
            val cRect = RectF(stripLeft + idx * compChipW, compTop, stripLeft + (idx + 1) * compChipW, compBot)
            fillPaint.color = cc
            canvas.drawRect(cRect, fillPaint)
            hits.add(ModalHit("PICK_COMP_COLOR", cRect, cc))
        }

        // Harmony Mode Cycle Button (Interlocking rings icon on right of Complementary)
        val harmCx = right - 88f * sx
        val harmCy = (compTop + compBot) * 0.5f
        drawHarmonyRingsIcon(canvas, harmCx, harmCy, 24f * sx)
        hits.add(ModalHit("CYCLE_HARMONY_MODE", RectF(harmCx - 42f * sx, harmCy - 42f * sy, harmCx + 42f * sx, harmCy + 42f * sy)))

        // Color History Label & 9-Chip Strip
        canvas.drawText("Color History", stripLeft, bodyTop + 168f * sy, subTextPaint)
        val histTop = bodyTop + 188f * sy
        val histBot = bodyTop + 234f * sy
        val histChipW = (stripRight - stripLeft) / 9f
        for (i in 0 until min(9, colorState.colorHistory.size)) {
            val hc = colorState.colorHistory[i]
            val hRect = RectF(stripLeft + i * histChipW, histTop, stripLeft + (i + 1) * histChipW, histBot)
            fillPaint.color = hc
            canvas.drawRect(hRect, fillPaint)
            hits.add(ModalHit("PICK_HIST_COLOR", hRect, hc))
        }

        // Barcode / Shuffle History Icon on right of Color History
        val barCx = right - 88f * sx
        val barCy = (histTop + histBot) * 0.5f
        drawBarcodeIcon(canvas, barCx, barCy, 22f * sx, Color.WHITE)
        hits.add(ModalHit("SHUFFLE_HISTORY_BAR", RectF(barCx - 42f * sx, barCy - 42f * sy, barCx + 42f * sx, barCy + 42f * sy)))

        // 3. Center Precision Color Wheel + Rotated Saturation/Lightness Diamond
        wheelCx = (left + right) * 0.5f
        wheelCy = bodyTop + 668f * sy
        wheelOuterR = 324f * sx
        wheelInnerR = 222f * sx
        diamondHalfDiag = 192f * sx

        // Top-Left Transparent Eraser Button (cx = left+78, cy = bodyTop+380)
        val transCx = left + 78f * sx
        val transCy = bodyTop + 382f * sy
        drawTransparentEraserButton(canvas, transCx, transCy, 44f * sx, colorState.isColorlessEraser)
        hits.add(ModalHit("TOGGLE_COLORLESS_ERASER", RectF(transCx - 52f * sx, transCy - 52f * sy, transCx + 52f * sx, transCy + 52f * sy)))

        // Top-Right Eyedropper Pipette Button (cx = right-78, cy = bodyTop+380)
        val dropCx = right - 78f * sx
        val dropCy = bodyTop + 382f * sy
        drawEyedropperIcon(canvas, dropCx, dropCy, 42f * sx)
        hits.add(ModalHit("ACTIVATE_EYEDROPPER", RectF(dropCx - 52f * sx, dropCy - 52f * sy, dropCx + 52f * sx, dropCy + 52f * sy)))

        // Dark outer shadow ring + black inner circle backing
        fillPaint.color = Color.rgb(20, 20, 20)
        canvas.drawCircle(wheelCx, wheelCy, wheelOuterR + 12f * sx, fillPaint)

        // Continuous 360° Hue Ring (0° at Right = Red, clockwise: Magenta -> Blue -> Cyan -> Green -> Yellow -> Red)
        // Wait: Look at 115958.jpg!
        // In 115958.jpg: Top is Yellow, Right is Orange/Red, Bottom-Right is Magenta, Bottom is Blue, Left is Green!
        // Let's match the exact angular colors of 115958.jpg:
        // Angle 0° (Right) = Red (#FF1E00), 60° (Bottom-Right) = Magenta (#FF00AA), 110° (Bottom) = Blue (#0018FF),
        // 155° (Bottom-Left) = Cyan (#00E5FF), 205° (Left) = Green (#00FF18), 270° (Top) = Yellow (#FFD000), 360° = Red!
        val ringRadius = (wheelOuterR + wheelInnerR) * 0.5f
        val ringThickness = wheelOuterR - wheelInnerR
        val hueSweepColors = intArrayOf(
            Color.RED,
            Color.MAGENTA,
            Color.BLUE,
            Color.CYAN,
            Color.GREEN,
            Color.YELLOW,
            Color.rgb(255, 128, 0),
            Color.RED
        )
        strokePaint.shader = SweepGradient(wheelCx, wheelCy, hueSweepColors, null)
        strokePaint.strokeWidth = ringThickness
        canvas.drawCircle(wheelCx, wheelCy, ringRadius, strokePaint)
        strokePaint.shader = null

        // Inner Rotated Diamond (Top = White, Bottom = Black, Right = Pure Hue, Left = 50% Gray)
        drawRotatedSlDiamond(canvas, wheelCx, wheelCy, diamondHalfDiag, colorState.hue)

        // Hue Ring Indicator Thumb Circle
        val hueAngleRad = hueToWheelAngleRad(colorState.hue)
        val hThumbX = wheelCx + cos(hueAngleRad).toFloat() * ringRadius
        val hThumbY = wheelCy + sin(hueAngleRad).toFloat() * ringRadius
        val pureHueColor = ColorStudioState.hslToColor(colorState.hue, 1f, 0.5f)
        drawSelectorRing(canvas, hThumbX, hThumbY, 27f * sx, pureHueColor)

        // Diamond (S, L) Indicator Thumb Circle
        val dPt = slToDiamondPoint(colorState.saturation, colorState.lightness, wheelCx, wheelCy, diamondHalfDiag)
        drawSelectorRing(canvas, dPt.x, dPt.y, 27f * sx, colorState.currentColor)

        // Bottom-Right HEX # Readout & Inline Editor
        subTextPaint.color = Color.rgb(138, 138, 138)
        subTextPaint.textSize = 33f * sx
        subTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("HEX #", right - 26f * sx, bodyTop + 926f * sy, subTextPaint)

        val hexStr = String.format("%06X", 0xFFFFFF and colorState.currentColor)
        textPaint.color = Color.rgb(225, 225, 225)
        textPaint.textSize = 37f * sx
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(hexStr, right - 26f * sx, bodyTop + 984f * sy, textPaint)
        hits.add(ModalHit("EDIT_HEX_COLOR", RectF(right - 210f * sx, bodyTop + 885f * sy, right - 12f * sx, bodyTop + 1005f * sy)))

        // 4. Middle Mode Bar: HSL | RGB | Grid | Shuffle | Tonal Strip (y = bodyTop + 1065)
        val modeY = bodyTop + 1095f * sy
        val modeSlotW = panelW / 5f
        for (m in 0..4) {
            val mx = left + (m + 0.5f) * modeSlotW
            val mRect = RectF(left + m * modeSlotW, modeY - 45f * sy, left + (m + 1) * modeSlotW, modeY + 45f * sy)
            val isAct = (colorState.bottomModeIndex == m)
            val tint = if (isAct) Color.rgb(225, 225, 225) else Color.rgb(112, 112, 112)
            when (m) {
                0 -> {
                    textPaint.color = tint
                    textPaint.textSize = 38f * sx
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("HSL", mx, modeY + 14f * sy, textPaint)
                    textPaint.isFakeBoldText = false
                }
                1 -> {
                    textPaint.color = tint
                    textPaint.textSize = 38f * sx
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("RGB", mx, modeY + 14f * sy, textPaint)
                    textPaint.isFakeBoldText = false
                }
                2 -> drawSmall3x2GridIcon(canvas, mx, modeY, 34f * sx, tint)
                3 -> drawShuffleArrowsIcon(canvas, mx, modeY, 34f * sx, tint)
                4 -> drawBarcodeIcon(canvas, mx, modeY, 30f * sx, tint)
            }
            hits.add(ModalHit("COLOR_BOTTOM_MODE", mRect, m))
        }

        // 5. Bottom Sliders (H/S/L or R/G/B) or Quick Swatches
        val slidersTop = bodyTop + 1190f * sy
        if (colorState.bottomModeIndex == 1) {
            // RGB Sliders
            val r = Color.red(colorState.currentColor)
            val g = Color.green(colorState.currentColor)
            val b = Color.blue(colorState.currentColor)
            drawColorChannelSlider(canvas, left, right, slidersTop, sx, sy, "COLOR_R", "R", r.toString(), r / 255f, Color.BLACK, Color.rgb(255, g, b))
            drawColorChannelSlider(canvas, left, right, slidersTop + 132f * sy, sx, sy, "COLOR_G", "G", g.toString(), g / 255f, Color.BLACK, Color.rgb(r, 255, b))
            drawColorChannelSlider(canvas, left, right, slidersTop + 264f * sy, sx, sy, "COLOR_B", "B", b.toString(), b / 255f, Color.BLACK, Color.rgb(r, g, 255))
        } else {
            // HSL Sliders matching 115958.jpg
            val hInt = colorState.hue.roundToInt()
            val sInt = (colorState.saturation * 100f).roundToInt()
            val lInt = (colorState.lightness * 100f).roundToInt()
            drawColorChannelSlider(canvas, left, right, slidersTop, sx, sy, "COLOR_H", "H", hInt.toString(), colorState.hue / 360f, Color.rgb(12, 12, 12), Color.rgb(12, 12, 12))
            drawColorChannelSlider(canvas, left, right, slidersTop + 132f * sy, sx, sy, "COLOR_S", "S", sInt.toString(), colorState.saturation, Color.rgb(12, 12, 12), Color.rgb(12, 12, 12))
            drawColorChannelSlider(canvas, left, right, slidersTop + 264f * sy, sx, sy, "COLOR_L", "L", lInt.toString(), colorState.lightness, Color.BLACK, Color.WHITE)
        }
    }

    private fun drawColorChannelSlider(
        canvas: Canvas,
        left: Float,
        right: Float,
        yCenter: Float,
        sx: Float,
        sy: Float,
        id: String,
        label: String,
        valStr: String,
        fraction: Float,
        gradStart: Int,
        gradEnd: Int
    ) {
        val f = fraction.coerceIn(0f, 1f)
        subTextPaint.color = Color.rgb(155, 155, 155)
        subTextPaint.textSize = 33f * sx
        subTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, left + 90f * sx, yCenter + 12f * sy, subTextPaint)

        val trackLeft = left + 148f * sx
        val trackRight = right - 148f * sx
        val trackH = 26f * sy
        val trackRect = RectF(trackLeft, yCenter - trackH * 0.5f, trackRight, yCenter + trackH * 0.5f)

        fillPaint.shader = LinearGradient(trackLeft, yCenter, trackRight, yCenter, gradStart, gradEnd, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, fillPaint)
        fillPaint.shader = null

        val knobX = trackLeft + (trackRight - trackLeft) * f
        fillPaint.color = Color.WHITE
        canvas.drawCircle(knobX, yCenter, 26f * sx, fillPaint)

        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(valStr, right - 112f * sx, yCenter + 12f * sy, subTextPaint)

        sliderRects[id] = RectF(trackLeft - 30f * sx, yCenter - 48f * sy, trackRight + 30f * sx, yCenter + 48f * sy)
    }

    private fun drawRotatedSlDiamond(canvas: Canvas, cx: Float, cy: Float, halfDiag: Float, hue: Float) {
        val p = Path().apply {
            moveTo(cx, cy - halfDiag) // Top = White
            lineTo(cx + halfDiag, cy) // Right = Pure Hue
            lineTo(cx, cy + halfDiag) // Bottom = Black
            lineTo(cx - halfDiag, cy) // Left = Gray
            close()
        }
        canvas.save()
        canvas.clipPath(p)

        val pureHue = ColorStudioState.hslToColor(hue, 1f, 0.5f)
        // Base horizontal gradient: Left (Gray) -> Right (Pure Hue)
        fillPaint.shader = LinearGradient(
            cx - halfDiag, cy, cx + halfDiag, cy,
            Color.rgb(128, 128, 128), pureHue, Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - halfDiag, cy - halfDiag, cx + halfDiag, cy + halfDiag, fillPaint)

        // Vertical lightness overlay: Top (White) -> Middle (Transparent) -> Bottom (Black)
        fillPaint.shader = LinearGradient(
            cx, cy - halfDiag, cx, cy + halfDiag,
            intArrayOf(Color.WHITE, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - halfDiag, cy - halfDiag, cx + halfDiag, cy + halfDiag, fillPaint)
        fillPaint.shader = null
        canvas.restore()
    }

    private fun drawFullCopicSwatchGridTab(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, sx: Float, sy: Float) {
        val cols = 6
        val rows = 10
        val pad = 24f * sx
        val cellW = (right - left - pad * 2f) / cols
        val cellH = (bottom - top - pad * 2f) / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val h = ((c * 60f + r * 12f) % 360f)
                val s = 0.75f
                val l = (0.18f + (r / 10f) * 0.72f).coerceIn(0.1f, 0.92f)
                val col = ColorStudioState.hslToColor(h, s, l)
                val rect = RectF(
                    left + pad + c * cellW + 4f * sx,
                    top + pad + r * cellH + 4f * sy,
                    left + pad + (c + 1) * cellW - 4f * sx,
                    top + pad + (r + 1) * cellH - 4f * sy
                )
                fillPaint.color = col
                canvas.drawRect(rect, fillPaint)
                hits.add(ModalHit("PICK_HIST_COLOR", rect, col))
            }
        }
    }

    // ==========================================================================
    // BOTTOM 3 FLOATING CIRCULAR BUTTONS (Color Wheel | X Close | Brush Studio)
    // ==========================================================================
    private fun drawBottomThreeCircles(canvas: Canvas, viewW: Float, viewH: Float, sx: Float, sy: Float, isColorMode: Boolean) {
        val cy = viewH - 128f * sy
        val r = 61f * sx

        // 1. Left Circle: Color Wheel Button (cx = 291 * sx)
        val leftCx = 291f * sx
        fillPaint.color = if (isColorMode) Color.rgb(69, 149, 230) else Color.rgb(33, 33, 33)
        canvas.drawCircle(leftCx, cy, r, fillPaint)
        SketchIcons.drawTopIcon6ColorWheel(canvas, leftCx, cy, r * 2.1f)
        hits.add(ModalHit("BOTTOM_CIRCLE_COLOR", RectF(leftCx - r, cy - r, leftCx + r, cy + r)))

        // 2. Center Circle: Close 'X' (or '^' when dropdown is open) (cx = 539 * sx)
        val midCx = 539f * sx
        fillPaint.color = Color.rgb(33, 33, 33)
        canvas.drawCircle(midCx, cy, r, fillPaint)
        strokePaint.color = Color.rgb(69, 149, 230)
        strokePaint.strokeWidth = 5f * sx
        if (subOverlay != BrushSubOverlay.NONE) {
            canvas.drawLine(midCx - 20f * sx, cy + 8f * sy, midCx, cy - 12f * sy, strokePaint)
            canvas.drawLine(midCx + 20f * sx, cy + 8f * sy, midCx, cy - 12f * sy, strokePaint)
        } else {
            val d = 19f * sx
            canvas.drawLine(midCx - d, cy - d, midCx + d, cy + d, strokePaint)
            canvas.drawLine(midCx + d, cy - d, midCx - d, cy + d, strokePaint)
        }
        hits.add(ModalHit("BOTTOM_CIRCLE_CLOSE", RectF(midCx - r, cy - r, midCx + r, cy + r)))

        // 3. Right Circle: Brush Menu Button (cx = 787 * sx)
        val rightCx = 787f * sx
        fillPaint.color = if (!isColorMode) Color.rgb(69, 149, 230) else Color.rgb(33, 33, 33)
        canvas.drawCircle(rightCx, cy, r, fillPaint)
        drawBottomBrushSliderCircleIcon(canvas, rightCx, cy, r * 0.55f, isColorMode)
        hits.add(ModalHit("BOTTOM_CIRCLE_BRUSH", RectF(rightCx - r, cy - r, rightCx + r, cy + r)))
    }

    // ==========================================================================
    // TOUCH HANDLING FOR BRUSH & COLOR MODALS
    // ==========================================================================
    fun onTouchEvent(event: android.view.MotionEvent, viewW: Float, viewH: Float, isColorMode: Boolean): Boolean {
        val x = event.x
        val y = event.y
        val sx = viewW / 1080f

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastY = y
                draggingSliderId = null
                isDraggingHueRing = false
                isDraggingSlDiamond = false
                isScrollingLibrary = false
                isScrollingAdvanced = false

                // Check sliders first
                for ((id, rect) in sliderRects) {
                    if (rect.contains(x, y)) {
                        draggingSliderId = id
                        updateSliderValue(id, x)
                        hostView.invalidate()
                        return true
                    }
                }

                // Check Color Wheel & Diamond if in Color Mode
                if (isColorMode && colorState.topTabIndex == 0) {
                    val dist = hypot(x - wheelCx, y - wheelCy)
                    if (dist in (wheelInnerR - 18f * sx)..(wheelOuterR + 24f * sx)) {
                        isDraggingHueRing = true
                        updateHueFromTouch(x, y)
                        hostView.invalidate()
                        return true
                    }
                    val manhattan = abs(x - wheelCx) + abs(y - wheelCy)
                    if (manhattan <= diamondHalfDiag + 16f * sx) {
                        isDraggingSlDiamond = true
                        updateSlFromDiamondTouch(x, y)
                        hostView.invalidate()
                        return true
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = y - lastY
                lastY = y

                if (draggingSliderId != null) {
                    updateSliderValue(draggingSliderId!!, x)
                    hostView.invalidate()
                    return true
                }
                if (isDraggingHueRing) {
                    updateHueFromTouch(x, y)
                    hostView.invalidate()
                    return true
                }
                if (isDraggingSlDiamond) {
                    updateSlFromDiamondTouch(x, y)
                    hostView.invalidate()
                    return true
                }

                if (abs(y - downY) > 15f * sx) {
                    if (!isColorMode && subOverlay == BrushSubOverlay.NONE) {
                        if (brushTopTab == BrushModalTopTab.LIBRARY_GRID && libraryScrollBounds.contains(downX, downY)) {
                            isScrollingLibrary = true
                            libraryScrollY -= dy
                            hostView.invalidate()
                            return true
                        }
                        if (brushTopTab == BrushModalTopTab.SETTINGS_SLIDERS &&
                            settingsSubTab == BrushSettingsSubTab.ADVANCED &&
                            advancedScrollBounds.contains(downX, downY)
                        ) {
                            isScrollingAdvanced = true
                            advancedScrollY = (advancedScrollY - dy).coerceIn(0f, 800f * sx)
                            hostView.invalidate()
                            return true
                        }
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                val wasDragging = (draggingSliderId != null || isDraggingHueRing || isDraggingSlDiamond || isScrollingLibrary || isScrollingAdvanced)
                draggingSliderId = null
                isDraggingHueRing = false
                isDraggingSlDiamond = false
                isScrollingLibrary = false
                isScrollingAdvanced = false

                if (!wasDragging && abs(x - downX) < 30f * sx && abs(y - downY) < 30f * sx) {
                    handleModalTap(x, y)
                }
                hostView.invalidate()
                return true
            }
        }
        return true
    }

    private fun handleModalTap(x: Float, y: Float) {
        var hit: ModalHit? = null
        for (i in hits.size - 1 downTo 0) {
            if (hits[i].rect.contains(x, y)) {
                hit = hits[i]
                break
            }
        }
        if (hit == null) return

        val brush = selectedBrush()
        val set = selectedSet()

        when (hit.id) {
            "BOTTOM_CIRCLE_COLOR" -> {
                subOverlay = BrushSubOverlay.NONE
                hostView.popupState = TopPopupState.COLOR_MENU
            }
            "BOTTOM_CIRCLE_BRUSH" -> {
                subOverlay = BrushSubOverlay.NONE
                hostView.popupState = TopPopupState.BRUSH_MENU
            }
            "BOTTOM_CIRCLE_CLOSE" -> {
                if (subOverlay != BrushSubOverlay.NONE) {
                    subOverlay = BrushSubOverlay.NONE
                } else {
                    hostView.popupState = TopPopupState.NONE
                }
            }
            "TAB_BRUSH_LIBRARY" -> {
                brushTopTab = BrushModalTopTab.LIBRARY_GRID
                subOverlay = BrushSubOverlay.NONE
            }
            "TAB_BRUSH_SETTINGS" -> {
                brushTopTab = BrushModalTopTab.SETTINGS_SLIDERS
                subOverlay = BrushSubOverlay.NONE
            }
            "SELECT_BRUSH_CELL" -> {
                selectedSetIndex = hit.arg1.coerceIn(0, brushSets.lastIndex)
                selectedBrushIndex = hit.arg2.coerceIn(0, brushSets[selectedSetIndex].brushes.lastIndex)
            }
            "SET_TOGGLE_PIN" -> {
                if (hit.arg1 in brushSets.indices) {
                    brushSets[hit.arg1].isPinned = !brushSets[hit.arg1].isPinned
                }
            }
            "BTN_OPEN_SET_SETTINGS" -> {
                subOverlay = if (subOverlay == BrushSubOverlay.SET_SETTINGS) BrushSubOverlay.NONE else BrushSubOverlay.SET_SETTINGS
            }
            "BTN_PINNED_BRUSH_ACTIONS" -> {
                subOverlay = if (subOverlay == BrushSubOverlay.BRUSH_ACTIONS) BrushSubOverlay.NONE else BrushSubOverlay.BRUSH_ACTIONS
            }
            // Set Settings 4 Actions: Rename Set, Create New Set, Duplicate Brush, Delete Set ("confirm")
            "SET_ACT_RENAME" -> {
                hostView.showSimpleInputModal("Rename Set", set.name) { newName ->
                    if (newName.isNotBlank()) set.name = newName.trim()
                    subOverlay = BrushSubOverlay.NONE
                    hostView.invalidate()
                }
            }
            "SET_ACT_CREATE_NEW" -> {
                val newSet = SketchBrushSet(
                    id = "set_${System.currentTimeMillis()}",
                    name = "New Set ${brushSets.size + 1}",
                    brushes = mutableListOf(
                        brush.copy(id = "b_${System.currentTimeMillis()}", name = brush.name)
                    )
                )
                val insertPos = (selectedSetIndex + 1).coerceIn(0, brushSets.size)
                brushSets.add(insertPos, newSet)
                selectedSetIndex = insertPos
                selectedBrushIndex = 0
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Created '${newSet.name}' below current set")
            }
            "SET_ACT_DUPLICATE_BRUSH" -> {
                val dup = brush.copy(
                    id = "b_${System.currentTimeMillis()}",
                    name = "Duplicate"
                )
                val insertIdx = (selectedBrushIndex + 1).coerceIn(0, set.brushes.size)
                set.brushes.add(insertIdx, dup)
                selectedBrushIndex = insertIdx
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Duplicated brush in '${set.name}'")
            }
            "SET_ACT_DELETE_SET" -> {
                if (brushSets.size <= 1) {
                    hostView.showBanner("Cannot delete the last remaining brush set")
                } else {
                    hostView.showConfirmDeleteSetModal(set.name) {
                        brushSets.removeAt(selectedSetIndex)
                        selectedSetIndex = selectedSetIndex.coerceIn(0, brushSets.lastIndex)
                        selectedBrushIndex = 0
                        subOverlay = BrushSubOverlay.NONE
                        hostView.showBanner("Deleted set")
                        hostView.invalidate()
                    }
                }
            }
            // Brush Actions 5 Options: Rename, Duplicate, Change Icon, Export Icon, Delete
            "BRUSH_ACT_RENAME" -> {
                hostView.showSimpleInputModal("Rename Brush", brush.name) { newName ->
                    if (newName.isNotBlank()) brush.name = newName.trim()
                    subOverlay = BrushSubOverlay.NONE
                    hostView.invalidate()
                }
            }
            "BRUSH_ACT_DUPLICATE" -> {
                val dup = brush.copy(id = "b_${System.currentTimeMillis()}", name = "Duplicate")
                val insertIdx = (selectedBrushIndex + 1).coerceIn(0, set.brushes.size)
                set.brushes.add(insertIdx, dup)
                selectedBrushIndex = insertIdx
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Brush Duplicated")
            }
            "BRUSH_ACT_CHANGE_ICON" -> {
                val styles = BrushIconStyle.values()
                brush.iconStyle = styles[(brush.iconStyle.ordinal + 1) % styles.size]
                subOverlay = BrushSubOverlay.NONE
                hostView.showBanner("Brush Icon Updated")
            }
            "BRUSH_ACT_EXPORT_ICON" -> {
                exportBrushIconToStorage(brush)
                subOverlay = BrushSubOverlay.NONE
            }
            "BRUSH_ACT_DELETE" -> {
                if (set.brushes.size <= 1) {
                    hostView.showBanner("Cannot delete the only brush in this set")
                } else {
                    set.brushes.removeAt(selectedBrushIndex)
                    selectedBrushIndex = selectedBrushIndex.coerceIn(0, set.brushes.lastIndex)
                    subOverlay = BrushSubOverlay.NONE
                    hostView.showBanner("Brush Deleted")
                }
            }
            // Brush Settings & Types
            "TOGGLE_BRUSH_TYPES_DROPDOWN" -> {
                subOverlay = if (subOverlay == BrushSubOverlay.BRUSH_TYPES_DROPDOWN) BrushSubOverlay.NONE else BrushSubOverlay.BRUSH_TYPES_DROPDOWN
            }
            "SELECT_BRUSH_TYPE" -> {
                val types = SketchBrushType.values()
                if (hit.arg1 in types.indices) {
                    brush.brushType = types[hit.arg1]
                }
                subOverlay = BrushSubOverlay.NONE
            }
            "SUBTAB_BASIC" -> {
                settingsSubTab = BrushSettingsSubTab.BASIC
            }
            "SUBTAB_ADVANCED" -> {
                settingsSubTab = BrushSettingsSubTab.ADVANCED
            }
            "TOGGLE_ADV_SECTION" -> {
                expandedAdvancedSection = if (expandedAdvancedSection == hit.arg1) -1 else hit.arg1
            }
            "BTN_RESET_BRUSH" -> {
                brush.resetToDefault()
                hostView.showBanner("Brush Reset to Default")
            }
            // Color Menu Actions
            "COLOR_TOP_TAB" -> {
                if (hit.arg1 == 2) {
                    hostView.popupState = TopPopupState.NONE
                    hostView.activateOnCanvasEyedropper()
                } else {
                    colorState.topTabIndex = hit.arg1
                }
            }
            "REVERT_PREV_COLOR" -> {
                colorState.setFromColorInt(colorState.previousColor, recordPrevious = true)
            }
            "PICK_COMP_COLOR", "PICK_HIST_COLOR" -> {
                colorState.setFromColorInt(hit.arg1, recordPrevious = true)
            }
            "CYCLE_HARMONY_MODE" -> {
                colorState.harmonyMode = (colorState.harmonyMode + 1) % 4
            }
            "SHUFFLE_HISTORY_BAR" -> {
                colorState.pushToHistory(ColorStudioState.hslToColor(Random.nextFloat() * 360f, 0.8f, 0.55f))
            }
            "TOGGLE_COLORLESS_ERASER" -> {
                colorState.isColorlessEraser = !colorState.isColorlessEraser
                hostView.showBanner(if (colorState.isColorlessEraser) "Transparent Colorless Mode ON" else "Color Mode Restored")
            }
            "ACTIVATE_EYEDROPPER" -> {
                hostView.popupState = TopPopupState.NONE
                hostView.activateOnCanvasEyedropper()
            }
            "EDIT_HEX_COLOR" -> {
                val curHex = String.format("%06X", 0xFFFFFF and colorState.currentColor)
                hostView.showSimpleInputModal("HEX Color (#RRGGBB)", curHex) { input ->
                    val cleaned = input.trim().removePrefix("#")
                    if (cleaned.length == 6) {
                        try {
                            val parsed = Color.parseColor("#$cleaned")
                            colorState.setFromColorInt(parsed, recordPrevious = true)
                        } catch (_: Exception) {}
                    }
                    hostView.invalidate()
                }
            }
            "COLOR_BOTTOM_MODE" -> {
                when (hit.arg1) {
                    0, 1 -> colorState.bottomModeIndex = hit.arg1
                    2 -> colorState.topTabIndex = if (colorState.topTabIndex == 1) 0 else 1
                    3 -> {
                        val randCol = ColorStudioState.hslToColor(Random.nextFloat() * 360f, Random.nextFloat().coerceIn(0.4f, 1f), Random.nextFloat().coerceIn(0.25f, 0.8f))
                        colorState.setFromColorInt(randCol, recordPrevious = true)
                    }
                    4 -> {
                        colorState.harmonyMode = (colorState.harmonyMode + 1) % 4
                    }
                }
            }
        }
    }

    private fun updateSliderValue(id: String, touchX: Float) {
        val rect = sliderRects[id] ?: return
        val f = ((touchX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val b = selectedBrush()

        when (id) {
            "SLIDER_BASIC_SIZE" -> b.size = 1f + f * 299f
            "SLIDER_BASIC_OPACITY" -> b.opacity = 1f + f * 99f
            "ADV_PRESS_SIZE_HEAVY" -> b.sizeHeavyPressure = f * 100f
            "ADV_PRESS_SIZE_LIGHT" -> b.sizeLightPressure = f * 100f
            "ADV_PRESS_OPAC_LIGHT" -> b.opacityLightPressure = f * 100f
            "ADV_STAMP_SPACING" -> b.spacing = 2f + f * 98f
            "ADV_STAMP_ROUND" -> b.roundness = 10f + f * 90f
            "ADV_STAMP_ROT" -> b.rotation = -180f + f * 360f
            "ADV_NIB_HARDNESS" -> b.hardness = f * 100f
            "ADV_NIB_GRAIN" -> b.grainStrength = f * 100f
            "ADV_RAND_SIZE" -> b.sizeJitter = f * 100f
            "ADV_RAND_OPAC" -> b.opacityJitter = f * 100f
            "ADV_RAND_SCATTER" -> b.scatterJitter = f * 100f
            "COLOR_H" -> {
                colorState.hue = f * 360f
                colorState.syncColorFromHsl()
            }
            "COLOR_S" -> {
                colorState.saturation = f
                colorState.syncColorFromHsl()
            }
            "COLOR_L" -> {
                colorState.lightness = f
                colorState.syncColorFromHsl()
            }
            "COLOR_R" -> {
                val nr = (f * 255f).roundToInt().coerceIn(0, 255)
                colorState.setFromColorInt(Color.rgb(nr, Color.green(colorState.currentColor), Color.blue(colorState.currentColor)))
            }
            "COLOR_G" -> {
                val ng = (f * 255f).roundToInt().coerceIn(0, 255)
                colorState.setFromColorInt(Color.rgb(Color.red(colorState.currentColor), ng, Color.blue(colorState.currentColor)))
            }
            "COLOR_B" -> {
                val nb = (f * 255f).roundToInt().coerceIn(0, 255)
                colorState.setFromColorInt(Color.rgb(Color.red(colorState.currentColor), Color.green(colorState.currentColor), nb))
            }
        }
    }

    private fun updateHueFromTouch(x: Float, y: Float) {
        var deg = Math.toDegrees(atan2((y - wheelCy).toDouble(), (x - wheelCx).toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        // Map wheel angle (clockwise from right) to HSL Hue (0..360)
        colorState.hue = (360f - deg) % 360f
        colorState.syncColorFromHsl()
    }

    private fun hueToWheelAngleRad(hue: Float): Double {
        val deg = (360f - hue) % 360f
        return Math.toRadians(deg.toDouble())
    }

    private fun updateSlFromDiamondTouch(x: Float, y: Float) {
        val dy = ((y - wheelCy) / diamondHalfDiag).coerceIn(-1f, 1f)
        val l = ((1f - dy) * 0.5f).coerceIn(0f, 1f)
        val maxSpanX = (1f - abs(dy)).coerceAtLeast(0.02f) * diamondHalfDiag
        val dxNorm = ((x - wheelCx) / maxSpanX).coerceIn(-1f, 1f)
        val s = ((dxNorm + 1f) * 0.5f).coerceIn(0f, 1f)
        colorState.lightness = l
        colorState.saturation = s
        colorState.syncColorFromHsl()
    }

    private fun slToDiamondPoint(s: Float, l: Float, cx: Float, cy: Float, halfDiag: Float): PointF {
        val dy = (1f - 2f * l).coerceIn(-1f, 1f)
        val py = cy + dy * halfDiag
        val maxSpanX = (1f - abs(dy)) * halfDiag
        val px = cx + (2f * s - 1f) * maxSpanX
        return PointF(px, py)
    }

    private fun exportBrushIconToStorage(brush: SketchBrushItem) {
        try {
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            drawRealisticBrushIcon(c, RectF(28f, 28f, 228f, 228f), brush.iconStyle, 1.5f)
            val tmpProj = SketchProjectState(name = "${brush.name}_icon", width = 256, height = 256)
            tmpProj.canvasBitmap = bmp
            val res = ProjectStorageManager.saveProjectToDevice(hostView.context, tmpProj, asCopy = false, paperColor = Color.WHITE)
            res.onSuccess { p -> hostView.showBanner("Exported Icon -> $p") }
                .onFailure { hostView.showBanner("Export failed") }
        } catch (_: Exception) {}
    }

    // ==========================================================================
    // PRECISION VECTOR TOOL & UI ICONS FOR BRUSH AND COLOR MODALS
    // ==========================================================================
    private fun drawLibraryTabIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        fillPaint.color = Color.rgb(225, 225, 225)
        strokePaint.color = Color.rgb(225, 225, 225)
        strokePaint.strokeWidth = size * 0.06f
        val w = size * 0.88f
        val topH = size * 0.24f
        c.drawRect(cx - w * 0.5f, cy - size * 0.34f, cx + w * 0.5f, cy - size * 0.34f + topH, fillPaint)
        val sq = size * 0.22f
        val gap = (w - sq * 3f) * 0.5f
        for (i in 0..2) {
            val sx = cx - w * 0.5f + i * (sq + gap)
            c.drawRect(sx, cy + size * 0.04f, sx + sq, cy + size * 0.04f + sq * 0.85f, strokePaint)
        }
    }

    private fun drawSlidersTabIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(232, 232, 232)
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = Color.rgb(232, 232, 232)
        val w = size * 0.86f
        val y1 = cy - size * 0.16f
        val y2 = cy + size * 0.16f
        c.drawLine(cx - w * 0.5f, y1, cx + w * 0.5f, y1, strokePaint)
        c.drawLine(cx - w * 0.5f, y2, cx + w * 0.5f, y2, strokePaint)
        c.drawCircle(cx - w * 0.14f, y1, size * 0.13f, fillPaint)
        c.drawCircle(cx + w * 0.18f, y2, size * 0.13f, fillPaint)
    }

    private fun drawPinCircleIcon(c: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        fillPaint.color = if (active) Color.rgb(20, 120, 216) else Color.rgb(104, 104, 104)
        c.drawCircle(cx, cy, r, fillPaint)
        fillPaint.color = Color.WHITE
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = r * 0.14f
        c.drawRect(cx - r * 0.34f, cy - r * 0.48f, cx + r * 0.34f, cy - r * 0.32f, fillPaint)
        c.drawRect(cx - r * 0.20f, cy - r * 0.32f, cx + r * 0.20f, cy + r * 0.08f, fillPaint)
        c.drawLine(cx - r * 0.38f, cy + r * 0.08f, cx + r * 0.38f, cy + r * 0.08f, strokePaint)
        c.drawLine(cx, cy + r * 0.08f, cx, cy + r * 0.55f, strokePaint)
    }

    private fun drawRenameBoxIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val w = size * 1.15f
        val h = size * 0.85f
        fillPaint.color = Color.WHITE
        c.drawRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, fillPaint)
        strokePaint.color = Color.rgb(95, 95, 95)
        strokePaint.strokeWidth = size * 0.05f
        c.drawRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, strokePaint)

        textPaint.color = Color.rgb(75, 75, 75)
        textPaint.textSize = size * 0.48f
        textPaint.isFakeBoldText = true
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText("N", cx - w * 0.12f, cy + size * 0.17f, textPaint)
        textPaint.isFakeBoldText = false
        c.drawLine(cx + w * 0.18f, cy - h * 0.32f, cx + w * 0.18f, cy + h * 0.32f, strokePaint)
    }

    private fun drawCreateSetIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.09f
        val w = size * 1.05f
        val h = size * 0.46f
        c.drawRoundRect(RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f), size * 0.08f, size * 0.08f, strokePaint)
        val p = size * 0.14f
        c.drawLine(cx - p, cy, cx + p, cy, strokePaint)
        c.drawLine(cx, cy - p, cx, cy + p, strokePaint)
    }

    private fun drawDuplicateNibIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        fillPaint.color = Color.rgb(88, 88, 88)
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.09f
        val p = Path().apply {
            moveTo(cx, cy - size * 0.48f)
            lineTo(cx - size * 0.28f, cy + size * 0.05f)
            lineTo(cx + size * 0.28f, cy + size * 0.05f)
            close()
        }
        c.drawPath(p, fillPaint)
        val py = cy + size * 0.32f
        val r = size * 0.16f
        c.drawLine(cx - r, py, cx + r, py, strokePaint)
        c.drawLine(cx, py - r, cx, py + r, strokePaint)
    }

    private fun drawChangeIconBox(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = Color.rgb(88, 88, 88)
        val s = size * 0.45f
        c.drawRect(cx - s, cy - s, cx + s, cy + s, strokePaint)
        val p = Path().apply {
            moveTo(cx, cy - s * 0.55f)
            lineTo(cx - s * 0.48f, cy + s * 0.45f)
            lineTo(cx + s * 0.48f, cy + s * 0.45f)
            close()
        }
        c.drawPath(p, fillPaint)
    }

    private fun drawExportIconBox(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.color = Color.rgb(88, 88, 88)
        strokePaint.strokeWidth = size * 0.07f
        fillPaint.color = Color.rgb(88, 88, 88)
        val s = size * 0.38f
        c.drawRect(cx - s, cy - s * 0.5f, cx + s, cy + s * 1.1f, strokePaint)
        c.drawLine(cx, cy, cx, cy - s * 1.25f, strokePaint)
        c.drawLine(cx - s * 0.45f, cy - s * 0.8f, cx, cy - s * 1.25f, strokePaint)
        c.drawLine(cx + s * 0.45f, cy - s * 0.8f, cx, cy - s * 1.25f, strokePaint)
    }

    private fun drawSelectorRing(c: Canvas, cx: Float, cy: Float, r: Float, innerColor: Int) {
        fillPaint.color = innerColor
        c.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = r * 0.24f
        c.drawCircle(cx, cy, r + r * 0.1f, strokePaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = r * 0.16f
        c.drawCircle(cx, cy, r, strokePaint)
    }

    private fun drawHarmonyRingsIcon(c: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.color = Color.rgb(225, 225, 225)
        strokePaint.strokeWidth = r * 0.20f
        c.drawCircle(cx, cy - r * 0.38f, r * 0.55f, strokePaint)
        c.drawCircle(cx - r * 0.38f, cy + r * 0.28f, r * 0.55f, strokePaint)
        c.drawCircle(cx + r * 0.38f, cy + r * 0.28f, r * 0.55f, strokePaint)
    }

    private fun drawBarcodeIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        strokePaint.color = color
        val w = size * 0.9f
        val h = size * 0.7f
        for (i in -3..3) {
            strokePaint.strokeWidth = if (i % 2 == 0) size * 0.13f else size * 0.07f
            val x = cx + i * (w / 6.5f)
            c.drawLine(x, cy - h * 0.5f, x, cy + h * 0.5f, strokePaint)
        }
    }

    private fun drawSmall3x2GridIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        fillPaint.color = color
        val cell = size * 0.32f
        val gap = size * 0.12f
        for (r in 0..1) {
            for (col in -1..1) {
                val x = cx + col * (cell + gap) - cell * 0.5f
                val y = cy + (r - 0.5f) * (cell + gap) - cell * 0.3f
                c.drawRect(x, y, x + cell, y + cell * 0.75f, fillPaint)
            }
        }
    }

    private fun drawShuffleArrowsIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.14f
        val w = size * 0.75f
        val h = size * 0.38f
        c.drawLine(cx - w, cy - h, cx + w, cy + h, strokePaint)
        c.drawLine(cx - w, cy + h, cx + w, cy - h, strokePaint)
    }

    private fun drawTransparentEraserButton(c: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        fillPaint.color = Color.rgb(92, 92, 92)
        c.drawCircle(cx, cy, r, fillPaint)
        // Checkerboard pattern + white eraser block matching 115958.jpg
        fillPaint.color = Color.rgb(175, 175, 175)
        val sq = r * 0.28f
        for (ix in -2..1) {
            for (iy in -2..1) {
                if ((ix + iy) % 2 == 0) {
                    val rx = cx + ix * sq
                    val ry = cy + iy * sq
                    if (hypot(rx - cx, ry - cy) < r * 0.78f) {
                        c.drawRect(rx, ry, rx + sq, ry + sq, fillPaint)
                    }
                }
            }
        }
        fillPaint.color = Color.WHITE
        c.drawRoundRect(RectF(cx - r * 0.38f, cy - r * 0.18f, cx + r * 0.38f, cy + r * 0.65f), 6f, 6f, fillPaint)
        if (active) {
            strokePaint.color = Color.rgb(65, 148, 238)
            strokePaint.strokeWidth = 5f
            c.drawCircle(cx, cy, r + 4f, strokePaint)
        }
    }

    private fun drawEyedropperIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        c.save()
        c.translate(cx, cy)
        c.rotate(45f)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = size * 0.11f
        fillPaint.color = Color.WHITE
        c.drawRect(-size * 0.14f, -size * 0.65f, size * 0.14f, -size * 0.22f, fillPaint)
        c.drawLine(-size * 0.28f, -size * 0.22f, size * 0.28f, -size * 0.22f, strokePaint)
        c.drawRect(-size * 0.11f, -size * 0.22f, size * 0.11f, size * 0.45f, strokePaint)
        c.drawLine(0f, size * 0.45f, 0f, size * 0.68f, strokePaint)
        c.restore()
    }

    private fun drawBottomBrushSliderCircleIcon(c: Canvas, cx: Float, cy: Float, size: Float, isColorMode: Boolean) {
        val col = if (isColorMode) Color.rgb(69, 149, 230) else Color.WHITE
        fillPaint.color = col
        strokePaint.color = col
        strokePaint.strokeWidth = size * 0.12f
        val p = Path().apply {
            moveTo(cx, cy - size * 0.72f)
            lineTo(cx - size * 0.38f, cy + size * 0.18f)
            lineTo(cx + size * 0.38f, cy + size * 0.18f)
            close()
        }
        c.drawPath(p, fillPaint)
        c.drawLine(cx - size * 0.75f, cy + size * 0.48f, cx + size * 0.75f, cy + size * 0.48f, strokePaint)
        c.drawCircle(cx, cy + size * 0.48f, size * 0.16f, fillPaint)
    }

    private fun drawCornerScrollArrows(c: Canvas, cx: Float, cy: Float, size: Float) {
        fillPaint.color = Color.rgb(115, 115, 115)
        val p1 = Path().apply {
            moveTo(cx, cy - size)
            lineTo(cx - size * 0.65f, cy - size * 0.2f)
            lineTo(cx + size * 0.65f, cy - size * 0.2f)
            close()
        }
        val p2 = Path().apply {
            moveTo(cx, cy + size)
            lineTo(cx - size * 0.65f, cy + size * 0.2f)
            lineTo(cx + size * 0.65f, cy + size * 0.2f)
            close()
        }
        c.drawPath(p1, fillPaint)
        c.drawPath(p2, fillPaint)
    }

    /**
     * Draws realistic multi-layered SketchBook brush & texture thumbnails matching 115925.jpg & 115929.jpg.
     */
    fun drawRealisticBrushIcon(c: Canvas, box: RectF, style: BrushIconStyle, sx: Float) {
        val cx = box.centerX()
        val cy = box.centerY()
        val top = box.top
        val bot = box.bottom
        val w = box.width()
        val h = box.height()

        when (style) {
            BrushIconStyle.WOOD_PENCIL -> {
                // Wood cone
                val cone = Path().apply {
                    moveTo(cx, top + h * 0.06f)
                    lineTo(cx - w * 0.28f, top + h * 0.58f)
                    lineTo(cx + w * 0.28f, top + h * 0.58f)
                    close()
                }
                fillPaint.color = Color.rgb(218, 188, 158)
                c.drawPath(cone, fillPaint)
                // Graphite lead tip
                val tip = Path().apply {
                    moveTo(cx, top + h * 0.06f)
                    lineTo(cx - w * 0.10f, top + h * 0.25f)
                    lineTo(cx + w * 0.10f, top + h * 0.25f)
                    close()
                }
                fillPaint.color = Color.rgb(42, 42, 42)
                c.drawPath(tip, fillPaint)
                // Dark body + gold ferrule band
                fillPaint.color = Color.rgb(38, 38, 38)
                c.drawRect(cx - w * 0.28f, top + h * 0.58f, cx + w * 0.28f, bot - h * 0.06f, fillPaint)
                fillPaint.color = Color.rgb(205, 180, 115)
                c.drawRect(cx - w * 0.28f, bot - h * 0.22f, cx + w * 0.28f, bot - h * 0.14f, fillPaint)
            }

            BrushIconStyle.HARD_ERASER, BrushIconStyle.SOFT_ERASER -> {
                fillPaint.color = Color.rgb(235, 235, 235)
                val isRound = (style == BrushIconStyle.SOFT_ERASER)
                if (isRound) {
                    c.drawRoundRect(RectF(cx - w * 0.36f, top + h * 0.25f, cx + w * 0.36f, top + h * 0.65f), 14f * sx, 14f * sx, fillPaint)
                } else {
                    c.drawRect(cx - w * 0.36f, top + h * 0.25f, cx + w * 0.36f, top + h * 0.55f, fillPaint)
                }
                fillPaint.color = Color.rgb(125, 130, 135)
                c.drawRect(cx - w * 0.40f, top + h * 0.52f, cx + w * 0.40f, bot - h * 0.08f, fillPaint)
            }

            BrushIconStyle.CHISEL_MARKER -> {
                val nib = Path().apply {
                    moveTo(cx - w * 0.14f, top + h * 0.12f)
                    lineTo(cx + w * 0.16f, top + h * 0.24f)
                    lineTo(cx + w * 0.16f, top + h * 0.48f)
                    lineTo(cx - w * 0.14f, top + h * 0.48f)
                    close()
                }
                fillPaint.color = Color.rgb(210, 210, 210)
                c.drawPath(nib, fillPaint)
                fillPaint.color = Color.rgb(55, 55, 55)
                c.drawRect(cx - w * 0.32f, top + h * 0.48f, cx + w * 0.32f, bot - h * 0.08f, fillPaint)
                fillPaint.color = Color.rgb(205, 180, 115)
                c.drawRect(cx - w * 0.26f, top + h * 0.48f, cx + w * 0.26f, top + h * 0.62f, fillPaint)
            }

            BrushIconStyle.AIRBRUSH -> {
                fillPaint.color = Color.rgb(75, 75, 75)
                c.drawRect(cx - w * 0.14f, top + h * 0.12f, cx + w * 0.14f, top + h * 0.34f, fillPaint)
                fillPaint.color = Color.rgb(212, 190, 135)
                val cone = Path().apply {
                    moveTo(cx - w * 0.18f, top + h * 0.34f)
                    lineTo(cx + w * 0.18f, top + h * 0.34f)
                    lineTo(cx + w * 0.34f, top + h * 0.64f)
                    lineTo(cx - w * 0.34f, top + h * 0.64f)
                    close()
                }
                c.drawPath(cone, fillPaint)
                fillPaint.color = Color.rgb(65, 65, 65)
                c.drawRect(cx - w * 0.36f, top + h * 0.64f, cx + w * 0.36f, bot - h * 0.08f, fillPaint)
            }

            BrushIconStyle.TECHNICAL_PEN -> {
                fillPaint.color = Color.rgb(90, 90, 90)
                c.drawRect(cx - w * 0.04f, top + h * 0.06f, cx + w * 0.04f, top + h * 0.24f, fillPaint)
                fillPaint.color = Color.rgb(185, 185, 185)
                c.drawRect(cx - w * 0.11f, top + h * 0.22f, cx + w * 0.11f, top + h * 0.36f, fillPaint)
                val body = Path().apply {
                    moveTo(cx - w * 0.18f, top + h * 0.36f)
                    lineTo(cx + w * 0.18f, top + h * 0.36f)
                    lineTo(cx + w * 0.28f, bot - h * 0.16f)
                    lineTo(cx - w * 0.28f, bot - h * 0.16f)
                    close()
                }
                fillPaint.color = Color.rgb(142, 146, 150)
                c.drawPath(body, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.28f, bot - h * 0.18f, cx + w * 0.28f, bot - h * 0.10f, fillPaint)
            }

            BrushIconStyle.FOUNTAIN_PEN -> {
                val nib = Path().apply {
                    moveTo(cx, top + h * 0.08f)
                    lineTo(cx - w * 0.22f, top + h * 0.54f)
                    lineTo(cx - w * 0.14f, top + h * 0.72f)
                    lineTo(cx + w * 0.14f, top + h * 0.72f)
                    lineTo(cx + w * 0.22f, top + h * 0.54f)
                    close()
                }
                fillPaint.color = Color.rgb(218, 195, 138)
                c.drawPath(nib, fillPaint)
                strokePaint.color = Color.rgb(60, 60, 60)
                strokePaint.strokeWidth = 2f * sx
                c.drawLine(cx, top + h * 0.08f, cx, top + h * 0.45f, strokePaint)
                fillPaint.color = Color.rgb(65, 65, 65)
                c.drawRect(cx - w * 0.18f, top + h * 0.72f, cx + w * 0.18f, bot - h * 0.08f, fillPaint)
            }

            BrushIconStyle.ROUND_BRUSH, BrushIconStyle.FELT_PEN -> {
                val hair = Path().apply {
                    moveTo(cx, top + h * 0.08f)
                    quadTo(cx - w * 0.26f, top + h * 0.36f, cx - w * 0.16f, top + h * 0.62f)
                    lineTo(cx + w * 0.16f, top + h * 0.62f)
                    quadTo(cx + w * 0.26f, top + h * 0.36f, cx, top + h * 0.08f)
                    close()
                }
                fillPaint.color = Color.rgb(122, 78, 48)
                c.drawPath(hair, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.18f, top + h * 0.62f, cx + w * 0.18f, top + h * 0.74f, fillPaint)
                fillPaint.color = Color.rgb(45, 45, 45)
                c.drawRect(cx - w * 0.22f, top + h * 0.74f, cx + w * 0.22f, bot - h * 0.08f, fillPaint)
            }

            BrushIconStyle.BLENDER_CONE -> {
                val cone = Path().apply {
                    moveTo(cx, top + h * 0.10f)
                    lineTo(cx - w * 0.22f, top + h * 0.72f)
                    lineTo(cx + w * 0.22f, top + h * 0.72f)
                    close()
                }
                fillPaint.color = Color.rgb(210, 210, 210)
                c.drawPath(cone, fillPaint)
                fillPaint.color = Color.rgb(75, 75, 75)
                c.drawRect(cx - w * 0.22f, top + h * 0.72f, cx + w * 0.22f, bot - h * 0.08f, fillPaint)
            }

            BrushIconStyle.PASTEL_BLOCK, BrushIconStyle.CHARCOAL_STICK -> {
                fillPaint.color = Color.rgb(38, 38, 38)
                c.drawRoundRect(RectF(cx - w * 0.22f, top + h * 0.24f, cx + w * 0.22f, bot - h * 0.08f), 6f * sx, 6f * sx, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.22f, bot - h * 0.22f, cx + w * 0.22f, bot - h * 0.14f, fillPaint)
            }

            BrushIconStyle.FLAT_BRISTLE, BrushIconStyle.FAN_BRUSH -> {
                fillPaint.color = if (style == BrushIconStyle.FAN_BRUSH) Color.rgb(215, 215, 215) else Color.rgb(118, 86, 56)
                val topW = if (style == BrushIconStyle.FAN_BRUSH) w * 0.36f else w * 0.24f
                val head = Path().apply {
                    moveTo(cx - topW, top + h * 0.16f)
                    lineTo(cx + topW, top + h * 0.16f)
                    lineTo(cx + w * 0.22f, top + h * 0.66f)
                    lineTo(cx - w * 0.22f, top + h * 0.66f)
                    close()
                }
                c.drawPath(head, fillPaint)
                fillPaint.color = Color.rgb(205, 182, 120)
                c.drawRect(cx - w * 0.24f, top + h * 0.66f, cx + w * 0.24f, top + h * 0.76f, fillPaint)
                fillPaint.color = Color.rgb(55, 55, 55)
                c.drawRect(cx - w * 0.26f, top + h * 0.76f, cx + w * 0.26f, bot - h * 0.08f, fillPaint)
            }

            BrushIconStyle.DIAMOND_SHADER -> {
                val p = Path().apply {
                    moveTo(cx, top + h * 0.14f)
                    lineTo(cx + w * 0.24f, cy)
                    lineTo(cx, bot - h * 0.14f)
                    lineTo(cx - w * 0.24f, cy)
                    close()
                }
                fillPaint.color = Color.rgb(22, 22, 22)
                c.drawPath(p, fillPaint)
            }

            // Procedural Texture Thumbnails matching 115929.jpg
            BrushIconStyle.TEXTURE_FRACTAL -> {
                fillPaint.shader = LinearGradient(box.left, box.top, box.right, box.bottom, Color.rgb(18, 32, 78), Color.rgb(0, 185, 215), Shader.TileMode.CLAMP)
                c.drawRect(box, fillPaint)
                fillPaint.shader = null
                strokePaint.color = Color.argb(160, 180, 255, 255)
                strokePaint.strokeWidth = 2f * sx
                c.drawLine(box.left, box.top, box.right, box.bottom, strokePaint)
                c.drawLine(box.right, box.top, box.left, box.bottom, strokePaint)
                c.drawCircle(cx, box.centerY(), w * 0.28f, strokePaint)
            }

            BrushIconStyle.TEXTURE_HATCH -> {
                fillPaint.color = Color.rgb(62, 62, 62)
                c.drawRect(box, fillPaint)
                strokePaint.color = Color.rgb(185, 185, 185)
                strokePaint.strokeWidth = 1.8f * sx
                for (i in 0..5) {
                    val off = i * (w / 5f)
                    c.drawLine(box.left + off, box.top, box.left, box.top + off, strokePaint)
                    c.drawLine(box.right - off, box.top, box.right, box.top + off, strokePaint)
                }
            }

            BrushIconStyle.TEXTURE_FROST, BrushIconStyle.TEXTURE_SPLATTER -> {
                fillPaint.color = Color.rgb(22, 22, 22)
                c.drawRect(box, fillPaint)
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 2f * sx
                val r = w * 0.38f
                val by = box.centerY()
                for (a in 0 until 8) {
                    val rad = Math.toRadians((a * 45).toDouble())
                    c.drawLine(cx, by, cx + cos(rad).toFloat() * r, by + sin(rad).toFloat() * r, strokePaint)
                }
            }

            BrushIconStyle.TEXTURE_NOISE -> {
                fillPaint.color = Color.rgb(35, 35, 35)
                c.drawRect(box, fillPaint)
                fillPaint.color = Color.rgb(225, 225, 225)
                val step = w / 6f
                for (rx in 0..5) {
                    for (ry in 0..5) {
                        if ((rx * 3 + ry * 7) % 2 == 0) {
                            c.drawRect(box.left + rx * step, box.top + ry * step, box.left + rx * step + step * 0.6f, box.top + ry * step + step * 0.6f, fillPaint)
                        }
                    }
                }
            }

            BrushIconStyle.TEXTURE_GRID -> {
                fillPaint.color = Color.rgb(25, 25, 45)
                c.drawRect(box, fillPaint)
                val cols = intArrayOf(Color.CYAN, Color.MAGENTA, Color.GREEN, Color.YELLOW)
                val step = w / 4f
                for (gx in 0..3) {
                    for (gy in 0..3) {
                        fillPaint.color = cols[(gx + gy) % cols.size]
                        c.drawRect(box.left + gx * step + 2f, box.top + gy * step + 2f, box.left + (gx + 1) * step - 2f, box.top + (gy + 1) * step - 2f, fillPaint)
                    }
                }
            }
        }
    }
}
