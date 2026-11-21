package com.edgeledger.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgeledger.app.theme.AppTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val TEAM_COLORS = listOf(
    Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF059669), Color(0xFF7C3AED),
    Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFFDB2777), Color(0xFF4F46E5),
    Color(0xFF0D9488), Color(0xFFB91C1C), Color(0xFF1D4ED8), Color(0xFF9333EA),
)

@Composable
fun tabularStyle(sizeSp: Float, weight: FontWeight = FontWeight.Bold, color: Color = AppTheme.tokens.colors.text): TextStyle {
    val scale = AppTheme.tokens.fontScale
    return TextStyle(
        fontSize = (sizeSp * scale).sp,
        fontWeight = weight,
        color = color,
        fontFeatureSettings = "tnum",
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AppTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(t.cornerLg),
        color = t.colors.surface,
        border = BorderStroke(1.dp, t.colors.border),
    ) {
        Column(
            modifier = Modifier.padding(t.padMd),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun TeamAvatar(teamName: String, size: Dp = 40.dp) {
    val words = teamName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val initials = when {
        words.isEmpty() -> "??"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0][0]}${words[1][0]}".uppercase()
    }
    val color = TEAM_COLORS[abs(teamName.lowercase().hashCode()) % TEAM_COLORS.size]

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
fun ProbBar(
    homeLabel: String,
    awayLabel: String,
    homeProb: Double,
    awayProb: Double,
    drawProb: Double? = null,
    barHeight: Dp = 10.dp,
) {
    val c = AppTheme.tokens.colors
    val hasDraw = drawProb != null && drawProb > 0.0
    val sum = max(0.001, homeProb + awayProb + (drawProb ?: 0.0))
    val hPct = ((homeProb / sum) * 100.0).roundToInt().coerceIn(1, 98)
    val dPct = if (hasDraw) (((drawProb ?: 0.0) / sum) * 100.0).roundToInt().coerceIn(1, 98) else 0
    val aPct = (100 - hPct - dPct).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(homeLabel, color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hasDraw) {
                Text("Draw", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(0.6f))
            }
            Text(awayLabel, color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(c.surfaceRaised),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.weight(hPct.toFloat()).fillMaxHeight().background(c.accent))
            if (hasDraw) {
                Box(Modifier.weight(dPct.toFloat()).fillMaxHeight().background(c.draw))
            }
            Box(Modifier.weight(aPct.toFloat()).fillMaxHeight().background(c.loss))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$hPct%", style = tabularStyle(13f, FontWeight.ExtraBold, c.text))
            if (hasDraw) {
                Text("$dPct%", style = tabularStyle(13f, FontWeight.SemiBold, c.textSecondary))
            }
            Text("$aPct%", style = tabularStyle(13f, FontWeight.ExtraBold, c.text))
        }
    }
}

@Composable
fun FormDots(results: List<String>) {
    val c = AppTheme.tokens.colors
    val recent = results.takeLast(5)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        recent.forEach { res ->
            val bg = when (res) {
                "W" -> c.win
                "D" -> c.draw
                else -> c.loss
            }
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(res, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
fun StatCompareBar(
    label: String,
    homeValue: Double,
    awayValue: Double,
    suffix: String = "",
    decimals: Int = 1,
    higherIsBetter: Boolean = true,
) {
    val c = AppTheme.tokens.colors
    val total = max(0.001, homeValue + awayValue)
    val hWeight = (homeValue / total).toFloat().coerceIn(0.05f, 0.95f)
    val aWeight = (1f - hWeight).coerceAtLeast(0.05f)
    val fmt = "%.${decimals}f$suffix"
    val hLeads = if (higherIsBetter) homeValue >= awayValue else homeValue <= awayValue

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(fmt, homeValue),
                style = tabularStyle(12.5f, if (hLeads) FontWeight.ExtraBold else FontWeight.Medium, if (hLeads) c.text else c.textSecondary),
                modifier = Modifier.width(52.dp),
            )
            Text(
                label,
                color = c.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(fmt, awayValue),
                style = tabularStyle(12.5f, if (!hLeads) FontWeight.ExtraBold else FontWeight.Medium, if (!hLeads) c.text else c.textSecondary),
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.surfaceRaised),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.weight(hWeight).fillMaxHeight().background(c.accent.copy(alpha = if (hLeads) 1f else 0.45f)))
            Box(Modifier.weight(aWeight).fillMaxHeight().background(c.loss.copy(alpha = if (!hLeads) 1f else 0.45f)))
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val t = AppTheme.tokens
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(t.cornerMd))
            .background(t.colors.surfaceRaised)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = t.colors.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = tabularStyle(17f, FontWeight.ExtraBold, t.colors.text), maxLines = 1)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = t.colors.win, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
