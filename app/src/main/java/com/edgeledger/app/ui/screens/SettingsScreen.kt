package com.edgeledger.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.BuildConfig
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.engine.DevigMethod
import com.edgeledger.app.engine.LineMovePoint
import com.edgeledger.app.engine.PlayerPropPrediction
import com.edgeledger.app.engine.StakingMode
import com.edgeledger.app.model.OddsDisplayFormat
import com.edgeledger.app.model.RiskStyle
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.theme.DensityPreset
import com.edgeledger.app.theme.FixtureLayoutMode
import com.edgeledger.app.theme.FontFamilyPreset
import com.edgeledger.app.theme.HapticLevel
import com.edgeledger.app.theme.MotionSpeed
import com.edgeledger.app.theme.ShapeFamily
import com.edgeledger.app.theme.SurfaceStyle
import com.edgeledger.app.theme.ThemeMode
import com.edgeledger.app.theme.ThemePresets
import com.edgeledger.app.theme.autoFixContrastHex
import com.edgeledger.app.theme.computeContrastRatio
import com.edgeledger.app.theme.hslToHexPublic
import com.edgeledger.app.theme.parseHexColor
import com.edgeledger.app.theme.rgbToHslPublic
import com.edgeledger.app.theme.toHexString
import com.edgeledger.app.ui.components.FormDots
import com.edgeledger.app.ui.components.LineMovementChart
import com.edgeledger.app.ui.components.PlayerPropCard
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TeamAvatar
import com.edgeledger.app.ui.components.SegmentedTabs
import com.edgeledger.app.ui.components.TokenPill
import com.edgeledger.app.ui.components.scaledSp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val context = LocalContext.current

    val config by app.prefsRepo.theme.collectAsStateWithLifecycle()
    val savedThemes by app.prefsRepo.savedThemes.collectAsStateWithLifecycle()
    val canUndo by app.prefsRepo.canUndoFlow.collectAsStateWithLifecycle()
    val canRedo by app.prefsRepo.canRedoFlow.collectAsStateWithLifecycle()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()
    val apiKeys by app.prefsRepo.apiKeys.collectAsStateWithLifecycle()
    val persistedQuotas by app.syncRepo.apiQuotas.collectAsStateWithLifecycle(initialValue = emptyList())
    val quotas = remember(apiKeys, persistedQuotas) {
        app.syncRepo.getQuotaSummaryWithPersistence(apiKeys, persistedQuotas)
    }
    val syncing by app.syncRepo.syncing.collectAsStateWithLifecycle()
    val persistErr by app.prefsRepo.persistenceError.collectAsStateWithLifecycle()
    val diagnostics by app.recentDiagnostics.collectAsStateWithLifecycle()

    var sectionTab by rememberSaveable { mutableIntStateOf(0) }
    var advancedMode by rememberSaveable { mutableStateOf(false) }
    var previewTab by rememberSaveable { mutableIntStateOf(0) }
    var activeColorRole by rememberSaveable { mutableIntStateOf(0) }
    var customThemeName by rememberSaveable { mutableStateOf("") }
    var importJsonText by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var flatUnitInput by rememberSaveable(prefs.flatUnitAmount) { mutableStateOf(String.format(Locale.US, "%.0f", prefs.flatUnitAmount)) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            statusMsg = "Notification permission denied; enable it in Android settings to receive +EV alerts."
        }
    }

    val themeExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val jsonStr = app.prefsRepo.exportThemeJson()
            app.appScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Could not open output stream")
                    stream.use { os -> os.write(jsonStr.toByteArray(Charsets.UTF_8)) }
                }
                withContext(Dispatchers.Main) {
                    statusMsg = if (result.isSuccess) {
                        "Saved .theme.json to device storage."
                    } else {
                        "Could not save .theme.json file."
                    }
                }
            }
        }
    }

    var oddsApiInput by rememberSaveable(apiKeys.theOddsApiKey) { mutableStateOf(apiKeys.theOddsApiKey) }
    var fdOrgInput by rememberSaveable(apiKeys.footballDataOrgKey) { mutableStateOf(apiKeys.footballDataOrgKey) }
    var oddsPapiInput by rememberSaveable(apiKeys.oddsPapiKey) { mutableStateOf(apiKeys.oddsPapiKey) }
    var cricketInput by rememberSaveable(apiKeys.cricketDataKey) { mutableStateOf(apiKeys.cricketDataKey) }
    var apiSportsInput by rememberSaveable(apiKeys.apiSportsKey) { mutableStateOf(apiKeys.apiSportsKey) }
    var balldontlieInput by rememberSaveable(apiKeys.balldontlieKey) { mutableStateOf(apiKeys.balldontlieKey) }
    var liveTennisInput by rememberSaveable(apiKeys.liveTennisApiKey) { mutableStateOf(apiKeys.liveTennisApiKey) }
    var openWeatherInput by rememberSaveable(apiKeys.openWeatherApiKey) { mutableStateOf(apiKeys.openWeatherApiKey) }
    var sportsGameOddsInput by rememberSaveable(apiKeys.sportsGameOddsKey) { mutableStateOf(apiKeys.sportsGameOddsKey) }
    var parlayApiInput by rememberSaveable(apiKeys.parlayApiKey) { mutableStateOf(apiKeys.parlayApiKey) }
    var cfbdInput by rememberSaveable(apiKeys.cfbdApiKey) { mutableStateOf(apiKeys.cfbdApiKey) }

    var balanceInput by remember(prefs.bankroll) { mutableStateOf(prefs.bankroll.toInt().toString()) }
    var paperInput by remember(prefs.paperBankroll) { mutableStateOf(prefs.paperBankroll.toInt().toString()) }

    // Debounced local slider states to prevent DataStore write & undo-stack spam during drags
    var localCornerDp by remember(config.cardCornerOverrideDp) { mutableFloatStateOf((config.cardCornerOverrideDp ?: 16).toFloat()) }
    var localFontScale by remember(config.fontScale) { mutableFloatStateOf(config.fontScale) }
    var localMinEv by remember(prefs.minEvPct) { mutableFloatStateOf(prefs.minEvPct.toFloat()) }
    var localMinProb by remember(prefs.minProbFloorPct) { mutableFloatStateOf(prefs.minProbFloorPct.toFloat()) }

    val colorRoles = listOf(
        "Accent" to config.accentHex,
        "Win" to config.winHex,
        "Draw" to config.drawHex,
        "Loss" to config.lossHex,
        "Pending" to config.pendingHex,
        "Odds Up" to config.oddsUpHex,
        "Odds Down" to config.oddsDownHex,
        "Value" to config.valueHighlightHex,
        "Sharp" to config.sharpBadgeHex,
    )
    val safeRoleIdx = activeColorRole.coerceIn(0, colorRoles.lastIndex)
    val activeHex = colorRoles[safeRoleIdx].second
    var hexInput by rememberSaveable(activeHex, safeRoleIdx) { mutableStateOf(activeHex) }

    fun updateActiveRoleHex(newHex: String) {
        app.prefsRepo.updateTheme { t ->
            val base = t.copy(id = "custom-theme", name = "Custom Theme")
            when (safeRoleIdx) {
                0 -> base.copy(accentHex = newHex)
                1 -> base.copy(winHex = newHex)
                2 -> base.copy(drawHex = newHex)
                3 -> base.copy(lossHex = newHex)
                4 -> base.copy(pendingHex = newHex)
                5 -> base.copy(oddsUpHex = newHex)
                6 -> base.copy(oddsDownHex = newHex)
                7 -> base.copy(valueHighlightHex = newHex)
                else -> base.copy(sharpBadgeHex = newHex)
            }
        }
    }

    val activeColorObj = parseHexColor(activeHex)
    val activeContrast = computeContrastRatio(activeColorObj, c.surface)
    val textContrast = tokens.textSurfaceContrast

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", color = c.text, fontSize = scaledSp(19f), fontWeight = FontWeight.ExtraBold)
                    StatusBadge(
                        text = "WCAG ${String.format(Locale.US, "%.1f", textContrast)}:1 ${when { textContrast >= 7.0f -> "AAA"; textContrast >= 4.5f -> "AA"; else -> "FAIL" }}",
                        color = if (textContrast >= 4.5f) c.win else c.loss,
                        containerColor = if (textContrast >= 4.5f) c.winDim else c.lossDim,
                    )
                }
                Text("Customize theme, enter optional free API keys, and tune your prediction engine.", color = c.textMuted, fontSize = scaledSp(12.5f))
            }
        }

        item {
            SegmentedTabs(
                tabs = listOf("Theme", "APIs", "Model", "Alerts"),
                selectedIndex = sectionTab,
                onSelect = { idx ->
                    sectionTab = idx
                    if (idx == 1) app.syncRepo.refreshQuotaSummary()
                },
            )
        }

        if (!persistErr.isNullOrBlank()) {
            item {
                Surface(color = c.lossDim, shape = tokens.shapes.medium, border = BorderStroke(1.dp, c.loss), modifier = Modifier.fillMaxWidth()) {
                    Text(persistErr.orEmpty(), color = c.loss, fontWeight = FontWeight.Bold, fontSize = scaledSp(12f), modifier = Modifier.padding(10.dp))
                }
            }
        }
        if (statusMsg != null) {
            item {
                Surface(
                    color = c.accentDim,
                    shape = tokens.shapes.medium,
                    border = BorderStroke(1.dp, c.accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(statusMsg.orEmpty(), color = c.accent, fontWeight = FontWeight.Bold, fontSize = scaledSp(12.5f), modifier = Modifier.padding(10.dp))
                }
            }
        }

        when (sectionTab) {
            0 -> {
                item {
                    SegmentedTabs(
                        tabs = listOf("Simple Editor", "Advanced Tokens"),
                        selectedIndex = if (advancedMode) 1 else 0,
                        onSelect = { idx ->
                            advancedMode = idx == 1
                            if (!advancedMode) activeColorRole = activeColorRole.coerceIn(0, 3)
                        },
                    )
                }

                item {
                    SectionCard(useHeroGradient = true) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("PREVIEW", color = c.accent, fontSize = scaledSp(11f), fontWeight = FontWeight.ExtraBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Match", "Prop", "Chart").forEachIndexed { idx, lbl ->
                                    TokenPill(lbl, previewTab == idx, { previewTab = idx })
                                }
                            }
                        }
                        when (previewTab) {
                            0 -> {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TeamAvatar("Arsenal", size = 32.dp)
                                        Text("Arsenal", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(13.5f))
                                    }
                                    StatusBadge("Strong · 74%", c.win, c.winDim)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Chelsea", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(13.5f))
                                        TeamAvatar("Chelsea", size = 32.dp)
                                    }
                                }
                                ProbBar("Arsenal", "Chelsea", 0.56, 0.22, 0.22, barHeight = 9.dp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    FormDots(listOf("W", "W", "D", "W", "W"))
                                    Text("Last 5", color = c.textMuted, fontSize = scaledSp(10.5f))
                                    FormDots(listOf("W", "L", "D", "W", "L"), alignEnd = true)
                                }
                            }
                            1 -> {
                                PlayerPropCard(
                                    prop = PlayerPropPrediction(
                                        id = "preview-prop",
                                        playerName = "Sample Forward",
                                        team = "Arsenal",
                                        position = "RW",
                                        marketLabel = "Shots on Target",
                                        lineLabel = "Over 1.5 SOT",
                                        projectedValue = 1.8,
                                        overChancePct = 62,
                                        rangeLow = 1.0,
                                        rangeHigh = 3.0,
                                        confidenceLabel = "Strong",
                                        plainReason = "Theme preview card showing prop layout.",
                                    ),
                                    onSave = { statusMsg = "Preview card interactive!" },
                                )
                            }
                            else -> {
                                LineMovementChart(
                                    points = listOf(
                                        LineMovePoint("Open", 51f, 50f, false),
                                        LineMovePoint("-24h", 53f, 51f, false),
                                        LineMovePoint("-6h", 57f, 53f, true),
                                        LineMovePoint("-1h", 58f, 55f, false),
                                        LineMovePoint("Now", 59f, 56f, false),
                                    ),
                                )
                            }
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { app.prefsRepo.undoTheme() }, enabled = canUndo, shape = tokens.shapes.button, modifier = Modifier.weight(1f)) {
                            Text("Undo", fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { app.prefsRepo.redoTheme() }, enabled = canRedo, shape = tokens.shapes.button, modifier = Modifier.weight(1f)) {
                            Text("Redo", fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { app.prefsRepo.resetTheme(); statusMsg = "Reset to Midnight Broadcast." }, shape = tokens.shapes.button, modifier = Modifier.weight(1f)) {
                            Text("Reset", fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Curated Presets", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ThemePresets.all, key = { it.id }) { preset ->
                                val active = config.id == preset.id
                                Surface(
                                    color = if (active) c.accentDim else c.surfaceRaised,
                                    shape = tokens.shapes.medium,
                                    border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) c.accent else c.border),
                                    modifier = Modifier.clickable { app.prefsRepo.updateTheme { preset } },
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(parseHexColor(preset.accentHex)))
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(parseHexColor(preset.winHex)))
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(parseHexColor(preset.drawHex)))
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(parseHexColor(preset.lossHex)))
                                        }
                                        Text(preset.name, color = if (active) c.accent else c.text, fontSize = scaledSp(12.5f), fontWeight = FontWeight.ExtraBold)
                                        Text(preset.mode.label, color = c.textMuted, fontSize = scaledSp(10.5f))
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Favorite Team Palettes", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text("Seed your app colors instantly from your favorite club or franchise.", color = c.textMuted, fontSize = scaledSp(12f))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ThemePresets.teamPalettes, key = { it.name }) { tp ->
                                Surface(
                                    color = c.surfaceRaised,
                                    shape = tokens.shapes.medium,
                                    border = BorderStroke(1.dp, c.border),
                                    modifier = Modifier.clickable {
                                        app.prefsRepo.updateTheme { cur ->
                                            cur.copy(
                                                id = "team-${tp.name.lowercase(Locale.US).replace(" ", "-")}",
                                                name = tp.name,
                                                accentHex = tp.accentHex,
                                                winHex = tp.winHex,
                                                drawHex = tp.drawHex,
                                                lossHex = tp.lossHex,
                                            )
                                        }
                                        statusMsg = "Applied ${tp.name} team palette!"
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(parseHexColor(tp.accentHex)))
                                        Text(tp.name, color = c.text, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Appearance & Color-Blind Modes", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ThemeMode.entries, key = { it.name }) { m ->
                                TokenPill(m.label, config.mode == m, {
                                    app.prefsRepo.updateTheme { cur ->
                                        val lightSurface = Color(0xFFFFFFFF)
                                        val nextAccent = if (m == ThemeMode.LIGHT) autoFixContrastHex(cur.accentHex, lightSurface, 4.5f) else cur.accentHex
                                        val nextWin = if (m == ThemeMode.LIGHT) autoFixContrastHex(cur.winHex, lightSurface, 4.5f) else cur.winHex
                                        val nextDraw = if (m == ThemeMode.LIGHT) autoFixContrastHex(cur.drawHex, lightSurface, 4.5f) else cur.drawHex
                                        val nextLoss = if (m == ThemeMode.LIGHT) autoFixContrastHex(cur.lossHex, lightSurface, 4.5f) else cur.lossHex
                                        cur.copy(
                                            id = "custom-theme",
                                            name = "Custom Theme",
                                            mode = m,
                                            accentHex = nextAccent,
                                            winHex = nextWin,
                                            drawHex = nextDraw,
                                            lossHex = nextLoss,
                                            batterySaverAutoAmoled = if (m == ThemeMode.LIGHT) false else cur.batterySaverAutoAmoled,
                                        )
                                    }
                                })
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Android Dynamic Color (Material You)", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                    Text("Syncs accent colors with your wallpaper.", color = c.textMuted, fontSize = scaledSp(12f))
                                }
                                Switch(checked = config.useDynamicColor, onCheckedChange = { v -> app.prefsRepo.updateTheme { it.copy(useDynamicColor = v) } })
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Battery Saver → Auto AMOLED", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                Text("Automatically switches to pure black on low battery.", color = c.textMuted, fontSize = scaledSp(12f))
                            }
                            Switch(checked = config.batterySaverAutoAmoled, onCheckedChange = { v -> app.prefsRepo.updateTheme { it.copy(batterySaverAutoAmoled = v) } })
                        }
                    }
                }

                item {
                    val displayedRoles = if (advancedMode) colorRoles else colorRoles.take(4)
                    val hsl = rgbToHslPublic(activeColorObj)
                    val rVal = (activeColorObj.red * 255f).roundToInt()
                    val gVal = (activeColorObj.green * 255f).roundToInt()
                    val bVal = (activeColorObj.blue * 255f).roundToInt()

                    SectionCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (advancedMode) "All 9 Semantic Colors" else "Accent & Outcome Colors", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                            StatusBadge(
                                text = "${String.format(Locale.US, "%.1f", activeContrast)}:1",
                                color = if (activeContrast >= 4.5f) c.win else c.draw,
                                containerColor = if (activeContrast >= 4.5f) c.winDim else c.drawDim,
                            )
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(displayedRoles.indices.toList(), key = { it }) { idx ->
                                TokenPill(displayedRoles[idx].first, safeRoleIdx == idx, { activeColorRole = idx })
                            }
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(ThemePresets.accentSwatches, key = { it }) { swHex ->
                                val selected = activeHex.equals(swHex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .semantics { contentDescription = "Color swatch $swHex" }
                                        .clip(CircleShape)
                                        .background(parseHexColor(swHex))
                                        .border(if (selected) 3.dp else 1.dp, if (selected) c.text else c.border, CircleShape)
                                        .clickable {
                                            hexInput = swHex
                                            updateActiveRoleHex(swHex)
                                        },
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { hexInput = it },
                                label = { Text("Hex (#RRGGBB)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = { if (Regex("^#?[0-9A-Fa-f]{6}$").matches(hexInput.trim())) { updateActiveRoleHex(hexInput.trim()) } else { statusMsg = "Enter a valid 6-digit hex color like #38BDF8." } },
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                shape = tokens.shapes.button,
                            ) { Text("Apply", fontWeight = FontWeight.Bold) }
                        }

                        ChannelSlider("Hue", hsl[0], 0f..360f, c.accent) { nh ->
                            updateActiveRoleHex(hslToHexPublic(nh, hsl[1], hsl[2]))
                        }
                        ChannelSlider("Sat", hsl[1] * 100f, 0f..100f, c.accent) { ns ->
                            updateActiveRoleHex(hslToHexPublic(hsl[0], ns / 100f, hsl[2]))
                        }
                        ChannelSlider("Light", hsl[2] * 100f, 5f..95f, c.accent) { nl ->
                            updateActiveRoleHex(hslToHexPublic(hsl[0], hsl[1], nl / 100f))
                        }

                        if (advancedMode) {
                            ChannelSlider("Red", rVal.toFloat(), 0f..255f, Color(0xFFEF4444)) { nr ->
                                updateActiveRoleHex(Color(nr.roundToInt(), gVal, bVal).toHexString())
                            }
                            ChannelSlider("Green", gVal.toFloat(), 0f..255f, Color(0xFF10B981)) { ng ->
                                updateActiveRoleHex(Color(rVal, ng.roundToInt(), bVal).toHexString())
                            }
                            ChannelSlider("Blue", bVal.toFloat(), 0f..255f, Color(0xFF3B82F6)) { nb ->
                                updateActiveRoleHex(Color(rVal, gVal, nb.roundToInt()).toHexString())
                            }
                        }

                        if (activeContrast < 4.5f) {
                            Button(
                                onClick = {
                                    val fixed = autoFixContrastHex(activeHex, c.surface)
                                    hexInput = fixed
                                    updateActiveRoleHex(fixed)
                                    statusMsg = "Auto-fixed ${colorRoles[safeRoleIdx].first} to $fixed (WCAG AA)."
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = c.draw, contentColor = c.onDraw),
                                shape = tokens.shapes.button,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("One-Tap Auto-Fix Contrast (>= 4.5:1)", fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Layout, Shape & Typography", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))

                        Text("Game List Layout", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FixtureLayoutMode.entries.forEach { fl ->
                                TokenPill(fl.label, config.fixtureLayout == fl, { app.prefsRepo.updateTheme { it.copy(fixtureLayout = fl) } }, Modifier.weight(1f))
                            }
                        }

                        Text("Card Style", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SurfaceStyle.entries, key = { it.name }) { st ->
                                TokenPill(st.label, config.surfaceStyle == st, { app.prefsRepo.updateTheme { it.copy(surfaceStyle = st) } })
                            }
                        }

                        Text("Corner Shape", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ShapeFamily.entries, key = { it.name }) { sf ->
                                TokenPill(sf.label, config.shapeFamily == sf, { app.prefsRepo.updateTheme { it.copy(shapeFamily = sf, cardCornerOverrideDp = null) } })
                            }
                        }

                        if (advancedMode) {
                            Text("Custom Card Corner Radius: ${localCornerDp.roundToInt()}dp", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                            Slider(
                                value = localCornerDp,
                                onValueChange = { localCornerDp = it },
                                onValueChangeFinished = { app.prefsRepo.updateTheme { it.copy(cardCornerOverrideDp = localCornerDp.roundToInt()) } },
                                valueRange = 0f..28f,
                                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                            )
                        }

                        Text("Font Family", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(FontFamilyPreset.entries, key = { it.name }) { ff ->
                                TokenPill(ff.label, config.fontFamily == ff, { app.prefsRepo.updateTheme { it.copy(fontFamily = ff) } })
                            }
                        }

                        Text("Density", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DensityPreset.entries, key = { it.name }) { dp ->
                                TokenPill(dp.label, config.density == dp, { app.prefsRepo.updateTheme { it.copy(density = dp) } })
                            }
                        }

                        Text("Global Text Scale: ${(localFontScale * 100).roundToInt()}%", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        Slider(
                            value = localFontScale,
                            onValueChange = { localFontScale = (it * 20).roundToInt() / 20f },
                            onValueChangeFinished = { app.prefsRepo.updateTheme { it.copy(fontScale = localFontScale) } },
                            valueRange = 0.85f..1.35f,
                            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Gradient Hero Cards", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                            Switch(checked = config.useGradientHero, onCheckedChange = { v -> app.prefsRepo.updateTheme { it.copy(useGradientHero = v) } })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Subtle Screen Background Gradient", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                            Switch(checked = config.useBackgroundGradient, onCheckedChange = { v -> app.prefsRepo.updateTheme { it.copy(useBackgroundGradient = v) } })
                        }

                        Text("Motion Speed", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(MotionSpeed.entries, key = { it.name }) { ms ->
                                TokenPill(ms.label, config.motionSpeed == ms, { app.prefsRepo.updateTheme { it.copy(motionSpeed = ms) } })
                            }
                        }

                        Text("Haptic Feedback", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            HapticLevel.entries.forEach { hl ->
                                TokenPill(hl.label, config.haptics == hl, { app.prefsRepo.updateTheme { it.copy(haptics = hl) } }, Modifier.weight(1f))
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Saved Custom Themes (${savedThemes.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customThemeName,
                                onValueChange = { customThemeName = it },
                                label = { Text("Custom theme name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    app.prefsRepo.saveCustomTheme(customThemeName)
                                    statusMsg = "Saved custom theme!"
                                    customThemeName = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                shape = tokens.shapes.button,
                            ) { Text("Save", fontWeight = FontWeight.ExtraBold) }
                        }
                        savedThemes.forEach { st ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f).clickable {
                                        app.prefsRepo.updateTheme { st }
                                        statusMsg = "Applied \"${st.name}\"."
                                    },
                                ) {
                                    Box(Modifier.size(14.dp).clip(CircleShape).background(parseHexColor(st.accentHex)))
                                    Text(st.name, color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { app.prefsRepo.duplicateCustomTheme(st.id) }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                        Text("Copy", fontSize = scaledSp(11f))
                                    }
                                    OutlinedButton(onClick = { app.prefsRepo.deleteCustomTheme(st.id) }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                        Text("Delete", fontSize = scaledSp(11f), color = c.textMuted)
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { themeExportLauncher.launch("edgeledger-theme.theme.json") },
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                            shape = tokens.shapes.button,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save .theme.json Backup", fontWeight = FontWeight.ExtraBold) }

                        OutlinedTextField(
                            value = importJsonText,
                            onValueChange = { importJsonText = it },
                            label = { Text("Paste .theme.json to Import (v1 or v2)") },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                statusMsg = if (app.prefsRepo.importThemeJson(importJsonText)) {
                                    importJsonText = ""
                                    "Theme imported and migrated cleanly!"
                                } else {
                                    "Invalid theme JSON."
                                }
                            },
                            shape = tokens.shapes.button,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Import Theme JSON", color = c.accent, fontWeight = FontWeight.Bold) }

                        OutlinedButton(
                            onClick = {
                                app.prefsRepo.resetTheme()
                                statusMsg = "Safe Mode: Theme reset to Midnight Broadcast."
                            },
                            shape = tokens.shapes.button,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Safe Mode: Reset Theme to Default", color = c.textSecondary, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            1 -> {
                item {
                    SectionCard {
                        Text("Optional Free API Keys", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15.5f))
                        Text(
                            "EdgeLedger runs 100% free with zero keys. Add any personal free keys below (encrypted with Android Keystore AES-GCM) to unlock live cross-bookmaker odds, Football-Data.org fixtures, and CricketData matches.",
                            color = c.textMuted,
                            fontSize = scaledSp(12f),
                        )

                        SettingsApiKeyField("The Odds API", "500 credits/mo · Pinnacle, Betfair, Bet365 & US bookmaker odds", oddsApiInput) { oddsApiInput = it }
                        SettingsApiKeyField("Football-Data.org", "12 leagues · Official European football schedules & standings", fdOrgInput) { fdOrgInput = it }
                        SettingsApiKeyField("OddsPapi", "250 req/mo · Sharp exchange & bookmaker lines", oddsPapiInput) { oddsPapiInput = it }
                        SettingsApiKeyField("CricketData.org", "100 hits/day · Live IPL, T20, ODI & international cricket", cricketInput) { cricketInput = it }
                        SettingsApiKeyField("API-Sports", "100 req/day per sport · Football, NBA, NFL, NHL, MLB, F1, MMA", apiSportsInput) { apiSportsInput = it }
                        SettingsApiKeyField("Balldontlie.io", "Free tier · NBA, NFL, MLB, NHL & EPL games & teams", balldontlieInput) { balldontlieInput = it }
                        SettingsApiKeyField("Live Tennis API", "100 req/day · Live ATP & WTA fixtures, scores & rankings", liveTennisInput) { liveTennisInput = it }
                        SettingsApiKeyField("OpenWeatherMap", "1,000 req/day · Stadium weather backup alongside Open-Meteo", openWeatherInput) { openWeatherInput = it }
                        SettingsApiKeyField("SportsGameOdds", "2,500 game objs/mo · 9 US/global books + Fair Odds (1 game = 1 obj)", sportsGameOddsInput) { sportsGameOddsInput = it }
                        SettingsApiKeyField("ParlayAPI", "1,000 credits/mo · 30+ sportsbooks, +EV, arbitrage & consensus", parlayApiInput) { parlayApiInput = it }
                        SettingsApiKeyField("CFBD / CBBD", "1,000 calls/mo shared · NCAAF & NCAAB lines, SP+, Elo & EPA", cfbdInput) { cfbdInput = it }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Polymarket Sports Odds (Keyless)", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                Text("Free prediction-market consensus probabilities", color = c.textMuted, fontSize = scaledSp(11.5f))
                            }
                            Switch(checked = apiKeys.enablePolymarket, onCheckedChange = { v -> app.prefsRepo.updateApiKeys { it.copy(enablePolymarket = v) } })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Kalshi Sports Market Data (Keyless)", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                Text("Free public exchange probabilities", color = c.textMuted, fontSize = scaledSp(11.5f))
                            }
                            Switch(checked = apiKeys.enableKalshi, onCheckedChange = { v -> app.prefsRepo.updateApiKeys { it.copy(enableKalshi = v) } })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Manifold Sports Markets (Keyless)", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                Text("Free community prediction-market probabilities", color = c.textMuted, fontSize = scaledSp(11.5f))
                            }
                            Switch(checked = apiKeys.enableManifold, onCheckedChange = { v -> app.prefsRepo.updateApiKeys { it.copy(enableManifold = v) } })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("SX Bet V3 Exchange (Keyless)", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                Text("P2P decentralized sports exchange orderbook odds", color = c.textMuted, fontSize = scaledSp(11.5f))
                            }
                            Switch(checked = apiKeys.enableSxBet, onCheckedChange = { v -> app.prefsRepo.updateApiKeys { it.copy(enableSxBet = v) } })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("PredictionBubbles Feed (Keyless)", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                Text("Unified 15-min Polymarket & Kalshi sports feed", color = c.textMuted, fontSize = scaledSp(11.5f))
                            }
                            Switch(checked = apiKeys.enablePredictionBubbles, onCheckedChange = { v -> app.prefsRepo.updateApiKeys { it.copy(enablePredictionBubbles = v) } })
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    app.prefsRepo.updateApiKeys {
                                        it.copy(
                                            theOddsApiKey = oddsApiInput.trim(),
                                            footballDataOrgKey = fdOrgInput.trim(),
                                            oddsPapiKey = oddsPapiInput.trim(),
                                            cricketDataKey = cricketInput.trim(),
                                            apiSportsKey = apiSportsInput.trim(),
                                            balldontlieKey = balldontlieInput.trim(),
                                            liveTennisApiKey = liveTennisInput.trim(),
                                            openWeatherApiKey = openWeatherInput.trim(),
                                            sportsGameOddsKey = sportsGameOddsInput.trim(),
                                            parlayApiKey = parlayApiInput.trim(),
                                            cfbdApiKey = cfbdInput.trim(),
                                        )
                                    }
                                    app.syncRepo.refreshQuotaSummary()
                                    statusMsg = "API keys encrypted & saved on your device."
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                shape = tokens.shapes.button,
                                modifier = Modifier.weight(1f),
                            ) { Text("Save Keys", fontWeight = FontWeight.ExtraBold) }
                            OutlinedButton(
                                enabled = !syncing,
                                onClick = {
                                    app.prefsRepo.updateApiKeys {
                                        it.copy(
                                            theOddsApiKey = oddsApiInput.trim(),
                                            footballDataOrgKey = fdOrgInput.trim(),
                                            oddsPapiKey = oddsPapiInput.trim(),
                                            cricketDataKey = cricketInput.trim(),
                                            apiSportsKey = apiSportsInput.trim(),
                                            balldontlieKey = balldontlieInput.trim(),
                                            liveTennisApiKey = liveTennisInput.trim(),
                                            openWeatherApiKey = openWeatherInput.trim(),
                                            sportsGameOddsKey = sportsGameOddsInput.trim(),
                                            parlayApiKey = parlayApiInput.trim(),
                                            cfbdApiKey = cfbdInput.trim(),
                                        )
                                    }
                                    statusMsg = "Keys saved! Syncing live feeds..."
                                    app.appScope.launch { app.syncRepo.runOnDeviceWeeklySync() }
                                },
                                shape = tokens.shapes.button,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (syncing) "Syncing..." else "Save & Sync", color = c.accent, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                if (quotas.isNotEmpty()) {
                    item {
                        SectionCard {
                            Text("API Quota & Provider Status", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                            quotas.forEach { q ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(q.provider, color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13f))
                                        Text("${q.freeLimitLabel} · ${q.lastStatus}", color = c.textMuted, fontSize = scaledSp(11.5f))
                                    }
                                    StatusBadge(
                                        text = if (q.configured) "${q.callsUsed} used" else "Optional",
                                        color = if (q.configured) c.win else c.textMuted,
                                        containerColor = if (q.configured) c.winDim else c.surfaceRaised,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                item {
                    SectionCard {
                        Text("Bankroll & Staking Engine", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = balanceInput,
                                onValueChange = { balanceInput = it },
                                label = { Text("Real Balance ($)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = paperInput,
                                onValueChange = { paperInput = it },
                                label = { Text("Paper Balance ($)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = flatUnitInput,
                                onValueChange = { flatUnitInput = it },
                                label = { Text("Flat Unit Stake ($)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = {
                                    val amt = flatUnitInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }
                                    if (amt != null) {
                                        app.prefsRepo.updatePrefs { it.copy(flatUnitAmount = amt) }
                                        statusMsg = "Flat unit stake updated to $${String.format(Locale.US, "%.0f", amt)}."
                                    } else {
                                        statusMsg = "Enter a valid flat unit amount (>= 1)."
                                    }
                                },
                                shape = tokens.shapes.button,
                            ) { Text("Save Unit", color = c.accent, fontWeight = FontWeight.Bold) }
                        }
                        Button(
                            onClick = {
                                val rb = balanceInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 50.0 } ?: prefs.bankroll
                                val pb = paperInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 50.0 } ?: prefs.paperBankroll
                                app.prefsRepo.updatePrefs { it.copy(bankroll = rb, paperBankroll = pb) }
                                statusMsg = "Updated Real ($${rb.toInt()}) & Paper ($${pb.toInt()}) balances."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                            shape = tokens.shapes.button,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Apply Balances", fontWeight = FontWeight.ExtraBold) }

                        Text("Staking Strategy", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(StakingMode.entries, key = { it.name }) { sm ->
                                TokenPill(sm.label, prefs.stakingMode == sm, { app.prefsRepo.updatePrefs { it.copy(stakingMode = sm) } })
                            }
                        }

                        Text("Risk Style", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            RiskStyle.entries.forEach { style ->
                                TokenPill(style.label, prefs.riskStyle == style, { app.prefsRepo.updatePrefs { it.copy(riskStyle = style) } }, Modifier.weight(1f))
                            }
                        }

                        Text("Odds Display Format", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(OddsDisplayFormat.entries, key = { it.name }) { fmt ->
                                TokenPill(fmt.label, prefs.oddsFormat == fmt, { app.prefsRepo.updatePrefs { it.copy(oddsFormat = fmt) } })
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Fair Odds & Value Filters", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text("Vig-Removal Method (Fair Market Probability)", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DevigMethod.entries, key = { it.name }) { dm ->
                                TokenPill(dm.label, prefs.devigMethod == dm, { app.prefsRepo.updatePrefs { it.copy(devigMethod = dm) } })
                            }
                        }

                        Text("Minimum Value Edge to Flag: +${String.format(Locale.US, "%.1f", localMinEv)}%", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        Slider(
                            value = localMinEv,
                            onValueChange = { localMinEv = (it * 2).roundToInt() / 2f },
                            onValueChangeFinished = { app.prefsRepo.updatePrefs { it.copy(minEvPct = localMinEv.toDouble()) } },
                            valueRange = 0.5f..8.0f,
                            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        )

                        Text("Minimum Win Probability Floor: ${localMinProb.roundToInt()}%", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                        Slider(
                            value = localMinProb,
                            onValueChange = { localMinProb = it },
                            onValueChangeFinished = { app.prefsRepo.updatePrefs { it.copy(minProbFloorPct = localMinProb.roundToInt()) } },
                            valueRange = 10f..45f,
                            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Update Once a Week", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                Text("Background refresh every 7 days via WorkManager.", color = c.textMuted, fontSize = scaledSp(12f))
                            }
                            Switch(checked = prefs.autoSyncWeekly, onCheckedChange = { v -> app.prefsRepo.updatePrefs { it.copy(autoSyncWeekly = v) } })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Wi-Fi Only for Large CSV Backfills", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                Text("Saves mobile data during weekly dataset syncs.", color = c.textMuted, fontSize = scaledSp(12f))
                            }
                            Switch(checked = prefs.wifiOnlySync, onCheckedChange = { v -> app.prefsRepo.updatePrefs { it.copy(wifiOnlySync = v) } })
                        }
                    }
                }
            }

            else -> {
                item {
                    SectionCard {
                        Text("Value Alerts & Quiet Hours", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Local Value & Steam Flags", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                Text("Highlights games exceeding +${String.format(Locale.US, "%.1f", prefs.minEvPct)}% value edge or >=3% steam moves.", color = c.textMuted, fontSize = scaledSp(12f))
                            }
                            Switch(checked = prefs.alertsEnabled, onCheckedChange = { v ->
                                app.prefsRepo.updatePrefs { it.copy(alertsEnabled = v) }
                                if (v && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            })
                        }
                        Text(
                            String.format(Locale.US, "Quiet Hours: %02d:00 – %02d:00 (30 min cooldown)", prefs.quietHoursStart, prefs.quietHoursEnd),
                            color = c.textSecondary,
                            fontSize = scaledSp(12f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { app.prefsRepo.updatePrefs { it.copy(quietHoursStart = (it.quietHoursStart + 23) % 24) } }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.weight(1f)) {
                                Text("Start -1h", fontSize = scaledSp(11f))
                            }
                            OutlinedButton(onClick = { app.prefsRepo.updatePrefs { it.copy(quietHoursStart = (it.quietHoursStart + 1) % 24) } }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.weight(1f)) {
                                Text("Start +1h", fontSize = scaledSp(11f))
                            }
                            OutlinedButton(onClick = { app.prefsRepo.updatePrefs { it.copy(quietHoursEnd = (it.quietHoursEnd + 23) % 24) } }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.weight(1f)) {
                                Text("End -1h", fontSize = scaledSp(11f))
                            }
                            OutlinedButton(onClick = { app.prefsRepo.updatePrefs { it.copy(quietHoursEnd = (it.quietHoursEnd + 1) % 24) } }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.weight(1f)) {
                                Text("End +1h", fontSize = scaledSp(11f))
                            }
                        }
                    }
                }

                item {
                    SectionCard {
                        Text("Open-Source & Free Data Credits", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        val credits = listOf(
                            "Football-Data.co.uk — 38 European & global football leagues with shots, corners, cards & bookmaker odds",
                            "olbauday/FPL-Core-Insights, FPL Bootstrap & World Football Elo (eloratings.net) — Club/international Elo & EPL injuries",
                            "OpenLigaDB (ODbL 1.0) — Bundesliga, 2. Bundesliga, 3. Liga & DFB-Pokal matchday feeds",
                            "nflverse (CC-BY 4.0 / CC-BY-SA 4.0) — NFL games, spreads, totals, rest days & weekly injury reports",
                            "MoneyPuck, Official NHL Web API & MLB Stats API — NHL shot-level xG, live schedules & standings",
                            "TennisMyLife ATP Database & Tennis Abstract — ATP match history plus weekly ATP/WTA surface Elo",
                            "Greco1899/scrape_ufc_stats, Squiggle AFL, OpenDota & henrygd/ncaa-api — Keyless MMA, AFL, Dota 2 & NCAA feeds",
                            "Open-Meteo (CC BY 4.0) — Free stadium kickoff weather across 89 football, NFL & MLB venues",
                            "Polymarket, Kalshi, Manifold, SX Bet V3 & PredictionBubbles — Public sports exchange probabilities",
                            "The Odds API, SportsGameOdds, ParlayAPI, CFBD/CBBD, OddsPapi, Football-Data.org, CricketData.org & API-Sports — Optional free-tier keys",
                        )
                        credits.forEach { line ->
                            Text("• $line", color = c.textSecondary, fontSize = scaledSp(12f))
                        }
                        Text(
                            "EdgeLedger v${BuildConfig.VERSION_NAME} · 100% On-Device & Local-Only Privacy",
                            color = c.textMuted,
                            fontSize = scaledSp(11.5f),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                item {
                    SectionCard {
                        Text("Informational Analytics & Responsible Play", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text(
                            "EdgeLedger is strictly an informational sports analytics and personal tracking tool. It does not accept wagers, process deposits, or broker bets. All probability estimates are statistical models and carry uncertainty — never risk money you cannot afford to lose.",
                            color = c.textSecondary,
                            fontSize = scaledSp(12f),
                        )
                    }
                }

                if (diagnostics.isNotEmpty()) {
                    item {
                        SectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Recent Diagnostics (${diagnostics.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f))
                                OutlinedButton(
                                    onClick = { app.clearDiagnostics() },
                                    shape = tokens.shapes.button,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                ) { Text("Clear", fontSize = scaledSp(12f)) }
                            }
                            diagnostics.take(8).forEach { entry ->
                                Text(entry, color = c.textMuted, fontSize = scaledSp(12f))
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ChannelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    trackColor: Color,
    onValueCommit: (Float) -> Unit,
) {
    val c = AppTheme.tokens.colors
    var localValue by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label ${localValue.roundToInt()}", color = c.textSecondary, fontSize = scaledSp(11.5f), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.28f))
        Slider(
            value = localValue,
            onValueChange = { localValue = it },
            onValueChangeFinished = { onValueCommit(localValue) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = trackColor, activeTrackColor = trackColor),
            modifier = Modifier.weight(0.72f),
        )
    }
}

@Composable
private fun SettingsApiKeyField(
    label: String,
    helper: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val c = AppTheme.tokens.colors
    var visible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Hide key" else "Show key",
                        tint = c.textMuted,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(helper, color = c.textMuted, fontSize = scaledSp(11f))
    }
}
