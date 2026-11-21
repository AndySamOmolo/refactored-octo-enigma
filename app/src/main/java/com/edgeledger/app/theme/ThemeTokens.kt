package com.edgeledger.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Serializable
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    AMOLED("AMOLED Black"),
    LIGHT("Light"),
    COLORBLIND("Color-Blind Safe"),
}

@Serializable
enum class DensityPreset(val label: String) {
    COMPACT("Compact"),
    COMFORTABLE("Normal"),
    SPACIOUS("Spacious"),
}

@Serializable
enum class ShapeFamily(val label: String) {
    SHARP("Sharp"),
    ROUNDED("Rounded"),
    SOFT("Soft"),
}

@Serializable
data class ThemeConfigV1(
    val schemaVersion: Int = 1,
    val id: String = "midnight-broadcast",
    val name: String = "Midnight Broadcast",
    val mode: ThemeMode = ThemeMode.DARK,
    val useDynamicColor: Boolean = false,
    val accentHex: String = "#3B82F6",
    val density: DensityPreset = DensityPreset.COMFORTABLE,
    val shapeFamily: ShapeFamily = ShapeFamily.ROUNDED,
    val fontScale: Float = 1.0f,
)

@Immutable
data class SemanticColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentDim: Color,
    val win: Color,
    val winDim: Color,
    val draw: Color,
    val drawDim: Color,
    val loss: Color,
    val lossDim: Color,
)

@Immutable
data class ResolvedThemeTokens(
    val config: ThemeConfigV1,
    val colors: SemanticColors,
    val cornerSm: Dp,
    val cornerMd: Dp,
    val cornerLg: Dp,
    val padSm: Dp,
    val padMd: Dp,
    val padLg: Dp,
    val fontScale: Float,
    val textSurfaceContrast: Double,
)

object ThemePresets {
    val ALL: List<ThemeConfigV1> = listOf(
        ThemeConfigV1(
            id = "midnight-broadcast",
            name = "Midnight Broadcast",
            mode = ThemeMode.DARK,
            accentHex = "#3B82F6",
        ),
        ThemeConfigV1(
            id = "amoled-pure",
            name = "AMOLED Pure Black",
            mode = ThemeMode.AMOLED,
            accentHex = "#10B981",
            density = DensityPreset.COMPACT,
        ),
        ThemeConfigV1(
            id = "crisp-daylight",
            name = "Crisp Daylight",
            mode = ThemeMode.LIGHT,
            accentHex = "#2563EB",
        ),
        ThemeConfigV1(
            id = "colorblind-safe",
            name = "Color-Blind Safe",
            mode = ThemeMode.COLORBLIND,
            accentHex = "#0072B2",
        ),
    )

    val ACCENT_SWATCHES: List<Pair<String, String>> = listOf(
        "Cobalt" to "#3B82F6",
        "Emerald" to "#10B981",
        "Violet" to "#8B5CF6",
        "Amber" to "#F59E0B",
        "Cyan" to "#06B6D4",
        "Rose" to "#F43F5E",
        "Teal" to "#14B8A6",
        "Orange" to "#F97316",
    )
}

object ThemeCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: ThemeConfigV1): String = json.encodeToString(config)

    fun decodeOrNull(raw: String): ThemeConfigV1? = runCatching {
        val parsed = json.decodeFromString<ThemeConfigV1>(raw)
        require(parsed.schemaVersion == 1)
        require(parsed.accentHex.matches(Regex("^#[0-9A-Fa-f]{6}$")))
        parsed.copy(fontScale = parsed.fontScale.coerceIn(0.85f, 1.35f))
    }.getOrNull()
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFF3B82F6)): Color = runCatching {
    val clean = hex.removePrefix("#")
    val rgb = clean.toLong(16)
    Color(0xFF000000L or rgb)
}.getOrDefault(fallback)

private fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/**
 * WCAG 2.1 contrast ratio between two Compose Colors (1.0 .. 21.0).
 */
fun computeContrastRatio(fg: Color, bg: Color): Double {
    val l1 = relativeLuminance(fg)
    val l2 = relativeLuminance(bg)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return ((lighter + 0.05) / (darker + 0.05) * 100.0).toInt() / 100.0
}

/**
 * Auto-fixes a foreground color against a background if contrast is below 4.5:1 (AX-02).
 */
fun autoFixContrastColor(fg: Color, bg: Color): Color {
    if (computeContrastRatio(fg, bg) >= 4.5) return fg
    val bgLum = relativeLuminance(bg)
    return if (bgLum > 0.45) Color(0xFF0F172A) else Color(0xFFF8FAFC)
}

fun resolveTokens(
    config: ThemeConfigV1,
    dynamicAccentOverride: Color? = null,
): ResolvedThemeTokens {
    val isLight = config.mode == ThemeMode.LIGHT
    val isAmoled = config.mode == ThemeMode.AMOLED
    val isCb = config.mode == ThemeMode.COLORBLIND

    val bg = when {
        isLight -> Color(0xFFF8FAFC)
        isAmoled -> Color(0xFF000000)
        else -> Color(0xFF0A0E17)
    }
    val surface = when {
        isLight -> Color(0xFFFFFFFF)
        isAmoled -> Color(0xFF0B0F15)
        else -> Color(0xFF131A29)
    }
    val surfaceRaised = when {
        isLight -> Color(0xFFF1F5F9)
        isAmoled -> Color(0xFF141B26)
        else -> Color(0xFF1C2538)
    }
    val border = when {
        isLight -> Color(0xFFCBD5E1)
        isAmoled -> Color(0xFF1F2937)
        else -> Color(0xFF26334D)
    }
    val rawText = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val text = autoFixContrastColor(rawText, surface)
    val textSecondary = if (isLight) Color(0xFF334155) else Color(0xFFCBD5E1)
    val textMuted = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8)

    val accent = dynamicAccentOverride ?: parseHexColor(config.accentHex)
    val win = if (isCb) Color(0xFF009E73) else Color(0xFF10B981)
    val draw = if (isCb) Color(0xFFE69F00) else Color(0xFFF59E0B)
    val loss = if (isCb) Color(0xFFD55E00) else Color(0xFFEF4444)

    val dimAlpha = if (isLight) 0.13f else 0.17f

    val (cSm, cMd, cLg) = when (config.shapeFamily) {
        ShapeFamily.SHARP -> Triple(2.dp, 4.dp, 6.dp)
        ShapeFamily.SOFT -> Triple(12.dp, 18.dp, 24.dp)
        ShapeFamily.ROUNDED -> Triple(8.dp, 12.dp, 16.dp)
    }

    val (pSm, pMd, pLg) = when (config.density) {
        DensityPreset.COMPACT -> Triple(6.dp, 10.dp, 14.dp)
        DensityPreset.SPACIOUS -> Triple(10.dp, 18.dp, 24.dp)
        DensityPreset.COMFORTABLE -> Triple(8.dp, 14.dp, 20.dp)
    }

    return ResolvedThemeTokens(
        config = config,
        colors = SemanticColors(
            bg = bg,
            surface = surface,
            surfaceRaised = surfaceRaised,
            border = border,
            text = text,
            textSecondary = textSecondary,
            textMuted = textMuted,
            accent = accent,
            accentDim = accent.copy(alpha = dimAlpha),
            win = win,
            winDim = win.copy(alpha = dimAlpha),
            draw = draw,
            drawDim = draw.copy(alpha = dimAlpha),
            loss = loss,
            lossDim = loss.copy(alpha = dimAlpha),
        ),
        cornerSm = cSm,
        cornerMd = cMd,
        cornerLg = cLg,
        padSm = pSm,
        padMd = pMd,
        padLg = pLg,
        fontScale = config.fontScale.coerceIn(0.85f, 1.35f),
        textSurfaceContrast = computeContrastRatio(text, surface),
    )
}
