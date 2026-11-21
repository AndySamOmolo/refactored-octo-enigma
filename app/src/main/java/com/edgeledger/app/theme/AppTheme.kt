package com.edgeledger.app.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    val view = LocalView.current

    val isPowerSaveMode = remember(context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        }.getOrDefault(false)
    }

    val isLight = config.mode == ThemeMode.LIGHT

    val dynamicScheme = if (config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else {
        null
    }

    val tokens = remember(config, dynamicScheme, isPowerSaveMode) {
        resolveTokens(
            config = config,
            dynamicAccentOverride = dynamicScheme?.primary,
            isBatterySaverActive = isPowerSaveMode,
        )
    }

    val colorScheme = dynamicScheme ?: tokens.colorScheme

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !tokens.isDark
                controller.isAppearanceLightNavigationBars = !tokens.isDark
            }
        }
    }

    CompositionLocalProvider(LocalAppTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = tokens.typography,
            shapes = tokens.materialShapes,
            content = content,
        )
    }
}
