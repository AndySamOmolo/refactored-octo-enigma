package com.edgeledger.app.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalAppTokens = staticCompositionLocalOf { resolveTokens(ThemeConfigV1()) }

object AppTheme {
    val tokens: ResolvedThemeTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTokens.current
}

@Composable
fun EdgeLedgerTheme(
    config: ThemeConfigV1,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isLight = config.mode == ThemeMode.LIGHT

    val dynamicScheme = if (config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else {
        null
    }

    val tokens = resolveTokens(
        config = config,
        dynamicAccentOverride = dynamicScheme?.primary,
    )

    val colorScheme = dynamicScheme ?: if (isLight) {
        lightColorScheme(
            primary = tokens.colors.accent,
            background = tokens.colors.bg,
            surface = tokens.colors.surface,
            onBackground = tokens.colors.text,
            onSurface = tokens.colors.text,
            outline = tokens.colors.border,
        )
    } else {
        darkColorScheme(
            primary = tokens.colors.accent,
            background = tokens.colors.bg,
            surface = tokens.colors.surface,
            onBackground = tokens.colors.text,
            onSurface = tokens.colors.text,
            outline = tokens.colors.border,
        )
    }

    CompositionLocalProvider(LocalAppTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
