package com.edgeledger.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.theme.EdgeLedgerTheme
import com.edgeledger.app.ui.screens.AccuracyScreen
import com.edgeledger.app.ui.screens.GamesScreen
import com.edgeledger.app.ui.screens.MatchDetailScreen
import com.edgeledger.app.ui.screens.PicksScreen
import com.edgeledger.app.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    private val _pendingMatchId = MutableStateFlow<String?>(null)
    val pendingMatchId: StateFlow<String?> = _pendingMatchId.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val splashStartMs = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) extractDeepLink(intent)

        val app = application as EdgeLedgerApp
        // Bounded 3-second splash timeout fallback prevents indefinite hang on slow DataStore hydration
        splash.setKeepOnScreenCondition {
            !app.prefsRepo.loaded.value && (SystemClock.elapsedRealtime() - splashStartMs) < 3_000L
        }

        setContent {
            val themeConfig by app.prefsRepo.theme.collectAsStateWithLifecycle()
            val deepLinkMatchId by pendingMatchId.collectAsStateWithLifecycle()
            EdgeLedgerTheme(config = themeConfig) {
                EdgeLedgerRoot(
                    app = app,
                    deepLinkMatchId = deepLinkMatchId,
                    onConsumeDeepLink = { _pendingMatchId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLink(intent)
    }

    private fun extractDeepLink(intent: Intent?) {
        val matchId = intent?.getStringExtra(EXTRA_MATCH_ID)?.trim()
        if (!matchId.isNullOrBlank()) {
            _pendingMatchId.value = matchId
            intent.removeExtra(EXTRA_MATCH_ID)
        }
    }

    companion object {
        const val EXTRA_MATCH_ID = "com.edgeledger.app.EXTRA_MATCH_ID"
    }
}

@Composable
private fun EdgeLedgerRoot(
    app: EdgeLedgerApp,
    deepLinkMatchId: String?,
    onConsumeDeepLink: () -> Unit,
) {
    val navController = rememberNavController()
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "games"

    LaunchedEffect(deepLinkMatchId) {
        if (!deepLinkMatchId.isNullOrBlank()) {
            val encoded = Uri.encode(deepLinkMatchId)
            navController.navigate("match/$encoded") {
                popUpTo("games") { inclusive = false }
                launchSingleTop = true
            }
            onConsumeDeepLink()
        }
    }

    val tabs = remember {
        listOf(
            BottomTab("games", "Games", Icons.Outlined.EmojiEvents),
            BottomTab("accuracy", "Accuracy", Icons.Outlined.QueryStats),
            BottomTab("picks", "Picks", Icons.Outlined.BookmarkBorder),
            BottomTab("settings", "Settings", Icons.Outlined.Settings),
        )
    }

    val showBottomBar = tabs.any { it.route == currentRoute }

    val rootBgModifier = if (tokens.useBackgroundGradient) {
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.bgGradientTop, c.bgGradientBottom)))
    } else {
        Modifier
            .fillMaxSize()
            .background(c.bg)
    }

    Box(modifier = rootBgModifier) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    Column {
                        HorizontalDivider(thickness = 0.75.dp, color = c.border)
                        NavigationBar(
                            containerColor = c.surface,
                            contentColor = c.text,
                        ) {
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            navController.navigate(tab.route) {
                                                popUpTo("games") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.accent,
                                        selectedTextColor = c.accent,
                                        unselectedIconColor = c.textMuted,
                                        unselectedTextColor = c.textMuted,
                                        indicatorColor = c.accentDim,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "games",
                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            ) {
                composable("games") {
                    GamesScreen(
                        app = app,
                        onOpenMatch = { id ->
                            val encoded = Uri.encode(id)
                            navController.navigate("match/$encoded")
                        },
                    )
                }
                composable("accuracy") { AccuracyScreen(app = app) }
                composable("picks") { PicksScreen(app = app) }
                composable("settings") { SettingsScreen(app = app) }
                composable(
                    route = "match/{matchId}",
                    arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
                ) { entry ->
                    val decoded = entry.arguments?.getString("matchId") ?: ""
                    MatchDetailScreen(
                        app = app,
                        matchId = decoded,
                        onBack = { navController.navigateUp() },
                    )
                }
            }
        }
    }
}
