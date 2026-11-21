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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.tabularStyle

@Composable
fun AccuracyScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val snapshot by app.syncRepo.snapshot.collectAsStateWithLifecycle()
    val lockedPicks by app.database.dao().observeLockedPicks().collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.padMd),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Track Record & Accuracy", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Tested strictly chronologically on past games without peeking ahead.", color = c.textMuted, fontSize = 13.sp)
        }

        items(snapshot.trackRecords, key = { "${it.sport.name}-${it.leagueId}" }) { rec ->
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${rec.sport.label} · ${rec.leagueName}", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text(
                        if (rec.beatsSimpleGuess) "Strong Track Record" else "Building History",
                        color = if (rec.beatsSimpleGuess) c.win else c.draw,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Accuracy", "${rec.accuracyScore}/100", null, Modifier.weight(1f))
                    MetricTile("Games Tested", "${rec.gamesTested}", null, Modifier.weight(1f))
                    MetricTile("Sim Profit", "${if (rec.simulatedProfitPct >= 0) "+" else ""}${rec.simulatedProfitPct}%", null, Modifier.weight(1f))
                }
                if (rec.reliabilityBins.isNotEmpty()) {
                    Text("When we say X%, how often do they win?", color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                    Text("Locked Pre-Match Predictions (${lockedPicks.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("Timestamped and hash-locked automatically before kickoff so history stays honest.", color = c.textMuted, fontSize = 12.sp)
                    lockedPicks.take(8).forEach { lp ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${lp.matchup} · ${lp.pickLabel}", color = c.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text("Lock #${lp.hashSha256.take(10)} · ${lp.lockedAtIso.take(10)}", color = c.textMuted, fontSize = 11.sp)
                            }
                            Text("${lp.chancePct}%", style = tabularStyle(13f, FontWeight.ExtraBold, c.accent))
                        }
                    }
                }
            }
        }

        // Data Feed Health & Circuit Breaker Status (DT-03, DT-05)
        item {
            SectionCard {
                Text("Free Data Feeds & Health", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                snapshot.providerHealth.forEach { ph ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ph.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(ph.note, color = c.textMuted, fontSize = 11.5.sp)
                        }
                        val statusColor = when (ph.status) {
                            "LIVE" -> c.win
                            "CIRCUIT_OPEN" -> c.loss
                            else -> c.draw
                        }
                        Text(ph.status, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}
