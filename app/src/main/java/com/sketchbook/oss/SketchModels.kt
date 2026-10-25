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
    var name: String = nextDefaultName(),
    var width: Int = 1080,
    var height: Int = 2408,
    var isDirty: Boolean = false,
    var isSavedOnce: Boolean = false,
    var lastSavedPath: String? = null
) {
    var canvasBitmap: Bitmap? = null
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()

    /**
     * Allocates the project canvas bitmap at exact 1:1 pixel dimensions (supports 4K+)
     * with a safe fallback only if the device GPU/RAM limit is exceeded.
     */
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
            val copy = current.copy(Bitmap.Config.ARGB_8888, true)
            undoStack.addLast(copy)
            // Dynamically cap undo history to ~160MB total to support 4K canvases without OOM
            val bytesPerBmp = (current.width.toLong() * current.height.toLong() * 4L).coerceAtLeast(1L)
            val maxSteps = (160_000_000L / bytesPerBmp).toInt().coerceIn(3, 18)
            while (undoStack.size > maxSteps) {
                undoStack.removeFirst().recycle()
            }
            for (b in redoStack) {
                if (!b.isRecycled) b.recycle()
            }
            redoStack.clear()
        } catch (_: OutOfMemoryError) {
            if (undoStack.isNotEmpty()) undoStack.removeFirst().recycle()
        }
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
        private var untitledCounter = 1

        fun nextDefaultName(): String {
            return "Untitled-${untitledCounter++}"
        }

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
