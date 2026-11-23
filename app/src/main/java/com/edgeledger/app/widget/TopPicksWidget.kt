package com.edgeledger.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.edgeledger.app.MainActivity
import com.edgeledger.app.data.AppDatabase
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.ui.components.shortTeamName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

private val MATCH_ID_PARAM_KEY = ActionParameters.Key<String>(MainActivity.EXTRA_MATCH_ID)

private data class WidgetPickRow(
    val matchId: String?,
    val headline: String,
    val badge: String?,
)

class RefreshTopPicksWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TopPicksWidget.refreshNonce.value = System.currentTimeMillis()
        TopPicksWidget().update(context, glanceId)
    }
}

class TopPicksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialRows = loadTopRows(context)

        provideContent {
            var rows by remember { mutableStateOf(initialRows) }
            val nonce by refreshNonce.collectAsState()
            LaunchedEffect(nonce) {
                if (nonce > 0L) {
                    rows = loadTopRows(context)
                }
            }
            GlanceTheme {
                WidgetContent(rows)
            }
        }
    }

    private suspend fun loadTopRows(context: Context): List<WidgetPickRow> {
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
        }?.sortedWith(
            compareByDescending<com.edgeledger.app.model.MatchFixture> {
                val ev = it.bestValueEdgePct
                if (ev != null && ev.isFinite() && ev > 0.0) ev else -999.0
            }.thenByDescending { it.markets.topPicks.firstOrNull()?.chancePct ?: 0 }
        )

        return upcoming?.take(3)?.map { fx ->
            val pick = fx.markets.topPicks.firstOrNull()
            val home = shortTeamName(fx.homeTeam)
            val away = shortTeamName(fx.awayTeam)
            val evBadge = fx.bestValueEdgePct?.takeIf { it.isFinite() && it >= 1.5 }?.let {
                "+${String.format(Locale.US, "%.1f", it)}% EV"
            }
            WidgetPickRow(
                matchId = fx.id,
                headline = "$home vs $away · ${pick?.label ?: "View Match"} (${pick?.chancePct ?: 0}%)",
                badge = evBadge,
            )
        }?.takeIf { it.isNotEmpty() }
            ?: listOf(WidgetPickRow(null, "Tap to open EdgeLedger & sync live picks", null))
    }

    @Composable
    private fun WidgetContent(rows: List<WidgetPickRow>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.surface)
                .clickable(actionStartActivity<MainActivity>())
                .padding(12.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "EdgeLedger · Top Picks",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = "Refresh",
                    modifier = GlanceModifier
                        .clickable(actionRunCallback<RefreshTopPicksWidgetAction>())
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            rows.forEach { row ->
                val rowAction = if (!row.matchId.isNullOrBlank()) {
                    actionStartActivity<MainActivity>(
                        actionParametersOf(MATCH_ID_PARAM_KEY to row.matchId),
                    )
                } else {
                    actionStartActivity<MainActivity>()
                }
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(rowAction)
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "• ${row.headline}",
                        maxLines = 2,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 12.sp,
                        ),
                    )
                    if (!row.badge.isNullOrBlank()) {
                        Text(
                            text = " ${row.badge}",
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        internal val refreshNonce = MutableStateFlow(0L)
    }
}

class TopPicksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopPicksWidget()
}
