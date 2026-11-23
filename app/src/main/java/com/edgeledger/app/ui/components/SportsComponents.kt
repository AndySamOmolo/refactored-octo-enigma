package com.edgeledger.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.edgeledger.app.engine.FeatureDriver
import com.edgeledger.app.engine.LineMovePoint
import com.edgeledger.app.engine.PlayerPropPrediction
import com.edgeledger.app.theme.AppTheme
import com.edgeledger.app.theme.HapticLevel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val TEAM_COLORS = listOf(
    Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF059669), Color(0xFF7C3AED),
    Color(0xFFEA580C), Color(0xFF0891B2), Color(0xFFDB2777), Color(0xFF4F46E5),
    Color(0xFF0D9488), Color(0xFFB91C1C), Color(0xFF1D4ED8), Color(0xFF9333EA),
)

private val LOCAL_KICKOFF_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.US)

private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

/**
 * Pure, crash-proof stat formatter (guards NaN/Infinity and avoids locale-dependent decimals).
 */
fun formatStatValue(value: Double, decimals: Int = 1, suffix: String = ""): String {
    if (!value.isFinite()) return "—$suffix"
    val safeDec = decimals.coerceIn(0, 4)
    return String.format(Locale.US, "%.${safeDec}f", value) + suffix
}

private val SHORT_TEAM_NAMES: Map<String, String> = mapOf(
        "crystal palace" to "C. Palace",
        "aston villa" to "A. Villa",
        "manchester city" to "Man City",
        "manchester united" to "Man Utd",
        "nottingham forest" to "Nott'm Forest",
        "newcastle united" to "Newcastle",
        "tottenham hotspur" to "Spurs",
        "west ham united" to "West Ham",
        "wolverhampton wanderers" to "Wolves",
        "brighton & hove albion" to "Brighton",
        "brighton and hove albion" to "Brighton",
        "coventry city" to "Coventry",
        "leicester city" to "Leicester",
        "ipswich town" to "Ipswich",
        "real madrid" to "R. Madrid",
        "atletico madrid" to "Atletico",
        "bayern munich" to "Bayern",
        "borussia dortmund" to "Dortmund",
        "bayer leverkusen" to "Leverkusen",
        "inter milan" to "Inter",
        "ac milan" to "AC Milan",
        "paris saint-germain" to "PSG",
        "kansas city chiefs" to "Chiefs",
        "san francisco 49ers" to "49ers",
        "philadelphia eagles" to "Eagles",
        "baltimore ravens" to "Ravens",
        "buffalo bills" to "Bills",
        "detroit lions" to "Lions",
        "boston celtics" to "Celtics",
        "los angeles lakers" to "Lakers",
        "golden state warriors" to "Warriors",
        "oklahoma city thunder" to "Thunder",
        "denver nuggets" to "Nuggets",
        "new york knicks" to "Knicks",
        "portland trail blazers" to "Blazers",
        "sacramento kings" to "Kings",
        "philadelphia 76ers" to "76ers",
        "milwaukee bucks" to "Bucks",
        "dallas mavericks" to "Mavericks",
        "la clippers" to "Clippers",
        "los angeles clippers" to "Clippers",
        "minnesota timberwolves" to "Wolves",
        "memphis grizzlies" to "Grizzlies",
        "san antonio spurs" to "Spurs",
        "new orleans pelicans" to "Pelicans",
        "phoenix suns" to "Suns",
        "miami heat" to "Heat",
        "cleveland cavaliers" to "Cavaliers",
        "indiana pacers" to "Pacers",
        "tampa bay buccaneers" to "Bucs",
        "arizona cardinals" to "Cardinals",
        "green bay packers" to "Packers",
        "dallas cowboys" to "Cowboys",
        "new england patriots" to "Patriots",
        "los angeles rams" to "Rams",
        "los angeles chargers" to "Chargers",
        "new york giants" to "Giants",
        "new york jets" to "Jets",
        "los angeles dodgers" to "Dodgers",
        "new york yankees" to "Yankees",
        "atlanta braves" to "Braves",
        "philadelphia phillies" to "Phillies",
        "houston astros" to "Astros",
        "boston red sox" to "Red Sox",
        "las vegas raiders" to "Raiders",
        "washington commanders" to "Commanders",
        "jacksonville jaguars" to "Jaguars",
        "carolina panthers" to "Panthers",
        "seattle seahawks" to "Seahawks",
        "minnesota vikings" to "Vikings",
        "cincinnati bengals" to "Bengals",
        "cleveland browns" to "Browns",
        "pittsburgh steelers" to "Steelers",
        "houston texans" to "Texans",
        "indianapolis colts" to "Colts",
        "tennessee titans" to "Titans",
        "denver broncos" to "Broncos",
        "chicago bears" to "Bears",
        "atlanta falcons" to "Falcons",
        "new orleans saints" to "Saints",
                "miami dolphins" to "Dolphins",
        "anaheim ducks" to "Ducks",
        "boston bruins" to "Bruins",
        "buffalo sabres" to "Sabres",
        "calgary flames" to "Flames",
        "carolina hurricanes" to "Hurricanes",
        "chicago blackhawks" to "Blackhawks",
        "colorado avalanche" to "Avalanche",
        "columbus blue jackets" to "Blue Jackets",
        "dallas stars" to "Stars",
        "detroit red wings" to "Red Wings",
        "edmonton oilers" to "Oilers",
        "florida panthers" to "Panthers",
        "los angeles kings" to "Kings",
        "minnesota wild" to "Wild",
        "montreal canadiens" to "Canadiens",
        "montréal canadiens" to "Canadiens",
        "nashville predators" to "Predators",
        "new jersey devils" to "Devils",
        "new york islanders" to "Islanders",
        "new york rangers" to "Rangers",
        "ottawa senators" to "Senators",
        "philadelphia flyers" to "Flyers",
        "pittsburgh penguins" to "Penguins",
        "san jose sharks" to "Sharks",
        "seattle kraken" to "Kraken",
        "st. louis blues" to "Blues",
        "st louis blues" to "Blues",
        "tampa bay lightning" to "Lightning",
        "toronto maple leafs" to "Maple Leafs",
        "utah hockey club" to "Utah HC",
        "utah mammoth" to "Mammoth",
        "vancouver canucks" to "Canucks",
        "vegas golden knights" to "Golden Knights",
        "washington capitals" to "Capitals",
        "winnipeg jets" to "Jets",
)

fun shortTeamName(fullName: String): String {
    val clean = fullName.trim()
    SHORT_TEAM_NAMES[clean.lowercase(Locale.ROOT)]?.let { return it }
    if (clean.length <= 13) return clean
    val words = clean.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
    val abbr = if (words.size >= 2 && words[0].length >= 4) {
        "${words[0].first()}. ${words.drop(1).joinToString(" ")}"
    } else null
    return when {
        abbr != null && abbr.length <= 14 -> abbr
        words.size >= 2 && words.last().length <= 14 -> words.last()
        else -> {
            val built = buildString {
                for (w in words) {
                    if (isEmpty()) append(w)
                    else if (length + 1 + w.length <= 14) append(' ').append(w)
                    else break
                }
            }
            if (built.length <= 14) built else "${built.take(13)}…"
        }
    }
}

@Composable
fun tabularStyle(sizeSp: Float, weight: FontWeight = FontWeight.Bold, color: Color = AppTheme.tokens.colors.text): TextStyle {
    val scale = AppTheme.tokens.fontScale
    return TextStyle(
        fontSize = (sizeSp * scale).coerceAtLeast(10f).sp,
        fontWeight = weight,
        color = color,
        fontFeatureSettings = "tnum",
    )
}

@Composable
fun scaledSp(baseSp: Float) = (baseSp * AppTheme.tokens.fontScale).coerceAtLeast(10f).sp

fun formatLocalKickoff(kickoffIso: String): String = runCatching {
    val trimmed = kickoffIso.trim()
    val instant = runCatching { OffsetDateTime.parse(trimmed).toInstant() }
        .getOrElse { Instant.parse(trimmed) }
    instant.atZone(ZoneId.systemDefault()).format(LOCAL_KICKOFF_FORMATTER)
}.getOrElse { kickoffIso.take(16).replace("T", " · ") }

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    useHeroGradient: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AppTheme.tokens
    val border = if (t.borderWidthDp > 0.dp) BorderStroke(t.borderWidthDp, t.colors.border) else null

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = t.shapes.card,
        color = when {
            useHeroGradient && t.config.useGradientHero -> Color.Transparent
            t.surfaceStyle == com.edgeledger.app.theme.SurfaceStyle.TONAL -> t.colors.surfaceTonal
            else -> t.colors.surface
        },
        shadowElevation = t.elevationDp,
        tonalElevation = if (t.elevationDp > 0.dp) 1.dp else 0.dp,
        border = border,
    ) {
        val bgModifier = if (useHeroGradient && t.config.useGradientHero) {
            Modifier.background(
                Brush.linearGradient(
                    colors = listOf(t.colors.heroGradientStart, t.colors.heroGradientEnd)
                )
            )
        } else {
            Modifier
        }
        Column(
            modifier = bgModifier.padding(t.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(t.spacing.itemGap),
            content = content,
        )
    }
}

@Composable
fun TokenPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = AppTheme.tokens.colors.accent,
    activeContainer: Color = AppTheme.tokens.colors.accentDim,
) {
    val t = AppTheme.tokens
    val c = t.colors
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab) {
                if (t.config.haptics != HapticLevel.OFF) {
                    haptic.performHapticFeedback(
                        if (t.config.haptics == HapticLevel.CRISP) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                    )
                }
                onClick()
            },
        shape = t.shapes.small,
        color = if (selected) activeContainer else c.surface,
        border = BorderStroke(
            width = if (selected) 1.25.dp else 0.9.dp,
            color = if (selected) activeColor else c.border,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(
                horizontal = 11.dp,
                vertical = 6.dp,
            ),
        ) {
            Text(
                text = label,
                color = if (selected) activeColor else c.textSecondary,
                fontSize = scaledSp(12f),
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}


@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = AppTheme.tokens
    val c = t.colors
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth().height(38.dp),
        shape = t.shapes.medium,
        color = c.surfaceRaised,
        border = BorderStroke(0.75.dp, c.border),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, label ->
                val isSelected = idx == selectedIndex
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = isSelected }
                        .clickable(role = Role.Tab) {
                            if (t.config.haptics != HapticLevel.OFF) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onSelect(idx)
                        },
                    shape = t.shapes.small,
                    color = if (isSelected) c.accentDim else Color.Transparent,
                    border = if (isSelected) BorderStroke(1.dp, c.accent.copy(alpha = 0.7f)) else null,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            text = label,
                            color = if (isSelected) c.accent else c.textSecondary,
                            fontSize = scaledSp(11.5f),
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    val t = AppTheme.tokens
    Surface(
        modifier = modifier,
        shape = t.shapes.badge,
        color = containerColor,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = scaledSp(11f),
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun TeamAvatar(
    teamName: String,
    size: Dp = 40.dp,
    logoUrl: String? = null,
) {
    val words = remember(teamName) {
        teamName.trim().split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
    }
    val initials = when {
        words.isEmpty() -> "??"
        words.size == 1 -> words[0].take(2).uppercase(Locale.ROOT)
        else -> "${words[0][0]}${words[1][0]}".uppercase(Locale.ROOT)
    }
    val color = TEAM_COLORS[Math.floorMod(teamName.lowercase(Locale.ROOT).hashCode(), TEAM_COLORS.size)]
    val c = AppTheme.tokens.colors

    @Composable
    fun InitialsFallback() {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
                .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
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

    if (logoUrl.isNullOrBlank()) {
        InitialsFallback()
    } else {
        val context = LocalContext.current
        val isLightMode = AppTheme.tokens.config.mode.isLight
        val emblemBg = if (isLightMode) Color(0xFFF1F5F9) else Color(0xFFEAF0F7)
        val emblemBorder = if (isLightMode) c.border else Color.White.copy(alpha = 0.22f)
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(emblemBg)
                .border(1.dp, emblemBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            var showFallback by remember(logoUrl) { mutableStateOf(true) }
            val imgRequest = remember(context, logoUrl) {
                ImageRequest.Builder(context)
                    .data(logoUrl)
                    .crossfade(true)
                    .build()
            }
            if (showFallback) {
                InitialsFallback()
            }
            AsyncImage(
                model = imgRequest,
                imageLoader = context.imageLoader,
                contentDescription = "$teamName logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.76f),
                onSuccess = { showFallback = false },
                onError = { showFallback = true },
            )
        }
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
    val safeH = if (homeProb.isFinite()) homeProb.coerceAtLeast(0.0) else 0.5
    val safeA = if (awayProb.isFinite()) awayProb.coerceAtLeast(0.0) else 0.5
    val safeD = drawProb?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val hasDraw = safeD > 0.0
    val sum = max(0.001, safeH + safeA + safeD)
    val hPct = ((safeH / sum) * 100.0).roundToInt().coerceIn(1, if (hasDraw) 97 else 99)
    val dPct = if (hasDraw) (((safeD / sum) * 100.0).roundToInt().coerceIn(1, (99 - hPct).coerceAtLeast(1))) else 0
    val aPct = (100 - hPct - dPct).coerceAtLeast(1)

    val a11yDesc = if (hasDraw) {
        "Win probability: $homeLabel $hPct percent, Draw $dPct percent, $awayLabel $aPct percent"
    } else {
        "Win probability: $homeLabel $hPct percent, $awayLabel $aPct percent"
    }

    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = a11yDesc },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(homeLabel, color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hasDraw) {
                Text("Draw", color = c.textMuted, fontSize = scaledSp(12f), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(0.55f))
            }
            Text(awayLabel, color = c.textSecondary, fontSize = scaledSp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(c.surfaceRaised),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.weight(hPct.toFloat()).fillMaxHeight().background(c.win))
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
fun FormDots(results: List<String>, alignEnd: Boolean = false) {
    val t = AppTheme.tokens
    val c = t.colors
    val recent = results.takeLast(5)
    val padded = if (recent.size < 5) {
        val empties = List(5 - recent.size) { "–" }
        if (alignEnd) empties + recent else recent + empties
    } else {
        recent
    }

    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Recent form: ${recent.joinToString(", ").ifBlank { "No recent games" }}"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        padded.forEach { res ->
            val bg = when (res) {
                "W" -> c.win
                "D", "OTL" -> c.draw
                "L" -> c.loss
                else -> c.surfaceRaised
            }
            val fg = when (res) {
                "W" -> c.onWin
                "D", "OTL" -> c.onDraw
                "L" -> c.onLoss
                else -> c.textMuted
            }
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(if (res.length > 1) 26.dp else 20.dp)
                    .clip(t.shapes.extraSmall)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(res, color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
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
    val safeH = if (homeValue.isFinite()) homeValue else 0.0
    val safeA = if (awayValue.isFinite()) awayValue else 0.0
    val total = max(0.001, abs(safeH) + abs(safeA))
    val hWeight = (abs(safeH) / total).toFloat().coerceIn(0.05f, 0.95f)
    val aWeight = (1f - hWeight).coerceAtLeast(0.05f)
    val hLeads = if (higherIsBetter) safeH >= safeA else safeH <= safeA

    val homeFormatted = formatStatValue(homeValue, decimals, suffix)
    val awayFormatted = formatStatValue(awayValue, decimals, suffix)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                homeFormatted,
                style = tabularStyle(12.5f, if (hLeads) FontWeight.ExtraBold else FontWeight.Medium, if (hLeads) c.text else c.textSecondary),
                modifier = Modifier.width(56.dp),
                maxLines = 1,
            )
            Text(
                label,
                color = c.textMuted,
                fontSize = scaledSp(12f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                awayFormatted,
                style = tabularStyle(12.5f, if (!hLeads) FontWeight.ExtraBold else FontWeight.Medium, if (!hLeads) c.text else c.textSecondary),
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
                maxLines = 1,
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
            Box(Modifier.weight(hWeight).fillMaxHeight().background(c.win.copy(alpha = if (hLeads) 1f else 0.42f)))
            Box(Modifier.weight(aWeight).fillMaxHeight().background(c.loss.copy(alpha = if (!hLeads) 1f else 0.42f)))
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    subtitleColor: Color = AppTheme.tokens.colors.win,
) {
    val t = AppTheme.tokens
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 68.dp)
            .clip(t.shapes.medium)
            .background(t.colors.surfaceRaised)
            .border(t.borderWidthDp.coerceAtLeast(0.75.dp), t.colors.border.copy(alpha = 0.65f), t.shapes.medium)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = t.colors.textSecondary, fontSize = scaledSp(11.5f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = tabularStyle(17f, FontWeight.ExtraBold, t.colors.text), maxLines = 1)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = subtitleColor, fontSize = scaledSp(11f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LineMovementChart(
    points: List<LineMovePoint>,
    modifier: Modifier = Modifier,
) {
    val t = AppTheme.tokens
    val c = t.colors
    if (points.size < 2) return

    val minVal = (points.minOf { minOf(it.sharpHomeProbPct, it.retailHomeProbPct) } - 4f).coerceAtLeast(1f)
    val maxVal = (points.maxOf { maxOf(it.sharpHomeProbPct, it.retailHomeProbPct) } + 4f).coerceAtMost(99f)
    val range = max(4f, maxVal - minVal)
    val steamCount = points.count { it.isSteamMove }

    val labelPoints = if (points.size <= 5) {
        points
    } else {
        listOf(points.first(), points[points.size / 2], points.last())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Line movement chart from ${points.first().sharpHomeProbPct.roundToInt()}% to ${points.last().sharpHomeProbPct.roundToInt()}% sharp probability, $steamCount steam moves"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
                    Text("Sharp Line", color = c.textSecondary, fontSize = scaledSp(11f), fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.draw))
                    Text("Retail (Dashed)", color = c.textMuted, fontSize = scaledSp(11f), fontWeight = FontWeight.Medium)
                }
            }
            if (steamCount > 0) {
                StatusBadge("$steamCount Steam Move${if (steamCount > 1) "s" else ""}", c.oddsUp, c.oddsUpDim)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
                .clip(t.shapes.medium)
                .background(c.surfaceRaised)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1).coerceAtLeast(1)

            for (g in 0..2) {
                val gy = h * (g / 2f)
                drawLine(
                    color = c.border.copy(alpha = 0.45f),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            fun yFor(v: Float): Float = h - ((v - minVal) / range).coerceIn(0f, 1f) * h

            val sharpPath = Path()
            val retailPath = Path()

            points.forEachIndexed { idx, pt ->
                val x = idx * stepX
                val ySharp = yFor(pt.sharpHomeProbPct)
                val yRetail = yFor(pt.retailHomeProbPct)
                if (idx == 0) {
                    sharpPath.moveTo(x, ySharp)
                    retailPath.moveTo(x, yRetail)
                } else {
                    sharpPath.lineTo(x, ySharp)
                    retailPath.lineTo(x, yRetail)
                }
            }

            drawPath(
                path = retailPath,
                color = c.draw,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                ),
            )
            drawPath(
                path = sharpPath,
                color = c.accent,
                style = Stroke(width = 2.6.dp.toPx()),
            )

            points.forEachIndexed { idx, pt ->
                val x = idx * stepX
                val ySharp = yFor(pt.sharpHomeProbPct)
                drawCircle(
                    color = if (pt.isSteamMove) c.oddsUp else c.accent,
                    radius = if (pt.isSteamMove) 5.dp.toPx() else 3.2.dp.toPx(),
                    center = Offset(x, ySharp),
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labelPoints.forEach { pt ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(pt.timeLabel, color = c.textMuted, fontSize = scaledSp(10f), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${pt.sharpHomeProbPct.roundToInt()}%",
                        style = tabularStyle(10.5f, if (pt.isSteamMove) FontWeight.ExtraBold else FontWeight.Medium, if (pt.isSteamMove) c.oddsUp else c.textSecondary),
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureDriverRow(driver: FeatureDriver) {
    val c = AppTheme.tokens.colors
    val sign = if (driver.impactPct >= 0.0) "+" else ""
    val badgeColor = if (driver.isPositive) c.win else c.loss
    val badgeDim = if (driver.isPositive) c.winDim else c.lossDim
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(driver.label, color = c.text, fontSize = scaledSp(13f), fontWeight = FontWeight.Bold)
            Text(driver.detail, color = c.textMuted, fontSize = scaledSp(11.5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        StatusBadge(
            text = "$sign${formatStatValue(driver.impactPct, 1, "%")}",
            color = badgeColor,
            containerColor = badgeDim,
        )
    }
}

@Composable
fun PlayerPropCard(
    prop: PlayerPropPrediction,
    onSave: () -> Unit,
) {
    val t = AppTheme.tokens
    val c = t.colors
    var expanded by remember(prop.id) { mutableStateOf(false) }
    Surface(
        color = c.surfaceRaised,
        shape = t.shapes.medium,
        border = BorderStroke(0.75.dp, c.border.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(prop.playerName, color = c.text, fontSize = scaledSp(14f), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("${prop.position} · ${shortTeamName(prop.team)}", color = c.textMuted, fontSize = scaledSp(11f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${prop.marketLabel}: ${prop.lineLabel}", color = c.accent, fontSize = scaledSp(13f), fontWeight = FontWeight.Bold)
                Text(
                    "Projected ${formatStatValue(prop.projectedValue, 1)} (70% range ${formatStatValue(prop.rangeLow, 1)}–${formatStatValue(prop.rangeHigh, 1)}) · ${prop.overChancePct}% chance",
                    style = tabularStyle(11.5f, FontWeight.SemiBold, c.textSecondary),
                )
                Text(prop.plainReason, color = c.textMuted, fontSize = scaledSp(11.5f), maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge("${prop.overChancePct}%", c.win, c.winDim)
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    shape = t.shapes.button,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Save", fontSize = scaledSp(11.5f), fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}
