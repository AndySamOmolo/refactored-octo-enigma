package com.edgeledger.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.data.PersonalBetEntity
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TokenPill
import com.edgeledger.app.ui.components.scaledSp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.roundToInt

@Serializable
private data class ExportedBetDto(
    val id: String,
    val date: String,
    val mode: String,
    val sport: String,
    val league: String,
    val matchup: String,
    val selection: String,
    val bookmaker: String,
    val odds: Double,
    val closingOdds: Double?,
    val stake: Double,
    val status: String,
    val settledPnl: Double?,
)

private val exportJsonCodec = Json { prettyPrint = true; encodeDefaults = true }

private fun csvEscapeSafe(raw: String): String {
    val formulaSafe = if (raw.isNotEmpty() && raw.first() in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
        "'$raw"
    } else {
        raw
    }
    return "\"${formulaSafe.replace("\"", "\"\"")}\""
}

private fun computeSettlementPnl(bet: PersonalBetEntity, status: String): Double? = when (status) {
    "WON" -> bet.stakeAmount * (bet.decimalOdds - 1.0)
    "HALF_WIN" -> (bet.stakeAmount * (bet.decimalOdds - 1.0)) * 0.5
    "LOST" -> -bet.stakeAmount
    "HALF_LOSS" -> -bet.stakeAmount * 0.5
    "PUSH", "VOID" -> 0.0
    "CASH_OUT" -> bet.settledPnl ?: 0.0
    else -> null
}

@Composable
fun PicksScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()
    val allBets by app.database.dao().observePersonalBets().collectAsStateWithLifecycle(initialValue = emptyList())

    val isPaper = prefs.isPaperTrading
    val bets = remember(allBets, isPaper) { allBets.filter { it.isPaper == isPaper } }
    val baseBalance = if (isPaper) prefs.paperBankroll else prefs.bankroll

    var won = 0
    var settled = 0
    var netPnl = 0.0
    var clvSum = 0.0
    var clvCount = 0

    for (b in bets) {
        when (b.status) {
            "WON" -> { settled++; won++; netPnl += b.settledPnl ?: (b.stakeAmount * (b.decimalOdds - 1.0)) }
            "HALF_WIN" -> { settled++; won++; netPnl += b.settledPnl ?: ((b.stakeAmount * (b.decimalOdds - 1.0)) * 0.5) }
            "LOST" -> { settled++; netPnl += b.settledPnl ?: (-b.stakeAmount) }
            "HALF_LOSS" -> { settled++; netPnl += b.settledPnl ?: (-b.stakeAmount * 0.5) }
            "CASH_OUT" -> { settled++; netPnl += b.settledPnl ?: 0.0 }
        }
        b.closingOdds?.takeIf { it > 1.0 }?.let { close ->
            clvSum += OddsMath.computeClvPct(b.decimalOdds, close)
            clvCount++
        }
    }
    val winRate = if (settled > 0) ((won.toDouble() / settled) * 100.0).roundToInt() else 0
    val avgClv = if (clvCount > 0) clvSum / clvCount else null

    fun exportCsv() {
        val header = "Date,Mode,Sport,League,Matchup,Pick,Bookmaker,Odds,ClosingOdds,Stake,Status,SettledPnL\n"
        val body = bets.joinToString("\n") {
            listOf(
                csvEscapeSafe(it.createdAtIso.take(10)),
                if (it.isPaper) "PAPER" else "REAL",
                csvEscapeSafe(it.sport),
                csvEscapeSafe(it.leagueName),
                csvEscapeSafe(it.matchup),
                csvEscapeSafe(it.selection),
                csvEscapeSafe(it.bookmaker),
                String.format(Locale.US, "%.2f", it.decimalOdds),
                it.closingOdds?.let { co -> String.format(Locale.US, "%.2f", co) } ?: "",
                String.format(Locale.US, "%.2f", it.stakeAmount),
                it.status,
                it.settledPnl?.let { pnl -> String.format(Locale.US, "%.2f", pnl) } ?: "",
            ).joinToString(",")
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "EdgeLedger Picks Backup (CSV)")
            putExtra(Intent.EXTRA_TEXT, header + body)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Picks CSV"))
    }

    fun exportJson() {
        val dtos = bets.map {
            ExportedBetDto(
                id = it.id,
                date = it.createdAtIso,
                mode = if (it.isPaper) "paper" else "real",
                sport = it.sport,
                league = it.leagueName,
                matchup = it.matchup,
                selection = it.selection,
                bookmaker = it.bookmaker,
                odds = it.decimalOdds,
                closingOdds = it.closingOdds,
                stake = it.stakeAmount,
                status = it.status,
                settledPnl = it.settledPnl,
            )
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "EdgeLedger Picks Backup (JSON)")
            putExtra(Intent.EXTRA_TEXT, exportJsonCodec.encodeToString(dtos))
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Picks JSON"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("My Saved Picks", color = c.text, fontSize = scaledSp(20f), fontWeight = FontWeight.ExtraBold)
                Text("Switch between Real Balance and Paper-Trading shadow bankroll to test strategies.", color = c.textMuted, fontSize = scaledSp(12.5f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TokenPill(
                    label = "Real Balance",
                    selected = !isPaper,
                    onClick = { app.prefsRepo.updatePrefs { it.copy(isPaperTrading = false) } },
                    modifier = Modifier.weight(1f),
                )
                TokenPill(
                    label = "Paper (Virtual)",
                    selected = isPaper,
                    onClick = { app.prefsRepo.updatePrefs { it.copy(isPaperTrading = true) } },
                    activeColor = c.sharpBadge,
                    activeContainer = c.sharpBadgeDim,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(if (isPaper) "Paper Bal" else "Balance", "$${(baseBalance + netPnl).roundToInt()}", null, Modifier.weight(1f))
                MetricTile(
                    "Total Profit",
                    "${if (netPnl >= 0) "+" else ""}$${netPnl.roundToInt()}",
                    avgClv?.let { "CLV ${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.1f", it)}%" },
                    Modifier.weight(1f),
                )
                MetricTile("Win Rate", "$winRate%", "$won/$settled settled", Modifier.weight(1f))
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${if (isPaper) "Paper" else "Real"} Ledger (${bets.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15.5f))
                if (bets.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { exportCsv() }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("CSV", color = c.accent, fontWeight = FontWeight.Bold, fontSize = scaledSp(11.5f))
                        }
                        OutlinedButton(onClick = { exportJson() }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("JSON", color = c.accent, fontWeight = FontWeight.Bold, fontSize = scaledSp(11.5f))
                        }
                    }
                }
            }
        }

        if (bets.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        if (isPaper) "No paper-trading picks yet" else "No picks saved yet",
                        color = c.text,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = scaledSp(15f),
                    )
                    Text(
                        "Open any game in the Games tab and tap Save on a match pick or player prop to track profit and closing line value here.",
                        color = c.textMuted,
                        fontSize = scaledSp(13f),
                    )
                }
            }
        } else {
            items(bets, key = { it.id }) { bet ->
                var closingInput by remember(bet.id, bet.closingOdds) {
                    mutableStateOf(bet.closingOdds?.let { String.format(Locale.US, "%.2f", it) } ?: "")
                }
                val clvPct = bet.closingOdds?.takeIf { it > 1.0 }?.let { close ->
                    OddsMath.computeClvPct(bet.decimalOdds, close)
                }

                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(bet.matchup, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(14.5f), modifier = Modifier.weight(1f))
                        val (stColor, stDim) = when (bet.status) {
                            "WON", "HALF_WIN" -> c.win to c.winDim
                            "LOST", "HALF_LOSS" -> c.loss to c.lossDim
                            "PUSH", "VOID" -> c.textSecondary to c.surfaceRaised
                            else -> c.pending to c.pendingDim
                        }
                        StatusBadge(bet.status.replace("_", " "), stColor, stDim)
                    }
                    Text(
                        "${bet.selection} @ ${OddsMath.formatOdds(bet.decimalOdds, prefs.oddsFormat)} · Stake $${bet.stakeAmount.roundToInt()} · Win Chance ${(bet.modelProb * 100).roundToInt()}%",
                        color = c.textSecondary,
                        fontSize = scaledSp(12.5f),
                    )
                    if (clvPct != null) {
                        StatusBadge(
                            text = "Closing Line Value: ${if (clvPct >= 0) "+" else ""}${String.format(Locale.US, "%.1f", clvPct)}%",
                            color = if (clvPct >= 0) c.win else c.loss,
                            containerColor = if (clvPct >= 0) c.winDim else c.lossDim,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = closingInput,
                            onValueChange = { closingInput = it },
                            label = { Text("Closing Odds") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                closingInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 1.0 }?.let { v ->
                                    scope.launch { app.database.dao().updateClosingOdds(bet.id, v) }
                                }
                            },
                            shape = tokens.shapes.button,
                        ) { Text("Save CLV", color = c.accent, fontSize = scaledSp(11.5f), fontWeight = FontWeight.Bold) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val states = listOf(
                            "WON" to "Won",
                            "LOST" to "Lost",
                            "PUSH" to "Push",
                            "HALF_WIN" to "½ Win",
                            "HALF_LOSS" to "½ Loss",
                            "VOID" to "Void",
                            "CASH_OUT" to "Cash-Out",
                        )
                        items(states, key = { it.first }) { (code, lbl) ->
                            OutlinedButton(
                                onClick = {
                                    val pnl = computeSettlementPnl(bet, code)
                                    scope.launch { app.database.dao().updateBetStatus(bet.id, code, pnl) }
                                },
                                shape = tokens.shapes.button,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) { Text(lbl, fontSize = scaledSp(11f), fontWeight = FontWeight.Bold) }
                        }
                        item(key = "remove") {
                            OutlinedButton(
                                onClick = { scope.launch { app.database.dao().deleteBet(bet.id) } },
                                shape = tokens.shapes.button,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) { Text("Remove", fontSize = scaledSp(11f), color = c.textMuted) }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(88.dp)) }
    }
}
