package com.sketchbook.oss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class ModFunctionSpec(
    val type: String,
    val params: Map<String, String>
)

data class ModFeatureBundle(
    val id: String,
    val title: String,
    val description: String,
    var enabled: Boolean,
    val functions: List<ModFunctionSpec>
)

data class ModUiPalette(
    val workspaceBackdrop: Int = Color.rgb(127, 127, 127),
    val topBarBg: Int = Color.rgb(239, 239, 239),
    val topBarActiveSlot: Int = Color.rgb(220, 220, 220),
    val popupBg: Int = Color.rgb(243, 243, 243),
    val popupHeaderBg: Int = Color.rgb(236, 236, 236),
    val cardBg: Int = Color.rgb(249, 249, 249),
    val inputBg: Int = Color.WHITE,
    val textPrimary: Int = Color.rgb(20, 20, 20),
    val textSecondary: Int = Color.rgb(100, 100, 100),
    val iconTint: Int = Color.rgb(104, 104, 104),
    val accentColor: Int = Color.rgb(55, 116, 205),
    val borderColor: Int = Color.rgb(205, 205, 205),
    val paperTint: Int = Color.WHITE
)

class ModCoreEngine(private val context: Context, private val onStateChanged: () -> Unit) {

    private val prefs = context.getSharedPreferences("sketchbook_mod_core_v5", Context.MODE_PRIVATE)
    val bundles = mutableListOf<ModFeatureBundle>()
    var activeScriptSource: String = ""
        private set
    var lastStatusMessage: String = "Core Mod Engine Ready"

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 30, 30, 30)
        textSize = 28f
        isFakeBoldText = true
    }

    init {
        // Always ensure built-in starter bundles are present, and merge any saved custom script on top!
        loadScript(DEFAULT_MULTI_BUNDLE_SCRIPT, saveToPrefs = false, mergeWithExisting = false)
        val savedCustom = prefs.getString("active_mod_script", null)
        if (!savedCustom.isNullOrBlank()) {
            loadScript(savedCustom, saveToPrefs = false, mergeWithExisting = true)
        }
    }

    /**
     * Parses a Mod Script. When [mergeWithExisting] is true (default when importing/pasting),
     * older/existing bundles are preserved in [bundles] while new or updated @bundle blocks
     * are added or updated seamlessly!
     */
    fun loadScript(
        scriptText: String,
        saveToPrefs: Boolean = true,
        mergeWithExisting: Boolean = true
    ): Boolean {
        val trimmed = scriptText.trim()
        if (trimmed.isEmpty()) {
            lastStatusMessage = "Error: Empty script"
            return false
        }

        val parsedBundles = mutableListOf<ModFeatureBundle>()
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                parseJsonScript(trimmed, parsedBundles)
            } else {
                parseDslScript(trimmed, parsedBundles)
            }

            if (parsedBundles.isEmpty()) {
                lastStatusMessage = "No @bundle blocks found in script"
                return false
            }

            for (b in parsedBundles) {
                if (prefs.contains("bundle_toggle_${b.id}")) {
                    b.enabled = prefs.getBoolean("bundle_toggle_${b.id}", b.enabled)
                }
            }

            if (!mergeWithExisting) {
                bundles.clear()
                bundles.addAll(parsedBundles)
            } else {
                for (incoming in parsedBundles) {
                    val existingIdx = bundles.indexOfFirst { it.id == incoming.id }
                    if (existingIdx >= 0) {
                        bundles[existingIdx] = incoming
                    } else {
                        bundles.add(0, incoming) // Put newly imported bundles at the top of the Mod Menu!
                    }
                }
            }

            activeScriptSource = rebuildUnifiedDslFromBundles()

            if (saveToPrefs) {
                prefs.edit().putString("active_mod_script", activeScriptSource).apply()
                for (b in parsedBundles) {
                    prefs.edit().putBoolean("bundle_toggle_${b.id}", b.enabled).apply()
                }
            }
            val totalFns = bundles.fold(0) { acc, item -> acc + item.functions.size }
            lastStatusMessage = "Active: ${bundles.size} bundles ($totalFns core functions)"
            onStateChanged()
            return true
        } catch (e: Exception) {
            lastStatusMessage = "Script Parse Error: ${e.message}"
            return false
        }
    }

        private fun rebuildUnifiedDslFromBundles(): String {
        val sb = StringBuilder()
        sb.append("# SketchBook OSS - Unified Multi-Bundle Core Mod Script\n\n")
        for (b in bundles) {
            sb.append("@bundle id=\"").append(b.id)
                .append("\" title=\"").append(b.title)
                .append("\" default=").append(b.enabled)
                .append(" desc=\"").append(b.description).append("\"\n")
            for (fn in b.functions) {
                sb.append("fn ").append(fn.type)
                for ((k, v) in fn.params) {
                    if (v.contains(" ") || v.startsWith("#")) {
                        sb.append(" ").append(k).append("=\"").append(v).append("\"")
                    } else {
                        sb.append(" ").append(k).append("=").append(v)
                    }
                }
                sb.append("\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun parseDslScript(dsl: String, out: MutableList<ModFeatureBundle>) {
        var currentId = ""
        var currentTitle = ""
        var currentDesc = ""
        var currentDefault = false
        var currentFuncs = mutableListOf<ModFunctionSpec>()

        fun flushCurrent() {
            if (currentId.isNotEmpty()) {
                out.add(
                    ModFeatureBundle(
                        id = currentId,
                        title = currentTitle.ifEmpty { currentId },
                        description = currentDesc,
                        enabled = currentDefault,
                        functions = currentFuncs.toList()
                    )
                )
            }
        }

        for (rawLine in dsl.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            if (line.startsWith("@bundle")) {
                flushCurrent()
                currentFuncs = mutableListOf()
                val attrs = parseKeyValues(line.removePrefix("@bundle").trim())
                currentId = attrs["id"] ?: "bundle_${System.currentTimeMillis()}_${out.size}"
                currentTitle = attrs["title"] ?: currentId
                currentDesc = attrs["desc"] ?: "Bundled core functions"
                currentDefault = (attrs["default"] ?: "false").equals("true", ignoreCase = true)
            } else if (line.startsWith("fn ")) {
                val body = line.removePrefix("fn ").trim()
                val spaceIdx = body.indexOf(' ')
                val fnType = if (spaceIdx > 0) body.substring(0, spaceIdx).trim() else body
                val fnArgs = if (spaceIdx > 0) parseKeyValues(body.substring(spaceIdx + 1)) else emptyMap()
                currentFuncs.add(ModFunctionSpec(fnType, fnArgs))
            }
        }
        flushCurrent()
    }

    private fun parseJsonScript(jsonStr: String, out: MutableList<ModFeatureBundle>) {
        val rootArr = if (jsonStr.startsWith("{")) {
            JSONObject(jsonStr).optJSONArray("bundles") ?: JSONArray()
        } else {
            JSONArray(jsonStr)
        }
        for (i in 0 until rootArr.length()) {
            val obj = rootArr.getJSONObject(i)
            val id = obj.optString("id", "bundle_$i")
            val title = obj.optString("title", id)
            val desc = obj.optString("description", "")
            val enabled = obj.optBoolean("enabled", false)
            val fnArr = obj.optJSONArray("functions") ?: JSONArray()
            val fnList = mutableListOf<ModFunctionSpec>()
            for (j in 0 until fnArr.length()) {
                val fObj = fnArr.getJSONObject(j)
                val type = fObj.optString("type", "noop")
                val map = mutableMapOf<String, String>()
                val keys = fObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "type") map[k] = fObj.optString(k, "")
                }
                fnList.add(ModFunctionSpec(type, map))
            }
            out.add(ModFeatureBundle(id, title, desc, enabled, fnList))
        }
    }

    private fun parseKeyValues(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("(\\w+)\\s*=\\s*(\"([^\"]*)\"|(\\S+))")
        for (match in regex.findAll(input)) {
            val key = match.groupValues[1]
            val quoted = match.groupValues[3]
            val unquoted = match.groupValues[4]
            result[key] = if (match.groupValues[2].startsWith("\"")) quoted else unquoted
        }
        return result
    }

    fun toggleBundle(bundleId: String) {
        val b = bundles.find { it.id == bundleId } ?: return
        b.enabled = !b.enabled
        prefs.edit().putBoolean("bundle_toggle_${b.id}", b.enabled).apply()
        lastStatusMessage = "${b.title}: ${if (b.enabled) "ON" else "OFF"}"
        onStateChanged()
    }

    fun resetToDefaultScript() {
        prefs.edit().clear().apply()
        loadScript(DEFAULT_MULTI_BUNDLE_SCRIPT, saveToPrefs = true, mergeWithExisting = false)
    }

    fun getActiveFunctions(type: String): List<ModFunctionSpec> {
        val list = mutableListOf<ModFunctionSpec>()
        for (b in bundles) {
            if (b.enabled) {
                for (fn in b.functions) {
                    if (fn.type.equals(type, ignoreCase = true)) {
                        list.add(fn)
                    }
                }
            }
        }
        return list
    }

    fun hasActiveAnimations(): Boolean {
        for (b in bundles) {
            if (b.enabled) {
                for (fn in b.functions) {
                    if (fn.type.startsWith("ui_anim_")) return true
                }
            }
        }
        return false
    }

    fun getInjectedModBrushes(): List<SketchBrushItem> {
        val out = mutableListOf<SketchBrushItem>()
        for ((idx, fn) in getActiveFunctions("inject_brush").withIndex()) {
            val p = fn.params
            val id = p["id"] ?: "mod_brush_$idx"
            val name = p["name"] ?: "Mod Brush ${idx + 1}"
            val mode = p["mode"] ?: "procedural_web"
            val size = p["size"]?.toFloatOrNull() ?: 42f
            val opac = p["opacity"]?.toFloatOrNull() ?: 92f
            val spacing = p["spacing"]?.toFloatOrNull() ?: 10f
            val hardness = p["hardness"]?.toFloatOrNull() ?: 50f

            val iconStyle = when (mode) {
                "time_animated" -> BrushIconStyle.TEXTURE_FRACTAL
                "custom_harmonic_nib" -> BrushIconStyle.DIAMOND_SHADER
                else -> BrushIconStyle.TEXTURE_FROST
            }
            val bType = if (mode == "time_animated") SketchBrushType.GLOW else SketchBrushType.STANDARD

            out.add(
                SketchBrushItem(
                    id = id,
                    name = name,
                    iconStyle = iconStyle,
                    brushType = bType,
                    size = size,
                    opacity = opac,
                    spacing = spacing,
                    hardness = hardness,
                    modMode = mode,
                    modParams = p
                )
            )
        }
        return out
    }

    private fun parseHexOrDefault(hex: String?, defaultColor: Int): Int {
        if (hex.isNullOrBlank()) return defaultColor
        return try { Color.parseColor(hex) } catch (_: Exception) { defaultColor }
    }

    fun resolveUiPalette(): ModUiPalette {
        var pal = ModUiPalette()
        for (fn in getActiveFunctions("ui_theme")) {
            val p = fn.params
            pal = pal.copy(
                workspaceBackdrop = parseHexOrDefault(p["backdrop"], pal.workspaceBackdrop),
                topBarBg = parseHexOrDefault(p["topbar"], pal.topBarBg),
                topBarActiveSlot = parseHexOrDefault(p["topbar_active"], pal.topBarActiveSlot),
                popupBg = parseHexOrDefault(p["popup_bg"], pal.popupBg),
                popupHeaderBg = parseHexOrDefault(p["popup_header"], pal.popupHeaderBg),
                cardBg = parseHexOrDefault(p["card_bg"], pal.cardBg),
                inputBg = parseHexOrDefault(p["input_bg"], pal.inputBg),
                textPrimary = parseHexOrDefault(p["text_primary"], pal.textPrimary),
                textSecondary = parseHexOrDefault(p["text_secondary"], pal.textSecondary),
                iconTint = parseHexOrDefault(p["icon_tint"], pal.iconTint),
                accentColor = parseHexOrDefault(p["accent"], pal.accentColor),
                borderColor = parseHexOrDefault(p["border"], pal.borderColor)
            )
        }
        for (fn in getActiveFunctions("paper_tint")) {
            pal = pal.copy(paperTint = parseHexOrDefault(fn.params["color"], pal.paperTint))
        }
        return pal
    }

    fun getInjectedPresets(): List<CanvasPreset> {
        val out = mutableListOf<CanvasPreset>()
        for (fn in getActiveFunctions("inject_preset")) {
            val name = fn.params["name"] ?: "Mod Preset"
            val w = fn.params["w"]?.toIntOrNull() ?: 1920
            val h = fn.params["h"]?.toIntOrNull() ?: 1080
            out.add(CanvasPreset("mod_${name}_${w}x${h}", name, w, h))
        }
        return out
    }

    fun renderTopBarAnimatedEffects(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, timeSec: Float) {
        val w = right - left
        for (fn in getActiveFunctions("ui_anim_aurora")) {
            val c1 = parseHexOrDefault(fn.params["c1"], Color.argb(110, 0, 229, 255))
            val c2 = parseHexOrDefault(fn.params["c2"], Color.argb(110, 124, 77, 255))
            val c3 = parseHexOrDefault(fn.params["c3"], Color.argb(110, 255, 64, 129))
            val shift = (sin(timeSec * 2.2f) * 0.5f + 0.5f) * w
            animPaint.style = Paint.Style.FILL
            animPaint.shader = LinearGradient(
                left - w * 0.5f + shift, top,
                left + w * 0.8f + shift, bottom,
                intArrayOf(c1, c2, c3, c1),
                null,
                Shader.TileMode.MIRROR
            )
            canvas.drawRect(left, top, right, bottom, animPaint)
            animPaint.shader = null
        }

        for (fn in getActiveFunctions("ui_anim_laser_border")) {
            val beamColor = parseHexOrDefault(fn.params["color"], Color.rgb(0, 229, 255))
            val beamW = w * 0.32f
            val pos = ((timeSec * 0.65f) % 1.0f) * (w + beamW) - beamW * 0.5f
            animPaint.style = Paint.Style.FILL
            animPaint.shader = LinearGradient(
                left + pos - beamW / 2f, bottom - 4f,
                left + pos + beamW / 2f, bottom,
                intArrayOf(Color.TRANSPARENT, beamColor, Color.WHITE, beamColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(left, bottom - 5f, right, bottom, animPaint)
            animPaint.shader = null
        }
    }

    fun renderWorkspaceAnimatedEffects(canvas: Canvas, w: Float, h: Float, topBarH: Float, dotCx: Float, dotCy: Float, dotR: Float, timeSec: Float) {
        for (fn in getActiveFunctions("ui_anim_particles")) {
            val baseColor = parseHexOrDefault(fn.params["color"], Color.rgb(0, 229, 255))
            val count = (fn.params["count"]?.toIntOrNull() ?: 18).coerceIn(4, 40)
            animPaint.style = Paint.Style.FILL
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)

            for (i in 0 until count) {
                val seed = (i + 1) * 37.7f
                val px = ((sin(timeSec * (0.25f + (i % 5) * 0.06f) + seed) * 0.5f + 0.5f) * w)
                val py = topBarH + ((cos(timeSec * (0.18f + (i % 7) * 0.05f) + seed * 1.3f) * 0.5f + 0.5f) * (h - topBarH))
                val rad = (4f + (i % 4) * 3.5f) * (w / 1080f)
                val pulseAlpha = (50 + (sin(timeSec * 3f + i) * 35f).toInt()).coerceIn(15, 110)
                animPaint.color = Color.argb(pulseAlpha, r, g, b)
                canvas.drawCircle(px, py, rad, animPaint)
            }
        }

        for (fn in getActiveFunctions("ui_anim_dot_orbit")) {
            val ringColor = parseHexOrDefault(fn.params["color"], Color.rgb(0, 229, 255))
            animPaint.style = Paint.Style.STROKE
            animPaint.strokeWidth = 3f * (w / 1080f)
            val pulseR = dotR * (1.45f + 0.25f * sin(timeSec * 4.0f))
            animPaint.color = ringColor
            animPaint.alpha = 180
            val oval = RectF(dotCx - pulseR, dotCy - pulseR, dotCx + pulseR, dotCy + pulseR)
            val startAngle = (timeSec * 140f) % 360f
            canvas.drawArc(oval, startAngle, 110f, false, animPaint)
            canvas.drawArc(oval, startAngle + 180f, 110f, false, animPaint)
        }
    }

    fun renderPopupAnimatedBorder(canvas: Canvas, bounds: RectF, timeSec: Float) {
        if (bounds.isEmpty) return
        for (fn in getActiveFunctions("ui_anim_laser_border")) {
            val c1 = parseHexOrDefault(fn.params["color"], Color.rgb(0, 229, 255))
            val c2 = Color.rgb(160, 100, 255)
            val cx = bounds.centerX() + cos(timeSec * 3.0f) * bounds.width() * 0.5f
            val cy = bounds.centerY() + sin(timeSec * 3.0f) * bounds.height() * 0.5f
            animPaint.style = Paint.Style.STROKE
            animPaint.strokeWidth = 3.5f
            animPaint.shader = LinearGradient(
                bounds.left, bounds.top, cx, cy,
                intArrayOf(c1, Color.WHITE, c2, c1),
                null,
                Shader.TileMode.MIRROR
            )
            canvas.drawRect(bounds, animPaint)
            animPaint.shader = null
        }
    }

    fun renderCanvasOverlays(canvas: Canvas, rect: RectF, project: SketchProjectState) {
        for (fn in getActiveFunctions("canvas_grid")) {
            val divs = (fn.params["divisions"]?.toIntOrNull() ?: 3).coerceIn(2, 64)
            val colorStr = fn.params["color"] ?: "#550077CC"
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = (fn.params["stroke"]?.toFloatOrNull() ?: 2f)
            overlayPaint.color = parseHexOrDefault(colorStr, Color.argb(90, 0, 120, 215))
            overlayPaint.pathEffect = null

            val stepX = rect.width() / divs
            val stepY = rect.height() / divs
            for (i in 1 until divs) {
                val x = rect.left + stepX * i
                val y = rect.top + stepY * i
                canvas.drawLine(x, rect.top, x, rect.bottom, overlayPaint)
                canvas.drawLine(rect.left, y, rect.right, y, overlayPaint)
            }
        }

        for (fn in getActiveFunctions("perspective_crosshair")) {
            val colorStr = fn.params["color"] ?: "#66E65100"
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 2.5f
            overlayPaint.color = parseHexOrDefault(colorStr, Color.argb(110, 230, 81, 0))
            overlayPaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)

            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.drawLine(rect.left, cy, rect.right, cy, overlayPaint)
            canvas.drawLine(cx, rect.top, cx, rect.bottom, overlayPaint)
            canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, overlayPaint)
            canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, overlayPaint)
            val r = min(rect.width(), rect.height()) * 0.25f
            canvas.drawCircle(cx, cy, r, overlayPaint)
            overlayPaint.pathEffect = null
        }

        for (fn in getActiveFunctions("safe_frame")) {
            val marginPct = (fn.params["margin"]?.toFloatOrNull() ?: 0.08f).coerceIn(0.02f, 0.4f)
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 2f
            overlayPaint.color = Color.argb(120, 46, 125, 50)
            val dx = rect.width() * marginPct
            val dy = rect.height() * marginPct
            canvas.drawRect(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy, overlayPaint)
        }

        for (fn in getActiveFunctions("canvas_hud")) {
            val mpx = String.format("%.2fMpx", project.calcMegapixels())
            val maxL = SketchProjectState.calculateMaxLayers(project.width, project.height)
            val info = "${project.name} | ${project.width}x${project.height} | $mpx | L$maxL"
            overlayPaint.style = Paint.Style.FILL
            overlayPaint.color = Color.argb(185, 240, 240, 240)
            val pad = 14f
            val tw = textPaint.measureText(info)
            val boxTop = rect.top + 16f
            val boxLeft = rect.left + 16f
            canvas.drawRect(boxLeft, boxTop, boxLeft + tw + pad * 2, boxTop + 46f, overlayPaint)
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.color = Color.rgb(120, 120, 120)
            overlayPaint.strokeWidth = 1.5f
            canvas.drawRect(boxLeft, boxTop, boxLeft + tw + pad * 2, boxTop + 46f, overlayPaint)
            canvas.drawText(info, boxLeft + pad, boxTop + 32f, textPaint)
        }
    }

    companion object {
        val DEFAULT_MULTI_BUNDLE_SCRIPT =
            "# SketchBook OSS - Programmable Brush Shader VM & Core Mod Script\n" +
            "@bundle id=\"vm_shader_brushes\" title=\"Programmable VM Shader Brushes (8 Unique Engines)\" default=true desc=\"True code-driven brushes: 3D Pipe, Gravity Drip, Mycelium Roots, 7-Nib Rake, Dragon Scales & Glitch\"\n" +
            "fn inject_brush id=\"vm_3d_pipe\" name=\"3D Specular Pipe\" mode=\"vm\" size=58 opacity=100 spacing=4 code=\"ribbon(1, 1.0, 0.0, h, s, l*0.35, a); ribbon(1, 0.72, -0.08, h, s, l*0.75, a); ribbon(1, 0.42, -0.18, h, s, min(0.95, l*1.15+0.15), a); ribbon(1, 0.16, -0.26, h, s*0.25, 0.96, a*0.95);\"\n" +
            "fn inject_brush id=\"vm_street_drip\" name=\"Street-Art Gravity Drip\" mode=\"vm\" size=44 opacity=95 spacing=8 code=\"circle(0, 0, r, h, s, l, a, 0.35); for(k, 0, 5, 1) { var ang2 = rand()*6.28; var d2 = r*(0.6+rand()*0.9); circle(cos(ang2)*d2, sin(ang2)*d2, r*(0.06+rand()*0.12), h, s, l, a*0.8, 0.1); }; if(rand() > 0.78) { drip(srnd()*r*0.4, r*0.3, 0, 45+rand()*60, r*(0.10+rand()*0.12), 1.4+rand()*1.8, 140, 0, h, s, l, a*0.92); };\"\n" +
            "fn inject_brush id=\"vm_mycelium\" name=\"Living Mycelium Roots\" mode=\"vm\" size=36 opacity=92 spacing=14 code=\"circle(0, 0, r*0.35, h, s, l, a, 0.4); if(rand() > 0.35) { var dir = rad + 1.57*srnd(); drip(0, 0, cos(dir)*75, sin(dir)*75, r*0.18, 1.2+rand()*1.2, 0, 260, h+srnd()*25, s, l, a*0.85); };\"\n" +
            "fn inject_brush id=\"vm_rake7\" name=\"7-Nib Calligrapher Rake\" mode=\"vm\" size=68 opacity=92 spacing=4 code=\"for(k, -3, 3, 1) { var off = k*0.32; var w = 0.18*(1.0 - abs(k)*0.12); ribbon(1, w, off, h+k*6, s, clamp(l+k*0.04, 0.08, 0.92), a); };\"\n" +
            "fn inject_brush id=\"vm_dragon_scale\" name=\"Dragon Scale Armor\" mode=\"vm\" size=76 opacity=96 spacing=38 code=\"var rows = 2; for(k, -1, 1, 1) { var oy = (k + mod(i, 2)*0.5)*r*0.58; var px = nx*oy; var py = ny*oy; poly(6, px, py, r*0.48, ang, h, s, l*0.35, a, 1); poly(6, px, py, r*0.40, ang, h+k*10, s, clamp(l*0.95+0.1, 0.1, 0.9), a, 1); oval(px+tx*r*0.1, py+ty*r*0.1, r*0.22, r*0.12, ang, h, s*0.4, min(0.98, l+0.35), a*0.85, 0.3); };\"\n" +
            "fn inject_brush id=\"vm_glitch\" name=\"Chromatic Glitch Smear\" mode=\"vm\" size=64 opacity=90 spacing=12 code=\"var ch = pickH(srnd()*r, srnd()*r); var cs = pickS(0, 0); var cl = pickL(0, 0); for(k, 0, 4, 1) { var sy = srnd()*r*0.9; var sw = r*(0.6+rand()*1.2); var sh = r*(0.06+rand()*0.14); line(-sw-r*0.25, sy, sw-r*0.25, sy, sh, 0, 0.95, 0.55, a*0.55); line(-sw+r*0.25, sy, sw+r*0.25, sy, sh, 190, 0.95, 0.55, a*0.55); line(-sw, sy, sw, sy, sh*0.8, ch, cs, cl, a*0.85); };\"\n" +
            "fn inject_brush id=\"vm_smoke\" name=\"Perlin Volumetric Smoke\" mode=\"vm\" size=82 opacity=75 spacing=14 code=\"for(k, 0, 5, 1) { var nx2 = noise(x*0.018+k*3.1, y*0.018)*r*1.1; var ny2 = noise(y*0.018+k*5.7, x*0.018)*r*1.1; circle(nx2, ny2, r*(0.45+0.35*abs(noise(i*0.2, k))), h+noise(i*0.1, k)*20, s*0.7, clamp(l+k*0.05, 0.1, 0.95), a*0.28, 0.92); };\"\n" +
            "fn inject_brush id=\"vm_zipper\" name=\"Stitched Leather Seam\" mode=\"vm\" size=52 opacity=96 spacing=28 code=\"ribbon(1, 0.22, 0.0, h, s, l*0.35, a); var side = (mod(i, 2)*2 - 1); line(0, 0, nx*r*0.75*side - tx*r*0.25, ny*r*0.75*side - ty*r*0.25, r*0.14, h, s*0.5, min(0.95, l+0.35), a); circle(nx*r*0.75*side - tx*r*0.25, ny*r*0.75*side - ty*r*0.25, r*0.11, h, s, l*0.25, a, 0.1);\"\n\n" +
            "@bundle id=\"obsidian_dark_ui\" title=\"Obsidian Dark Pro UI\" default=false desc=\"Transforms top bar, popups, icons & backdrop into carbon dark mode\"\n" +
            "fn ui_theme topbar=\"#1B1E26\" topbar_active=\"#2B303C\" popup_bg=\"#232731\" popup_header=\"#171A21\" card_bg=\"#2C313E\" input_bg=\"#14171D\" text_primary=\"#ECEFF4\" text_secondary=\"#9AA5B8\" icon_tint=\"#D8DEE9\" accent=\"#00B8D4\" border=\"#383F4E\" backdrop=\"#0F1116\"\n\n" +
            "@bundle id=\"anim_aurora_fx\" title=\"Animated Aurora & Laser FX\" default=false desc=\"60fps flowing aurora shimmer on topbar + laser popup border + dot orbit\"\n" +
            "fn ui_anim_aurora c1=\"#6600E5FF\" c2=\"#667C4DFF\" c3=\"#66FF4081\"\n" +
            "fn ui_anim_laser_border color=\"#00E5FF\"\n" +
            "fn ui_anim_dot_orbit color=\"#00E5FF\"\n\n" +
            "@bundle id=\"composition_guides\" title=\"Composition & Grid Guides\" default=false desc=\"Rule-of-thirds + 12-div subgrid + safe frame\"\n" +
            "fn canvas_grid divisions=3 color=\"#662B6CB0\" stroke=2.5\n" +
            "fn canvas_grid divisions=12 color=\"#282B6CB0\" stroke=1.0\n" +
            "fn safe_frame margin=0.06\n\n" +
            "@bundle id=\"cinema_presets\" title=\"Cinema & Social Presets Pack\" default=true desc=\"Injects 4K UHD, Cinema 21:9, and Square 2K presets\"\n" +
            "fn inject_preset name=\"4K UHD\" w=3840 h=2160\n" +
            "fn inject_preset name=\"Cinema 21:9\" w=2560 h=1080\n" +
            "fn inject_preset name=\"Square 2K\" w=2048 h=2048"
    }
}
