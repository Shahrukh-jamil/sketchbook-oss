package com.sketchbook.oss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    BRUSH_MENU
}

enum class NewSketchTab {
    PLUS_CUSTOM,
    LIST_PRESETS
}

enum class CoolBrushMode(val title: String) {
    STUDIO_INK("Studio Tapered Ink"),
    NEON_GLOW("Neon Laser Glow"),
    CHISEL_PEN("Calligraphy Chisel")
}

data class CoolBrushConfig(
    var mode: CoolBrushMode = CoolBrushMode.STUDIO_INK,
    var sizePx: Float = 18f,
    var opacity: Int = 255,
    var smoothing: Float = 0.65f,
    var color: Int = Color.rgb(20, 24, 30)
)

data class CanvasPreset(
    val id: String,
    var name: String,
    var width: Int,
    var height: Int
)

class SketchProjectState(
    var name: String = "Untitled-1",
    var width: Int = 1080,
    var height: Int = 2408,
    var isDirty: Boolean = false,
    var isSavedOnce: Boolean = false,
    var lastSavedPath: String? = null
) {
    var canvasBitmap: Bitmap? = null
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()

    fun ensureCanvasBitmap(): Bitmap {
        val maxSide = 2560
        val scale = if (width > maxSide || height > maxSide) {
            maxSide.toFloat() / max(width, height).toFloat()
        } else 1f
        val bw = (width * scale).toInt().coerceAtLeast(1)
        val bh = (height * scale).toInt().coerceAtLeast(1)

        var bmp = canvasBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != bw || bmp.height != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.TRANSPARENT)
            canvasBitmap = bmp
        }
        return bmp
    }

    fun pushUndoSnapshot() {
        val current = canvasBitmap ?: return
        if (current.isRecycled) return
        val copy = current.copy(Bitmap.Config.ARGB_8888, true)
        undoStack.addLast(copy)
        while (undoStack.size > 18) {
            undoStack.removeFirst().recycle()
        }
        for (b in redoStack) {
            if (!b.isRecycled) b.recycle()
        }
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val current = canvasBitmap
        if (current != null && !current.isRecycled) {
            redoStack.addLast(current)
        }
        canvasBitmap = undoStack.removeLast()
        isDirty = true
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val current = canvasBitmap
        if (current != null && !current.isRecycled) {
            undoStack.addLast(current)
        }
        canvasBitmap = redoStack.removeLast()
        isDirty = true
        return true
    }

    fun releaseAll() {
        canvasBitmap?.let { if (!it.isRecycled) it.recycle() }
        canvasBitmap = null
        for (b in undoStack) if (!b.isRecycled) b.recycle()
        for (b in redoStack) if (!b.isRecycled) b.recycle()
        undoStack.clear()
        redoStack.clear()
    }

    fun calcMegapixels(): Float {
        if (width <= 0 || height <= 0) return 0f
        return (width.toLong() * height.toLong()) / 1_000_000f
    }

    companion object {
        fun calculateMaxLayers(w: Int, h: Int): Int {
            if (w <= 0 || h <= 0) return 0
            val mpx = (w.toLong() * h.toLong()) / 1_000_000f
            if (mpx <= 0.01f) return 250
            val raw = (390f / mpx).roundToInt()
            return min(250, max(4, raw))
        }

        /**
         * Returns the exact hardware screen pixel resolution (e.g. 1080 x 2408)
         * without subtracting status bar or navigation bar insets.
         */
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
