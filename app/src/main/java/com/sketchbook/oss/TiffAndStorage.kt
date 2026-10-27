package com.sketchbook.oss

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import kotlin.math.abs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ProjectStorageManager {

    private const val PREFS_NAME = "sketchbook_oss_prefs"
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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

    fun loadAnimDuration(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat("anim_export_duration_sec", 3.5f).coerceIn(0.5f, 60f)
    }

    fun saveAnimDuration(context: Context, durationSec: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("anim_export_duration_sec", durationSec.coerceIn(0.5f, 60f))
            .apply()
    }

    private data class UniformFit(val scale: Float, val offX: Float, val offY: Float, val drawW: Float, val drawH: Float)

    private fun computeUniformFit(srcW: Int, srcH: Int, targetW: Int, targetH: Int): UniformFit {
        val sW = max(1, srcW).toFloat(); val sH = max(1, srcH).toFloat()
        val tW = max(1, targetW).toFloat(); val tH = max(1, targetH).toFloat()
        val scale = min(tW / sW, tH / sH)
        val drawW = sW * scale; val drawH = sH * scale
        val offX = (tW - drawW) * 0.5f; val offY = (tH - drawH) * 0.5f
        return UniformFit(scale, offX, offY, drawW, drawH)
    }

    private fun createPrebakedStaticBitmap(targetW: Int, targetH: Int, layerBmp: Bitmap, paperColor: Int): Bitmap {
        val baseBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(baseBmp)
        c.drawColor(paperColor)
        if (layerBmp.width == targetW && layerBmp.height == targetH) {
            c.drawBitmap(layerBmp, 0f, 0f, null)
        } else {
            val fit = computeUniformFit(layerBmp.width, layerBmp.height, targetW, targetH)
            c.drawBitmap(layerBmp, null, RectF(fit.offX, fit.offY, fit.offX + fit.drawW, fit.offY + fit.drawH), filterPaint)
        }
        return baseBmp
    }

    private fun createPrebakedStaticBuffer(targetW: Int, targetH: Int, layerBmp: Bitmap, paperColor: Int): ByteBuffer {
        val baseBmp = createPrebakedStaticBitmap(targetW, targetH, layerBmp, paperColor)
        val buf = ByteBuffer.allocateDirect(targetW * targetH * 4)
        baseBmp.copyPixelsToBuffer(buf)
        buf.rewind()
        baseBmp.recycle()
        return buf
    }

    private fun renderFrameWithPrebakedBase(
        targetBmp: Bitmap, targetCanvas: Canvas, prebakedBaseSlice: ByteBuffer?,
        layerBmp: Bitmap, temporalStrokes: List<TemporalAnimatedStroke>,
        paperColor: Int, timeSec: Float, vm: BrushShaderVM
    ) {
        if (prebakedBaseSlice != null) {
            prebakedBaseSlice.rewind()
            targetBmp.copyPixelsFromBuffer(prebakedBaseSlice)
            if (temporalStrokes.isNotEmpty()) {
                val exactW = targetBmp.width; val exactH = targetBmp.height
                if (exactW == layerBmp.width && exactH == layerBmp.height) {
                    UnifiedBrushRenderer.renderTemporalStrokes(targetCanvas, temporalStrokes, layerBmp, timeSec, vm)
                } else {
                    val fit = computeUniformFit(layerBmp.width, layerBmp.height, exactW, exactH)
                    targetCanvas.save()
                    targetCanvas.translate(fit.offX, fit.offY)
                    targetCanvas.scale(fit.scale, fit.scale)
                    UnifiedBrushRenderer.renderTemporalStrokes(targetCanvas, temporalStrokes, layerBmp, timeSec, vm)
                    targetCanvas.restore()
                }
            }
        } else {
            renderCompositeFrame(targetBmp, layerBmp, temporalStrokes, paperColor, timeSec, vm)
        }
    }

    fun renderCompositeFrame(
        targetBmp: Bitmap, layerBmp: Bitmap, temporalStrokes: List<TemporalAnimatedStroke>,
        paperColor: Int, timeSec: Float, vm: BrushShaderVM
    ) {
        val exactW = targetBmp.width; val exactH = targetBmp.height
        val c = Canvas(targetBmp)
        c.drawColor(paperColor)
        val hasEraseTemporal = temporalStrokes.any { it.isErase }
        val saveCount = if (hasEraseTemporal) c.saveLayer(0f, 0f, exactW.toFloat(), exactH.toFloat(), null) else -1
        val fit = computeUniformFit(layerBmp.width, layerBmp.height, exactW, exactH)
        if (layerBmp.width == exactW && layerBmp.height == exactH) {
            c.drawBitmap(layerBmp, 0f, 0f, null)
        } else {
            c.drawBitmap(layerBmp, null, RectF(fit.offX, fit.offY, fit.offX + fit.drawW, fit.offY + fit.drawH), filterPaint)
        }
        if (temporalStrokes.isNotEmpty()) {
            if (exactW == layerBmp.width && exactH == layerBmp.height) {
                UnifiedBrushRenderer.renderTemporalStrokes(c, temporalStrokes, layerBmp, timeSec, vm)
            } else {
                c.save()
                c.translate(fit.offX, fit.offY)
                c.scale(fit.scale, fit.scale)
                UnifiedBrushRenderer.renderTemporalStrokes(c, temporalStrokes, layerBmp, timeSec, vm)
                c.restore()
            }
        }
        if (saveCount >= 0) c.restoreToCount(saveCount)
    }

    fun saveProjectToDevice(
        context: Context, project: SketchProjectState, asCopy: Boolean,
        paperColor: Int = Color.WHITE,
        snapshotTimeSec: Float = (System.nanoTime() / 1_000_000_000.0).toFloat() % 3600f,
        temporalStrokesSnapshot: List<TemporalAnimatedStroke> = project.temporalStrokes.map { it.deepCopy() }
    ): Result<String> {
        return try {
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val finalName = if (asCopy) "${safeBase}_copy_${System.currentTimeMillis() % 10000}" else safeBase
            val exactW = project.width.coerceAtLeast(1); val exactH = project.height.coerceAtLeast(1)
            val layerBmp = project.ensureCanvasBitmap()
            val outBmp = Bitmap.createBitmap(exactW, exactH, Bitmap.Config.ARGB_8888)
            val vm = BrushShaderVM()
            renderCompositeFrame(outBmp, layerBmp, temporalStrokesSnapshot, paperColor, snapshotTimeSec, vm)
            val savedPngLocation = saveImageToMediaStoreOrDisk(
                context, outBmp, finalName, "png", "image/png", Bitmap.CompressFormat.PNG, 100
            )
            val tiffDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Projects")
            if (!tiffDir.exists()) tiffDir.mkdirs()
            val tiffFile = File(tiffDir, "$finalName.tif")
            FileOutputStream(tiffFile).use { fos -> writeSketchbookTiff(outBmp, fos, exactW, exactH) }
            val savedTiffLocation = saveTiffToPublicStorage(context, tiffFile, "$finalName.tif")
            outBmp.recycle()
            Result.success("$savedPngLocation + $savedTiffLocation")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportStaticFormatToDevice(
        context: Context, project: SketchProjectState, formatCode: String,
        paperColor: Int = Color.WHITE,
        snapshotTimeSec: Float = (System.nanoTime() / 1_000_000_000.0).toFloat() % 3600f,
        temporalStrokesSnapshot: List<TemporalAnimatedStroke> = project.temporalStrokes.map { it.deepCopy() }
    ): Result<String> {
        return try {
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val exactW = project.width.coerceAtLeast(1); val exactH = project.height.coerceAtLeast(1)
            val layerBmp = project.ensureCanvasBitmap()
            val outBmp = Bitmap.createBitmap(exactW, exactH, Bitmap.Config.ARGB_8888)
            val vm = BrushShaderVM()
            renderCompositeFrame(outBmp, layerBmp, temporalStrokesSnapshot, paperColor, snapshotTimeSec, vm)
            val loc = when (formatCode.uppercase()) {
                "JPG", "JPEG" -> saveImageToMediaStoreOrDisk(
                    context, outBmp, safeBase, "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 96
                )
                "TIF", "TIFF" -> {
                    val tiffBytes = File(context.cacheDir, "export_${System.currentTimeMillis()}.tif")
                    FileOutputStream(tiffBytes).use { fos -> writeSketchbookTiff(outBmp, fos, exactW, exactH) }
                    val resLoc = saveTiffToPublicStorage(context, tiffBytes, "$safeBase.tif")
                    tiffBytes.delete()
                    resLoc
                }
                else -> saveImageToMediaStoreOrDisk(
                    context, outBmp, safeBase, "png", "image/png", Bitmap.CompressFormat.PNG, 100
                )
            }
            outBmp.recycle()
            Result.success("$loc (${exactW}×${exactH})")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveAnimatedProjectToDevice(
        context: Context, project: SketchProjectState, format: AnimatedExportFormat,
        paperColor: Int = Color.WHITE, startTimeSec: Float = 0f, durationSec: Float = 3.5f,
        temporalStrokesSnapshot: List<TemporalAnimatedStroke> = project.temporalStrokes.map { it.deepCopy() },
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<String> {
        return try {
            val safeBase = sanitizeFileName(project.name.ifBlank { "Untitled-1" })
            val layerBmp = project.ensureCanvasBitmap()
            val srcW = project.width.coerceAtLeast(1); val srcH = project.height.coerceAtLeast(1)
            val clampedDuration = durationSec.coerceIn(0.5f, 60f)
            when (format) {
                AnimatedExportFormat.MP4_HIGH_BITRATE -> encodeHighBitrateVideo(
                    context, safeBase, srcW, srcH, layerBmp, temporalStrokesSnapshot,
                    paperColor, startTimeSec, clampedDuration, isWebm = false, onProgress = onProgress
                )
                AnimatedExportFormat.WEBM_HIGH_BITRATE -> encodeHighBitrateVideo(
                    context, safeBase, srcW, srcH, layerBmp, temporalStrokesSnapshot,
                    paperColor, startTimeSec, clampedDuration, isWebm = true, onProgress = onProgress
                )
                AnimatedExportFormat.GIF_LOOP -> encodeAnimatedGif(
                    context, safeBase, srcW, srcH, layerBmp, temporalStrokesSnapshot,
                    paperColor, startTimeSec, clampedDuration, onProgress = onProgress
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveSupportedVideoSize(
        vc: MediaCodecInfo.VideoCapabilities?, srcW: Int, srcH: Int
    ): Pair<Int, Int> {
        val maxCandidates = intArrayOf(1920, 1600, 1440, 1280, 1088, 1080, 960, 720, 640, 480, 360)
        val wAlign = max(2, vc?.widthAlignment ?: 16)
        val hAlign = max(2, vc?.heightAlignment ?: 16)
        val sW = max(1, srcW).toFloat(); val sH = max(1, srcH).toFloat()
        val maxCodecW = vc?.supportedWidths?.upper ?: 1920
        val minCodecW = vc?.supportedWidths?.lower ?: 64
        val maxCodecH = vc?.supportedHeights?.upper ?: 1920
        val minCodecH = vc?.supportedHeights?.lower ?: 64

        fun alignDim(v: Float, align: Int, minV: Int, maxV: Int): Int {
            val rounded = (((v / align.toFloat()).roundToInt()) * align).coerceIn(minV, maxV)
            return (rounded / align) * align
        }

        for (cap in maxCandidates) {
            val effMaxW = min(cap, maxCodecW).toFloat()
            val effMaxH = min(cap, maxCodecH).toFloat()
            val scale1 = min(1.0f, min(effMaxW / sW, effMaxH / sH))
            val w1 = alignDim(sW * scale1, wAlign, minCodecW, maxCodecW)
            val h1 = alignDim(sH * scale1, hAlign, minCodecH, maxCodecH)
            if (vc == null) return Pair(w1, h1)
            if (w1 >= 64 && h1 >= 64 && vc.isSizeSupported(w1, h1)) return Pair(w1, h1)
            try {
                val hRangeForW = vc.getSupportedHeightsFor(w1)
                val maxHForW = min(cap, hRangeForW.upper).toFloat()
                val scale2 = min(scale1, maxHForW / sH)
                val w2 = alignDim(sW * scale2, wAlign, minCodecW, maxCodecW)
                val h2 = alignDim(sH * scale2, hAlign, max(minCodecH, hRangeForW.lower), hRangeForW.upper)
                if (w2 >= 64 && h2 >= 64 && vc.isSizeSupported(w2, h2)) return Pair(w2, h2)
            } catch (_: Exception) {}
        }
        val fallbackScale = min(1.0f, min(640f / sW, 640f / sH))
        return Pair(alignDim(sW * fallbackScale, 16, 64, 640), alignDim(sH * fallbackScale, 16, 64, 640))
    }

    private fun encodeHighBitrateVideo(
        context: Context, baseName: String, srcW: Int, srcH: Int, layerBmp: Bitmap,
        temporalStrokes: List<TemporalAnimatedStroke>, paperColor: Int,
        startTimeSec: Float, durationSec: Float, isWebm: Boolean,
        onProgress: (Float, String) -> Unit
    ): Result<String> {
        val ext = if (isWebm) "webm" else "mp4"
        val outMime = if (isWebm) "video/webm" else "video/mp4"
        val tempFile = File(context.cacheDir, "anim_export_${System.currentTimeMillis()}.$ext")

        var encoder: MediaCodec? = null
        var codecMime = if (isWebm) MediaFormat.MIMETYPE_VIDEO_VP9 else MediaFormat.MIMETYPE_VIDEO_AVC
        if (isWebm) {
            try {
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP9)
            } catch (_: Exception) {
                codecMime = MediaFormat.MIMETYPE_VIDEO_VP8
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP8)
            }
        } else {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        val enc = encoder ?: throw IllegalStateException("Video encoder unavailable")
        val vc = try { enc.codecInfo.getCapabilitiesForType(codecMime).videoCapabilities } catch (_: Exception) { null }
        val (vidW, vidH) = resolveSupportedVideoSize(vc, srcW, srcH)
        val targetBitrate = vc?.bitrateRange?.clamp(28_000_000) ?: 24_000_000
        var fps = 60
        try {
            val maxFps = vc?.getSupportedFrameRatesFor(vidW, vidH)?.upper?.toInt() ?: 60
            if (maxFps < 60) fps = maxFps.coerceIn(24, 60)
        } catch (_: Exception) {}

        val format = MediaFormat.createVideoFormat(codecMime, vidW, vidH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            enc.reset()
            fps = 30
            val fallbackBitrate = vc?.bitrateRange?.clamp(16_000_000) ?: 12_000_000
            val fbFormat = MediaFormat.createVideoFormat(codecMime, vidW, vidH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, fallbackBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(fbFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val inputSurface = enc.createInputSurface()
        enc.start()

        val muxerFormat = if (isWebm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val muxer = MediaMuxer(tempFile.absolutePath, muxerFormat)
        var trackIndex = -1
        var muxerStarted = false
        var lastWrittenPtsUs = -1L
        val bufferInfo = MediaCodec.BufferInfo()

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) {
                try { enc.signalEndOfInputStream() } catch (_: Exception) {}
            }
            val timeoutUs = if (endOfStream) 5_000L else 0L
            var eosRetries = 0
            while (true) {
                val outStatus = enc.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break
                    eosRetries++
                    if (eosRetries > 250) break
                } else if (outStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outStatus >= 0) {
                    val encodedData = enc.getOutputBuffer(outStatus)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0
                    if (bufferInfo.size > 0 && muxerStarted && encodedData != null) {
                        if (bufferInfo.presentationTimeUs <= lastWrittenPtsUs) {
                            bufferInfo.presentationTimeUs = lastWrittenPtsUs + (1_000_000L / fps.toLong())
                        }
                        lastWrittenPtsUs = bufferInfo.presentationTimeUs
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    enc.releaseOutputBuffer(outStatus, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }

        val vm = BrushShaderVM()
        for (ts in temporalStrokes) {
            if (ts.vmCode.isNotEmpty()) vm.getCompiledProgram(ts.vmCode)
        }

        val eglHelper = EglBitmapSurfaceRenderer(inputSurface, vidW, vidH, layerBmp.width, layerBmp.height)
        val staticBaseBmp = createPrebakedStaticBitmap(vidW, vidH, layerBmp, paperColor)
        val gpuBatcher = GpuShapeBatcher(65536).apply {
            onFlushCallback = { batch -> eglHelper.drawGpuShapeBatch(batch) }
        }

        val totalFrames = (fps * durationSec).roundToInt().coerceAtLeast(15)
        val mbpsStr = String.format("%.0f Mbps", targetBitrate / 1_000_000f)
        val durLabel = if (durationSec % 1f == 0f) String.format("%.0fs", durationSec) else String.format("%.1fs", durationSec)

        try {
            eglHelper.uploadBaseTextureOnce(staticBaseBmp)
            staticBaseBmp.recycle()

            for (f in 0 until totalFrames) {
                val tSec = startTimeSec + f.toFloat() / fps.toFloat()
                val ptsNs = f.toLong() * 1_000_000_000L / fps.toLong()

                eglHelper.beginGpuFrame()
                if (temporalStrokes.isNotEmpty()) {
                    gpuBatcher.reset(isErase = false)
                    UnifiedBrushRenderer.renderTemporalStrokesGpu(
                        gpuBatcher = gpuBatcher,
                        temporalStrokes = temporalStrokes,
                        canvasBitmap = layerBmp,
                        timeSec = tSec,
                        vmInstance = vm
                    )
                }
                eglHelper.endGpuFrame(ptsNs)
                drainEncoder(endOfStream = false)

                if (f % 6 == 0 || f == totalFrames - 1) {
                    val frac = (f + 1).toFloat() / totalFrames.toFloat()
                    onProgress(frac, "GPU • $durLabel • $mbpsStr • ${vidW}×${vidH} @ ${fps}fps (${(frac * 100).roundToInt()}%)")
                }
            }
            drainEncoder(endOfStream = true)
        } finally {
            if (!staticBaseBmp.isRecycled) staticBaseBmp.recycle()
            eglHelper.release()
            try { enc.stop() } catch (_: Exception) {}
            try { enc.release() } catch (_: Exception) {}
            try { inputSurface.release() } catch (_: Exception) {}
            if (muxerStarted) { try { muxer.stop() } catch (_: Exception) {} }
            try { muxer.release() } catch (_: Exception) {}
        }

        val savedPath = saveVideoToMediaStoreOrDisk(context, tempFile, "$baseName.$ext", outMime)
        tempFile.delete()
        return Result.success("$savedPath ($durLabel ${vidW}×${vidH} ${fps}fps $mbpsStr)")
    }

    private class GifWorkerSlot(gifW: Int, gifH: Int, baseBuf: ByteBuffer?) {
        val bmp: Bitmap = Bitmap.createBitmap(gifW, gifH, Bitmap.Config.ARGB_8888)
        val canvas: Canvas = Canvas(bmp)
        val vm: BrushShaderVM = BrushShaderVM()
        val baseSlice: ByteBuffer? = baseBuf?.duplicate()
        val pixels: IntArray = IntArray(gifW * gifH)
        val indices: ByteArray = ByteArray(gifW * gifH)
        val lzwTable: IntArray = IntArray(4096 * 256)
        var lzwGen: Int = 1
        val subBlock: ByteArray = ByteArray(256)
        val frameOut: ByteArrayOutputStream = ByteArrayOutputStream(max(4096, gifW * gifH / 3))

        fun nextGeneration(): Int {
            lzwGen++
            if (lzwGen >= 0x7FFFF) { lzwTable.fill(0); lzwGen = 1 }
            return lzwGen
        }
        fun release() { if (!bmp.isRecycled) bmp.recycle() }
    }

    private fun encodeAnimatedGif(
        context: Context, baseName: String, srcW: Int, srcH: Int, layerBmp: Bitmap,
        temporalStrokes: List<TemporalAnimatedStroke>, paperColor: Int,
        startTimeSec: Float, durationSec: Float, onProgress: (Float, String) -> Unit
    ): Result<String> {
        val maxGifDim = 640
        val scale = min(1.0f, maxGifDim.toFloat() / max(srcW, srcH).toFloat())
        val gifW = (srcW * scale).roundToInt().coerceAtLeast(32)
        val gifH = (srcH * scale).roundToInt().coerceAtLeast(32)
        val fps = 20; val delayCs = 5
        val clampedGifDur = durationSec.coerceIn(0.5f, 30f)
        val totalFrames = (fps * clampedGifDur).roundToInt().coerceIn(10, 600)
        val tempFile = File(context.cacheDir, "anim_gif_${System.currentTimeMillis()}.gif")

        val warmupVm = BrushShaderVM()
        for (ts in temporalStrokes) {
            if (ts.vmCode.isNotEmpty()) warmupVm.getCompiledProgram(ts.vmCode)
        }

        val hasEraseTemporal = temporalStrokes.any { it.isErase }
        val prebakedBase = if (!hasEraseTemporal) createPrebakedStaticBuffer(gifW, gifH, layerBmp, paperColor) else null
        val bayer4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val numWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val executor = Executors.newFixedThreadPool(numWorkers)
        val slots = Array(numWorkers) { GifWorkerSlot(gifW, gifH, prebakedBase) }

        try {
            BufferedOutputStream(FileOutputStream(tempFile), 262144).use { out ->
                out.write("GIF89a".toByteArray(Charsets.US_ASCII))
                out.write(gifW and 0xFF); out.write((gifW shr 8) and 0xFF)
                out.write(gifH and 0xFF); out.write((gifH shr 8) and 0xFF)
                out.write(0xF7); out.write(0); out.write(0)

                for (r in 0 until 6) {
                    val r8 = (r * 255) / 5
                    for (g in 0 until 7) {
                        val g8 = (g * 255) / 6
                        for (b in 0 until 6) {
                            out.write(r8); out.write(g8); out.write((b * 255) / 5)
                        }
                    }
                }
                for (gray in intArrayOf(36, 109, 182, 219)) {
                    out.write(gray); out.write(gray); out.write(gray)
                }

                out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
                out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
                out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))

                var batchStart = 0
                while (batchStart < totalFrames) {
                    val batchCount = min(numWorkers, totalFrames - batchStart)
                    val tasks = ArrayList<Callable<Unit>>(batchCount)

                    for (w in 0 until batchCount) {
                        val frameIdx = batchStart + w
                        val slot = slots[w]
                        tasks.add(Callable {
                            val tSec = startTimeSec + frameIdx.toFloat() / fps.toFloat()
                            renderFrameWithPrebakedBase(
                                slot.bmp, slot.canvas, slot.baseSlice, layerBmp,
                                temporalStrokes, paperColor, tSec, slot.vm
                            )
                            val pixels = slot.pixels; val indices = slot.indices
                            slot.bmp.getPixels(pixels, 0, gifW, 0, 0, gifW, gifH)

                            var pIdx = 0
                            for (y in 0 until gifH) {
                                val yRow = (y and 3) shl 2
                                for (x in 0 until gifW) {
                                    val c = pixels[pIdx]
                                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                                    val d = (bayer4[yRow or (x and 3)] - 7) * 3
                                    val ri = ((r * 5 + d + 127) / 255).coerceIn(0, 5)
                                    val gi = ((g * 6 + d + 127) / 255).coerceIn(0, 6)
                                    val bi = ((b * 5 + d + 127) / 255).coerceIn(0, 5)
                                    indices[pIdx] = ((ri * 7 + gi) * 6 + bi).toByte()
                                    pIdx++
                                }
                            }

                            val fOut = slot.frameOut; fOut.reset()
                            fOut.write(0x21); fOut.write(0xF9); fOut.write(0x04); fOut.write(0x04)
                            fOut.write(delayCs and 0xFF); fOut.write((delayCs shr 8) and 0xFF)
                            fOut.write(0x00); fOut.write(0x00)
                            fOut.write(0x2C); fOut.write(0); fOut.write(0); fOut.write(0); fOut.write(0)
                            fOut.write(gifW and 0xFF); fOut.write((gifW shr 8) and 0xFF)
                            fOut.write(gifH and 0xFF); fOut.write((gifH shr 8) and 0xFF)
                            fOut.write(0x00); fOut.write(8)

                            val subBlock = slot.subBlock; val lzwTable = slot.lzwTable
                            var subLen = 0; var bitBuf = 0; var bitCnt = 0

                            fun emitCode(code: Int, size: Int) {
                                bitBuf = bitBuf or (code shl bitCnt)
                                bitCnt += size
                                while (bitCnt >= 8) {
                                    subBlock[subLen++] = (bitBuf and 0xFF).toByte()
                                    bitBuf = bitBuf ushr 8; bitCnt -= 8
                                    if (subLen == 255) {
                                        fOut.write(255); fOut.write(subBlock, 0, 255); subLen = 0
                                    }
                                }
                            }

                            val clearCode = 256; val endCode = 257
                            var codeSize = 9; var nextCode = 258
                            var gen = slot.nextGeneration(); var genShift = gen shl 12

                            emitCode(clearCode, codeSize)
                            var prefix = indices[0].toInt() and 0xFF
                            val totalPx = indices.size
                            for (i in 1 until totalPx) {
                                val k = indices[i].toInt() and 0xFF
                                val tIdx = (prefix shl 8) or k
                                val ent = lzwTable[tIdx]
                                if ((ent ushr 12) == gen) {
                                    prefix = ent and 0xFFF
                                } else {
                                    emitCode(prefix, codeSize)
                                    if (nextCode < 4096) {
                                        lzwTable[tIdx] = genShift or nextCode
                                        if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                                        nextCode++
                                    } else {
                                        emitCode(clearCode, codeSize)
                                        codeSize = 9; nextCode = 258
                                        gen = slot.nextGeneration(); genShift = gen shl 12
                                    }
                                    prefix = k
                                }
                            }
                            emitCode(prefix, codeSize); emitCode(endCode, codeSize)
                            if (bitCnt > 0) subBlock[subLen++] = (bitBuf and 0xFF).toByte()
                            if (subLen > 0) { fOut.write(subLen); fOut.write(subBlock, 0, subLen) }
                            fOut.write(0)
                        })
                    }

                    executor.invokeAll(tasks)
                    for (w in 0 until batchCount) slots[w].frameOut.writeTo(out)
                    val doneFrames = batchStart + batchCount
                    val frac = doneFrames.toFloat() / totalFrames.toFloat()
                    onProgress(frac, "GIF89a • ${gifW}×${gifH} • Frame $doneFrames/$totalFrames (${(frac * 100).roundToInt()}%)")
                    batchStart += batchCount
                }
                out.write(0x3B); out.flush()
            }
        } finally {
            executor.shutdownNow()
            for (s in slots) s.release()
        }

        val savedPath = saveRawFileToPictures(context, tempFile, "$baseName.gif", "image/gif")
        tempFile.delete()
        return Result.success("$savedPath (${gifW}×${gifH} Loop GIF)")
    }

    private class EglBitmapSurfaceRenderer(
        surface: Surface,
        private val width: Int,
        private val height: Int,
        srcCanvasW: Int,
        srcCanvasH: Int
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var texProgramId: Int = 0
        private var shapeProgramId: Int = 0
        private var texId: Int = 0
        private var shapeVboId: Int = 0
        private val vertexBuf: FloatBuffer
        private val texCoordBuf: FloatBuffer
        private val uTransformScaleX: Float
        private val uTransformScaleY: Float
        private val uTransformOffX: Float
        private val uTransformOffY: Float

        init {
            val fit = computeUniformFit(srcCanvasW, srcCanvasH, width, height)
            uTransformScaleX = (fit.scale * 2.0f) / width.toFloat()
            uTransformScaleY = -(fit.scale * 2.0f) / height.toFloat()
            uTransformOffX = (fit.offX / width.toFloat()) * 2.0f - 1.0f
            uTransformOffY = 1.0f - (fit.offY / height.toFloat()) * 2.0f

            val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val texs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            vertexBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(verts); position(0)
            }
            texCoordBuf = ByteBuffer.allocateDirect(texs.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(texs); position(0)
            }

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val vers = IntArray(2)
            EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1)

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                val fbAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
                )
                EGL14.eglChooseConfig(eglDisplay, fbAttribs, 0, configs, 0, 1, numConfigs, 0)
            }
            val cfg = configs[0] ?: throw IllegalStateException("No EGLConfig found")
            val ctxAttribs = intArrayOf(0x3098, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val vTexShader = compileShader(
                GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPosition;\nattribute vec2 aTexCoord;\nvarying vec2 vTexCoord;\n" +
                "void main() {\n  gl_Position = aPosition;\n  vTexCoord = aTexCoord;\n}"
            )
            val fTexShader = compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float;\nvarying vec2 vTexCoord;\nuniform sampler2D sTexture;\n" +
                "void main() {\n  gl_FragColor = texture2D(sTexture, vTexCoord);\n}"
            )
            texProgramId = GLES20.glCreateProgram()
            GLES20.glAttachShader(texProgramId, vTexShader)
            GLES20.glAttachShader(texProgramId, fTexShader)
            GLES20.glLinkProgram(texProgramId)

            val vShapeShader = compileShader(
                GLES20.GL_VERTEX_SHADER,
                "uniform vec4 uTransform;\nattribute vec2 aPos;\nattribute vec2 aUV;\n" +
                "attribute vec4 aColor;\nattribute float aFeather;\nvarying vec2 vUV;\n" +
                "varying vec4 vColor;\nvarying float vFeather;\n" +
                "void main() {\n" +
                "  gl_Position = vec4(aPos.x * uTransform.x + uTransform.z, aPos.y * uTransform.y + uTransform.w, 0.0, 1.0);\n" +
                "  vUV = aUV;\n  vColor = aColor;\n  vFeather = aFeather;\n}"
            )
            val fShapeShader = compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float;\nvarying vec2 vUV;\nvarying vec4 vColor;\nvarying float vFeather;\n" +
                "void main() {\n" +
                "  if (vFeather < 0.0) {\n    gl_FragColor = vColor;\n  } else {\n" +
                "    float d = length(vUV);\n    if (d > 1.0) discard;\n" +
                "    float inner = clamp(1.0 - max(0.06, vFeather * 0.85), 0.05, 0.94);\n" +
                "    float w = 1.0 - smoothstep(inner, 1.0, d);\n" +
                "    gl_FragColor = vColor * w;\n  }\n}"
            )
            shapeProgramId = GLES20.glCreateProgram()
            GLES20.glAttachShader(shapeProgramId, vShapeShader)
            GLES20.glAttachShader(shapeProgramId, fShapeShader)
            GLES20.glLinkProgram(shapeProgramId)

            val texArr = IntArray(1)
            GLES20.glGenTextures(1, texArr, 0)
            texId = texArr[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val vboArr = IntArray(1)
            GLES20.glGenBuffers(1, vboArr, 0)
            shapeVboId = vboArr[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shapeVboId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 65536 * GpuShapeBatcher.STRIDE_BYTES, null, GLES20.GL_DYNAMIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun compileShader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            return sh
        }

        fun uploadBaseTextureOnce(bmp: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }

        fun beginGpuFrame() {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(texProgramId)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            val aPos = GLES20.glGetAttribLocation(texProgramId, "aPosition")
            val aTex = GLES20.glGetAttribLocation(texProgramId, "aTexCoord")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }

        fun drawGpuShapeBatch(batcher: GpuShapeBatcher) {
            val count = batcher.vertexCount
            if (count <= 0) return

            GLES20.glEnable(GLES20.GL_BLEND)
            if (batcher.currentEraseMode) {
                GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            } else {
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }

            GLES20.glUseProgram(shapeProgramId)
            val uTrans = GLES20.glGetUniformLocation(shapeProgramId, "uTransform")
            GLES20.glUniform4f(uTrans, uTransformScaleX, uTransformScaleY, uTransformOffX, uTransformOffY)

            val buf = batcher.vertexBuffer
            buf.position(0)
            val stride = GpuShapeBatcher.STRIDE_BYTES

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shapeVboId)
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * stride, buf)

            val aPos = GLES20.glGetAttribLocation(shapeProgramId, "aPos")
            val aUV = GLES20.glGetAttribLocation(shapeProgramId, "aUV")
            val aCol = GLES20.glGetAttribLocation(shapeProgramId, "aColor")
            val aFea = GLES20.glGetAttribLocation(shapeProgramId, "aFeather")

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(aUV)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, stride, 8)
            GLES20.glEnableVertexAttribArray(aCol)
            GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 16)
            GLES20.glEnableVertexAttribArray(aFea)
            GLES20.glVertexAttribPointer(aFea, 1, GLES20.GL_FLOAT, false, stride, 32)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUV)
            GLES20.glDisableVertexAttribArray(aCol)
            GLES20.glDisableVertexAttribArray(aFea)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        fun endGpuFrame(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun saveTiffToPublicStorage(context: Context, srcFile: File, fileName: String): String {
        var savedLoc = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val dlValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/tiff")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SketchBookOSS")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val dlUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                if (dlUri != null) {
                    resolver.openOutputStream(dlUri)?.use { out ->
                        FileInputStream(srcFile).use { inp -> inp.copyTo(out) }
                    }
                    dlValues.clear()
                    dlValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(dlUri, dlValues, null, null)
                    savedLoc = "Download/SketchBookOSS/$fileName"
                }
            } catch (_: Exception) {}

            try {
                val resolver = context.contentResolver
                val picValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/tiff")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SketchBookOSS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val picUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, picValues)
                if (picUri != null) {
                    resolver.openOutputStream(picUri)?.use { out ->
                        FileInputStream(srcFile).use { inp -> inp.copyTo(out) }
                    }
                    picValues.clear()
                    picValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(picUri, picValues, null, null)
                    if (savedLoc.isEmpty()) savedLoc = "Pictures/SketchBookOSS/$fileName"
                }
            } catch (_: Exception) {}
            if (savedLoc.isNotEmpty()) return savedLoc
        }

        try {
            val pubDl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SketchBookOSS")
            if (!pubDl.exists()) pubDl.mkdirs()
            val dlFile = File(pubDl, fileName)
            FileInputStream(srcFile).use { inp -> FileOutputStream(dlFile).use { out -> inp.copyTo(out) } }
            savedLoc = "Download/SketchBookOSS/$fileName"
        } catch (_: Exception) {}

        try {
            val pubPic = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SketchBookOSS")
            if (!pubPic.exists()) pubPic.mkdirs()
            val picFile = File(pubPic, fileName)
            FileInputStream(srcFile).use { inp -> FileOutputStream(picFile).use { out -> inp.copyTo(out) } }
            if (savedLoc.isEmpty()) savedLoc = "Pictures/SketchBookOSS/$fileName"
        } catch (_: Exception) {}

        if (savedLoc.isNotEmpty()) return savedLoc
        val fbDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "SketchBookOSS")
        if (!fbDir.exists()) fbDir.mkdirs()
        val fbFile = File(fbDir, fileName)
        FileInputStream(srcFile).use { inp -> FileOutputStream(fbFile).use { out -> inp.copyTo(out) } }
        return fbFile.absolutePath
    }

    private fun saveImageToMediaStoreOrDisk(
        context: Context,
        bmp: Bitmap,
        baseName: String,
        ext: String,
        mimeType: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): String {
        val fileName = "$baseName.$ext"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SketchBookOSS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    BufferedOutputStream(out, 131072).use { bos ->
                        bmp.compress(format, quality, bos)
                    }
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
            BufferedOutputStream(FileOutputStream(outFile), 131072).use { out ->
                bmp.compress(format, quality, out)
            }
            outFile.absolutePath
        } catch (_: Exception) {
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "SketchBookOSS")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fbFile = File(fallbackDir, fileName)
            BufferedOutputStream(FileOutputStream(fbFile), 131072).use { out ->
                bmp.compress(format, quality, out)
            }
            fbFile.absolutePath
        }
    }

    private fun saveRawFileToPictures(context: Context, srcFile: File, fileName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SketchBookOSS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(srcFile).use { inp -> inp.copyTo(out) }
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
            FileInputStream(srcFile).use { inp ->
                FileOutputStream(outFile).use { out -> inp.copyTo(out) }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            val fbDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "SketchBookOSS")
            if (!fbDir.exists()) fbDir.mkdirs()
            val fbFile = File(fbDir, fileName)
            FileInputStream(srcFile).use { inp ->
                FileOutputStream(fbFile).use { out -> inp.copyTo(out) }
            }
            fbFile.absolutePath
        }
    }

    private fun saveVideoToMediaStoreOrDisk(context: Context, srcFile: File, fileName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SketchBookOSS")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(srcFile).use { inp -> inp.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Movies/SketchBookOSS/$fileName"
            }
        }
        val pubMov = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SketchBookOSS")
        if (!pubMov.exists()) pubMov.mkdirs()
        val outFile = File(pubMov, fileName)
        return try {
            FileInputStream(srcFile).use { inp ->
                FileOutputStream(outFile).use { out -> inp.copyTo(out) }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            val fbDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "SketchBookOSS")
            if (!fbDir.exists()) fbDir.mkdirs()
            val fbFile = File(fbDir, fileName)
            FileInputStream(srcFile).use { inp ->
                FileOutputStream(fbFile).use { out -> inp.copyTo(out) }
            }
            fbFile.absolutePath
        }
    }

    fun writeSketchbookTiff(bmp: Bitmap, out: OutputStream, logicalW: Int, logicalH: Int) {
        val w = bmp.width
        val h = bmp.height
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

        val bos = BufferedOutputStream(out, 262144)
        bos.write(headerBuf.array())

        val chunkRows = min(64, h).coerceAtLeast(1)
        val chunkPixels = IntArray(w * chunkRows)
        val chunkBytes = ByteArray(w * chunkRows * 4)
        var y = 0
        while (y < h) {
            val rows = min(chunkRows, h - y)
            bmp.getPixels(chunkPixels, 0, w, 0, y, w, rows)
            val totalPx = w * rows
            var bPos = 0
            for (i in 0 until totalPx) {
                val c = chunkPixels[i]
                chunkBytes[bPos++] = ((c shr 16) and 0xFF).toByte()
                chunkBytes[bPos++] = ((c shr 8) and 0xFF).toByte()
                chunkBytes[bPos++] = (c and 0xFF).toByte()
                chunkBytes[bPos++] = ((c shr 24) and 0xFF).toByte()
            }
            bos.write(chunkBytes, 0, totalPx * 4)
            y += rows
        }
        bos.flush()
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifEmpty { "Untitled-1" }
    }
}
