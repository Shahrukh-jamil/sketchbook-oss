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

    private val prefs = context.getSharedPreferences("sketchbook_mod_core_v6", Context.MODE_PRIVATE)
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
            "# SketchBook OSS - Persistently Animated 60fps VM Brush Suite\n" +
            "@bundle id=\"persistent_anim_brushes\" title=\"60fps Persistently Animated Brushes (8 Engines)\" default=true desc=\"8 constantly animating brushes with distinct functional icons\"\n" +
            "fn inject_brush id=\"pa_serpent\" name=\"Slithering Cyber-Serpent\" icon=\"serpent\" mode=\"vm_anim\" size=56 opacity=96 spacing=18 code=\"var wv = sin(t*6.5 - i*0.45)*r*0.65; set_pos(nx*wv, ny*wv); ribbon(1, 0.55, 0.0, h, s, l*0.45, a); poly(6, 0, 0, r*0.48, ang + cos(t*6.5 - i*0.45)*25, mod(h + i*4, 360), s, clamp(l+0.15, 0.15, 0.9), a, 1); var fin = (0.55 + 0.45*sin(t*8.0 - i*0.5))*r*1.15; line(0, 0, nx*fin - tx*r*0.35, ny*fin - ty*r*0.35, r*0.10, h+45, 0.95, 0.75, a*0.85); line(0, 0, -nx*fin - tx*r*0.35, -ny*fin - ty*r*0.35, r*0.10, h+45, 0.95, 0.75, a*0.85);\"\n" +
            "fn inject_brush id=\"pa_clockwork\" name=\"Meshing Clockwork Gears\" icon=\"clock\" mode=\"vm_anim\" size=74 opacity=95 spacing=52 code=\"var dir = mod(i, 2)*2 - 1; var rot = t*95.0*dir + i*22.5; arc(0, 0, r*0.68, 0, 360, r*0.18, h + mod(i,2)*35, s, clamp(l+0.1, 0.2, 0.85), a); for(k, 0, 7, 1) { var ga = (rot + k*45.0)*0.017453; line(cos(ga)*r*0.55, sin(ga)*r*0.55, cos(ga)*r*0.95, sin(ga)*r*0.95, r*0.20, h + mod(i,2)*35, s, clamp(l+0.2, 0.25, 0.92), a); }; for(sp, 0, 2, 1) { var sa = (rot + sp*120.0)*0.017453; line(0, 0, cos(sa)*r*0.62, sin(sa)*r*0.62, r*0.09, h, s*0.5, 0.9, a*0.85); }; circle(0, 0, r*0.18, h, 0.2, 0.95, a, 0.1);\"\n" +
            "fn inject_brush id=\"pa_tesla_arc\" name=\"High-Voltage Tesla Arc\" icon=\"tesla\" mode=\"vm_anim\" size=50 opacity=98 spacing=24 code=\"var jx = noise(i*0.8, t*16.0)*r*0.85; var jy = noise(i*0.8 + 9.3, t*16.0)*r*0.85; set_pos(jx, jy); hline(1, 0, 0, 0, 0, r*0.42, h, 0.95, 0.55, a*0.38); hline(1, 0, 0, 0, 0, r*0.14, h, 0.3, 0.98, a); var sx2 = noise(i*1.7, t*22.0)*r*1.3; var sy2 = noise(t*22.0, i*1.7)*r*1.3; line(0, 0, sx2, sy2, r*0.07, h+30, 0.8, 0.92, a*0.75);\"\n" +
            "fn inject_brush id=\"pa_dna_helix\" name=\"3D Twisting DNA Helix\" icon=\"dna\" mode=\"vm_anim\" size=64 opacity=96 spacing=16 code=\"var ph = t*5.0 + i*0.42; var s1 = sin(ph)*r*0.78; var z1 = cos(ph); var s2 = -s1; var z2 = -z1; line(nx*s1, ny*s1, nx*s2, ny*s2, r*0.09, h+60, 0.5, 0.85, a*0.55); circle(nx*s1, ny*s1, r*(0.22 + 0.08*z1), h, s, clamp(l + z1*0.22, 0.15, 0.95), a, 0.15); circle(nx*s2, ny*s2, r*(0.22 + 0.08*z2), mod(h+150, 360), s, clamp(l + z2*0.22, 0.15, 0.95), a, 0.15);\"\n" +
            "fn inject_brush id=\"pa_portal\" name=\"Kaleidoscope Vortex Portal\" icon=\"portal\" mode=\"vm_anim\" size=84 opacity=94 spacing=62 code=\"poly(8, 0, 0, r*0.92, t*65.0 + i*15, h, s, l, a*0.75, 0); poly(4, 0, 0, r*0.68, -t*95.0 + i*30, mod(h+60, 360), s, clamp(l+0.15, 0.2, 0.9), a*0.85, 0); poly(4, 0, 0, r*0.68, -t*95.0 + 45 + i*30, mod(h+120, 360), s, clamp(l+0.2, 0.2, 0.95), a*0.85, 0); arc(0, 0, r*0.38, t*180.0, 220, r*0.12, h, 0.2, 0.98, a);\"\n" +
            "fn inject_brush id=\"pa_synth_eq\" name=\"Synthwave Audio Equalizer\" icon=\"synth\" mode=\"vm_anim\" size=68 opacity=95 spacing=16 code=\"var amp = (0.15 + 0.85*abs(sin(t*7.5 + i*0.55)*cos(t*4.2 - i*0.3)))*r*1.25; line(-nx*amp, -ny*amp, nx*amp, ny*amp, r*0.22, mod(h + amp*1.2, 360), 0.95, 0.60, a*0.85); var peak = amp + r*0.22 + abs(sin(t*3.5 + i*0.55))*r*0.25; circle(nx*peak, ny*peak, r*0.11, h, 0.1, 0.98, a, 0.05); circle(-nx*peak, -ny*peak, r*0.11, h, 0.1, 0.98, a, 0.05);\"\n" +
            "fn inject_brush id=\"pa_atomic\" name=\"Atomic Quantum Orbitals\" icon=\"atom\" mode=\"vm_anim\" size=76 opacity=95 spacing=56 code=\"circle(0, 0, r*(0.24 + 0.06*sin(t*8.0 + i)), h, s, 0.92, a, 0.35); for(k, 0, 2, 1) { var tilt = ang + k*60.0; var tr = tilt*0.017453; oval(0, 0, r*0.92, r*0.34, tilt, mod(h + k*45, 360), s*0.8, 0.65, a*0.38, 0.1); var ea = t*(6.0 + k*1.5) + i + k*2.1; var ex = cos(ea)*r*0.92; var ey = sin(ea)*r*0.34; var rx2 = ex*cos(tr) - ey*sin(tr); var ry2 = ex*sin(tr) + ey*cos(tr); circle(rx2, ry2, r*0.14, mod(h + k*45, 360), 0.2, 0.98, a, 0.1); };\"\n" +
            "fn inject_brush id=\"pa_lava_plume\" name=\"Molten Lava & Rising Embers\" icon=\"lava\" mode=\"vm_anim\" size=58 opacity=96 spacing=18 code=\"var pulse = 0.8 + 0.25*sin(t*5.0 + i*0.7); circle(0, 0, r*0.55*pulse, mod(h + sin(t*3.0 + i)*20, 360), 0.98, 0.48, a, 0.45); circle(0, 0, r*0.28*pulse, mod(h + 35, 360), 0.95, 0.82, a, 0.2); for(k, 0, 2, 1) { var life = fract(t*(0.8 + k*0.25) + i*0.19 + k*0.33); var riseY = -life*r*1.85; var swayX = sin(life*6.28 + i + k)*r*0.45; circle(swayX, riseY, r*0.22*(1.0 - life*0.75), mod(h + life*50, 360), 0.95, 0.75 - life*0.35, a*(1.0 - life), 0.3); };\"\n\n" +
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
