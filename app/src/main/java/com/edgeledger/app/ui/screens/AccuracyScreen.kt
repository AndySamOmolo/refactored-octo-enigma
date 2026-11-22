package com.edgeledger.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.tabularStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AccuracyScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val lockedPicks by app.database.dao().observeLockedPicks().collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Track Record & Accuracy", color = c.text, fontSize = scaledSp(20f), fontWeight = FontWeight.ExtraBold)
                Text("Tested strictly chronologically on past games without peeking ahead.", color = c.textMuted, fontSize = scaledSp(13f))
            }
        }

        if (snapshot.trackRecords.isEmpty()) {
            item {
                SectionCard {
                    Text("No backtest history yet", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    Text(
                        "Tap Update on the Games tab to download live historical datasets and run walk-forward accuracy tests on your phone.",
                        color = c.textMuted,
                        fontSize = scaledSp(12.5f),
                    )
                }
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
                val roiSub = if (rec.hasGradedOdds) (if (rec.simulatedProfitPct >= 0.0) "Profitable" else "Unprofitable") else null
                val roiSubColor = if (rec.simulatedProfitPct >= 0.0) c.win else c.loss

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f), modifier = Modifier.weight(1f))
                    StatusBadge(text = badgeTxt, color = badgeFg, containerColor = badgeBg)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Skill Score", "${rec.accuracyScore}/100", skillSub, Modifier.weight(1f), subtitleColor = skillSubColor)
                    MetricTile("Games", "${rec.gamesTested}", null, Modifier.weight(1f))
                    MetricTile(
                        "Sim ROI",
                        if (rec.hasGradedOdds) "${if (rec.simulatedProfitPct >= 0) "+" else ""}${String.format(Locale.US, "%.1f", rec.simulatedProfitPct)}%" else "N/A",
                        roiSub,
                        Modifier.weight(1f),
                        subtitleColor = roiSubColor,
                    )
                }
                if (rec.reliabilityBins.isNotEmpty()) {
                    Text("When we say X%, how often do they actually win?", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.SemiBold)
                    rec.reliabilityBins.forEach { bin ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(bin.rangeLabel, style = tabularStyle(11f, FontWeight.Bold, c.textSecondary), modifier = Modifier.width(52.dp))
                            Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceRaised)) {
                                if (bin.actualWinPct > 0) Box(modifier = Modifier.fillMaxWidth((bin.actualWinPct / 100f).coerceIn(0.03f, 1f)).fillMaxHeight().background(c.accent))
                            }
                            Text("${bin.actualWinPct}% (${bin.count})", style = tabularStyle(11f, FontWeight.Medium, c.textMuted), modifier = Modifier.width(68.dp))
                        }
                    }
                }
            }
        }

        if (lockedPicks.isNotEmpty()) {
            item {
                SectionCard {
                    Text("Locked Pre-Match Predictions (${lockedPicks.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                    Text("Timestamped and hash-locked automatically before kickoff so history stays honest.", color = c.textMuted, fontSize = scaledSp(12f))
                    lockedPicks.take(8).forEach { lp ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${lp.matchup} · ${lp.pickLabel}", color = c.text, fontSize = scaledSp(12.5f), fontWeight = FontWeight.Bold)
                                Text("Lock #${lp.hashSha256.take(10)} · ${lp.lockedAtIso.take(10)}", color = c.textMuted, fontSize = scaledSp(11f))
                            }
                            Text("${lp.chancePct}%", style = tabularStyle(13f, FontWeight.ExtraBold, c.accent))
                        }
                    }
                }
            }
        }

        if (snapshot.predictionMarkets.isNotEmpty()) {
            item {
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Live Sports Markets (${snapshot.predictionMarkets.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f), modifier = Modifier.weight(1f))
                        val activePmSources = snapshot.predictionMarkets.map { it.source }.distinct().joinToString(" · ")
                        StatusBadge(activePmSources.ifBlank { "Sports Markets" }, c.sharpBadge, c.sharpBadgeDim)
                    }
                    Text("Live public implied probabilities synced directly on your phone.", color = c.textMuted, fontSize = scaledSp(12f))
                    snapshot.predictionMarkets.take(12).forEach { pm ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pm.question, color = c.text, fontSize = scaledSp(12.5f), fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(pm.source, color = c.accent, fontSize = scaledSp(11f), fontWeight = FontWeight.SemiBold)
                            }
                            StatusBadge("Yes ${pm.yesProbPct}%", c.win, c.winDim)
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("Data Feeds & Sync Health", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f))
                snapshot.providerHealth.forEach { ph ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ph.name, color = c.text, fontSize = scaledSp(13f), fontWeight = FontWeight.Bold)
                            Text(ph.note, color = c.textMuted, fontSize = scaledSp(11.5f))
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

        item { Spacer(Modifier.height(88.dp)) }
    }
}
