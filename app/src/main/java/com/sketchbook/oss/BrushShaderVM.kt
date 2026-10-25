package com.sketchbook.oss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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

class BrushShaderVM {

    private sealed class Stmt {
        data class AssignVar(val name: String, val expr: Expr) : Stmt()
        data class AssignMem(val idxExpr: Expr, val valExpr: Expr) : Stmt()
        data class ForLoop(val varName: String, val start: Expr, val end: Expr, val step: Expr, val body: List<Stmt>) : Stmt()
        data class IfBranch(val cond: Expr, val body: List<Stmt>, val elseBody: List<Stmt>?) : Stmt()
        data class Command(val name: String, val args: List<Expr>) : Stmt()
    }

    private sealed class Expr {
        data class Num(val v: Float) : Expr()
        data class Var(val name: String) : Expr()
        data class UnaryNeg(val inner: Expr) : Expr()
        data class BinOp(val op: String, val left: Expr, val right: Expr) : Expr()
        data class Call(val fn: String, val args: List<Expr>) : Expr()
    }

    private val compiledCache = HashMap<String, List<Stmt>>()
    val memory = FloatArray(64)

    fun resetMemory() {
        memory.fill(0f)
    }

    private fun getCompiled(code: String): List<Stmt> {
        return compiledCache.getOrPut(code) {
            try {
                Parser(tokenize(code)).parseProgram()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // --------------------------------------------------------------------------
    // 2D CONTINUOUS PERLIN / VALUE GRADIENT NOISE (-1.0 .. +1.0)
    // --------------------------------------------------------------------------
    private fun hash2D(ix: Int, iy: Int): Float {
        var n = ix * 374761393 + iy * 668265263
        n = (n xor (n shr 13)) * 1274126177
        return ((n xor (n shr 16)) and 0x7FFFFFFF) / 1073741824f - 1f
    }

    fun noise2D(x: Float, y: Float): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - floor(x)
        val fy = y - floor(y)
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

    // --------------------------------------------------------------------------
    // VM EXECUTION CONTEXT PER DAB
    // --------------------------------------------------------------------------
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
        historyPts: List<PointF>,
        historyRadii: List<Float>,
        canvasBitmap: Bitmap?,
        activeAgentsOut: MutableList<LivePhysicsAgent>?,
        fillPaint: Paint,
        strokePaint: Paint,
        dstOutXfer: PorterDuffXfermode
    ) {
        val program = getCompiled(code)
        if (program.isEmpty()) return

        val vars = HashMap<String, Float>(24)
        vars["x"] = x
        vars["y"] = y
        vars["r"] = radius
        vars["p"] = pressure
        vars["v"] = speed
        vars["i"] = dabIndex.toFloat()
        vars["t"] = timeSec
        vars["ang"] = tangentDeg
        vars["rad"] = Math.toRadians(tangentDeg.toDouble()).toFloat()
        vars["tx"] = tx
        vars["ty"] = ty
        vars["nx"] = nx
        vars["ny"] = ny
        vars["h"] = baseHue
        vars["s"] = baseSat
        vars["l"] = baseLit
        vars["a"] = baseAlpha
        vars["pi"] = Math.PI.toFloat()
        vars["hcount"] = historyPts.size.toFloat()

        var randSalt = 1
        fun nextRand01(): Float {
            randSalt++
            return if (deterministicRandom) {
                val h = ((dabIndex * 73856093) xor (randSalt * 19349663)) and 0x7FFFFFFF
                (h % 10000) / 10000f
            } else {
                Random.nextFloat()
            }
        }

        fun sampleCanvasHslA(dx: Float, dy: Float): FloatArray {
            if (canvasBitmap == null) return floatArrayOf(baseHue, baseSat, baseLit, 1f)
            val sx = (x + dx).toInt().coerceIn(0, canvasBitmap.width - 1)
            val sy = (y + dy).toInt().coerceIn(0, canvasBitmap.height - 1)
            val col = canvasBitmap.getPixel(sx, sy)
            val a = Color.alpha(col) / 255f
            if (a < 0.05f) return floatArrayOf(baseHue, baseSat, baseLit, 0f)
            val hsl = ColorStudioState.colorToHsl(col)
            return floatArrayOf(hsl[0], hsl[1], hsl[2], a)
        }

        fun eval(e: Expr): Float {
            return when (e) {
                is Expr.Num -> e.v
                is Expr.Var -> vars[e.name] ?: 0f
                is Expr.UnaryNeg -> -eval(e.inner)
                is Expr.BinOp -> {
                    val l = eval(e.left)
                    val r = eval(e.right)
                    when (e.op) {
                        "+" -> l + r
                        "-" -> l - r
                        "*" -> l * r
                        "/" -> if (abs(r) < 0.00001f) 0f else l / r
                        "%" -> if (abs(r) < 0.00001f) 0f else l % r
                        "<" -> if (l < r) 1f else 0f
                        ">" -> if (l > r) 1f else 0f
                        "<=" -> if (l <= r) 1f else 0f
                        ">=" -> if (l >= r) 1f else 0f
                        "==" -> if (abs(l - r) < 0.0001f) 1f else 0f
                        "!=" -> if (abs(l - r) >= 0.0001f) 1f else 0f
                        "&&" -> if (l != 0f && r != 0f) 1f else 0f
                        "||" -> if (l != 0f || r != 0f) 1f else 0f
                        else -> 0f
                    }
                }
                is Expr.Call -> {
                    val fn = e.fn
                    val a0 = if (e.args.isNotEmpty()) eval(e.args[0]) else 0f
                    val a1 = if (e.args.size > 1) eval(e.args[1]) else 0f
                    val a2 = if (e.args.size > 2) eval(e.args[2]) else 0f
                    when (fn) {
                        "sin" -> sin(a0)
                        "cos" -> cos(a0)
                        "tan" -> tan(a0)
                        "atan2" -> atan2(a0, a1)
                        "sqrt" -> sqrt(max(0f, a0))
                        "pow" -> a0.pow(a1)
                        "abs" -> abs(a0)
                        "min" -> min(a0, a1)
                        "max" -> max(a0, a1)
                        "floor" -> floor(a0)
                        "fract" -> a0 - floor(a0)
                        "mod" -> if (abs(a1) < 0.00001f) 0f else a0 % a1
                        "clamp" -> a0.coerceIn(min(a1, a2), max(a1, a2))
                        "mix" -> a0 + (a1 - a0) * a2.coerceIn(0f, 1f)
                        "noise" -> noise2D(a0, a1)
                        "rand" -> nextRand01()
                        "srnd" -> nextRand01() * 2f - 1f
                        "mem" -> memory[a0.toInt().coerceIn(0, 63)]
                        "hx" -> {
                            val lag = a0.toInt().coerceAtLeast(1)
                            val idx = (historyPts.size - 1 - lag).coerceIn(0, max(0, historyPts.lastIndex))
                            if (historyPts.isNotEmpty()) historyPts[idx].x else x
                        }
                        "hy" -> {
                            val lag = a0.toInt().coerceAtLeast(1)
                            val idx = (historyPts.size - 1 - lag).coerceIn(0, max(0, historyPts.lastIndex))
                            if (historyPts.isNotEmpty()) historyPts[idx].y else y
                        }
                        "pickH" -> sampleCanvasHslA(a0, a1)[0]
                        "pickS" -> sampleCanvasHslA(a0, a1)[1]
                        "pickL" -> sampleCanvasHslA(a0, a1)[2]
                        "pickA" -> sampleCanvasHslA(a0, a1)[3]
                        else -> 0f
                    }
                }
            }
        }

        fun resolveColor(h: Float, s: Float, l: Float): Int {
            if (isErase) return Color.BLACK
            val normH = ((h % 360f) + 360f) % 360f
            return ColorStudioState.hslToColor(normH, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }

        var opsBudget = 600
        fun execStmts(stmts: List<Stmt>) {
            for (st in stmts) {
                if (opsBudget-- <= 0) return
                when (st) {
                    is Stmt.AssignVar -> vars[st.name] = eval(st.expr)
                    is Stmt.AssignMem -> {
                        val idx = eval(st.idxExpr).toInt().coerceIn(0, 63)
                        memory[idx] = eval(st.valExpr)
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
                            while ((if (stepVal > 0f) cur <= endVal + 0.0001f else cur >= endVal - 0.0001f) && guard++ < 64 && opsBudget > 0) {
                                vars[st.varName] = cur
                                execStmts(st.body)
                                cur += stepVal
                            }
                        }
                    }
                    is Stmt.Command -> {
                        val args = st.args.map { eval(it) }
                        fun arg(i: Int, def: Float = 0f) = if (i < args.size) args[i] else def
                        val xfer = if (isErase) dstOutXfer else null

                        when (st.name) {
                            "circle" -> {
                                // circle(dx, dy, rad, h, s, l, alpha, feather)
                                val cx = x + arg(0)
                                val cy = y + arg(1)
                                val cr = max(0.8f, arg(2, radius))
                                val col = resolveColor(arg(3, baseHue), arg(4, baseSat), arg(5, baseLit))
                                val al = (arg(6, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val feather = arg(7, 0f).coerceIn(0f, 1f)
                                fillPaint.xfermode = xfer
                                if (feather > 0.05f && cr >= 1.5f) {
                                    val coreStop = (1f - feather * 0.88f).coerceIn(0.04f, 0.92f)
                                    val rC = Color.red(col); val gC = Color.green(col); val bC = Color.blue(col)
                                    fillPaint.shader = RadialGradient(
                                        cx, cy, cr,
                                        intArrayOf(Color.argb(al, rC, gC, bC), Color.argb(al / 2, rC, gC, bC), Color.TRANSPARENT),
                                        floatArrayOf(0f, coreStop, 1f),
                                        Shader.TileMode.CLAMP
                                    )
                                } else {
                                    fillPaint.shader = null
                                    fillPaint.color = Color.argb(al, Color.red(col), Color.green(col), Color.blue(col))
                                }
                                canvas.drawCircle(cx, cy, cr, fillPaint)
                                fillPaint.shader = null
                                fillPaint.xfermode = null
                            }

                            "oval" -> {
                                // oval(dx, dy, rx, ry, rotDeg, h, s, l, alpha, feather)
                                val cx = x + arg(0)
                                val cy = y + arg(1)
                                val rx = max(0.8f, arg(2, radius))
                                val ry = max(0.8f, arg(3, radius * 0.5f))
                                val rot = arg(4, tangentDeg)
                                val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                                val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val feather = arg(9, 0f).coerceIn(0f, 1f)

                                fillPaint.xfermode = xfer
                                val rC = Color.red(col); val gC = Color.green(col); val bC = Color.blue(col)
                                if (feather > 0.05f) {
                                    fillPaint.shader = RadialGradient(
                                        0f, 0f, max(1.5f, rx),
                                        intArrayOf(Color.argb(al, rC, gC, bC), Color.TRANSPARENT),
                                        floatArrayOf((1f - feather * 0.85f).coerceIn(0.05f, 0.9f), 1f),
                                        Shader.TileMode.CLAMP
                                    )
                                } else {
                                    fillPaint.shader = null
                                    fillPaint.color = Color.argb(al, rC, gC, bC)
                                }
                                canvas.save()
                                canvas.translate(cx, cy)
                                canvas.rotate(rot)
                                canvas.scale(1f, (ry / rx).coerceIn(0.05f, 10f))
                                canvas.drawCircle(0f, 0f, rx, fillPaint)
                                canvas.restore()
                                fillPaint.shader = null
                                fillPaint.xfermode = null
                            }

                            "line" -> {
                                // line(dx1, dy1, dx2, dy2, width, h, s, l, alpha)
                                val x1 = x + arg(0)
                                val y1 = y + arg(1)
                                val x2 = x + arg(2)
                                val y2 = y + arg(3)
                                val w = max(0.8f, arg(4, 2f))
                                val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                                val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                strokePaint.xfermode = xfer
                                strokePaint.shader = null
                                strokePaint.strokeWidth = w
                                strokePaint.color = Color.argb(al, Color.red(col), Color.green(col), Color.blue(col))
                                canvas.drawLine(x1, y1, x2, y2, strokePaint)
                                strokePaint.xfermode = null
                            }

                            "ribbon" -> {
                                // ribbon(lag, widthScale, normalOffsetScale, h, s, l, alpha)
                                val lag = arg(0, 1f).toInt().coerceAtLeast(1)
                                if (historyPts.size >= lag) {
                                    val pIdx = (historyPts.size - lag).coerceIn(0, historyPts.lastIndex)
                                    val prevPt = historyPts[pIdx]
                                    val prevR = if (pIdx < historyRadii.size) historyRadii[pIdx] else radius
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

                                    val path = Path().apply {
                                        moveTo(prevPt.x + prvOffX - nx * hwPrv, prevPt.y + prvOffY - ny * hwPrv)
                                        lineTo(prevPt.x + prvOffX + nx * hwPrv, prevPt.y + prvOffY + ny * hwPrv)
                                        lineTo(x + curOffX + nx * hwCur, y + curOffY + ny * hwCur)
                                        lineTo(x + curOffX - nx * hwCur, y + curOffY - ny * hwCur)
                                        close()
                                    }
                                    fillPaint.xfermode = xfer
                                    fillPaint.shader = null
                                    fillPaint.color = Color.argb(al, Color.red(col), Color.green(col), Color.blue(col))
                                    canvas.drawPath(path, fillPaint)
                                    fillPaint.xfermode = null
                                }
                            }

                            "poly" -> {
                                // poly(sides, dx, dy, rad, rotDeg, h, s, l, alpha, fillMode)
                                val sides = arg(0, 5f).toInt().coerceIn(3, 24)
                                val cx = x + arg(1)
                                val cy = y + arg(2)
                                val pr = max(1f, arg(3, radius))
                                val rotRad = Math.toRadians(arg(4, tangentDeg).toDouble())
                                val col = resolveColor(arg(5, baseHue), arg(6, baseSat), arg(7, baseLit))
                                val al = (arg(8, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                val isFill = arg(9, 1f) >= 0.5f

                                val path = Path()
                                for (k in 0 until sides) {
                                    val a = rotRad + k * 2.0 * Math.PI / sides
                                    val vx = cx + cos(a).toFloat() * pr
                                    val vy = cy + sin(a).toFloat() * pr
                                    if (k == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
                                }
                                path.close()
                                if (isFill) {
                                    fillPaint.xfermode = xfer
                                    fillPaint.shader = null
                                    fillPaint.color = Color.argb(al, Color.red(col), Color.green(col), Color.blue(col))
                                    canvas.drawPath(path, fillPaint)
                                    fillPaint.xfermode = null
                                } else {
                                    strokePaint.xfermode = xfer
                                    strokePaint.strokeWidth = max(1.2f, radius * 0.12f)
                                    strokePaint.color = Color.argb(al, Color.red(col), Color.green(col), Color.blue(col))
                                    canvas.drawPath(path, strokePaint)
                                    strokePaint.xfermode = null
                                }
                            }

                            "arc" -> {
                                // arc(dx, dy, rad, startDeg, sweepDeg, width, h, s, l, alpha)
                                val cx = x + arg(0)
                                val cy = y + arg(1)
                                val ar = max(1f, arg(2, radius))
                                val sDeg = arg(3, 0f)
                                val swDeg = arg(4, 180f)
                                val w = max(1f, arg(5, 3f))
                                val col = resolveColor(arg(6, baseHue), arg(7, baseSat), arg(8, baseLit))
                                val al = (arg(9, baseAlpha).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(1, 255)
                                strokePaint.xfermode = xfer
                                strokePaint.strokeWidth = w
                                strokePaint.color = Color.argb(al, Color.red(col), Color.green(col), Color.blue(col))
                                canvas.drawArc(RectF(cx - ar, cy - ar, cx + ar, cy + ar), sDeg, swDeg, false, strokePaint)
                                strokePaint.xfermode = null
                            }

                            "drip" -> {
                                // drip(dx, dy, vx, vy, rad, lifeSec, gravity, curlStrength, h, s, l, alpha)
                                if (activeAgentsOut != null && activeAgentsOut.size < 280) {
                                    val life = arg(5, 1.5f).coerceIn(0.1f, 8.0f)
                                    activeAgentsOut.add(
                                        LivePhysicsAgent(
                                            x = x + arg(0),
                                            y = y + arg(1),
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

        execStmts(program)
    }

    // ==========================================================================
    // COMPACT RECURSIVE-DESCENT TOKENIZER & PARSER FOR BRUSH SHADER VM
    // ==========================================================================
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

        private fun peek(offset: Int = 0): String? = tokens.getOrNull(pos + offset)
        private fun consume(): String = tokens[pos++]
        private fun match(expected: String): Boolean {
            if (peek() == expected) {
                pos++
                return true
            }
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
                consume()
                val name = consume()
                match("=")
                val expr = parseExpr()
                return Stmt.AssignVar(name, expr)
            }
            if (tok == "mem" && peek(1) == "[") {
                consume()
                consume() // '['
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
                match(",")
                val sExpr = parseExpr()
                match(",")
                val eExpr = parseExpr()
                val stepExpr = if (match(",")) parseExpr() else Expr.Num(1f)
                match(")")
                match("{")
                val body = parseProgram()
                match("}")
                return Stmt.ForLoop(vName, sExpr, eExpr, stepExpr, body)
            }
            if (tok == "if") {
                consume()
                match("(")
                val cond = parseExpr()
                match(")")
                match("{")
                val body = parseProgram()
                match("}")
                var elseBody: List<Stmt>? = null
                if (match("else")) {
                    match("{")
                    elseBody = parseProgram()
                    match("}")
                }
                return Stmt.IfBranch(cond, body, elseBody)
            }
            if (peek(1) == "=") {
                val name = consume()
                consume() // '='
                return Stmt.AssignVar(name, parseExpr())
            }
            if (peek(1) == "(") {
                val cmdName = consume()
                consume() // '('
                val args = ArrayList<Expr>()
                if (peek() != ")") {
                    while (true) {
                        args.add(parseExpr())
                        if (!match(",")) break
                    }
                }
                match(")")
                return Stmt.Command(cmdName, args)
            }
            pos++
            return null
        }

        private fun parseExpr(): Expr = parseLogic()

        private fun parseLogic(): Expr {
            var left = parseCmp()
            while (peek() in listOf("&&", "||")) {
                val op = consume()
                left = Expr.BinOp(op, left, parseCmp())
            }
            return left
        }

        private fun parseCmp(): Expr {
            var left = parseAdd()
            while (peek() in listOf("<", ">", "<=", ">=", "==", "!=")) {
                val op = consume()
                left = Expr.BinOp(op, left, parseAdd())
            }
            return left
        }

        private fun parseAdd(): Expr {
            var left = parseMul()
            while (peek() in listOf("+", "-")) {
                val op = consume()
                left = Expr.BinOp(op, left, parseMul())
            }
            return left
        }

        private fun parseMul(): Expr {
            var left = parseUnary()
            while (peek() in listOf("*", "/", "%")) {
                val op = consume()
                left = Expr.BinOp(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Expr {
            if (match("-")) return Expr.UnaryNeg(parseUnary())
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
                val args = ArrayList<Expr>()
                if (peek() != ")") {
                    while (true) {
                        args.add(parseExpr())
                        if (!match(",")) break
                    }
                }
                match(")")
                return Expr.Call(id, args)
            }
            return Expr.Var(id)
        }
    }
}
