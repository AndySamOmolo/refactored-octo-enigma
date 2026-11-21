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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.edgeledger.app.ui.components.PlayerPropCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatCompareBar
import com.edgeledger.app.ui.components.TeamAvatar
import com.edgeledger.app.ui.components.shortTeamName
import com.edgeledger.app.ui.components.tabularStyle
import kotlinx.coroutines.launch
import java.time.Instant
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

    var savedBanner by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Match Breakdown", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) },
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
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Game not found in local database", color = c.text, fontWeight = FontWeight.Bold)
            }
            return@Column
        }

        val pH = snapshot.profiles[PredictionEngine.profileKey(fixture.sport, fixture.homeTeam)]
        val pA = snapshot.profiles[PredictionEngine.profileKey(fixture.sport, fixture.awayTeam)]
        val m = fixture.markets
        val firstLine = fixture.bookmakerOdds.firstOrNull()

        var homeOddsStr by remember(fixture.id) { mutableStateOf(String.format("%.2f", firstLine?.homeOdds ?: 2.00)) }
        var drawOddsStr by remember(fixture.id) { mutableStateOf(String.format("%.2f", firstLine?.drawOdds ?: 3.40)) }
        var awayOddsStr by remember(fixture.id) { mutableStateOf(String.format("%.2f", firstLine?.awayOdds ?: 3.50)) }

        fun savePick(label: String, odds: Double, chance: Double, stakeFrac: Double) {
            val activeBankroll = if (prefs.isPaperTrading) prefs.paperBankroll else prefs.bankroll
            val stake = max(10.0, OddsMath.recommendedStake(
                bankroll = activeBankroll,
                modelProb = chance,
                decimalOdds = odds,
                mode = prefs.stakingMode,
                kellyFraction = prefs.riskStyle.kellyFraction,
                maxCap = prefs.riskStyle.maxStakePct,
            ).roundToInt().toDouble())
            scope.launch {
                app.database.dao().upsertBet(
                    PersonalBetEntity(
                        id = "bet-${System.currentTimeMillis()}",
                        createdAtIso = Instant.now().toString(),
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
                val ledgerName = if (prefs.isPaperTrading) "Paper Picks" else "My Picks"
                savedBanner = "Saved \"$label\" ($${stake.toInt()}) to $ledgerName"
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = tokens.padMd, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (savedBanner != null) {
                item {
                    Surface(color = c.winDim, shape = RoundedCornerShape(tokens.cornerMd), border = BorderStroke(1.dp, c.win), modifier = Modifier.fillMaxWidth()) {
                        Text(savedBanner!!, color = c.win, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // Hero Matchup Card
            item {
                SectionCard(useHeroGradient = true) {
                    Text(
                        "${fixture.leagueName}${if (fixture.surface != null) " · ${fixture.surface} Court" else ""} · ${fixture.kickoffIso.take(16).replace("T", " ")}",
                        color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            TeamAvatar(fixture.homeTeam, size = 48.dp)
                            Text(fixture.homeTeam, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, textAlign = TextAlign.Center)
                            Text("Power ${pH?.powerScore ?: 55}/99", color = c.textSecondary, fontSize = 11.sp)
                            FormDots(pH?.recentForm.orEmpty())
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text("PROJECTED", color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = when (fixture.sport) {
                                    Sport.FOOTBALL -> "${String.format("%.1f", m.projectedHome)} – ${String.format("%.1f", m.projectedAway)}"
                                    Sport.TENNIS -> "${(m.probHome * 100).roundToInt()}–${(m.probAway * 100).roundToInt()}"
                                    else -> m.projectedScoreDisplay.ifBlank { "VS" }
                                },
                                style = tabularStyle(20f, FontWeight.ExtraBold, c.text),
                            )
                            Text(
                                if (fixture.sport == Sport.FOOTBALL) "Goals" else if (fixture.sport == Sport.TENNIS) "Win %" else m.projectedSpreadDisplay,
                                color = c.win, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            TeamAvatar(fixture.awayTeam, size = 48.dp)
                            Text(fixture.awayTeam, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, textAlign = TextAlign.Center)
                            Text("Power ${pA?.powerScore ?: 55}/99", color = c.textSecondary, fontSize = 11.sp)
                            FormDots(pA?.recentForm.orEmpty())
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
                                "80% confidence range (${shortTeamName(fixture.homeTeam)}): ${fixture.homeRangeLowPct}% – ${fixture.homeRangeHighPct}%",
                                color = c.textMuted,
                                fontSize = scaledSp(11.5f),
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (fixture.marketHoldPct > 0.0) {
                                StatusBadge("Vig ${String.format("%.1f", fixture.marketHoldPct)}%", c.sharpBadge, c.accentDim)
                            }
                        }
                    }
                }
            }

            // Stadium Weather (OD-04)
            fixture.weather?.let { w ->
                item {
                    SectionCard {
                        Text("Stadium Weather · ${w.stadium}", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text("${w.tempC}°C · Wind ${w.windKmh} km/h · Rain ${w.rainChancePct}% — ${w.summary}", color = c.textSecondary, fontSize = 13.sp)
                    }
                }
            }

            // Key Plain-English Insights (PR-04)
            if (fixture.plainInsights.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("Why We Lean This Way", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        fixture.plainInsights.forEach { bullet ->
                            Text("• $bullet", color = c.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Quantitative Feature Drivers (PR-04)
            if (fixture.featureDrivers.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("What's Driving This Prediction", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text("Signed impact of ratings, form, home edge, and conditions.", color = c.textMuted, fontSize = scaledSp(12f))
                        fixture.featureDrivers.forEach { drv ->
                            FeatureDriverRow(drv)
                        }
                    }
                }
            }

            // Interactive Line Movement & Steam Move Chart (OD-02)
            if (fixture.lineMovement.size >= 2) {
                item {
                    SectionCard {
                        Text("Line Movement & Steam Moves", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                        Text("Opening-to-kickoff implied win probability for ${shortTeamName(fixture.homeTeam)}.", color = c.textMuted, fontSize = scaledSp(12f))
                        LineMovementChart(fixture.lineMovement)
                    }
                }
            }

            // Player Props Section (PR-03)
            if (fixture.playerProps.isNotEmpty()) {
                item {
                    SectionCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Player Props (${fixture.playerProps.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                            StatusBadge("80% Range", c.accent, c.accentDim)
                        }
                        Text("Projected player stat lines adjusted for match pace and opponent defense.", color = c.textMuted, fontSize = scaledSp(12f))
                        fixture.playerProps.forEach { prop ->
                            PlayerPropCard(
                                prop = prop,
                                onSave = {
                                    val fairOdds = 100.0 / max(15, prop.overChancePct)
                                    savePick("${prop.playerName} — ${prop.lineLabel}", fairOdds, prop.overChancePct / 100.0, 0.02)
                                },
                            )
                        }
                    }
                }
            }

            // Ranked Multi-Market Top Picks
            item {
                SectionCard {
                    Text("Top Picks Across All Markets", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    m.topPicks.forEach { pick ->
                        Surface(color = c.surfaceRaised, shape = RoundedCornerShape(tokens.cornerMd), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${pick.category} · ${pick.confidence.label} (${pick.chancePct}%)", color = c.win, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(pick.label, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                    Text(pick.plainReason, color = c.textMuted, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { savePick(pick.label, 100.0 / max(15, pick.chancePct), pick.chancePct / 100.0, 0.02) },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                                    shape = RoundedCornerShape(tokens.cornerSm),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Save", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Complete Sport-Specific Markets
            item {
                SectionCard {
                    Text("Every Predicted Market", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
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
                                MetricTile("Home Clean Sheet", "${m.homeCleanSheetPct}%", "Win to Nil: ${m.homeWinToNilPct}%", Modifier.weight(1f))
                                MetricTile("Home Win & O1.5", "${m.homeWinAndOver15Pct}%", "Win & BTTS: ${m.homeWinAndBttsPct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Projected Corners", String.format("%.1f", m.projectedCorners), "O8.5: ${m.cornersOver85Pct}% · O9.5: ${m.cornersOver95Pct}%", Modifier.weight(1f))
                                MetricTile("Projected Cards", String.format("%.1f", m.projectedCards), "O3.5: ${m.cardsOver35Pct}% · Red: ${m.redCardChancePct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Shots / On Target", "${m.projectedShotsTotal.roundToInt()} / ${String.format("%.1f", m.projectedSotTotal)}", "Combined match volume", Modifier.weight(1f))
                                MetricTile("Projected Fouls", String.format("%.1f", m.projectedFouls), "Over 21.5: ${m.foulsOver215Pct}%", Modifier.weight(1f))
                            }
                            Text("Most Likely Exact Scores", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                m.topScorelines.take(3).forEach { s ->
                                    MetricTile(s.score, "${s.chancePct}%", null, Modifier.weight(1f))
                                }
                            }
                            m.topHtFt.forEach { (label, pct) ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Half-Time / Full-Time: $label", color = c.textSecondary, fontSize = 12.sp)
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
                                MetricTile("Total Games (${String.format("%.1f", m.projectedTotalGames)})", "Over ${m.totalGamesLine}: ${m.totalGamesOverPct}%", "Aces: ${String.format("%.1f", m.projectedAces)}", Modifier.weight(1f))
                            }
                        }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Projected Score", m.projectedScoreDisplay, "Halftime: ${m.halftimeDisplay}", Modifier.weight(1f))
                                MetricTile("Projected Spread", m.projectedSpreadDisplay, "${m.altSpreadLabel}: ${m.altSpreadPct}%", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetricTile("Projected Total", String.format("%.1f", m.projectedTotal), "Over ${m.totalLine}: ${m.totalOverPct}%", Modifier.weight(1f))
                                MetricTile(m.closeGameLabel, "${m.closeGamePct}%", "Overtime: ${m.overtimeChancePct}%", Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Cross-Bookmaker Line Shopping Table (OD-01, OD-06)
            if (fixture.bookmakerOdds.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("Bookmaker Odds Comparison", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        fixture.bookmakerOdds.forEach { bk ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1.3f)) {
                                    Text(bk.bookmaker, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(if (bk.isSharp) "Sharp Reference" else "Retail Book", color = if (bk.isSharp) c.win else c.textMuted, fontSize = 11.sp)
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

            // Side-by-Side Stats
            if (pH != null && pA != null) {
                item {
                    SectionCard {
                        Text("Team Stats Comparison", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        StatCompareBar("Win Rate", pH.winPct, pA.winPct, "%", 0)
                        StatCompareBar("Scored / Game", pH.avgScored, pA.avgScored)
                        StatCompareBar("Allowed / Game", pH.avgConceded, pA.avgConceded, higherIsBetter = false)
                        if (fixture.sport == Sport.FOOTBALL) {
                            StatCompareBar("Shots on Target", pH.shotsOnTargetPerGame, pA.shotsOnTargetPerGame)
                            StatCompareBar("Corners / Game", pH.cornersPerGame, pA.cornersPerGame)
                            StatCompareBar("Clean Sheets", pH.cleanSheetPct, pA.cleanSheetPct, "%", 0)
                        }
                    }
                }
            }

            // Offline Custom Bookmaker Odds & Suggested Stake Checker
            item {
                val hOdds = max(1.02, homeOddsStr.toDoubleOrNull() ?: 2.0)
                val dOdds = if (fixture.sport.hasDraw) max(1.02, drawOddsStr.toDoubleOrNull() ?: 3.4) else null
                val aOdds = max(1.02, awayOddsStr.toDoubleOrNull() ?: 2.0)
                val evals = OddsMath.evaluateSides(
                    fixture.homeTeam, fixture.awayTeam, m.probHome, m.probDraw, m.probAway,
                    hOdds, dOdds, aOdds, prefs.riskStyle.kellyFraction, prefs.riskStyle.maxStakePct,
                )

                SectionCard {
                    Text("Check Your Bookie's Odds (Offline)", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = homeOddsStr, onValueChange = { homeOddsStr = it }, label = { Text("Home") }, singleLine = true, modifier = Modifier.weight(1f))
                        if (fixture.sport.hasDraw) {
                            OutlinedTextField(value = drawOddsStr, onValueChange = { drawOddsStr = it }, label = { Text("Draw") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(value = awayOddsStr, onValueChange = { awayOddsStr = it }, label = { Text("Away") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    evals.forEach { ev ->
                        val dollars = (prefs.bankroll * ev.suggestedStakeFraction).roundToInt()
                        Surface(
                            color = if (ev.isGoodValue) c.winDim else c.surfaceRaised,
                            border = BorderStroke(1.dp, if (ev.isGoodValue) c.win else c.border),
                            shape = RoundedCornerShape(tokens.cornerMd),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${ev.label} @ ${OddsMath.formatOdds(ev.decimalOdds, prefs.oddsFormat)}", color = c.text, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                    Text("Our Chance ${(ev.modelProb * 100).roundToInt()}% · Fair ${(ev.fairMarketProb * 100).roundToInt()}%", color = c.textSecondary, fontSize = 11.5.sp)
                                }
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                                    Text(if (ev.isGoodValue) "Good Value (+${(ev.expectedValue * 100).roundToInt()}%)" else "Skip", color = if (ev.isGoodValue) c.win else c.textMuted, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                    Text(if (dollars > 0) "Bet $$dollars" else "No edge", color = c.textSecondary, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { savePick(ev.label, ev.decimalOdds, ev.modelProb, ev.suggestedStakeFraction) },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
