package com.edgeledger.app.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ============================================================================
// 1. Serializable Theme Enums (CL-03, CL-08, SH-02..05, TY-01..02, MO-01, MO-04)
// ============================================================================

@Serializable
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    AMOLED("AMOLED Black"),
    LIGHT("Light"),
    DEUTERANOPIA("Deuteranopia Safe"),
    PROTANOPIA("Protanopia Safe"),
    TRITANOPIA("Tritanopia Safe");

    val isLight: Boolean
        get() = this == LIGHT

    val isColorBlindSafe: Boolean
        get() = this == DEUTERANOPIA || this == PROTANOPIA || this == TRITANOPIA
}

@Serializable
enum class DensityPreset(val label: String) {
    COMPACT("Compact"),
    COMFORTABLE("Comfortable"),
    SPACIOUS("Spacious")
}

@Serializable
enum class ShapeFamily(val label: String) {
    SHARP("Sharp"),
    ROUNDED("Rounded"),
    CUT("Cut"),
    SOFT("Soft")
}

@Serializable
enum class SurfaceStyle(val label: String) {
    OUTLINED("Outlined"),
    TONAL("Tonal"),
    ELEVATED("Elevated"),
    FLAT("Flat")
}

@Serializable
enum class FontFamilyPreset(val label: String) {
    SYSTEM("System Sans"),
    SERIF("Editorial Serif"),
    MONO("Sharp Mono"),
    ROUNDED("Friendly Sans")
}

@Serializable
enum class FixtureLayoutMode(val label: String) {
    ANALYTICAL("Full Card"),
    COMPACT_ROW("Compact Odds Row")
}

@Serializable
enum class MotionSpeed(val label: String, val multiplier: Float) {
    OFF("Off", 0f),
    REDUCED("Reduced", 0.5f),
    NORMAL("Normal", 1.0f),
    SNAPPY("Snappy", 1.5f)
}

@Serializable
enum class HapticLevel(val label: String) {
    OFF("Off"),
    SUBTLE("Subtle"),
    CRISP("Crisp")
}

// ============================================================================
// 2. Theme Configuration Schema v2 (Lossless Migration from v1 — TH-07)
// ============================================================================

@Immutable
@Serializable
data class ThemeConfigV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String = "midnight-broadcast",
    val name: String = "Midnight Broadcast",
    val mode: ThemeMode = ThemeMode.DARK,
    val useDynamicColor: Boolean = false,
    // All 9 editable semantic betting colors (CL-05)
    val accentHex: String = DEFAULT_ACCENT_HEX,
    val winHex: String = DEFAULT_WIN_HEX,
    val drawHex: String = DEFAULT_DRAW_HEX,
    val lossHex: String = DEFAULT_LOSS_HEX,
    val pendingHex: String = DEFAULT_PENDING_HEX,
    val oddsUpHex: String = DEFAULT_ODDS_UP_HEX,
    val oddsDownHex: String = DEFAULT_ODDS_DOWN_HEX,
    val valueHighlightHex: String = DEFAULT_VALUE_HIGHLIGHT_HEX,
    val sharpBadgeHex: String = DEFAULT_SHARP_BADGE_HEX,
    // Visual atmosphere (CL-06)
    val useGradientHero: Boolean = true,
    val useBackgroundGradient: Boolean = false,
    // Layout, shape, and surface geometry (SH-01..05)
    val density: DensityPreset = DensityPreset.COMFORTABLE,
    val shapeFamily: ShapeFamily = ShapeFamily.ROUNDED,
    val surfaceStyle: SurfaceStyle = SurfaceStyle.OUTLINED,
    val cardCornerOverrideDp: Int? = null,
    // Typography (TY-01, TY-04)
    val fontFamily: FontFamilyPreset = FontFamilyPreset.SYSTEM,
    val fontScale: Float = 1.0f,
    // Fixture list density (SH-05)
    val fixtureLayout: FixtureLayoutMode = FixtureLayoutMode.ANALYTICAL,
    // Power optimization (ED-08)
    val batterySaverAutoAmoled: Boolean = false,
    // Motion & tactile feedback (MO-01, MO-04)
    val motionSpeed: MotionSpeed = MotionSpeed.NORMAL,
    val haptics: HapticLevel = HapticLevel.SUBTLE
) {
    fun sanitized(): ThemeConfigV1 {
        val cleanId = id.trim().ifBlank { "custom-theme" }.take(64)
        val cleanName = name.trim().ifBlank { "Custom Theme" }.take(48)
        val cleanFontScale = if (fontScale.isNaN() || fontScale.isInfinite()) {
            1.0f
        } else {
            fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        }
        return copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            id = cleanId,
            name = cleanName,
            accentHex = normalizeHexOrDefault(accentHex, DEFAULT_ACCENT_HEX),
            winHex = normalizeHexOrDefault(winHex, DEFAULT_WIN_HEX),
            drawHex = normalizeHexOrDefault(drawHex, DEFAULT_DRAW_HEX),
            lossHex = normalizeHexOrDefault(lossHex, DEFAULT_LOSS_HEX),
            pendingHex = normalizeHexOrDefault(pendingHex, DEFAULT_PENDING_HEX),
            oddsUpHex = normalizeHexOrDefault(oddsUpHex, DEFAULT_ODDS_UP_HEX),
            oddsDownHex = normalizeHexOrDefault(oddsDownHex, DEFAULT_ODDS_DOWN_HEX),
            valueHighlightHex = normalizeHexOrDefault(valueHighlightHex, DEFAULT_VALUE_HIGHLIGHT_HEX),
            sharpBadgeHex = normalizeHexOrDefault(sharpBadgeHex, DEFAULT_SHARP_BADGE_HEX),
            cardCornerOverrideDp = cardCornerOverrideDp?.coerceIn(MIN_CORNER_OVERRIDE_DP, MAX_CORNER_OVERRIDE_DP),
            fontScale = cleanFontScale
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
        const val MIN_FONT_SCALE: Float = 0.85f
        const val MAX_FONT_SCALE: Float = 1.35f
        const val MIN_CORNER_OVERRIDE_DP: Int = 0
        const val MAX_CORNER_OVERRIDE_DP: Int = 28

        const val DEFAULT_ACCENT_HEX: String = "#38BDF8"
        const val DEFAULT_WIN_HEX: String = "#22C55E"
        const val DEFAULT_DRAW_HEX: String = "#F59E0B"
        const val DEFAULT_LOSS_HEX: String = "#EF4444"
        const val DEFAULT_PENDING_HEX: String = "#94A3B8"
        const val DEFAULT_ODDS_UP_HEX: String = "#10B981"
        const val DEFAULT_ODDS_DOWN_HEX: String = "#F43F5E"
        const val DEFAULT_VALUE_HIGHLIGHT_HEX: String = "#A855F7"
        const val DEFAULT_SHARP_BADGE_HEX: String = "#06B6D4"
    }
}

// ============================================================================
// 3. Curated Presets, Accent Swatches & Team Palettes (CL-07)
// ============================================================================

@Immutable
@Serializable
data class TeamPalettePreset(
    val name: String,
    val accentHex: String,
    val winHex: String,
    val drawHex: String,
    val lossHex: String
)

object ThemePresets {

    val midnightBroadcast: ThemeConfigV1 = ThemeConfigV1(
        schemaVersion = ThemeConfigV1.CURRENT_SCHEMA_VERSION,
        id = "midnight-broadcast",
        name = "Midnight Broadcast",
        mode = ThemeMode.DARK,
        useDynamicColor = false,
        accentHex = "#38BDF8",
        winHex = "#22C55E",
        drawHex = "#F59E0B",
        lossHex = "#EF4444",
        pendingHex = "#94A3B8",
        oddsUpHex = "#10B981",
        oddsDownHex = "#F43F5E",
        valueHighlightHex = "#A855F7",
        sharpBadgeHex = "#06B6D4",
        useGradientHero = true,
        useBackgroundGradient = false,
        density = DensityPreset.COMFORTABLE,
        shapeFamily = ShapeFamily.ROUNDED,
        surfaceStyle = SurfaceStyle.OUTLINED,
        cardCornerOverrideDp = null,
        fontFamily = FontFamilyPreset.SYSTEM,
        fontScale = 1.0f,
        fixtureLayout = FixtureLayoutMode.ANALYTICAL,
        batterySaverAutoAmoled = false,
        motionSpeed = MotionSpeed.NORMAL,
        haptics = HapticLevel.SUBTLE
    )

    val amoledPureBlack: ThemeConfigV1 = ThemeConfigV1(
        schemaVersion = ThemeConfigV1.CURRENT_SCHEMA_VERSION,
        id = "amoled-pure-black",
        name = "AMOLED Pure Black",
        mode = ThemeMode.AMOLED,
        useDynamicColor = false,
        accentHex = "#22D3EE",
        winHex = "#34D399",
        drawHex = "#FBBF24",
        lossHex = "#FB7185",
        pendingHex = "#94A3B8",
        oddsUpHex = "#2DD4BF",
        oddsDownHex = "#F43F5E",
        valueHighlightHex = "#C084FC",
        sharpBadgeHex = "#38BDF8",
        useGradientHero = true,
        useBackgroundGradient = false,
        density = DensityPreset.COMFORTABLE,
        shapeFamily = ShapeFamily.ROUNDED,
        surfaceStyle = SurfaceStyle.OUTLINED,
        cardCornerOverrideDp = null,
        fontFamily = FontFamilyPreset.SYSTEM,
        fontScale = 1.0f,
        fixtureLayout = FixtureLayoutMode.ANALYTICAL,
        batterySaverAutoAmoled = false,
        motionSpeed = MotionSpeed.NORMAL,
        haptics = HapticLevel.SUBTLE
    )

    val crispDaylight: ThemeConfigV1 = ThemeConfigV1(
        schemaVersion = ThemeConfigV1.CURRENT_SCHEMA_VERSION,
        id = "crisp-daylight",
        name = "Crisp Daylight",
        mode = ThemeMode.LIGHT,
        useDynamicColor = false,
        accentHex = "#0284C7",
        winHex = "#15803D",
        drawHex = "#B45309",
        lossHex = "#DC2626",
        pendingHex = "#64748B",
        oddsUpHex = "#059669",
        oddsDownHex = "#E11D48",
        valueHighlightHex = "#7C3AED",
        sharpBadgeHex = "#0891B2",
        useGradientHero = true,
        useBackgroundGradient = false,
        density = DensityPreset.COMFORTABLE,
        shapeFamily = ShapeFamily.ROUNDED,
        surfaceStyle = SurfaceStyle.ELEVATED,
        cardCornerOverrideDp = null,
        fontFamily = FontFamilyPreset.SYSTEM,
        fontScale = 1.0f,
        fixtureLayout = FixtureLayoutMode.ANALYTICAL,
        batterySaverAutoAmoled = false,
        motionSpeed = MotionSpeed.NORMAL,
        haptics = HapticLevel.SUBTLE
    )

    val emeraldSharp: ThemeConfigV1 = ThemeConfigV1(
        schemaVersion = ThemeConfigV1.CURRENT_SCHEMA_VERSION,
        id = "emerald-sharp",
        name = "Emerald Sharp",
        mode = ThemeMode.DARK,
        useDynamicColor = false,
        accentHex = "#10B981",
        winHex = "#34D399",
        drawHex = "#F59E0B",
        lossHex = "#F43F5E",
        pendingHex = "#94A3B8",
        oddsUpHex = "#22C55E",
        oddsDownHex = "#FB7185",
        valueHighlightHex = "#38BDF8",
        sharpBadgeHex = "#FACC15",
        useGradientHero = false,
        useBackgroundGradient = false,
        density = DensityPreset.COMPACT,
        shapeFamily = ShapeFamily.SHARP,
        surfaceStyle = SurfaceStyle.OUTLINED,
        cardCornerOverrideDp = null,
        fontFamily = FontFamilyPreset.MONO,
        fontScale = 0.95f,
        fixtureLayout = FixtureLayoutMode.COMPACT_ROW,
        batterySaverAutoAmoled = false,
        motionSpeed = MotionSpeed.SNAPPY,
        haptics = HapticLevel.CRISP
    )

    val colorBlindSafe: ThemeConfigV1 = ThemeConfigV1(
        schemaVersion = ThemeConfigV1.CURRENT_SCHEMA_VERSION,
        id = "color-blind-safe",
        name = "Color-Blind Safe",
        mode = ThemeMode.DEUTERANOPIA,
        useDynamicColor = false,
        accentHex = "#56B4E9",
        winHex = "#009E73",
        drawHex = "#E69F00",
        lossHex = "#D55E00",
        pendingHex = "#94A3B8",
        oddsUpHex = "#0072B2",
        oddsDownHex = "#D55E00",
        valueHighlightHex = "#CC79A7",
        sharpBadgeHex = "#F0E442",
        useGradientHero = true,
        useBackgroundGradient = false,
        density = DensityPreset.COMFORTABLE,
        shapeFamily = ShapeFamily.ROUNDED,
        surfaceStyle = SurfaceStyle.OUTLINED,
        cardCornerOverrideDp = null,
        fontFamily = FontFamilyPreset.SYSTEM,
        fontScale = 1.0f,
        fixtureLayout = FixtureLayoutMode.ANALYTICAL,
        batterySaverAutoAmoled = false,
        motionSpeed = MotionSpeed.NORMAL,
        haptics = HapticLevel.SUBTLE
    )

    val sunsetNeon: ThemeConfigV1 = ThemeConfigV1(
        schemaVersion = ThemeConfigV1.CURRENT_SCHEMA_VERSION,
        id = "sunset-neon",
        name = "Sunset Neon",
        mode = ThemeMode.DARK,
        useDynamicColor = false,
        accentHex = "#F97316",
        winHex = "#2DD4BF",
        drawHex = "#FACC15",
        lossHex = "#F43F5E",
        pendingHex = "#A1A1AA",
        oddsUpHex = "#34D399",
        oddsDownHex = "#FB7185",
        valueHighlightHex = "#E879F9",
        sharpBadgeHex = "#38BDF8",
        useGradientHero = true,
        useBackgroundGradient = true,
        density = DensityPreset.SPACIOUS,
        shapeFamily = ShapeFamily.SOFT,
        surfaceStyle = SurfaceStyle.TONAL,
        cardCornerOverrideDp = null,
        fontFamily = FontFamilyPreset.ROUNDED,
        fontScale = 1.0f,
        fixtureLayout = FixtureLayoutMode.ANALYTICAL,
        batterySaverAutoAmoled = false,
        motionSpeed = MotionSpeed.NORMAL,
        haptics = HapticLevel.SUBTLE
    )

    val default: ThemeConfigV1 = midnightBroadcast

    val all: List<ThemeConfigV1> = listOf(
        midnightBroadcast,
        amoledPureBlack,
        crispDaylight,
        emeraldSharp,
        colorBlindSafe,
        sunsetNeon
    )

    val ALL: List<ThemeConfigV1> = all

    val accentSwatches: List<String> = listOf(
        "#38BDF8", // Broadcast Sky
        "#10B981", // Sharp Emerald
        "#8B5CF6", // Electric Violet
        "#F59E0B", // Championship Amber
        "#F43F5E", // Crimson Rose
        "#06B6D4", // Stadium Cyan
        "#6366F1", // Cobalt Indigo
        "#84CC16", // Pitch Lime
        "#F97316", // Sunset Orange
        "#14B8A6", // Mint Teal
        "#D946EF", // Neon Fuchsia
        "#EAB308"  // Trophy Gold
    )

    val teamPalettes: List<TeamPalettePreset> = listOf(
        TeamPalettePreset(
            name = "Arsenal",
            accentHex = "#EF0107",
            winHex = "#22C55E",
            drawHex = "#F59E0B",
            lossHex = "#F87171"
        ),
        TeamPalettePreset(
            name = "Man City",
            accentHex = "#6CABDD",
            winHex = "#22C55E",
            drawHex = "#F59E0B",
            lossHex = "#EF4444"
        ),
        TeamPalettePreset(
            name = "Liverpool",
            accentHex = "#C8102E",
            winHex = "#00B2A9",
            drawHex = "#F6EB61",
            lossHex = "#FB7185"
        ),
        TeamPalettePreset(
            name = "Chelsea",
            accentHex = "#2563EB",
            winHex = "#22C55E",
            drawHex = "#DBA111",
            lossHex = "#EF4444"
        ),
        TeamPalettePreset(
            name = "Real Madrid",
            accentHex = "#FEBE10",
            winHex = "#22C55E",
            drawHex = "#60A5FA",
            lossHex = "#EF4444"
        ),
        TeamPalettePreset(
            name = "Lakers",
            accentHex = "#FDB927",
            winHex = "#A855F7",
            drawHex = "#F59E0B",
            lossHex = "#EF4444"
        ),
        TeamPalettePreset(
            name = "Celtics",
            accentHex = "#008348",
            winHex = "#22C55E",
            drawHex = "#BA9653",
            lossHex = "#EF4444"
        ),
        TeamPalettePreset(
            name = "Chiefs",
            accentHex = "#E31837",
            winHex = "#22C55E",
            drawHex = "#FFB81C",
            lossHex = "#F87171"
        ),
        TeamPalettePreset(
            name = "Eagles",
            accentHex = "#0D9488",
            winHex = "#22C55E",
            drawHex = "#A5ACAF",
            lossHex = "#EF4444"
        ),
        TeamPalettePreset(
            name = "Ferrari",
            accentHex = "#DC0000",
            winHex = "#22C55E",
            drawHex = "#FACC15",
            lossHex = "#F87171"
        )
    )
}

// ============================================================================
// 4. Lossless Serialization & Schema v1 -> v2 Migration Codec (TH-07)
// ============================================================================

object ThemeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    private val validThemeModes: Set<String> = ThemeMode.entries.map { it.name }.toSet()

    fun encode(config: ThemeConfigV1): String =
        json.encodeToString(ThemeConfigV1.serializer(), config.sanitized())

    fun decodeOrNull(raw: String?): ThemeConfigV1? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val element = json.parseToJsonElement(raw.trim())
            if (element !is JsonObject) return null

            val migrated = buildJsonObject {
                for ((key, value) in element) {
                    when (key) {
                        "mode" -> {
                            val rawMode = value.jsonPrimitive.contentOrNull?.trim()?.uppercase()
                            val normalizedMode = when (rawMode) {
                                "COLORBLIND", "COLOR_BLIND", "COLOR-BLIND" -> ThemeMode.DEUTERANOPIA.name
                                in validThemeModes -> rawMode!!
                                else -> ThemeMode.DARK.name
                            }
                            put(key, JsonPrimitive(normalizedMode))
                        }
                        "schemaVersion" -> {
                            put(key, JsonPrimitive(ThemeConfigV1.CURRENT_SCHEMA_VERSION))
                        }
                        else -> put(key, value)
                    }
                }
                if ("schemaVersion" !in element) {
                    put("schemaVersion", JsonPrimitive(ThemeConfigV1.CURRENT_SCHEMA_VERSION))
                }
            }

            json.decodeFromJsonElement(ThemeConfigV1.serializer(), migrated).sanitized()
        }.getOrNull()
    }
}

// ============================================================================
// 5. Pure-Kotlin Color Math (Zero String.format Bugs)
// ============================================================================

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun byteToHex(value: Int): String {
    val clamped = value.coerceIn(0, 255)
    val hi = HEX_DIGITS[(clamped ushr 4) and 0x0F]
    val lo = HEX_DIGITS[clamped and 0x0F]
    return "$hi$lo"
}

fun normalizeHexOrDefault(hex: String, fallback: String): String {
    val cleaned = hex.trim().removePrefix("#")
    val expanded = when (cleaned.length) {
        3 -> buildString(6) {
            for (c in cleaned) {
                append(c)
                append(c)
            }
        }
        6 -> cleaned
        8 -> cleaned.substring(2) // Drop alpha channel for 6-digit semantic config hexes
        else -> return fallback.uppercase()
    }
    val parsed = expanded.toLongOrNull(16) ?: return fallback.uppercase()
    val r = ((parsed shr 16) and 0xFF).toInt()
    val g = ((parsed shr 8) and 0xFF).toInt()
    val b = (parsed and 0xFF).toInt()
    return "#${byteToHex(r)}${byteToHex(g)}${byteToHex(b)}"
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFF38BDF8)): Color {
    val cleaned = hex.trim().removePrefix("#")
    val argbHex = when (cleaned.length) {
        3 -> buildString(8) {
            append("FF")
            for (c in cleaned) {
                append(c)
                append(c)
            }
        }
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return fallback
    }
    val value = argbHex.toLongOrNull(16) ?: return fallback
    return Color(value)
}

fun Color.toHexString(includeAlpha: Boolean = false): String {
    val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return if (includeAlpha) {
        "#${byteToHex(a)}${byteToHex(r)}${byteToHex(g)}${byteToHex(b)}"
    } else {
        "#${byteToHex(r)}${byteToHex(g)}${byteToHex(b)}"
    }
}

private fun linearizeChannel(channel: Float): Double {
    val c = channel.coerceIn(0f, 1f).toDouble()
    return if (c <= 0.04045) {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).pow(2.4)
    }
}

private fun relativeLuminance(color: Color): Double {
    val r = linearizeChannel(color.red)
    val g = linearizeChannel(color.green)
    val b = linearizeChannel(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun compositeOver(foreground: Color, background: Color): Color {
    val fgA = foreground.alpha.coerceIn(0f, 1f)
    if (fgA >= 0.999f) return foreground
    val invA = 1f - fgA
    return Color(
        red = foreground.red * fgA + background.red * invA,
        green = foreground.green * fgA + background.green * invA,
        blue = foreground.blue * fgA + background.blue * invA,
        alpha = 1f
    )
}

fun computeContrastRatio(foreground: Color, background: Color): Float {
    val effectiveFg = compositeOver(foreground, background)
    val l1 = relativeLuminance(effectiveFg)
    val l2 = relativeLuminance(background)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return ((lighter + 0.05) / (darker + 0.05)).toFloat()
}

fun rgbToHslPublic(color: Color): FloatArray {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)

    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val l = (maxC + minC) / 2f

    if (delta < 1e-6f) {
        return floatArrayOf(0f, 0f, l)
    }

    val s = if (l < 0.5f) {
        delta / (maxC + minC)
    } else {
        delta / (2f - maxC - minC)
    }.coerceIn(0f, 1f)

    var h = when (maxC) {
        r -> ((g - b) / delta) + (if (g < b) 6f else 0f)
        g -> ((b - r) / delta) + 2f
        else -> ((r - g) / delta) + 4f
    } * 60f

    if (h < 0f) h += 360f
    if (h >= 360f) h -= 360f

    return floatArrayOf(h, s, l)
}

fun hslToColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val light = l.coerceIn(0f, 1f)

    val c = (1f - abs(2f * light - 1f)) * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = light - c / 2f

    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

fun hslToHexPublic(h: Float, s: Float, l: Float): String =
    hslToColor(h, s, l).toHexString(includeAlpha = false)

fun autoFixContrastHex(
    hex: String,
    background: Color,
    targetRatio: Float = 4.5f
): String {
    val initial = parseHexColor(hex)
    if (computeContrastRatio(initial, background) >= targetRatio) {
        return initial.toHexString(includeAlpha = false)
    }

    val hsl = rgbToHslPublic(initial)
    val h = hsl[0]
    val s = hsl[1]
    val startL = hsl[2]
    val bgLum = relativeLuminance(background)

    fun searchTowards(targetEndL: Float): Color? {
        val extremeColor = hslToColor(h, s, targetEndL)
        if (computeContrastRatio(extremeColor, background) < targetRatio) {
            return null
        }
        var low = min(startL, targetEndL)
        var high = max(startL, targetEndL)
        val moveUp = targetEndL > startL
        var best = extremeColor

        repeat(24) {
            val mid = (low + high) / 2f
            val candidate = hslToColor(h, s, mid)
            if (computeContrastRatio(candidate, background) >= targetRatio) {
                best = candidate
                if (moveUp) high = mid else low = mid
            } else {
                if (moveUp) low = mid else high = mid
            }
        }
        return best
    }

    val primaryDirection = if (bgLum < 0.5) 1f else 0f
    val secondaryDirection = if (bgLum < 0.5) 0f else 1f

    val fixed = searchTowards(primaryDirection)
        ?: searchTowards(secondaryDirection)
        ?: run {
            val whiteRatio = computeContrastRatio(Color.White, background)
            val blackRatio = computeContrastRatio(Color.Black, background)
            if (whiteRatio >= blackRatio) Color.White else Color.Black
        }

    return fixed.toHexString(includeAlpha = false)
}

private fun blendColors(base: Color, overlay: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    val inv = 1f - t
    return Color(
        red = base.red * inv + overlay.red * t,
        green = base.green * inv + overlay.green * t,
        blue = base.blue * inv + overlay.blue * t,
        alpha = base.alpha * inv + overlay.alpha * t
    )
}

private fun idealOnColor(background: Color): Color {
    val whiteContrast = computeContrastRatio(Color.White, background)
    val darkText = Color(0xFF0B1120)
    val darkContrast = computeContrastRatio(darkText, background)
    return if (whiteContrast >= darkContrast) Color.White else darkText
}

// ============================================================================
// 6. Scaled Typography Builder (TY-01, TY-04, TY-05)
// ============================================================================

fun buildScaledTypography(
    scale: Float,
    fontFamily: FontFamilyPreset
): Typography {
    val safeScale = if (scale.isNaN() || scale.isInfinite()) {
        1.0f
    } else {
        scale.coerceIn(ThemeConfigV1.MIN_FONT_SCALE, ThemeConfigV1.MAX_FONT_SCALE)
    }

    val composeFamily = when (fontFamily) {
        FontFamilyPreset.SYSTEM -> FontFamily.SansSerif
        FontFamilyPreset.SERIF -> FontFamily.Serif
        FontFamilyPreset.MONO -> FontFamily.Monospace
        FontFamilyPreset.ROUNDED -> FontFamily.SansSerif
    }

    val isMono = fontFamily == FontFamilyPreset.MONO
    val isRounded = fontFamily == FontFamilyPreset.ROUNDED
    val trackingBoost = when {
        isMono -> (-0.2f)
        isRounded -> 0.15f
        else -> 0f
    }

    fun style(
        sizeSp: Float,
        lineHeightSp: Float,
        weight: FontWeight,
        letterSpacingSp: Float = 0f,
        tabularNumbers: Boolean = false
    ): TextStyle = TextStyle(
        fontFamily = composeFamily,
        fontWeight = weight,
        fontSize = (sizeSp * safeScale).sp,
        lineHeight = (lineHeightSp * safeScale).sp,
        letterSpacing = (letterSpacingSp + trackingBoost).sp,
        fontFeatureSettings = if (tabularNumbers) "tnum" else null
    )

    return Typography(
        displayLarge = style(34f, 40f, FontWeight.Bold, -0.5f, tabularNumbers = true),
        displayMedium = style(28f, 34f, FontWeight.Bold, -0.25f, tabularNumbers = true),
        displaySmall = style(24f, 30f, FontWeight.SemiBold, 0f, tabularNumbers = true),
        headlineLarge = style(22f, 28f, FontWeight.Bold, -0.2f, tabularNumbers = true),
        headlineMedium = style(20f, 26f, FontWeight.SemiBold, -0.1f, tabularNumbers = true),
        headlineSmall = style(18f, 24f, FontWeight.SemiBold, 0f, tabularNumbers = true),
        titleLarge = style(17f, 23f, FontWeight.SemiBold, 0f, tabularNumbers = true),
        titleMedium = style(15f, 21f, FontWeight.SemiBold, 0.1f, tabularNumbers = true),
        titleSmall = style(13.5f, 19f, FontWeight.Medium, 0.1f, tabularNumbers = true),
        bodyLarge = style(15f, 22f, FontWeight.Normal, 0.15f, tabularNumbers = false),
        bodyMedium = style(13.5f, 20f, FontWeight.Normal, 0.15f, tabularNumbers = true),
        bodySmall = style(12f, 17f, FontWeight.Normal, 0.2f, tabularNumbers = true),
        labelLarge = style(13f, 18f, FontWeight.SemiBold, 0.15f, tabularNumbers = true),
        labelMedium = style(11.5f, 15f, FontWeight.SemiBold, 0.3f, tabularNumbers = true),
        labelSmall = style(10.5f, 14f, FontWeight.Medium, 0.35f, tabularNumbers = true)
    )
}

// ============================================================================
// 7. Resolved Token Models & Resolver
// ============================================================================

@Immutable
data class SemanticColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceTonal: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    // 9 editable semantic betting colors
    val accent: Color,
    val onAccent: Color,
    val win: Color,
    val draw: Color,
    val loss: Color,
    val pending: Color,
    val oddsUp: Color,
    val oddsDown: Color,
    val valueHighlight: Color,
    val sharpBadge: Color,
    // Dim / container tints
    val accentDim: Color,
    val winDim: Color,
    val drawDim: Color,
    val lossDim: Color,
    val pendingDim: Color,
    val oddsUpDim: Color,
    val oddsDownDim: Color,
    val valueHighlightDim: Color,
    val sharpBadgeDim: Color,
    // Gradients (CL-06)
    val bgGradientTop: Color,
    val bgGradientBottom: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    // Chart series (color-blind aware)
    val chartSeries: List<Color>
) {
    // Convenience aliases for concise Compose callsites
    val bg: Color get() = background
    val text: Color get() = textPrimary
    val surfaceRaised: Color get() = surfaceElevated
    val onWin: Color get() = idealOnColor(win)
    val onDraw: Color get() = idealOnColor(draw)
    val onLoss: Color get() = idealOnColor(loss)
}

@Immutable
data class ShapeTokens(
    val cornerSm: Dp,
    val cornerMd: Dp,
    val cornerLg: Dp,
    val cardCorner: Dp,
    val pillCorner: Dp,
    val small: CornerBasedShape,
    val medium: CornerBasedShape,
    val large: CornerBasedShape,
    val card: CornerBasedShape,
    val pill: CornerBasedShape,
    val materialShapes: Shapes
) {
    val extraSmall: CornerBasedShape get() = small
    val button: CornerBasedShape get() = medium
    val chip: CornerBasedShape get() = pill
    val badge: CornerBasedShape get() = small
}

@Immutable
data class SpacingTokens(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val cardPadding: Dp,
    val sectionGap: Dp,
    val itemGap: Dp,
    val borderWidth: Dp,
    val cardElevation: Dp
) {
    val screenPadding: Dp get() = md
    val chipHorizontalPadding: Dp get() = md
    val chipVerticalPadding: Dp get() = xs + 2.dp
}

@Immutable
data class MotionTokens(
    val speed: MotionSpeed,
    val multiplier: Float,
    val enabled: Boolean,
    val fastMs: Int,
    val normalMs: Int,
    val slowMs: Int,
    val springDampingRatio: Float,
    val springStiffness: Float,
    val haptics: HapticLevel
) {
    fun scaleDuration(baseMs: Int): Int {
        if (baseMs <= 0) return 0
        return when (speed) {
            MotionSpeed.OFF -> 0
            MotionSpeed.REDUCED -> (baseMs * 0.5f).roundToInt().coerceAtLeast(16)
            MotionSpeed.NORMAL -> baseMs
            MotionSpeed.SNAPPY -> (baseMs / 1.5f).roundToInt().coerceAtLeast(16)
        }
    }
}

@Immutable
data class ResolvedThemeTokens(
    val config: ThemeConfigV1,
    val effectiveMode: ThemeMode,
    val isDark: Boolean,
    val colors: SemanticColors,
    val colorScheme: ColorScheme,
    val shapes: ShapeTokens,
    val spacing: SpacingTokens,
    val motion: MotionTokens,
    val typography: Typography,
    val fixtureLayout: FixtureLayoutMode,
    val useGradientHero: Boolean,
    val useBackgroundGradient: Boolean,
    val surfaceStyle: SurfaceStyle,
    val textSurfaceContrast: Float,
    val accentSurfaceContrast: Float
) {
    // Convenience aliases
    val padMd: Dp get() = spacing.md
    val padSm: Dp get() = spacing.sm
    val cornerSm: Dp get() = shapes.cornerSm
    val cornerMd: Dp get() = shapes.cornerMd
    val cornerLg: Dp get() = shapes.cornerLg
    val fontScale: Float get() = config.fontScale
    val materialShapes: Shapes get() = shapes.materialShapes
    val elevationDp: Dp get() = spacing.cardElevation
    val borderWidthDp: Dp get() = spacing.borderWidth
}

fun resolveTokens(
    config: ThemeConfigV1,
    dynamicAccentOverride: Color? = null,
    isBatterySaverActive: Boolean = false
): ResolvedThemeTokens {
    val safe = config.sanitized()

    // Battery saver auto-AMOLED override (ED-08)
    val effectiveMode = if (isBatterySaverActive && safe.batterySaverAutoAmoled && safe.mode != ThemeMode.LIGHT) {
        ThemeMode.AMOLED
    } else {
        safe.mode
    }
    val isDark = !effectiveMode.isLight

    // Parse all 9 semantic betting colors
    val accent = if (safe.useDynamicColor && dynamicAccentOverride != null) {
        dynamicAccentOverride
    } else {
        parseHexColor(safe.accentHex, Color(0xFF38BDF8))
    }
    val win = parseHexColor(safe.winHex, Color(0xFF22C55E))
    val draw = parseHexColor(safe.drawHex, Color(0xFFF59E0B))
    val loss = parseHexColor(safe.lossHex, Color(0xFFEF4444))
    val pending = parseHexColor(safe.pendingHex, Color(0xFF94A3B8))
    val oddsUp = parseHexColor(safe.oddsUpHex, Color(0xFF10B981))
    val oddsDown = parseHexColor(safe.oddsDownHex, Color(0xFFF43F5E))
    val valueHighlight = parseHexColor(safe.valueHighlightHex, Color(0xFFA855F7))
    val sharpBadge = parseHexColor(safe.sharpBadgeHex, Color(0xFF06B6D4))

    // Neutral surface hierarchy per effective mode
    val background: Color
    val surface: Color
    val surfaceElevated: Color
    val border: Color
    val borderStrong: Color
    val textPrimary: Color
    val textSecondary: Color
    val textMuted: Color

    when (effectiveMode) {
        ThemeMode.LIGHT -> {
            background = Color(0xFFF4F6FA)
            surface = Color(0xFFFFFFFF)
            surfaceElevated = Color(0xFFF8FAFC)
            border = Color(0xFFD8E0EC)
            borderStrong = Color(0xFFB6C2D4)
            textPrimary = Color(0xFF0F172A)
            textSecondary = Color(0xFF475569)
            textMuted = Color(0xFF64748B)
        }
        ThemeMode.AMOLED -> {
            background = Color(0xFF000000)
            surface = Color(0xFF090B10)
            surfaceElevated = Color(0xFF121620)
            border = Color(0xFF1F2633)
            borderStrong = Color(0xFF334155)
            textPrimary = Color(0xFFF8FAFC)
            textSecondary = Color(0xFFCBD5E1)
            textMuted = Color(0xFF94A3B8)
        }
        ThemeMode.DARK,
        ThemeMode.DEUTERANOPIA,
        ThemeMode.PROTANOPIA,
        ThemeMode.TRITANOPIA -> {
            background = Color(0xFF0B1120)
            surface = Color(0xFF111827)
            surfaceElevated = Color(0xFF1E293B)
            border = Color(0xFF243044)
            borderStrong = Color(0xFF3B4C66)
            textPrimary = Color(0xFFF1F5F9)
            textSecondary = Color(0xFFCBD5E1)
            textMuted = Color(0xFF94A3B8)
        }
    }

    val tonalRatio = if (isDark) 0.12f else 0.08f
    val surfaceTonal = blendColors(surface, accent, tonalRatio)

    val dimAlpha = if (isDark) 0.18f else 0.13f
    val bgGradientTop = if (safe.useBackgroundGradient && effectiveMode != ThemeMode.AMOLED) {
        blendColors(background, accent, if (isDark) 0.14f else 0.09f)
    } else {
        background
    }
    val bgGradientBottom = if (safe.useBackgroundGradient && effectiveMode != ThemeMode.AMOLED) {
        blendColors(background, valueHighlight, if (isDark) 0.08f else 0.05f)
    } else {
        background
    }

    val heroGradientStart = if (safe.useGradientHero) {
        blendColors(surfaceElevated, accent, if (isDark) 0.24f else 0.14f)
    } else {
        surface
    }
    val heroGradientEnd = if (safe.useGradientHero) {
        blendColors(surface, sharpBadge, if (isDark) 0.12f else 0.06f)
    } else {
        surface
    }

    // Color-blind-safe chart series when user is in a CVD mode (CL-03, CL-08)
    val chartSeries: List<Color> = when (safe.mode) {
        ThemeMode.DEUTERANOPIA -> listOf(
            Color(0xFF56B4E9),
            Color(0xFFE69F00),
            Color(0xFF009E73),
            Color(0xFFD55E00),
            Color(0xFFCC79A7),
            Color(0xFF0072B2)
        )
        ThemeMode.PROTANOPIA -> listOf(
            Color(0xFF38BDF8),
            Color(0xFFFACC15),
            Color(0xFF2DD4BF),
            Color(0xFFFB923C),
            Color(0xFFC084FC),
            Color(0xFF2563EB)
        )
        ThemeMode.TRITANOPIA -> listOf(
            Color(0xFFEF4444),
            Color(0xFF06B6D4),
            Color(0xFFF472B6),
            Color(0xFF14B8A6),
            Color(0xFFFB7185),
            Color(0xFFA3E635)
        )
        ThemeMode.DARK,
        ThemeMode.AMOLED,
        ThemeMode.LIGHT -> listOf(
            accent,
            win,
            draw,
            loss,
            valueHighlight,
            sharpBadge
        )
    }

    val onAccent = idealOnColor(accent)

    val semanticColors = SemanticColors(
        background = background,
        surface = surface,
        surfaceElevated = surfaceElevated,
        surfaceTonal = surfaceTonal,
        border = border,
        borderStrong = borderStrong,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        accent = accent,
        onAccent = onAccent,
        win = win,
        draw = draw,
        loss = loss,
        pending = pending,
        oddsUp = oddsUp,
        oddsDown = oddsDown,
        valueHighlight = valueHighlight,
        sharpBadge = sharpBadge,
        accentDim = accent.copy(alpha = dimAlpha),
        winDim = win.copy(alpha = dimAlpha),
        drawDim = draw.copy(alpha = dimAlpha),
        lossDim = loss.copy(alpha = dimAlpha),
        pendingDim = pending.copy(alpha = dimAlpha),
        oddsUpDim = oddsUp.copy(alpha = dimAlpha),
        oddsDownDim = oddsDown.copy(alpha = dimAlpha),
        valueHighlightDim = valueHighlight.copy(alpha = dimAlpha),
        sharpBadgeDim = sharpBadge.copy(alpha = dimAlpha),
        bgGradientTop = bgGradientTop,
        bgGradientBottom = bgGradientBottom,
        heroGradientStart = heroGradientStart,
        heroGradientEnd = heroGradientEnd,
        chartSeries = chartSeries
    )

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = blendColors(surface, accent, 0.24f),
            onPrimaryContainer = textPrimary,
            secondary = sharpBadge,
            onSecondary = idealOnColor(sharpBadge),
            secondaryContainer = blendColors(surface, sharpBadge, 0.22f),
            onSecondaryContainer = textPrimary,
            tertiary = valueHighlight,
            onTertiary = idealOnColor(valueHighlight),
            tertiaryContainer = blendColors(surface, valueHighlight, 0.22f),
            onTertiaryContainer = textPrimary,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
            outline = border,
            outlineVariant = borderStrong,
            error = loss,
            onError = idealOnColor(loss),
            errorContainer = blendColors(surface, loss, 0.24f),
            onErrorContainer = textPrimary
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = blendColors(surface, accent, 0.16f),
            onPrimaryContainer = textPrimary,
            secondary = sharpBadge,
            onSecondary = idealOnColor(sharpBadge),
            secondaryContainer = blendColors(surface, sharpBadge, 0.16f),
            onSecondaryContainer = textPrimary,
            tertiary = valueHighlight,
            onTertiary = idealOnColor(valueHighlight),
            tertiaryContainer = blendColors(surface, valueHighlight, 0.16f),
            onTertiaryContainer = textPrimary,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
            outline = border,
            outlineVariant = borderStrong,
            error = loss,
            onError = idealOnColor(loss),
            errorContainer = blendColors(surface, loss, 0.15f),
            onErrorContainer = textPrimary
        )
    }

    // Shape tokens + SH-01 component-level card corner override
    val (baseSm, baseMd, baseLg) = when (safe.shapeFamily) {
        ShapeFamily.SHARP -> Triple(2.dp, 4.dp, 6.dp)
        ShapeFamily.ROUNDED -> Triple(8.dp, 14.dp, 20.dp)
        ShapeFamily.CUT -> Triple(6.dp, 12.dp, 18.dp)
        ShapeFamily.SOFT -> Triple(12.dp, 20.dp, 28.dp)
    }
    val cardCorner = safe.cardCornerOverrideDp?.dp ?: baseMd
    val pillCorner = if (safe.shapeFamily == ShapeFamily.SHARP) 4.dp else 999.dp

    fun shapeFor(radius: Dp): CornerBasedShape = when (safe.shapeFamily) {
        ShapeFamily.CUT -> CutCornerShape(radius)
        ShapeFamily.SHARP,
        ShapeFamily.ROUNDED,
        ShapeFamily.SOFT -> RoundedCornerShape(radius)
    }

    val smallShape = shapeFor(baseSm)
    val mediumShape = shapeFor(baseMd)
    val largeShape = shapeFor(baseLg)
    val cardShape = shapeFor(cardCorner)
    val pillShape = if (safe.shapeFamily == ShapeFamily.CUT) {
        CutCornerShape(baseSm)
    } else {
        RoundedCornerShape(pillCorner)
    }

    val shapeTokens = ShapeTokens(
        cornerSm = baseSm,
        cornerMd = baseMd,
        cornerLg = baseLg,
        cardCorner = cardCorner,
        pillCorner = pillCorner,
        small = smallShape,
        medium = mediumShape,
        large = largeShape,
        card = cardShape,
        pill = pillShape,
        materialShapes = Shapes(
            extraSmall = smallShape,
            small = smallShape,
            medium = cardShape,
            large = largeShape,
            extraLarge = largeShape
        )
    )

    // Spacing & surface style tokens (SH-03, SH-04)
    val (borderWidth, cardElevation) = when (safe.surfaceStyle) {
        SurfaceStyle.OUTLINED -> 1.dp to 0.dp
        SurfaceStyle.TONAL -> 0.dp to 0.dp
        SurfaceStyle.ELEVATED -> 0.dp to if (isDark) 3.dp else 4.dp
        SurfaceStyle.FLAT -> 0.dp to 0.dp
    }

    val spacingTokens = when (safe.density) {
        DensityPreset.COMPACT -> SpacingTokens(
            xxs = 2.dp,
            xs = 4.dp,
            sm = 6.dp,
            md = 10.dp,
            lg = 14.dp,
            xl = 20.dp,
            cardPadding = 10.dp,
            sectionGap = 12.dp,
            itemGap = 6.dp,
            borderWidth = borderWidth,
            cardElevation = cardElevation
        )
        DensityPreset.COMFORTABLE -> SpacingTokens(
            xxs = 4.dp,
            xs = 6.dp,
            sm = 10.dp,
            md = 14.dp,
            lg = 20.dp,
            xl = 28.dp,
            cardPadding = 14.dp,
            sectionGap = 16.dp,
            itemGap = 10.dp,
            borderWidth = borderWidth,
            cardElevation = cardElevation
        )
        DensityPreset.SPACIOUS -> SpacingTokens(
            xxs = 6.dp,
            xs = 8.dp,
            sm = 12.dp,
            md = 18.dp,
            lg = 24.dp,
            xl = 34.dp,
            cardPadding = 18.dp,
            sectionGap = 22.dp,
            itemGap = 14.dp,
            borderWidth = borderWidth,
            cardElevation = cardElevation
        )
    }

    // Motion & haptics tokens (MO-01, MO-04)
    val motionTokens = when (safe.motionSpeed) {
        MotionSpeed.OFF -> MotionTokens(
            speed = MotionSpeed.OFF,
            multiplier = MotionSpeed.OFF.multiplier,
            enabled = false,
            fastMs = 0,
            normalMs = 0,
            slowMs = 0,
            springDampingRatio = 1.0f,
            springStiffness = 10_000f,
            haptics = safe.haptics
        )
        MotionSpeed.REDUCED -> MotionTokens(
            speed = MotionSpeed.REDUCED,
            multiplier = MotionSpeed.REDUCED.multiplier,
            enabled = true,
            fastMs = 80,
            normalMs = 140,
            slowMs = 220,
            springDampingRatio = 0.95f,
            springStiffness = 500f,
            haptics = safe.haptics
        )
        MotionSpeed.NORMAL -> MotionTokens(
            speed = MotionSpeed.NORMAL,
            multiplier = MotionSpeed.NORMAL.multiplier,
            enabled = true,
            fastMs = 140,
            normalMs = 260,
            slowMs = 420,
            springDampingRatio = 0.82f,
            springStiffness = 400f,
            haptics = safe.haptics
        )
        MotionSpeed.SNAPPY -> MotionTokens(
            speed = MotionSpeed.SNAPPY,
            multiplier = MotionSpeed.SNAPPY.multiplier,
            enabled = true,
            fastMs = 95,
            normalMs = 175,
            slowMs = 280,
            springDampingRatio = 0.78f,
            springStiffness = 650f,
            haptics = safe.haptics
        )
    }

    val typography = buildScaledTypography(
        scale = safe.fontScale,
        fontFamily = safe.fontFamily
    )

    val textSurfaceContrast = computeContrastRatio(semanticColors.textPrimary, semanticColors.surface)
    val accentSurfaceContrast = computeContrastRatio(semanticColors.accent, semanticColors.surface)

    return ResolvedThemeTokens(
        config = safe,
        effectiveMode = effectiveMode,
        isDark = isDark,
        colors = semanticColors,
        colorScheme = colorScheme,
        shapes = shapeTokens,
        spacing = spacingTokens,
        motion = motionTokens,
        typography = typography,
        fixtureLayout = safe.fixtureLayout,
        useGradientHero = safe.useGradientHero,
        useBackgroundGradient = safe.useBackgroundGradient && effectiveMode != ThemeMode.AMOLED,
        surfaceStyle = safe.surfaceStyle,
        textSurfaceContrast = textSurfaceContrast,
        accentSurfaceContrast = accentSurfaceContrast
    )
}


