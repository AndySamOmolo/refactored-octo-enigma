package com.edgeledger.app.engine

import com.edgeledger.app.model.LivePlayerEntry
import com.edgeledger.app.model.Sport
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
data class PlayerPropPrediction(
    val id: String,
    val playerName: String,
    val team: String,
    val position: String,
    val marketLabel: String,
    val lineLabel: String,
    val projectedValue: Double,
    val overChancePct: Int,
    val rangeLow: Double,
    val rangeHigh: Double,
    val confidenceLabel: String,
    val plainReason: String,
)

@Serializable
data class FeatureDriver(
    val label: String,
    val impactPct: Double,
    val isPositive: Boolean,
    val detail: String,
)

@Serializable
data class LineMovePoint(
    val timeLabel: String,
    val sharpHomeProbPct: Float,
    val retailHomeProbPct: Float,
    val isSteamMove: Boolean = false,
)

object PlayerPropsEngine {

    private const val Z_80 = 1.28155

    private enum class DistKind {
        POISSON_AT_LEAST,
        NORMAL_OVER,
    }

    private data class PropSpec(
        val playerName: String,
        val position: String,
        val marketLabel: String,
        val baseMean: Double,
        val baseStdDev: Double,
        val threshold: Double,
        val distKind: DistKind,
        val unitLabel: String,
        val inverseOpponentEffect: Boolean = false,
    )

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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
    ): List<PlayerPropPrediction> {
        // 100% Live Dataset Policy: only generate player props from live synced rosters/leaders
        val homeSpecs = buildLivePropSpecs(sport, liveHomePlayers)
        val awaySpecs = buildLivePropSpecs(sport, liveAwayPlayers)

        val homeProps = homeSpecs.mapIndexed { idx, spec ->
            evaluateProp(
                sport = sport,
                side = "home",
                index = idx,
                team = homeTeam,
                opponent = awayTeam,
                spec = spec,
                teamProjScore = projectedHomeScore,
                oppProjScore = projectedAwayScore,
                teamPower = homePower,
                oppPower = awayPower,
                isHome = true,
            )
        }

        val awayProps = awaySpecs.mapIndexed { idx, spec ->
            evaluateProp(
                sport = sport,
                side = "away",
                index = idx,
                team = awayTeam,
                opponent = homeTeam,
                spec = spec,
                teamProjScore = projectedAwayScore,
                oppProjScore = projectedHomeScore,
                teamPower = awayPower,
                oppPower = homePower,
                isHome = false,
            )
        }

        // Interleave home and away props so both sides stay balanced, capping to 6..8 total
        val combined = mutableListOf<PlayerPropPrediction>()
        val maxPairs = max(homeProps.size, awayProps.size)
        for (i in 0 until maxPairs) {
            if (i < homeProps.size) combined.add(homeProps[i])
            if (i < awayProps.size) combined.add(awayProps[i])
        }
        return combined.take(8)
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

        // 1. Overall Team Strength Gap
        val powerDiff = homePower - awayPower
        val powerImpact = round1((powerDiff * 0.42).coerceIn(-18.0, 18.0))
        val powerLeader = if (powerDiff >= 0) homeTeam else awayTeam
        val powerDetail = when {
            abs(powerDiff) <= 2 -> "$homeTeam and $awayTeam grade almost evenly on overall roster strength."
            else -> "$powerLeader holds a ${abs(powerDiff)}-point overall rating edge in this matchup."
        }
        drivers.add(
            FeatureDriver(
                label = "Team Strength Gap",
                impactPct = powerImpact,
                isPositive = powerImpact >= 0.0,
                detail = powerDetail,
            ),
        )

        // 2. Recent Form Momentum
        val normHomeForm = normalizeFormScore(homeFormScore)
        val normAwayForm = normalizeFormScore(awayFormScore)
        val formDiff = normHomeForm - normAwayForm
        val formImpact = round1((formDiff * 12.0).coerceIn(-10.0, 10.0))
        val formDetail = when {
            abs(formDiff) < 0.08 -> "Both sides enter with very similar recent results over their last few outings."
            formDiff > 0.0 -> "$homeTeam brings stronger recent momentum into this contest."
            else -> "$awayTeam has been sharper in recent games and carries form momentum."
        }
        drivers.add(
            FeatureDriver(
                label = "Recent Form",
                impactPct = formImpact,
                isPositive = formImpact >= 0.0,
                detail = formDetail,
            ),
        )

        // 3. Home Venue / Crowd Edge
        val homeEdge = when (sport) {
            Sport.FOOTBALL -> 4.8
            Sport.CRICKET -> 4.2
            Sport.NBA -> 3.6
            Sport.NHL -> 3.2
            Sport.NFL -> 2.8
            Sport.MLB -> 2.5
            Sport.TENNIS -> 1.2
            Sport.F1 -> 1.4
            Sport.MMA -> 1.0
        }
        val venueLabel = when (sport) {
            Sport.TENNIS -> "Surface & Crowd Comfort"
            Sport.F1 -> "Track Familiarity"
            Sport.MMA -> "Corner & Crowd Support"
            else -> "Home-Field Advantage"
        }
        val venueDetail = when (sport) {
            Sport.TENNIS -> "$homeTeam gets a modest lift from crowd backing and court conditions."
            Sport.F1 -> "$homeTeam holds a slight familiarity edge on this circuit layout."
            Sport.MMA -> "$homeTeam benefits slightly from local crowd energy and travel comfort."
            else -> "$homeTeam historically gains about ${format1(homeEdge)}% win probability playing at home."
        }
        drivers.add(
            FeatureDriver(
                label = venueLabel,
                impactPct = round1(homeEdge),
                isPositive = true,
                detail = venueDetail,
            ),
        )

        // 4. Matchup Win-Rate Edge
        val normHomeWin = normalizePct(homeWinPct)
        val normAwayWin = normalizePct(awayWinPct)
        val winGap = normHomeWin - normAwayWin
        val consensusImpact = round1((winGap * 0.14).coerceIn(-9.5, 9.5))
        val consensusDetail = when {
            abs(winGap) < 4.0 -> "Win-rate projections keep this matchup close to a coin flip."
            winGap > 0.0 -> "$homeTeam holds a ${format1(abs(winGap))}% historical win-rate gap over $awayTeam."
            else -> "$awayTeam holds a ${format1(abs(winGap))}% historical win-rate gap over $homeTeam."
        }
        drivers.add(
            FeatureDriver(
                label = "Matchup Win Profile",
                impactPct = consensusImpact,
                isPositive = consensusImpact >= 0.0,
                detail = consensusDetail,
            ),
        )

        // 5. Weather / Conditions (for outdoor sports when wind or rain is present)
        val isOutdoorSport = sport == Sport.FOOTBALL ||
            sport == Sport.NFL ||
            sport == Sport.MLB ||
            sport == Sport.CRICKET ||
            sport == Sport.TENNIS ||
            sport == Sport.F1

        if (hasWindOrRain && isOutdoorSport) {
            // Wet or windy weather compresses skill gaps, slightly pulling back the favorite
            val favoriteSign = if (powerDiff >= 0) -1.0 else 1.0
            val weatherMagnitude = when (sport) {
                Sport.NFL -> 3.4
                Sport.CRICKET -> 3.8
                Sport.MLB -> 2.9
                Sport.FOOTBALL -> 2.6
                Sport.F1 -> 3.5
                Sport.TENNIS -> 2.2
                else -> 2.0
            }
            val weatherImpact = round1(favoriteSign * weatherMagnitude)
            val weatherDetail = if (powerDiff >= 0) {
                "Wind or rain adds randomness and trims $homeTeam's edge by slowing clean execution."
            } else {
                "Wind or rain adds randomness and helps $homeTeam narrow the gap against $awayTeam."
            }
            drivers.add(
                FeatureDriver(
                    label = "Weather & Conditions",
                    impactPct = weatherImpact,
                    isPositive = weatherImpact >= 0.0,
                    detail = weatherDetail,
                ),
            )
        }

        return drivers.sortedByDescending { abs(it.impactPct) }
    }

    fun buildLineMovementSeries(
        fixtureId: String,
        currentSharpHomeProb: Double,
        currentRetailHomeProb: Double,
    ): List<LineMovePoint> {
        val labels = listOf("Open", "-48h", "-24h", "-6h", "-1h", "Now")
        val sharpNow = normalizePct(currentSharpHomeProb).coerceIn(5.0, 95.0)
        val retailNow = normalizePct(currentRetailHomeProb).coerceIn(5.0, 95.0)

        val rng = DeterministicRng(fnv1a64(fixtureId))
        val sharpRetailGap = sharpNow - retailNow

        // Determine whether this fixture has a sharp steam move (>= 3.0% single step)
        val forceSteam = abs(sharpRetailGap) >= 2.8 || rng.nextDouble() < 0.45
        val steamIndex = if (forceSteam) 2 + rng.nextInt(3) else -1 // step 2 (-24h), 3 (-6h), or 4 (-1h)
        val steamDirection = when {
            abs(sharpRetailGap) >= 1.2 -> if (sharpRetailGap >= 0.0) 1.0 else -1.0
            else -> if (rng.nextDouble() < 0.5) 1.0 else -1.0
        }

        // Generate 5 step deltas for sharp line, then anchor backward from sharpNow
        val sharpSteps = DoubleArray(5) { stepIdx ->
            val pointIdx = stepIdx + 1
            if (pointIdx == steamIndex) {
                val mag = 3.2 + rng.nextDouble() * 2.4 // 3.2% to 5.6% sharp steam step
                steamDirection * mag
            } else {
                (rng.nextDouble() * 2.6 - 1.3) // -1.3% to +1.3% normal market drift
            }
        }

        val sharpSeries = DoubleArray(6)
        sharpSeries[5] = sharpNow
        for (i in 4 downTo 0) {
            sharpSeries[i] = (sharpSeries[i + 1] - sharpSteps[i]).coerceIn(4.0, 96.0)
        }

        // Retail line tracks sharp with a lag and slightly smaller swings, ending at retailNow
        val retailOpenOffset = (rng.nextDouble() * 2.2 - 1.1)
        val retailSeries = DoubleArray(6)
        retailSeries[0] = (sharpSeries[0] + retailOpenOffset).coerceIn(4.0, 96.0)
        retailSeries[5] = retailNow
        for (i in 1..4) {
            val progress = i / 5.0
            val laggedSharp = sharpSeries[i - 1] * 0.55 + sharpSeries[i] * 0.45
            val targetBlend = retailSeries[0] * (1.0 - progress) + retailNow * progress
            val noise = (rng.nextDouble() * 1.2 - 0.6)
            retailSeries[i] = (laggedSharp * 0.50 + targetBlend * 0.50 + noise).coerceIn(4.0, 96.0)
        }

        return labels.mapIndexed { idx, label ->
            val sharpPct = round1(sharpSeries[idx]).toFloat()
            val retailPct = round1(retailSeries[idx]).toFloat()
            val prevSharpPct = if (idx > 0) round1(sharpSeries[idx - 1]).toFloat() else sharpPct
            val isSteam = idx > 0 && abs(sharpPct - prevSharpPct) >= 3.0f
            LineMovePoint(
                timeLabel = label,
                sharpHomeProbPct = sharpPct,
                retailHomeProbPct = retailPct,
                isSteamMove = isSteam,
            )
        }
    }

    fun computeUncertaintyInterval(
        prob: Double,
        sampleSize: Int,
    ): Pair<Int, Int> {
        val p = (if (prob > 1.0) prob / 100.0 else prob).coerceIn(0.01, 0.99)
        val n = max(1, sampleSize)

        // Posterior standard error with Beta(1, 1) prior stabilization
        val baseSe = sqrt((p * (1.0 - p)) / (n + 2.0))

        // Widen interval explicitly when sampleSize < 5 (PR-01 small-sample safeguard)
        val smallSampleMultiplier = if (sampleSize < 5) {
            1.0 + (5 - max(0, sampleSize)) * 0.22
        } else {
            1.0
        }

        var halfWidthPct = Z_80 * baseSe * 100.0 * smallSampleMultiplier
        if (sampleSize < 5) {
            halfWidthPct = max(halfWidthPct, 8.0 + (4 - max(0, sampleSize)) * 2.0)
        } else {
            halfWidthPct = max(halfWidthPct, 2.5)
        }
        halfWidthPct = min(halfWidthPct, 35.0)

        val centerPct = p * 100.0
        val low = (centerPct - halfWidthPct).roundToInt().coerceIn(1, 97)
        val high = (centerPct + halfWidthPct).roundToInt().coerceIn(low + 2, 99)
        return Pair(low, high)
    }

    // -------------------------------------------------------------------------
    // Prop Evaluation & Probability Math
    // -------------------------------------------------------------------------

    private fun evaluateProp(
        sport: Sport,
        side: String,
        index: Int,
        team: String,
        opponent: String,
        spec: PropSpec,
        teamProjScore: Double,
        oppProjScore: Double,
        teamPower: Int,
        oppPower: Int,
        isHome: Boolean,
    ): PlayerPropPrediction {
        val baselineScore = sportBaselineScore(sport, teamProjScore)
        val rawScoreFactor = if (baselineScore > 0.0) teamProjScore / baselineScore else 1.0
        val scoreFactor = if (spec.inverseOpponentEffect) {
            val oppBase = sportBaselineScore(sport, oppProjScore)
            val oppFactor = if (oppBase > 0.0) oppProjScore / oppBase else 1.0
            (0.65 + 0.35 * oppFactor).coerceIn(0.78, 1.32)
        } else {
            (0.45 + 0.55 * rawScoreFactor).coerceIn(0.70, 1.40)
        }

        val powerDelta = (teamPower - oppPower) / 100.0
        val powerFactor = if (spec.inverseOpponentEffect) {
            (1.0 - powerDelta * 0.28).coerceIn(0.82, 1.24)
        } else {
            (1.0 + powerDelta * 0.22).coerceIn(0.80, 1.25)
        }

        val homeBump = if (isHome && !spec.inverseOpponentEffect) 1.03 else 1.0
        val adjustedMean = max(0.08, spec.baseMean * scoreFactor * powerFactor * homeBump)

        val overProb: Double
        val rangeLow: Double
        val rangeHigh: Double
        val lineLabel: String

        when (spec.distKind) {
            DistKind.POISSON_AT_LEAST -> {
                val k = max(1, spec.threshold.roundToInt())
                overProb = poissonAtLeast(adjustedMean, k).coerceIn(0.05, 0.94)
                val sd = sqrt(adjustedMean)
                rangeLow = round1(max(0.0, adjustedMean - Z_80 * sd))
                rangeHigh = round1(adjustedMean + Z_80 * sd)
                lineLabel = buildPoissonLineLabel(spec, k)
            }

            DistKind.NORMAL_OVER -> {
                val scaledStd = max(0.4, spec.baseStdDev * sqrt(scoreFactor))
                val line = spec.threshold
                overProb = (1.0 - normalCdf(line, adjustedMean, scaledStd)).coerceIn(0.06, 0.94)
                rangeLow = round1(max(0.0, adjustedMean - Z_80 * scaledStd))
                rangeHigh = round1(adjustedMean + Z_80 * scaledStd)
                lineLabel = "Over ${format1(line)} ${spec.unitLabel}"
            }
        }

        val overPct = (overProb * 100.0).roundToInt().coerceIn(5, 95)
        val confidence = confidenceFromProbability(overPct)
        val reason = buildPlainReason(
            spec = spec,
            team = team,
            opponent = opponent,
            adjustedMean = adjustedMean,
            scoreFactor = scoreFactor,
            teamPower = teamPower,
            oppPower = oppPower,
        )

        return PlayerPropPrediction(
            id = "${sport.name.lowercase()}_${side}_${index}_${sanitizeId(spec.playerName)}_${sanitizeId(spec.marketLabel)}",
            playerName = spec.playerName,
            team = team,
            position = spec.position,
            marketLabel = spec.marketLabel,
            lineLabel = lineLabel,
            projectedValue = round1(adjustedMean),
            overChancePct = overPct,
            rangeLow = rangeLow,
            rangeHigh = rangeHigh,
            confidenceLabel = confidence,
            plainReason = reason,
        )
    }

    private fun buildPoissonLineLabel(spec: PropSpec, k: Int): String {
        return when {
            spec.marketLabel.contains("Goalscorer", ignoreCase = true) -> "Yes (1+ Goal)"
            spec.marketLabel.contains("Touchdown", ignoreCase = true) -> "Yes (1+ TD)"
            spec.marketLabel.contains("Booked", ignoreCase = true) -> "To Be Booked"
            spec.marketLabel.contains("Assist", ignoreCase = true) -> "Yes (1+ Assist)"
            spec.marketLabel.contains("Anytime Goal", ignoreCase = true) -> "Yes (1+ Goal)"
            spec.marketLabel.contains("Total Bases", ignoreCase = true) -> "Over 1.5 Bases"
            spec.marketLabel.contains("Aces", ignoreCase = true) ||
                spec.marketLabel.contains("Double Faults", ignoreCase = true) ||
                spec.marketLabel.contains("Break Points", ignoreCase = true) ||
                spec.marketLabel.contains("3-Pointers", ignoreCase = true) -> {
                val halfLine = max(0.5, k - 0.5)
                "Over ${format1(halfLine)} ${spec.unitLabel}"
            }
            else -> "$k+ ${spec.unitLabel}"
        }
    }

    private fun buildPlainReason(
        spec: PropSpec,
        team: String,
        opponent: String,
        adjustedMean: Double,
        scoreFactor: Double,
        teamPower: Int,
        oppPower: Int,
    ): String {
        val paceText = when {
            spec.inverseOpponentEffect && oppPower > teamPower + 3 ->
                "$opponent's pressure raises defensive workload and foul risk."
            spec.inverseOpponentEffect ->
                "A physical midfield battle keeps card risk active against $opponent."
            scoreFactor >= 1.12 ->
                "$team's high projected scoring creates extra chances against $opponent."
            scoreFactor <= 0.88 ->
                "A tighter scoring outlook against $opponent keeps the projection measured."
            teamPower > oppPower + 4 ->
                "$team's matchup advantage supports steady volume against $opponent."
            else ->
                "Balanced matchup pace against $opponent keeps ${spec.playerName} near seasonal averages."
        }
        return "Projected at ${format1(adjustedMean)} ${spec.unitLabel.lowercase()} — $paceText"
    }

    private fun confidenceFromProbability(overPct: Int): String = when {
        overPct >= 68 -> "High Confidence"
        overPct >= 55 -> "Solid Lean"
        overPct >= 42 -> "Balanced"
        else -> "Longshot"
    }

    private fun sportBaselineScore(sport: Sport, observedScore: Double): Double = when (sport) {
        Sport.FOOTBALL -> 1.45
        Sport.NBA -> 113.0
        Sport.NFL -> 23.0
        Sport.NHL -> 3.1
        Sport.MLB -> 4.5
        Sport.TENNIS -> if (observedScore > 6.0) 13.5 else 2.0
        Sport.CRICKET -> if (observedScore > 210.0) 275.0 else 165.0
        Sport.F1 -> 12.0
        Sport.MMA -> 2.5
    }

    // -------------------------------------------------------------------------
    // Pure Kotlin Statistical Functions
    // -------------------------------------------------------------------------

    private fun poissonAtLeast(lambda: Double, k: Int): Double {
        if (k <= 0) return 1.0
        if (lambda <= 0.0) return 0.0
        var cumulativeBelow = 0.0
        for (i in 0 until k) {
            cumulativeBelow += poissonPmf(lambda, i)
        }
        return (1.0 - cumulativeBelow).coerceIn(0.0, 1.0)
    }

    private fun poissonPmf(lambda: Double, k: Int): Double {
        if (k < 0 || lambda <= 0.0) return 0.0
        val logP = k * ln(lambda) - lambda - logFactorial(k)
        return exp(logP)
    }

    private fun logFactorial(n: Int): Double {
        if (n <= 1) return 0.0
        var sum = 0.0
        for (i in 2..n) {
            sum += ln(i.toDouble())
        }
        return sum
    }

    private fun normalCdf(x: Double, mean: Double, stdDev: Double): Double {
        val z = (x - mean) / (stdDev * sqrt(2.0))
        return 0.5 * (1.0 + erf(z))
    }

    // Abramowitz and Stegun 7.1.26 approximation (max error ~1.5e-7)
    private fun erf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        val y = 1.0 - poly * exp(-ax * ax)
        return sign * y
    }

    // -------------------------------------------------------------------------
    // Embedded Real Player Profiles Across Major Teams & Sports
    // -------------------------------------------------------------------------

    /**
     * Builds player prop projections directly from LIVE synced 2026-27 ESPN/NHL rosters & season leaders.
     * Guarantees zero stale transferred players whenever synced on-device.
     */
    private fun buildLivePropSpecs(sport: Sport, players: List<LivePlayerEntry>): List<PropSpec> {
        val clean = players.filter { it.name.isNotBlank() }.distinctBy { it.name.lowercase() }
        if (clean.isEmpty()) return emptyList()

        return when (sport) {
            Sport.FOOTBALL -> {
                val forwards = clean.filter { it.position in setOf("F", "FW", "ST", "CF", "LW", "RW", "FORWARD", "ATTACKER") }
                val mids = clean.filter { it.position in setOf("M", "MF", "AM", "CM", "DM", "MIDFIELDER") }
                val defs = clean.filter { it.position in setOf("D", "DF", "CB", "LB", "RB", "DEFENDER") }
                val p1 = forwards.getOrNull(0) ?: clean.getOrNull(0)
                val p2 = forwards.getOrNull(1) ?: mids.getOrNull(0) ?: clean.getOrNull(1)
                val p3 = mids.getOrNull(0)?.takeIf { it != p2 } ?: mids.getOrNull(1) ?: clean.getOrNull(2)
                val p4 = defs.getOrNull(0) ?: mids.lastOrNull() ?: clean.getOrNull(3)
                buildList {
                    p1?.let { add(fbGoal(it.name, it.position.ifBlank { "FW" }, 0.44)) }
                    p2?.let { add(fbSot(it.name, it.position.ifBlank { "FW" }, 1.20)) }
                    p3?.let { add(fbAssist(it.name, it.position.ifBlank { "MF" }, 0.28)) }
                    p4?.let { add(fbCard(it.name, it.position.ifBlank { "DF" }, 0.28)) }
                }
            }
            Sport.NBA -> {
                val ptsLead = clean.firstOrNull { "point" in it.statCategory } ?: clean.getOrNull(0)
                val rebLead = clean.firstOrNull { "rebound" in it.statCategory } ?: clean.firstOrNull { it.position == "C" || it.position == "PF" } ?: clean.getOrNull(1)
                val astLead = clean.firstOrNull { "assist" in it.statCategory } ?: clean.firstOrNull { it.position == "PG" } ?: clean.getOrNull(2)
                val p4 = clean.firstOrNull { it != ptsLead && it != rebLead } ?: clean.getOrNull(3) ?: ptsLead
                buildList {
                    ptsLead?.let {
                        val avg = (it.statValue ?: 22.5).coerceIn(12.0, 36.0)
                        val line = avg.toInt() - 0.5
                        add(nbaPts(it.name, it.position.ifBlank { "F" }, avg, 5.6, line))
                    }
                    rebLead?.let {
                        val avg = (it.statValue ?: 8.6).coerceIn(4.5, 15.0)
                        val line = avg.toInt() - 0.5
                        add(nbaReb(it.name, it.position.ifBlank { "C" }, avg, 2.5, line))
                    }
                    astLead?.let {
                        val avg = (it.statValue ?: 6.2).coerceIn(3.5, 12.0)
                        val line = avg.toInt() - 0.5
                        add(nbaAst(it.name, it.position.ifBlank { "PG" }, avg, 2.1, line))
                    }
                    p4?.let { add(nbaThrees(it.name, it.position.ifBlank { "SG" }, 2.6, 2.5)) }
                }
            }
            Sport.NFL -> {
                val qb = clean.firstOrNull { "pass" in it.statCategory || it.position == "QB" } ?: clean.getOrNull(0)
                val rb = clean.firstOrNull { "rush" in it.statCategory || it.position == "RB" } ?: clean.getOrNull(1)
                val wr = clean.firstOrNull { "receiv" in it.statCategory || it.position == "WR" || it.position == "TE" } ?: clean.getOrNull(2)
                buildList {
                    qb?.let {
                        val avg = (it.statValue?.takeIf { v -> v in 150.0..340.0 } ?: 245.0)
                        add(nflPassYds(it.name, "QB", avg, 52.0, (avg - 10.0).toInt() + 0.5))
                    }
                    rb?.let {
                        val avg = (it.statValue?.takeIf { v -> v in 35.0..135.0 } ?: 72.0)
                        add(nflRushYds(it.name, "RB", avg, 26.0, (avg - 6.0).toInt() + 0.5))
                        add(nflTd(it.name, "RB", 0.62))
                    }
                    wr?.let {
                        val avg = (it.statValue?.takeIf { v -> v in 35.0..135.0 } ?: 76.0)
                        add(nflRecYds(it.name, it.position.ifBlank { "WR" }, avg, 27.0, (avg - 6.0).toInt() + 0.5))
                    }
                }
            }
            Sport.NHL -> {
                val fwds = clean.filter { it.position in setOf("C", "L", "R", "LW", "RW", "F") }
                val p1 = fwds.getOrNull(0) ?: clean.getOrNull(0)
                val p2 = fwds.getOrNull(1) ?: clean.getOrNull(1)
                val p3 = fwds.getOrNull(2) ?: clean.getOrNull(2)
                buildList {
                    p1?.let { add(nhlGoal(it.name, it.position.ifBlank { "F" }, 0.44)) }
                    p2?.let { add(nhlSog(it.name, it.position.ifBlank { "F" }, 3.15)) }
                    p3?.let { add(nhlPoint(it.name, it.position.ifBlank { "F" }, 0.86)) }
                }
            }
            else -> emptyList()
        }.ifEmpty { emptyList() }
    }

    private fun resolveTeamProps(sport: Sport, teamName: String, isHome: Boolean): List<PropSpec> {
        val key = teamName.trim().lowercase()
        return when (sport) {
            Sport.FOOTBALL -> footballPropsFor(key, teamName)
            Sport.NBA -> nbaPropsFor(key, teamName)
            Sport.NFL -> nflPropsFor(key, teamName)
            Sport.NHL -> nhlPropsFor(key, teamName)
            Sport.MLB -> mlbPropsFor(key, teamName)
            Sport.TENNIS -> tennisPropsFor(key, teamName)
            Sport.CRICKET -> cricketPropsFor(key, teamName)
            Sport.F1 -> f1PropsFor(teamName, isHome)
            Sport.MMA -> mmaPropsFor(teamName)
        }
    }

    private fun footballPropsFor(key: String, team: String): List<PropSpec> = when {
        key.contains("arsenal") -> listOf(
            fbGoal("Viktor Gyokeres", "ST", 0.54),
            fbSot("Bukayo Saka", "RW", 1.32),
            fbAssist("Martin Odegaard", "AM", 0.34),
            fbCard("Bruno Guimaraes", "CM", 0.28),
        )
        key.contains("liverpool") -> listOf(
            fbGoal("Alexander Isak", "ST", 0.62),
            fbAssist("Florian Wirtz", "AM", 0.38),
            fbSot("Mohamed Salah", "RW", 1.35),
            fbCard("Alexis Mac Allister", "CM", 0.26),
        )
        key.contains("man city") || key.contains("manchester city") -> listOf(
            fbGoal("Erling Haaland", "ST", 0.78),
            fbSot("Phil Foden", "AM", 1.28),
            fbAssist("Rayan Cherki", "AM", 0.35),
            fbShots("Jeremy Doku", "LW", 2.35),
        )
        key.contains("chelsea") -> listOf(
            fbGoal("Joao Pedro", "ST", 0.48),
            fbSot("Cole Palmer", "AM", 1.34),
            fbShots("Morgan Rogers", "AM", 2.30),
            fbCard("Moises Caicedo", "DM", 0.32),
        )
        key.contains("newcastle") -> listOf(
            fbGoal("Will Osula", "ST", 0.40),
            fbSot("Anthony Elanga", "RW", 1.12),
            fbShots("Bazoumana Toure", "LW", 2.10),
            fbCard("Tino Livramento", "RB", 0.24),
        )
        key.contains("man utd") || key.contains("manchester united") || key.contains("man united") -> listOf(
            fbSot("Bruno Fernandes", "AM", 1.20),
            fbGoal("Rasmus Hojlund", "ST", 0.41),
            fbShots("Alejandro Garnacho", "LW", 2.50),
            fbCard("Casemiro", "DM", 0.33),
        )
        key.contains("real madrid") -> listOf(
            fbGoal("Kylian Mbappe", "ST", 0.66),
            fbSot("Vinicius Junior", "LW", 1.42),
            fbAssist("Jude Bellingham", "AM", 0.31),
            fbCard("Aurelien Tchouameni", "DM", 0.26),
        )
        key.contains("barcelona") || key.contains("barca") -> listOf(
            fbGoal("Robert Lewandowski", "ST", 0.64),
            fbAssist("Lamine Yamal", "RW", 0.38),
            fbShots("Raphinha", "LW", 2.85),
            fbCard("Gavi", "CM", 0.31),
        )
        key.contains("bayern") -> listOf(
            fbGoal("Harry Kane", "ST", 0.72),
            fbSot("Jamal Musiala", "AM", 1.32),
            fbAssist("Michael Olise", "RW", 0.35),
            fbCard("Joshua Kimmich", "DM", 0.23),
        )
        key.contains("inter") -> listOf(
            fbGoal("Lautaro Martinez", "ST", 0.56),
            fbSot("Marcus Thuram", "ST", 1.24),
            fbAssist("Hakan Calhanoglu", "CM", 0.28),
            fbCard("Nicolo Barella", "CM", 0.29),
        )
        key.contains("psg") || key.contains("paris") -> listOf(
            fbGoal("Ousmane Dembele", "RW", 0.46),
            fbSot("Bradley Barcola", "LW", 1.28),
            fbAssist("Achraf Hakimi", "RB", 0.30),
            fbCard("Vitinha", "CM", 0.22),
        )
        else -> {
            val short = shortClubLabel(team)
            listOf(
                fbGoal("$short Lead Striker", "ST", 0.43),
                fbSot("$short Attacking Winger", "RW", 1.12),
                fbShots("$short Playmaker", "AM", 2.25),
                fbCard("$short Holding Midfielder", "DM", 0.28),
            )
        }
    }

    private fun fbGoal(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "Anytime Goalscorer", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Goals")

    private fun fbSot(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "1+ SOT", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Shots on Target")

    private fun fbShots(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "2+ Total Shots", mean, 0.0, 2.0, DistKind.POISSON_AT_LEAST, "Total Shots")

    private fun fbAssist(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "Anytime Assist", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Assists")

    private fun fbCard(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "To Be Booked (Yellow Card)", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Cards", inverseOpponentEffect = true)

    private fun nbaPropsFor(key: String, team: String): List<PropSpec> = when {
        key.contains("76ers") || key.contains("philadelphia") -> listOf(
            nbaPts("Joel Embiid", "C", 27.5, 6.2, 26.5),
            nbaReb("Joel Embiid", "C", 10.8, 2.8, 10.5),
            nbaAst("Tyrese Maxey", "PG", 6.4, 2.2, 5.5),
            nbaThrees("Tyrese Maxey", "PG", 3.1, 2.5),
        )
        key.contains("bucks") || key.contains("milwaukee") -> listOf(
            nbaPts("Giannis Antetokounmpo", "PF", 30.4, 6.4, 29.5),
            nbaReb("Giannis Antetokounmpo", "PF", 11.6, 2.9, 11.5),
            nbaAst("Damian Lillard", "PG", 7.1, 2.3, 6.5),
            nbaThrees("Damian Lillard", "PG", 3.3, 2.5),
        )
        key.contains("kings") || key.contains("sacramento") -> listOf(
            nbaPts("De'Aaron Fox", "PG", 26.2, 5.8, 25.5),
            nbaReb("Domantas Sabonis", "C", 13.4, 3.0, 12.5),
            nbaAst("Domantas Sabonis", "C", 7.2, 2.3, 6.5),
            nbaThrees("Keegan Murray", "PF", 2.5, 2.5),
        )
        key.contains("blazers") || key.contains("portland") -> listOf(
            nbaPts("Anfernee Simons", "SG", 20.8, 5.4, 19.5),
            nbaReb("Deandre Ayton", "C", 10.6, 2.7, 9.5),
            nbaAst("Scoot Henderson", "PG", 5.8, 2.1, 5.5),
            nbaThrees("Anfernee Simons", "SG", 3.1, 2.5),
        )
        key.contains("mavericks") || key.contains("dallas") -> listOf(
            nbaPts("Luka Doncic", "PG", 31.2, 6.6, 30.5),
            nbaReb("Luka Doncic", "PG", 8.8, 2.5, 8.5),
            nbaAst("Luka Doncic", "PG", 9.1, 2.6, 8.5),
            nbaThrees("Kyrie Irving", "SG", 2.9, 2.5),
        )
        key.contains("clippers") -> listOf(
            nbaPts("Kawhi Leonard", "SF", 23.4, 5.6, 22.5),
            nbaReb("Ivica Zubac", "C", 11.4, 2.8, 10.5),
            nbaAst("James Harden", "PG", 8.6, 2.5, 8.5),
            nbaThrees("James Harden", "PG", 2.8, 2.5),
        )
        key.contains("timberwolves") || key.contains("minnesota") -> listOf(
            nbaPts("Anthony Edwards", "SG", 27.4, 6.1, 26.5),
            nbaReb("Rudy Gobert", "C", 11.8, 2.9, 11.5),
            nbaAst("Mike Conley", "PG", 5.6, 2.0, 5.5),
            nbaThrees("Anthony Edwards", "SG", 3.4, 2.5),
        )
        key.contains("grizzlies") || key.contains("memphis") -> listOf(
            nbaPts("Ja Morant", "PG", 25.4, 5.9, 24.5),
            nbaReb("Jaren Jackson Jr.", "PF", 6.5, 2.3, 6.5),
            nbaAst("Ja Morant", "PG", 8.2, 2.4, 7.5),
            nbaThrees("Desmond Bane", "SG", 3.0, 2.5),
        )
        key.contains("spurs") || key.contains("san antonio") -> listOf(
            nbaPts("Victor Wembanyama", "C", 24.2, 5.8, 23.5),
            nbaReb("Victor Wembanyama", "C", 10.8, 2.8, 10.5),
            nbaAst("Chris Paul", "PG", 8.1, 2.4, 7.5),
            nbaThrees("Devin Vassell", "SG", 2.5, 2.5),
        )
        key.contains("pelicans") || key.contains("new orleans") -> listOf(
            nbaPts("Zion Williamson", "PF", 23.8, 5.7, 22.5),
            nbaReb("Zion Williamson", "PF", 6.8, 2.3, 6.5),
            nbaAst("Brandon Ingram", "SF", 5.4, 2.0, 5.5),
            nbaThrees("CJ McCollum", "SG", 3.1, 2.5),
        )
        key.contains("suns") || key.contains("phoenix") -> listOf(
            nbaPts("Kevin Durant", "PF", 27.2, 5.9, 26.5),
            nbaReb("Jusuf Nurkic", "C", 9.8, 2.6, 9.5),
            nbaAst("Devin Booker", "SG", 6.6, 2.2, 6.5),
            nbaThrees("Devin Booker", "SG", 2.5, 2.5),
        )
        key.contains("heat") || key.contains("miami") -> listOf(
            nbaPts("Tyler Herro", "SG", 21.8, 5.4, 21.5),
            nbaReb("Bam Adebayo", "C", 10.2, 2.7, 9.5),
            nbaAst("Jimmy Butler", "SF", 5.2, 2.0, 4.5),
            nbaThrees("Tyler Herro", "SG", 3.2, 2.5),
        )
        key.contains("cavaliers") || key.contains("cleveland") -> listOf(
            nbaPts("Donovan Mitchell", "SG", 25.8, 5.9, 25.5),
            nbaReb("Evan Mobley", "PF", 9.4, 2.5, 9.5),
            nbaAst("Darius Garland", "PG", 6.8, 2.2, 6.5),
            nbaThrees("Donovan Mitchell", "SG", 3.3, 2.5),
        )
        key.contains("pacers") || key.contains("indiana") -> listOf(
            nbaPts("Pascal Siakam", "PF", 21.4, 5.2, 20.5),
            nbaReb("Myles Turner", "C", 7.2, 2.3, 7.5),
            nbaAst("Tyrese Haliburton", "PG", 9.6, 2.7, 9.5),
            nbaThrees("Tyrese Haliburton", "PG", 3.0, 2.5),
        )
        key.contains("celtics") || key.contains("boston") -> listOf(
            nbaPts("Jayson Tatum", "SF", 27.6, 6.2, 26.5),
            nbaReb("Kristaps Porzingis", "C", 7.8, 2.5, 7.5),
            nbaAst("Derrick White", "PG", 5.2, 2.0, 4.5),
            nbaThrees("Jaylen Brown", "SG", 2.6, 2.5),
        )
        key.contains("nuggets") || key.contains("denver") -> listOf(
            nbaPts("Nikola Jokic", "C", 28.4, 6.0, 27.5),
            nbaReb("Nikola Jokic", "C", 12.6, 3.1, 11.5),
            nbaAst("Jamal Murray", "PG", 6.4, 2.2, 5.5),
            nbaThrees("Michael Porter Jr.", "SF", 2.9, 2.5),
        )
        key.contains("thunder") || key.contains("oklahoma") || key.contains("okc") -> listOf(
            nbaPts("Shai Gilgeous-Alexander", "PG", 30.8, 5.8, 29.5),
            nbaReb("Chet Holmgren", "C", 8.6, 2.6, 8.5),
            nbaAst("Jalen Williams", "SF", 5.4, 2.0, 4.5),
            nbaThrees("Isaiah Joe", "SG", 2.7, 2.5),
        )
        key.contains("lakers") -> listOf(
            nbaPts("Anthony Davis", "C", 26.8, 6.1, 25.5),
            nbaReb("Anthony Davis", "C", 12.2, 3.0, 11.5),
            nbaAst("LeBron James", "SF", 8.4, 2.4, 7.5),
            nbaThrees("Austin Reaves", "SG", 2.4, 2.5),
        )
        key.contains("warriors") || key.contains("golden state") -> listOf(
            nbaPts("Stephen Curry", "PG", 26.4, 6.4, 25.5),
            nbaThrees("Stephen Curry", "PG", 4.6, 4.5),
            nbaAst("Draymond Green", "PF", 6.1, 2.1, 5.5),
            nbaReb("Andrew Wiggins", "SF", 5.2, 2.0, 4.5),
        )
        key.contains("knicks") || key.contains("new york") -> listOf(
            nbaPts("Jalen Brunson", "PG", 27.2, 6.0, 26.5),
            nbaReb("Karl-Anthony Towns", "C", 11.8, 2.9, 11.5),
            nbaAst("Josh Hart", "SG", 5.6, 2.0, 5.5),
            nbaThrees("OG Anunoby", "SF", 2.4, 2.5),
        )
        else -> {
            val short = shortClubLabel(team)
            listOf(
                nbaPts("$short Primary Scorer", "SF", 23.4, 5.8, 22.5),
                nbaReb("$short Starting Center", "C", 9.6, 2.8, 9.5),
                nbaAst("$short Lead Guard", "PG", 6.8, 2.2, 6.5),
                nbaThrees("$short Wing Shooter", "SG", 2.7, 2.5),
            )
        }
    }

    private fun nbaPts(name: String, pos: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, pos, "Points Over/Under", mean, sd, line, DistKind.NORMAL_OVER, "Pts")

    private fun nbaReb(name: String, pos: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, pos, "Rebounds Over/Under", mean, sd, line, DistKind.NORMAL_OVER, "Reb")

    private fun nbaAst(name: String, pos: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, pos, "Assists Over/Under", mean, sd, line, DistKind.NORMAL_OVER, "Ast")

    private fun nbaThrees(name: String, pos: String, mean: Double, line: Double) =
        PropSpec(name, pos, "3-Pointers Made", mean, 0.0, floor(line) + 1.0, DistKind.POISSON_AT_LEAST, "3PM")

    private fun nflPropsFor(key: String, team: String): List<PropSpec> = when {
        key.contains("chiefs") || key.contains("kansas city") -> listOf(
            nflPass("Patrick Mahomes", 268.0, 48.0, 259.5),
            nflRush("Isiah Pacheco", 68.0, 24.0, 62.5),
            nflRec("Travis Kelce", "TE", 64.0, 23.0, 59.5),
            nflTd("Xavier Worthy", "WR", 0.52),
        )
        key.contains("49ers") || key.contains("san francisco") -> listOf(
            nflPass("Brock Purdy", 256.0, 46.0, 249.5),
            nflRush("Christian McCaffrey", 84.0, 27.0, 77.5),
            nflRec("George Kittle", "TE", 62.0, 24.0, 57.5),
            nflTd("Christian McCaffrey", "RB", 0.88),
        )
        key.contains("eagles") || key.contains("philadelphia") -> listOf(
            nflPass("Jalen Hurts", 232.0, 45.0, 224.5),
            nflRush("Saquon Barkley", 92.0, 29.0, 84.5),
            nflRec("A.J. Brown", "WR", 82.0, 28.0, 74.5),
            nflTd("Jalen Hurts", "QB", 0.72),
        )
        key.contains("ravens") || key.contains("baltimore") -> listOf(
            nflPass("Lamar Jackson", 244.0, 46.0, 234.5),
            nflRush("Derrick Henry", 88.0, 28.0, 81.5),
            nflRec("Zay Flowers", "WR", 66.0, 24.0, 60.5),
            nflTd("Derrick Henry", "RB", 0.84),
        )
        key.contains("bills") || key.contains("buffalo") -> listOf(
            nflPass("Josh Allen", 258.0, 49.0, 249.5),
            nflRush("James Cook", 66.0, 24.0, 61.5),
            nflRec("Khalil Shakir", "WR", 61.0, 22.0, 56.5),
            nflTd("Josh Allen", "QB", 0.68),
        )
        key.contains("lions") || key.contains("detroit") -> listOf(
            nflPass("Jared Goff", 262.0, 45.0, 254.5),
            nflRush("Jahmyr Gibbs", 74.0, 26.0, 68.5),
            nflRec("Amon-Ra St. Brown", "WR", 84.0, 26.0, 78.5),
            nflTd("David Montgomery", "RB", 0.76),
        )
        else -> {
            val short = shortClubLabel(team)
            listOf(
                nflPass("$short Starting QB", 238.0, 46.0, 232.5),
                nflRush("$short Lead RB", 67.0, 24.0, 62.5),
                nflRec("$short WR1 Target", "WR", 68.0, 25.0, 63.5),
                nflTd("$short Lead RB", "RB", 0.58),
            )
        }
    }

    private fun nflPass(name: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, "QB", "Passing Yards", mean, sd, line, DistKind.NORMAL_OVER, "Pass Yds")

    private fun nflRush(name: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, "RB", "Rushing Yards", mean, sd, line, DistKind.NORMAL_OVER, "Rush Yds")

    private fun nflRec(name: String, pos: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, pos, "Receiving Yards", mean, sd, line, DistKind.NORMAL_OVER, "Rec Yds")

    private fun nflTd(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "Anytime Touchdown", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "TDs")

    private fun nhlPropsFor(key: String, team: String): List<PropSpec> = when {
        key.contains("oilers") || key.contains("edmonton") -> listOf(
            nhlPoint("Connor McDavid", "C", 1.48),
            nhlGoal("Leon Draisaitl", "C", 0.62),
            nhlShots("Zach Hyman", "LW", 3.45),
        )
        key.contains("panthers") || key.contains("florida") -> listOf(
            nhlPoint("Aleksander Barkov", "C", 1.15),
            nhlGoal("Sam Reinhart", "RW", 0.54),
            nhlShots("Matthew Tkachuk", "LW", 3.35),
        )
        key.contains("rangers") || key.contains("new york") -> listOf(
            nhlPoint("Artemi Panarin", "LW", 1.32),
            nhlGoal("Chris Kreider", "LW", 0.48),
            nhlShots("Mika Zibanejad", "C", 3.15),
        )
        key.contains("avalanche") || key.contains("colorado") -> listOf(
            nhlPoint("Nathan MacKinnon", "C", 1.52),
            nhlGoal("Mikko Rantanen", "RW", 0.56),
            nhlShots("Cale Makar", "D", 3.25),
        )
        else -> {
            val short = shortClubLabel(team)
            listOf(
                nhlPoint("$short First-Line Center", "C", 1.08),
                nhlGoal("$short Top-Line Winger", "RW", 0.44),
                nhlShots("$short Power-Play Wing", "LW", 3.05),
            )
        }
    }

    private fun nhlGoal(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "Anytime Goal", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Goals")

    private fun nhlShots(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "3+ Shots on Goal", mean, 0.0, 3.0, DistKind.POISSON_AT_LEAST, "SOG")

    private fun nhlPoint(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "1+ Point", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Points")

    private fun mlbPropsFor(key: String, team: String): List<PropSpec> = when {
        key.contains("dodgers") || key.contains("los angeles") -> listOf(
            mlbBases("Shohei Ohtani", "DH", 2.35),
            mlbHit("Mookie Betts", "RF", 1.24),
            mlbStrikeouts("Tyler Glasnow", 6.8, 2.1, 6.5),
        )
        key.contains("yankees") || key.contains("new york") -> listOf(
            mlbBases("Aaron Judge", "CF", 2.40),
            mlbHit("Juan Soto", "RF", 1.18),
            mlbStrikeouts("Gerrit Cole", 6.4, 2.0, 5.5),
        )
        key.contains("braves") || key.contains("atlanta") -> listOf(
            mlbHit("Ronald Acuna Jr.", "RF", 1.28),
            mlbBases("Matt Olson", "1B", 2.05),
            mlbStrikeouts("Chris Sale", 6.9, 2.1, 6.5),
        )
        key.contains("phillies") || key.contains("philadelphia") -> listOf(
            mlbBases("Bryce Harper", "1B", 2.12),
            mlbHit("Trea Turner", "SS", 1.22),
            mlbStrikeouts("Zack Wheeler", 6.6, 2.0, 6.5),
        )
        else -> {
            val short = shortClubLabel(team)
            listOf(
                mlbHit("$short Leadoff Hitter", "CF", 1.12),
                mlbBases("$short Cleanup Slugger", "1B", 1.88),
                mlbStrikeouts("$short Starting Pitcher", 5.6, 1.9, 5.5),
            )
        }
    }

    private fun mlbHit(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "1+ Hit", mean, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Hits")

    private fun mlbBases(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "1.5+ Total Bases", mean, 0.0, 2.0, DistKind.POISSON_AT_LEAST, "Total Bases")

    private fun mlbStrikeouts(name: String, mean: Double, sd: Double, line: Double) =
        PropSpec(name, "SP", "Pitcher Strikeouts", mean, sd, line, DistKind.NORMAL_OVER, "Strikeouts", inverseOpponentEffect = true)

    private fun tennisPropsFor(key: String, playerName: String): List<PropSpec> = when {
        key.contains("alcaraz") -> listOf(
            tennisAces("Carlos Alcaraz", 6.8, 6.5),
            tennisDf("Carlos Alcaraz", 2.6, 2.5),
            tennisBp("Carlos Alcaraz", 4.1, 3.5),
        )
        key.contains("sinner") -> listOf(
            tennisAces("Jannik Sinner", 8.2, 7.5),
            tennisDf("Jannik Sinner", 2.1, 2.5),
            tennisBp("Jannik Sinner", 4.2, 3.5),
        )
        key.contains("djokovic") -> listOf(
            tennisAces("Novak Djokovic", 7.4, 6.5),
            tennisDf("Novak Djokovic", 2.3, 2.5),
            tennisBp("Novak Djokovic", 3.9, 3.5),
        )
        key.contains("zverev") -> listOf(
            tennisAces("Alexander Zverev", 10.6, 9.5),
            tennisDf("Alexander Zverev", 3.4, 3.5),
            tennisBp("Alexander Zverev", 3.3, 2.5),
        )
        key.contains("medvedev") -> listOf(
            tennisAces("Daniil Medvedev", 9.1, 8.5),
            tennisDf("Daniil Medvedev", 3.8, 3.5),
            tennisBp("Daniil Medvedev", 3.8, 3.5),
        )
        else -> listOf(
            tennisAces(playerName, 6.4, 5.5),
            tennisDf(playerName, 2.8, 2.5),
            tennisBp(playerName, 3.1, 2.5),
        )
    }

    private fun tennisAces(name: String, mean: Double, line: Double) =
        PropSpec(name, "Player", "Aces Over/Under", mean, 0.0, floor(line) + 1.0, DistKind.POISSON_AT_LEAST, "Aces")

    private fun tennisDf(name: String, mean: Double, line: Double) =
        PropSpec(name, "Player", "Double Faults", mean, 0.0, floor(line) + 1.0, DistKind.POISSON_AT_LEAST, "Double Faults", inverseOpponentEffect = true)

    private fun tennisBp(name: String, mean: Double, line: Double) =
        PropSpec(name, "Player", "Break Points Won", mean, 0.0, floor(line) + 1.0, DistKind.POISSON_AT_LEAST, "Break Points")

    private fun cricketPropsFor(key: String, team: String): List<PropSpec> = when {
        key.contains("india") -> listOf(
            cricketRuns("Virat Kohli", "Top-Order Batter", 36.5, 18.0),
            cricketRuns("Rohit Sharma", "Opener", 32.0, 17.5),
            cricketWickets("Jasprit Bumrah", "Fast Bowler", 2.05),
        )
        key.contains("australia") -> listOf(
            cricketRuns("Travis Head", "Opener", 35.0, 19.0),
            cricketRuns("Steve Smith", "Top-Order Batter", 33.5, 17.0),
            cricketWickets("Pat Cummins", "Fast Bowler", 1.85),
        )
        key.contains("england") -> listOf(
            cricketRuns("Jos Buttler", "WK-Batter", 34.0, 18.5),
            cricketRuns("Harry Brook", "Middle-Order Batter", 31.5, 17.5),
            cricketWickets("Jofra Archer", "Fast Bowler", 1.82),
        )
        key.contains("south africa") -> listOf(
            cricketRuns("Heinrich Klaasen", "WK-Batter", 34.5, 18.5),
            cricketRuns("Aiden Markram", "Top-Order Batter", 30.5, 16.5),
            cricketWickets("Kagiso Rabada", "Fast Bowler", 1.92),
        )
        else -> {
            val short = shortClubLabel(team)
            listOf(
                cricketRuns("$short Opening Batter", "Opener", 30.0, 17.0),
                cricketRuns("$short No. 3 Batter", "Top-Order Batter", 29.0, 16.5),
                cricketWickets("$short Strike Bowler", "Fast Bowler", 1.72),
            )
        }
    }

    private fun cricketRuns(name: String, pos: String, mean: Double, sd: Double) =
        PropSpec(name, pos, "Top Batter 25+ Runs", mean, sd, 24.5, DistKind.NORMAL_OVER, "Runs")

    private fun cricketWickets(name: String, pos: String, mean: Double) =
        PropSpec(name, pos, "Bowler 2+ Wickets", mean, 0.0, 2.0, DistKind.POISSON_AT_LEAST, "Wickets")

    private fun f1PropsFor(driverOrTeam: String, isHome: Boolean): List<PropSpec> {
        val basePts = if (isHome) 11.5 else 9.5
        return listOf(
            PropSpec(driverOrTeam, "Driver", "Championship Points", basePts, 5.2, 7.5, DistKind.NORMAL_OVER, "Pts"),
            PropSpec(driverOrTeam, "Driver", "Overtakes Completed", 3.4, 0.0, 3.0, DistKind.POISSON_AT_LEAST, "Overtakes"),
            PropSpec(driverOrTeam, "Driver", "Fastest Sector Splits", 1.3, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Sectors"),
        )
    }

    private fun mmaPropsFor(fighter: String): List<PropSpec> = listOf(
        PropSpec(fighter, "Fighter", "Significant Strikes", 54.0, 18.0, 49.5, DistKind.NORMAL_OVER, "Strikes"),
        PropSpec(fighter, "Fighter", "1+ Takedown", 1.45, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Takedowns"),
        PropSpec(fighter, "Fighter", "Knockdowns Landed", 0.42, 0.0, 1.0, DistKind.POISSON_AT_LEAST, "Knockdowns"),
    )

    // -------------------------------------------------------------------------
    // Deterministic PRNG & Safe Formatting Helpers (Zero String.format)
    // -------------------------------------------------------------------------

    private fun normalizePct(value: Double): Double =
        if (value in 0.0..1.0) value * 100.0 else value

    private fun normalizeFormScore(value: Double): Double = when {
        value > 10.0 -> (value / 100.0).coerceIn(0.0, 1.0)
        value > 1.0 -> (value / 10.0).coerceIn(0.0, 1.0)
        else -> value.coerceIn(0.0, 1.0)
    }

    private fun shortClubLabel(team: String): String {
        val trimmed = team.trim()
        if (trimmed.isEmpty()) return "Team"
        val words = trimmed.split(" ").filter { it.isNotBlank() }
        return if (words.size >= 2 && words.first().length <= 3) {
            "${words[0]} ${words[1]}"
        } else {
            words.first()
        }
    }

    private fun sanitizeId(raw: String): String =
        raw.lowercase().map { ch -> if (ch.isLetterOrDigit()) ch else '_' }.joinToString("").trim('_')

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0

    private fun format1(value: Double): String {
        val r = round1(value)
        val neg = r < 0.0
        val absVal = abs(r)
        val whole = absVal.toInt()
        val tenth = round((absVal - whole) * 10.0).toInt()
        val adjWhole = if (tenth == 10) whole + 1 else whole
        val adjTenth = if (tenth == 10) 0 else tenth
        val sign = if (neg && (adjWhole > 0 || adjTenth > 0)) "-" else ""
        return "$sign$adjWhole.$adjTenth"
    }

    private fun fnv1a64(input: String): Long {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037UL
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L // 1099511628211L
        }
        return if (hash == 0L) 0x9e3779b97f4a7c15uL.toLong() else hash
    }

    private class DeterministicRng(seed: Long) {
        private var state: Long = seed

        private fun nextLong(): Long {
            state += -0x61c8864680b583ebL // 0x9e3779b97f4a7c15
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xbf58476d1ce4e5b9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94d049bb133111eb
            return z xor (z ushr 31)
        }

        fun nextDouble(): Double {
            val bits = nextLong() ushr 11
            return bits.toDouble() / 2.0.pow(53)
        }

        fun nextInt(bound: Int): Int {
            if (bound <= 1) return 0
            val v = (nextLong() and Long.MAX_VALUE) % bound.toLong()
            return v.toInt()
        }
    }
}
