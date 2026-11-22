package com.edgeledger.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.model.ConfidenceLevel
import com.edgeledger.app.model.MatchFixture
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.theme.FixtureLayoutMode
import com.edgeledger.app.ui.components.FormDots
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TeamAvatar
import com.edgeledger.app.ui.components.TokenPill
import com.edgeledger.app.ui.components.formatLocalKickoff
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.shortTeamName
import com.edgeledger.app.ui.components.tabularStyle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class GameSortMode(val shortLabel: String, val fullLabel: String, val subtitle: String) {
    BEST_PICK("Best Pick %", "Best Pick %", "Highest model win probability first"),
    KICKOFF("Kickoff", "Kickoff Time", "Soonest upcoming games first"),
    BEST_VALUE("Best Value", "Best Value Edge", "Largest positive EV edge vs bookmakers"),
    BTTS_HIGH("BTTS %", "Both Teams to Score (BTTS)", "Highest probability both teams score (Football)"),
    OVER_25_HIGH("Over 2.5 / High", "Over 2.5 Goals / High Scoring", "Highest Over 2.5 % or projected total score"),
    UNDER_25_HIGH("Under 2.5 / Low", "Under 2.5 Goals / Low Scoring", "Highest Under 2.5 % or lowest projected total"),
    DRAW_HIGH("Draw %", "Draw Probability", "Highest draw likelihood (3-way sports)"),
    CLOSEST("Closest", "Closest Matchup", "Tightest odds & highest upset potential"),
    POWER_GAP("Mismatch", "Biggest Mismatch", "Largest team power rating gap"),
}

internal object GamesFilterSort {
    fun sortFixtures(
        fixtures: List<MatchFixture>,
        sortMode: GameSortMode,
        powerLookup: (MatchFixture) -> Pair<Int, Int>,
    ): List<MatchFixture> = when (sortMode) {
        GameSortMode.BEST_PICK -> fixtures.sortedByDescending { it.markets.topPicks.firstOrNull()?.chancePct ?: 0 }
        GameSortMode.KICKOFF -> fixtures.sortedBy { it.kickoffIso }
        GameSortMode.BEST_VALUE -> fixtures.sortedByDescending { it.bestValueEdgePct ?: Double.NEGATIVE_INFINITY }
        GameSortMode.BTTS_HIGH -> fixtures.sortedWith(
            compareByDescending<MatchFixture> { it.sport == Sport.FOOTBALL }
                .thenByDescending { it.markets.bttsYesPct }
                .thenByDescending { it.markets.over25Pct }
        )
        GameSortMode.OVER_25_HIGH -> fixtures.sortedWith(
            compareByDescending<MatchFixture> {
                if (it.sport == Sport.FOOTBALL) it.markets.over25Pct.toDouble()
                else it.markets.projectedHome + it.markets.projectedAway
            }.thenByDescending { it.markets.bttsYesPct }
        )
        GameSortMode.UNDER_25_HIGH -> fixtures.sortedWith(
            compareByDescending<MatchFixture> {
                if (it.sport == Sport.FOOTBALL) (100 - it.markets.over25Pct).toDouble()
                else -(it.markets.projectedHome + it.markets.projectedAway)
            }
        )
        GameSortMode.DRAW_HIGH -> fixtures.sortedWith(
            compareByDescending<MatchFixture> { it.sport.hasDraw }
                .thenByDescending { it.markets.probDraw }
        )
        GameSortMode.CLOSEST -> fixtures.sortedBy { abs(it.markets.probHome - it.markets.probAway) }
        GameSortMode.POWER_GAP -> fixtures.sortedByDescending { fx ->
            val (hp, ap) = powerLookup(fx)
            abs(hp - ap)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GamesScreen(
    app: EdgeLedgerApp,
    onOpenMatch: (String) -> Unit,
) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val syncing by app.syncRepo.syncing.collectAsStateWithLifecycle()
    val syncText by app.syncRepo.syncStatusText.collectAsStateWithLifecycle()
    val syncError by app.syncRepo.syncError.collectAsStateWithLifecycle()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()

    var selectedSportName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSport = selectedSportName?.let { runCatching { Sport.valueOf(it) }.getOrNull() }
    var selectedLeague by rememberSaveable { mutableStateOf<String?>(null) }
    var strongOnly by rememberSaveable { mutableStateOf(false) }
    var valueOnly by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var highBttsOnly by rememberSaveable { mutableStateOf(false) }
    var highOver25Only by rememberSaveable { mutableStateOf(false) }
    var highUnder25Only by rememberSaveable { mutableStateOf(false) }
    var highDrawOnly by rememberSaveable { mutableStateOf(false) }
    var liveOddsOnly by rememberSaveable { mutableStateOf(false) }
    var todayOnly by rememberSaveable { mutableStateOf(false) }
    var sortModeName by rememberSaveable { mutableStateOf(GameSortMode.BEST_PICK.name) }
    val sortMode = runCatching { GameSortMode.valueOf(sortModeName) }.getOrDefault(GameSortMode.BEST_PICK)

    var showSportSheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    val daysOld = if (snapshot.lastSyncEpochMs <= 0L) 0L else ((Instant.now().toEpochMilli() - snapshot.lastSyncEpochMs) / 86_400_000L).coerceAtLeast(0L)
    val todayLocal = remember { LocalDate.now(ZoneId.systemDefault()) }

    val sportCounts = remember(snapshot.fixtures) {
        snapshot.fixtures.groupingBy { it.sport }.eachCount()
    }
    val orderedSports = remember(sportCounts) {
        Sport.entries.sortedWith(
            compareByDescending<Sport> { (sportCounts[it] ?: 0) > 0 }
                .thenByDescending { sportCounts[it] ?: 0 }
                .thenBy { it.ordinal }
        )
    }
    val availableLeagues = remember(snapshot.fixtures, selectedSport) {
        snapshot.fixtures
            .filter { selectedSport == null || it.sport == selectedSport }
            .map { it.leagueName }
            .distinct()
            .sorted()
    }

    val activeFilterCount = listOf(
        favoritesOnly, strongOnly, valueOnly, highBttsOnly,
        highOver25Only, highUnder25Only, highDrawOnly, liveOddsOnly, todayOnly,
        selectedLeague != null,
    ).count { it }

    val filtered = remember(
        snapshot.fixtures, snapshot.profiles, selectedSport, selectedLeague,
        strongOnly, valueOnly, favoritesOnly, highBttsOnly, highOver25Only,
        highUnder25Only, highDrawOnly, liveOddsOnly, todayOnly, prefs.pinnedTeams, sortMode,
    ) {
        val base = snapshot.fixtures.filter { fx ->
            val matchesSport = selectedSport == null || fx.sport == selectedSport
            val matchesLeague = selectedLeague == null || fx.leagueName == selectedLeague
            val matchesStrong = !strongOnly || fx.markets.topPicks.any { it.confidence == ConfidenceLevel.STRONG }
            val matchesValue = !valueOnly || fx.bestValueLabel != null
            val matchesFav = !favoritesOnly || fx.homeTeam in prefs.pinnedTeams || fx.awayTeam in prefs.pinnedTeams
            val matchesBtts = !highBttsOnly || (fx.sport == Sport.FOOTBALL && fx.markets.bttsYesPct >= 58)
            val matchesOver25 = !highOver25Only || (fx.sport == Sport.FOOTBALL && fx.markets.over25Pct >= 58)
            val matchesUnder25 = !highUnder25Only || (fx.sport == Sport.FOOTBALL && (100 - fx.markets.over25Pct) >= 58)
            val matchesDraw = !highDrawOnly || (fx.sport.hasDraw && (fx.markets.probDraw * 100.0) >= 28.0)
            val matchesLiveOdds = !liveOddsOnly || fx.bookmakerOdds.isNotEmpty()
            val matchesToday = !todayOnly || runCatching {
                val inst = runCatching { OffsetDateTime.parse(fx.kickoffIso).toInstant() }
                    .getOrElse { Instant.parse(fx.kickoffIso) }
                inst.atZone(ZoneId.systemDefault()).toLocalDate() == todayLocal
            }.getOrDefault(false)

            matchesSport && matchesLeague && matchesStrong && matchesValue && matchesFav &&
                matchesBtts && matchesOver25 && matchesUnder25 && matchesDraw && matchesLiveOdds && matchesToday
        }
        GamesFilterSort.sortFixtures(base, sortMode) { fx ->
            val hp = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]?.powerScore ?: 50
            val ap = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]?.powerScore ?: 50
            hp to ap
        }
    }

    fun clearAllFilters() {
        selectedSportName = null
        selectedLeague = null
        favoritesOnly = false
        strongOnly = false
        valueOnly = false
        highBttsOnly = false
        highOver25Only = false
        highUnder25Only = false
        highDrawOnly = false
        liveOddsOnly = false
        todayOnly = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact single-line status bar (replaces bloated top banner)
        Surface(color = c.surface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(horizontal = tokens.spacing.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (syncing) c.accent else if (snapshot.fixtures.isNotEmpty()) c.win else c.draw),
                    )
                    Text(
                        text = when {
                            syncing && syncText.isNotBlank() -> syncText
                            snapshot.fixtures.isEmpty() || snapshot.lastSyncEpochMs <= 0L -> "Tap Update to sync live games"
                            daysOld == 0L -> "Ready · Today · ${snapshot.totalHistoricalMatches} past games"
                            else -> "Ready · ${daysOld}d ago · ${snapshot.totalHistoricalMatches} past games"
                        },
                        color = if (syncing) c.accent else c.textSecondary,
                        fontSize = scaledSp(12f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        modifier = Modifier
                            .height(30.dp)
                            .clickable(enabled = !syncing) {
                                app.appScope.launch { app.syncRepo.runOnDeviceWeeklySync() }
                            },
                        shape = tokens.shapes.small,
                        color = if (syncing) c.surfaceRaised else c.accent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp, color = c.accent)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Update", tint = c.onAccent, modifier = Modifier.size(14.dp))
                                Text("Update", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(11.5f))
                            }
                        }
                    }
                }
                if (syncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.surfaceRaised)
                } else {
                    HorizontalDivider(thickness = 0.75.dp, color = c.border)
                }
            }
        }

        if (!syncError.isNullOrBlank()) {
            Surface(
                color = c.lossDim,
                border = BorderStroke(1.dp, c.loss.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.screenPadding, vertical = 6.dp),
                shape = tokens.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(syncError.orEmpty(), color = c.loss, fontSize = scaledSp(11.5f), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { app.syncRepo.clearSyncError() }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss sync error", tint = c.loss, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Clean zero-scrollbar 3-menu toolbar (Sport | Filter | Sort)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.screenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuTriggerChip(
                icon = Icons.Rounded.SportsSoccer,
                label = selectedSport?.label ?: "All (${snapshot.fixtures.size})",
                active = selectedSport != null,
                onClick = { showSportSheet = true },
                modifier = Modifier.weight(1f),
            )
            MenuTriggerChip(
                icon = Icons.Rounded.FilterList,
                label = if (activeFilterCount > 0) "Filter ($activeFilterCount)" else "Filter",
                active = activeFilterCount > 0,
                onClick = { showFilterSheet = true },
                modifier = Modifier.weight(1f),
            )
            MenuTriggerChip(
                icon = Icons.AutoMirrored.Rounded.Sort,
                label = sortMode.shortLabel,
                active = sortMode != GameSortMode.BEST_PICK,
                onClick = { showSortSheet = true },
                modifier = Modifier.weight(1f),
            )
        }

        // Compact active-filter summary strip when any filter/sport is active
        if (selectedSport != null || activeFilterCount > 0 || sortMode != GameSortMode.BEST_PICK) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.screenPadding)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${filtered.size} of ${snapshot.fixtures.size} games · ${sortMode.fullLabel}",
                    color = c.textMuted,
                    fontSize = scaledSp(11.5f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectedSport != null || activeFilterCount > 0) {
                    Text(
                        text = "Reset",
                        color = c.accent,
                        fontSize = scaledSp(11.5f),
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clickable { clearAllFilters() }
                            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = tokens.spacing.screenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
        ) {
            if (filtered.isEmpty()) {
                item {
                    SectionCard {
                        val hasAnyFixtures = snapshot.fixtures.isNotEmpty()
                        Text(
                            if (hasAnyFixtures) "No games match these filters" else "No live upcoming fixtures loaded yet",
                            color = c.text,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = scaledSp(14.5f),
                        )
                        Text(
                            if (hasAnyFixtures) {
                                "Try resetting your active filters or switching to another sport."
                            } else {
                                "Tap Update above to sync live upcoming schedules and ratings across Football, NFL, NBA, NHL, MLB, Tennis, Cricket, MMA & F1."
                            },
                            color = c.textMuted,
                            fontSize = scaledSp(12f),
                        )
                        if (hasAnyFixtures) {
                            Button(
                                onClick = { clearAllFilters() },
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                shape = tokens.shapes.button,
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            ) {
                                Text("Reset Filters", fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(12f))
                            }
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { fx ->
                    val hp = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]
                    val ap = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]
                    val isPinned = fx.homeTeam in prefs.pinnedTeams || fx.awayTeam in prefs.pinnedTeams
                    FixtureCard(
                        fixture = fx,
                        homePower = hp?.powerScore ?: 50,
                        awayPower = ap?.powerScore ?: 50,
                        homeForm = hp?.recentForm ?: emptyList(),
                        awayForm = ap?.recentForm ?: emptyList(),
                        isPinned = isPinned,
                        layoutMode = tokens.config.fixtureLayout,
                        highlightMarketMode = sortMode,
                        onTogglePin = { app.prefsRepo.togglePinTeam(fx.homeTeam) },
                        onClick = { onOpenMatch(fx.id) },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Sport Selection Bottom Sheet
    if (showSportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSportSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Select Sport", color = c.text, fontSize = scaledSp(16f), fontWeight = FontWeight.ExtraBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TokenPill(
                        label = "All Sports (${snapshot.fixtures.size})",
                        selected = selectedSport == null,
                        onClick = {
                            selectedSportName = null
                            selectedLeague = null
                            showSportSheet = false
                        },
                    )
                    orderedSports.forEach { sp ->
                        val cnt = sportCounts[sp] ?: 0
                        TokenPill(
                            label = "${sp.label} ($cnt)",
                            selected = selectedSport == sp,
                            onClick = {
                                selectedSportName = sp.name
                                selectedLeague = null
                                showSportSheet = false
                            },
                        )
                    }
                }
            }
        }
    }

    // Filter Bottom Sheet (Quick Filters + Football/Goals/BTTS + League)
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Filter Games", color = c.text, fontSize = scaledSp(16f), fontWeight = FontWeight.ExtraBold)
                    if (activeFilterCount > 0) {
                        Text(
                            "Clear All",
                            color = c.accent,
                            fontSize = scaledSp(12.5f),
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clickable { clearAllFilters() },
                        )
                    }
                }

                Text("Quick & Market Filters", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TokenPill("★ Favorites (${prefs.pinnedTeams.size})", favoritesOnly, { favoritesOnly = !favoritesOnly }, activeColor = c.draw, activeContainer = c.drawDim)
                    TokenPill("Strong Picks (≥65%)", strongOnly, { strongOnly = !strongOnly }, activeColor = c.win, activeContainer = c.winDim)
                    TokenPill("Good Value (+EV)", valueOnly, { valueOnly = !valueOnly }, activeColor = c.valueHighlight, activeContainer = c.valueHighlightDim)
                    TokenPill("High BTTS (≥58%)", highBttsOnly, { highBttsOnly = !highBttsOnly }, activeColor = c.accent, activeContainer = c.accentDim)
                    TokenPill("Over 2.5 Goals (≥58%)", highOver25Only, { highOver25Only = !highOver25Only }, activeColor = c.accent, activeContainer = c.accentDim)
                    TokenPill("Under 2.5 Goals (≥58%)", highUnder25Only, { highUnder25Only = !highUnder25Only }, activeColor = c.accent, activeContainer = c.accentDim)
                    TokenPill("High Draw (≥28%)", highDrawOnly, { highDrawOnly = !highDrawOnly }, activeColor = c.draw, activeContainer = c.drawDim)
                    TokenPill("Live Odds Only", liveOddsOnly, { liveOddsOnly = !liveOddsOnly }, activeColor = c.sharpBadge, activeContainer = c.sharpBadgeDim)
                    TokenPill("Today Only", todayOnly, { todayOnly = !todayOnly }, activeColor = c.win, activeContainer = c.winDim)
                }

                if (availableLeagues.size > 1) {
                    Text("Competition / League", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TokenPill("All Leagues", selectedLeague == null, { selectedLeague = null })
                        availableLeagues.take(16).forEach { lg ->
                            TokenPill(lg, selectedLeague == lg, { selectedLeague = if (selectedLeague == lg) null else lg })
                        }
                    }
                }

                Button(
                    onClick = { showFilterSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    shape = tokens.shapes.button,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                ) {
                    Text("Show ${filtered.size} Matching Games", fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(13f))
                }
            }
        }
    }

    // Sort Bottom Sheet
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text("Sort Games By", color = c.text, fontSize = scaledSp(16f), fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 6.dp))
                }
                items(GameSortMode.entries, key = { it.name }) { mode ->
                    val isSelected = sortMode == mode
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sortModeName = mode.name
                                showSortSheet = false
                            },
                        shape = tokens.shapes.medium,
                        color = if (isSelected) c.accentDim else c.surfaceRaised,
                        border = BorderStroke(if (isSelected) 1.25.dp else 0.75.dp, if (isSelected) c.accent else c.border),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(mode.fullLabel, color = if (isSelected) c.accent else c.text, fontSize = scaledSp(13.5f), fontWeight = FontWeight.ExtraBold)
                                Text(mode.subtitle, color = c.textMuted, fontSize = scaledSp(11.5f))
                            }
                            if (isSelected) {
                                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTriggerChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = AppTheme.tokens
    val c = t.colors
    Surface(
        modifier = modifier
            .height(36.dp)
            .clickable(onClick = onClick),
        shape = t.shapes.small,
        color = if (active) c.accentDim else c.surface,
        border = BorderStroke(if (active) 1.25.dp else 0.9.dp, if (active) c.accent else c.border),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) c.accent else c.textSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = if (active) c.accent else c.text,
                fontSize = scaledSp(11.5f),
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = if (active) c.accent else c.textMuted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun FixtureCard(
    fixture: MatchFixture,
    homePower: Int,
    awayPower: Int,
    homeForm: List<String>,
    awayForm: List<String>,
    isPinned: Boolean,
    layoutMode: FixtureLayoutMode,
    highlightMarketMode: GameSortMode,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
) {
    val t = AppTheme.tokens
    val c = t.colors
    val topPick = fixture.markets.topPicks.firstOrNull()

    if (layoutMode == FixtureLayoutMode.COMPACT_ROW) {
        SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${fixture.leagueName} · ${formatLocalKickoff(fixture.kickoffIso)}", color = c.accent, fontSize = scaledSp(11f), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (topPick != null) StatusBadge("${topPick.chancePct}%", c.win, c.winDim)
            }
            Text("${fixture.homeTeam} vs ${fixture.awayTeam}", color = c.text, fontSize = scaledSp(14f), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProbBar(
                homeLabel = shortTeamName(fixture.homeTeam),
                awayLabel = shortTeamName(fixture.awayTeam),
                homeProb = fixture.markets.probHome,
                drawProb = if (fixture.sport.hasDraw) fixture.markets.probDraw else null,
                awayProb = fixture.markets.probAway,
                barHeight = 8.dp,
            )
            if (topPick != null) {
                Text("Best Pick: ${topPick.label}", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }

    // STANDARD CARD — strictly symmetric 2-column team header + compact market strip
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${fixture.leagueName}${if (!fixture.surface.isNullOrBlank()) " · ${fixture.surface}" else ""}",
                color = c.accent,
                fontSize = scaledSp(11.5f),
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatLocalKickoff(fixture.kickoffIso),
                    color = c.textMuted,
                    fontSize = scaledSp(11f),
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isPinned) "Unpin favorite team" else "Pin favorite team",
                        tint = if (isPinned) c.draw else c.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Strictly balanced 2-column team row (fixed 2-line text alignment, uniform 36dp crests)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TeamAvatar(fixture.homeTeam, size = 36.dp, logoUrl = fixture.homeLogoUrl)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = fixture.homeTeam,
                        color = c.text,
                        fontSize = scaledSp(13.5f),
                        fontWeight = FontWeight.ExtraBold,
                        minLines = 2,
                        maxLines = 2,
                        lineHeight = scaledSp(16.5f),
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Power $homePower/99", color = c.textMuted, fontSize = scaledSp(10.5f), maxLines = 1)
                }
            }
            Text(
                text = "VS",
                color = c.textMuted,
                fontSize = scaledSp(10.5f),
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(24.dp),
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = fixture.awayTeam,
                        color = c.text,
                        fontSize = scaledSp(13.5f),
                        fontWeight = FontWeight.ExtraBold,
                        minLines = 2,
                        maxLines = 2,
                        lineHeight = scaledSp(16.5f),
                        textAlign = TextAlign.End,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Power $awayPower/99", color = c.textMuted, fontSize = scaledSp(10.5f), textAlign = TextAlign.End, maxLines = 1)
                }
                TeamAvatar(fixture.awayTeam, size = 36.dp, logoUrl = fixture.awayLogoUrl)
            }
        }

        ProbBar(
            homeLabel = shortTeamName(fixture.homeTeam),
            awayLabel = shortTeamName(fixture.awayTeam),
            homeProb = fixture.markets.probHome,
            drawProb = if (fixture.sport.hasDraw) fixture.markets.probDraw else null,
            awayProb = fixture.markets.probAway,
        )

        if (homeForm.isNotEmpty() || awayForm.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormDots(homeForm, alignEnd = false)
                Text("Last 5", color = c.textMuted, fontSize = scaledSp(10.5f), fontWeight = FontWeight.SemiBold)
                FormDots(awayForm, alignEnd = true)
            }
        }

        // Compact football goals / BTTS quick-glance strip
        if (fixture.sport == Sport.FOOTBALL) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val bttsHi = highlightMarketMode == GameSortMode.BTTS_HIGH
                val o25Hi = highlightMarketMode == GameSortMode.OVER_25_HIGH
                val u25Hi = highlightMarketMode == GameSortMode.UNDER_25_HIGH
                StatusBadge(
                    text = "BTTS ${fixture.markets.bttsYesPct}%",
                    color = if (bttsHi) c.accent else c.textSecondary,
                    containerColor = if (bttsHi) c.accentDim else c.surfaceRaised,
                )
                StatusBadge(
                    text = "O2.5 ${fixture.markets.over25Pct}%",
                    color = if (o25Hi) c.accent else c.textSecondary,
                    containerColor = if (o25Hi) c.accentDim else c.surfaceRaised,
                )
                StatusBadge(
                    text = "U2.5 ${100 - fixture.markets.over25Pct}%",
                    color = if (u25Hi) c.accent else c.textSecondary,
                    containerColor = if (u25Hi) c.accentDim else c.surfaceRaised,
                )
            }
        }

        if (topPick != null) {
            Surface(
                color = c.surfaceRaised,
                shape = t.shapes.medium,
                border = BorderStroke(t.borderWidthDp.coerceAtLeast(0.75.dp), c.border.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge("${topPick.confidence.label} Pick · ${topPick.chancePct}%", c.win, c.winDim)
                        Text(
                            if (fixture.playerProps.isNotEmpty()) "${fixture.playerProps.size} Props & Markets →" else "All Markets →",
                            color = c.accent,
                            fontSize = scaledSp(11.5f),
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Text(topPick.label, color = c.text, fontSize = scaledSp(14f), fontWeight = FontWeight.ExtraBold)
                    Text(topPick.plainReason, color = c.textSecondary, fontSize = scaledSp(12f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (fixture.bestValueLabel != null && fixture.bestValueEdgePct != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                StatusBadge(
                    text = "Good Value: ${fixture.bestValueLabel} (+${String.format(Locale.US, "%.1f", fixture.bestValueEdgePct)}% edge)",
                    color = c.valueHighlight,
                    containerColor = c.valueHighlightDim,
                )
            }
        }
    }
}
