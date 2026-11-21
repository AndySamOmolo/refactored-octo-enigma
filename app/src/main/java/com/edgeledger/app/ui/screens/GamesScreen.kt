package com.edgeledger.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.edgeledger.app.ui.components.formatLocalKickoff
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TeamAvatar
import com.edgeledger.app.ui.components.TokenPill
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.shortTeamName
import com.edgeledger.app.ui.components.tabularStyle
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GameSortMode(val label: String) {
    BEST_PICK("Sort: Best Pick %"),
    KICKOFF("Sort: Kickoff Time"),
    BEST_VALUE("Sort: Best Value"),
    CLOSEST("Sort: Closest"),
    POWER_GAP("Sort: Power Gap"),
}

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
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedSport by remember { mutableStateOf<Sport?>(null) }
    var strongOnly by remember { mutableStateOf(false) }
    var valueOnly by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(GameSortMode.BEST_PICK) }

    val daysOld = if (snapshot.lastSyncEpochMs <= 0L) 0L else ((Instant.now().toEpochMilli() - snapshot.lastSyncEpochMs) / 86_400_000L).coerceAtLeast(0L)
    val filtered = remember(snapshot.fixtures, snapshot.profiles, selectedSport, strongOnly, valueOnly, favoritesOnly, prefs.pinnedTeams, sortMode) {
        val base = snapshot.fixtures.filter { fx ->
            (selectedSport == null || fx.sport == selectedSport) &&
                (!strongOnly || fx.markets.topPicks.any { it.confidence == ConfidenceLevel.STRONG }) &&
                (!valueOnly || fx.bestValueLabel != null) &&
                (!favoritesOnly || fx.homeTeam in prefs.pinnedTeams || fx.awayTeam in prefs.pinnedTeams)
        }
        when (sortMode) {
            GameSortMode.BEST_PICK -> base.sortedByDescending { it.markets.topPicks.firstOrNull()?.chancePct ?: 0 }
            GameSortMode.KICKOFF -> base.sortedBy { it.kickoffIso }
            GameSortMode.BEST_VALUE -> base.sortedByDescending { it.bestValueEdgePct ?: -99.0 }
            GameSortMode.CLOSEST -> base.sortedBy { abs(it.markets.probHome - it.markets.probAway) }
            GameSortMode.POWER_GAP -> base.sortedByDescending { fx ->
                val hp = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]?.powerScore ?: 50
                val ap = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]?.powerScore ?: 50
                abs(hp - ap)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Offline Weekly Update Hero Header
        Surface(
            color = Color.Transparent,
            border = BorderStroke(1.dp, c.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val headerBg = if (tokens.useGradientHero) {
                Modifier.background(Brush.horizontalGradient(listOf(c.heroGradientStart, c.heroGradientEnd)))
            } else {
                Modifier.background(c.surface)
            }
            Column(modifier = headerBg.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.screenPadding, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when {
                                snapshot.fixtures.isEmpty() -> "Tap Update to Sync Live Datasets"
                                daysOld == 0L -> "Ready Offline · Updated Today"
                                else -> "Ready Offline · Updated ${daysOld}d ago"
                            },
                            color = c.win,
                            fontSize = scaledSp(13.5f),
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = if (syncing && syncText.isNotBlank()) syncText else "${snapshot.totalHistoricalMatches} past games analyzed on your phone",
                            color = c.textMuted,
                            fontSize = scaledSp(12f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { scope.launch { app.syncRepo.runOnDeviceWeeklySync() } },
                        enabled = !syncing,
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                        shape = tokens.shapes.button,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAccent)
                        } else {
                            Text("Update", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(12.5f))
                        }
                    }
                }
                if (syncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.5.dp), color = c.accent, trackColor = c.surfaceRaised)
                }
            }
        }

        // Sport + Saved Preset Filter Pills (EN-02)
        LazyRow(
            contentPadding = PaddingValues(horizontal = tokens.spacing.screenPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                TokenPill("All Sports", selectedSport == null && !favoritesOnly && !valueOnly && !strongOnly, {
                    selectedSport = null; favoritesOnly = false; valueOnly = false; strongOnly = false
                })
            }
            item {
                TokenPill("Favorites (${prefs.pinnedTeams.size})", favoritesOnly, { favoritesOnly = !favoritesOnly }, activeColor = c.draw, activeContainer = c.drawDim)
            }
            item {
                TokenPill("Good Value", valueOnly, { valueOnly = !valueOnly }, activeColor = c.valueHighlight, activeContainer = c.valueHighlightDim)
            }
            item {
                TokenPill("Strong Picks", strongOnly, { strongOnly = !strongOnly }, activeColor = c.win, activeContainer = c.winDim)
            }
            items(Sport.entries) { sp ->
                TokenPill(sp.label, selectedSport == sp, { selectedSport = if (selectedSport == sp) null else sp })
            }
        }

        // Sort Bar
        LazyRow(
            contentPadding = PaddingValues(horizontal = tokens.spacing.screenPadding, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(GameSortMode.entries) { sm ->
                TokenPill(
                    label = sm.label,
                    selected = sortMode == sm,
                    onClick = { sortMode = sm },
                    activeColor = c.sharpBadge,
                    activeContainer = c.sharpBadgeDim,
                )
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
                        Text("No matching games for this filter", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text(
                            if (favoritesOnly) "Tap the star icon on any game card to pin your favorite teams." else "Tap All Sports to see every upcoming game.",
                            color = c.textMuted,
                            fontSize = scaledSp(13f),
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { fx ->
                    val isPinned = fx.homeTeam in prefs.pinnedTeams || fx.awayTeam in prefs.pinnedTeams
                    if (tokens.fixtureLayout == FixtureLayoutMode.COMPACT_ROW) {
                        CompactFixtureRow(
                            fixture = fx,
                            isPinned = isPinned,
                            onTogglePin = {
                                if (fx.homeTeam in prefs.pinnedTeams) app.prefsRepo.togglePinTeam(fx.homeTeam)
                                else if (fx.awayTeam in prefs.pinnedTeams) app.prefsRepo.togglePinTeam(fx.awayTeam)
                                else app.prefsRepo.togglePinTeam(fx.homeTeam)
                            },
                            onClick = { onOpenMatch(fx.id) },
                        )
                    } else {
                        FixtureCard(
                            fixture = fx,
                            homePower = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]?.powerScore ?: 50,
                            awayPower = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]?.powerScore ?: 50,
                            homeForm = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]?.recentForm.orEmpty(),
                            awayForm = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]?.recentForm.orEmpty(),
                            isPinned = isPinned,
                            onTogglePin = {
                                if (fx.homeTeam in prefs.pinnedTeams) app.prefsRepo.togglePinTeam(fx.homeTeam)
                                else if (fx.awayTeam in prefs.pinnedTeams) app.prefsRepo.togglePinTeam(fx.awayTeam)
                                else app.prefsRepo.togglePinTeam(fx.homeTeam)
                            },
                            onClick = { onOpenMatch(fx.id) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun CompactFixtureRow(
    fixture: MatchFixture,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
) {
    val c = AppTheme.tokens.colors
    val hPct = (fixture.markets.probHome * 100).roundToInt()
    val aPct = (fixture.markets.probAway * 100).roundToInt()
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${fixture.leagueName} · ${formatLocalKickoff(fixture.kickoffIso)}", color = c.accent, fontSize = scaledSp(11f), fontWeight = FontWeight.Bold)
                Text("${fixture.homeTeam} vs ${fixture.awayTeam}", color = c.text, fontSize = scaledSp(14f), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fixture.playerProps.size} player props · Top: ${fixture.markets.topPicks.firstOrNull()?.label ?: "—"}", color = c.textMuted, fontSize = scaledSp(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$hPct% – $aPct%", style = tabularStyle(13.5f, FontWeight.ExtraBold, c.win))
                IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Pin favorite",
                        tint = if (isPinned) c.draw else c.textMuted,
                    )
                }
            }
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
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
) {
    val t = AppTheme.tokens
    val c = t.colors
    val topPick = fixture.markets.topPicks.firstOrNull()

    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${fixture.leagueName}${if (fixture.surface != null) " · ${fixture.surface}" else ""}",
                color = c.accent,
                fontSize = scaledSp(12f),
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = formatLocalKickoff(fixture.kickoffIso),
                    color = c.textMuted,
                    fontSize = scaledSp(11f),
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Pin favorite team",
                        tint = if (isPinned) c.draw else c.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamAvatar(fixture.homeTeam, size = 38.dp, logoUrl = fixture.homeLogoUrl)
                Column {
                    Text(fixture.homeTeam, color = c.text, fontSize = scaledSp(13.5f), fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Power $homePower/99", color = c.textMuted, fontSize = scaledSp(11f))
                }
            }
            Text("VS", color = c.textMuted, fontSize = scaledSp(11f), fontWeight = FontWeight.ExtraBold)
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                    Text(fixture.awayTeam, color = c.text, fontSize = scaledSp(13.5f), fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Power $awayPower/99", color = c.textMuted, fontSize = scaledSp(11f))
                }
                TeamAvatar(fixture.awayTeam, size = 38.dp, logoUrl = fixture.awayLogoUrl)
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
                Text("Last 5", color = c.textMuted, fontSize = scaledSp(11f), fontWeight = FontWeight.SemiBold)
                FormDots(awayForm, alignEnd = true)
            }
        }

        if (topPick != null) {
            Surface(
                color = c.surfaceRaised,
                shape = t.shapes.medium,
                border = BorderStroke(0.75.dp, c.border.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    Text(topPick.label, color = c.text, fontSize = scaledSp(14.5f), fontWeight = FontWeight.ExtraBold)
                    Text(topPick.plainReason, color = c.textSecondary, fontSize = scaledSp(12f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (fixture.bestValueLabel != null && fixture.bestValueEdgePct != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                StatusBadge(
                    text = "Good Value: ${fixture.bestValueLabel} (+${String.format("%.1f", fixture.bestValueEdgePct)}% edge)",
                    color = c.valueHighlight,
                    containerColor = c.valueHighlightDim,
                )
            }
        }
    }
}
