package com.edgeledger.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.edgeledger.app.MainActivity
import com.edgeledger.app.data.AppDatabase
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.ui.components.shortTeamName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.withContext

class TopPicksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = withContext(Dispatchers.IO) {
            try {
                SyncRepository.readSnapshotFromDisk(context, AppDatabase.getInstance(context))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
        }
        val nowMs = System.currentTimeMillis()
        val upcoming = snap?.fixtures?.filter { fx ->
            val ts = runCatching { OffsetDateTime.parse(fx.kickoffIso.trim()).toInstant().toEpochMilli() }
                .getOrElse { runCatching { Instant.parse(fx.kickoffIso.trim()).toEpochMilli() }.getOrNull() }
            ts == null || ts >= nowMs - 2L * 3600L * 1000L
        }?.sortedByDescending { it.markets.topPicks.firstOrNull()?.chancePct ?: 0 }
        val topRows = upcoming?.take(3)?.map { fx ->
            val pick = fx.markets.topPicks.firstOrNull()
            val home = shortTeamName(fx.homeTeam)
            val away = shortTeamName(fx.awayTeam)
            "$home vs $away · ${pick?.label ?: "View Match"} (${pick?.chancePct ?: 0}%)"
        }?.takeIf { it.isNotEmpty() }
            ?: listOf("Tap to open EdgeLedger & sync live picks")

        provideContent {
            WidgetContent(topRows)
        }
    }

    @Composable
    private fun WidgetContent(rows: List<String>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF0A0E17))
                .clickable(actionStartActivity<MainActivity>())
                .padding(12.dp),
        ) {
            Text(
                text = "EdgeLedger · Top Picks",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF3B82F6)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            rows.forEach { line ->
                Text(
                    text = "• $line",
                    maxLines = 2,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFF8FAFC)),
                        fontSize = 12.sp,
                    ),
                )
                Spacer(GlanceModifier.height(4.dp))
            }
        }
    }
}

class TopPicksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopPicksWidget()
}
