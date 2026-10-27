package com.sketchbook.oss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object UnifiedBrushRenderer {
    val shaderVM = BrushShaderVM()
    val previewRadiiHistory = mutableListOf<Float>()
    val canvasRadiiHistory = mutableListOf<Float>()
    var livePhysicsAgents: MutableList<LivePhysicsAgent>? = null

    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val strokeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dstOutXfer = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val clearXfer = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    /**
     * Renders a single dab at (x, y) with the exact parameters of [brush].
     * Used identically by both the Pinned Brush Preview and the live Canvas Stroke!
     * When [isTransparentColor] is true, uses DST_OUT so the exact brush shape,
     * opacity, airbrush softness, grain, and quirks act as an eraser!
     */
    fun renderSingleDab(
        canvas: Canvas,
        brush: SketchBrushItem,
        x: Float,
        y: Float,
        tangentDeg: Float,
        pressureNorm: Float,
        baseColor: Int,
        isTransparentColor: Boolean,
        dabIndex: Int,
        deterministicRandom: Boolean,
        procHistory: MutableList<PointF>?,
        smudgeBitmap: Bitmap?,
        smudgeCarryColorHolder: IntArray?
    ) {
        val pClamped = pressureNorm.coerceIn(0.05f, 1.0f)
        val sizePressScale = (brush.sizeLightPressure + (brush.sizeHeavyPressure - brush.sizeLightPressure) * pClamped) / 100f
        val opacPressScale = (brush.opacityLightPressure + (brush.opacityHeavyPressure - brush.opacityLightPressure) * pClamped) / 100f

        var rad = (brush.size * 0.5f * sizePressScale.coerceIn(0.10f, 1.5f)).coerceAtLeast(1.0f)
        var dabAlpha = ((brush.opacity / 100f) * opacPressScale.coerceIn(0.05f, 1.0f) * 255f).roundToInt().coerceIn(2, 255)

        // Deterministic PRNG for preview (so preview doesn't flicker) vs live PRNG on canvas
        fun nextRand01(salt: Int): Float {
            return if (deterministicRandom) {
                val h = ((dabIndex * 73856093) xor (salt * 19349663)) and 0x7FFFFFFF
                (h % 10000) / 10000f
            } else {
                Random.nextFloat()
            }
        }

        var drawX = x
        var drawY = y

        // 1. Randomness -> Scatter, Size Jitter, Opacity Jitter
        if (brush.scatterJitter > 0.5f) {
            val maxScatter = rad * (brush.scatterJitter / 38f)
            val ang = nextRand01(1) * (Math.PI.toFloat() * 2f)
            val dist = (nextRand01(2) - 0.5f) * 2f * maxScatter
            drawX += cos(ang) * dist
            drawY += sin(ang) * dist
        }
        if (brush.sizeJitter > 0.5f) {
            val sFactor = 1f - nextRand01(3) * (brush.sizeJitter / 105f)
            rad = (rad * sFactor).coerceAtLeast(1.0f)
        }
        if (brush.opacityJitter > 0.5f) {
            val oFactor = 1f - nextRand01(4) * (brush.opacityJitter / 105f)
            dabAlpha = (dabAlpha * oFactor).roundToInt().coerceIn(2, 255)
        }

        // Apply per-dab spacing density compensation so continuous low-spacing strokes don't over-saturate
        val spacingNorm = (brush.spacing / 100f).coerceIn(0.02f, 1f)
        val flowScale = if (brush.brushType == SketchBrushType.MARKER) {
            0.32f
        } else if (brush.hardness < 75f) {
            (0.22f + spacingNorm * 0.65f).coerceIn(0.18f, 1f)
        } else {
            1f
        }
        val effectiveAlpha = (dabAlpha * flowScale).roundToInt().coerceIn(2, 255)

        val isEraseMode = (brush.brushType == SketchBrushType.ERASER || isTransparentColor)
        val activeXfer = if (isEraseMode) dstOutXfer else null

        val cr = if (isEraseMode) 0 else Color.red(baseColor)
        val cg = if (isEraseMode) 0 else Color.green(baseColor)
        val cb = if (isEraseMode) 0 else Color.blue(baseColor)

        
        // Programmable Brush Shader VM Execution (when `code` parameter is defined in modParams!)
        val vmCode = brush.modParams["code"]
        if (!vmCode.isNullOrBlank()) {
            val radAngle = Math.toRadians(tangentDeg.toDouble())
            val tx = cos(radAngle).toFloat()
            val ty = sin(radAngle).toFloat()
            val nx = -ty
            val ny = tx
            val histList = procHistory ?: mutableListOf()
            val radList = if (deterministicRandom) previewRadiiHistory else canvasRadiiHistory
            if (dabIndex == 0) {
                histList.clear()
                radList.clear()
                shaderVM.resetMemory()
            }
            histList.add(PointF(drawX, drawY))
            radList.add(rad)
            if (histList.size > 180) {
                histList.removeAt(0)
                if (radList.isNotEmpty()) radList.removeAt(0)
            }
            val hsl = ColorStudioState.colorToHsl(baseColor)
            val timeNow = (System.nanoTime() / 1_000_000_000.0).toFloat()
            shaderVM.executeDabProgram(
                code = vmCode,
                canvas = canvas,
                x = drawX,
                y = drawY,
                nx = nx,
                ny = ny,
                tx = tx,
                ty = ty,
                tangentDeg = tangentDeg,
                radius = rad,
                pressure = pClamped,
                speed = 1f,
                dabIndex = dabIndex,
                timeSec = timeNow,
                baseHue = hsl[0],
                baseSat = hsl[1],
                baseLit = hsl[2],
                baseAlpha = (dabAlpha / 255f).coerceIn(0.02f, 1f),
                isErase = isEraseMode,
                deterministicRandom = deterministicRandom,
                historyPts = histList,
                historyRadii = radList,
                canvasBitmap = smudgeBitmap,
                activeAgentsOut = if (deterministicRandom) null else livePhysicsAgents,
                fillPaint = dabPaint,
                strokePaint = strokeLinePaint,
                dstOutXfer = dstOutXfer
            )
            return
        }

        // 2. Mod Mode: Procedural Web
        if (brush.modMode == "procedural_web") {
            val reach = (brush.modParams["reach"]?.toFloatOrNull() ?: 135f) * (brush.size / 38f).coerceIn(0.35f, 3f)
            val branches = (brush.modParams["branches"]?.toIntOrNull() ?: 3).coerceIn(1, 8)
            if (procHistory != null) {
                if (procHistory.isEmpty() || hypot(drawX - procHistory.last().x, drawY - procHistory.last().y) >= 4f) {
                    procHistory.add(PointF(drawX, drawY))
                    if (procHistory.size > 140) procHistory.removeAt(0)
                }
                strokeLinePaint.xfermode = activeXfer
                strokeLinePaint.shader = null
                strokeLinePaint.strokeWidth = (rad * 0.14f).coerceAtLeast(1.3f)
                for (hIdx in max(0, procHistory.size - 40) until procHistory.size - 1) {
                    val hp = procHistory[hIdx]
                    val d = hypot(drawX - hp.x, drawY - hp.y)
                    if (d in 6f..reach) {
                        val falloff = 1f - d / reach
                        val webA = (dabAlpha * falloff * falloff * 0.52f).toInt().coerceIn(4, 200)
                        strokeLinePaint.color = Color.argb(webA, cr, cg, cb)
                        canvas.drawLine(drawX, drawY, hp.x, hp.y, strokeLinePaint)
                    }
                }
            }
            if (dabIndex % 2 == 0) {
                strokeLinePaint.xfermode = activeXfer
                strokeLinePaint.strokeWidth = (rad * 0.12f).coerceAtLeast(1.2f)
                for (b in 0 until branches) {
                    val ang = nextRand01(10 + b) * (Math.PI.toFloat() * 2f)
                    val bLen = rad * (0.7f + nextRand01(20 + b) * 1.3f)
                    val mx = drawX + cos(ang) * bLen * 0.5f + (nextRand01(30 + b) - 0.5f) * rad * 0.45f
                    val my = drawY + sin(ang) * bLen * 0.5f + (nextRand01(40 + b) - 0.5f) * rad * 0.45f
                    val ex = drawX + cos(ang) * bLen
                    val ey = drawY + sin(ang) * bLen
                    strokeLinePaint.color = Color.argb((dabAlpha * 0.6f).toInt().coerceIn(8, 220), cr, cg, cb)
                    canvas.drawLine(drawX, drawY, mx, my, strokeLinePaint)
                    canvas.drawLine(mx, my, ex, ey, strokeLinePaint)
                }
                strokeLinePaint.xfermode = null
            }
            dabPaint.xfermode = activeXfer
            dabPaint.shader = null
            dabPaint.color = Color.argb(dabAlpha, cr, cg, cb)
            canvas.drawCircle(drawX, drawY, (rad * 0.25f).coerceAtLeast(1.4f), dabPaint)
            dabPaint.xfermode = null
            return
        }

        // 3. Mod Mode: Custom Harmonic Lotus Nib (3-zone varying opacity & smoothness)
        if (brush.modMode == "custom_harmonic_nib") {
            val lobes = (brush.modParams["lobes"]?.toIntOrNull() ?: 5).coerceIn(3, 12)
            val coreOpac = (brush.modParams["core_opac"]?.toFloatOrNull() ?: 96f) / 100f
            val petalOpac = (brush.modParams["petal_opac"]?.toFloatOrNull() ?: 36f) / 100f
            val featherSmooth = (brush.modParams["feather_smooth"]?.toFloatOrNull() ?: 88f) / 100f

            dabPaint.xfermode = activeXfer
            val outerR = rad * (1.2f + featherSmooth * 0.65f)
            dabPaint.color = Color.WHITE
            dabPaint.shader = RadialGradient(
                drawX, drawY, outerR.coerceAtLeast(2f),
                intArrayOf(
                    Color.argb((dabAlpha * 0.22f).toInt().coerceIn(4, 90), cr, cg, cb),
                    Color.argb((dabAlpha * 0.08f).toInt().coerceIn(2, 40), cr, cg, cb),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, (1f - featherSmooth * 0.5f).coerceIn(0.25f, 0.85f), 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(drawX, drawY, outerR, dabPaint)
            dabPaint.shader = null

            canvas.save()
            canvas.translate(drawX, drawY)
            canvas.rotate(tangentDeg + brush.rotation)
            val petalA = (dabAlpha * petalOpac).toInt().coerceIn(6, 200)
            dabPaint.color = Color.argb(petalA, cr, cg, cb)
            val roundRatio = (brush.roundness / 100f).coerceIn(0.18f, 1f)
            for (L in 0 until lobes) {
                val aRad = L * 2.0 * Math.PI / lobes
                val px = cos(aRad).toFloat() * rad * 0.66f
                val py = sin(aRad).toFloat() * rad * 0.66f
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate(Math.toDegrees(aRad).toFloat())
                canvas.drawOval(RectF(-rad * 0.46f, -rad * 0.22f * roundRatio, rad * 0.46f, rad * 0.22f * roundRatio), dabPaint)
                canvas.restore()
            }

            val coreA = (dabAlpha * coreOpac).toInt().coerceIn(12, 255)
            dabPaint.color = Color.argb(coreA, cr, cg, cb)
            canvas.drawOval(RectF(-rad * 0.40f, -rad * 0.18f * roundRatio, rad * 0.40f, rad * 0.18f * roundRatio), dabPaint)
            canvas.restore()
            dabPaint.xfermode = null
            return
        }

        // 4. Standard / Marker / Smudge / Glow / Eraser with full Nib Hardness, Grain & Stamp Roundness/Rotation
        when (brush.brushType) {
            SketchBrushType.SMUDGE -> {
                var sCol = baseColor
                if (smudgeBitmap != null && smudgeCarryColorHolder != null) {
                    val sxInt = drawX.toInt().coerceIn(0, smudgeBitmap.width - 1)
                    val syInt = drawY.toInt().coerceIn(0, smudgeBitmap.height - 1)
                    val sampled = smudgeBitmap.getPixel(sxInt, syInt)
                    val prev = smudgeCarryColorHolder[0]
                    if (Color.alpha(sampled) > 10) {
                        val mix = 0.30f
                        val mr = (Color.red(prev) * (1f - mix) + Color.red(sampled) * mix).toInt()
                        val mg = (Color.green(prev) * (1f - mix) + Color.green(sampled) * mix).toInt()
                        val mb = (Color.blue(prev) * (1f - mix) + Color.blue(sampled) * mix).toInt()
                        smudgeCarryColorHolder[0] = Color.rgb(mr, mg, mb)
                    }
                    sCol = smudgeCarryColorHolder[0]
                }
                val sr = if (isEraseMode) 0 else Color.red(sCol)
                val sg = if (isEraseMode) 0 else Color.green(sCol)
                val sb = if (isEraseMode) 0 else Color.blue(sCol)

                dabPaint.xfermode = activeXfer
                val smAlpha = (dabAlpha * 0.55f).toInt().coerceIn(8, 190)
                dabPaint.color = Color.WHITE
            dabPaint.shader = RadialGradient(
                    drawX, drawY, rad.coerceAtLeast(2f),
                    intArrayOf(Color.argb(smAlpha, sr, sg, sb), Color.argb(smAlpha / 2, sr, sg, sb), Color.TRANSPARENT),
                    floatArrayOf(0f, (brush.hardness / 100f).coerceIn(0.15f, 0.85f), 1f),
                    Shader.TileMode.CLAMP
                )
                drawTransformedStampShape(canvas, drawX, drawY, rad, brush)
                dabPaint.shader = null
                dabPaint.xfermode = null
            }

            SketchBrushType.GLOW -> {
                dabPaint.xfermode = activeXfer
                val outerR = rad * 1.9f
                dabPaint.color = Color.WHITE
            dabPaint.shader = RadialGradient(
                    drawX, drawY, outerR.coerceAtLeast(2f),
                    intArrayOf(
                        Color.argb(dabAlpha, if (isEraseMode) 0 else 255, if (isEraseMode) 0 else 255, if (isEraseMode) 0 else 255),
                        Color.argb((dabAlpha * 0.45f).toInt().coerceIn(8, 160), cr, cg, cb),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(drawX, drawY, outerR, dabPaint)
                dabPaint.shader = null
                dabPaint.xfermode = null
            }

            else -> {
                // STANDARD, MARKER, and ERASER:
                // Respects Hardness (soft airbrush gradient vs crisp edge), Roundness, Rotation, and Texture Grain!
                dabPaint.xfermode = activeXfer
                val hardNorm = (brush.hardness / 100f).coerceIn(0.02f, 1.0f)

                if (hardNorm < 0.82f) {
                    // True Soft Airbrush / Feathered Nib RadialGradient!
                    val coreStop = (hardNorm * 0.75f).coerceIn(0.02f, 0.75f)
                    val midAlpha = (effectiveAlpha * (0.35f + hardNorm * 0.45f)).toInt().coerceIn(2, 240)
                    dabPaint.color = Color.WHITE
            dabPaint.shader = RadialGradient(
                        0f, 0f, rad.coerceAtLeast(1.5f),
                        intArrayOf(
                            Color.argb(effectiveAlpha, cr, cg, cb),
                            Color.argb(midAlpha, cr, cg, cb),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, coreStop, 1f),
                        Shader.TileMode.CLAMP
                    )
                } else {
                    dabPaint.shader = null
                    dabPaint.color = Color.argb(effectiveAlpha, cr, cg, cb)
                }

                canvas.save()
                canvas.translate(drawX, drawY)
                canvas.rotate(brush.rotation)
                val ry = rad * (brush.roundness / 100f).coerceIn(0.12f, 1.0f)
                if (brush.roundness < 96f) {
                    canvas.scale(1f, (brush.roundness / 100f).coerceIn(0.12f, 1.0f))
                    canvas.drawCircle(0f, 0f, rad, dabPaint)
                } else {
                    canvas.drawCircle(0f, 0f, rad, dabPaint)
                }

                // If Nib -> Texture Grain > 0, overlay deterministic paper grain micro-stipples inside the dab
                if (brush.grainStrength > 5f && rad >= 3f) {
                    val grainFactor = (brush.grainStrength / 100f).coerceIn(0.05f, 1f)
                    dabPaint.shader = null
                    dabPaint.xfermode = dstOutXfer
                    val carveAlpha = (grainFactor * 165f).toInt().coerceIn(10, 185)
                    dabPaint.color = Color.argb(carveAlpha, 0, 0, 0)
                    val specks = (4 + (grainFactor * 10f).toInt()).coerceIn(4, 16)
                    for (g in 0 until specks) {
                        val ga = nextRand01(100 + g) * (Math.PI.toFloat() * 2f)
                        val gd = nextRand01(200 + g) * rad * 0.82f
                        val gx = cos(ga) * gd
                        val gy = sin(ga) * gd * (ry / rad)
                        val gr = (rad * (0.10f + 0.12f * nextRand01(300 + g))).coerceAtLeast(0.8f)
                        canvas.drawCircle(gx, gy, gr, dabPaint)
                    }
                }
                canvas.restore()
                dabPaint.shader = null
                dabPaint.xfermode = null
            }
        }
    }

    private fun drawTransformedStampShape(canvas: Canvas, x: Float, y: Float, rad: Float, brush: SketchBrushItem) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(brush.rotation)
        val ry = rad * (brush.roundness / 100f).coerceIn(0.12f, 1f)
        canvas.drawOval(RectF(-rad, -ry, rad, ry), dabPaint)
        canvas.restore()
    }

    /**
     * Thread-safe renderer for persistent 60fps animated brush strokes (both VM-based `vm_anim`
     * and procedural `time_animated` strokes). Used by live canvas drawing, static 1:1 PNG/TIFF
     * saving, and high-bitrate MP4/GIF/WebM frame encoding.
     */
    fun renderTemporalStrokes(
        canvas: Canvas,
        temporalStrokes: List<TemporalAnimatedStroke>,
        canvasBitmap: Bitmap?,
        timeSec: Float,
        vmInstance: BrushShaderVM = shaderVM
    ) {
        if (temporalStrokes.isEmpty()) return
        val localFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val localStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val localDstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

        for (ts in temporalStrokes) {
            val nCount = ts.nodes.size
            if (nCount == 0) continue
            val baseHsl = ColorStudioState.colorToHsl(ts.color)

            if (ts.vmCode.isNotEmpty()) {
                val histPts = ArrayList<PointF>(nCount)
                val histRads = ArrayList<Float>(nCount)
                vmInstance.resetMemory()
                for (i in 0 until nCount) {
                    val nd = ts.nodes[i]
                    histPts.add(PointF(nd.x, nd.y))
                    histRads.add(nd.radius)
                    vmInstance.executeDabProgram(
                        code = ts.vmCode,
                        canvas = canvas,
                        x = nd.x,
                        y = nd.y,
                        nx = nd.nx,
                        ny = nd.ny,
                        tx = nd.tx,
                        ty = nd.ty,
                        tangentDeg = nd.tangentDeg,
                        radius = nd.radius,
                        pressure = nd.pressure,
                        speed = 1f,
                        dabIndex = nd.dabIndex,
                        timeSec = timeSec,
                        baseHue = baseHsl[0],
                        baseSat = baseHsl[1],
                        baseLit = baseHsl[2],
                        baseAlpha = (nd.alpha / 255f).coerceIn(0.02f, 1f),
                        isErase = ts.isErase,
                        deterministicRandom = true,
                        historyPts = histPts,
                        historyRadii = histRads,
                        canvasBitmap = canvasBitmap,
                        activeAgentsOut = null,
                        fillPaint = localFillPaint,
                        strokePaint = localStrokePaint,
                        dstOutXfer = localDstOut
                    )
                }
                continue
            }

            for (i in 0 until nCount) {
                val nd = ts.nodes[i]
                val wave = sin(timeSec * ts.speed + nd.phase) * ts.waveAmp
                val animX = nd.x + nd.nx * wave
                val animY = nd.y + nd.ny * wave
                val pulse = 0.78f + 0.28f * sin(timeSec * (ts.speed * 1.3f) + nd.phase * 1.7f)
                val curRad = (nd.radius * pulse).coerceAtLeast(1.5f)

                val shiftedHue = (baseHsl[0] + sin(timeSec * 2f + nd.phase) * ts.hueShift + 360f) % 360f
                val glowCol = ColorStudioState.hslToColor(shiftedHue, baseHsl[1].coerceAtLeast(0.75f), baseHsl[2].coerceIn(0.35f, 0.7f))
                val gr = Color.red(glowCol); val gg = Color.green(glowCol); val gb = Color.blue(glowCol)

                localFillPaint.color = Color.WHITE
                localFillPaint.shader = RadialGradient(
                    animX, animY, curRad * 2.2f,
                    intArrayOf(
                        Color.argb((nd.alpha * 0.42f).toInt().coerceIn(8, 160), gr, gg, gb),
                        Color.argb((nd.alpha * 0.14f).toInt().coerceIn(4, 70), gr, gg, gb),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(animX, animY, curRad * 2.2f, localFillPaint)
                localFillPaint.shader = null

                localFillPaint.color = Color.argb(nd.alpha, 255, 255, 255)
                canvas.drawCircle(animX, animY, curRad * 0.42f, localFillPaint)
            }
        }
    }
}
