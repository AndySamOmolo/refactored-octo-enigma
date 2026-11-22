package com.edgeledger.app.theme

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
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

    var isPowerSaveMode by remember(context) {
        mutableStateOf(
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isPowerSaveMode == true
            }.getOrDefault(false)
        )
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                isPowerSaveMode = runCatching {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.isPowerSaveMode == true
                }.getOrDefault(false)
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val isLight = config.mode == ThemeMode.LIGHT

    val dynamicPrimary = remember(context, config.useDynamicColor, isLight) {
        if (config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (if (isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)).primary
        } else {
            null
        }
    }

    val tokens = remember(config, dynamicPrimary, isPowerSaveMode) {
        resolveTokens(
            config = config,
            dynamicAccentOverride = dynamicPrimary,
            isBatterySaverActive = isPowerSaveMode,
        )
    }

    // Keep MaterialTheme and AppTheme.tokens unified so AMOLED/Light/CVD surfaces never split-brain
    val colorScheme = tokens.colorScheme

    if (!view.isInEditMode) {
        SideEffect {
            var ctx: Context? = view.context
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            val window = (ctx as? Activity)?.window
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
