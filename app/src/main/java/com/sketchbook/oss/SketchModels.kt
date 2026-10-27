package com.sketchbook.oss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AnimatedExportFormat(val label: String, val ext: String) {
    MP4_HIGH_BITRATE("MP4 (60fps High Bitrate)", "mp4"),
    GIF_LOOP("Animated GIF (Loop)", "gif"),
    WEBM_HIGH_BITRATE("WebM (60fps High Bitrate)", "webm")
}

enum class TopPopupState {
    NONE,
    BUTTON1_MAIN,
    NEW_SKETCH,
    EDIT_PRESET,
    SAVE_MENU,
    SHARE_EXPORT_MAIN,
    EXPORT_AS_MENU,
    SHARE_AS_MENU,
    MOD_MENU,
    BRUSH_MENU,
    COLOR_MENU
}

enum class NewSketchTab {
    PLUS_CUSTOM,
    LIST_PRESETS
}

enum class BrushModalTopTab {
    LIBRARY_GRID,
    SETTINGS_SLIDERS
}

enum class BrushSettingsSubTab {
    BASIC,
    ADVANCED
}

enum class BrushSubOverlay {
    NONE,
    SET_SETTINGS,
    BRUSH_ACTIONS,
    BRUSH_TYPES_DROPDOWN
}

enum class SketchBrushType(val title: String) {
    STANDARD("Standard"),
    MARKER("Marker"),
    SMUDGE("Smudge"),
    GLOW("Glow"),
    ERASER("Eraser")
}

enum class BrushIconStyle {
    WOOD_PENCIL,
    HARD_ERASER,
    SOFT_ERASER,
    CHISEL_MARKER,
    AIRBRUSH,
    TECHNICAL_PEN,
    DIAMOND_SHADER,
    FOUNTAIN_PEN,
    ROUND_BRUSH,
    FELT_PEN,
    BLENDER_CONE,
    PASTEL_BLOCK,
    CHARCOAL_STICK,
    FLAT_BRISTLE,
    FAN_BRUSH,
    TEXTURE_FRACTAL,
    TEXTURE_HATCH,
    TEXTURE_FROST,
    TEXTURE_SPLATTER,
    TEXTURE_NOISE,
    TEXTURE_GRID
}

data class TemporalDabNode(
    val x: Float,
    val y: Float,
    val nx: Float,
    val ny: Float,
    val radius: Float,
    val alpha: Int,
    val phase: Float,
    val tx: Float = 1f,
    val ty: Float = 0f,
    val tangentDeg: Float = 0f,
    val pressure: Float = 1f,
    val dabIndex: Int = 0
)

data class TemporalAnimatedStroke(
    val color: Int,
    val speed: Float,
    val waveAmp: Float,
    val sparkCount: Int,
    val hueShift: Float,
    val vmCode: String = "",
    val isErase: Boolean = false,
    val nodes: MutableList<TemporalDabNode> = mutableListOf()
) {
    fun deepCopy(): TemporalAnimatedStroke {
        return copy(nodes = nodes.toMutableList())
    }
}

data class SketchBrushItem(
    val id: String,
    var name: String,
    var iconStyle: BrushIconStyle,
    var brushType: SketchBrushType = SketchBrushType.STANDARD,
    var size: Float = 24f,
    var opacity: Float = 100f,
    var sizeHeavyPressure: Float = 100f,
    var sizeLightPressure: Float = 25f,
    var opacityHeavyPressure: Float = 100f,
    var opacityLightPressure: Float = 40f,
    var spacing: Float = 12f,
    var roundness: Float = 100f,
    var rotation: Float = 0f,
    var hardness: Float = 85f,
    var grainStrength: Float = 0f,
    var sizeJitter: Float = 0f,
    var opacityJitter: Float = 0f,
    var scatterJitter: Float = 0f,
    // Mod-specific custom behavior ("", "procedural_web", "time_animated", "custom_harmonic_nib")
    var modMode: String = "",
    var modParams: Map<String, String> = emptyMap(),
    val defType: SketchBrushType = brushType,
    val defSize: Float = size,
    val defOpacity: Float = opacity,
    val defHardness: Float = hardness,
    val defSpacing: Float = spacing,
    val defRoundness: Float = roundness,
    val defGrain: Float = grainStrength
) {
    fun resetToDefault() {
        brushType = defType
        size = defSize
        opacity = defOpacity
        sizeHeavyPressure = 100f
        sizeLightPressure = 25f
        opacityHeavyPressure = 100f
        opacityLightPressure = 40f
        spacing = defSpacing
        roundness = defRoundness
        rotation = 0f
        hardness = defHardness
        grainStrength = defGrain
        sizeJitter = 0f
        opacityJitter = 0f
        scatterJitter = 0f
    }
}

data class SketchBrushSet(
    val id: String,
    var name: String,
    var isPinned: Boolean = false,
    val brushes: MutableList<SketchBrushItem> = mutableListOf()
)

class ColorStudioState {
    var hue: Float = 0f
    var saturation: Float = 0.5f
    var lightness: Float = 0.0f
    var currentColor: Int = Color.BLACK
    var previousColor: Int = Color.BLACK
    var isColorlessEraser: Boolean = false
    var isEyedropperActive: Boolean = false
    var topTabIndex: Int = 0
    var bottomModeIndex: Int = 0
    var harmonyMode: Int = 0

    val colorHistory = mutableListOf(
        Color.rgb(18, 22, 238),
        Color.rgb(255, 255, 255),
        Color.rgb(0, 0, 0),
        Color.rgb(14, 150, 238),
        Color.rgb(0, 200, 255),
        Color.rgb(65, 18, 255),
        Color.rgb(235, 18, 18),
        Color.rgb(112, 112, 112),
        Color.rgb(72, 110, 0)
    )

    fun syncColorFromHsl() {
        currentColor = hslToColor(hue, saturation, lightness)
        isColorlessEraser = false
    }

    fun setFromColorInt(c: Int, recordPrevious: Boolean = false) {
        if (recordPrevious) previousColor = currentColor
        currentColor = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
        val hsl = colorToHsl(currentColor)
        hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
        isColorlessEraser = false
    }

    fun pushToHistory(c: Int) {
        val opaque = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
        colorHistory.remove(opaque)
        colorHistory.add(0, opaque)
        while (colorHistory.size > 9) {
            colorHistory.removeAt(colorHistory.lastIndex)
        }
    }

    fun computeHarmonyStrip(): List<Int> {
        val out = mutableListOf<Int>()
        when (harmonyMode % 4) {
            0 -> {
                for (i in 0..5) {
                    val h = if (i < 3) hue else (hue + 180f) % 360f
                    val l = (0.25f + (i % 3) * 0.25f).coerceIn(0.08f, 0.92f)
                    out.add(hslToColor(h, saturation.coerceAtLeast(0.35f), l))
                }
            }
            1 -> {
                for (i in 0..5) {
                    val h = (hue + (i / 2) * 120f) % 360f
                    val l = if (i % 2 == 0) 0.42f else 0.68f
                    out.add(hslToColor(h, saturation.coerceAtLeast(0.5f), l))
                }
            }
            2 -> {
                for (i in 0..5) {
                    val h = (hue - 50f + i * 20f + 360f) % 360f
                    out.add(hslToColor(h, saturation.coerceAtLeast(0.45f), lightness.coerceIn(0.25f, 0.75f)))
                }
            }
            else -> {
                val angles = floatArrayOf(0f, 15f, 150f, 180f, 210f, 345f)
                for (a in angles) {
                    out.add(hslToColor((hue + a) % 360f, saturation.coerceAtLeast(0.5f), 0.52f))
                }
            }
        }
        return out
    }

    companion object {
        fun hslToColor(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = l - c / 2f
            val r1: Float
            val g1: Float
            val b1: Float
            when {
                h < 60f -> { r1 = c; g1 = x; b1 = 0f }
                h < 120f -> { r1 = x; g1 = c; b1 = 0f }
                h < 180f -> { r1 = 0f; g1 = c; b1 = x }
                h < 240f -> { r1 = 0f; g1 = x; b1 = c }
                h < 300f -> { r1 = x; g1 = 0f; b1 = c }
                else -> { r1 = c; g1 = 0f; b1 = x }
            }
            val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        fun colorToHslOut(color: Int, out: FloatArray) {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val d = maxC - minC
            val l = (maxC + minC) * 0.5f
            var h = 0f
            var s = 0f
            if (d > 0.0001f) {
                s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(0.0001f)
                h = when (maxC) {
                    r -> 60f * (((g - b) / d) % 6f)
                    g -> 60f * (((b - r) / d) + 2f)
                    else -> 60f * (((r - g) / d) + 4f)
                }
                if (h < 0f) h += 360f
            }
            out[0] = h.coerceIn(0f, 360f)
            out[1] = s.coerceIn(0f, 1f)
            out[2] = l.coerceIn(0f, 1f)
        }

        fun colorToHsl(color: Int): FloatArray {
            val out = FloatArray(3)
            colorToHslOut(color, out)
            return out
        }
    }
}

object DefaultBrushCatalog {
    fun createDefaultSets(): MutableList<SketchBrushSet> {
        val basicBrushes = mutableListOf(
            SketchBrushItem("b1", "Primary Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 8f, 85f, hardness = 65f, grainStrength = 35f),
            SketchBrushItem("b2", "2B Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 12f, 90f, hardness = 60f, grainStrength = 40f),
            SketchBrushItem("b3", "4B Soft Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 18f, 92f, hardness = 55f, grainStrength = 45f),
            SketchBrushItem("b4", "6B Shading Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 26f, 80f, hardness = 45f, grainStrength = 50f),
            SketchBrushItem("b5", "HB Technical Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 5f, 95f, hardness = 85f, grainStrength = 20f),
            SketchBrushItem("b6", "Sketching Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 14f, 75f, hardness = 50f, grainStrength = 38f),
            SketchBrushItem("b7", "Drawing Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 16f, 88f, hardness = 62f, grainStrength = 30f),
            SketchBrushItem("b8", "Fine Art Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 10f, 85f, hardness = 70f, grainStrength = 28f),
            SketchBrushItem("b9", "Hard Eraser", BrushIconStyle.HARD_ERASER, SketchBrushType.ERASER, 36f, 100f, hardness = 98f),
            SketchBrushItem("b10", "Soft Eraser", BrushIconStyle.SOFT_ERASER, SketchBrushType.ERASER, 54f, 65f, hardness = 25f),
            SketchBrushItem("b11", "Chisel Marker", BrushIconStyle.CHISEL_MARKER, SketchBrushType.MARKER, 42f, 70f, roundness = 28f, rotation = -35f, hardness = 90f),
            SketchBrushItem("b12", "Fine Airbrush", BrushIconStyle.AIRBRUSH, SketchBrushType.STANDARD, 65f, 45f, hardness = 12f, spacing = 8f),
            SketchBrushItem("b13", "Soft Airbrush", BrushIconStyle.AIRBRUSH, SketchBrushType.STANDARD, 120f, 35f, hardness = 8f, spacing = 8f),
            SketchBrushItem("b14", "Technical Pen", BrushIconStyle.TECHNICAL_PEN, SketchBrushType.STANDARD, 7f, 100f, hardness = 98f, spacing = 5f),
            SketchBrushItem("b15", "Duplicate", BrushIconStyle.TECHNICAL_PEN, SketchBrushType.STANDARD, 28f, 100f, hardness = 95f, spacing = 6f),
            SketchBrushItem("b16", "Tattoo Inker", BrushIconStyle.DIAMOND_SHADER, SketchBrushType.STANDARD, 16f, 100f, hardness = 92f),
            SketchBrushItem("b17", "Fountain Pen", BrushIconStyle.FOUNTAIN_PEN, SketchBrushType.STANDARD, 15f, 100f, hardness = 96f, roundness = 45f, rotation = -30f),
            SketchBrushItem("b18", "Calligraphy Nib", BrushIconStyle.FOUNTAIN_PEN, SketchBrushType.STANDARD, 24f, 100f, hardness = 95f, roundness = 22f, rotation = -40f),
            SketchBrushItem("b19", "Watercolor Wash", BrushIconStyle.ROUND_BRUSH, SketchBrushType.MARKER, 52f, 48f, hardness = 35f),
            SketchBrushItem("b20", "Felt Tip Pen", BrushIconStyle.FELT_PEN, SketchBrushType.STANDARD, 20f, 95f, hardness = 88f),
            SketchBrushItem("b21", "Smudge Blender", BrushIconStyle.BLENDER_CONE, SketchBrushType.SMUDGE, 44f, 80f, hardness = 40f),
            SketchBrushItem("b22", "Flat Marker", BrushIconStyle.CHISEL_MARKER, SketchBrushType.MARKER, 50f, 60f, roundness = 25f, rotation = -25f),
            SketchBrushItem("b23", "Brush Marker", BrushIconStyle.CHISEL_MARKER, SketchBrushType.MARKER, 38f, 75f, roundness = 55f, rotation = -35f),
            SketchBrushItem("b24", "Pastel Block", BrushIconStyle.PASTEL_BLOCK, SketchBrushType.STANDARD, 46f, 82f, hardness = 50f, grainStrength = 65f),
            SketchBrushItem("b25", "Charcoal Stick", BrushIconStyle.CHARCOAL_STICK, SketchBrushType.STANDARD, 40f, 88f, hardness = 45f, grainStrength = 72f),
            SketchBrushItem("b26", "Synthetic Flat", BrushIconStyle.FLAT_BRISTLE, SketchBrushType.STANDARD, 48f, 90f, roundness = 35f, grainStrength = 30f),
            SketchBrushItem("b27", "Coarse Bristle", BrushIconStyle.FLAT_BRISTLE, SketchBrushType.STANDARD, 52f, 85f, grainStrength = 55f),
            SketchBrushItem("b28", "Glaze Brush", BrushIconStyle.FLAT_BRISTLE, SketchBrushType.MARKER, 64f, 40f, hardness = 50f),
            SketchBrushItem("b29", "Fan Brush", BrushIconStyle.FAN_BRUSH, SketchBrushType.STANDARD, 60f, 75f, roundness = 30f, grainStrength = 60f)
        )

        val textureBrushes = mutableListOf(
            SketchBrushItem("t1", "Textured Pencil", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 32f, 90f, hardness = 65f, grainStrength = 55f),
            SketchBrushItem("t2", "Grain Shader", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 48f, 85f, hardness = 55f, grainStrength = 70f),
            SketchBrushItem("t3", "Rough Graphite", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 36f, 88f, hardness = 60f, grainStrength = 65f),
            SketchBrushItem("t4", "Duplicate", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 180.3f, 100f, hardness = 72f, grainStrength = 62f),
            SketchBrushItem("t5", "Canvas Weave", BrushIconStyle.WOOD_PENCIL, SketchBrushType.STANDARD, 54f, 82f, hardness = 50f, grainStrength = 78f),
            SketchBrushItem("t6", "Neon Fractal", BrushIconStyle.TEXTURE_FRACTAL, SketchBrushType.GLOW, 75f, 90f, hardness = 35f, grainStrength = 60f),
            SketchBrushItem("t7", "Crosshatch Stamp", BrushIconStyle.TEXTURE_HATCH, SketchBrushType.STANDARD, 68f, 85f, spacing = 22f, grainStrength = 80f),
            SketchBrushItem("t8", "Frost Crystal", BrushIconStyle.TEXTURE_FROST, SketchBrushType.STANDARD, 72f, 88f, spacing = 28f, sizeJitter = 25f, grainStrength = 75f),
            SketchBrushItem("t9", "Radial Burst", BrushIconStyle.TEXTURE_SPLATTER, SketchBrushType.STANDARD, 80f, 92f, spacing = 30f, scatterJitter = 20f, grainStrength = 70f),
            SketchBrushItem("t10", "Dark Nebula", BrushIconStyle.TEXTURE_FRACTAL, SketchBrushType.GLOW, 90f, 78f, hardness = 25f, grainStrength = 65f)
        )

        return mutableListOf(
            SketchBrushSet("set_basic", "Basic", isPinned = true, brushes = basicBrushes),
            SketchBrushSet("set_overlay", "overlay texture", isPinned = false, brushes = textureBrushes)
        )
    }
}

data class CanvasPreset(
    val id: String,
    var name: String,
    var width: Int,
    var height: Int
)

private data class ProjectSnapshot(
    val bitmap: Bitmap,
    val temporalStrokes: List<TemporalAnimatedStroke>
)

class SketchProjectState(
    var name: String = nextDefaultName(),
    var width: Int = 1080,
    var height: Int = 2408,
    var isDirty: Boolean = false,
    var isSavedOnce: Boolean = false,
    var lastSavedPath: String? = null
) {
    var canvasBitmap: Bitmap? = null
    val temporalStrokes = mutableListOf<TemporalAnimatedStroke>()
    private val undoStack = ArrayDeque<ProjectSnapshot>()
    private val redoStack = ArrayDeque<ProjectSnapshot>()

    fun ensureCanvasBitmap(): Bitmap {
        val targetW = width.coerceIn(1, 8192)
        val targetH = height.coerceIn(1, 8192)

        var bmp = canvasBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != targetW || bmp.height != targetH) {
            bmp = try {
                Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                System.gc()
                Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            }
            val c = Canvas(bmp!!)
            c.drawColor(Color.TRANSPARENT)
            canvasBitmap = bmp
        }
        return bmp!!
    }

    fun pushUndoSnapshot() {
        val current = canvasBitmap ?: return
        if (current.isRecycled) return
        try {
            val copyBmp = current.copy(Bitmap.Config.ARGB_8888, true)
            val copyTemporal = temporalStrokes.map { it.deepCopy() }
            undoStack.addLast(ProjectSnapshot(copyBmp, copyTemporal))

            val bytesPerBmp = (current.width.toLong() * current.height.toLong() * 4L).coerceAtLeast(1L)
            val maxSteps = (160_000_000L / bytesPerBmp).toInt().coerceIn(3, 18)
            while (undoStack.size > maxSteps) {
                val old = undoStack.removeFirst()
                if (!old.bitmap.isRecycled) old.bitmap.recycle()
            }
            for (s in redoStack) {
                if (!s.bitmap.isRecycled) s.bitmap.recycle()
            }
            redoStack.clear()
        } catch (_: OutOfMemoryError) {
            if (undoStack.isNotEmpty()) {
                val old = undoStack.removeFirst()
                if (!old.bitmap.isRecycled) old.bitmap.recycle()
            }
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val current = canvasBitmap
        if (current != null && !current.isRecycled) {
            redoStack.addLast(ProjectSnapshot(current, temporalStrokes.map { it.deepCopy() }))
        }
        val prev = undoStack.removeLast()
        canvasBitmap = prev.bitmap
        temporalStrokes.clear()
        temporalStrokes.addAll(prev.temporalStrokes)
        isDirty = true
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val current = canvasBitmap
        if (current != null && !current.isRecycled) {
            undoStack.addLast(ProjectSnapshot(current, temporalStrokes.map { it.deepCopy() }))
        }
        val next = redoStack.removeLast()
        canvasBitmap = next.bitmap
        temporalStrokes.clear()
        temporalStrokes.addAll(next.temporalStrokes)
        isDirty = true
        return true
    }

    fun releaseAll() {
        canvasBitmap?.let { if (!it.isRecycled) it.recycle() }
        canvasBitmap = null
        temporalStrokes.clear()
        for (s in undoStack) if (!s.bitmap.isRecycled) s.bitmap.recycle()
        for (s in redoStack) if (!s.bitmap.isRecycled) s.bitmap.recycle()
        undoStack.clear()
        redoStack.clear()
    }

    fun hasAnimatedStrokes(): Boolean {
        return temporalStrokes.any { it.nodes.isNotEmpty() }
    }

    fun calcMegapixels(): Float {
        if (width <= 0 || height <= 0) return 0f
        return (width.toLong() * height.toLong()) / 1_000_000f
    }

    companion object {
        private var untitledCounter = 1

        fun nextDefaultName(): String = "Untitled-${untitledCounter++}"

        fun calculateMaxLayers(w: Int, h: Int): Int {
            if (w <= 0 || h <= 0) return 0
            val mpx = (w.toLong() * h.toLong()) / 1_000_000f
            if (mpx <= 0.01f) return 250
            val raw = (390f / mpx).roundToInt()
            return min(250, max(4, raw))
        }

        fun getHardwareScreenResolution(context: Context): Pair<Int, Int> {
            return try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.maximumWindowMetrics.bounds
                    Pair(min(b.width(), b.height()), max(b.width(), b.height()))
                } else {
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    Pair(min(dm.widthPixels, dm.heightPixels), max(dm.widthPixels, dm.heightPixels))
                }
            } catch (_: Exception) {
                val dm = context.resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        }
    }
}
