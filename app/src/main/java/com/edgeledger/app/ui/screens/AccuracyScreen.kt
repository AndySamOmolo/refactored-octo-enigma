package com.edgeledger.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.SegmentedTabs
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.formatLocalKickoff
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.tabularStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AccuracyScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val history by app.syncRepo.predictionHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var historyFilterIdx by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val graded = remember(history) { history.filter { it.resultStatus in setOf("WON", "LOST", "PUSH") } }
    val wonCount = remember(graded) { graded.count { it.resultStatus == "WON" } }
    val lostCount = remember(graded) { graded.count { it.resultStatus == "LOST" } }
    val pendingCount = remember(history) { history.count { it.resultStatus == "PENDING" } }
    val decidedCount = wonCount + lostCount
    val liveHitPct = if (decidedCount > 0) ((wonCount.toDouble() / decidedCount.toDouble()) * 100.0).roundToInt() else null

    val filteredHistory = remember(history, historyFilterIdx, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        history.filter { entry ->
            val matchesStatus = when (historyFilterIdx) {
                1 -> entry.resultStatus == "PENDING"
                2 -> entry.resultStatus == "WON"
                3 -> entry.resultStatus == "LOST"
                else -> true
            }
            val matchesQuery = q.isEmpty() ||
                entry.homeTeam.lowercase(Locale.ROOT).contains(q) ||
                entry.awayTeam.lowercase(Locale.ROOT).contains(q) ||
                entry.leagueName.lowercase(Locale.ROOT).contains(q) ||
                entry.sport.lowercase(Locale.ROOT).contains(q) ||
                entry.topPickLabel.lowercase(Locale.ROOT).contains(q)
            matchesStatus && matchesQuery
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Track Record & Accuracy", color = c.text, fontSize = scaledSp(19f), fontWeight = FontWeight.ExtraBold)
                    Text(
                        when (activeTab) {
                            0 -> "Real pre-kickoff predictions permanently stored and graded after final whistle."
                            1 -> "Chronological walk-forward backtest on historical completed games."
                            else -> "Live public prediction markets and feed sync diagnostics."
                        },
                        color = c.textMuted,
                        fontSize = scaledSp(12f),
                    )
                }
                SegmentedTabs(
                    tabs = listOf("Live History (${history.size})", "Backtest", "Markets"),
                    selectedIndex = activeTab,
                    onSelect = { activeTab = it },
                )
            }
        }

        // TAB 0: LIVE GRADED PREDICTION HISTORY (Permanent, Never Deleted)
        if (activeTab == 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricTile(
                        title = "Tracked",
                        value = "${history.size}",
                        subtitle = "$pendingCount pending",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        subtitleColor = c.textMuted,
                    )
                    MetricTile(
                        title = "Graded",
                        value = "${graded.size}",
                        subtitle = if (decidedCount > 0) "${wonCount}W – ${lostCount}L" else "Awaiting finals",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        subtitleColor = if (decidedCount > 0) c.accent else c.draw,
                    )
                    MetricTile(
                        title = "Live Hit %",
                        value = if (liveHitPct != null) "$liveHitPct%" else "—",
                        subtitle = if (liveHitPct != null) "Real outcomes" else "No finals yet",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        subtitleColor = if (liveHitPct != null && liveHitPct >= 55) c.win else c.textMuted,
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search team, league, or pick") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SegmentedTabs(
                        tabs = listOf("All (${history.size})", "Pending ($pendingCount)", "Won ($wonCount)", "Lost ($lostCount)"),
                        selectedIndex = historyFilterIdx,
                        onSelect = { historyFilterIdx = it },
                    )
                }
            }

            if (filteredHistory.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            if (history.isEmpty()) "No live predictions archived yet" else "No predictions match this filter",
                            color = c.text,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = scaledSp(14.5f),
                        )
                        Text(
                            if (history.isEmpty()) {
                                "Tap Update on the Games tab. Every upcoming game's pre-kickoff prediction is locked here permanently and auto-graded once its real final score arrives."
                            } else {
                                "Try switching to All or clearing your search query."
                            },
                            color = c.textMuted,
                            fontSize = scaledSp(12f),
                        )
                    }
                }
            } else {
                items(filteredHistory, key = { it.fixtureId }) { entry ->
                    val (stFg, stBg) = when (entry.resultStatus) {
                        "WON" -> c.win to c.winDim
                        "LOST" -> c.loss to c.lossDim
                        "PUSH" -> c.textSecondary to c.surfaceRaised
                        else -> c.draw to c.drawDim
                    }
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${entry.sport} · ${entry.leagueName} · ${formatLocalKickoff(entry.kickoffIso)}",
                                color = c.accent,
                                fontSize = scaledSp(11f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            StatusBadge(entry.resultStatus, stFg, stBg)
                        }
                        Text(
                            "${entry.homeTeam} vs ${entry.awayTeam}",
                            color = c.text,
                            fontSize = scaledSp(14f),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "Pick: ${entry.topPickLabel} (${entry.topPickChancePct}%)",
                                    color = c.textSecondary,
                                    fontSize = scaledSp(12f),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (entry.actualHomeScore != null && entry.actualAwayScore != null) {
                                        "Final Score: ${entry.actualHomeScore} – ${entry.actualAwayScore} · Lock #${entry.hashSha256.take(8)}"
                                    } else {
                                        "Projected: ${String.format(Locale.US, "%.1f", entry.projectedHome)} – ${String.format(Locale.US, "%.1f", entry.projectedAway)} · Lock #${entry.hashSha256.take(8)}"
                                    },
                                    color = c.textMuted,
                                    fontSize = scaledSp(11f),
                                )
                            }
                            if (entry.sport.equals("Football", ignoreCase = true)) {
                                StatusBadge("BTTS ${entry.bttsYesPct}% · O2.5 ${entry.over25Pct}%", c.textSecondary, c.surfaceRaised)
                            }
                        }
                    }
                }
            }
        }

        // TAB 1: HISTORICAL WALK-FORWARD BACKTEST
        if (activeTab == 1) {
            item {
                Surface(
                    color = c.accentDim,
                    shape = tokens.shapes.medium,
                    border = BorderStroke(0.75.dp, c.accent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("How Backtest Scores Work", color = c.accent, fontSize = scaledSp(12f), fontWeight = FontWeight.ExtraBold)
                        Text(
                            "These scores test the model chronologically on already-completed historical games (using only prior Elo & form, never future results). Your real upcoming picks are graded separately under Live History.",
                            color = c.textSecondary,
                            fontSize = scaledSp(11.5f),
                        )
                    }
                }
            }

            if (snapshot.trackRecords.isEmpty()) {
                item {
                    SectionCard {
                        Text("No backtest history yet", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f))
                        Text(
                            "Tap Update on the Games tab to download live historical datasets and run walk-forward accuracy tests on your phone.",
                            color = c.textMuted,
                            fontSize = scaledSp(12f),
                        )
                    }
                }
            } else {
                item {
                    val totalGames = snapshot.trackRecords.sumOf { it.gamesTested }
                    val avgSkill = if (totalGames > 0) (snapshot.trackRecords.sumOf { it.accuracyScore.toDouble() * it.gamesTested } / totalGames.toDouble()).roundToInt() else 0
                    val gradedTracks = snapshot.trackRecords.filter { it.hasGradedOdds }
                    val avgRoi = if (gradedTracks.isNotEmpty()) gradedTracks.map { it.simulatedProfitPct }.average() else null
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricTile("Avg Skill", "$avgSkill/100", "${snapshot.trackRecords.size} leagues", Modifier.weight(1f).fillMaxHeight(), subtitleColor = c.accent)
                        MetricTile("Past Games", "$totalGames", "Walk-forward", Modifier.weight(1f).fillMaxHeight(), subtitleColor = c.textMuted)
                        MetricTile(
                            "Avg Sim ROI",
                            if (avgRoi != null) "${if (avgRoi >= 0) "+" else ""}${String.format(Locale.US, "%.1f", avgRoi)}%" else "N/A",
                            if (avgRoi != null) (if (avgRoi >= 0) "Historical" else "Negative") else "No odds CSV",
                            Modifier.weight(1f).fillMaxHeight(),
                            subtitleColor = if (avgRoi != null && avgRoi >= 0) c.win else c.loss,
                        )
                    }
                }

                items(snapshot.trackRecords, key = { "${it.sport.name}-${it.leagueId}" }) { rec ->
                    val cleanLeague = SyncRepository.readableLeagueName(rec.leagueId, rec.sport)
                    val title = if (cleanLeague.equals(rec.sport.label, ignoreCase = true)) {
                        cleanLeague
                    } else {
                        "${rec.sport.label} · $cleanLeague"
                    }

                    SectionCard {
                        val binTotal = rec.reliabilityBins.sumOf { it.count }
                        val binHits = rec.reliabilityBins.sumOf { ((it.actualWinPct / 100.0) * it.count).roundToInt() }
                        val baselineRate = if (rec.sport.hasDraw) 1.0 / 3.0 else 0.50
                        val sig = if (binTotal > 0) {
                            OddsMath.computeSignificance(binHits.coerceIn(0, binTotal), binTotal, baselineRate)
                        } else null
                        val isNegativeRoi = rec.hasGradedOdds && rec.simulatedProfitPct < 0.0
                        val (badgeTxt, badgeFg, badgeBg) = when {
                            rec.beatsSimpleGuess && rec.gamesTested >= 20 && !isNegativeRoi -> Triple("Strong Record", c.win, c.winDim)
                            rec.beatsSimpleGuess && isNegativeRoi -> Triple("Calibrated · Neg ROI", c.draw, c.drawDim)
                            isNegativeRoi -> Triple("Negative ROI", c.loss, c.lossDim)
                            else -> Triple("Building History", c.draw, c.drawDim)
                        }
                        val (skillSub, skillSubColor) = when {
                            rec.beatsSimpleGuess -> "Beats baseline" to c.win
                            sig != null && sig.zScore < -1.0 -> "Below baseline" to c.loss
                            else -> "Near baseline" to c.draw
                        }
                        val roiSub = if (rec.hasGradedOdds) (if (rec.simulatedProfitPct >= 0.0) "Profitable" else "Unprofitable") else "No odds"
                        val roiSubColor = if (rec.hasGradedOdds) (if (rec.simulatedProfitPct >= 0.0) c.win else c.loss) else c.textMuted

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f), modifier = Modifier.weight(1f))
                            StatusBadge(text = badgeTxt, color = badgeFg, containerColor = badgeBg)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetricTile("Skill Score", "${rec.accuracyScore}/100", skillSub, Modifier.weight(1f).fillMaxHeight(), subtitleColor = skillSubColor)
                            MetricTile("Games", "${rec.gamesTested}", "Historical", Modifier.weight(1f).fillMaxHeight(), subtitleColor = c.textMuted)
                            MetricTile(
                                "Sim ROI",
                                if (rec.hasGradedOdds) "${if (rec.simulatedProfitPct >= 0) "+" else ""}${String.format(Locale.US, "%.1f", rec.simulatedProfitPct)}%" else "N/A",
                                roiSub,
                                Modifier.weight(1f).fillMaxHeight(),
                                subtitleColor = roiSubColor,
                            )
                        }
                        if (rec.reliabilityBins.isNotEmpty()) {
                            Text("Calibration: predicted % vs actual win %", color = c.textSecondary, fontSize = scaledSp(11.5f), fontWeight = FontWeight.SemiBold)
                            rec.reliabilityBins.forEach { bin ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text(bin.rangeLabel, style = tabularStyle(10.5f, FontWeight.Bold, c.textSecondary), modifier = Modifier.width(52.dp))
                                    Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceRaised)) {
                                        if (bin.actualWinPct > 0) Box(modifier = Modifier.fillMaxWidth((bin.actualWinPct / 100f).coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
                                    }
                                    Text("${bin.actualWinPct}% (${bin.count})", style = tabularStyle(10.5f, FontWeight.Medium, c.textMuted), modifier = Modifier.width(68.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // TAB 2: LIVE PREDICTION MARKETS & FEED HEALTH
        if (activeTab == 2) {
            if (snapshot.predictionMarkets.isNotEmpty()) {
                item {
                    SectionCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Live Sports Markets (${snapshot.predictionMarkets.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f), modifier = Modifier.weight(1f))
                            val activePmSources = snapshot.predictionMarkets.map { it.source }.distinct().joinToString(" · ")
                            StatusBadge(activePmSources.ifBlank { "Sports Markets" }, c.sharpBadge, c.sharpBadgeDim)
                        }
                        Text("Live public implied probabilities synced directly on your phone.", color = c.textMuted, fontSize = scaledSp(11.5f))
                        snapshot.predictionMarkets.forEach { pm ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pm.question, color = c.text, fontSize = scaledSp(12.5f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(pm.source, color = c.accent, fontSize = scaledSp(10.5f), fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.width(8.dp))
                                StatusBadge("Yes ${pm.yesProbPct}%", c.win, c.winDim)
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text("Data Feeds & Sync Health", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f))
                    snapshot.providerHealth.forEach { ph ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ph.name, color = c.text, fontSize = scaledSp(12.5f), fontWeight = FontWeight.Bold)
                                Text(ph.note, color = c.textMuted, fontSize = scaledSp(11f))
                            }
                            val (stColor, stDim) = when (ph.status) {
                                "LIVE" -> c.win to c.winDim
                                "CIRCUIT_OPEN" -> c.loss to c.lossDim
                                else -> c.draw to c.drawDim
                            }
                            StatusBadge(ph.status, stColor, stDim)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
