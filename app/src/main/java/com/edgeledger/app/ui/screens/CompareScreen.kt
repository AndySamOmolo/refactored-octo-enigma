package com.edgeledger.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()

    var sportName by rememberSaveable { mutableStateOf(Sport.FOOTBALL.name) }
    val sport = runCatching { Sport.valueOf(sportName) }.getOrDefault(Sport.FOOTBALL)
    var homeIdx by rememberSaveable { mutableIntStateOf(0) }
    var awayIdx by rememberSaveable { mutableIntStateOf(1) }
    var pickingSlot by rememberSaveable { mutableStateOf<String?>(null) }
    var teamQuery by remember { mutableStateOf("") }

    var localBlend by remember(prefs.marketBlendWeight) { mutableFloatStateOf(prefs.marketBlendWeight.toFloat()) }

    var leg1Odds by rememberSaveable { mutableStateOf("1.80") }
    var leg2Odds by rememberSaveable { mutableStateOf("1.95") }
    var leg3Odds by rememberSaveable { mutableStateOf("2.10") }
    var parlayStake by rememberSaveable { mutableStateOf("25") }
    var arbOddsA by rememberSaveable { mutableStateOf("2.15") }
    var arbOddsB by rememberSaveable { mutableStateOf("2.05") }
    var arbStake by rememberSaveable { mutableStateOf("100") }

    val teams = remember(snapshot.profiles, sport) {
        snapshot.profiles.values.filter { it.sport == sport }.sortedByDescending { it.elo }
    }
    val count = max(1, teams.size)
    val hIdx = homeIdx.coerceAtLeast(0) % count
    val aIdx = if (awayIdx.coerceAtLeast(0) % count == hIdx && count > 1) (hIdx + 1) % count else awayIdx.coerceAtLeast(0) % count
    val home = teams.getOrNull(hIdx)
    val away = teams.getOrNull(aIdx)

    val markets = remember(home, away, sport, localBlend) {
        if (home != null && away != null && home.team != away.team) {
            PredictionEngine.predictAllMarkets(
                sport = sport,
                homeTeam = home.team,
                awayTeam = away.team,
                homeProfile = home,
                awayProfile = away,
                marketBlendWeight = localBlend.toDouble(),
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
                Text("Compare Any Two Teams", color = c.text, fontSize = scaledSp(19f), fontWeight = FontWeight.ExtraBold)
                Text("Tap either team card to pick any matchup and predict all markets 100% offline.", color = c.textMuted, fontSize = scaledSp(13f))
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Sport.entries, key = { it.name }) { sp ->
                    TokenPill(
                        label = sp.label,
                        selected = sport == sp,
                        onClick = { sportName = sp.name; homeIdx = 0; awayIdx = 1 },
                    )
                }
            }
        }

        if (teams.size < 2) {
            item {
                SectionCard {
                    Text("Not enough synced teams for ${sport.label}", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    Text("Tap Update on the Games tab to sync live datasets for this sport.", color = c.textMuted, fontSize = scaledSp(12.5f))
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            color = c.surface,
                            border = BorderStroke(1.dp, c.border),
                            shape = tokens.shapes.card,
                            shadowElevation = tokens.elevationDp,
                            modifier = Modifier.weight(1f).clickable { teamQuery = ""; pickingSlot = "HOME" },
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TeamAvatar(home?.team ?: "Home", size = 44.dp, logoUrl = home?.logoUrl)
                                Text("HOME · PICK", color = c.textMuted, fontSize = scaledSp(10f), fontWeight = FontWeight.Bold)
                                Text(home?.team ?: "No teams", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                StatusBadge("Power ${home?.powerScore ?: 50}/99", c.accent, c.accentDim)
                            }
                        }
                        Surface(
                            color = c.surface,
                            border = BorderStroke(1.dp, c.border),
                            shape = tokens.shapes.card,
                            shadowElevation = tokens.elevationDp,
                            modifier = Modifier.weight(1f).clickable { teamQuery = ""; pickingSlot = "AWAY" },
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TeamAvatar(away?.team ?: "Away", size = 44.dp, logoUrl = away?.logoUrl)
                                Text("AWAY · PICK", color = c.textMuted, fontSize = scaledSp(10f), fontWeight = FontWeight.Bold)
                                Text(away?.team ?: "No teams", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                StatusBadge("Power ${away?.powerScore ?: 50}/99", c.accent, c.accentDim)
                            }
                        }
                    }
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
                    StatCompareBar("Allowed / Gm (Lower = Better)", home.avgConceded, away.avgConceded, higherIsBetter = false)
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

        item {
            SectionCard {
                Text("Blend with Sharp Market Odds: ${(localBlend * 100).roundToInt()}%", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f))
                Text("0% uses only your on-device stats model. Raising this mixes in sharp bookmaker odds to account for late lineup or injury news.", color = c.textMuted, fontSize = scaledSp(12f))
                Slider(
                    value = localBlend,
                    onValueChange = { localBlend = it },
                    onValueChangeFinished = { app.prefsRepo.updatePrefs { it.copy(marketBlendWeight = localBlend.toDouble()) } },
                    valueRange = 0f..0.5f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                )
            }
        }

        item {
            val l1 = max(1.01, leg1Odds.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 1.80)
            val l2 = max(1.01, leg2Odds.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 1.95)
            val l3 = max(1.01, leg3Odds.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 2.10)
            val st = max(1.0, parlayStake.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 25.0)
            val parlay = OddsMath.calculateParlay(
                listOf(l1 to (1.0 / (l1 * 1.045)), l2 to (1.0 / (l2 * 1.045)), l3 to (1.0 / (l3 * 1.045))),
                st,
            )

            SectionCard {
                Text("Quick Parlay / Multi-Bet Calculator", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = leg1Odds, onValueChange = { leg1Odds = it }, label = { Text("Leg 1") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = leg2Odds, onValueChange = { leg2Odds = it }, label = { Text("Leg 2") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = leg3Odds, onValueChange = { leg3Odds = it }, label = { Text("Leg 3") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = parlayStake, onValueChange = { parlayStake = it }, label = { Text("Stake ($)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Combined Odds: ${OddsMath.formatOdds(parlay.combinedOdds, prefs.oddsFormat)}", style = tabularStyle(13.5f, FontWeight.Bold, c.text))
                    Text("Payout: $${String.format(Locale.US, "%.2f", parlay.potentialPayout)}", style = tabularStyle(14f, FontWeight.ExtraBold, c.win))
                }
                Text("Estimated combined win chance: ${parlay.combinedChancePct}% · Est. book vig: ${String.format(Locale.US, "%.1f", parlay.estimatedVigPct)}% (Profit: +$${String.format(Locale.US, "%.2f", parlay.potentialProfit)})", color = c.textMuted, fontSize = scaledSp(12f))
                parlay.correlationWarning?.let { warn ->
                    Text(warn, color = c.draw, fontSize = scaledSp(11.5f), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            val oA = max(1.01, arbOddsA.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 2.15)
            val oB = max(1.01, arbOddsB.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 2.05)
            val totalS = max(1.0, arbStake.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 100.0)
            val arb = OddsMath.calculateArbitrage(oA, oB, totalS)
            val eqStakeA = totalS * (oB / (oA + oB))
            val eqStakeB = totalS - eqStakeA
            val eqReturn = eqStakeA * oA
            val eqProfit = eqReturn - totalS

            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Arbitrage & Hedge", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f), modifier = Modifier.weight(1f))
                    StatusBadge(
                        text = if (arb.isArbitrage) "Arb +${String.format(Locale.US, "%.2f", arb.profitPct)}%" else "No Arb (${String.format(Locale.US, "%.1f", arb.profitPct)}%)",
                        color = if (arb.isArbitrage) c.win else c.textMuted,
                        containerColor = if (arb.isArbitrage) c.winDim else c.surfaceRaised,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = arbOddsA, onValueChange = { arbOddsA = it }, label = { Text("Odds A") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = arbOddsB, onValueChange = { arbOddsB = it }, label = { Text("Odds B") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = arbStake, onValueChange = { arbStake = it }, label = { Text("Stake ($)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Text(
                    "Equalized split: $${String.format(Locale.US, "%.2f", eqStakeA)} on A · $${String.format(Locale.US, "%.2f", eqStakeB)} on B → Est. Return $${String.format(Locale.US, "%.2f", eqReturn)} (${if (eqProfit >= 0) "+" else "-"}$${String.format(Locale.US, "%.2f", kotlin.math.abs(eqProfit))})",
                    style = tabularStyle(12.5f, FontWeight.Bold, if (arb.isArbitrage) c.win else c.textSecondary),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (pickingSlot != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val filteredTeams = remember(teams, teamQuery) {
            val q = teamQuery.trim()
            teams.mapIndexedNotNull { idx, tp ->
                if (q.isEmpty() || tp.team.contains(q, ignoreCase = true)) idx to tp else null
            }
        }
        ModalBottomSheet(
            onDismissRequest = { pickingSlot = null },
            sheetState = sheetState,
            containerColor = c.surface,
            contentColor = c.text,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Select ${if (pickingSlot == "HOME") "Home" else "Away"} Team (${sport.label})",
                    color = c.text,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = scaledSp(16f),
                )
                OutlinedTextField(
                    value = teamQuery,
                    onValueChange = { teamQuery = it },
                    placeholder = { Text("Search teams…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = c.textMuted) },
                    trailingIcon = if (teamQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { teamQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = c.textMuted)
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredTeams, key = { (_, tp) -> tp.team }) { (idx, tp) ->
                        val isCurrent = (pickingSlot == "HOME" && idx == hIdx) || (pickingSlot == "AWAY" && idx == aIdx)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (pickingSlot == "HOME") {
                                        if (idx == aIdx) awayIdx = hIdx
                                        homeIdx = idx
                                    } else {
                                        if (idx == hIdx) homeIdx = aIdx
                                        awayIdx = idx
                                    }
                                    pickingSlot = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TeamAvatar(tp.team, size = 34.dp, logoUrl = tp.logoUrl)
                            Text(
                                tp.team,
                                color = if (isCurrent) c.accent else c.text,
                                fontWeight = FontWeight.Bold,
                                fontSize = scaledSp(14f),
                                modifier = Modifier.weight(1f),
                            )
                            StatusBadge(
                                "Power ${tp.powerScore}/99",
                                if (isCurrent) c.accent else c.textSecondary,
                                if (isCurrent) c.accentDim else c.surfaceRaised,
                            )
                        }
                        HorizontalDivider(color = c.border.copy(alpha = 0.5f))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
