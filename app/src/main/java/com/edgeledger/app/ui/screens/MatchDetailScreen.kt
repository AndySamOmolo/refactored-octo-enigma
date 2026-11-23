package com.edgeledger.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.data.PersonalBetEntity
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.FeatureDriverRow
import com.edgeledger.app.ui.components.FormDots
import com.edgeledger.app.ui.components.LineMovementChart
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.PlayerPropCard
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatCompareBar
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TeamAvatar
import com.edgeledger.app.ui.components.formatLocalKickoff
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.shortTeamName
import com.edgeledger.app.ui.components.tabularStyle
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(
    app: EdgeLedgerApp,
    matchId: String,
    onBack: () -> Unit,
) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val fixture = remember(snapshot.fixtures, matchId) {
        snapshot.fixtures.find { it.id == matchId }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    if (fixture != null) "${shortTeamName(fixture.homeTeam)} vs ${shortTeamName(fixture.awayTeam)}" else "Match Breakdown",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = scaledSp(16.5f),
                    maxLines = 1,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = c.bg,
                titleContentColor = c.text,
                navigationIconContentColor = c.text,
            ),
        )

        if (fixture == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Game not found in local database", color = c.text, fontWeight = FontWeight.Bold)
            }
            return@Column
        }

        val pH = snapshot.profiles[PredictionEngine.profileKey(fixture.sport, fixture.homeTeam)]
        val pA = snapshot.profiles[PredictionEngine.profileKey(fixture.sport, fixture.awayTeam)]
        val m = fixture.markets
        val firstLine = fixture.bookmakerOdds.firstOrNull()
        val activeBankroll = if (prefs.isPaperTrading) prefs.paperBankroll else prefs.bankroll

        val defHomeOdds = firstLine?.homeOdds ?: (1.0 / (m.probHome.coerceAtLeast(0.05) * 1.045)).coerceIn(1.05, 25.0)
        val defDrawOdds = firstLine?.drawOdds ?: if (fixture.sport.hasDraw && m.probDraw > 0.03) (1.0 / (m.probDraw * 1.045)).coerceIn(1.50, 25.0) else 3.40
        val defAwayOdds = firstLine?.awayOdds ?: (1.0 / (m.probAway.coerceAtLeast(0.05) * 1.045)).coerceIn(1.05, 25.0)
        var homeOddsStr by rememberSaveable(fixture.id, firstLine?.homeOdds) { mutableStateOf(String.format(Locale.US, "%.2f", defHomeOdds)) }
        var drawOddsStr by rememberSaveable(fixture.id, firstLine?.drawOdds) { mutableStateOf(String.format(Locale.US, "%.2f", defDrawOdds)) }
        var awayOddsStr by rememberSaveable(fixture.id, firstLine?.awayOdds) { mutableStateOf(String.format(Locale.US, "%.2f", defAwayOdds)) }

        fun savePick(label: String, odds: Double, chance: Double, explicitStake: Double? = null) {
            val computed = explicitStake ?: OddsMath.recommendedStake(
                bankroll = activeBankroll,
                modelProb = chance,
                decimalOdds = odds,
                mode = prefs.stakingMode,
                kellyFraction = prefs.riskStyle.kellyFraction,
                maxCap = prefs.riskStyle.maxStakePct,
                flatUnitAmount = prefs.flatUnitAmount,
            )
            val fallbackUnit = (activeBankroll * 0.01).coerceIn(1.0, activeBankroll)
            val stake = (if (computed > 0.0) computed else fallbackUnit).roundToInt().coerceAtLeast(1).toDouble()
            app.appScope.launch {
                val nowMs = System.currentTimeMillis()
                app.database.dao().upsertBet(
                    PersonalBetEntity(
                        id = "bet-${UUID.randomUUID()}",
                        createdAtIso = Instant.ofEpochMilli(nowMs).toString(),
                        createdAtEpochMs = nowMs,
                        fixtureId = fixture.id,
                        sport = fixture.sport.label,
                        leagueName = fixture.leagueName,
                        matchup = "${fixture.homeTeam} vs ${fixture.awayTeam}",
                        selection = label,
                        decimalOdds = odds,
                        modelProb = chance,
                        stakeAmount = stake,
                        isPaper = prefs.isPaperTrading,
                    )
                )
            }
            val ledgerName = if (prefs.isPaperTrading) "Paper Picks" else "My Picks"
            scope.launch {
                snackbarHostState.showSnackbar("Saved \"$label\" ($${stake.toInt()}) to $ledgerName")
            }
        }

        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = tokens.padMd, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {


            item {
                SectionCard(useHeroGradient = true) {
                    Text(
                        "${fixture.leagueName}${if (!fixture.surface.isNullOrBlank()) " · ${fixture.surface} Court" else ""} · ${formatLocalKickoff(fixture.kickoffIso)}",
                        color = c.accent,
                        fontSize = scaledSp(12f),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            TeamAvatar(fixture.homeTeam, size = 48.dp, logoUrl = fixture.homeLogoUrl)
                            Text(fixture.homeTeam, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(13.5f), textAlign = TextAlign.Center)
                            Text("Power ${pH?.powerScore ?: 50}/99", color = c.textSecondary, fontSize = scaledSp(11f))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text("PROJECTED", color = c.textMuted, fontSize = scaledSp(10f), fontWeight = FontWeight.Bold)
                            Text(
                                text = when (fixture.sport) {
                                    Sport.FOOTBALL -> "${String.format(Locale.US, "%.1f", m.projectedHome)} – ${String.format(Locale.US, "%.1f", m.projectedAway)}"
                                    Sport.TENNIS -> "${(m.probHome * 100).roundToInt()}–${(m.probAway * 100).roundToInt()}"
                                    else -> m.projectedScoreDisplay.ifBlank { "VS" }
                                },
                                style = tabularStyle(20f, FontWeight.ExtraBold, c.text),
                            )
                            Text(
                                if (fixture.sport == Sport.FOOTBALL) "Goals" else if (fixture.sport == Sport.TENNIS) "Win %" else m.projectedSpreadDisplay,
                                color = c.win,
                                fontSize = scaledSp(11f),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            TeamAvatar(fixture.awayTeam, size = 48.dp, logoUrl = fixture.awayLogoUrl)
                            Text(fixture.awayTeam, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(13.5f), textAlign = TextAlign.Center)
                            Text("Power ${pA?.powerScore ?: 50}/99", color = c.textSecondary, fontSize = scaledSp(11f))
                        }
                    }
                    if (!pH?.recentForm.isNullOrEmpty() || !pA?.recentForm.isNullOrEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            FormDots(pH?.recentForm.orEmpty(), alignEnd = false)
                            Text("Last 5", color = c.textMuted, fontSize = scaledSp(11f), fontWeight = FontWeight.SemiBold)
                            FormDots(pA?.recentForm.orEmpty(), alignEnd = true)
                        }
                    }
                    ProbBar(
                        homeLabel = shortTeamName(fixture.homeTeam),
                        awayLabel = shortTeamName(fixture.awayTeam),
                        homeProb = m.probHome,
                        drawProb = if (fixture.sport.hasDraw) m.probDraw else null,
                        awayProb = m.probAway,
                        barHeight = 12.dp,
                    )
                    if (fixture.homeRangeLowPct > 0 && fixture.homeRangeHighPct > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "90% credible range (${shortTeamName(fixture.homeTeam)}): ${fixture.homeRangeLowPct}%–${fixture.homeRangeHighPct}%",
                                color = c.textMuted,
                                fontSize = scaledSp(11.5f),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (fixture.marketHoldPct > 0.0) {
                                StatusBadge("Vig ${String.format(Locale.US, "%.1f", fixture.marketHoldPct)}%", c.sharpBadge, c.sharpBadgeDim)
                            }
                        }
                    }
                }
            }

            fixture.weather?.let { w ->
                item {
                    SectionCard {
                        Text("Stadium Weather · ${w.stadium}", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14f))
                        Text("${w.tempC}°C · Wind ${w.windKmh} km/h · Rain ${w.rainChancePct}% — ${w.summary}", color = c.textSecondary, fontSize = scaledSp(13f))
                    }
                }
            }

            if (fixture.plainInsights.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("Why We Lean This Way", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        fixture.plainInsights.forEach { bullet ->
                            Text("• $bullet", color = c.textSecondary, fontSize = scaledSp(13f))
                        }
                    }
                }
            }

            if (fixture.featureDrivers.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("What's Driving This Prediction", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text("Signed impact from ${shortTeamName(fixture.homeTeam)}'s perspective.", color = c.textMuted, fontSize = scaledSp(12f))
                        fixture.featureDrivers.forEach { drv ->
                            FeatureDriverRow(drv)
                        }
                    }
                }
            }

            if (fixture.lineMovement.size >= 2) {
                item {
                    SectionCard {
                        Text("Line Movement & Steam Moves", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text("Recorded live bookmaker implied win probability for ${shortTeamName(fixture.homeTeam)}.", color = c.textMuted, fontSize = scaledSp(12f))
                        LineMovementChart(fixture.lineMovement)
                    }
                }
            }

            if (fixture.playerProps.isNotEmpty()) {
                item {
                    SectionCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Player Props (${fixture.playerProps.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                            StatusBadge("70% Range", c.accent, c.accentDim)
                        }
                        Text("Projected player stat lines from live synced rosters adjusted for match pace.", color = c.textMuted, fontSize = scaledSp(12f))
                        fixture.playerProps.forEach { prop ->
                            PlayerPropCard(
                                prop = prop,
                                onSave = {
                                    val fairOdds = 100.0 / max(15, prop.overChancePct)
                                    savePick("${prop.playerName} — ${prop.lineLabel}", fairOdds, prop.overChancePct / 100.0)
                                },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text("Top Picks Across All Markets", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    m.topPicks.forEach { pick ->
                        Surface(color = c.surfaceRaised, shape = RoundedCornerShape(tokens.cornerMd), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${pick.category} · ${pick.confidence.label} (${pick.chancePct}%)", color = c.win, fontSize = scaledSp(11f), fontWeight = FontWeight.Bold)
                                    Text(pick.label, color = c.text, fontSize = scaledSp(14f), fontWeight = FontWeight.ExtraBold)
                                    Text(pick.plainReason, color = c.textMuted, fontSize = scaledSp(12f))
                                }
                                Button(
                                    onClick = { savePick(pick.label, 100.0 / max(15, pick.chancePct), pick.chancePct / 100.0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                                    shape = tokens.shapes.small,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Save", color = c.onAccent, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text("Every Predicted Market", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    when (fixture.sport) {
                        Sport.FOOTBALL -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("${fixture.homeTeam} or Draw", "${m.doubleChanceHomeOrDrawPct}%", "Draw = Refund: ${m.drawNoBetHomePct}%", Modifier.weight(1f))
                                MetricTile("Home Win by 2+", "${m.homeWinBy2PlusPct}%", "Away +1.5: ${m.awayPlus15Pct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Over 2.5 Goals", "${m.over25Pct}%", "Over 1.5: ${m.over15Pct}% · O3.5: ${m.over35Pct}%", Modifier.weight(1f))
                                MetricTile("Both Teams Score", "${m.bttsYesPct}%", "1st Half Goal: ${m.firstHalfOver05Pct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Home Clean Sheet", "${m.homeCleanSheetPct}%", "${shortTeamName(fixture.homeTeam)} Win to Nil: ${m.homeWinToNilPct}%", Modifier.weight(1f))
                                MetricTile("Home Win & O1.5", "${m.homeWinAndOver15Pct}%", "${shortTeamName(fixture.homeTeam)} Win & BTTS: ${m.homeWinAndBttsPct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Projected Corners", String.format(Locale.US, "%.1f", m.projectedCorners), "O8.5: ${m.cornersOver85Pct}% · O9.5: ${m.cornersOver95Pct}%", Modifier.weight(1f))
                                MetricTile("Projected Cards", String.format(Locale.US, "%.1f", m.projectedCards), "O3.5: ${m.cardsOver35Pct}% · Red: ${m.redCardChancePct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Shots / On Target", "${m.projectedShotsTotal.roundToInt()} / ${String.format(Locale.US, "%.1f", m.projectedSotTotal)}", "Combined match volume", Modifier.weight(1f))
                                MetricTile("Projected Fouls", String.format(Locale.US, "%.1f", m.projectedFouls), "Over 21.5: ${m.foulsOver215Pct}%", Modifier.weight(1f))
                            }
                            Text("Most Likely Exact Scores", color = c.textMuted, fontSize = scaledSp(12f), fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                m.topScorelines.take(3).forEach { s ->
                                    MetricTile(s.score, "${s.chancePct}%", null, Modifier.weight(1f))
                                }
                            }
                            m.topHtFt.forEach { (label, pct) ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Half-Time / Full-Time: $label", color = c.textSecondary, fontSize = scaledSp(12f))
                                    Text("$pct%", style = tabularStyle(12.5f, FontWeight.Bold, c.accent))
                                }
                            }
                        }
                        Sport.TENNIS -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("1st Set Winner", "${m.firstSetHomePct}% / ${100 - m.firstSetHomePct}%", null, Modifier.weight(1f))
                                MetricTile("Straight vs 3 Sets", "${m.straightSetsPct}% / ${m.goesToThreeSetsPct}%", null, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile(m.gameHandicapLabel, "${m.gameHandicapPct}%", "Tiebreak: ${m.tiebreakChancePct}%", Modifier.weight(1f))
                                MetricTile("Total Games (${String.format(Locale.US, "%.1f", m.projectedTotalGames)})", "Over ${m.totalGamesLine}: ${m.totalGamesOverPct}%", "Aces: ${String.format(Locale.US, "%.1f", m.projectedAces)}", Modifier.weight(1f))
                            }
                        }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Projected Score", m.projectedScoreDisplay, "Halftime: ${m.halftimeDisplay}", Modifier.weight(1f))
                                MetricTile("Projected Spread", m.projectedSpreadDisplay, "${m.altSpreadLabel}: ${m.altSpreadPct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Projected Total", String.format(Locale.US, "%.1f", m.projectedTotal), "Over ${m.totalLine}: ${m.totalOverPct}%", Modifier.weight(1f))
                                MetricTile(m.closeGameLabel, "${m.closeGamePct}%", "Overtime: ${m.overtimeChancePct}%", Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (fixture.bookmakerOdds.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("Bookmaker Odds Comparison", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        fixture.bookmakerOdds.forEach { bk ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1.3f)) {
                                    Text(bk.bookmaker, color = c.text, fontSize = scaledSp(13f), fontWeight = FontWeight.Bold)
                                    Text(if (bk.isSharp) "Sharp Reference" else "Retail Book", color = if (bk.isSharp) c.win else c.textMuted, fontSize = scaledSp(11f))
                                }
                                Text(OddsMath.formatOdds(bk.homeOdds, prefs.oddsFormat), style = tabularStyle(13f, FontWeight.Bold, c.text), modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
                                if (fixture.sport.hasDraw) {
                                    Text(bk.drawOdds?.let { OddsMath.formatOdds(it, prefs.oddsFormat) } ?: "-", style = tabularStyle(13f, FontWeight.Medium, c.textSecondary), modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
                                }
                                Text(OddsMath.formatOdds(bk.awayOdds, prefs.oddsFormat), style = tabularStyle(13f, FontWeight.Bold, c.text), modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }

            if (pH != null && pA != null) {
                item {
                    SectionCard {
                        Text("Team Stats Comparison", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        StatCompareBar("Win Rate", pH.winPct, pA.winPct, "%", 0)
                        StatCompareBar("Scored / Game", pH.avgScored, pA.avgScored)
                        StatCompareBar("Allowed / Gm (Lower = Better)", pH.avgConceded, pA.avgConceded, higherIsBetter = false)
                        if (fixture.sport == Sport.FOOTBALL) {
                            StatCompareBar("Shots on Target", pH.shotsOnTargetPerGame, pA.shotsOnTargetPerGame)
                            StatCompareBar("Corners / Game", pH.cornersPerGame, pA.cornersPerGame)
                            StatCompareBar("Clean Sheets", pH.cleanSheetPct, pA.cleanSheetPct, "%", 0)
                        }
                    }
                }
            }

            item {
                val hOdds = max(1.02, homeOddsStr.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 2.0)
                val dOdds = if (fixture.sport.hasDraw) max(1.02, drawOddsStr.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 3.4) else null
                val aOdds = max(1.02, awayOddsStr.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 2.0)
                val evals = OddsMath.evaluateSides(
                    homeLabel = fixture.homeTeam,
                    awayLabel = fixture.awayTeam,
                    probHome = m.probHome,
                    probDraw = m.probDraw,
                    probAway = m.probAway,
                    oddsHome = hOdds,
                    oddsDraw = dOdds,
                    oddsAway = aOdds,
                    kellyFraction = prefs.riskStyle.kellyFraction,
                    maxStakePct = prefs.riskStyle.maxStakePct,
                    minEvThreshold = prefs.minEvPct / 100.0,
                    minProbFloor = prefs.minProbFloorPct / 100.0,
                    devigMethod = prefs.devigMethod,
                )

                SectionCard {
                    Text("Check Your Bookie's Odds (Offline)", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = homeOddsStr,
                            onValueChange = { homeOddsStr = it },
                            label = { Text("Home") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        if (fixture.sport.hasDraw) {
                            OutlinedTextField(
                                value = drawOddsStr,
                                onValueChange = { drawOddsStr = it },
                                label = { Text("Draw") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = awayOddsStr,
                            onValueChange = { awayOddsStr = it },
                            label = { Text("Away") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    evals.forEach { ev ->
                        val recStake = if (ev.isGoodValue) {
                            OddsMath.recommendedStake(
                                bankroll = activeBankroll,
                                modelProb = ev.modelProb,
                                decimalOdds = ev.decimalOdds,
                                mode = prefs.stakingMode,
                                kellyFraction = prefs.riskStyle.kellyFraction,
                                maxCap = prefs.riskStyle.maxStakePct,
                flatUnitAmount = prefs.flatUnitAmount,
                            )
                        } else 0.0
                        val dollars = recStake.roundToInt()
                        Surface(
                            color = if (ev.isGoodValue) c.winDim else c.surfaceRaised,
                            border = BorderStroke(1.dp, if (ev.isGoodValue) c.win else c.border),
                            shape = RoundedCornerShape(tokens.cornerMd),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${ev.label} @ ${OddsMath.formatOdds(ev.decimalOdds, prefs.oddsFormat)}", color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                    Text("Our Chance ${(ev.modelProb * 100).roundToInt()}% · Fair ${(ev.fairMarketProb * 100).roundToInt()}%", color = c.textSecondary, fontSize = scaledSp(11.5f))
                                }
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                                    Text(if (ev.isGoodValue) "Good Value (+${(ev.expectedValue * 100).roundToInt()}%)" else "Skip", color = if (ev.isGoodValue) c.win else c.textMuted, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(12f))
                                    Text(if (ev.isGoodValue && dollars > 0) "Suggested $$dollars" else "No edge", color = c.textSecondary, fontSize = scaledSp(11f))
                                }
                                Button(
                                    onClick = { savePick(ev.label, ev.decimalOdds, ev.modelProb, explicitStake = if (recStake > 0.0) recStake else null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("Save", color = c.onAccent, fontSize = scaledSp(11f), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Text("For personal analytics & informational use only.", color = c.textMuted, fontSize = scaledSp(11f))
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}
