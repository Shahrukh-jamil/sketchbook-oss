package com.sketchbook.oss

import android.graphics.Bitmap
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
    MOD_MENU
}

enum class NewSketchTab {
    PLUS_CUSTOM,
    LIST_PRESETS
}

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
    var importedBitmap: Bitmap? = null,
    var isDirty: Boolean = false,
    var isSavedOnce: Boolean = false,
    var lastSavedPath: String? = null
) {
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
    }
}
