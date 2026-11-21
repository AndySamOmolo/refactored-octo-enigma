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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.FormDots
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatCompareBar
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TeamAvatar
import com.edgeledger.app.ui.components.TokenPill
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.shortTeamName
import com.edgeledger.app.ui.components.tabularStyle
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun CompareScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()

    var sport by remember { mutableStateOf(Sport.FOOTBALL) }
    var homeIdx by remember { mutableIntStateOf(0) }
    var awayIdx by remember { mutableIntStateOf(1) }

    // Parlay / Multi-Leg Calculator state (EN-05)
    var leg1Odds by remember { mutableStateOf("1.80") }
    var leg2Odds by remember { mutableStateOf("1.95") }
    var leg3Odds by remember { mutableStateOf("2.10") }
    var parlayStake by remember { mutableStateOf("25") }
    var arbOddsA by remember { mutableStateOf("2.15") }
    var arbOddsB by remember { mutableStateOf("2.05") }
    var arbStake by remember { mutableStateOf("100") }

    val teams = remember(snapshot.profiles, sport) {
        snapshot.profiles.values.filter { it.sport == sport }.sortedByDescending { it.elo }
    }
    val count = max(1, teams.size)
    val hIdx = homeIdx.coerceAtLeast(0) % count
    val aIdx = if (awayIdx.coerceAtLeast(0) % count == hIdx && count > 1) (hIdx + 1) % count else awayIdx.coerceAtLeast(0) % count
    val home = teams.getOrNull(hIdx)
    val away = teams.getOrNull(aIdx)

    val markets = remember(home, away, sport, prefs.marketBlendWeight) {
        if (home != null && away != null) {
            PredictionEngine.predictAllMarkets(
                sport = sport,
                homeTeam = home.team,
                awayTeam = away.team,
                homeProfile = home,
                awayProfile = away,
                marketBlendWeight = prefs.marketBlendWeight,
            )
        } else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Compare Any Two Teams", color = c.text, fontSize = scaledSp(20f), fontWeight = FontWeight.ExtraBold)
                Text("Tap either team card to cycle matchups and predict all markets 100% offline.", color = c.textMuted, fontSize = scaledSp(13f))
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Sport.entries) { sp ->
                    TokenPill(
                        label = sp.label,
                        selected = sport == sp,
                        onClick = { sport = sp; homeIdx = 0; awayIdx = 1 },
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = c.surface,
                        border = BorderStroke(1.dp, c.border),
                        shape = tokens.shapes.card,
                        shadowElevation = tokens.elevationDp,
                        modifier = Modifier.weight(1f).clickable { homeIdx = (hIdx + 1) % count },
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TeamAvatar(home?.team ?: "Home", size = 44.dp)
                            Text("HOME · TAP", color = c.textMuted, fontSize = scaledSp(10f), fontWeight = FontWeight.Bold)
                            Text(home?.team ?: "No teams", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            StatusBadge("Power ${home?.powerScore ?: 55}/99", c.accent, c.accentDim)
                        }
                    }
                    Surface(
                        color = c.surface,
                        border = BorderStroke(1.dp, c.border),
                        shape = tokens.shapes.card,
                        shadowElevation = tokens.elevationDp,
                        modifier = Modifier.weight(1f).clickable { awayIdx = (aIdx + 1) % count },
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TeamAvatar(away?.team ?: "Away", size = 44.dp)
                            Text("AWAY · TAP", color = c.textMuted, fontSize = scaledSp(10f), fontWeight = FontWeight.Bold)
                            Text(away?.team ?: "No teams", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            StatusBadge("Power ${away?.powerScore ?: 55}/99", c.accent, c.accentDim)
                        }
                    }
                }
                if (count > 1) {
                    OutlinedButton(
                        onClick = { val tmp = hIdx; homeIdx = aIdx; awayIdx = tmp },
                        shape = tokens.shapes.button,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Swap Home & Away", color = c.accent, fontWeight = FontWeight.Bold, fontSize = scaledSp(12.5f))
                    }
                }
            }
        }

        if (markets != null && home != null && away != null) {
            item {
                SectionCard(useHeroGradient = true) {
                    Text("Predicted Win Chances", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    ProbBar(
                        homeLabel = shortTeamName(home.team),
                        awayLabel = shortTeamName(away.team),
                        homeProb = markets.probHome,
                        drawProb = if (sport.hasDraw) markets.probDraw else null,
                        awayProb = markets.probAway,
                        barHeight = 12.dp,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        FormDots(home.recentForm, alignEnd = false)
                        Text("Last 5", color = c.textMuted, fontSize = scaledSp(11f), fontWeight = FontWeight.SemiBold)
                        FormDots(away.recentForm, alignEnd = true)
                    }
                    markets.topPicks.take(4).forEach { pk ->
                        Surface(color = c.surfaceRaised, shape = tokens.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pk.label, color = c.text, fontWeight = FontWeight.Bold, fontSize = scaledSp(13.5f))
                                    Text(pk.plainReason, color = c.textMuted, fontSize = scaledSp(11.5f))
                                }
                                Text("${pk.chancePct}%", style = tabularStyle(14f, FontWeight.ExtraBold, c.win))
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text("Side-by-Side Stats", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    StatCompareBar("Win Rate", home.winPct, away.winPct, "%", 0)
                    StatCompareBar("Scored / Game", home.avgScored, away.avgScored)
                    StatCompareBar("Allowed / Game", home.avgConceded, away.avgConceded, higherIsBetter = false)
                    if (sport == Sport.FOOTBALL) {
                        StatCompareBar("Shots / Game", home.shotsPerGame, away.shotsPerGame)
                        StatCompareBar("Corners / Game", home.cornersPerGame, away.cornersPerGame)
                        StatCompareBar("Cards / Game", home.cardsPerGame, away.cardsPerGame, higherIsBetter = false)
                        StatCompareBar("Clean Sheet %", home.cleanSheetPct, away.cleanSheetPct, "%", 0)
                    } else if (sport == Sport.TENNIS) {
                        StatCompareBar("1st Serve Won", home.firstServeWinPct, away.firstServeWinPct, "%", 0)
                        StatCompareBar("Aces / Match", home.avgAces, away.avgAces)
                    } else if (sport == Sport.NFL) {
                        StatCompareBar("Spread Cover %", home.atsCoverPct, away.atsCoverPct, "%", 0)
                        StatCompareBar("Over Rate %", home.overTotalPct, away.overTotalPct, "%", 0)
                    }
                }
            }
        }

        // Sharp Market Blend Slider (PR-10)
        item {
            SectionCard {
                Text("Blend with Sharp Market Odds: ${(prefs.marketBlendWeight * 100).roundToInt()}%", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f))
                Text("0% uses only your on-device stats model. Raising this mixes in sharp bookmaker odds to account for late lineup or injury news.", color = c.textMuted, fontSize = scaledSp(12f))
                Slider(
                    value = prefs.marketBlendWeight.toFloat(),
                    onValueChange = { v -> app.prefsRepo.updatePrefs { it.copy(marketBlendWeight = v.toDouble()) } },
                    valueRange = 0f..0.5f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                )
            }
        }

        // Multi-Leg Parlay / Accumulator Calculator (EN-05)
        item {
            val l1 = max(1.01, leg1Odds.toDoubleOrNull() ?: 1.80)
            val l2 = max(1.01, leg2Odds.toDoubleOrNull() ?: 1.95)
            val l3 = max(1.01, leg3Odds.toDoubleOrNull() ?: 2.10)
            val st = max(1.0, parlayStake.toDoubleOrNull() ?: 25.0)
            val parlay = OddsMath.calculateParlay(
                listOf(l1 to (1.0 / l1), l2 to (1.0 / l2), l3 to (1.0 / l3)),
                st,
            )

            SectionCard {
                Text("Quick Parlay / Multi-Bet Calculator", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = leg1Odds, onValueChange = { leg1Odds = it }, label = { Text("Leg 1") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = leg2Odds, onValueChange = { leg2Odds = it }, label = { Text("Leg 2") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = leg3Odds, onValueChange = { leg3Odds = it }, label = { Text("Leg 3") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = parlayStake, onValueChange = { parlayStake = it }, label = { Text("Stake ($)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Combined Odds: ${OddsMath.formatOdds(parlay.combinedOdds, prefs.oddsFormat)}", style = tabularStyle(13.5f, FontWeight.Bold, c.text))
                    Text("Payout: $${String.format("%.2f", parlay.potentialPayout)}", style = tabularStyle(14f, FontWeight.ExtraBold, c.win))
                }
                Text("Estimated combined win chance: ${parlay.combinedChancePct}% · Est. book vig: ${String.format("%.1f", parlay.estimatedVigPct)}% (Profit: +$${String.format("%.2f", parlay.potentialProfit)})", color = c.textMuted, fontSize = scaledSp(12f))
                parlay.correlationWarning?.let { warn ->
                    Text(warn, color = c.draw, fontSize = scaledSp(11.5f), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Arbitrage & Hedge Split Calculator (EN-05)
        item {
            val oA = max(1.01, arbOddsA.toDoubleOrNull() ?: 2.15)
            val oB = max(1.01, arbOddsB.toDoubleOrNull() ?: 2.05)
            val totalS = max(1.0, arbStake.toDoubleOrNull() ?: 100.0)
            val arb = OddsMath.calculateArbitrage(oA, oB, totalS)

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Arbitrage & Hedge Calculator", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    StatusBadge(
                        text = if (arb.isArbitrage) "Arb +${String.format("%.2f", arb.profitPct)}%" else "No Arb (${String.format("%.1f", arb.profitPct)}%)",
                        color = if (arb.isArbitrage) c.win else c.textMuted,
                        containerColor = if (arb.isArbitrage) c.winDim else c.surfaceRaised,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = arbOddsA, onValueChange = { arbOddsA = it }, label = { Text("Side A Odds") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = arbOddsB, onValueChange = { arbOddsB = it }, label = { Text("Side B Odds") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = arbStake, onValueChange = { arbStake = it }, label = { Text("Total ($)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text(
                    "Split: $${String.format("%.2f", arb.stakeA)} on Side A · $${String.format("%.2f", arb.stakeB)} on Side B → Return $${String.format("%.2f", arb.guaranteedPayout)} (${if (arb.guaranteedProfit >= 0) "+" else ""}$${String.format("%.2f", arb.guaranteedProfit)})",
                    style = tabularStyle(12.5f, FontWeight.Bold, if (arb.isArbitrage) c.win else c.textSecondary),
                )
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}
