package com.sketchbook.oss

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ProjectStorageManager {

    private const val PREFS_NAME = "sketchbook_oss_prefs"

    fun loadCustomPresets(context: Context): MutableList<CanvasPreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_presets_v1", null)
        val list = mutableListOf<CanvasPreset>()
        if (raw.isNullOrEmpty()) {
            list.addAll(
                listOf(
                    CanvasPreset("p_letter", "Letter", 2550, 3300),
                    CanvasPreset("p_a4", "A4", 2480, 3508),
                    CanvasPreset("p_a5", "A5", 1748, 2480),
                    CanvasPreset("p_4x6", "4 x 6", 1200, 1800),
                    CanvasPreset("p_5x7", "5 x 7", 1500, 2100)
                )
            )
            saveCustomPresets(context, list)
            return list
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CanvasPreset(
                        id = o.optString("id", "p_$i"),
                        name = o.optString("name", "Preset"),
                        width = o.optInt("width", 1080),
                        height = o.optInt("height", 2408)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveCustomPresets(context: Context, presets: List<CanvasPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("width", p.width)
            o.put("height", p.height)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("custom_presets_v1", arr.toString())
            .apply()
    }

    fun loadLastDimensions(context: Context, screenW: Int, screenH: Int): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val w = prefs.getInt("last_w", screenW)
        val h = prefs.getInt("last_h", screenH)
        return Pair(w, h)
    }

    fun saveLastDimensions(context: Context, w: Int, h: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("last_w", w)
            .putInt("last_h", h)
            .apply()
    }

    fun saveProjectToDevice(
        context: Context,
        project: SketchProjectState,
        asCopy: Boolean,
        paperColor: Int = Color.WHITE
    ): Result<String> {
        return try {
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val finalName = if (asCopy) {
                "${safeBase}_copy_${System.currentTimeMillis() % 10000}"
            } else {
                safeBase
            }

            val layerBmp = project.ensureCanvasBitmap()
            val outBmp = Bitmap.createBitmap(layerBmp.width, layerBmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(outBmp)
            c.drawColor(paperColor)
            c.drawBitmap(layerBmp, 0f, 0f, null)

            val savedLocation = savePngToMediaStoreOrDisk(context, outBmp, finalName)

            val tiffDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects")
            if (!tiffDir.exists()) tiffDir.mkdirs()
            val tiffFile = File(tiffDir, "$finalName.tif")
            FileOutputStream(tiffFile).use { fos ->
                writeSketchbookTiff(outBmp, fos, project.width, project.height)
            }

            outBmp.recycle()
            Result.success(savedLocation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun savePngToMediaStoreOrDisk(context: Context, bmp: Bitmap, baseName: String): String {
        val fileName = "$baseName.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SketchBookOSS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Pictures/SketchBookOSS/$fileName"
            }
        }

        val pubPic = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SketchBookOSS")
        if (!pubPic.exists()) pubPic.mkdirs()
        val outFile = File(pubPic, fileName)
        return try {
            FileOutputStream(outFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outFile.absolutePath
        } catch (_: Exception) {
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "SketchBookOSS")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fbFile = File(fallbackDir, fileName)
            FileOutputStream(fbFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            fbFile.absolutePath
        }
    }

    fun writeSketchbookTiff(bmp: Bitmap, out: OutputStream, logicalW: Int, logicalH: Int) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val imageByteCount = w * h * 4
        val softwareStr = "Autodesk SketchBook OSS ($logicalW x $logicalH)\u0000"
        val softwareBytes = softwareStr.toByteArray(Charsets.US_ASCII)

        val ifdOffset = 8
        val numEntries = 12
        val afterIfdOffset = ifdOffset + 2 + (numEntries * 12) + 4
        val bpsOffset = afterIfdOffset
        val xResOffset = bpsOffset + 8
        val yResOffset = xResOffset + 8
        val softOffset = yResOffset + 8
        val pixelDataOffset = softOffset + softwareBytes.size + (softwareBytes.size % 2)

        val headerBuf = ByteBuffer.allocate(pixelDataOffset).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put('I'.code.toByte())
        headerBuf.put('I'.code.toByte())
        headerBuf.putShort(42)
        headerBuf.putInt(ifdOffset)

        headerBuf.position(ifdOffset)
        headerBuf.putShort(numEntries.toShort())

        fun putIfdEntry(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
            headerBuf.putShort(tag.toShort())
            headerBuf.putShort(type.toShort())
            headerBuf.putInt(count)
            headerBuf.putInt(valueOrOffset)
        }

        putIfdEntry(256, 4, 1, w)
        putIfdEntry(257, 4, 1, h)
        putIfdEntry(258, 3, 4, bpsOffset)
        putIfdEntry(259, 3, 1, 1)
        putIfdEntry(262, 3, 1, 2)
        putIfdEntry(273, 4, 1, pixelDataOffset)
        putIfdEntry(277, 3, 1, 4)
        putIfdEntry(278, 4, 1, h)
        putIfdEntry(279, 4, 1, imageByteCount)
        putIfdEntry(282, 5, 1, xResOffset)
        putIfdEntry(283, 5, 1, yResOffset)
        putIfdEntry(305, 2, softwareBytes.size, softOffset)

        headerBuf.putInt(0)

        headerBuf.position(bpsOffset)
        headerBuf.putShort(8); headerBuf.putShort(8); headerBuf.putShort(8); headerBuf.putShort(8)

        headerBuf.position(xResOffset)
        headerBuf.putInt(300); headerBuf.putInt(1)
        headerBuf.position(yResOffset)
        headerBuf.putInt(300); headerBuf.putInt(1)

        headerBuf.position(softOffset)
        headerBuf.put(softwareBytes)

        out.write(headerBuf.array())

        val rowBytes = ByteArray(w * 4)
        var idx = 0
        for (y in 0 until h) {
            var bPos = 0
            for (x in 0 until w) {
                val c = pixels[idx++]
                rowBytes[bPos++] = ((c shr 16) and 0xFF).toByte()
                rowBytes[bPos++] = ((c shr 8) and 0xFF).toByte()
                rowBytes[bPos++] = (c and 0xFF).toByte()
                rowBytes[bPos++] = ((c shr 24) and 0xFF).toByte()
            }
            out.write(rowBytes)
        }
        out.flush()
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifEmpty { "Untitled-1" }
    }
}
