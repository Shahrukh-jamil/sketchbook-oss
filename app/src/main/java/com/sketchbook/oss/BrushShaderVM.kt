package com.sketchbook.oss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

data class LivePhysicsAgent(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    var remainingSec: Float,
    val totalSec: Float,
    val gravity: Float,
    val curlStrength: Float,
    val hue: Float,
    val sat: Float,
    val lit: Float,
    val alpha: Float,
    val isErase: Boolean
)

class BrushShaderVM {

    companion object {
        const val REG_X = 0
        const val REG_Y = 1
        const val REG_R = 2
        const val REG_P = 3
        const val REG_V = 4
        const val REG_I = 5
        const val REG_T = 6
        const val REG_ANG = 7
        const val REG_RAD = 8
        const val REG_TX = 9
        const val REG_TY = 10
        const val REG_NX = 11
        const val REG_NY = 12
        const val REG_H = 13
        const val REG_S = 14
        const val REG_L = 15
        const val REG_A = 16
        const val REG_PI = 17
        const val REG_HCOUNT = 18
        const val FIRST_USER_REG = 19
        const val MAX_REGS = 64

        const val OP_ADD = 1
        const val OP_SUB = 2
        const val OP_MUL = 3
        const val OP_DIV = 4
        const val OP_MOD = 5
        const val OP_LT = 6
        const val OP_GT = 7
        const val OP_LE = 8
        const val OP_GE = 9
        const val OP_EQ = 10
        const val OP_NE = 11
        const val OP_AND = 12
        const val OP_OR = 13

        const val FN_SIN = 1
        const val FN_COS = 2
        const val FN_TAN = 3
        const val FN_ATAN2 = 4
        const val FN_SQRT = 5
        const val FN_POW = 6
        const val FN_ABS = 7
        const val FN_MIN = 8
        const val FN_MAX = 9
        const val FN_FLOOR = 10
        const val FN_FRACT = 11
        const val FN_MOD = 12
        const val FN_CLAMP = 13
        const val FN_MIX = 14
        const val FN_NOISE = 15
        const val FN_RAND = 16
        const val FN_SRND = 17
        const val FN_MEM = 18
        const val FN_HX = 19
        const val FN_HY = 20
        const val FN_PICK_H = 21
        const val FN_PICK_S = 22
        const val FN_PICK_L = 23
        const val FN_PICK_A = 24

        const val CMD_SET_POS = 1
        const val CMD_CIRCLE = 2
        const val CMD_OVAL = 3
        const val CMD_LINE = 4
        const val CMD_HLINE = 5
        const val CMD_RIBBON = 6
        const val CMD_POLY = 7
        const val CMD_ARC = 8
        const val CMD_DRIP = 9

        private val sharedCompiledCache = ConcurrentHashMap<String, CompiledProgram>()
    }

    class CompiledProgram(val stmts: Array<Stmt>)

    sealed class Stmt {
        class AssignReg(val slot: Int, val expr: Expr) : Stmt()
        class AssignMem(val idxExpr: Expr, val valExpr: Expr) : Stmt()
        class ForLoop(val slot: Int, val start: Expr, val end: Expr, val step: Expr, val body: Array<Stmt>) : Stmt()
        class IfBranch(val cond: Expr, val body: Array<Stmt>, val elseBody: Array<Stmt>?) : Stmt()
        class Command(val cmdCode: Int, val args: Array<Expr>) : Stmt()
    }

    sealed class Expr {
        class Num(val v: Float) : Expr()
        class Reg(val slot: Int) : Expr()
        class UnaryNeg(val inner: Expr) : Expr()
        class BinOp(val op: Int, val left: Expr, val right: Expr) : Expr()
        class Call(val fn: Int, val a0: Expr?, val a1: Expr?, val a2: Expr?) : Expr()
    }

    val memory = FloatArray(64)
    private val regs = FloatArray(MAX_REGS)
    private val cmdArgs = FloatArray(16)
    private val sampleHslScratch = FloatArray(3)

    var primHistX = FloatArray(512)
    var primHistY = FloatArray(512)
    var primHistR = FloatArray(512)
    var primHistCount = 0

    private val reusablePath = Path()
    private val reusableRect = RectF()

    val localFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val localStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val localDstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    fun resetMemory() {
        memory.fill(0f)
    }

    fun beginStroke(expectedNodes: Int) {
        memory.fill(0f)
        primHistCount = 0
        if (expectedNodes > primHistX.size) {
            val newCap = max(expectedNodes + 64, primHistX.size * 2)
            primHistX = FloatArray(newCap)
            primHistY = FloatArray(newCap)
            primHistR = FloatArray(newCap)
        }
    }

    fun pushHistoryNode(x: Float, y: Float, r: Float) {
        if (primHistCount >= primHistX.size) {
            val newCap = primHistX.size * 2
            primHistX = primHistX.copyOf(newCap)
            primHistY = primHistY.copyOf(newCap)
            primHistR = primHistR.copyOf(newCap)
        }
        primHistX[primHistCount] = x
        primHistY[primHistCount] = y
        primHistR[primHistCount] = r
        primHistCount++
    }

    fun getCompiledProgram(code: String): CompiledProgram {
        return sharedCompiledCache.getOrPut(code) {
            try {
                CompiledProgram(Parser(tokenize(code)).parseProgram())
            } catch (_: Exception) {
                CompiledProgram(emptyArray())
            }
        }
    }

    private fun hash2D(ix: Int, iy: Int): Float {
        var n = ix * 374761393 + iy * 668265263
        n = (n xor (n shr 13)) * 1274126177
        return ((n xor (n shr 16)) and 0x7FFFFFFF) / 1073741824f - 1f
    }

    fun noise2D(x: Float, y: Float): Float {
        val flX = floor(x)
        val flY = floor(y)
        val ix = flX.toInt()
        val iy = flY.toInt()
        val fx = x - flX
        val fy = y - flY
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash2D(ix, iy)
        val b = hash2D(ix + 1, iy)
        val c = hash2D(ix, iy + 1)
        val d = hash2D(ix + 1, iy + 1)
        val ab = a + (b - a) * ux
        val cd = c + (d - c) * ux
        return ab + (cd - ab) * uy
    }

    fun executeDabProgram(
        code: String,
        canvas: Canvas,
        x: Float,
        y: Float,
        nx: Float,
        ny: Float,
        tx: Float,
        ty: Float,
        tangentDeg: Float,
        radius: Float,
        pressure: Float,
        speed: Float,
        dabIndex: Int,
        timeSec: Float,
        baseHue: Float,
        baseSat: Float,
        baseLit: Float,
        baseAlpha: Float,
        isErase: Boolean,
        deterministicRandom: Boolean,
        historyPts: MutableList<PointF>,
        historyRadii: MutableList<Float>,
        canvasBitmap: Bitmap?,
        activeAgentsOut: MutableList<LivePhysicsAgent>?,
        fillPaint: Paint,
        strokePaint: Paint,
        dstOutXfer: PorterDuffXfermode
    ) {
        val program = getCompiledProgram(code)
        if (program.stmts.isEmpty()) return

        val hSize = historyPts.size
        if (hSize > primHistX.size) {
            val newCap = max(hSize + 64, primHistX.size * 2)
            primHistX = FloatArray(newCap)
            primHistY = FloatArray(newCap)
            primHistR = FloatArray(newCap)
        }
        for (k in 0 until hSize) {
            val pt = historyPts[k]
            primHistX[k] = pt.x
            primHistY[k] = pt.y
            primHistR[k] = if (k < historyRadii.size) historyRadii[k] else radius
        }
        primHistCount = hSize

        executeCompiledNode(
            program = program,
            canvas = canvas,
            x = x,
            y = y,
            nx = nx,
            ny = ny,
            tx = tx,
            ty = ty,
            tangentDeg = tangentDeg,
            radius = radius,
            pressure = pressure,
            speed = speed,
            dabIndex = dabIndex,
            timeSec = timeSec,
            baseHue = baseHue,
            baseSat = baseSat,
            baseLit = baseLit,
            baseAlpha = baseAlpha,
            isErase = isErase,
            deterministicRandom = deterministicRandom,
            syncHistoryPts = historyPts,
            canvasBitmap = canvasBitmap,
            activeAgentsOut = activeAgentsOut,
            fillPaint = fillPaint,
            strokePaint = strokePaint,
            dstOutXfer = dstOutXfer
        )
    }

    fun executeCompiledNode(
        program: CompiledProgram,
        canvas: Canvas,
        x: Float,
        y: Float,
        nx: Float,
        ny: Float,
        tx: Float,
        ty: Float,
        tangentDeg: Float,
        radius: Float,
        pressure: Float,
        speed: Float,
        dabIndex: Int,
        timeSec: Float,
        baseHue: Float,
        baseSat: Float,
        baseLit: Float,
        baseAlpha: Float,
        isErase: Boolean,
        deterministicRandom: Boolean,
        syncHistoryPts: MutableList<PointF>?,
        canvasBitmap: Bitmap?,
        activeAgentsOut: MutableList<LivePhysicsAgent>?,
        fillPaint: Paint,
        strokePaint: Paint,
        dstOutXfer: PorterDuffXfermode
    ) {
        val stmts = program.stmts
        if (stmts.isEmpty()) return

        var curX = x
        var curY = y

        regs[REG_X] = curX
        regs[REG_Y] = curY
        regs[REG_R] = radius
        regs[REG_P] = pressure
        regs[REG_V] = speed
        regs[REG_I] = dabIndex.toFloat()
        regs[REG_T] = timeSec
        regs[REG_ANG] = tangentDeg
        regs[REG_RAD] = tangentDeg * 0.017453292f
        regs[REG_TX] = tx
        regs[REG_TY] = ty
        regs[REG_NX] = nx
        regs[REG_NY] = ny
        regs[REG_H] = baseHue
        regs[REG_S] = baseSat
        regs[REG_L] = baseLit
        regs[REG_A] = baseAlpha
        regs[REG_PI] = 3.1415927f
        regs[REG_HCOUNT] = primHistCount.toFloat()

        var randSalt = 1
        fun nextRand01(): Float {
            randSalt++
            return if (deterministicRandom) {
                val h = ((dabIndex * 73856093) xor (randSalt * 19349663)) and 0x7FFFFFFF
                (h % 10000) * 0.0001f
            } else {
                Random.nextFloat()
            }
        }

        fun sampleCanvasHslA(dx: Float, dy: Float, comp: Int): Float {
            if (canvasBitmap == null) {
                return when (comp) {
                    0 -> baseHue
                    1 -> baseSat
                    2 -> baseLit
                    else -> 1f
                }
            }
            val sx = (curX + dx).toInt().coerceIn(0, canvasBitmap.width - 1)
            val sy = (curY + dy).toInt().coerceIn(0, canvasBitmap.height - 1)
            val col = canvasBitmap.getPixel(sx, sy)
            val a = (col ushr 24) / 255f
            if (comp == 3) return a
            if (a < 0.05f) {
                return when (comp) {
                    0 -> baseHue
                    1 -> baseSat
                    else -> baseLit
                }
            }
            ColorStudioState.colorToHslOut(col, sampleHslScratch)
            return sampleHslScratch[comp]
        }

        fun eval(e: Expr): Float {
            return when (e) {
                is Expr.Num -> e.v
                is Expr.Reg -> regs[e.slot]
                is Expr.UnaryNeg -> -eval(e.inner)
                is Expr.BinOp -> {
                    val l = eval(e.left)
                    val r = eval(e.right)
                    when (e.op) {
                        OP_ADD -> l + r
                        OP_SUB -> l - r
                        OP_MUL -> l * r
                        OP_DIV -> if (abs(r) < 0.00001f) 0f else l / r
                        OP_MOD -> if (abs(r) < 0.00001f) 0f else l % r
                        OP_LT -> if (l < r) 1f else 0f
                        OP_GT -> if (l > r) 1f else 0f
                        OP_LE -> if (l <= r) 1f else 0f
                        OP_GE -> if (l >= r) 1f else 0f
                        OP_EQ -> if (abs(l - r) < 0.0001f) 1f else 0f
                        OP_NE -> if (abs(l - r) >= 0.0001f) 1f else 0f
                        OP_AND -> if (l != 0f && r != 0f) 1f else 0f
                        OP_OR -> if (l != 0f || r != 0f) 1f else 0f
                        else -> 0f
                    }
                }
                is Expr.Call -> {
                    val a0 = if (e.a0 != null) eval(e.a0) else 0f
                    val a1 = if (e.a1 != null) eval(e.a1) else 0f
                    val a2 = if (e.a2 != null) eval(e.a2) else 0f
                    when (e.fn) {
                        FN_SIN -> sin(a0)
                        FN_COS -> cos(a0)
                        FN_TAN -> tan(a0)
                        FN_ATAN2 -> atan2(a0, a1)
                        FN_SQRT -> sqrt(max(0f, a0))
                        FN_POW -> a0.pow(a1)
                        FN_ABS -> abs(a0)
                        FN_MIN -> min(a0, a1)
                        FN_MAX -> max(a0, a1)
                        FN_FLOOR -> floor(a0)
                        FN_FRACT -> a0 - floor(a0)
                        FN_MOD -> if (abs(a1) < 0.00001f) 0f else a0 % a1
                        FN_CLAMP -> {
                            val lo = min(a1, a2)
                            val hi = max(a1, a2)
                            if (a0 < lo) lo else if (a0 > hi) hi else a0
                        }
                        FN_MIX -> a0 + (a1 - a0) * a2.coerceIn(0f, 1f)
                        FN_NOISE -> noise2D(a0, a1)
                        FN_RAND -> nextRand01()
                        FN_SRND -> nextRand01() * 2f - 1f
                        FN_MEM -> memory[a0.toInt().coerceIn(0, 63)]
                        FN_HX -> {
                            val lag = a0.toInt().coerceAtLeast(1)
                            val idx = (primHistCount - 1 - lag).coerceIn(0, max(0, primHistCount - 1))
                            if (primHistCount > 0) primHistX[idx] else curX
                        }
                        FN_HY -> {
                            val lag = a0.toInt().coerceAtLeast(1)
                            val idx = (primHistCount - 1 - lag).coerceIn(0, max(0, primHistCount - 1))
                            if (primHistCount > 0) primHistY[idx] else curY
                        }
                        FN_PICK_H -> sampleCanvasHslA(a0, a1, 0)
                        FN_PICK_S -> sampleCanvasHslA(a0, a1, 1)
                        FN_PICK_L -> sampleCanvasHslA(a0, a1, 2)
                        FN_PICK_A -> sampleCanvasHslA(a0, a1, 3)
                        else -> 0f
                    }
                }
            }
        }

        fun resolveColor(h: Float, s: Float, l: Float): Int {
            if (isErase) return Color.BLACK
            var normH = h % 360f
            if (normH < 0f) normH += 360f
            return ColorStudioState.hslToColor(normH, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }

        val xfer = if (isErase) dstOutXfer else null
        var opsBudget = 900

        fun execStmts(block: Array<Stmt>) {
            for (idx in block.indices) {
                if (opsBudget-- <= 0) return
                when (val st = block[idx]) {
                    is Stmt.AssignReg -> regs[st.slot] = eval(st.expr)
                    is Stmt.AssignMem -> {
                        val mIdx = eval(st.idxExpr).toInt().coerceIn(0, 63)
                        memory[mIdx] = eval(st.valExpr)
                    }
                    is Stmt.IfBranch -> {
                        if (eval(st.cond) != 0f) {
                            execStmts(st.body)
                        } else if (st.elseBody != null) {
                            execStmts(st.elseBody)
                        }
                    }
                    is Stmt.ForLoop -> {
                        val startVal = eval(st.start)
                        val endVal = eval(st.end)
                        val stepVal = eval(st.step)
                        if (abs(stepVal) > 0.0001f) {
                            var cur = startVal
                            var guard = 0
                            val slot = st.slot
                            val body = st.body
                            while ((if (stepVal > 0f) cur <= endVal + 0.0001f else cur >= endVal - 0.0001f) && guard++ < 64 && opsBudget > 0) {
                                regs[slot] = cur
                                execStmts(body)
                                cur += stepVal
                            }
                        }
                    }
                    is Stmt.Command -> {
                        val exprs = st.args
                        val aLen = min(exprs.size, cmdArgs.size)
                        for (aIdx in 0 until aLen) {
                            cmdArgs[aIdx] = eval(exprs[aIdx])
                        }
                        fun arg(i: Int, def: Float = 0f): Float = if (i < aLen) cmdArgs[i] else def

                        when (st.cmdCode) {
                            CMD_SET_POS -> {
                                curX = x + arg(0)
                                curY = y + arg(1)
                                regs[REG_X] = curX
                                regs[REG_Y] = curY
                                if (primHistCount > 0) {
                                    primHistX[primHistCount - 1] = curX
                                    primHistY[primHistCount - 1] = curY
                                }
                                if (syncHistoryPts != null && syncHistoryPts.isNotEmpty()) {
                                    syncHistoryPts[syncHistoryPts.lastIndex].set(curX, curY)
                                }
                            }

                            CMD_CIRCLE -> {
                                val cx = curX + arg(0)
                                val cy = curY + arg(1)
                                val cr = max(0.8f, arg(2, radius))
                                val col = resolveColor(arg(3, baseHue), arg(4, baseSat), arg(5, baseLit))
                                val al = (arg(6, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val feather = arg(7, 0f).coerceIn(0f, 1f)
                                fillPaint.xfermode = xfer
                                fillPaint.shader = null
                                val rC = (col shr 16) and 0xFF
                                val gC = (col shr 8) and 0xFF
                                val bC = col and 0xFF
                                if (feather > 0.05f && cr >= 2.0f) {
                                    if (feather <= 0.22f) {
                                        fillPaint.color = Color.argb((al * 0.42f).toInt().coerceAtLeast(1), rC, gC, bC)
                                        canvas.drawCircle(cx, cy, cr, fillPaint)
                                        fillPaint.color = Color.argb((al * 0.86f).toInt().coerceAtLeast(1), rC, gC, bC)
                                        canvas.drawCircle(cx, cy, cr * (1f - feather * 0.65f), fillPaint)
                                    } else {
                                        fillPaint.color = Color.argb((al * 0.24f).toInt().coerceAtLeast(1), rC, gC, bC)
                                        canvas.drawCircle(cx, cy, cr, fillPaint)
                                        fillPaint.color = Color.argb((al * 0.50f).toInt().coerceAtLeast(1), rC, gC, bC)
                                        canvas.drawCircle(cx, cy, cr * (1f - feather * 0.36f), fillPaint)
                                        fillPaint.color = Color.argb((al * 0.86f).toInt().coerceAtLeast(1), rC, gC, bC)
                                        canvas.drawCircle(cx, cy, cr * (1f - feather * 0.72f), fillPaint)
                                    }
                                } else {
                                    fillPaint.color = Color.argb(al, rC, gC, bC)
                                    canvas.drawCircle(cx, cy, cr, fillPaint)
                                }
                                fillPaint.xfermode = null
                            }

                            CMD_OVAL -> {
                                val cx = curX + arg(0)
                                val cy = curY + arg(1)
                                val rx = max(0.8f, arg(2, radius))
                                val ry = max(0.8f, arg(3, radius * 0.5f))
                                val rot = arg(4, tangentDeg)
                                val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                                val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val feather = arg(9, 0f).coerceIn(0f, 1f)

                                fillPaint.xfermode = xfer
                                fillPaint.shader = null
                                val rC = (col shr 16) and 0xFF
                                val gC = (col shr 8) and 0xFF
                                val bC = col and 0xFF
                                canvas.save()
                                canvas.translate(cx, cy)
                                canvas.rotate(rot)
                                if (feather > 0.05f && rx >= 2.0f) {
                                    fillPaint.color = Color.argb((al * 0.36f).toInt().coerceAtLeast(1), rC, gC, bC)
                                    reusableRect.set(-rx, -ry, rx, ry)
                                    canvas.drawOval(reusableRect, fillPaint)
                                    val sc = (1f - feather * 0.65f).coerceIn(0.25f, 0.92f)
                                    fillPaint.color = Color.argb((al * 0.85f).toInt().coerceAtLeast(1), rC, gC, bC)
                                    reusableRect.set(-rx * sc, -ry * sc, rx * sc, ry * sc)
                                    canvas.drawOval(reusableRect, fillPaint)
                                } else {
                                    fillPaint.color = Color.argb(al, rC, gC, bC)
                                    reusableRect.set(-rx, -ry, rx, ry)
                                    canvas.drawOval(reusableRect, fillPaint)
                                }
                                canvas.restore()
                                fillPaint.xfermode = null
                            }

                            CMD_LINE -> {
                                val x1 = curX + arg(0)
                                val y1 = curY + arg(1)
                                val x2 = curX + arg(2)
                                val y2 = curY + arg(3)
                                val w = max(0.8f, arg(4, 2f))
                                val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                                val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                strokePaint.xfermode = xfer
                                strokePaint.shader = null
                                strokePaint.strokeWidth = w
                                strokePaint.color = (al shl 24) or (col and 0xFFFFFF)
                                canvas.drawLine(x1, y1, x2, y2, strokePaint)
                                strokePaint.xfermode = null
                            }

                            CMD_HLINE -> {
                                val lag = arg(0, 1f).toInt().coerceAtLeast(1)
                                if (primHistCount > lag) {
                                    val pIdx = (primHistCount - 1 - lag).coerceIn(0, primHistCount - 1)
                                    val x1 = primHistX[pIdx] + arg(1)
                                    val y1 = primHistY[pIdx] + arg(2)
                                    val x2 = curX + arg(3)
                                    val y2 = curY + arg(4)
                                    val w = max(0.8f, arg(5, 2f))
                                    val col = resolveColor(arg(6, baseHue), arg(7, baseSat), arg(8, baseLit))
                                    val al = (arg(9, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                    strokePaint.xfermode = xfer
                                    strokePaint.shader = null
                                    strokePaint.strokeWidth = w
                                    strokePaint.color = (al shl 24) or (col and 0xFFFFFF)
                                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                                    strokePaint.xfermode = null
                                }
                            }

                            CMD_RIBBON -> {
                                val lag = arg(0, 1f).toInt().coerceAtLeast(1)
                                if (primHistCount > lag) {
                                    val pIdx = (primHistCount - 1 - lag).coerceIn(0, primHistCount - 1)
                                    val prevX = primHistX[pIdx]
                                    val prevY = primHistY[pIdx]
                                    val prevR = primHistR[pIdx]
                                    val wScale = arg(1, 1f)
                                    val offScale = arg(2, 0f)
                                    val col = resolveColor(arg(3, baseHue), arg(4, baseSat), arg(5, baseLit))
                                    val al = (arg(6, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)

                                    val curOffX = nx * radius * offScale
                                    val curOffY = ny * radius * offScale
                                    val prvOffX = nx * prevR * offScale
                                    val prvOffY = ny * prevR * offScale

                                    val hwCur = radius * wScale * 0.5f
                                    val hwPrv = prevR * wScale * 0.5f

                                    reusablePath.rewind()
                                    reusablePath.moveTo(prevX + prvOffX - nx * hwPrv, prevY + prvOffY - ny * hwPrv)
                                    reusablePath.lineTo(prevX + prvOffX + nx * hwPrv, prevY + prvOffY + ny * hwPrv)
                                    reusablePath.lineTo(curX + curOffX + nx * hwCur, curY + curOffY + ny * hwCur)
                                    reusablePath.lineTo(curX + curOffX - nx * hwCur, curY + curOffY - ny * hwCur)
                                    reusablePath.close()

                                    fillPaint.xfermode = xfer
                                    fillPaint.shader = null
                                    fillPaint.color = (al shl 24) or (col and 0xFFFFFF)
                                    canvas.drawPath(reusablePath, fillPaint)
                                    fillPaint.xfermode = null
                                }
                            }

                            CMD_POLY -> {
                                val sides = arg(0, 5f).toInt().coerceIn(3, 24)
                                val cx = curX + arg(1)
                                val cy = curY + arg(2)
                                val pr = max(1f, arg(3, radius))
                                val rotRad = arg(4, tangentDeg) * 0.017453292f
                                val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                                val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val isFill = arg(9, 1f) >= 0.5f

                                reusablePath.rewind()
                                val stepAng = 6.2831855f / sides
                                for (k in 0 until sides) {
                                    val a = rotRad + k * stepAng
                                    val vx = cx + cos(a) * pr
                                    val vy = cy + sin(a) * pr
                                    if (k == 0) reusablePath.moveTo(vx, vy) else reusablePath.lineTo(vx, vy)
                                }
                                reusablePath.close()
                                val argb = (al shl 24) or (col and 0xFFFFFF)
                                if (isFill) {
                                    fillPaint.xfermode = xfer
                                    fillPaint.shader = null
                                    fillPaint.color = argb
                                    canvas.drawPath(reusablePath, fillPaint)
                                    fillPaint.xfermode = null
                                } else {
                                    strokePaint.xfermode = xfer
                                    strokePaint.strokeWidth = max(1.2f, radius * 0.12f)
                                    strokePaint.color = argb
                                    canvas.drawPath(reusablePath, strokePaint)
                                    strokePaint.xfermode = null
                                }
                            }

                            CMD_ARC -> {
                                val cx = curX + arg(0)
                                val cy = curY + arg(1)
                                val ar = max(1f, arg(2, radius))
                                val sDeg = arg(3, 0f)
                                val swDeg = arg(4, 180f)
                                val w = max(1f, arg(5, 3f))
                                val col = resolveColor(arg(6, baseHue), arg(7, baseSat), arg(8, baseLit))
                                val al = (arg(9, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                strokePaint.xfermode = xfer
                                strokePaint.strokeWidth = w
                                strokePaint.color = (al shl 24) or (col and 0xFFFFFF)
                                reusableRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
                                canvas.drawArc(reusableRect, sDeg, swDeg, false, strokePaint)
                                strokePaint.xfermode = null
                            }

                            CMD_DRIP -> {
                                if (activeAgentsOut != null && activeAgentsOut.size < 280) {
                                    val life = arg(5, 1.5f).coerceIn(0.1f, 8.0f)
                                    activeAgentsOut.add(
                                        LivePhysicsAgent(
                                            x = curX + arg(0),
                                            y = curY + arg(1),
                                            vx = arg(2),
                                            vy = arg(3),
                                            radius = max(1f, arg(4, radius * 0.25f)),
                                            remainingSec = life,
                                            totalSec = life,
                                            gravity = arg(6, 120f),
                                            curlStrength = arg(7, 0f),
                                            hue = arg(8, baseHue),
                                            sat = arg(9, baseSat),
                                            lit = arg(10, baseLit),
                                            alpha = arg(11, baseAlpha),
                                            isErase = isErase
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        execStmts(stmts)
    }

    private fun tokenize(src: String): List<String> {
        val tokens = ArrayList<String>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit()) -> {
                    val start = i
                    while (i < n && (src[i].isDigit() || src[i] == '.')) i++
                    tokens.add(src.substring(start, i))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    tokens.add(src.substring(start, i))
                }
                i + 1 < n && src.substring(i, i + 2) in listOf("<=", ">=", "==", "!=", "&&", "||") -> {
                    tokens.add(src.substring(i, i + 2))
                    i += 2
                }
                else -> {
                    tokens.add(c.toString())
                    i++
                }
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0
        private var nextUserSlot = FIRST_USER_REG
        private val regMap = HashMap<String, Int>(32).apply {
            put("x", REG_X); put("y", REG_Y); put("r", REG_R); put("p", REG_P)
            put("v", REG_V); put("i", REG_I); put("t", REG_T); put("ang", REG_ANG)
            put("rad", REG_RAD); put("tx", REG_TX); put("ty", REG_TY)
            put("nx", REG_NX); put("ny", REG_NY); put("h", REG_H); put("s", REG_S)
            put("l", REG_L); put("a", REG_A); put("pi", REG_PI); put("hcount", REG_HCOUNT)
        }

        private fun slotForVar(name: String): Int {
            return regMap.getOrPut(name) {
                val s = nextUserSlot.coerceAtMost(MAX_REGS - 1)
                if (nextUserSlot < MAX_REGS - 1) nextUserSlot++
                s
            }
        }

        private fun peek(offset: Int = 0): String? = tokens.getOrNull(pos + offset)
        private fun consume(): String = tokens[pos++]
        private fun match(expected: String): Boolean {
            if (peek() == expected) {
                pos++
                return true
            }
            return false
        }

        fun parseProgram(): Array<Stmt> {
            val list = ArrayList<Stmt>()
            while (pos < tokens.size && peek() != "}") {
                if (match(";")) continue
                val st = parseStmt()
                if (st != null) list.add(st)
                match(";")
            }
            return list.toTypedArray()
        }

        private fun parseStmt(): Stmt? {
            val tok = peek() ?: return null
            if (tok == "var") {
                consume()
                val name = consume()
                match("=")
                val expr = parseExpr()
                return Stmt.AssignReg(slotForVar(name), expr)
            }
            if (tok == "mem" && peek(1) == "[") {
                consume()
                consume()
                val idxE = parseExpr()
                match("]")
                match("=")
                val valE = parseExpr()
                return Stmt.AssignMem(idxE, valE)
            }
            if (tok == "for") {
                consume()
                match("(")
                val vName = consume()
                val slot = slotForVar(vName)
                match(",")
                val sExpr = parseExpr()
                match(",")
                val eExpr = parseExpr()
                val stepExpr = if (match(",")) parseExpr() else Expr.Num(1f)
                match(")")
                match("{")
                val body = parseProgram()
                match("}")
                return Stmt.ForLoop(slot, sExpr, eExpr, stepExpr, body)
            }
            if (tok == "if") {
                consume()
                match("(")
                val cond = parseExpr()
                match(")")
                match("{")
                val body = parseProgram()
                match("}")
                var elseBody: Array<Stmt>? = null
                if (match("else")) {
                    match("{")
                    elseBody = parseProgram()
                    match("}")
                }
                return Stmt.IfBranch(cond, body, elseBody)
            }
            if (peek(1) == "=") {
                val name = consume()
                consume()
                return Stmt.AssignReg(slotForVar(name), parseExpr())
            }
            if (peek(1) == "(") {
                val cmdName = consume()
                consume()
                val args = ArrayList<Expr>()
                if (peek() != ")") {
                    while (true) {
                        args.add(parseExpr())
                        if (!match(",")) break
                    }
                }
                match(")")
                val cmdCode = when (cmdName) {
                    "set_pos" -> CMD_SET_POS
                    "circle" -> CMD_CIRCLE
                    "oval" -> CMD_OVAL
                    "line" -> CMD_LINE
                    "hline" -> CMD_HLINE
                    "ribbon" -> CMD_RIBBON
                    "poly" -> CMD_POLY
                    "arc" -> CMD_ARC
                    "drip" -> CMD_DRIP
                    else -> 0
                }
                return Stmt.Command(cmdCode, args.toTypedArray())
            }
            pos++
            return null
        }

        private fun parseExpr(): Expr = parseLogic()

        private fun foldBinOp(op: Int, l: Expr, r: Expr): Expr {
            if (l is Expr.Num && r is Expr.Num) {
                val lv = l.v
                val rv = r.v
                val res = when (op) {
                    OP_ADD -> lv + rv
                    OP_SUB -> lv - rv
                    OP_MUL -> lv * rv
                    OP_DIV -> if (abs(rv) < 0.00001f) 0f else lv / rv
                    OP_MOD -> if (abs(rv) < 0.00001f) 0f else lv % rv
                    else -> return Expr.BinOp(op, l, r)
                }
                return Expr.Num(res)
            }
            return Expr.BinOp(op, l, r)
        }

        private fun parseLogic(): Expr {
            var left = parseCmp()
            while (peek() in listOf("&&", "||")) {
                val op = if (consume() == "&&") OP_AND else OP_OR
                left = Expr.BinOp(op, left, parseCmp())
            }
            return left
        }

        private fun parseCmp(): Expr {
            var left = parseAdd()
            while (peek() in listOf("<", ">", "<=", ">=", "==", "!=")) {
                val op = when (consume()) {
                    "<" -> OP_LT
                    ">" -> OP_GT
                    "<=" -> OP_LE
                    ">=" -> OP_GE
                    "==" -> OP_EQ
                    else -> OP_NE
                }
                left = Expr.BinOp(op, left, parseAdd())
            }
            return left
        }

        private fun parseAdd(): Expr {
            var left = parseMul()
            while (peek() in listOf("+", "-")) {
                val op = if (consume() == "+") OP_ADD else OP_SUB
                left = foldBinOp(op, left, parseMul())
            }
            return left
        }

        private fun parseMul(): Expr {
            var left = parseUnary()
            while (peek() in listOf("*", "/", "%")) {
                val op = when (consume()) {
                    "*" -> OP_MUL
                    "/" -> OP_DIV
                    else -> OP_MOD
                }
                left = foldBinOp(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Expr {
            if (match("-")) {
                val inner = parseUnary()
                return if (inner is Expr.Num) Expr.Num(-inner.v) else Expr.UnaryNeg(inner)
            }
            if (match("+")) return parseUnary()
            return parsePrimary()
        }

        private fun parsePrimary(): Expr {
            val t = peek() ?: return Expr.Num(0f)
            if (t == "(") {
                consume()
                val e = parseExpr()
                match(")")
                return e
            }
            if (t[0].isDigit() || t[0] == '.') {
                consume()
                return Expr.Num(t.toFloatOrNull() ?: 0f)
            }
            val id = consume()
            if (match("(")) {
                val args = ArrayList<Expr>(3)
                if (peek() != ")") {
                    while (true) {
                        args.add(parseExpr())
                        if (!match(",")) break
                    }
                }
                match(")")
                val fnCode = when (id) {
                    "sin" -> FN_SIN
                    "cos" -> FN_COS
                    "tan" -> FN_TAN
                    "atan2" -> FN_ATAN2
                    "sqrt" -> FN_SQRT
                    "pow" -> FN_POW
                    "abs" -> FN_ABS
                    "min" -> FN_MIN
                    "max" -> FN_MAX
                    "floor" -> FN_FLOOR
                    "fract" -> FN_FRACT
                    "mod" -> FN_MOD
                    "clamp" -> FN_CLAMP
                    "mix" -> FN_MIX
                    "noise" -> FN_NOISE
                    "rand" -> FN_RAND
                    "srnd" -> FN_SRND
                    "mem" -> FN_MEM
                    "hx" -> FN_HX
                    "hy" -> FN_HY
                    "pickH" -> FN_PICK_H
                    "pickS" -> FN_PICK_S
                    "pickL" -> FN_PICK_L
                    "pickA" -> FN_PICK_A
                    else -> 0
                }
                return Expr.Call(
                    fnCode,
                    args.getOrNull(0),
                    args.getOrNull(1),
                    args.getOrNull(2)
                )
            }
            if (id == "pi") return Expr.Num(3.1415927f)
            return Expr.Reg(slotForVar(id))
        }
    }
}
