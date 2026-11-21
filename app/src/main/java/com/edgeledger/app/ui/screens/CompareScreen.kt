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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.ProbBar
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatCompareBar
import com.edgeledger.app.ui.components.TeamAvatar
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

    val teams = remember(snapshot.profiles, sport) {
        snapshot.profiles.values.filter { it.sport == sport }.sortedByDescending { it.elo }
    }
    val count = max(1, teams.size)
    val hIdx = homeIdx % count
    val aIdx = if (awayIdx % count == hIdx && count > 1) (hIdx + 1) % count else awayIdx % count
    val home = teams.getOrNull(hIdx)
    val away = teams.getOrNull(aIdx)

    val markets = remember(home, away, sport, prefs.marketBlendWeight) {
        if (home != null && away != null) {
            PredictionEngine.predictAllMarkets(sport, home.team, away.team, home, away, marketBlendWeight = prefs.marketBlendWeight)
        } else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.padMd),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Compare Any Two Teams Offline", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Tap either team card to switch teams and predict all markets without internet.", color = c.textMuted, fontSize = 13.sp)
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Sport.entries) { sp ->
                    FilterChip(
                        selected = sport == sp,
                        onClick = { sport = sp; homeIdx = 0; awayIdx = 1 },
                        label = { Text(sp.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accentDim, selectedLabelColor = c.accent),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = c.surface,
                    border = BorderStroke(1.dp, c.border),
                    shape = RoundedCornerShape(tokens.cornerLg),
                    modifier = Modifier.weight(1f).clickable { homeIdx = (homeIdx + 1) % count },
                ) {
                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        TeamAvatar(home?.team ?: "Home", size = 44.dp)
                        Text("HOME · TAP", color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(home?.team ?: "—", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text("Power ${home?.powerScore ?: 55}/99", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    color = c.surface,
                    border = BorderStroke(1.dp, c.border),
                    shape = RoundedCornerShape(tokens.cornerLg),
                    modifier = Modifier.weight(1f).clickable { awayIdx = (awayIdx + 1) % count },
                ) {
                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        TeamAvatar(away?.team ?: "Away", size = 44.dp)
                        Text("AWAY · TAP", color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(away?.team ?: "—", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text("Power ${away?.powerScore ?: 55}/99", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (markets != null && home != null && away != null) {
            item {
                SectionCard {
                    Text("Predicted Win Chances", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    ProbBar(
                        homeLabel = home.team,
                        awayLabel = away.team,
                        homeProb = markets.probHome,
                        drawProb = if (sport.hasDraw) markets.probDraw else null,
                        awayProb = markets.probAway,
                        barHeight = 12.dp,
                    )
                    markets.topPicks.take(4).forEach { pk ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pk.label, color = c.text, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                Text(pk.plainReason, color = c.textMuted, fontSize = 11.5.sp)
                            }
                            Text("${pk.chancePct}%", style = tabularStyle(14f, FontWeight.ExtraBold, c.win))
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text("Side-by-Side Stats", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    StatCompareBar("Win Rate", home.winPct, away.winPct, "%", 0)
                    StatCompareBar("Scored / Game", home.avgScored, away.avgScored)
                    StatCompareBar("Allowed / Game", home.avgConceded, away.avgConceded, higherIsBetter = false)
                }
            }
        }

        // Sharp Market Blend Slider (PR-10)
        item {
            SectionCard {
                Text("Blend with Sharp Market Odds: ${(prefs.marketBlendWeight * 100).roundToInt()}%", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                Text("0% uses only our stats model. Raising this mixes in sharp bookmaker consensus to guard against late lineup news.", color = c.textMuted, fontSize = 12.sp)
                Slider(
                    value = prefs.marketBlendWeight.toFloat(),
                    onValueChange = { v -> app.prefsRepo.updatePrefs { it.copy(marketBlendWeight = v.toDouble()) } },
                    valueRange = 0f..0.5f,
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
                Text("Quick Parlay / Multi-Bet Calculator", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
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
                Text("Estimated combined win chance: ${parlay.combinedChancePct}% (Profit: +$${String.format("%.2f", parlay.potentialProfit)})", color = c.textMuted, fontSize = 12.sp)
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}
