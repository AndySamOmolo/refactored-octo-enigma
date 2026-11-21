package com.edgeledger.app.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.model.RiskStyle
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PicksScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()
    val bets by app.database.dao().observePersonalBets().collectAsStateWithLifecycle(initialValue = emptyList())

    var balanceInput by remember(prefs.bankroll) { mutableStateOf(prefs.bankroll.toInt().toString()) }

    var won = 0
    var settled = 0
    var netPnl = 0.0
    for (b in bets) {
        when (b.status) {
            "WON" -> { settled++; won++; netPnl += b.stakeAmount * (b.decimalOdds - 1.0) }
            "LOST" -> { settled++; netPnl -= b.stakeAmount }
        }
    }
    val winRate = if (settled > 0) ((won.toDouble() / settled) * 100.0).roundToInt() else 0

    fun exportCsv() {
        val header = "Date,Sport,League,Matchup,Pick,Odds,Stake,Status\n"
        val body = bets.joinToString("\n") {
            "${it.createdAtIso.take(10)},${it.sport},\"${it.leagueName}\",\"${it.matchup}\",\"${it.selection}\",${it.decimalOdds},${it.stakeAmount},${it.status}"
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "EdgeLedger Picks Backup (CSV)")
            putExtra(Intent.EXTRA_TEXT, header + body)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Picks CSV"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.padMd),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Balance", "$${(prefs.bankroll + netPnl).roundToInt()}", null, Modifier.weight(1f))
                MetricTile("Total Profit", "${if (netPnl >= 0) "+" else ""}$${netPnl.roundToInt()}", null, Modifier.weight(1f))
                MetricTile("Win Rate", "$winRate%", "$won/$settled won", Modifier.weight(1f))
            }
        }

        item {
            SectionCard {
                Text("Budget & Weekly Auto-Update", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = balanceInput,
                        onValueChange = { balanceInput = it },
                        label = { Text("Starting Balance ($)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            balanceInput.toDoubleOrNull()?.takeIf { it >= 50.0 }?.let { v ->
                                app.prefsRepo.updatePrefs { it.copy(bankroll = v) }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                    ) {
                        Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Text("Suggested Bet Size Style", color = c.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RiskStyle.entries.forEach { style ->
                        FilterChip(
                            selected = prefs.riskStyle == style,
                            onClick = { app.prefsRepo.updatePrefs { it.copy(riskStyle = style) } },
                            label = { Text(style.label, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accentDim, selectedLabelColor = c.accent),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Update Once a Week", color = c.text, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Text("Refreshes all free feeds in the background every 7 days.", color = c.textMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = prefs.autoSyncWeekly,
                        onCheckedChange = { checked -> app.prefsRepo.updatePrefs { it.copy(autoSyncWeekly = checked) } },
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("My Saved Picks (${bets.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                if (bets.isNotEmpty()) {
                    OutlinedButton(onClick = { exportCsv() }) {
                        Text("Export CSV", color = c.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        if (bets.isEmpty()) {
            item {
                SectionCard {
                    Text("No picks saved yet. Open any game in the Games tab and tap Save to track it here.", color = c.textMuted, fontSize = 13.sp)
                }
            }
        } else {
            items(bets, key = { it.id }) { bet ->
                Surface(
                    color = c.surface,
                    border = BorderStroke(1.dp, c.border),
                    shape = RoundedCornerShape(tokens.cornerLg),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(bet.matchup, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            val stColor = when (bet.status) {
                                "WON" -> c.win
                                "LOST" -> c.loss
                                else -> c.draw
                            }
                            Text(bet.status, color = stColor, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
                        }
                        Text(
                            "${bet.selection} @ ${OddsMath.formatOdds(bet.decimalOdds, prefs.oddsFormat)} · Stake $${bet.stakeAmount.roundToInt()} · Win Chance ${(bet.modelProb * 100).roundToInt()}%",
                            color = c.textSecondary,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("WON" to "Won", "LOST" to "Lost", "VOID" to "Refund").forEach { (code, lbl) ->
                                OutlinedButton(
                                    onClick = { scope.launch { app.database.dao().updateBetStatus(bet.id, code) } },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text(lbl, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            OutlinedButton(
                                onClick = { scope.launch { app.database.dao().deleteBet(bet.id) } },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) {
                                Text("Remove", fontSize = 11.sp, color = c.textMuted)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}
