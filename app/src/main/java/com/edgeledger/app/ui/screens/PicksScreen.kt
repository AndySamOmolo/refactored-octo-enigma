package com.edgeledger.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.ui.components.MetricTile
import com.edgeledger.app.ui.components.SectionCard
import com.edgeledger.app.ui.components.StatusBadge
import com.edgeledger.app.ui.components.TokenPill
import com.edgeledger.app.ui.components.scaledSp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale
import java.util.UUID
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
    var showLogDialog by remember { mutableStateOf(false) }
    var showResetPaperConfirm by remember { mutableStateOf(false) }
    var cashOutTarget by remember { mutableStateOf<PersonalBetEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonalBetEntity?>(null) }
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

    fun buildCsvPayload(): String {
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
        return header + body
    }

    fun buildJsonPayload(): String {
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
        return exportJsonCodec.encodeToString(dtos)
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            val payload = buildCsvPayload()
            val count = bets.size
            app.appScope.launch(Dispatchers.IO) {
                val res = runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Could not open output stream")
                    stream.use { os -> os.write(payload.toByteArray(Charsets.UTF_8)) }
                }
                withContext(Dispatchers.Main) {
                    val msg = if (res.isSuccess) "Saved CSV backup ($count picks)." else "Could not save CSV file."
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val payload = buildJsonPayload()
            val count = bets.size
            app.appScope.launch(Dispatchers.IO) {
                val res = runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Could not open output stream")
                    stream.use { os -> os.write(payload.toByteArray(Charsets.UTF_8)) }
                }
                withContext(Dispatchers.Main) {
                    val msg = if (res.isSuccess) "Saved JSON backup ($count picks)." else "Could not save JSON file."
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun exportCsv() {
        val stamp = Instant.now().toString().take(10)
        csvExportLauncher.launch("edgeledger-picks-$stamp.csv")
    }

    fun exportJson() {
        val stamp = Instant.now().toString().take(10)
        jsonExportLauncher.launch("edgeledger-picks-$stamp.json")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.itemGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("My Saved Picks", color = c.text, fontSize = scaledSp(19f), fontWeight = FontWeight.ExtraBold)
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
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(if (isPaper) "Paper Bal" else "Balance", "$${(baseBalance + netPnl).roundToInt()}", null, Modifier.weight(1f).fillMaxHeight())
                MetricTile(
                    "Total Profit",
                    if (netPnl >= 0) "+$${netPnl.roundToInt()}" else "-$${kotlin.math.abs(netPnl.roundToInt())}",
                    avgClv?.let { "CLV ${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.1f", it)}%" },
                    Modifier.weight(1f),
                )
                MetricTile("Win Rate", "$winRate%", "$won/$settled settled", Modifier.weight(1f).fillMaxHeight())
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (isPaper) "Paper" else "Real"} Ledger (${bets.size})", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(15.5f))
                    Button(
                        onClick = { showLogDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                        shape = tokens.shapes.button,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("+ Log Pick", fontWeight = FontWeight.ExtraBold, fontSize = scaledSp(11.5f))
                    }
                }
                if (bets.isNotEmpty() || isPaper) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bets.isNotEmpty()) {
                            OutlinedButton(onClick = { exportCsv() }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Text("Export CSV", color = c.accent, fontWeight = FontWeight.Bold, fontSize = scaledSp(11.5f))
                            }
                            OutlinedButton(onClick = { exportJson() }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Text("Export JSON", color = c.accent, fontWeight = FontWeight.Bold, fontSize = scaledSp(11.5f))
                            }
                        }
                        if (isPaper) {
                            OutlinedButton(onClick = { showResetPaperConfirm = true }, shape = tokens.shapes.button, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Text("Reset Paper", color = c.loss, fontWeight = FontWeight.Bold, fontSize = scaledSp(11.5f))
                            }
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
                            "CASH_OUT" -> c.accent to c.accentDim
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
                                    app.appScope.launch { app.database.dao().updateClosingOdds(bet.id, v) }
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
                            "PENDING" to "Pending",
                        )
                        items(states, key = { it.first }) { (code, lbl) ->
                            OutlinedButton(
                                onClick = {
                                    if (code == "CASH_OUT") {
                                        cashOutTarget = bet
                                    } else {
                                        val pnl = computeSettlementPnl(bet, code)
                                        app.appScope.launch { app.database.dao().updateBetStatus(bet.id, code, pnl) }
                                    }
                                },
                                shape = tokens.shapes.button,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) { Text(lbl, fontSize = scaledSp(11f), fontWeight = FontWeight.Bold) }
                        }
                        item(key = "remove") {
                            OutlinedButton(
                                onClick = { deleteTarget = bet },
                                shape = tokens.shapes.button,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) { Text("Remove", fontSize = scaledSp(11f), color = c.textMuted) }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showResetPaperConfirm) {
        AlertDialog(
            onDismissRequest = { showResetPaperConfirm = false },
            title = { Text("Reset Paper Bankroll?") },
            text = { Text("This deletes all ${bets.size} paper-trading picks and resets your paper balance to $1,000.") },
            confirmButton = {
                Button(
                    onClick = {
                        val paperIds = bets.map { it.id }
                        scope.launch { paperIds.forEach { app.database.dao().deleteBet(it) } }
                        app.prefsRepo.updatePrefs { it.copy(paperBankroll = 1000.0) }
                        showResetPaperConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.loss, contentColor = c.onLoss),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetPaperConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showLogDialog) {
        var pickSport by remember { mutableStateOf(Sport.FOOTBALL) }
        var matchupInput by remember { mutableStateOf("") }
        var selectionInput by remember { mutableStateOf("") }
        var oddsInput by remember { mutableStateOf("2.00") }
        var stakeInput by remember { mutableStateOf("25") }
        var bookmakerInput by remember { mutableStateOf("Manual") }

        val parsedOdds = oddsInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 1.0 }
        val parsedStake = stakeInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val canSave = matchupInput.isNotBlank() && selectionInput.isNotBlank() && parsedOdds != null && parsedStake != null

        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Log ${if (isPaper) "Paper" else "Real"} Pick") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Sport.entries, key = { it.name }) { sp ->
                            TokenPill(sp.shortCode, pickSport == sp, { pickSport = sp })
                        }
                    }
                    OutlinedTextField(value = matchupInput, onValueChange = { matchupInput = it }, label = { Text("Matchup (e.g. Arsenal vs Chelsea)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = selectionInput, onValueChange = { selectionInput = it }, label = { Text("Pick (e.g. Arsenal -0.5)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = oddsInput, onValueChange = { oddsInput = it }, label = { Text("Decimal Odds") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = stakeInput, onValueChange = { stakeInput = it }, label = { Text("Stake ($)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = bookmakerInput, onValueChange = { bookmakerInput = it }, label = { Text("Bookmaker") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        val now = Instant.now()
                        val odds = parsedOdds ?: return@Button
                        val stake = parsedStake ?: return@Button
                        app.appScope.launch {
                            app.database.dao().upsertBet(
                                PersonalBetEntity(
                                    id = "manual-${UUID.randomUUID()}",
                                    createdAtIso = now.toString(),
                                    fixtureId = null,
                                    sport = pickSport.label,
                                    leagueName = pickSport.label,
                                    matchup = matchupInput.trim(),
                                    selection = selectionInput.trim(),
                                    decimalOdds = odds,
                                    closingOdds = null,
                                    modelProb = (1.0 / odds).coerceIn(0.01, 0.99),
                                    stakeAmount = stake,
                                    status = "PENDING",
                                    notes = "",
                                    isPaper = isPaper,
                                    bookmaker = bookmakerInput.trim().ifBlank { "Manual" },
                                    settledPnl = null,
                                    createdAtEpochMs = now.toEpochMilli(),
                                )
                            )
                        }
                        showLogDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text("Save Pick") }
            },
            dismissButton = { TextButton(onClick = { showLogDialog = false }) { Text("Cancel") } },
        )
    }

    cashOutTarget?.let { target ->
        var payoutStr by remember(target.id) {
            val initial = (target.stakeAmount + (target.settledPnl ?: 0.0)).coerceAtLeast(0.0)
            mutableStateOf(String.format(Locale.US, "%.2f", initial))
        }
        val parsedPayout = payoutStr.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        AlertDialog(
            onDismissRequest = { cashOutTarget = null },
            title = { Text("Record Cash-Out Payout") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stake was $${String.format(Locale.US, "%.2f", target.stakeAmount)}. Enter total amount returned:", fontSize = scaledSp(12.5f))
                    OutlinedTextField(
                        value = payoutStr,
                        onValueChange = { payoutStr = it },
                        label = { Text("Total Cash-Out Returned ($)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedPayout != null,
                    onClick = {
                        val net = (parsedPayout ?: 0.0) - target.stakeAmount
                        app.appScope.launch { app.database.dao().updateBetStatus(target.id, "CASH_OUT", net) }
                        cashOutTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text("Save Cash-Out") }
            },
            dismissButton = { TextButton(onClick = { cashOutTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove Pick?") },
            text = { Text("Permanently remove \"${target.selection}\" from your ledger?") },
            confirmButton = {
                Button(
                    onClick = {
                        app.appScope.launch { app.database.dao().deleteBet(target.id) }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.loss, contentColor = c.onLoss),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}
