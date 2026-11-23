package com.edgeledger.app.engine

import com.edgeledger.app.data.OddsHistoryEntity
import com.edgeledger.app.model.LivePlayerEntry
import com.edgeledger.app.model.Sport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

typealias PlayerPropPrediction = com.edgeledger.app.model.PlayerPropPrediction
typealias FeatureDriver = com.edgeledger.app.model.FeatureDriver
typealias LineMovePoint = com.edgeledger.app.model.LineMovePoint

/**
 * 100% Live-Roster Player Props, Explainable Feature Drivers, Real Odds-History Line Movement,
 * and Beta-Posterior Confidence Interval Engine.
 *
 * Strictly forbids hardcoded player rosters and PRNG/synthetic line movement.
 */
object PlayerPropsEngine {

    private val GOALKEEPER_POSITIONS = setOf("G", "GK", "GOALKEEPER", "GOALIE")
    private val TIME_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(ZoneOffset.UTC)

    fun generatePlayerProps(
        sport: Sport,
        homeTeam: String,
        awayTeam: String,
        projectedHomeScore: Double,
        projectedAwayScore: Double,
        homePower: Int,
        awayPower: Int,
        liveHomePlayers: List<LivePlayerEntry> = emptyList(),
        liveAwayPlayers: List<LivePlayerEntry> = emptyList(),
        fixtureId: String = "",
    ): List<PlayerPropPrediction> {
        val safeHome = if (projectedHomeScore.isFinite() && projectedHomeScore > 0.0) projectedHomeScore else defaultScoreFor(sport)
        val safeAway = if (projectedAwayScore.isFinite() && projectedAwayScore > 0.0) projectedAwayScore else defaultScoreFor(sport)
        val fxPrefix = fixtureId.ifBlank { "${homeTeam}_${awayTeam}".replace(Regex("[^A-Za-z0-9]+"), "_") }

        val homeProps = buildTeamProps(sport, homeTeam, safeHome, homePower - awayPower, liveHomePlayers, fxPrefix, "H")
        val awayProps = buildTeamProps(sport, awayTeam, safeAway, awayPower - homePower, liveAwayPlayers, fxPrefix, "A")
        return (homeProps + awayProps).sortedByDescending { it.overChancePct }
    }

    private fun defaultScoreFor(sport: Sport): Double = when (sport) {
        Sport.FOOTBALL -> 1.35
        Sport.NBA -> 112.0
        Sport.NFL -> 22.0
        Sport.NHL -> 3.0
        Sport.MLB -> 4.3
        else -> 1.5
    }

    private fun buildTeamProps(
        sport: Sport,
        team: String,
        projectedTeamScore: Double,
        powerDiff: Int,
        rawPlayers: List<LivePlayerEntry>,
        fxPrefix: String,
        sideTag: String,
    ): List<PlayerPropPrediction> {
        val deduped = rawPlayers
            .filter { it.name.isNotBlank() }
            .filterNot { it.position.trim().uppercase(Locale.US) in GOALKEEPER_POSITIONS }
            .distinctBy {
                it.name.trim().lowercase(Locale.US) to it.statCategory.trim().lowercase(Locale.US)
            }
        if (deduped.isEmpty()) return emptyList()

        return when (sport) {
            Sport.FOOTBALL -> buildFootballProps(team, projectedTeamScore, deduped, fxPrefix, sideTag)
            Sport.NBA -> buildNbaProps(team, projectedTeamScore, deduped, fxPrefix, sideTag)
            Sport.NFL -> buildNflProps(team, projectedTeamScore, powerDiff, deduped, fxPrefix, sideTag)
            Sport.NHL -> buildNhlProps(team, projectedTeamScore, deduped, fxPrefix, sideTag)
            Sport.MLB -> buildMlbProps(team, projectedTeamScore, deduped, fxPrefix, sideTag)
            else -> emptyList()
        }
    }

    private fun buildFootballProps(
        team: String,
        teamXg: Double,
        players: List<LivePlayerEntry>,
        fxPrefix: String,
        sideTag: String,
    ): List<PlayerPropPrediction> {
        val ranked = players.sortedByDescending { footballAttackWeight(it.position, it.statCategory, it.statValue) }.take(3)
        if (ranked.isEmpty()) return emptyList()

        val rawWeights = ranked.map { footballAttackWeight(it.position, it.statCategory, it.statValue) }
        val weightSum = rawWeights.sum().coerceAtLeast(1.0)
        val topSharePool = (teamXg * 0.62).coerceAtMost(teamXg)

        val maxSingleXg = max(0.02, teamXg * 0.45)
        val minSingleXg = min(0.08, maxSingleXg * 0.35)
        val rawPlayerXgs = ranked.indices.map { idx ->
            (topSharePool * (rawWeights[idx] / weightSum)).coerceIn(minSingleXg, maxSingleXg)
        }
        val xgScale = if (rawPlayerXgs.sum() > topSharePool && rawPlayerXgs.sum() > 0.0) topSharePool / rawPlayerXgs.sum() else 1.0

        val out = mutableListOf<PlayerPropPrediction>()
        ranked.forEachIndexed { idx, p ->
            val playerXg = max(0.01, rawPlayerXgs[idx] * xgScale)
            val anytimePct = (StatsMath.poissonOverProb(playerXg, 0.5) * 100.0).roundToInt().coerceIn(5, 85)
            val low = StatsMath.poissonQuantile(playerXg, 0.15).toDouble()
            val high = max(low + 1.0, StatsMath.poissonQuantile(playerXg, 0.85).toDouble())
            out.add(
                PlayerPropPrediction(
                    id = "${fxPrefix}_${sideTag}_fb_goal_$idx",
                    playerName = p.name,
                    team = team,
                    position = p.position.ifBlank { "FWD" },
                    marketLabel = "Anytime Goalscorer",
                    lineLabel = "Over 0.5 Goals",
                    projectedValue = round2(playerXg),
                    overChancePct = anytimePct,
                    rangeLow = low,
                    rangeHigh = high,
                    confidenceLabel = confidenceTag(anytimePct),
                    plainReason = String.format(
                        Locale.US,
                        "Projected %.2f xG share of %s's %.2f expected goals.",
                        playerXg,
                        team,
                        teamXg,
                    ),
                )
            )
            if (idx == 0) {
                val sotMean = (playerXg * 2.6 + 0.35).coerceIn(0.4, 3.8)
                val line = centeredHalfLine(sotMean)
                val sotOver = (StatsMath.poissonOverProb(sotMean, line) * 100.0).roundToInt().coerceIn(5, 92)
                out.add(
                    PlayerPropPrediction(
                        id = "${fxPrefix}_${sideTag}_fb_sot_$idx",
                        playerName = p.name,
                        team = team,
                        position = p.position.ifBlank { "FWD" },
                        marketLabel = "Shots on Target",
                        lineLabel = String.format(Locale.US, "Over %.1f SOT", line),
                        projectedValue = round2(sotMean),
                        overChancePct = sotOver,
                        rangeLow = StatsMath.poissonQuantile(sotMean, 0.15).toDouble(),
                        rangeHigh = StatsMath.poissonQuantile(sotMean, 0.85).toDouble(),
                        confidenceLabel = confidenceTag(sotOver),
                        plainReason = String.format(
                            Locale.US,
                            "Primary shooter projected for %.1f shots on target.",
                            sotMean,
                        ),
                    )
                )
            }
            if (idx == 1) {
                val maxAssist = max(0.02, min(0.85, teamXg * 0.65)); val assistMean = min(playerXg * 0.72, teamXg * 0.28).coerceIn(min(0.06, maxAssist * 0.5), maxAssist)
                val assistPct = (StatsMath.poissonOverProb(assistMean, 0.5) * 100.0).roundToInt().coerceIn(5, 65)
                out.add(
                    PlayerPropPrediction(
                        id = "${fxPrefix}_${sideTag}_fb_ast_$idx",
                        playerName = p.name,
                        team = team,
                        position = p.position.ifBlank { "MID" },
                        marketLabel = "Anytime Assist",
                        lineLabel = "Over 0.5 Assists",
                        projectedValue = round2(assistMean),
                        overChancePct = assistPct,
                        rangeLow = StatsMath.poissonQuantile(assistMean, 0.15).toDouble(),
                        rangeHigh = max(1.0, StatsMath.poissonQuantile(assistMean, 0.85).toDouble()),
                        confidenceLabel = confidenceTag(assistPct),
                        plainReason = String.format(
                            Locale.US,
                            "Secondary creator projected for %.2f expected assists.",
                            assistMean,
                        ),
                    )
                )
            }
        }
        return out
    }

    private fun footballAttackWeight(pos: String, cat: String, statVal: Double?): Double {
        val p = pos.trim().uppercase(Locale.US)
        val base = when {
            p in setOf("F", "FW", "FWD", "ST", "CF", "LW", "RW") -> 3.0
            p in setOf("M", "MF", "MID", "AM", "CAM", "W") -> 1.8
            p in setOf("D", "DF", "DEF", "CB", "LB", "RB") -> 0.5
            else -> 1.5
        }
        val bonus = if (cat.contains("goal", ignoreCase = true) || cat.contains("score", ignoreCase = true)) {
            val v = statVal?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            min(2.5, 0.5 + v * 0.15)
        } else {
            0.0
        }
        return base + bonus
    }

    private fun buildNbaProps(
        team: String,
        teamPts: Double,
        players: List<LivePlayerEntry>,
        fxPrefix: String,
        sideTag: String,
    ): List<PlayerPropPrediction> {
        val leaders = players.sortedByDescending { p ->
            val isPts = p.statCategory.isBlank() || p.statCategory.contains("point", ignoreCase = true)
            val v = p.statValue?.takeIf { it.isFinite() } ?: 0.0
            (if (isPts) 1000.0 else 0.0) + v
        }.distinctBy { it.name.trim().lowercase(Locale.ROOT) }.take(3)
        val shares = listOf(0.23, 0.18, 0.14)
        val rawMeans = leaders.mapIndexed { idx, p ->
            val rawStat = p.statValue?.takeIf { it.isFinite() && it in 4.0..48.0 }
            val isPointsCat = p.statCategory.isBlank() || p.statCategory.contains("point", ignoreCase = true)
            if (rawStat != null && isPointsCat) {
                (0.65 * rawStat + 0.35 * (teamPts * shares[idx])).coerceIn(6.0, 44.0)
            } else {
                (teamPts * shares[idx]).coerceIn(8.0, 38.0)
            }
        }
        val maxCombined = teamPts * 0.72
        val ptsScale = if (rawMeans.sum() > maxCombined && rawMeans.sum() > 0.0) maxCombined / rawMeans.sum() else 1.0
        return leaders.mapIndexed { idx, p ->
            val mean = (rawMeans[idx] * ptsScale).coerceIn(6.0, 44.0)
            val line = centeredHalfLine(mean)
            val sd = max(4.2, mean * 0.27)
            val overPct = ((1.0 - StatsMath.normalCdf((line - mean) / sd)) * 100.0).roundToInt().coerceIn(5, 95)
            val low = max(0.0, mean + StatsMath.normalInvCdf(0.15) * sd)
            val high = mean + StatsMath.normalInvCdf(0.85) * sd
            PlayerPropPrediction(
                id = "${fxPrefix}_${sideTag}_nba_pts_$idx",
                playerName = p.name,
                team = team,
                position = p.position.ifBlank { "G/F" },
                marketLabel = "Points",
                lineLabel = String.format(Locale.US, "Over %.1f Pts", line),
                projectedValue = round1(mean),
                overChancePct = overPct,
                rangeLow = round1(low),
                rangeHigh = round1(high),
                confidenceLabel = confidenceTag(overPct),
                plainReason = String.format(
                    Locale.US,
                    "Projected %.1f pts on %s's %.1f team total.",
                    mean,
                    team,
                    teamPts,
                ),
            )
        }
    }

    private fun buildNflProps(
        team: String,
        teamPts: Double,
        powerDiff: Int,
        players: List<LivePlayerEntry>,
        fxPrefix: String,
        sideTag: String,
    ): List<PlayerPropPrediction> {
        val out = mutableListOf<PlayerPropPrediction>()
        val qb = players.firstOrNull { it.position.equals("QB", ignoreCase = true) || it.statCategory.contains("pass", ignoreCase = true) }
        val rb = players.firstOrNull { it.position.equals("RB", ignoreCase = true) || it.statCategory.contains("rush", ignoreCase = true) }
        val wr = players.firstOrNull { it.position.trim().uppercase(Locale.US) in setOf("WR", "TE") || it.statCategory.contains("receiv", ignoreCase = true) }

        val paceMult = (teamPts / 22.0).coerceIn(0.75, 1.35)
        qb?.let {
            val baseYds = it.statValue?.takeIf { v -> v.isFinite() && v in 120.0..420.0 } ?: (238.0 * paceMult)
            val mean = (baseYds - powerDiff * 0.35).coerceIn(140.0, 380.0)
            val line = centeredHalfLine(mean)
            val sd = 52.0
            val overPct = ((1.0 - StatsMath.normalCdf((line - mean) / sd)) * 100.0).roundToInt().coerceIn(5, 95)
            out.add(
                PlayerPropPrediction(
                    id = "${fxPrefix}_${sideTag}_nfl_pass",
                    playerName = it.name,
                    team = team,
                    position = "QB",
                    marketLabel = "Passing Yards",
                    lineLabel = String.format(Locale.US, "Over %.1f Yds", line),
                    projectedValue = round1(mean),
                    overChancePct = overPct,
                    rangeLow = round1(max(60.0, mean + StatsMath.normalInvCdf(0.15) * sd)),
                    rangeHigh = round1(mean + StatsMath.normalInvCdf(0.85) * sd),
                    confidenceLabel = confidenceTag(overPct),
                    plainReason = String.format(Locale.US, "Projected %.1f passing yards at %.1f implied team points.", mean, teamPts),
                )
            )
        }
        rb?.let {
            val baseYds = it.statValue?.takeIf { v -> v.isFinite() && v in 20.0..160.0 } ?: (68.0 * paceMult)
            val mean = (baseYds + powerDiff * 0.25).coerceIn(25.0, 145.0)
            val line = centeredHalfLine(mean)
            val sd = 26.0
            val overPct = ((1.0 - StatsMath.normalCdf((line - mean) / sd)) * 100.0).roundToInt().coerceIn(5, 95)
            out.add(
                PlayerPropPrediction(
                    id = "${fxPrefix}_${sideTag}_nfl_rush",
                    playerName = it.name,
                    team = team,
                    position = "RB",
                    marketLabel = "Rushing Yards",
                    lineLabel = String.format(Locale.US, "Over %.1f Yds", line),
                    projectedValue = round1(mean),
                    overChancePct = overPct,
                    rangeLow = round1(max(10.0, mean + StatsMath.normalInvCdf(0.15) * sd)),
                    rangeHigh = round1(mean + StatsMath.normalInvCdf(0.85) * sd),
                    confidenceLabel = confidenceTag(overPct),
                    plainReason = String.format(Locale.US, "Lead rusher projected for %.1f yards.", mean),
                )
            )
        }
        wr?.let {
            val baseYds = it.statValue?.takeIf { v -> v.isFinite() && v in 20.0..150.0 } ?: (66.0 * paceMult)
            val mean = baseYds.coerceIn(25.0, 140.0)
            val line = centeredHalfLine(mean)
            val sd = 28.0
            val overPct = ((1.0 - StatsMath.normalCdf((line - mean) / sd)) * 100.0).roundToInt().coerceIn(5, 95)
            out.add(
                PlayerPropPrediction(
                    id = "${fxPrefix}_${sideTag}_nfl_rec",
                    playerName = it.name,
                    team = team,
                    position = it.position.ifBlank { "WR" },
                    marketLabel = "Receiving Yards",
                    lineLabel = String.format(Locale.US, "Over %.1f Yds", line),
                    projectedValue = round1(mean),
                    overChancePct = overPct,
                    rangeLow = round1(max(10.0, mean + StatsMath.normalInvCdf(0.15) * sd)),
                    rangeHigh = round1(mean + StatsMath.normalInvCdf(0.85) * sd),
                    confidenceLabel = confidenceTag(overPct),
                    plainReason = String.format(Locale.US, "Primary receiver projected for %.1f yards.", mean),
                )
            )
        }
        return out
    }

    private fun buildNhlProps(
        team: String,
        teamGoals: Double,
        players: List<LivePlayerEntry>,
        fxPrefix: String,
        sideTag: String,
    ): List<PlayerPropPrediction> {
        val forwards = players.filter { it.position.uppercase(Locale.US) in setOf("C", "L", "R", "LW", "RW", "F") }
            .ifEmpty { players }
            .take(2)
        return forwards.mapIndexed { idx, p ->
            val sogMean = ((2.95 - idx * 0.45) * (teamGoals / 3.0).coerceIn(0.8, 1.3)).coerceIn(1.2, 4.8)
            val line = centeredHalfLine(sogMean)
            val overPct = (StatsMath.poissonOverProb(sogMean, line) * 100.0).roundToInt().coerceIn(5, 92)
            PlayerPropPrediction(
                id = "${fxPrefix}_${sideTag}_nhl_sog_$idx",
                playerName = p.name,
                team = team,
                position = p.position.ifBlank { "F" },
                marketLabel = "Shots on Goal",
                lineLabel = String.format(Locale.US, "Over %.1f SOG", line),
                projectedValue = round1(sogMean),
                overChancePct = overPct,
                rangeLow = StatsMath.poissonQuantile(sogMean, 0.15).toDouble(),
                rangeHigh = StatsMath.poissonQuantile(sogMean, 0.85).toDouble(),
                confidenceLabel = confidenceTag(overPct),
                plainReason = String.format(Locale.US, "Top-line skater projected for %.1f shots on goal.", sogMean),
            )
        }
    }

    private fun buildMlbProps(
        team: String,
        teamRuns: Double,
        players: List<LivePlayerEntry>,
        fxPrefix: String,
        sideTag: String,
    ): List<PlayerPropPrediction> {
        val hitters = players.filterNot { it.position.uppercase(Locale.US) in setOf("P", "SP", "RP") }.take(2)
        return hitters.mapIndexed { idx, p ->
            val tbMean = ((1.45 - idx * 0.15) * (teamRuns / 4.3).coerceIn(0.75, 1.35)).coerceIn(0.7, 2.8)
            val line = centeredHalfLine(tbMean)
            val overPct = (StatsMath.poissonOverProb(tbMean, line) * 100.0).roundToInt().coerceIn(5, 90)
            PlayerPropPrediction(
                id = "${fxPrefix}_${sideTag}_mlb_tb_$idx",
                playerName = p.name,
                team = team,
                position = p.position.ifBlank { "OF" },
                marketLabel = "Total Bases",
                lineLabel = String.format(Locale.US, "Over %.1f Bases", line),
                projectedValue = round2(tbMean),
                overChancePct = overPct,
                rangeLow = StatsMath.poissonQuantile(tbMean, 0.15).toDouble(),
                rangeHigh = StatsMath.poissonQuantile(tbMean, 0.85).toDouble(),
                confidenceLabel = confidenceTag(overPct),
                plainReason = String.format(Locale.US, "Projected %.2f total bases in %.1f-run team projection.", tbMean, teamRuns),
            )
        }
    }

    private fun centeredHalfLine(mean: Double): Double = max(0.5, mean.roundToInt().toDouble() - 0.5)
    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
    private fun confidenceTag(pct: Int): String = when {
        pct >= 62 -> "Strong"
        pct >= 52 -> "Good"
        else -> "Lean"
    }

    fun normalizeFormScore(rawScore: Double, maxScale: Double = 5.0): Double {
        if (!rawScore.isFinite() || maxScale <= 0.0) return 0.5
        return (rawScore / maxScale).coerceIn(0.0, 1.0)
    }

    fun computeFeatureDrivers(
        sport: Sport,
        homeTeam: String,
        awayTeam: String,
        homePower: Int,
        awayPower: Int,
        homeWinPct: Double,
        awayWinPct: Double,
        homeFormScore: Double,
        awayFormScore: Double,
        hasWindOrRain: Boolean,
    ): List<FeatureDriver> {
        val drivers = mutableListOf<FeatureDriver>()
        val powerGap = (homePower - awayPower).toDouble()
        if (abs(powerGap) >= 1.0) {
            val impact = (powerGap * 0.45).coerceIn(-18.0, 18.0)
            drivers.add(
                FeatureDriver(
                    label = "Power Rating Edge",
                    impactPct = round1(impact),
                    isPositive = impact >= 0.0,
                    detail = if (impact >= 0) "$homeTeam rated +${homePower - awayPower} pts higher" else "$awayTeam rated +${awayPower - homePower} pts higher",
                )
            )
        }

        val normH = normalizeFormScore(homeFormScore)
        val normA = normalizeFormScore(awayFormScore)
        val formGap = (normH - normA) * 10.0
        if (abs(formGap) >= 0.5) {
            drivers.add(
                FeatureDriver(
                    label = "Last-5 Form Momentum",
                    impactPct = round1(formGap),
                    isPositive = formGap >= 0.0,
                    detail = if (formGap >= 0) "$homeTeam holds stronger recent form" else "$awayTeam holds stronger recent form",
                )
            )
        }

        val winGap = ((homeWinPct - awayWinPct) * 0.14).coerceIn(-12.0, 12.0)
        if (abs(winGap) >= 0.5) {
            drivers.add(
                FeatureDriver(
                    label = "Season Win Rate",
                    impactPct = round1(winGap),
                    isPositive = winGap >= 0.0,
                    detail = String.format(Locale.US, "%s %.0f%% vs %s %.0f%%", homeTeam, homeWinPct, awayTeam, awayWinPct),
                )
            )
        }

        if (sport != Sport.TENNIS && sport != Sport.F1 && sport != Sport.MMA) {
            val hfa = when (sport) {
                Sport.FOOTBALL -> 4.2
                Sport.NBA -> 3.6
                Sport.NFL -> 2.8
                else -> 2.4
            }
            drivers.add(
                FeatureDriver(
                    label = "Home Venue Advantage",
                    impactPct = hfa,
                    isPositive = true,
                    detail = "$homeTeam playing at home",
                )
            )
        }

        if (hasWindOrRain) {
            drivers.add(
                FeatureDriver(
                    label = "Live Stadium Weather",
                    impactPct = -2.2,
                    isPositive = false,
                    detail = "Elevated wind/rain increases variance and trims scoring",
                )
            )
        }
        return drivers.sortedByDescending { abs(it.impactPct) }
    }

    /**
     * Builds real chronological line-movement points exclusively from persisted Room
     * `OddsHistoryEntity` snapshots. Returns empty list if fewer than 2 real snapshots exist.
     */
    fun buildRealLineMovementSeries(
        history: List<OddsHistoryEntity>,
    ): List<LineMovePoint> {
        if (history.size < 2) return emptyList()
        val sorted = history.sortedBy { it.recordedAtEpochMs }
        val points = mutableListOf<LineMovePoint>()
        var prevSharpPct: Float? = null

        for (entry in sorted) {
            val sharpVal = (entry.sharpHomeProbPct ?: entry.homeProbPct).takeIf { it.isFinite() } ?: continue
            val retailVal = (entry.retailHomeProbPct ?: sharpVal).takeIf { it.isFinite() } ?: sharpVal
            val sharpPct = sharpVal.toFloat().coerceIn(1f, 99f)
            val retailPct = retailVal.toFloat().coerceIn(1f, 99f)
            val isSteam = prevSharpPct != null && abs(sharpPct - prevSharpPct) >= 3.0f
            val label = entry.label.ifBlank {
                TIME_LABEL_FMT.format(Instant.ofEpochMilli(entry.recordedAtEpochMs))
            }
            points.add(
                LineMovePoint(
                    timeLabel = label,
                    sharpHomeProbPct = (sharpPct * 10f).roundToInt() / 10f,
                    retailHomeProbPct = (retailPct * 10f).roundToInt() / 10f,
                    isSteamMove = isSteam,
                )
            )
            prevSharpPct = sharpPct
        }
        return if (points.size >= 2) points else emptyList()
    }

    /**
     * Beta(p*n + 1, (1-p)*n + 1) posterior 90% credible interval (variance denominator n + 3).
     */
    fun computeUncertaintyInterval(prob: Double, sampleSize: Int): Pair<Int, Int> {
        val p = if (prob.isFinite()) prob.coerceIn(0.01, 0.99) else 0.5
        val n = sampleSize.coerceAtLeast(0).toDouble()
        val alpha = p * n + 1.0
        val beta = (1.0 - p) * n + 1.0
        val mean = alpha / (alpha + beta)
        val sd = sqrt((alpha * beta) / ((alpha + beta).pow(2) * (alpha + beta + 1.0)))
        val halfWidth = 1.645 * sd
        val low = ((mean - halfWidth) * 100.0).roundToInt().coerceIn(1, 98)
        val high = ((mean + halfWidth) * 100.0).roundToInt().coerceIn(low + 1, 99)
        return low to high
    }
}
