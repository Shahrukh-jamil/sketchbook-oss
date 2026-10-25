package com.sketchbook.oss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Core Mod Engine where a SINGLE Mod Script bundles multiple independent Feature Toggles.
 * Each Feature Bundle groups multiple core functions (canvas hooks, preset injectors,
 * workspace theme overrides, HUD overlays, and procedural actions) under ONE toggle in Core.
 */
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

class ModCoreEngine(private val context: Context, private val onStateChanged: () -> Unit) {

    private val prefs = context.getSharedPreferences("sketchbook_mod_core", Context.MODE_PRIVATE)
    val bundles = mutableListOf<ModFeatureBundle>()
    var activeScriptSource: String = ""
        private set
    var lastStatusMessage: String = "Core Mod Engine Ready"

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 30, 30, 30)
        textSize = 28f
        isFakeBoldText = true
    }

    init {
        val savedScript = prefs.getString("active_mod_script", null)
        if (savedScript.isNullOrBlank()) {
            loadScript(DEFAULT_MULTI_BUNDLE_SCRIPT, saveToPrefs = true)
        } else {
            loadScript(savedScript, saveToPrefs = false)
        }
    }

    /**
     * Parses a single unified Mod Script (supports both declarative SketchMod DSL and JSON format)
     * into multiple independent Feature Bundles, each with its own Core ON/OFF toggle.
     */
    fun loadScript(scriptText: String, saveToPrefs: Boolean = true): Boolean {
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

            // Restore saved toggle states per bundle ID
            for (b in parsedBundles) {
                if (prefs.contains("bundle_toggle_${b.id}")) {
                    b.enabled = prefs.getBoolean("bundle_toggle_${b.id}", b.enabled)
                }
            }

            bundles.clear()
            bundles.addAll(parsedBundles)
            activeScriptSource = trimmed

            if (saveToPrefs) {
                prefs.edit().putString("active_mod_script", trimmed).apply()
            }
            lastStatusMessage = "Loaded ${bundles.size} feature bundles (${bundles.sumOf { it.functions.size }} functions)"
            onStateChanged()
            return true
        } catch (e: Exception) {
            lastStatusMessage = "Script Parse Error: ${e.message}"
            return false
        }
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

        val lines = dsl.lines()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            if (line.startsWith("@bundle")) {
                flushCurrent()
                currentFuncs = mutableListOf()
                // Format: @bundle id="grid_pack" title="Pro Grid & Guides" default=false desc="..."
                val attrs = parseKeyValues(line.removePrefix("@bundle").trim())
                currentId = attrs["id"] ?: "bundle_${out.size + 1}"
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
        val regex = Regex("""(\w+)\s*=\s*("([^"]*)"|(\S+))""")
        for (match in regex.findAll(input)) {
            val key = match.groupValues[1]
            val quoted = match.groupValues[3]
            val unquoted = match.groupValues[4]
            result[key] = if (match.groupValues[2].startsWith(""")) quoted else unquoted
        }
        return result
    }

    fun setBundleEnabled(bundleId: String, enabled: Boolean) {
        val b = bundles.find { it.id == bundleId } ?: return
        b.enabled = enabled
        prefs.edit().putBoolean("bundle_toggle_${b.id}", enabled).apply()
        lastStatusMessage = "${b.title}: ${if (enabled) "ENABLED" else "DISABLED"}"
        onStateChanged()
    }

    fun toggleBundle(bundleId: String) {
        val b = bundles.find { it.id == bundleId } ?: return
        setBundleEnabled(bundleId, !b.enabled)
    }

    fun resetToDefaultScript() {
        prefs.edit().clear().apply()
        loadScript(DEFAULT_MULTI_BUNDLE_SCRIPT, saveToPrefs = true)
    }

    // --------------------------------------------------------------------------
    // CORE EXECUTION HOOKS (Automatically gated by each Bundle's toggle state)
    // --------------------------------------------------------------------------

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

    fun getWorkspaceColorOverride(defaultColor: Int): Int {
        val themeFns = getActiveFunctions("workspace_theme")
        if (themeFns.isNotEmpty()) {
            val hex = themeFns.last().params["backdrop"]
            if (!hex.isNullOrEmpty()) {
                return try { Color.parseColor(hex) } catch (_: Exception) { defaultColor }
            }
        }
        return defaultColor
    }

    fun getTopBarColorOverride(defaultColor: Int): Int {
        val themeFns = getActiveFunctions("workspace_theme")
        if (themeFns.isNotEmpty()) {
            val hex = themeFns.last().params["topbar"]
            if (!hex.isNullOrEmpty()) {
                return try { Color.parseColor(hex) } catch (_: Exception) { defaultColor }
            }
        }
        return defaultColor
    }

    fun getCanvasPaperTintOverride(defaultColor: Int): Int {
        val tintFns = getActiveFunctions("paper_tint")
        if (tintFns.isNotEmpty()) {
            val hex = tintFns.last().params["color"]
            if (!hex.isNullOrEmpty()) {
                return try { Color.parseColor(hex) } catch (_: Exception) { defaultColor }
            }
        }
        return defaultColor
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

    fun renderCanvasOverlays(canvas: Canvas, rect: RectF, project: SketchProjectState) {
        // 1. Grid Overlay function
        for (fn in getActiveFunctions("canvas_grid")) {
            val divs = (fn.params["divisions"]?.toIntOrNull() ?: 3).coerceIn(2, 64)
            val colorStr = fn.params["color"] ?: "#550077CC"
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = (fn.params["stroke"]?.toFloatOrNull() ?: 2f)
            overlayPaint.color = try { Color.parseColor(colorStr) } catch (_: Exception) { Color.argb(90, 0, 120, 215) }
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

        // 2. Perspective / Crosshair Axis Guide function
        for (fn in getActiveFunctions("perspective_crosshair")) {
            val colorStr = fn.params["color"] ?: "#66E65100"
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 2.5f
            overlayPaint.color = try { Color.parseColor(colorStr) } catch (_: Exception) { Color.argb(110, 230, 81, 0) }
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

        // 3. Golden Ratio / Safe Frame function
        for (fn in getActiveFunctions("safe_frame")) {
            val marginPct = (fn.params["margin"]?.toFloatOrNull() ?: 0.08f).coerceIn(0.02f, 0.4f)
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 2f
            overlayPaint.color = Color.argb(120, 46, 125, 50)
            val dx = rect.width() * marginPct
            val dy = rect.height() * marginPct
            canvas.drawRect(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy, overlayPaint)
        }

        // 4. Live Canvas Specs HUD
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
        val DEFAULT_MULTI_BUNDLE_SCRIPT = """
# SketchBook OSS - Unified Multi-Bundle Core Mod Script
# One script defines multiple independent feature bundles.
# Each @bundle creates a Core-level toggle grouping multiple functions together.

@bundle id="composition_guides" title="Composition & Grid Guides" default=false desc="Rule-of-thirds grid + 12-div subgrid + action safe frame"
fn canvas_grid divisions=3 color="#662B6CB0" stroke=2.5
fn canvas_grid divisions=12 color="#282B6CB0" stroke=1.0
fn safe_frame margin=0.06

@bundle id="perspective_symmetry" title="Symmetry & Vanishing Rays" default=false desc="Center X/Y symmetry axes + diagonal vanishing rays + compass ring"
fn perspective_crosshair color="#77D84315"

@bundle id="cinema_presets" title="Cinema & Social Presets Pack" default=true desc="Injects 4K UHD, CinemaScope 21:9, and Square 2K into New Sketch presets"
fn inject_preset name="4K UHD" w=3840 h=2160
fn inject_preset name="Cinema 21:9" w=2560 h=1080
fn inject_preset name="Square 2K" w=2048 h=2048

@bundle id="warm_sketchpad" title="Warm Cream Sketchpad Paper" default=false desc="Tints canvas paper to eye-care warm archival cream & darkens backdrop"
fn paper_tint color="#FDFBF2"
fn workspace_theme backdrop="#585858" topbar="#E6E6E6"

@bundle id="telemetry_hud" title="Live Canvas Telemetry HUD" default=false desc="Displays project name, pixel dimensions, Mpx & max layer count on canvas"
fn canvas_hud mode="compact"
""".trimIndent()
    }
}
