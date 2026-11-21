package com.edgeledger.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.edgeledger.app.ui.screens.CompareScreen
import com.edgeledger.app.ui.screens.GamesScreen
import com.edgeledger.app.ui.screens.MatchDetailScreen
import com.edgeledger.app.ui.screens.PicksScreen
import com.edgeledger.app.ui.screens.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as EdgeLedgerApp
        // Zero-flash cold start: hold splash until DataStore theme loads (TH-06)
        splash.setKeepOnScreenCondition { !app.prefsRepo.loaded.value }

        setContent {
            val themeConfig by app.prefsRepo.theme.collectAsStateWithLifecycle()
            EdgeLedgerTheme(config = themeConfig) {
                EdgeLedgerRoot(app = app)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EdgeLedgerRoot(app: EdgeLedgerApp) {
    val navController = rememberNavController()
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "games"

    val tabs = listOf(
        BottomTab("games", "Games", Icons.Outlined.EmojiEvents),
        BottomTab("compare", "Compare", Icons.Outlined.CompareArrows),
        BottomTab("accuracy", "Accuracy", Icons.Outlined.QueryStats),
        BottomTab("picks", "Picks", Icons.Outlined.BookmarkBorder),
        BottomTab("settings", "Settings", Icons.Outlined.Settings),
    )

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
                                    // AX-03: Long-press Settings icon triggers Safe Mode Theme Reset
                                    if (tab.route == "settings") {
                                        Box(
                                            modifier = Modifier.combinedClickable(
                                                onClick = {
                                                    if (!selected) {
                                                        navController.navigate("settings") {
                                                            popUpTo("games") { saveState = true }
                                                            launchSingleTop = true
                                                            restoreState = true
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    app.prefsRepo.resetTheme()
                                                    Toast.makeText(context, "Safe Mode: Theme reset to Midnight Broadcast", Toast.LENGTH_SHORT).show()
                                                },
                                            ),
                                        ) {
                                            Icon(tab.icon, contentDescription = "Settings (Long-press to reset theme)")
                                        }
                                    } else {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
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
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "games",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("games") {
                    GamesScreen(
                        app = app,
                        onOpenMatch = { id ->
                            val encoded = URLEncoder.encode(id, "UTF-8")
                            navController.navigate("match/$encoded")
                        },
                    )
                }
                composable("compare") { CompareScreen(app = app) }
                composable("accuracy") { AccuracyScreen(app = app) }
                composable("picks") { PicksScreen(app = app) }
                composable("settings") { SettingsScreen(app = app) }
                composable(
                    route = "match/{matchId}",
                    arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
                ) { entry ->
                    val raw = entry.arguments?.getString("matchId") ?: ""
                    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    MatchDetailScreen(
                        app = app,
                        matchId = decoded,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
