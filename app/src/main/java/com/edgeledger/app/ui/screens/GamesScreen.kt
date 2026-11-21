package com.edgeledger.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.model.ConfidenceLevel
import com.edgeledger.app.model.MatchFixture
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.FormDots
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.TeamAvatar
import kotlinx.coroutines.launch
import java.time.Instant

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
    val scope = rememberCoroutineScope()

    var selectedSport by remember { mutableStateOf<Sport?>(null) }
    var strongOnly by remember { mutableStateOf(false) }

    val daysOld = ((Instant.now().toEpochMilli() - snapshot.lastSyncEpochMs) / 86_400_000L).coerceAtLeast(0L)
    val filtered = remember(snapshot.fixtures, selectedSport, strongOnly) {
        snapshot.fixtures.filter { fx ->
            (selectedSport == null || fx.sport == selectedSport) &&
                (!strongOnly || fx.markets.topPicks.any { it.confidence == ConfidenceLevel.STRONG } || fx.bestValueLabel != null)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Offline Weekly Update Header
        Surface(
            color = c.surface,
            border = BorderStroke(1.dp, c.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.padMd, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            snapshot.isSeedData -> "Ready Offline · Starter Data Loaded"
                            daysOld == 0L -> "Ready Offline · Updated Today"
                            else -> "Ready Offline · Updated ${daysOld}d ago"
                        },
                        color = c.win,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (syncing && syncText.isNotBlank()) syncText else "${snapshot.totalHistoricalMatches} past games analyzed on your phone",
                        color = c.textMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { scope.launch { app.syncRepo.runOnDeviceWeeklySync() } },
                    enabled = !syncing,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                    shape = RoundedCornerShape(tokens.cornerSm),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Update", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Sport Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = tokens.padMd, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedSport == null,
                    onClick = { selectedSport = null },
                    label = { Text("All Sports", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = c.accentDim,
                        selectedLabelColor = c.accent,
                    ),
                )
            }
            items(Sport.entries) { sp ->
                FilterChip(
                    selected = selectedSport == sp,
                    onClick = { selectedSport = if (selectedSport == sp) null else sp },
                    label = { Text(sp.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = c.accentDim,
                        selectedLabelColor = c.accent,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = strongOnly,
                    onClick = { strongOnly = !strongOnly },
                    label = { Text("Strong Picks", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = c.winDim,
                        selectedLabelColor = c.win,
                    ),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = tokens.padMd, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filtered, key = { it.id }) { fx ->
                FixtureCard(
                    fixture = fx,
                    homePower = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]?.powerScore ?: 55,
                    awayPower = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]?.powerScore ?: 55,
                    homeForm = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.homeTeam)]?.recentForm.orEmpty(),
                    awayForm = snapshot.profiles[PredictionEngine.profileKey(fx.sport, fx.awayTeam)]?.recentForm.orEmpty(),
                    onClick = { onOpenMatch(fx.id) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
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
    onClick: () -> Unit,
) {
    val t = AppTheme.tokens
    val c = t.colors
    val topPick = fixture.markets.topPicks.firstOrNull()

    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${fixture.leagueName}${if (fixture.surface != null) " · ${fixture.surface}" else ""}",
                color = c.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = fixture.kickoffIso.take(16).replace("T", " · "),
                color = c.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamAvatar(fixture.homeTeam, size = 36.dp)
                Column {
                    Text(fixture.homeTeam, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Power $homePower/99", color = c.textMuted, fontSize = 11.sp)
                }
            }
            Text("VS", color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                    Text(fixture.awayTeam, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Power $awayPower/99", color = c.textMuted, fontSize = 11.sp)
                }
                TeamAvatar(fixture.awayTeam, size = 36.dp)
            }
        }

        ProbBar(
            homeLabel = fixture.homeTeam.substringBefore(" "),
            awayLabel = fixture.awayTeam.substringBefore(" "),
            homeProb = fixture.markets.probHome,
            drawProb = if (fixture.sport.hasDraw) fixture.markets.probDraw else null,
            awayProb = fixture.markets.probAway,
        )

        if (homeForm.isNotEmpty() || awayForm.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FormDots(homeForm)
                Text("Last 5", color = c.textMuted, fontSize = 11.sp)
                FormDots(awayForm)
            }
        }

        if (topPick != null) {
            Surface(
                color = c.surfaceRaised,
                shape = RoundedCornerShape(t.cornerMd),
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val badgeColor = when (topPick.confidence) {
                            ConfidenceLevel.STRONG -> c.win
                            ConfidenceLevel.GOOD -> c.accent
                            ConfidenceLevel.LEAN -> c.draw
                        }
                        Text("${topPick.confidence.label} Pick · ${topPick.chancePct}%", color = badgeColor, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                        Text("All Markets →", color = c.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(topPick.label, color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold)
                    Text(topPick.plainReason, color = c.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
