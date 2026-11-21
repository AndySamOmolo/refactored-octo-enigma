package com.edgeledger.app.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgeledger.app.EdgeLedgerApp
import com.edgeledger.app.model.OddsDisplayFormat
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.theme.DensityPreset
import com.edgeledger.app.theme.ShapeFamily
import com.edgeledger.app.theme.ThemePresets
import com.edgeledger.app.theme.parseHexColor
import com.edgeledger.app.ui.components.SectionCard

@Composable
fun ThemeStudioScreen(app: EdgeLedgerApp) {
    val tokens = AppTheme.tokens
    val c = tokens.colors
    val context = LocalContext.current
    val config by app.prefsRepo.theme.collectAsStateWithLifecycle()
    val prefs by app.prefsRepo.prefs.collectAsStateWithLifecycle()

    var importJsonText by remember { mutableStateOf("") }
    var importFeedback by remember { mutableStateOf<String?>(null) }

    fun shareThemeJson() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "EdgeLedger Theme (${config.name})")
            putExtra(Intent.EXTRA_TEXT, app.prefsRepo.exportThemeJson())
        }
        context.startActivity(Intent.createChooser(intent, "Export Theme JSON"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.padMd),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Live Theme Studio", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Instant zero-restart visual customization", color = c.textMuted, fontSize = 12.5.sp)
                }
                Text(
                    "Contrast ${tokens.textSurfaceContrast}:1",
                    color = if (tokens.textSurfaceContrast >= 4.5) c.win else c.loss,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        // Undo / Redo / Safe Mode Reset Bar (ED-04, AX-03)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { app.prefsRepo.undoTheme() }, enabled = app.prefsRepo.canUndo, modifier = Modifier.weight(1f)) {
                    Text("Undo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { app.prefsRepo.redoTheme() }, enabled = app.prefsRepo.canRedo, modifier = Modifier.weight(1f)) {
                    Text("Redo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { app.prefsRepo.resetTheme() }, modifier = Modifier.weight(1f)) {
                    Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.loss)
                }
            }
        }

        // Theme Presets + Android 12+ Dynamic Color (CL-02, CL-03, CL-08)
        item {
            SectionCard {
                Text("Theme Presets", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                ThemePresets.ALL.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { preset ->
                            FilterChip(
                                selected = config.id == preset.id,
                                onClick = { app.prefsRepo.updateTheme { preset } },
                                label = { Text(preset.name, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accentDim, selectedLabelColor = c.accent),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wallpaper Dynamic Color", color = c.text, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            Text("Match Android 12+ Material You wallpaper palette.", color = c.textMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = config.useDynamicColor,
                            onCheckedChange = { enabled -> app.prefsRepo.updateTheme { it.copy(useDynamicColor = enabled) } },
                        )
                    }
                }
            }
        }

        // Accent Swatches (CL-04)
        item {
            SectionCard {
                Text("Accent Seed Color", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ThemePresets.ACCENT_SWATCHES.forEach { (_, hex) ->
                        val active = config.accentHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex))
                                .border(if (active) 3.dp else 0.dp, if (active) c.text else Color.Transparent, CircleShape)
                                .clickable { app.prefsRepo.updateTheme { it.copy(accentHex = hex, id = "custom", useDynamicColor = false) } },
                        )
                    }
                }
            }
        }

        // Shape, Density & Text Scale (SH-01, SH-03, TY-04)
        item {
            SectionCard {
                Text("Card Shape & Density", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShapeFamily.entries.forEach { sf ->
                        FilterChip(
                            selected = config.shapeFamily == sf,
                            onClick = { app.prefsRepo.updateTheme { it.copy(shapeFamily = sf) } },
                            label = { Text(sf.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DensityPreset.entries.forEach { dp ->
                        FilterChip(
                            selected = config.density == dp,
                            onClick = { app.prefsRepo.updateTheme { it.copy(density = dp) } },
                            label = { Text(dp.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text("Number & Text Scale: ${String.format("%.2fx", config.fontScale)}", color = c.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = config.fontScale,
                    onValueChange = { s -> app.prefsRepo.updateTheme { it.copy(fontScale = s) } },
                    valueRange = 0.85f..1.30f,
                )
            }
        }

        // Global Odds Display Format (OD-05)
        item {
            SectionCard {
                Text("Odds Display Format", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                OddsDisplayFormat.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { fmt ->
                            FilterChip(
                                selected = prefs.oddsFormat == fmt,
                                onClick = { app.prefsRepo.updatePrefs { it.copy(oddsFormat = fmt) } },
                                label = { Text(fmt.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // Export / Import .theme.json (SG-01, SG-02)
        item {
            SectionCard {
                Text("Backup & Import Theme (.theme.json)", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Button(
                    onClick = { shareThemeJson() },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export / Share Theme JSON", color = Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("Paste .theme.json to import") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (importJsonText.isNotBlank()) {
                    Button(
                        onClick = {
                            val ok = app.prefsRepo.importThemeJson(importJsonText)
                            importFeedback = if (ok) "Theme imported and applied!" else "Invalid theme JSON format."
                            if (ok) importJsonText = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = c.win),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Apply Imported Theme", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                importFeedback?.let { Text(it, color = c.win, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}
