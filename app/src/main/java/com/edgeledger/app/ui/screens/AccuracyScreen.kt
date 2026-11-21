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
import kotlin.math.roundToInt
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.scaledSp
import com.edgeledger.app.ui.components.tabularStyle

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

        items(snapshot.trackRecords, key = { "${it.sport.name}-${it.leagueId}" }) { rec ->
            val cleanLeague = SyncRepository.readableLeagueName(rec.leagueId, rec.sport)
            val title = if (cleanLeague.equals(rec.sport.label, ignoreCase = true)) {
                cleanLeague
            } else {
                "${rec.sport.label} · $cleanLeague"
            }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f), modifier = Modifier.weight(1f))
                    StatusBadge(
                        text = if (rec.beatsSimpleGuess) "Strong Record" else "Building History",
                        color = if (rec.beatsSimpleGuess) c.win else c.draw,
                        containerColor = if (rec.beatsSimpleGuess) c.winDim else c.drawDim,
                    )
                }
                val approxWins = ((rec.accuracyScore / 100.0) * rec.gamesTested).roundToInt().coerceIn(0, rec.gamesTested)
                val sig = OddsMath.computeSignificance(approxWins, rec.gamesTested, if (rec.sport.hasDraw) 0.44 else 0.50)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Accuracy", "${rec.accuracyScore}/100", sig.plainLabel, Modifier.weight(1f))
                    MetricTile("Games", "${rec.gamesTested}", null, Modifier.weight(1f))
                    MetricTile("Profit", "${if (rec.simulatedProfitPct >= 0) "+" else ""}${rec.simulatedProfitPct}%", null, Modifier.weight(1f))
                }
                if (rec.reliabilityBins.isNotEmpty()) {
                    Text("When we say X%, how often do they actually win?", color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.SemiBold)
                    rec.reliabilityBins.forEach { bin ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(bin.rangeLabel, style = tabularStyle(11f, FontWeight.Bold, c.textSecondary), modifier = Modifier.width(52.dp))
                            Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceRaised)) {
                                Box(modifier = Modifier.fillMaxWidth((bin.actualWinPct / 100f).coerceIn(0.05f, 1f)).fillMaxHeight().background(c.accent))
                            }
                            Text("${bin.actualWinPct}% (${bin.count})", style = tabularStyle(11f, FontWeight.Medium, c.textMuted), modifier = Modifier.width(68.dp))
                        }
                    }
                }
            }
        }

        // Immutable SHA-256 Locked Pick Ledger (TR-02)
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

        // Live Prediction Markets (Polymarket & Kalshi)
        if (snapshot.predictionMarkets.isNotEmpty()) {
            item {
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Live Markets (${snapshot.predictionMarkets.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15f), modifier = Modifier.weight(1f))
                        StatusBadge("Polymarket · Kalshi", c.sharpBadge, c.sharpBadgeDim)
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

        // Data Feed Health & Circuit Breaker Status (DT-03, DT-05)
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

        item { Spacer(Modifier.height(20.dp)) }
    }
}
