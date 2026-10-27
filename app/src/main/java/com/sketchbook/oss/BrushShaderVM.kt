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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
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

class GpuShapeBatcher(maxVertices: Int = 65536) {
    companion object {
        const val FLOATS_PER_VERTEX = 9
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
    }
    private val maxVerts = maxVertices
    private val staging = FloatArray(maxVerts * FLOATS_PER_VERTEX)
    val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * STRIDE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    var vertexCount: Int = 0
        private set
    var currentEraseMode: Boolean = false
        private set
    var onFlushCallback: ((GpuShapeBatcher) -> Unit)? = null

    fun reset(isErase: Boolean = false) {
        vertexCount = 0
        currentEraseMode = isErase
    }

    fun setEraseMode(isErase: Boolean) {
        if (currentEraseMode != isErase) {
            flush()
            currentEraseMode = isErase
        }
    }

    fun flush() {
        if (vertexCount <= 0) return
        val floatCount = vertexCount * FLOATS_PER_VERTEX
        vertexBuffer.clear()
        vertexBuffer.put(staging, 0, floatCount)
        vertexBuffer.position(0)
        onFlushCallback?.invoke(this)
        vertexCount = 0
    }

    private fun ensureCapacity(vertsNeeded: Int) {
        if (vertexCount + vertsNeeded > maxVerts) flush()
    }

    private fun putVert(x: Float, y: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float, feather: Float) {
        var idx = vertexCount * FLOATS_PER_VERTEX
        staging[idx++] = x; staging[idx++] = y
        staging[idx++] = u; staging[idx++] = v
        staging[idx++] = r; staging[idx++] = g; staging[idx++] = b; staging[idx++] = a
        staging[idx] = feather
        vertexCount++
    }

    fun addCircle(cx: Float, cy: Float, radius: Float, color: Int, alpha255: Int, feather: Float) {
        ensureCapacity(6)
        val a = (alpha255.coerceIn(1, 255)) / 255f
        val r = ((color shr 16) and 0xFF) / 255f * a
        val g = ((color shr 8) and 0xFF) / 255f * a
        val b = (color and 0xFF) / 255f * a
        val f = feather.coerceIn(0f, 1f)
        val x0 = cx - radius; val y0 = cy - radius
        val x1 = cx + radius; val y1 = cy + radius
        putVert(x0, y0, -1f, -1f, r, g, b, a, f)
        putVert(x1, y0,  1f, -1f, r, g, b, a, f)
        putVert(x0, y1, -1f,  1f, r, g, b, a, f)
        putVert(x1, y0,  1f, -1f, r, g, b, a, f)
        putVert(x1, y1,  1f,  1f, r, g, b, a, f)
        putVert(x0, y1, -1f,  1f, r, g, b, a, f)
    }

    fun addOval(cx: Float, cy: Float, rx: Float, ry: Float, rotDeg: Float, color: Int, alpha255: Int, feather: Float) {
        ensureCapacity(6)
        val a = (alpha255.coerceIn(1, 255)) / 255f
        val r = ((color shr 16) and 0xFF) / 255f * a
        val g = ((color shr 8) and 0xFF) / 255f * a
        val b = (color and 0xFF) / 255f * a
        val f = feather.coerceIn(0f, 1f)
        val rad = rotDeg * 0.017453292f
        val cs = cos(rad); val sn = sin(rad)
        val ux = rx * cs; val uy = rx * sn
        val vx = -ry * sn; val vy = ry * cs
        val p0x = cx - ux - vx; val p0y = cy - uy - vy
        val p1x = cx + ux - vx; val p1y = cy + uy - vy
        val p2x = cx - ux + vx; val p2y = cy - uy + vy
        val p3x = cx + ux + vx; val p3y = cy + uy + vy
        putVert(p0x, p0y, -1f, -1f, r, g, b, a, f)
        putVert(p1x, p1y,  1f, -1f, r, g, b, a, f)
        putVert(p2x, p2y, -1f,  1f, r, g, b, a, f)
        putVert(p1x, p1y,  1f, -1f, r, g, b, a, f)
        putVert(p3x, p3y,  1f,  1f, r, g, b, a, f)
        putVert(p2x, p2y, -1f,  1f, r, g, b, a, f)
    }

    fun addLine(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, alpha255: Int) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy)
        val hw = max(0.6f, width * 0.5f)
        if (len < 0.001f) {
            addCircle(x1, y1, hw, color, alpha255, 0.1f)
            return
        }
        ensureCapacity(6)
        val a = (alpha255.coerceIn(1, 255)) / 255f
        val r = ((color shr 16) and 0xFF) / 255f * a
        val g = ((color shr 8) and 0xFF) / 255f * a
        val b = (color and 0xFF) / 255f * a
        val tx = (dx / len) * hw; val ty = (dy / len) * hw
        val nx = -ty; val ny = tx
        val sx1 = x1 - tx; val sy1 = y1 - ty
        val sx2 = x2 + tx; val sy2 = y2 + ty
        val p0x = sx1 - nx; val p0y = sy1 - ny
        val p1x = sx2 - nx; val p1y = sy2 - ny
        val p2x = sx1 + nx; val p2y = sy1 + ny
        val p3x = sx2 + nx; val p3y = sy2 + ny
        putVert(p0x, p0y, 0f, -1f, r, g, b, a, 0.15f)
        putVert(p1x, p1y, 0f, -1f, r, g, b, a, 0.15f)
        putVert(p2x, p2y, 0f,  1f, r, g, b, a, 0.15f)
        putVert(p1x, p1y, 0f, -1f, r, g, b, a, 0.15f)
        putVert(p3x, p3y, 0f,  1f, r, g, b, a, 0.15f)
        putVert(p2x, p2y, 0f,  1f, r, g, b, a, 0.15f)
    }

    fun addQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int, alpha255: Int) {
        ensureCapacity(6)
        val a = (alpha255.coerceIn(1, 255)) / 255f
        val r = ((color shr 16) and 0xFF) / 255f * a
        val g = ((color shr 8) and 0xFF) / 255f * a
        val b = (color and 0xFF) / 255f * a
        putVert(x0, y0, 0f, 0f, r, g, b, a, -1f)
        putVert(x1, y1, 0f, 0f, r, g, b, a, -1f)
        putVert(x2, y2, 0f, 0f, r, g, b, a, -1f)
        putVert(x0, y0, 0f, 0f, r, g, b, a, -1f)
        putVert(x2, y2, 0f, 0f, r, g, b, a, -1f)
        putVert(x3, y3, 0f, 0f, r, g, b, a, -1f)
    }

    fun addPoly(sides: Int, cx: Float, cy: Float, radius: Float, rotRad: Float, color: Int, alpha255: Int, isFill: Boolean, strokeW: Float) {
        val n = sides.coerceIn(3, 24)
        val stepAng = 6.2831855f / n
        if (isFill) {
            ensureCapacity((n - 2) * 3)
            val a = (alpha255.coerceIn(1, 255)) / 255f
            val r = ((color shr 16) and 0xFF) / 255f * a
            val g = ((color shr 8) and 0xFF) / 255f * a
            val b = (color and 0xFF) / 255f * a
            val v0x = cx + cos(rotRad) * radius; val v0y = cy + sin(rotRad) * radius
            var prevX = cx + cos(rotRad + stepAng) * radius; var prevY = cy + sin(rotRad + stepAng) * radius
            for (k in 2 until n) {
                val ang = rotRad + k * stepAng
                val curX = cx + cos(ang) * radius; val curY = cy + sin(ang) * radius
                putVert(v0x, v0y, 0f, 0f, r, g, b, a, -1f)
                putVert(prevX, prevY, 0f, 0f, r, g, b, a, -1f)
                putVert(curX, curY, 0f, 0f, r, g, b, a, -1f)
                prevX = curX; prevY = curY
            }
        } else {
            var prevX = cx + cos(rotRad) * radius; var prevY = cy + sin(rotRad) * radius
            val firstX = prevX; val firstY = prevY
            for (k in 1 until n) {
                val ang = rotRad + k * stepAng
                val curX = cx + cos(ang) * radius; val curY = cy + sin(ang) * radius
                addLine(prevX, prevY, curX, curY, strokeW, color, alpha255)
                prevX = curX; prevY = curY
            }
            addLine(prevX, prevY, firstX, firstY, strokeW, color, alpha255)
        }
    }

    fun addArc(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float, width: Float, color: Int, alpha255: Int) {
        val segs = max(6, min(28, (abs(sweepDeg) / 14f).toInt()))
        val startRad = startDeg * 0.017453292f
        val stepRad = (sweepDeg * 0.017453292f) / segs
        var prevX = cx + cos(startRad) * radius; var prevY = cy + sin(startRad) * radius
        for (s in 1..segs) {
            val a = startRad + s * stepRad
            val curX = cx + cos(a) * radius; val curY = cy + sin(a) * radius
            addLine(prevX, prevY, curX, curY, width, color, alpha255)
            prevX = curX; prevY = curY
        }
    }
}

class BrushShaderVM {
    companion object {
        const val REG_X = 0; const val REG_Y = 1; const val REG_R = 2; const val REG_P = 3
        const val REG_V = 4; const val REG_I = 5; const val REG_T = 6; const val REG_ANG = 7
        const val REG_RAD = 8; const val REG_TX = 9; const val REG_TY = 10; const val REG_NX = 11
        const val REG_NY = 12; const val REG_H = 13; const val REG_S = 14; const val REG_L = 15
        const val REG_A = 16; const val REG_PI = 17; const val REG_HCOUNT = 18
        const val FIRST_USER_REG = 19
        const val FIRST_TEMP_REG = 84
        const val CONST_BASE_REG = 360
        const val MAX_REGS = 512

        const val BC_MOV = 1; const val BC_NEG = 2; const val BC_ADD = 3; const val BC_SUB = 4
        const val BC_MUL = 5; const val BC_DIV = 6; const val BC_MOD = 7; const val BC_LT = 8
        const val BC_GT = 9; const val BC_LE = 10; const val BC_GE = 11; const val BC_EQ = 12
        const val BC_NE = 13; const val BC_AND = 14; const val BC_OR = 15
        const val BC_SIN = 16; const val BC_COS = 17; const val BC_TAN = 18; const val BC_ATAN2 = 19
        const val BC_SQRT = 20; const val BC_POW = 21; const val BC_ABS = 22; const val BC_MIN = 23
        const val BC_MAX = 24; const val BC_FLOOR = 25; const val BC_FRACT = 26; const val BC_CLAMP = 27
        const val BC_MIX = 28; const val BC_NOISE = 29; const val BC_RAND = 30; const val BC_SRND = 31
        const val BC_MEM_LOAD = 32; const val BC_MEM_STORE = 33; const val BC_HX = 34; const val BC_HY = 35
        const val BC_PICK_H = 36; const val BC_PICK_S = 37; const val BC_PICK_L = 38; const val BC_PICK_A = 39
        const val BC_JMP = 40; const val BC_JMP_ZERO = 41
        const val BC_CMD_SET_POS = 50; const val BC_CMD_CIRCLE = 51; const val BC_CMD_OVAL = 52
        const val BC_CMD_LINE = 53; const val BC_CMD_HLINE = 54; const val BC_CMD_RIBBON = 55
        const val BC_CMD_POLY = 56; const val BC_CMD_ARC = 57; const val BC_CMD_DRIP = 58

        fun packInsn(op: Int, dst: Int, s1: Int = 0, s2: Int = 0, s3: Int = 0): Long {
            return ((op.toLong() and 0xFFFFL) shl 48) or
                   ((dst.toLong() and 0xFFFL) shl 36) or
                   ((s1.toLong() and 0xFFFL) shl 24) or
                   ((s2.toLong() and 0xFFFL) shl 12) or
                   (s3.toLong() and 0xFFFL)
        }

        private val sharedCompiledCache = ConcurrentHashMap<String, CompiledProgram>()

        private fun foldBinOpStatic(op: Int, l: Expr, r: Expr): Expr {
            if (l is Expr.Num && r is Expr.Num) {
                val lv = l.v; val rv = r.v
                val res = when (op) {
                    BC_ADD -> lv + rv
                    BC_SUB -> lv - rv
                    BC_MUL -> lv * rv
                    BC_DIV -> if (abs(rv) < 0.00001f) 0f else lv / rv
                    BC_MOD -> if (abs(rv) < 0.00001f) 0f else lv % rv
                    else -> return Expr.BinOp(op, l, r)
                }
                return Expr.Num(res)
            }
            if (op == BC_ADD) {
                if (l is Expr.Num && l.v == 0f) return r
                if (r is Expr.Num && r.v == 0f) return l
            }
            if (op == BC_MUL) {
                if (l is Expr.Num && l.v == 1f) return r
                if (r is Expr.Num && r.v == 1f) return l
                if ((l is Expr.Num && l.v == 0f) || (r is Expr.Num && r.v == 0f)) return Expr.Num(0f)
            }
            return Expr.BinOp(op, l, r)
        }
    }

    class CompiledProgram(val bytecode: LongArray, val consts: FloatArray)

    private sealed class Stmt {
        data class AssignReg(val slot: Int, val expr: Expr) : Stmt()
        data class AssignMem(val idxExpr: Expr, val valExpr: Expr) : Stmt()
        data class ForLoop(val slot: Int, val start: Expr, val end: Expr, val step: Expr, val body: List<Stmt>) : Stmt()
        data class IfBranch(val cond: Expr, val body: List<Stmt>, val elseBody: List<Stmt>?) : Stmt()
        data class Command(val cmdOp: Int, val args: List<Expr>) : Stmt()
    }

    private sealed class Expr {
        data class Num(val v: Float) : Expr()
        data class Reg(val slot: Int) : Expr()
        data class UnaryNeg(val inner: Expr) : Expr()
        data class BinOp(val bcOp: Int, val left: Expr, val right: Expr) : Expr()
        data class Call(val bcOp: Int, val a0: Expr?, val a1: Expr?, val a2: Expr?) : Expr()
    }

    val memory = FloatArray(64)
    private val regs = FloatArray(MAX_REGS)
    private val cmdArgs = FloatArray(16)
    private val sampleHslScratch = FloatArray(3)

    var primHistX = FloatArray(512)
    var primHistY = FloatArray(512)
    var primHistR = FloatArray(512)
    var primHistCount = 0

    private var lineBatchPts = FloatArray(2048)
    private var lineBatchCount = 0
    private var lineBatchColor = 0
    private var lineBatchWidth = -1f
    private var lineBatchErase = false

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

    fun beginStroke(expectedNodes: Int, program: CompiledProgram? = null) {
        memory.fill(0f)
        primHistCount = 0
        lineBatchCount = 0
        if (expectedNodes > primHistX.size) {
            val newCap = max(expectedNodes + 64, primHistX.size * 2)
            primHistX = FloatArray(newCap)
            primHistY = FloatArray(newCap)
            primHistR = FloatArray(newCap)
        }
        if (program != null && program.consts.isNotEmpty()) {
            System.arraycopy(program.consts, 0, regs, CONST_BASE_REG, program.consts.size)
        }
    }

    fun flushCanvasBatch(canvas: Canvas, strokePaint: Paint, dstOutXfer: PorterDuffXfermode) {
        if (lineBatchCount <= 0) return
        strokePaint.xfermode = if (lineBatchErase) dstOutXfer else null
        strokePaint.shader = null
        strokePaint.strokeWidth = lineBatchWidth
        strokePaint.color = lineBatchColor
        if (lineBatchCount == 4) {
            canvas.drawLine(lineBatchPts[0], lineBatchPts[1], lineBatchPts[2], lineBatchPts[3], strokePaint)
        } else {
            canvas.drawLines(lineBatchPts, 0, lineBatchCount, strokePaint)
        }
        strokePaint.xfermode = null
        lineBatchCount = 0
    }

    private fun queueCanvasLine(
        canvas: Canvas, strokePaint: Paint, dstOutXfer: PorterDuffXfermode,
        x1: Float, y1: Float, x2: Float, y2: Float, width: Float, argb: Int, isErase: Boolean
    ) {
        if (lineBatchCount > 0 && (lineBatchColor != argb || abs(lineBatchWidth - width) > 0.05f || lineBatchErase != isErase)) {
            flushCanvasBatch(canvas, strokePaint, dstOutXfer)
        }
        if (lineBatchCount + 4 > lineBatchPts.size) {
            flushCanvasBatch(canvas, strokePaint, dstOutXfer)
        }
        lineBatchColor = argb
        lineBatchWidth = width
        lineBatchErase = isErase
        lineBatchPts[lineBatchCount++] = x1
        lineBatchPts[lineBatchCount++] = y1
        lineBatchPts[lineBatchCount++] = x2
        lineBatchPts[lineBatchCount++] = y2
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
                val ast = Parser(tokenize(code)).parseProgram()
                BytecodeCompiler().compile(ast)
            } catch (_: Exception) {
                CompiledProgram(LongArray(0), FloatArray(0))
            }
        }
    }

    private fun hash2D(ix: Int, iy: Int): Float {
        var n = ix * 374761393 + iy * 668265263
        n = (n xor (n shr 13)) * 1274126177
        return ((n xor (n shr 16)) and 0x7FFFFFFF) / 1073741824f - 1f
    }

    fun noise2D(x: Float, y: Float): Float {
        val flX = floor(x); val flY = floor(y)
        val ix = flX.toInt(); val iy = flY.toInt()
        val fx = x - flX; val fy = y - flY
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash2D(ix, iy); val b = hash2D(ix + 1, iy)
        val c = hash2D(ix, iy + 1); val d = hash2D(ix + 1, iy + 1)
        val ab = a + (b - a) * ux; val cd = c + (d - c) * ux
        return ab + (cd - ab) * uy
    }

    fun executeDabProgram(
        code: String, canvas: Canvas, x: Float, y: Float, nx: Float, ny: Float,
        tx: Float, ty: Float, tangentDeg: Float, radius: Float, pressure: Float,
        speed: Float, dabIndex: Int, timeSec: Float, baseHue: Float, baseSat: Float,
        baseLit: Float, baseAlpha: Float, isErase: Boolean, deterministicRandom: Boolean,
        historyPts: MutableList<PointF>, historyRadii: MutableList<Float>,
        canvasBitmap: Bitmap?, activeAgentsOut: MutableList<LivePhysicsAgent>?,
        fillPaint: Paint, strokePaint: Paint, dstOutXfer: PorterDuffXfermode
    ) {
        val program = getCompiledProgram(code)
        if (program.bytecode.isEmpty()) return
        if (program.consts.isNotEmpty()) {
            System.arraycopy(program.consts, 0, regs, CONST_BASE_REG, program.consts.size)
        }
        val hSize = historyPts.size
        if (hSize > primHistX.size) {
            val newCap = max(hSize + 64, primHistX.size * 2)
            primHistX = FloatArray(newCap); primHistY = FloatArray(newCap); primHistR = FloatArray(newCap)
        }
        for (k in 0 until hSize) {
            val pt = historyPts[k]
            primHistX[k] = pt.x; primHistY[k] = pt.y
            primHistR[k] = if (k < historyRadii.size) historyRadii[k] else radius
        }
        primHistCount = hSize
        lineBatchCount = 0
        executeCompiledNode(
            program = program, canvas = canvas, gpuBatcher = null,
            x = x, y = y, nx = nx, ny = ny, tx = tx, ty = ty,
            tangentDeg = tangentDeg, radius = radius, pressure = pressure,
            speed = speed, dabIndex = dabIndex, timeSec = timeSec,
            baseHue = baseHue, baseSat = baseSat, baseLit = baseLit, baseAlpha = baseAlpha,
            isErase = isErase, deterministicRandom = deterministicRandom,
            syncHistoryPts = historyPts, canvasBitmap = canvasBitmap,
            activeAgentsOut = activeAgentsOut, fillPaint = fillPaint,
            strokePaint = strokePaint, dstOutXfer = dstOutXfer
        )
        flushCanvasBatch(canvas, strokePaint, dstOutXfer)
    }

    fun executeCompiledNode(
        program: CompiledProgram, canvas: Canvas?, gpuBatcher: GpuShapeBatcher? = null,
        x: Float, y: Float, nx: Float, ny: Float, tx: Float, ty: Float,
        tangentDeg: Float, radius: Float, pressure: Float, speed: Float,
        dabIndex: Int, timeSec: Float, baseHue: Float, baseSat: Float,
        baseLit: Float, baseAlpha: Float, isErase: Boolean, deterministicRandom: Boolean,
        syncHistoryPts: MutableList<PointF>?, canvasBitmap: Bitmap?,
        activeAgentsOut: MutableList<LivePhysicsAgent>?,
        fillPaint: Paint, strokePaint: Paint, dstOutXfer: PorterDuffXfermode
    ) {
        val code = program.bytecode
        val codeLen = code.size
        if (codeLen == 0) return

        var curX = x; var curY = y
        val regs = this.regs
        regs[REG_X] = curX; regs[REG_Y] = curY; regs[REG_R] = radius
        regs[REG_P] = pressure; regs[REG_V] = speed; regs[REG_I] = dabIndex.toFloat()
        regs[REG_T] = timeSec; regs[REG_ANG] = tangentDeg; regs[REG_RAD] = tangentDeg * 0.017453292f
        regs[REG_TX] = tx; regs[REG_TY] = ty; regs[REG_NX] = nx; regs[REG_NY] = ny
        regs[REG_H] = baseHue; regs[REG_S] = baseSat; regs[REG_L] = baseLit; regs[REG_A] = baseAlpha
        regs[REG_PI] = 3.1415927f; regs[REG_HCOUNT] = primHistCount.toFloat()

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
                return when (comp) { 0 -> baseHue; 1 -> baseSat; 2 -> baseLit; else -> 1f }
            }
            val sx = (curX + dx).toInt().coerceIn(0, canvasBitmap.width - 1)
            val sy = (curY + dy).toInt().coerceIn(0, canvasBitmap.height - 1)
            val col = canvasBitmap.getPixel(sx, sy)
            val a = (col ushr 24) / 255f
            if (comp == 3) return a
            if (a < 0.05f) return when (comp) { 0 -> baseHue; 1 -> baseSat; else -> baseLit }
            ColorStudioState.colorToHslOut(col, sampleHslScratch)
            return sampleHslScratch[comp]
        }

        fun resolveColor(h: Float, s: Float, l: Float): Int {
            if (isErase) return Color.BLACK
            var normH = h % 360f
            if (normH < 0f) normH += 360f
            return ColorStudioState.hslToColor(normH, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }

        val xfer = if (isErase) dstOutXfer else null
        var pc = 0
        var opsBudget = 2000

        while (pc < codeLen && opsBudget-- > 0) {
            val insn = code[pc++]
            val op = (insn ushr 48).toInt() and 0xFFFF
            val dst = (insn ushr 36).toInt() and 0xFFF
            val s1 = (insn ushr 24).toInt() and 0xFFF
            val s2 = (insn ushr 12).toInt() and 0xFFF
            val s3 = insn.toInt() and 0xFFF

            when (op) {
                BC_MOV -> regs[dst] = regs[s1]
                BC_NEG -> regs[dst] = -regs[s1]
                BC_ADD -> regs[dst] = regs[s1] + regs[s2]
                BC_SUB -> regs[dst] = regs[s1] - regs[s2]
                BC_MUL -> regs[dst] = regs[s1] * regs[s2]
                BC_DIV -> { val d = regs[s2]; regs[dst] = if (abs(d) < 0.00001f) 0f else regs[s1] / d }
                BC_MOD -> { val d = regs[s2]; regs[dst] = if (abs(d) < 0.00001f) 0f else regs[s1] % d }
                BC_LT -> regs[dst] = if (regs[s1] < regs[s2]) 1f else 0f
                BC_GT -> regs[dst] = if (regs[s1] > regs[s2]) 1f else 0f
                BC_LE -> regs[dst] = if (regs[s1] <= regs[s2]) 1f else 0f
                BC_GE -> regs[dst] = if (regs[s1] >= regs[s2]) 1f else 0f
                BC_EQ -> regs[dst] = if (abs(regs[s1] - regs[s2]) < 0.0001f) 1f else 0f
                BC_NE -> regs[dst] = if (abs(regs[s1] - regs[s2]) >= 0.0001f) 1f else 0f
                BC_AND -> regs[dst] = if (regs[s1] != 0f && regs[s2] != 0f) 1f else 0f
                BC_OR -> regs[dst] = if (regs[s1] != 0f || regs[s2] != 0f) 1f else 0f
                BC_SIN -> regs[dst] = sin(regs[s1])
                BC_COS -> regs[dst] = cos(regs[s1])
                BC_TAN -> regs[dst] = tan(regs[s1])
                BC_ATAN2 -> regs[dst] = atan2(regs[s1], regs[s2])
                BC_SQRT -> regs[dst] = sqrt(max(0f, regs[s1]))
                BC_POW -> regs[dst] = regs[s1].pow(regs[s2])
                BC_ABS -> regs[dst] = abs(regs[s1])
                BC_MIN -> regs[dst] = min(regs[s1], regs[s2])
                BC_MAX -> regs[dst] = max(regs[s1], regs[s2])
                BC_FLOOR -> regs[dst] = floor(regs[s1])
                BC_FRACT -> { val v = regs[s1]; regs[dst] = v - floor(v) }
                BC_CLAMP -> {
                    val xVal = regs[s1]; val aVal = regs[s2]; val bVal = regs[s3]
                    val lo = min(aVal, bVal); val hi = max(aVal, bVal)
                    regs[dst] = if (xVal < lo) lo else if (xVal > hi) hi else xVal
                }
                BC_MIX -> {
                    val aVal = regs[s1]; val bVal = regs[s2]; val tVal = regs[s3].coerceIn(0f, 1f)
                    regs[dst] = aVal + (bVal - aVal) * tVal
                }
                BC_NOISE -> regs[dst] = noise2D(regs[s1], regs[s2])
                BC_RAND -> regs[dst] = nextRand01()
                BC_SRND -> regs[dst] = nextRand01() * 2f - 1f
                BC_MEM_LOAD -> regs[dst] = memory[regs[s1].toInt().coerceIn(0, 63)]
                BC_MEM_STORE -> memory[regs[s1].toInt().coerceIn(0, 63)] = regs[s2]
                BC_HX -> {
                    val lag = regs[s1].toInt().coerceAtLeast(1)
                    val idx = (primHistCount - 1 - lag).coerceIn(0, max(0, primHistCount - 1))
                    regs[dst] = if (primHistCount > 0) primHistX[idx] else curX
                }
                BC_HY -> {
                    val lag = regs[s1].toInt().coerceAtLeast(1)
                    val idx = (primHistCount - 1 - lag).coerceIn(0, max(0, primHistCount - 1))
                    regs[dst] = if (primHistCount > 0) primHistY[idx] else curY
                }
                BC_PICK_H -> regs[dst] = sampleCanvasHslA(regs[s1], regs[s2], 0)
                BC_PICK_S -> regs[dst] = sampleCanvasHslA(regs[s1], regs[s2], 1)
                BC_PICK_L -> regs[dst] = sampleCanvasHslA(regs[s1], regs[s2], 2)
                BC_PICK_A -> regs[dst] = sampleCanvasHslA(regs[s1], regs[s2], 3)
                BC_JMP -> pc = dst
                BC_JMP_ZERO -> if (regs[s1] == 0f) pc = dst
                else -> {
                    val aLen = dst
                    var aIdx = 0
                    while (aIdx < aLen && pc < codeLen) {
                        val pack = code[pc++]
                        if (aIdx < aLen) cmdArgs[aIdx++] = regs[((pack ushr 48).toInt() and 0xFFF)]
                        if (aIdx < aLen) cmdArgs[aIdx++] = regs[((pack ushr 36).toInt() and 0xFFF)]
                        if (aIdx < aLen) cmdArgs[aIdx++] = regs[((pack ushr 24).toInt() and 0xFFF)]
                        if (aIdx < aLen) cmdArgs[aIdx++] = regs[((pack ushr 12).toInt() and 0xFFF)]
                        if (aIdx < aLen) cmdArgs[aIdx++] = regs[(pack.toInt() and 0xFFF)]
                    }
                    fun arg(i: Int, def: Float = 0f): Float = if (i < aLen) cmdArgs[i] else def
                    when (op) {
                        BC_CMD_SET_POS -> {
                            curX = x + arg(0); curY = y + arg(1)
                            regs[REG_X] = curX; regs[REG_Y] = curY
                            if (primHistCount > 0) {
                                primHistX[primHistCount - 1] = curX
                                primHistY[primHistCount - 1] = curY
                            }
                            if (syncHistoryPts != null && syncHistoryPts.isNotEmpty()) {
                                syncHistoryPts[syncHistoryPts.lastIndex].set(curX, curY)
                            }
                        }
                        BC_CMD_CIRCLE -> {
                            val cx = curX + arg(0); val cy = curY + arg(1)
                            val cr = max(0.8f, arg(2, radius))
                            val col = resolveColor(arg(3, baseHue), arg(4, baseSat), arg(5, baseLit))
                            val al = (arg(6, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                            val feather = arg(7, 0f).coerceIn(0f, 1f)
                            if (gpuBatcher != null) {
                                gpuBatcher.addCircle(cx, cy, cr, col, al, feather)
                            } else if (canvas != null) {
                                if (lineBatchCount > 0) flushCanvasBatch(canvas, strokePaint, dstOutXfer)
                                fillPaint.xfermode = xfer; fillPaint.shader = null
                                val rgb = col and 0xFFFFFF
                                if (feather > 0.05f && cr >= 2.0f) {
                                    if (feather <= 0.22f) {
                                        fillPaint.color = ((al * 0.42f).toInt().coerceAtLeast(1) shl 24) or rgb
                                        canvas.drawCircle(cx, cy, cr, fillPaint)
                                        fillPaint.color = ((al * 0.86f).toInt().coerceAtLeast(1) shl 24) or rgb
                                        canvas.drawCircle(cx, cy, cr * (1f - feather * 0.65f), fillPaint)
                                    } else {
                                        fillPaint.color = ((al * 0.24f).toInt().coerceAtLeast(1) shl 24) or rgb
                                        canvas.drawCircle(cx, cy, cr, fillPaint)
                                        fillPaint.color = ((al * 0.50f).toInt().coerceAtLeast(1) shl 24) or rgb
                                        canvas.drawCircle(cx, cy, cr * (1f - feather * 0.36f), fillPaint)
                                        fillPaint.color = ((al * 0.86f).toInt().coerceAtLeast(1) shl 24) or rgb
                                        canvas.drawCircle(cx, cy, cr * (1f - feather * 0.72f), fillPaint)
                                    }
                                } else {
                                    fillPaint.color = (al shl 24) or rgb
                                    canvas.drawCircle(cx, cy, cr, fillPaint)
                                }
                                fillPaint.xfermode = null
                            }
                        }
                        BC_CMD_OVAL -> {
                            val cx = curX + arg(0); val cy = curY + arg(1)
                            val rx = max(0.8f, arg(2, radius)); val ry = max(0.8f, arg(3, radius * 0.5f))
                            val rot = arg(4, tangentDeg)
                            val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                            val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                            val feather = arg(9, 0f).coerceIn(0f, 1f)
                            if (gpuBatcher != null) {
                                gpuBatcher.addOval(cx, cy, rx, ry, rot, col, al, feather)
                            } else if (canvas != null) {
                                if (lineBatchCount > 0) flushCanvasBatch(canvas, strokePaint, dstOutXfer)
                                fillPaint.xfermode = xfer; fillPaint.shader = null
                                val rgb = col and 0xFFFFFF
                                canvas.save(); canvas.translate(cx, cy); canvas.rotate(rot)
                                if (feather > 0.05f && rx >= 2.0f) {
                                    fillPaint.color = ((al * 0.36f).toInt().coerceAtLeast(1) shl 24) or rgb
                                    reusableRect.set(-rx, -ry, rx, ry); canvas.drawOval(reusableRect, fillPaint)
                                    val sc = (1f - feather * 0.65f).coerceIn(0.25f, 0.92f)
                                    fillPaint.color = ((al * 0.85f).toInt().coerceAtLeast(1) shl 24) or rgb
                                    reusableRect.set(-rx * sc, -ry * sc, rx * sc, ry * sc); canvas.drawOval(reusableRect, fillPaint)
                                } else {
                                    fillPaint.color = (al shl 24) or rgb
                                    reusableRect.set(-rx, -ry, rx, ry); canvas.drawOval(reusableRect, fillPaint)
                                }
                                canvas.restore(); fillPaint.xfermode = null
                            }
                        }
                        BC_CMD_LINE -> {
                            val x1 = curX + arg(0); val y1 = curY + arg(1)
                            val x2 = curX + arg(2); val y2 = curY + arg(3)
                            val w = max(0.8f, arg(4, 2f))
                            val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                            val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                            if (gpuBatcher != null) {
                                gpuBatcher.addLine(x1, y1, x2, y2, w, col, al)
                            } else if (canvas != null) {
                                queueCanvasLine(canvas, strokePaint, dstOutXfer, x1, y1, x2, y2, w, (al shl 24) or (col and 0xFFFFFF), isErase)
                            }
                        }
                        BC_CMD_HLINE -> {
                            val lag = arg(0, 1f).toInt().coerceAtLeast(1)
                            if (primHistCount > lag) {
                                val pIdx = (primHistCount - 1 - lag).coerceIn(0, primHistCount - 1)
                                val x1 = primHistX[pIdx] + arg(1); val y1 = primHistY[pIdx] + arg(2)
                                val x2 = curX + arg(3); val y2 = curY + arg(4)
                                val w = max(0.8f, arg(5, 2f))
                                val col = resolveColor(arg(6, baseHue), arg(7, baseSat), arg(8, baseLit))
                                val al = (arg(9, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                if (gpuBatcher != null) {
                                    gpuBatcher.addLine(x1, y1, x2, y2, w, col, al)
                                } else if (canvas != null) {
                                    queueCanvasLine(canvas, strokePaint, dstOutXfer, x1, y1, x2, y2, w, (al shl 24) or (col and 0xFFFFFF), isErase)
                                }
                            }
                        }
                        BC_CMD_RIBBON -> {
                            val lag = arg(0, 1f).toInt().coerceAtLeast(1)
                            if (primHistCount > lag) {
                                val pIdx = (primHistCount - 1 - lag).coerceIn(0, primHistCount - 1)
                                val prevX = primHistX[pIdx]; val prevY = primHistY[pIdx]; val prevR = primHistR[pIdx]
                                val wScale = arg(1, 1f); val offScale = arg(2, 0f)
                                val col = resolveColor(arg(3, baseHue), arg(4, baseSat), arg(5, baseLit))
                                val al = (arg(6, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val curOffX = nx * radius * offScale; val curOffY = ny * radius * offScale
                                val prvOffX = nx * prevR * offScale; val prvOffY = ny * prevR * offScale
                                val hwCur = radius * wScale * 0.5f; val hwPrv = prevR * wScale * 0.5f
                                val x0 = prevX + prvOffX - nx * hwPrv; val y0 = prevY + prvOffY - ny * hwPrv
                                val x1 = prevX + prvOffX + nx * hwPrv; val y1 = prevY + prvOffY + ny * hwPrv
                                val x2 = curX + curOffX + nx * hwCur; val y2 = curY + curOffY + ny * hwCur
                                val x3 = curX + curOffX - nx * hwCur; val y3 = curY + curOffY - ny * hwCur
                                if (gpuBatcher != null) {
                                    gpuBatcher.addQuad(x0, y0, x1, y1, x2, y2, x3, y3, col, al)
                                } else if (canvas != null) {
                                    if (lineBatchCount > 0) flushCanvasBatch(canvas, strokePaint, dstOutXfer)
                                    reusablePath.rewind()
                                    reusablePath.moveTo(x0, y0); reusablePath.lineTo(x1, y1)
                                    reusablePath.lineTo(x2, y2); reusablePath.lineTo(x3, y3); reusablePath.close()
                                    fillPaint.xfermode = xfer; fillPaint.shader = null
                                    fillPaint.color = (al shl 24) or (col and 0xFFFFFF)
                                    canvas.drawPath(reusablePath, fillPaint); fillPaint.xfermode = null
                                }
                            }
                        }
                        BC_CMD_POLY -> {
                            val sides = arg(0, 5f).toInt().coerceIn(3, 24)
                            val cx = curX + arg(1); val cy = curY + arg(2)
                            val pr = max(1f, arg(3, radius))
                            val rotRad = arg(4, tangentDeg) * 0.017453292f
                            val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                            val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                            val isFill = arg(9, 1f) >= 0.5f
                            if (gpuBatcher != null) {
                                gpuBatcher.addPoly(sides, cx, cy, pr, rotRad, col, al, isFill, max(1.2f, radius * 0.12f))
                            } else if (canvas != null) {
                                if (lineBatchCount > 0) flushCanvasBatch(canvas, strokePaint, dstOutXfer)
                                reusablePath.rewind()
                                val stepAng = 6.2831855f / sides
                                for (k in 0 until sides) {
                                    val a = rotRad + k * stepAng
                                    val vx = cx + cos(a) * pr; val vy = cy + sin(a) * pr
                                    if (k == 0) reusablePath.moveTo(vx, vy) else reusablePath.lineTo(vx, vy)
                                }
                                reusablePath.close()
                                val argb = (al shl 24) or (col and 0xFFFFFF)
                                if (isFill) {
                                    fillPaint.xfermode = xfer; fillPaint.shader = null
                                    fillPaint.color = argb; canvas.drawPath(reusablePath, fillPaint); fillPaint.xfermode = null
                                } else {
                                    strokePaint.xfermode = xfer; strokePaint.strokeWidth = max(1.2f, radius * 0.12f)
                                    strokePaint.color = argb; canvas.drawPath(reusablePath, strokePaint); strokePaint.xfermode = null
                                }
                            }
                        }
                        BC_CMD_ARC -> {
                            val cx = curX + arg(0); val cy = curY + arg(1)
                            val ar = max(1f, arg(2, radius))
                            val sDeg = arg(3, 0f); val swDeg = arg(4, 180f); val w = max(1f, arg(5, 3f))
                            val col = resolveColor(arg(6, baseHue), arg(7, baseSat), arg(8, baseLit))
                            val al = (arg(9, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                            if (gpuBatcher != null) {
                                gpuBatcher.addArc(cx, cy, ar, sDeg, swDeg, w, col, al)
                            } else if (canvas != null) {
                                if (lineBatchCount > 0) flushCanvasBatch(canvas, strokePaint, dstOutXfer)
                                strokePaint.xfermode = xfer; strokePaint.strokeWidth = w
                                strokePaint.color = (al shl 24) or (col and 0xFFFFFF)
                                reusableRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
                                canvas.drawArc(reusableRect, sDeg, swDeg, false, strokePaint)
                                strokePaint.xfermode = null
                            }
                        }
                        BC_CMD_DRIP -> {
                            if (activeAgentsOut != null && activeAgentsOut.size < 280) {
                                val life = arg(5, 1.5f).coerceIn(0.1f, 8.0f)
                                activeAgentsOut.add(
                                    LivePhysicsAgent(
                                        x = curX + arg(0), y = curY + arg(1),
                                        vx = arg(2), vy = arg(3),
                                        radius = max(1f, arg(4, radius * 0.25f)),
                                        remainingSec = life, totalSec = life,
                                        gravity = arg(6, 120f), curlStrength = arg(7, 0f),
                                        hue = arg(8, baseHue), sat = arg(9, baseSat),
                                        lit = arg(10, baseLit), alpha = arg(11, baseAlpha),
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

    private class BytecodeCompiler {
        private val code = ArrayList<Long>(128)
        private val constPool = ArrayList<Float>(32)
        private val constMap = HashMap<Int, Int>(32)
        private val cseTable = HashMap<Long, Int>(64)
        private var nextTempReg = FIRST_TEMP_REG

        private fun allocConstReg(v: Float): Int {
            val bits = if (v == 0f) 0 else v.toRawBits()
            val existing = constMap[bits]
            if (existing != null) return existing
            val idx = constPool.size
            if (CONST_BASE_REG + idx >= MAX_REGS) return CONST_BASE_REG + (idx % 32)
            constPool.add(v)
            val reg = CONST_BASE_REG + idx
            constMap[bits] = reg
            return reg
        }

        private fun allocTempReg(): Int {
            val r = nextTempReg
            if (nextTempReg < CONST_BASE_REG - 1) nextTempReg++ else nextTempReg = FIRST_TEMP_REG
            return r
        }

        private fun invalidateCseForReg(modifiedReg: Int) {
            if (cseTable.isEmpty()) return
            val iter = cseTable.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val key = entry.key
                val s1 = ((key ushr 24) and 0xFFFL).toInt()
                val s2 = ((key ushr 12) and 0xFFFL).toInt()
                val s3 = (key and 0xFFFL).toInt()
                if (s1 == modifiedReg || s2 == modifiedReg || s3 == modifiedReg || entry.value == modifiedReg) {
                    iter.remove()
                }
            }
        }

        private fun substAndFoldExpr(e: Expr, targetSlot: Int, constVal: Float): Expr {
            return when (e) {
                is Expr.Num -> e
                is Expr.Reg -> if (e.slot == targetSlot) Expr.Num(constVal) else e
                is Expr.UnaryNeg -> {
                    val inner = substAndFoldExpr(e.inner, targetSlot, constVal)
                    if (inner is Expr.Num) Expr.Num(-inner.v) else Expr.UnaryNeg(inner)
                }
                is Expr.BinOp -> {
                    val l = substAndFoldExpr(e.left, targetSlot, constVal)
                    val r = substAndFoldExpr(e.right, targetSlot, constVal)
                    foldBinOpStatic(e.bcOp, l, r)
                }
                is Expr.Call -> {
                    val a0 = e.a0?.let { substAndFoldExpr(it, targetSlot, constVal) }
                    val a1 = e.a1?.let { substAndFoldExpr(it, targetSlot, constVal) }
                    val a2 = e.a2?.let { substAndFoldExpr(it, targetSlot, constVal) }
                    if (a0 is Expr.Num && (a1 == null || a1 is Expr.Num) && (a2 == null || a2 is Expr.Num)) {
                        val v0 = a0.v; val v1 = (a1 as? Expr.Num)?.v ?: 0f; val v2 = (a2 as? Expr.Num)?.v ?: 0f
                        when (e.bcOp) {
                            BC_SIN -> Expr.Num(sin(v0))
                            BC_COS -> Expr.Num(cos(v0))
                            BC_TAN -> Expr.Num(tan(v0))
                            BC_SQRT -> Expr.Num(sqrt(max(0f, v0)))
                            BC_ABS -> Expr.Num(abs(v0))
                            BC_FLOOR -> Expr.Num(floor(v0))
                            BC_FRACT -> Expr.Num(v0 - floor(v0))
                            BC_MOD -> Expr.Num(if (abs(v1) < 0.00001f) 0f else v0 % v1)
                            BC_CLAMP -> Expr.Num(v0.coerceIn(min(v1, v2), max(v1, v2)))
                            else -> Expr.Call(e.bcOp, a0, a1, a2)
                        }
                    } else Expr.Call(e.bcOp, a0, a1, a2)
                }
            }
        }

        private fun substStmt(st: Stmt, targetSlot: Int, constVal: Float): Stmt {
            return when (st) {
                is Stmt.AssignReg -> Stmt.AssignReg(st.slot, substAndFoldExpr(st.expr, targetSlot, constVal))
                is Stmt.AssignMem -> Stmt.AssignMem(
                    substAndFoldExpr(st.idxExpr, targetSlot, constVal),
                    substAndFoldExpr(st.valExpr, targetSlot, constVal)
                )
                is Stmt.ForLoop -> Stmt.ForLoop(
                    st.slot,
                    substAndFoldExpr(st.start, targetSlot, constVal),
                    substAndFoldExpr(st.end, targetSlot, constVal),
                    substAndFoldExpr(st.step, targetSlot, constVal),
                    st.body.map { substStmt(it, targetSlot, constVal) }
                )
                is Stmt.IfBranch -> Stmt.IfBranch(
                    substAndFoldExpr(st.cond, targetSlot, constVal),
                    st.body.map { substStmt(it, targetSlot, constVal) },
                    st.elseBody?.map { substStmt(it, targetSlot, constVal) }
                )
                is Stmt.Command -> Stmt.Command(st.cmdOp, st.args.map { substAndFoldExpr(it, targetSlot, constVal) })
            }
        }

        fun compile(stmts: List<Stmt>): CompiledProgram {
            emitBlock(stmts)
            return CompiledProgram(code.toLongArray(), constPool.toFloatArray())
        }

        private fun emitBlock(stmts: List<Stmt>) {
            for (st in stmts) emitStmt(st)
        }

        private fun emitStmt(st: Stmt) {
            when (st) {
                is Stmt.AssignReg -> {
                    val srcReg = emitExpr(st.expr)
                    if (srcReg != st.slot) code.add(packInsn(BC_MOV, st.slot, srcReg))
                    invalidateCseForReg(st.slot)
                }
                is Stmt.AssignMem -> {
                    val idxReg = emitExpr(st.idxExpr); val valReg = emitExpr(st.valExpr)
                    code.add(packInsn(BC_MEM_STORE, 0, idxReg, valReg))
                }
                is Stmt.ForLoop -> {
                    if (st.start is Expr.Num && st.end is Expr.Num && st.step is Expr.Num) {
                        val sVal = st.start.v; val eVal = st.end.v; val stepVal = st.step.v
                        if (abs(stepVal) > 0.0001f) {
                            val count = ((eVal - sVal) / stepVal).toInt() + 1
                            if (count in 1..20) {
                                var cur = sVal; var guard = 0
                                while ((if (stepVal > 0f) cur <= eVal + 0.0001f else cur >= eVal - 0.0001f) && guard++ < 20) {
                                    for (bStmt in st.body) emitStmt(substStmt(bStmt, st.slot, cur))
                                    cur += stepVal
                                }
                                return
                            }
                        }
                    }
                    val startReg = emitExpr(st.start)
                    code.add(packInsn(BC_MOV, st.slot, startReg))
                    cseTable.clear()
                    val loopTopPc = code.size
                    val endReg = emitExpr(st.end)
                    val condReg = allocTempReg()
                    code.add(packInsn(BC_LE, condReg, st.slot, endReg))
                    val jmpOutIdx = code.size; code.add(0L)
                    emitBlock(st.body)
                    val stepReg = emitExpr(st.step)
                    code.add(packInsn(BC_ADD, st.slot, st.slot, stepReg))
                    cseTable.clear()
                    code.add(packInsn(BC_JMP, loopTopPc))
                    code[jmpOutIdx] = packInsn(BC_JMP_ZERO, code.size, condReg)
                }
                is Stmt.IfBranch -> {
                    val condReg = emitExpr(st.cond)
                    val jmpFalseIdx = code.size; code.add(0L)
                    cseTable.clear(); emitBlock(st.body); cseTable.clear()
                    if (st.elseBody != null) {
                        val jmpEndIdx = code.size; code.add(0L)
                        code[jmpFalseIdx] = packInsn(BC_JMP_ZERO, code.size, condReg)
                        emitBlock(st.elseBody); cseTable.clear()
                        code[jmpEndIdx] = packInsn(BC_JMP, code.size)
                    } else {
                        code[jmpFalseIdx] = packInsn(BC_JMP_ZERO, code.size, condReg)
                    }
                }
                is Stmt.Command -> {
                    val argRegs = IntArray(st.args.size)
                    for (i in st.args.indices) argRegs[i] = emitExpr(st.args[i])
                    if (st.cmdOp == BC_CMD_SET_POS) {
                        invalidateCseForReg(REG_X); invalidateCseForReg(REG_Y)
                    }
                    val aLen = argRegs.size
                    code.add(packInsn(st.cmdOp, aLen))
                    var i = 0
                    while (i < aLen) {
                        val r0 = if (i < aLen) argRegs[i++] else 0
                        val r1 = if (i < aLen) argRegs[i++] else 0
                        val r2 = if (i < aLen) argRegs[i++] else 0
                        val r3 = if (i < aLen) argRegs[i++] else 0
                        val r4 = if (i < aLen) argRegs[i++] else 0
                        val packed = ((r0.toLong() and 0xFFFL) shl 48) or
                                     ((r1.toLong() and 0xFFFL) shl 36) or
                                     ((r2.toLong() and 0xFFFL) shl 24) or
                                     ((r3.toLong() and 0xFFFL) shl 12) or
                                     (r4.toLong() and 0xFFFL)
                        code.add(packed)
                    }
                }
            }
        }

        private fun emitPureOp(op: Int, s1: Int, s2: Int = 0, s3: Int = 0, canCse: Boolean = true): Int {
            if (canCse) {
                val cseKey = ((op.toLong() and 0xFFFFL) shl 36) or
                             ((s1.toLong() and 0xFFFL) shl 24) or
                             ((s2.toLong() and 0xFFFL) shl 12) or
                             (s3.toLong() and 0xFFFL)
                val cached = cseTable[cseKey]
                if (cached != null) return cached
                val dst = allocTempReg()
                invalidateCseForReg(dst)
                code.add(packInsn(op, dst, s1, s2, s3))
                cseTable[cseKey] = dst
                return dst
            } else {
                val dst = allocTempReg()
                invalidateCseForReg(dst)
                code.add(packInsn(op, dst, s1, s2, s3))
                return dst
            }
        }

        private fun emitExpr(e: Expr): Int {
            return when (e) {
                is Expr.Num -> allocConstReg(e.v)
                is Expr.Reg -> e.slot
                is Expr.UnaryNeg -> emitPureOp(BC_NEG, emitExpr(e.inner))
                is Expr.BinOp -> emitPureOp(e.bcOp, emitExpr(e.left), emitExpr(e.right))
                is Expr.Call -> {
                    val s1 = if (e.a0 != null) emitExpr(e.a0) else 0
                    val s2 = if (e.a1 != null) emitExpr(e.a1) else 0
                    val s3 = if (e.a2 != null) emitExpr(e.a2) else 0
                    val isPure = e.bcOp != BC_RAND && e.bcOp != BC_SRND && e.bcOp != BC_MEM_LOAD
                    emitPureOp(e.bcOp, s1, s2, s3, canCse = isPure)
                }
            }
        }
    }

    private fun tokenize(src: String): List<String> {
        val tokens = ArrayList<String>()
        var i = 0; val n = src.length
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
                    tokens.add(src.substring(i, i + 2)); i += 2
                }
                else -> { tokens.add(c.toString()); i++ }
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
                val s = nextUserSlot.coerceAtMost(FIRST_TEMP_REG - 1)
                if (nextUserSlot < FIRST_TEMP_REG - 1) nextUserSlot++
                s
            }
        }

        private fun peek(offset: Int = 0): String? = tokens.getOrNull(pos + offset)
        private fun consume(): String = tokens[pos++]
        private fun match(expected: String): Boolean {
            if (peek() == expected) { pos++; return true }
            return false
        }

        fun parseProgram(): List<Stmt> {
            val list = ArrayList<Stmt>()
            while (pos < tokens.size && peek() != "}") {
                if (match(";")) continue
                val st = parseStmt()
                if (st != null) list.add(st)
                match(";")
            }
            return list
        }

        private fun parseStmt(): Stmt? {
            val tok = peek() ?: return null
            if (tok == "var") {
                consume(); val name = consume(); match("=")
                return Stmt.AssignReg(slotForVar(name), parseExpr())
            }
            if (tok == "mem" && peek(1) == "[") {
                consume(); consume(); val idxE = parseExpr(); match("]"); match("=")
                return Stmt.AssignMem(idxE, parseExpr())
            }
            if (tok == "for") {
                consume(); match("("); val vName = consume(); val slot = slotForVar(vName)
                match(","); val sExpr = parseExpr(); match(","); val eExpr = parseExpr()
                val stepExpr = if (match(",")) parseExpr() else Expr.Num(1f)
                match(")"); match("{"); val body = parseProgram(); match("}")
                return Stmt.ForLoop(slot, sExpr, eExpr, stepExpr, body)
            }
            if (tok == "if") {
                consume(); match("("); val cond = parseExpr(); match(")"); match("{")
                val body = parseProgram(); match("}")
                var elseBody: List<Stmt>? = null
                if (match("else")) { match("{"); elseBody = parseProgram(); match("}") }
                return Stmt.IfBranch(cond, body, elseBody)
            }
            if (peek(1) == "=") {
                val name = consume(); consume()
                return Stmt.AssignReg(slotForVar(name), parseExpr())
            }
            if (peek(1) == "(") {
                val cmdName = consume(); consume()
                val args = ArrayList<Expr>()
                if (peek() != ")") {
                    while (true) { args.add(parseExpr()); if (!match(",")) break }
                }
                match(")")
                val cmdOp = when (cmdName) {
                    "set_pos" -> BC_CMD_SET_POS; "circle" -> BC_CMD_CIRCLE; "oval" -> BC_CMD_OVAL
                    "line" -> BC_CMD_LINE; "hline" -> BC_CMD_HLINE; "ribbon" -> BC_CMD_RIBBON
                    "poly" -> BC_CMD_POLY; "arc" -> BC_CMD_ARC; "drip" -> BC_CMD_DRIP; else -> 0
                }
                return Stmt.Command(cmdOp, args)
            }
            pos++
            return null
        }

        private fun parseExpr(): Expr = parseLogic()

        private fun parseLogic(): Expr {
            var left = parseCmp()
            while (peek() in listOf("&&", "||")) {
                val op = if (consume() == "&&") BC_AND else BC_OR
                left = foldBinOpStatic(op, left, parseCmp())
            }
            return left
        }

        private fun parseCmp(): Expr {
            var left = parseAdd()
            while (peek() in listOf("<", ">", "<=", ">=", "==", "!=")) {
                val op = when (consume()) {
                    "<" -> BC_LT; ">" -> BC_GT; "<=" -> BC_LE; ">=" -> BC_GE; "==" -> BC_EQ; else -> BC_NE
                }
                left = foldBinOpStatic(op, left, parseAdd())
            }
            return left
        }

        private fun parseAdd(): Expr {
            var left = parseMul()
            while (peek() in listOf("+", "-")) {
                val op = if (consume() == "+") BC_ADD else BC_SUB
                left = foldBinOpStatic(op, left, parseMul())
            }
            return left
        }

        private fun parseMul(): Expr {
            var left = parseUnary()
            while (peek() in listOf("*", "/", "%")) {
                val op = when (consume()) { "*" -> BC_MUL; "/" -> BC_DIV; else -> BC_MOD }
                left = foldBinOpStatic(op, left, parseUnary())
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
            if (t == "(") { consume(); val e = parseExpr(); match(")"); return e }
            if (t[0].isDigit() || t[0] == '.') { consume(); return Expr.Num(t.toFloatOrNull() ?: 0f) }
            val id = consume()
            if (match("(")) {
                val args = ArrayList<Expr>(3)
                if (peek() != ")") {
                    while (true) { args.add(parseExpr()); if (!match(",")) break }
                }
                match(")")
                val fnOp = when (id) {
                    "sin" -> BC_SIN; "cos" -> BC_COS; "tan" -> BC_TAN; "atan2" -> BC_ATAN2
                    "sqrt" -> BC_SQRT; "pow" -> BC_POW; "abs" -> BC_ABS; "min" -> BC_MIN
                    "max" -> BC_MAX; "floor" -> BC_FLOOR; "fract" -> BC_FRACT; "mod" -> BC_MOD
                    "clamp" -> BC_CLAMP; "mix" -> BC_MIX; "noise" -> BC_NOISE; "rand" -> BC_RAND
                    "srnd" -> BC_SRND; "mem" -> BC_MEM_LOAD; "hx" -> BC_HX; "hy" -> BC_HY
                    "pickH" -> BC_PICK_H; "pickS" -> BC_PICK_S; "pickL" -> BC_PICK_L; "pickA" -> BC_PICK_A
                    else -> 0
                }
                return Expr.Call(fnOp, args.getOrNull(0), args.getOrNull(1), args.getOrNull(2))
            }
            if (id == "pi") return Expr.Num(3.1415927f)
            return Expr.Reg(slotForVar(id))
        }
    }
}
