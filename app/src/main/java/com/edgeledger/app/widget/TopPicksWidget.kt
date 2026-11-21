package com.edgeledger.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import com.edgeledger.app.data.AppDatabase
import com.edgeledger.app.data.AppModelSnapshot
import kotlinx.serialization.json.Json

class TopPicksWidget : GlanceAppWidget() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapEntity = runCatching { AppDatabase.getInstance(context).dao().getSnapshot() }.getOrNull()
        val snap = snapEntity?.let { runCatching { json.decodeFromString<AppModelSnapshot>(it.payloadJson) }.getOrNull() }
        val topRows = snap?.fixtures?.take(3)?.map { fx ->
            val pick = fx.markets.topPicks.firstOrNull()
            "${fx.homeTeam.substringBefore(" ")} vs ${fx.awayTeam.substringBefore(" ")} · ${pick?.label ?: "Open App"} (${pick?.chancePct ?: 50}%)"
        } ?: listOf("Open EdgeLedger to load today's top picks")

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
