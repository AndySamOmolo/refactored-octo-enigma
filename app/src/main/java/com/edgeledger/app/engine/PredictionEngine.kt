package com.edgeledger.app.engine

import com.edgeledger.app.model.ConfidenceLevel
import com.edgeledger.app.model.ExactScorelineItem
import com.edgeledger.app.model.FullMatchMarkets
import com.edgeledger.app.model.HeadToHeadGame
import com.edgeledger.app.model.HistoricalMatch
import com.edgeledger.app.model.LeagueTrackRecord
import com.edgeledger.app.model.MarketPickItem
import com.edgeledger.app.model.ReliabilityBin
import com.edgeledger.app.model.Sport
import com.edgeledger.app.model.StadiumWeather
import com.edgeledger.app.model.TeamProfile
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object PredictionEngine {

    fun eloToPowerScore(
        elo: Double,
        sport: Sport = Sport.FOOTBALL,
        isClubEloAnchored: Boolean = false,
    ): Int {
        if (!elo.isFinite()) return 50
        val (minElo, maxElo) = if (isClubEloAnchored && sport == Sport.FOOTBALL) 1300.0 to 2100.0 else 1200.0 to 1800.0
        val clamped = elo.coerceIn(minElo, maxElo)
        return (((clamped - minElo) / (maxElo - minElo)) * 98.0 + 1.0).roundToInt().coerceIn(1, 99)
    }

    fun expectedEloScore(ratingA: Double, ratingB: Double): Double {
        if (!ratingA.isFinite() || !ratingB.isFinite()) return 0.5
        return 1.0 / (1.0 + 10.0.pow((ratingB - ratingA) / 400.0))
    }

    fun profileKey(sport: Sport, team: String): String = "${sport.name}::$team"

    private fun toPct(p: Double): Int {
        if (!p.isFinite()) return 50
        return (p * 100.0).roundToInt().coerceIn(1, 99)
    }

    private fun conf(p: Double): ConfidenceLevel = when {
        !p.isFinite() -> ConfidenceLevel.LEAN
        p >= 0.68 -> ConfidenceLevel.STRONG
        p >= 0.58 -> ConfidenceLevel.GOOD
        else -> ConfidenceLevel.LEAN
    }

    private fun homeAdvFor(sport: Sport): Double = when (sport) {
        Sport.FOOTBALL -> 65.0
        Sport.NFL -> 48.0
        Sport.NBA -> 72.0
        Sport.NHL -> 35.0
        Sport.MLB -> 24.0
        Sport.CRICKET -> 35.0
        Sport.RUGBY -> 52.0
        Sport.AFL -> 45.0
        Sport.TENNIS, Sport.F1, Sport.MMA, Sport.ESPORTS, Sport.GOLF -> 0.0
    }

    /**
     * Normalizes score margins across sports so a 20-pt NBA win or 80-run Cricket win
     * produces the same Elo MOV multiplier scale as a 2-goal Football win.
     */
    private fun sportMarginScale(sport: Sport): Double = when (sport) {
        Sport.FOOTBALL, Sport.NHL -> 1.0
        Sport.MLB -> 2.5
        Sport.NFL -> 7.0
        Sport.NBA -> 9.0
        Sport.CRICKET -> 25.0
        Sport.TENNIS -> 1.5
        Sport.RUGBY -> 7.0
        Sport.AFL -> 18.0
        else -> 1.0
    }

    /**
     * FiveThirtyEight-style Margin-of-Victory multiplier with favorite-blowout autocorrelation damping.
     */
    internal fun computeMovMultiplier(sport: Sport, margin: Int, winnerElo: Double, loserElo: Double): Double {
        if (margin <= 0) return 1.0
        val normMargin = margin.toDouble() / sportMarginScale(sport)
        val rawMov = ln(normMargin + 1.0) * 0.45 + 0.80
        val eloDiff = (winnerElo - loserElo).coerceIn(-600.0, 600.0)
        val autoCorr = 2.2 / (eloDiff * 0.001 + 2.2).coerceAtLeast(0.65)
        return (rawMov * autoCorr).coerceIn(0.65, 2.35)
    }

    private fun leagueGoalPrior(leagueId: String?): Double = when (leagueId?.uppercase(Locale.ROOT)) {
        "D1", "D2", "N1", "AUT", "SWZ", "NOR", "CHN" -> 1.52
        "E0", "UCL", "UEL", "UECL", "MLS", "USA", "DNK", "SWE", "FIN", "MEX" -> 1.44
        "E1", "E2", "E3", "EC", "B1", "SC0", "SC1", "SC2", "SC3", "T1", "JPN", "POL" -> 1.38
        "SP1", "SP2", "I1", "I2", "F1", "F2", "P1", "G1", "BRA", "ARG", "ROU", "RUS", "IRL" -> 1.28
        else -> 1.38
    }

    /** League-calibrated Dixon-Coles low-score correlation parameter (negative boosts 0-0 / 1-1). */
    internal fun leagueDixonColesRho(leagueId: String?): Double = when (leagueId?.uppercase(Locale.ROOT)) {
        "I1", "I2", "F1", "F2", "SP1", "SP2", "G1", "ARG", "ROU", "RUS", "P1" -> -0.12
        "D1", "D2", "N1", "AUT", "SWZ", "MLS", "USA", "NOR" -> -0.05
        else -> -0.09
    }

    private fun parseIsoDateOrNull(dateIso: String): LocalDate? {
        if (dateIso.length < 10) return null
        return try {
            LocalDate.parse(dateIso.substring(0, 10))
        } catch (_: Exception) {
            null
        }
    }

    private fun sportScoringPrior(sport: Sport): Pair<Double, Double> = when (sport) {
        Sport.FOOTBALL -> 1.38 to 1.38
        Sport.NFL -> 22.2 to 22.2
        Sport.NBA -> 113.5 to 113.5
        Sport.NHL -> 3.05 to 3.05
        Sport.MLB -> 4.50 to 4.50
        Sport.CRICKET -> 168.0 to 168.0
        Sport.RUGBY -> 23.0 to 23.0
        Sport.AFL -> 84.0 to 84.0
        else -> 1.0 to 1.0
    }

    /**
     * Fits Multi-Sport Elo + single post-replay ClubElo anchor (zero future lookahead) + rich team profiles.
     */
    fun buildTeamProfiles(
        matches: List<HistoricalMatch>,
        clubEloMap: Map<String, Double> = emptyMap(),
        externalEloByProfileKey: Map<String, Double> = emptyMap(),
    ): Map<String, TeamProfile> {
        val sorted = matches
            .distinctBy { "${it.sport.name}|${it.dateIso}|${it.homeTeam.trim().lowercase(Locale.ROOT)}|${it.awayTeam.trim().lowercase(Locale.ROOT)}" }
            .sortedBy { it.dateIso }

        class Acc(val team: String, val sport: Sport, var leagueId: String, var elo: Double) {
            var lastMatchDate: LocalDate? = null
            var gp = 0; var w = 0; var d = 0; var l = 0
            var hg = 0; var hw = 0; var ag = 0; var aw = 0
            var scored = 0.0; var conceded = 0.0; var decayedScored = 0.0; var decayedConceded = 0.0; var decayWeightSum = 0.0
            val form = mutableListOf<String>()
            var shots = 0.0; var shotsN = 0
            var sot = 0.0; var sotN = 0
            var corners = 0.0; var cornersN = 0
            var fouls = 0.0; var foulsN = 0
            var cards = 0.0; var reds = 0.0; var cardsN = 0
            var htScored = 0; var htN = 0
            var cs = 0; var btts = 0; var o25 = 0
            var atsW = 0; var atsTotal = 0; var overTot = 0; var totN = 0
            var restSum = 0.0; var restN = 0
            var hardW = 0; var hardN = 0; var clayW = 0; var clayN = 0; var grassW = 0; var grassN = 0
            var acesSum = 0.0; var acesN = 0; var dfSum = 0.0; var dfN = 0
            var fsSum = 0.0; var fsN = 0
        }

        val map = mutableMapOf<String, Acc>()
        fun get(sport: Sport, leagueId: String, team: String): Acc {
            val k = profileKey(sport, team)
            return map.getOrPut(k) { Acc(team, sport, leagueId, 1500.0) }.also {
                if (leagueId.isNotBlank()) it.leagueId = leagueId
            }
        }

        for (m in sorted) {
            val h = get(m.sport, m.leagueId, m.homeTeam)
            val a = get(m.sport, m.leagueId, m.awayTeam)
            val matchDate = parseIsoDateOrNull(m.dateIso)
            if (matchDate != null) {
                h.lastMatchDate?.let { prev ->
                    if (ChronoUnit.DAYS.between(prev, matchDate) > 60L) {
                        h.elo = h.elo * 0.75 + 1500.0 * 0.25
                    }
                }
                a.lastMatchDate?.let { prev ->
                    if (ChronoUnit.DAYS.between(prev, matchDate) > 60L) {
                        a.elo = a.elo * 0.75 + 1500.0 * 0.25
                    }
                }
                h.lastMatchDate = matchDate
                a.lastMatchDate = matchDate
            }
            val hAdv = homeAdvFor(m.sport)
            val expH = expectedEloScore(h.elo + hAdv, a.elo)
            val actualH = when {
                m.homeScore > m.awayScore -> 1.0
                m.homeScore == m.awayScore -> 0.5
                else -> 0.0
            }
            val margin = abs(m.homeScore - m.awayScore)
            val (wElo, lElo) = if (m.homeScore >= m.awayScore) (h.elo + hAdv) to a.elo else a.elo to (h.elo + hAdv)
            val mov = computeMovMultiplier(m.sport, margin, wElo, lElo)
            val kFactor = if (m.sport == Sport.TENNIS || m.sport == Sport.MMA) 28.0 else 22.0
            val delta = kFactor * mov * (actualH - expH)
            h.elo += delta
            a.elo -= delta

            fun recordSide(acc: Acc, isHome: Boolean) {
                val sc = if (isHome) m.homeScore else m.awayScore
                val cn = if (isHome) m.awayScore else m.homeScore
                acc.gp++
                acc.scored += sc
                acc.conceded += cn
                // Exponential recency decay (0.95 per match -> recent ~14 games carry ~50% weight)
                acc.decayedScored = acc.decayedScored * 0.95 + sc
                acc.decayedConceded = acc.decayedConceded * 0.95 + cn
                acc.decayWeightSum = acc.decayWeightSum * 0.95 + 1.0
                val res = when {
                    sc > cn -> { acc.w++; "W" }
                    sc < cn -> { acc.l++; "L" }
                    else -> { acc.d++; "D" }
                }
                acc.form.add(res)
                if (isHome) {
                    acc.hg++; if (res == "W") acc.hw++
                } else {
                    acc.ag++; if (res == "W") acc.aw++
                }
                if (m.sport == Sport.FOOTBALL) {
                    if (cn == 0) acc.cs++
                    if (sc > 0 && cn > 0) acc.btts++
                    if (sc + cn >= 3) acc.o25++
                    (if (isHome) m.homeShots else m.awayShots)?.let { acc.shots += it; acc.shotsN++ }
                    (if (isHome) m.homeSot else m.awaySot)?.let { acc.sot += it; acc.sotN++ }
                    (if (isHome) m.homeCorners else m.awayCorners)?.let { acc.corners += it; acc.cornersN++ }
                    (if (isHome) m.homeFouls else m.awayFouls)?.let { acc.fouls += it; acc.foulsN++ }
                    val y = if (isHome) m.homeYellows else m.awayYellows
                    val r = if (isHome) m.homeReds else m.awayReds
                    if (y != null || r != null) {
                        acc.cards += (y ?: 0) + (r ?: 0)
                        acc.reds += (r ?: 0)
                        acc.cardsN++
                    }
                    (if (isHome) m.homeHtGoals else m.awayHtGoals)?.let {
                        if (it > 0) acc.htScored++
                        acc.htN++
                    }
                }
                if (m.sport == Sport.NFL) {
                    m.spreadLine?.let { sp ->
                        val hm = (m.homeScore - m.awayScore) - sp
                        val tm = if (isHome) hm else -hm
                        if (tm != 0.0) {
                            acc.atsTotal++
                            if (tm > 0) acc.atsW++
                        }
                    }
                    m.totalLine?.let { tl ->
                        if ((m.homeScore + m.awayScore).toDouble() != tl) {
                            acc.totN++
                            if (m.homeScore + m.awayScore > tl) acc.overTot++
                        }
                    }
                    (if (isHome) m.homeRestDays else m.awayRestDays)?.let { acc.restSum += it; acc.restN++ }
                }
                if (m.sport == Sport.TENNIS) {
                    when (m.surface) {
                        "Clay" -> { acc.clayN++; if (res == "W") acc.clayW++ }
                        "Grass" -> { acc.grassN++; if (res == "W") acc.grassW++ }
                        else -> { acc.hardN++; if (res == "W") acc.hardW++ }
                    }
                    (if (isHome) m.homeAces else m.awayAces)?.let { acc.acesSum += it; acc.acesN++ }
                    (if (isHome) m.homeDoubleFaults else m.awayDoubleFaults)?.let { acc.dfSum += it; acc.dfN++ }
                    (if (isHome) m.homeFirstServeWonPct else m.awayFirstServeWonPct)?.let { acc.fsSum += it; acc.fsN++ }
                }
            }

            recordSide(h, true)
            recordSide(a, false)
        }

        val validClubElos = clubEloMap.values.filter { it.isFinite() && it in 1000.0..2600.0 }
        val clubMeanOffset = if (validClubElos.isNotEmpty()) validClubElos.average() - 1500.0 else 0.0
        return map.mapValues { (profKey, acc) ->
            val clubAnchor = if (acc.sport == Sport.FOOTBALL) {
                clubEloMap[acc.team]?.takeIf { it.isFinite() && it in 1000.0..2600.0 }
            } else {
                null
            }
            val extAnchor = externalEloByProfileKey[profKey]?.takeIf { it.isFinite() && it in 800.0..2600.0 }
            val centeredClub = clubAnchor?.let { it - clubMeanOffset }
            val finalElo = when {
                centeredClub != null -> acc.elo * 0.65 + centeredClub * 0.35
                extAnchor != null -> acc.elo * 0.55 + extAnchor * 0.45
                else -> acc.elo
            }
            val gp = max(1, acc.gp).toDouble()
            val overallWinPct = (acc.w / gp) * 100.0
            val isNeutralSport = acc.sport == Sport.TENNIS || acc.sport == Sport.F1 || acc.sport == Sport.MMA || acc.sport == Sport.ESPORTS || acc.sport == Sport.GOLF
            TeamProfile(
                team = acc.team,
                sport = acc.sport,
                leagueId = acc.leagueId,
                elo = finalElo,
                clubElo = clubAnchor,
                powerScore = eloToPowerScore(finalElo, acc.sport, isClubEloAnchored = false),
                gamesPlayed = acc.gp,
                wins = acc.w,
                draws = acc.d,
                losses = acc.l,
                winPct = overallWinPct,
                homeWinPct = if (isNeutralSport) overallWinPct else if (acc.hg > 0) (acc.hw.toDouble() / acc.hg) * 100.0 else 55.0,
                awayWinPct = if (isNeutralSport) overallWinPct else if (acc.ag > 0) (acc.aw.toDouble() / acc.ag) * 100.0 else 45.0,
                avgScored = run {
                    val (priorFor, _) = sportScoringPrior(acc.sport)
                    val priorW = 5.0
                    val effW = acc.decayWeightSum
                    if (effW > 0.0) (acc.decayedScored + priorFor * priorW) / (effW + priorW) else priorFor
                },
                avgConceded = run {
                    val (_, priorAg) = sportScoringPrior(acc.sport)
                    val priorW = 5.0
                    val effW = acc.decayWeightSum
                    if (effW > 0.0) (acc.decayedConceded + priorAg * priorW) / (effW + priorW) else priorAg
                },
                recentForm = acc.form.takeLast(5),
                shotsPerGame = if (acc.shotsN > 0) acc.shots / acc.shotsN else 12.5,
                shotsOnTargetPerGame = if (acc.sotN > 0) acc.sot / acc.sotN else 4.5,
                cornersPerGame = if (acc.cornersN > 0) acc.corners / acc.cornersN else 5.2,
                foulsPerGame = if (acc.foulsN > 0) acc.fouls / acc.foulsN else 11.2,
                cardsPerGame = if (acc.cardsN > 0) acc.cards / acc.cardsN else 1.9,
                redCardsPerGame = if (acc.cardsN > 0) acc.reds / acc.cardsN else 0.08,
                firstHalfGoalRatePct = if (acc.htN > 0) (acc.htScored.toDouble() / acc.htN) * 100.0 else 45.0,
                cleanSheetPct = (acc.cs / gp) * 100.0,
                bttsPct = (acc.btts / gp) * 100.0,
                over25Pct = (acc.o25 / gp) * 100.0,
                atsCoverPct = if (acc.atsTotal > 0) (acc.atsW.toDouble() / acc.atsTotal) * 100.0 else 50.0,
                overTotalPct = if (acc.totN > 0) (acc.overTot.toDouble() / acc.totN) * 100.0 else 50.0,
                avgRestDays = if (acc.restN > 0) acc.restSum / acc.restN else 7.0,
                hardWinPct = if (acc.hardN > 0) (acc.hardW.toDouble() / acc.hardN) * 100.0 else 55.0,
                clayWinPct = if (acc.clayN > 0) (acc.clayW.toDouble() / acc.clayN) * 100.0 else 55.0,
                grassWinPct = if (acc.grassN > 0) (acc.grassW.toDouble() / acc.grassN) * 100.0 else 55.0,
                avgAces = if (acc.acesN > 0) acc.acesSum / acc.acesN else 6.5,
                avgDoubleFaults = if (acc.dfN > 0) acc.dfSum / acc.dfN else 2.4,
                firstServeWinPct = if (acc.fsN > 0) acc.fsSum / acc.fsN else 73.0,
            )
        }
    }

    /**
     * Predicts ALL markets for any of the 9 sports, with optional Market-Consensus blending (PR-10).
     */
    fun predictAllMarkets(
        sport: Sport,
        homeTeam: String,
        awayTeam: String,
        homeProfile: TeamProfile?,
        awayProfile: TeamProfile?,
        surface: String? = null,
        sharpMarketHomeProb: Double? = null,
        marketBlendWeight: Double = 0.0,
        weather: StadiumWeather? = null,
        homeInjuryCount: Int = 0,
        awayInjuryCount: Int = 0,
    ): FullMatchMarkets {
        val hInjCap = homeInjuryCount.coerceIn(0, 3)
        val aInjCap = awayInjuryCount.coerceIn(0, 3)
        val hElo = (homeProfile?.elo?.takeIf { it.isFinite() } ?: 1500.0) - hInjCap * 12.0
        val aElo = (awayProfile?.elo?.takeIf { it.isFinite() } ?: 1500.0) - aInjCap * 12.0
        val safeMarketH = sharpMarketHomeProb?.takeIf { it.isFinite() }?.coerceIn(0.01, 0.98)
        val safeBlendW = if (marketBlendWeight.isFinite()) marketBlendWeight.coerceIn(0.0, 0.5) else 0.0

        if (sport == Sport.FOOTBALL) {
            val lgAvg = leagueGoalPrior(homeProfile?.leagueId ?: awayProfile?.leagueId)
            val hScoredSafe = homeProfile?.avgScored?.takeIf { it.isFinite() && it >= 0.0 } ?: lgAvg
            val hSotSafe = homeProfile?.shotsOnTargetPerGame?.takeIf { it.isFinite() && it >= 0.0 } ?: 4.5
            val aScoredSafe = awayProfile?.avgScored?.takeIf { it.isFinite() && it >= 0.0 } ?: lgAvg
            val aSotSafe = awayProfile?.shotsOnTargetPerGame?.takeIf { it.isFinite() && it >= 0.0 } ?: 4.2
            val hAtk = (hScoredSafe * 0.72 + hSotSafe * 0.30 * 0.28)
            val hDef = homeProfile?.avgConceded?.takeIf { it.isFinite() && it >= 0.0 } ?: lgAvg
            val aAtk = (aScoredSafe * 0.72 + aSotSafe * 0.29 * 0.28)
            val aDef = awayProfile?.avgConceded?.takeIf { it.isFinite() && it >= 0.0 } ?: lgAvg

            val venueTilt = if (homeProfile != null && awayProfile != null && homeProfile.gamesPlayed >= 6 && awayProfile.gamesPlayed >= 6) {
                (((homeProfile.homeWinPct - 50.0) - (awayProfile.awayWinPct - 35.0)) / 500.0).coerceIn(-0.06, 0.06)
            } else 0.0
            val rFactor = ((hElo - aElo) / 850.0 + venueTilt).coerceIn(-0.34, 0.34)
            val weatherGoalMult = when {
                weather == null || weather.summary.contains("Indoor Dome", ignoreCase = true) -> 1.0
                weather.windKmh >= 32 -> 0.92
                weather.windKmh >= 24 || weather.rainChancePct >= 65 -> 0.95
                else -> 1.0
            }
            val hInjMult = 1.0 - hInjCap * 0.022
            val aInjMult = 1.0 - aInjCap * 0.022
            var lambda = (((hAtk * aDef) / lgAvg) * 1.07 * (1.0 + rFactor * 0.50) * weatherGoalMult * hInjMult).coerceIn(0.30, 4.0)
            var mu = (((aAtk * hDef) / lgAvg) * 0.93 * (1.0 - rFactor * 0.50) * weatherGoalMult * aInjMult).coerceIn(0.24, 3.6)

            val rho = leagueDixonColesRho(homeProfile?.leagueId ?: awayProfile?.leagueId)

            // Total-goal-conserving 3-way Dixon-Coles sharp market blend (preserves lambda + mu so O/U 2.5 & BTTS stay unbiased)
            if (safeMarketH != null && safeBlendW > 0.0) {
                val origTotal = lambda + mu
                var rawH = 0.0
                var rawTotal = 0.0
                for (gh in 0..8) {
                    val phG = StatsMath.poissonPmf(gh, lambda)
                    for (ga in 0..8) {
                        val tau = when {
                            gh == 0 && ga == 0 -> max(0.05, 1.0 - lambda * mu * rho)
                            gh == 0 && ga == 1 -> max(0.05, 1.0 + lambda * rho)
                            gh == 1 && ga == 0 -> max(0.05, 1.0 + mu * rho)
                            gh == 1 && ga == 1 -> max(0.05, 1.0 - rho)
                            else -> 1.0
                        }
                        val pCell = phG * StatsMath.poissonPmf(ga, mu) * tau
                        rawTotal += pCell
                        if (gh > ga) rawH += pCell
                    }
                }
                val base3WayHome = if (rawTotal > 0.0) rawH / rawTotal else 0.44
                val shift = ((safeMarketH - base3WayHome) * safeBlendW * 1.4).coerceIn(-0.40, 0.40)
                val wH = lambda * exp(shift)
                val wA = mu * exp(-shift)
                val scale = origTotal / max(0.01, wH + wA)
                lambda = (wH * scale).coerceIn(0.28, 4.2)
                mu = (origTotal - lambda).coerceIn(0.22, 3.8)
            }

            // Expanded 0..12 Dixon-Coles bivariate Poisson grid (captures >99.99% tail mass)
            val maxGoals = 12
            val mat = Array(maxGoals + 1) { DoubleArray(maxGoals + 1) }
            var totalMass = 0.0
            for (h in 0..maxGoals) {
                val pHomeG = StatsMath.poissonPmf(h, lambda)
                for (a in 0..maxGoals) {
                    val tau = when {
                        h == 0 && a == 0 -> max(0.05, 1.0 - lambda * mu * rho)
                        h == 0 && a == 1 -> max(0.05, 1.0 + lambda * rho)
                        h == 1 && a == 0 -> max(0.05, 1.0 + mu * rho)
                        h == 1 && a == 1 -> max(0.05, 1.0 - rho)
                        else -> 1.0
                    }
                    val p = pHomeG * StatsMath.poissonPmf(a, mu) * tau
                    mat[h][a] = p
                    totalMass += p
                }
            }

            var pH = 0.0; var pD = 0.0; var pA = 0.0
            var o05 = 0.0; var o15 = 0.0; var o25 = 0.0; var o35 = 0.0; var o45 = 0.0; var btts = 0.0
            var hO15 = 0.0; var aO15 = 0.0; var hCs = 0.0; var hWtn = 0.0
            var hWinO15 = 0.0; var hWinBtts = 0.0; var h2p = 0.0; var aPlus15 = 0.0
            val scores = mutableListOf<Pair<String, Double>>()

            for (h in 0..maxGoals) {
                for (a in 0..maxGoals) {
                    val p = mat[h][a] / max(1e-9, totalMass)
                    val tot = h + a
                    when {
                        h > a -> pH += p
                        h == a -> pD += p
                        else -> pA += p
                    }
                    if (tot >= 1) o05 += p
                    if (tot >= 2) o15 += p
                    if (tot >= 3) o25 += p
                    if (tot >= 4) o35 += p
                    if (tot >= 5) o45 += p
                    if (h >= 1 && a >= 1) btts += p
                    if (h >= 2) hO15 += p
                    if (a >= 2) aO15 += p
                    if (a == 0) hCs += p
                    if (h > 0 && a == 0) hWtn += p
                    if (h > a && tot >= 2) hWinO15 += p
                    if (h > a && a >= 1) hWinBtts += p
                    if (h - a >= 2) h2p += p
                    if (a - h >= -1) aPlus15 += p
                    if (h <= 7 && a <= 7) scores.add("$h-$a" to p)
                }
            }

            // Guarantee sub-market coherence invariants
            hWtn = hWtn.coerceAtMost(pH)
            hWinO15 = hWinO15.coerceAtMost(pH)
            hWinBtts = hWinBtts.coerceAtMost(min(pH, btts))

            val topScores = scores.sortedByDescending { it.second }.take(6)
                .map { ExactScorelineItem(it.first, toPct(it.second)) }

            val h1Exp = lambda * 0.44
            val a1Exp = mu * 0.43
            val h2Exp = lambda - h1Exp
            val a2Exp = mu - a1Exp
            val ht05 = 1.0 - exp(-(h1Exp + a1Exp))
            val ht15 = StatsMath.poissonOverProb(h1Exp + a1Exp, 1.5)
            val sh05 = 1.0 - exp(-(h2Exp + a2Exp))

            val projCorners = (homeProfile?.cornersPerGame ?: 5.4) + (awayProfile?.cornersPerGame ?: 4.7)
            val projCards = (homeProfile?.cardsPerGame ?: 1.9) + (awayProfile?.cardsPerGame ?: 2.0)
            val projFouls = (homeProfile?.foulsPerGame ?: 11.1) + (awayProfile?.foulsPerGame ?: 11.6)
            val redChance = 1.0 - exp(-((homeProfile?.redCardsPerGame ?: 0.08) + (awayProfile?.redCardsPerGame ?: 0.09)))
            val projShots = (homeProfile?.shotsPerGame ?: 13.0) + (awayProfile?.shotsPerGame ?: 11.4)
            val projSot = (homeProfile?.shotsOnTargetPerGame ?: 4.8) + (awayProfile?.shotsOnTargetPerGame ?: 4.1)

            // Exact 9-outcome 1H x 2H bivariate Poisson convolution normalized by grid mass
            var hh = 0.0; var hd = 0.0; var ha = 0.0
            var dh = 0.0; var dd = 0.0; var da = 0.0
            var ah = 0.0; var ad = 0.0; var aa = 0.0
            var htFtMass = 0.0
            val p1H = DoubleArray(6) { StatsMath.poissonPmf(it, h1Exp) }
            val p1A = DoubleArray(6) { StatsMath.poissonPmf(it, a1Exp) }
            val p2H = DoubleArray(6) { StatsMath.poissonPmf(it, h2Exp) }
            val p2A = DoubleArray(6) { StatsMath.poissonPmf(it, a2Exp) }
            for (h1 in 0..5) for (a1 in 0..5) {
                val pFirst = p1H[h1] * p1A[a1]
                for (h2 in 0..5) for (a2 in 0..5) {
                    val pFull = pFirst * p2H[h2] * p2A[a2]
                    htFtMass += pFull
                    val ftH = h1 + h2
                    val ftA = a1 + a2
                    when {
                        h1 > a1 && ftH > ftA -> hh += pFull
                        h1 > a1 && ftH == ftA -> hd += pFull
                        h1 > a1 && ftA > ftH -> ha += pFull
                        h1 == a1 && ftH > ftA -> dh += pFull
                        h1 == a1 && ftH == ftA -> dd += pFull
                        h1 == a1 && ftA > ftH -> da += pFull
                        a1 > h1 && ftH > ftA -> ah += pFull
                        a1 > h1 && ftH == ftA -> ad += pFull
                        else -> aa += pFull
                    }
                }
            }
            val normHtFt = max(1e-9, htFtMass)
            val topHtFt = listOf(
                "$homeTeam / $homeTeam" to toPct(hh / normHtFt),
                "$homeTeam / Draw" to toPct(hd / normHtFt),
                "$homeTeam / $awayTeam" to toPct(ha / normHtFt),
                "Draw / $homeTeam" to toPct(dh / normHtFt),
                "Draw / Draw" to toPct(dd / normHtFt),
                "Draw / $awayTeam" to toPct(da / normHtFt),
                "$awayTeam / $homeTeam" to toPct(ah / normHtFt),
                "$awayTeam / Draw" to toPct(ad / normHtFt),
                "$awayTeam / $awayTeam" to toPct(aa / normHtFt),
            ).sortedByDescending { it.second }.take(3)

            // Overdispersed Negative Binomial for high-variance count markets (corners/cards/fouls)
            val c95 = StatsMath.negBinOverProb(projCorners, 9.5, dispersionAlpha = 0.045)
            val picks = buildList {
                if (pH >= pA && pH >= 0.48) add(MarketPickItem("fb_h", "Match Result", "$homeTeam to Win", toPct(pH), conf(pH), "$homeTeam project ${String.format(Locale.US, "%.1f", lambda)} goals at home."))
                else if (pA > pH && pA >= 0.48) add(MarketPickItem("fb_a", "Match Result", "$awayTeam to Win", toPct(pA), conf(pA), "$awayTeam hold the stronger attacking edge."))
                val dc = max(pH + pD, pA + pD)
                val dcTeam = if (pH >= pA) homeTeam else awayTeam
                if (dc >= 0.68) add(MarketPickItem("fb_dc", "Double Chance", "$dcTeam or Draw", toPct(dc), conf(dc), "$dcTeam have strong form and should avoid defeat."))
                if (o25 >= 0.55) add(MarketPickItem("fb_o25", "Goals", "Over 2.5 Goals", toPct(o25), conf(o25), "Both teams combine for ${String.format(Locale.US, "%.1f", lambda + mu)} projected goals."))
                else if (1.0 - o25 >= 0.56) add(MarketPickItem("fb_u25", "Goals", "Under 2.5 Goals", toPct(1.0 - o25), conf(1.0 - o25), "Tight defenses project around ${String.format(Locale.US, "%.1f", lambda + mu)} total goals."))
                if (btts >= 0.55) add(MarketPickItem("fb_btts", "Both Teams Score", "Both Teams to Score", toPct(btts), conf(btts), "Both sides create steady chances on target."))
                if (ht05 >= 0.68) add(MarketPickItem("fb_1h", "First Half", "1st Half Over 0.5 Goals", toPct(ht05), conf(ht05), "Fast-starting attacks point to an early goal."))
                if (c95 >= 0.56) add(MarketPickItem("fb_cor", "Corners", "Over 9.5 Corners", toPct(c95), conf(c95), "Projected total is ${String.format(Locale.US, "%.1f", projCorners)} corners."))
            }.sortedByDescending { it.chancePct }.take(5)

            return FullMatchMarkets(
                projectedHome = lambda,
                projectedAway = mu,
                probHome = pH,
                probDraw = pD,
                probAway = pA,
                doubleChanceHomeOrDrawPct = toPct(pH + pD),
                doubleChanceAwayOrDrawPct = toPct(pA + pD),
                drawNoBetHomePct = toPct(pH / max(0.01, pH + pA)),
                over05Pct = toPct(o05),
                over15Pct = toPct(o15),
                over25Pct = toPct(o25),
                over35Pct = toPct(o35),
                over45Pct = toPct(o45),
                bttsYesPct = toPct(btts),
                homeOver15Pct = toPct(hO15),
                awayOver15Pct = toPct(aO15),
                homeCleanSheetPct = toPct(hCs),
                homeWinToNilPct = toPct(hWtn),
                homeWinAndOver15Pct = toPct(hWinO15),
                homeWinAndBttsPct = toPct(hWinBtts),
                homeWinBy2PlusPct = toPct(h2p),
                awayPlus15Pct = toPct(aPlus15),
                firstHalfOver05Pct = toPct(ht05),
                firstHalfOver15Pct = toPct(ht15),
                secondHalfOver05Pct = toPct(sh05),
                topHtFt = topHtFt,
                projectedCorners = projCorners,
                cornersOver85Pct = toPct(StatsMath.negBinOverProb(projCorners, 8.5, dispersionAlpha = 0.045)),
                cornersOver95Pct = toPct(c95),
                projectedCards = projCards,
                cardsOver35Pct = toPct(StatsMath.negBinOverProb(projCards, 3.5, dispersionAlpha = 0.08)),
                redCardChancePct = toPct(redChance.coerceIn(0.06, 0.35)),
                projectedFouls = projFouls,
                foulsOver215Pct = toPct(StatsMath.negBinOverProb(projFouls, 21.5, dispersionAlpha = 0.02)),
                projectedShotsTotal = projShots,
                projectedSotTotal = projSot,
                topScorelines = topScores,
                topPicks = picks,
            )
        }

        if (sport == Sport.TENNIS) {
            val holdA = ((homeProfile?.firstServeWinPct ?: 73.0) / 100.0 * 1.12).coerceIn(0.62, 0.94)
            val holdB = ((awayProfile?.firstServeWinPct ?: 73.0) / 100.0 * 1.12).coerceIn(0.62, 0.94)
            val surfA = when (surface) {
                "Clay" -> homeProfile?.clayWinPct ?: 55.0
                "Grass" -> homeProfile?.grassWinPct ?: 55.0
                else -> homeProfile?.hardWinPct ?: 55.0
            } / 100.0
            val surfB = when (surface) {
                "Clay" -> awayProfile?.clayWinPct ?: 55.0
                "Grass" -> awayProfile?.grassWinPct ?: 55.0
                else -> awayProfile?.hardWinPct ?: 55.0
            } / 100.0
            val baseA = expectedEloScore(hElo, aElo)
            var pSetA = (baseA * 0.62 + (0.5 + (holdA - holdB) * 1.3) * 0.23 + (0.5 + (surfA - surfB) * 0.5) * 0.15)
                .coerceIn(0.12, 0.88)

            val rawMatchA = pSetA * pSetA + 2.0 * pSetA * (1.0 - pSetA) * pSetA
            val matchA = if (safeMarketH != null && safeBlendW > 0.0) {
                val blended = (rawMatchA * (1.0 - safeBlendW) + safeMarketH * safeBlendW).coerceIn(0.03, 0.97)
                // Invert Bo3 formula via bisection so pSetA stays coherent with blended matchA
                var lo = 0.05; var hi = 0.95
                repeat(24) {
                    val mid = 0.5 * (lo + hi)
                    val m = mid * mid * (3.0 - 2.0 * mid)
                    if (m < blended) lo = mid else hi = mid
                }
                pSetA = 0.5 * (lo + hi)
                blended
            } else {
                rawMatchA
            }

            val pSetB = 1.0 - pSetA
            val straightA = pSetA * pSetA
            val straightB = pSetB * pSetB
            val threeSets = 2.0 * pSetA * pSetB
            val expSets = 2.0 * (straightA + straightB) + 3.0 * threeSets
            val surfBase = when (surface) { "Grass" -> 10.0; "Clay" -> 9.3; else -> 9.6 }
            val projGames = expSets * surfBase
            val gLine = if (surface == "Clay") 21.5 else 22.5
            val overG = 1.0 - StatsMath.normalCdf(gLine, projGames, 4.3)
            val avgHold = (holdA + holdB) / 2.0
            val tbChance = (1.0 - (1.0 - (0.08 + max(0.0, avgHold - 0.72) * 1.5).coerceIn(0.08, 0.42)).pow(expSets)).coerceIn(0.14, 0.78)
            val favIsHome = matchA >= 0.5
            val favName = if (favIsHome) homeTeam else awayTeam
            val favSurname = favName.trim().substringAfterLast(' ').ifBlank { favName }
            val favProb = max(matchA, 1.0 - matchA)
            val favFirstSetProb = if (favIsHome) pSetA else pSetB
            val hcapProb = (1.0 - StatsMath.normalCdf(2.5, abs(pSetA - 0.5) * 9.5, 3.4)).coerceIn(0.25, 0.86)
            val projAces = (homeProfile?.avgAces ?: 6.8) + (awayProfile?.avgAces ?: 6.4)
            val projDf = (homeProfile?.avgDoubleFaults ?: 2.4) + (awayProfile?.avgDoubleFaults ?: 2.5)

            val signedGameMargin = ((pSetA - 0.5) * 9.5).coerceIn(-6.5, 6.5)
            val projHomeGames = ((projGames + signedGameMargin) / 2.0).coerceIn(4.0, 15.0)
            val projAwayGames = (projGames - projHomeGames).coerceIn(4.0, 15.0)
            val straightTotal = straightA + straightB
            val pickStraight = straightTotal >= threeSets
            val setsPickProb = if (pickStraight) straightTotal else threeSets
            val setsPickLabel = if (pickStraight) "Straight Sets (2-0)" else "Goes to 3 Sets"

            return FullMatchMarkets(
                projectedHome = projHomeGames,
                projectedAway = projAwayGames,
                probHome = matchA,
                probDraw = 0.0,
                probAway = 1.0 - matchA,
                firstSetHomePct = toPct(pSetA),
                straightSetsPct = toPct(straightTotal),
                goesToThreeSetsPct = toPct(threeSets),
                gameHandicapLabel = "$favSurname -2.5 Games",
                gameHandicapPct = toPct(hcapProb),
                tiebreakChancePct = toPct(tbChance),
                projectedAces = projAces,
                projectedDoubleFaults = projDf,
                projectedTotalGames = projGames,
                totalGamesLine = gLine,
                totalGamesOverPct = toPct(overG),
                topPicks = listOf(
                    MarketPickItem("atp_w", "Match Result", "$favName to Win", toPct(favProb), conf(favProb), "Stronger ${surface ?: "Hard"}-court serve and overall form."),
                    MarketPickItem("atp_s1", "First Set", "$favName to Win 1st Set", toPct(favFirstSetProb), conf(favFirstSetProb), "Higher projected service hold rate."),
                    MarketPickItem("atp_sets", "Sets", setsPickLabel, toPct(setsPickProb), conf(setsPickProb), "Projected ${String.format(Locale.US, "%.1f", projGames)} games (${toPct(tbChance)}% tiebreak chance)."),
                ),
            )
        }

        // US Team Sports + Cricket / F1 / MMA
        val tempScale = when (sport) {
            Sport.NHL, Sport.MLB -> 1.25
            Sport.NFL, Sport.NBA -> 1.15
            else -> 1.0
        }
        val restAdj = if ((sport == Sport.NFL || sport == Sport.NBA) && homeProfile != null && awayProfile != null) {
            ((homeProfile.avgRestDays - awayProfile.avgRestDays) * 5.0).coerceIn(-25.0, 25.0)
        } else 0.0
        val rawWinH = expectedEloScore((hElo + homeAdvFor(sport) + restAdj - aElo) / tempScale + 1500.0, 1500.0)
        val maxCap = when (sport) {
            Sport.NHL, Sport.MLB -> 0.74
            Sport.NFL, Sport.NBA -> 0.82
            else -> 0.88
        }
        val baseWinH = rawWinH.coerceIn(1.0 - maxCap, maxCap)
        val winH = if (safeMarketH != null && safeBlendW > 0.0) {
            (baseWinH * (1.0 - safeBlendW) + safeMarketH * safeBlendW).coerceIn(0.03, 0.97)
        } else {
            baseWinH
        }

        if (sport == Sport.F1 || sport == Sport.MMA || sport == Sport.ESPORTS || sport == Sport.GOLF) {
            val winA = (1.0 - winH).coerceIn(0.03, 0.97)
            val favName = if (winH >= winA) homeTeam else awayTeam
            val favPct = (max(winH, winA) * 100.0).roundToInt().coerceIn(50, 97)
            val h2hBasis = when (sport) {
                Sport.F1 -> "championship standings & form"
                Sport.MMA -> "fighter Elo & UFC bout history"
                Sport.ESPORTS -> "OpenDota pro team Elo rating"
                Sport.GOLF -> "tour form & golfer Elo rating"
                else -> "head-to-head rating"
            }
            val topPick = MarketPickItem(
                id = "h2h_winner",
                category = "Head-to-Head",
                label = "$favName to Win",
                chancePct = favPct,
                confidence = conf(max(winH, winA)),
                plainReason = "Head-to-head rating model ($h2hBasis).",
            )
            return FullMatchMarkets(
                probHome = winH,
                probDraw = 0.0,
                probAway = winA,
                projectedHome = 0.0,
                projectedAway = 0.0,
                projectedScoreDisplay = "",
                projectedSpreadDisplay = "",
                topPicks = listOf(topPick),
            )
        }

        val cfg: Array<Any> = when (sport) {
            Sport.NFL -> arrayOf(23.5, 21.0, 13.5, 13.0, 3.5, "One-Score Game (<=7 pts)", 7.0, 0.08, "Points")
            Sport.NBA -> arrayOf(115.0, 111.5, 12.2, 17.0, 5.5, "Close Finish (<=6 pts)", 6.0, 0.06, "Points")
            Sport.NHL -> arrayOf(3.2, 2.8, 2.15, 2.2, 1.5, "1-Goal Game", 1.5, 0.23, "Goals")
            Sport.MLB -> arrayOf(4.7, 4.3, 3.35, 3.5, 1.5, "Close Game (<=2 runs)", 2.0, 0.09, "Runs")
            Sport.CRICKET -> arrayOf(172.0, 165.0, 22.0, 28.0, 15.5, "Close Finish (<=15 runs)", 15.0, 0.04, "Runs")
            Sport.RUGBY -> arrayOf(24.5, 21.5, 13.0, 14.0, 6.5, "Close Finish (<=7 pts)", 7.0, 0.04, "Points")
            Sport.AFL -> arrayOf(86.0, 82.0, 32.0, 26.0, 12.5, "Close Finish (<=12 pts)", 12.0, 0.02, "Points")
            else -> arrayOf(1.0, 0.0, 1.0, 1.0, 0.5, "Close Matchup", 0.5, 0.05, "Pts")
        }
        val defScoreH = cfg[0] as Double
        val defScoreA = cfg[1] as Double
        val marginSd = cfg[2] as Double
        val totalSd = cfg[3] as Double
        val altSpreadVal = cfg[4] as Double
        val closeLabel = cfg[5] as String
        val closeBand = cfg[6] as Double
        val otBase = cfg[7] as Double
        val unit = cfg[8] as String

        // Home concedes away baseline; away concedes home baseline
        val hScored = homeProfile?.takeIf { it.gamesPlayed > 0 }?.avgScored ?: defScoreH
        val hConceded = homeProfile?.takeIf { it.gamesPlayed > 0 }?.avgConceded ?: defScoreA
        val aScored = awayProfile?.takeIf { it.gamesPlayed > 0 }?.avgScored ?: defScoreA
        val aConceded = awayProfile?.takeIf { it.gamesPlayed > 0 }?.avgConceded ?: defScoreH
        val rawH = (hScored + aConceded) / 2.0
        val rawA = (aScored + hConceded) / 2.0
        val envMult = when {
            weather == null -> 1.0
            sport == Sport.NFL && weather.summary.contains("Indoor Dome", ignoreCase = true) -> 1.02
            sport == Sport.NFL && weather.windKmh >= 28 -> 0.92
            sport == Sport.NFL && (weather.windKmh >= 20 || weather.rainChancePct >= 65) -> 0.96
            sport == Sport.MLB && weather.summary.contains("High Altitude", ignoreCase = true) -> 1.13
            sport == Sport.MLB && weather.tempC >= 28 -> 1.03
            sport == Sport.MLB && weather.tempC <= 10 -> 0.96
            sport == Sport.RUGBY && (weather.windKmh >= 28 || weather.rainChancePct >= 65) -> 0.93
            else -> 1.0
        }
        val total = ((rawH + rawA) * envMult).coerceAtLeast(2.0)

        val probImpliedMargin = StatsMath.normalInvCdf(winH.coerceIn(0.03, 0.97)) * marginSd
        val rawMargin = rawH - rawA
        val hasBothProfiles = (homeProfile?.gamesPlayed ?: 0) >= 4 && (awayProfile?.gamesPlayed ?: 0) >= 4
        val sameDirection = rawMargin * probImpliedMargin > 0.0
        val blendedMargin = if (hasBothProfiles && sameDirection) {
            rawMargin * 0.35 + probImpliedMargin * 0.65
        } else {
            probImpliedMargin
        }

        val isDecimalScore = sport == Sport.NHL || sport == Sport.MLB
        val isH2hSport = sport == Sport.F1 || sport == Sport.MMA
        val minCoherentMargin = when {
            isH2hSport -> 0.05
            isDecimalScore -> 0.15
            else -> 1.0
        }
        val margin = when {
            winH >= 0.515 -> max(blendedMargin, minCoherentMargin)
            winH <= 0.485 -> min(blendedMargin, -minCoherentMargin)
            else -> blendedMargin
        }.coerceIn(-total, total)

        val pH = ((total + margin) / 2.0).coerceAtLeast(0.0)
        val pA = ((total - margin) / 2.0).coerceAtLeast(0.0)
        val line = total.toInt() + 0.5
        val overP = (1.0 - StatsMath.normalCdf(line, total, totalSd)).coerceIn(0.05, 0.95)
        val underP = 1.0 - overP

        val fav = if (winH >= 0.5) homeTeam else awayTeam
        val dog = if (winH >= 0.5) awayTeam else homeTeam
        val favProb = max(winH, 1.0 - winH)

        val favCoverProb = (1.0 - StatsMath.normalCdf(altSpreadVal, abs(margin), marginSd)).coerceIn(0.10, 0.90)
        val dogCoverProb = 1.0 - favCoverProb
        val pickFavSpread = favCoverProb >= 0.5
        val spreadLabel = if (pickFavSpread) "$fav -$altSpreadVal" else "$dog +$altSpreadVal"
        val spreadProb = if (pickFavSpread) favCoverProb else dogCoverProb
        val spreadReason = if (pickFavSpread) {
            "Projected margin favors $fav by ${String.format(Locale.US, "%.1f", abs(margin))}, clearing -$altSpreadVal."
        } else {
            "Projected margin of ${String.format(Locale.US, "%.1f", abs(margin))} stays inside the +$altSpreadVal cushion for $dog."
        }

        val pickOver = overP >= 0.5
        val totalPickProb = max(overP, underP)
        val totalPickLabel = "${if (pickOver) "Over" else "Under"} $line $unit"

        val closeP = (StatsMath.normalCdf(closeBand, margin, marginSd) - StatsMath.normalCdf(-closeBand, margin, marginSd)).coerceIn(0.15, 0.85)
        val otP = (otBase * exp(-((margin / marginSd).pow(2)))).coerceIn(0.03, 0.28)
        val scoreDisp = when {
            isH2hSport -> "${toPct(winH)}% – ${toPct(1.0 - winH)}%"
            isDecimalScore -> "${String.format(Locale.US, "%.1f", pH)} – ${String.format(Locale.US, "%.1f", pA)}"
            else -> {
                var rH = pH.roundToInt()
                var rA = pA.roundToInt()
                if (rH == rA) {
                    if (winH >= 0.5) rH += 1 else rA += 1
                }
                "$rH – $rA"
            }
        }
        val decFmt = if (isDecimalScore) "%.1f" else "%.0f"
        val htDisp = "${String.format(Locale.US, decFmt, pH * 0.49)} – ${String.format(Locale.US, decFmt, pA * 0.49)}"
        val absMarginSafe = max(0.5, abs(margin))

        val rankedPicks = buildList {
            add(
                MarketPickItem(
                    id = "${sport.name}_w",
                    category = "Match Result",
                    label = "$fav to Win",
                    chancePct = toPct(favProb),
                    confidence = conf(favProb),
                    plainReason = if (isH2hSport) {
                        "Head-to-head edge based on recent race/fight form and ratings."
                    } else {
                        "Projected score $scoreDisp based on ratings and scoring pace."
                    },
                ),
            )
            if (!isH2hSport) {
                add(
                    MarketPickItem(
                        id = "${sport.name}_sp",
                        category = "Spread",
                        label = spreadLabel,
                        chancePct = toPct(spreadProb),
                        confidence = conf(spreadProb),
                        plainReason = spreadReason,
                    ),
                )
                add(
                    MarketPickItem(
                        id = "${sport.name}_tot",
                        category = "Total",
                        label = totalPickLabel,
                        chancePct = toPct(totalPickProb),
                        confidence = conf(totalPickProb),
                        plainReason = "Combined scoring pace projects ${String.format(Locale.US, "%.1f", total)} $unit.",
                    ),
                )
            }
        }.sortedByDescending { it.chancePct }

        val (finalProbH, finalProbD, finalProbA) = if (sport.hasDraw) {
            val drawMass = (StatsMath.normalCdf(0.5, margin, marginSd) - StatsMath.normalCdf(-0.5, margin, marginSd))
                .takeIf { it.isFinite() }?.coerceIn(0.01, 0.10) ?: 0.03
            val rem = 1.0 - drawMass
            Triple(winH * rem, drawMass, (1.0 - winH) * rem)
        } else {
            Triple(winH, 0.0, 1.0 - winH)
        }

        return FullMatchMarkets(
            projectedHome = pH,
            projectedAway = pA,
            probHome = finalProbH,
            probDraw = finalProbD,
            probAway = finalProbA,
            projectedScoreDisplay = scoreDisp,
            projectedSpreadDisplay = if (abs(margin) < 0.35) "Pick'em" else "$fav -${String.format(Locale.US, "%.1f", absMarginSafe)}",
            altSpreadLabel = spreadLabel,
            altSpreadPct = toPct(spreadProb),
            projectedTotal = total,
            totalLine = line,
            totalOverPct = toPct(overP),
            halftimeDisplay = htDisp,
            closeGameLabel = closeLabel,
            closeGamePct = toPct(closeP),
            overtimeChancePct = toPct(otP),
            topPicks = rankedPicks,
        )
    }

    fun generatePlainInsights(
        home: TeamProfile?,
        away: TeamProfile?,
        h2h: List<HeadToHeadGame>,
        sport: Sport,
        markets: FullMatchMarkets,
    ): List<String> = buildList {
        if (home != null && away != null) {
            val fav = if (markets.probHome >= markets.probAway) home else away
            val opp = if (markets.probHome >= markets.probAway) away else home
            if (fav.powerScore - opp.powerScore >= 6) {
                add("${fav.team} holds a clear strength edge (${fav.powerScore} vs ${opp.powerScore} Team Power).")
            }
            if (sport != Sport.TENNIS && sport != Sport.F1 && sport != Sport.MMA && sport != Sport.ESPORTS && sport != Sport.GOLF && home.homeWinPct >= 56.0) {
                add("${home.team} is tough to beat at home, winning ${home.homeWinPct.roundToInt()}% of home games.")
            }
            if (sport == Sport.FOOTBALL) {
                add("${home.team} averages ${String.format(Locale.US, "%.1f", home.shotsOnTargetPerGame)} shots on target and ${String.format(Locale.US, "%.1f", home.cornersPerGame)} corners per match.")
                if (home.over25Pct >= 55.0 && away.over25Pct >= 55.0) {
                    add("Both teams play open, high-scoring football (${((home.over25Pct + away.over25Pct) / 2.0).roundToInt()}% Over 2.5 games).")
                }
            } else if (sport == Sport.NFL) {
                add("${fav.team} covers the spread ${fav.atsCoverPct.roundToInt()}% of the time and averages ${String.format(Locale.US, "%.1f", fav.avgScored)} points.")
            } else if (sport == Sport.TENNIS) {
                add("${fav.team} wins ${fav.firstServeWinPct.roundToInt()}% of first-serve points and fires ${String.format(Locale.US, "%.1f", fav.avgAces)} aces per match.")
            }
        }
        if (h2h.size >= 2 && home != null && away != null) {
            var homeTeamWins = 0
            var awayTeamWins = 0
            var draws = 0
            for (g in h2h) {
                when {
                    g.homeScore == g.awayScore -> draws++
                    (g.homeTeam.equals(home.team, ignoreCase = true) && g.homeScore > g.awayScore) ||
                        (g.awayTeam.equals(home.team, ignoreCase = true) && g.awayScore > g.homeScore) -> homeTeamWins++
                    else -> awayTeamWins++
                }
            }
            val drawPart = if (draws > 0) ", $draws draws" else ""
            add("Recent head-to-head (${h2h.size} meetings): ${home.team} $homeTeamWins, ${away.team} $awayTeamWins$drawPart.")
        }
    }.take(4)

    /**
     * Strict chronological walk-forward backtester (PR-05, PR-08).
     * Guarantees 3-way probability sum == 1.0, grades Draw odds & non-draw ties as pushes,
     * records all outcome bins, and returns 0 accuracy when no games were tested.
     */
    fun runWalkForwardBacktest(
        sport: Sport,
        leagueId: String,
        leagueName: String,
        matches: List<HistoricalMatch>,
    ): LeagueTrackRecord {
        val sorted = matches
            .distinctBy { "${it.sport.name}|${it.dateIso}|${it.homeTeam.trim().lowercase(Locale.ROOT)}|${it.awayTeam.trim().lowercase(Locale.ROOT)}" }
            .sortedBy { it.dateIso }

        class RunningTeam(val name: String) {
            var elo: Double = 1500.0
            var lastDate: LocalDate? = null
            var gp: Int = 0
            var hg: Int = 0; var hw: Int = 0
            var ag: Int = 0; var aw: Int = 0
            var decayedScored: Double = 0.0
            var decayedConceded: Double = 0.0
            var decayWeightSum: Double = 0.0
            var sotSum: Double = 0.0; var sotN: Int = 0
            var hardW: Int = 0; var hardN: Int = 0
            var clayW: Int = 0; var clayN: Int = 0
            var grassW: Int = 0; var grassN: Int = 0

            fun toSnapshotProfile(): TeamProfile {
                val (priorFor, priorAg) = sportScoringPrior(sport)
                val priorW = 5.0
                val avgFor = if (decayWeightSum > 0.0) (decayedScored + priorFor * priorW) / (decayWeightSum + priorW) else priorFor
                val avgAg = if (decayWeightSum > 0.0) (decayedConceded + priorAg * priorW) / (decayWeightSum + priorW) else priorAg
                return TeamProfile(
                    team = name,
                    sport = sport,
                    leagueId = leagueId,
                    elo = elo,
                    powerScore = eloToPowerScore(elo, sport),
                    gamesPlayed = gp,
                    wins = hw + aw,
                    draws = 0,
                    losses = (gp - hw - aw).coerceAtLeast(0),
                    winPct = if (gp > 0) ((hw + aw).toDouble() / gp) * 100.0 else 50.0,
                    homeWinPct = if (hg > 0) (hw.toDouble() / hg) * 100.0 else 55.0,
                    awayWinPct = if (ag > 0) (aw.toDouble() / ag) * 100.0 else 45.0,
                    avgScored = avgFor,
                    avgConceded = avgAg,
                    recentForm = emptyList(),
                    shotsOnTargetPerGame = if (sotN > 0) sotSum / sotN else 4.5,
                    hardWinPct = if (hardN > 0) (hardW.toDouble() / hardN) * 100.0 else 55.0,
                    clayWinPct = if (clayN > 0) (clayW.toDouble() / clayN) * 100.0 else 55.0,
                    grassWinPct = if (grassN > 0) (grassW.toDouble() / grassN) * 100.0 else 55.0,
                )
            }
        }

        val teams = mutableMapOf<String, RunningTeam>()
        val warmup = min(15, sorted.size / 4)
        val hAdv = homeAdvFor(sport)
        val kFactor = if (sport == Sport.TENNIS || sport == Sport.MMA) 28.0 else 22.0

        var n = 0
        var modelErr = 0.0
        var baseErr = 0.0
        var logLossSum = 0.0
        var rpsSum = 0.0
        var bankroll = 1000.0
        var staked = 0.0
        val binHits = IntArray(5)
        val binCounts = IntArray(5)
        val binProbSum = DoubleArray(5)

        fun recordBin(prob: Double, hit: Boolean) {
            val safeP = prob.coerceIn(0.0, 1.0)
            val idx = (safeP * 5.0).toInt().coerceIn(0, 4)
            binCounts[idx]++
            binProbSum[idx] += safeP
            if (hit) binHits[idx]++
        }

        for ((idx, m) in sorted.withIndex()) {
            val flipSide = (sport == Sport.TENNIS || sport == Sport.MMA) && (idx % 2 == 1)
            val teamA = if (flipSide) m.awayTeam else m.homeTeam
            val teamB = if (flipSide) m.homeTeam else m.awayTeam
            val scoreA = if (flipSide) m.awayScore else m.homeScore
            val scoreB = if (flipSide) m.homeScore else m.awayScore
            val oddsA = if (flipSide) m.oddsAway else m.oddsHome
            val oddsB = if (flipSide) m.oddsHome else m.oddsAway
            val oddsD = m.oddsDraw

            val accA = teams.getOrPut(teamA) { RunningTeam(teamA) }
            val accB = teams.getOrPut(teamB) { RunningTeam(teamB) }

            val matchDate = parseIsoDateOrNull(m.dateIso)
            if (matchDate != null) {
                accA.lastDate?.let { prev ->
                    if (ChronoUnit.DAYS.between(prev, matchDate) > 60L) accA.elo = accA.elo * 0.75 + 1500.0 * 0.25
                }
                accB.lastDate?.let { prev ->
                    if (ChronoUnit.DAYS.between(prev, matchDate) > 60L) accB.elo = accB.elo * 0.75 + 1500.0 * 0.25
                }
                accA.lastDate = matchDate
                accB.lastDate = matchDate
            }

            // Route walk-forward evaluation through production predictAllMarkets (strictly pre-match state)
            val prodMarkets = predictAllMarkets(
                sport = sport,
                homeTeam = teamA,
                awayTeam = teamB,
                homeProfile = accA.toSnapshotProfile(),
                awayProfile = accB.toSnapshotProfile(),
                surface = m.surface,
            )

            val (pA, pD, pB) = if (sport.hasDraw) {
                val sum = (prodMarkets.probHome + prodMarkets.probDraw + prodMarkets.probAway).coerceAtLeast(1e-6)
                Triple(prodMarkets.probHome / sum, prodMarkets.probDraw / sum, prodMarkets.probAway / sum)
            } else {
                val sum = (prodMarkets.probHome + prodMarkets.probAway).coerceAtLeast(1e-6)
                Triple(prodMarkets.probHome / sum, 0.0, prodMarkets.probAway / sum)
            }

            val yA = if (scoreA > scoreB) 1.0 else 0.0
            val yD = if (scoreA == scoreB) 1.0 else 0.0
            val yB = if (scoreB > scoreA) 1.0 else 0.0

            if (idx >= warmup) {
                n++
                if (sport.hasDraw) {
                    modelErr += ((pA - yA).pow(2) + (pD - yD).pow(2) + (pB - yB).pow(2)) / 2.0
                    baseErr += ((0.44 - yA).pow(2) + (0.26 - yD).pow(2) + (0.30 - yB).pow(2)) / 2.0
                    val realizedP = (if (yA == 1.0) pA else if (yD == 1.0) pD else pB).coerceIn(1e-6, 1.0)
                    logLossSum += -ln(realizedP)
                    // 3-outcome Ranked Probability Score (ordered: Home Win -> Draw -> Away Win)
                    val cdfP1 = pA
                    val cdfP2 = pA + pD
                    val cdfY1 = yA
                    val cdfY2 = yA + yD
                    rpsSum += 0.5 * ((cdfP1 - cdfY1).pow(2) + (cdfP2 - cdfY2).pow(2))
                    recordBin(pA, yA == 1.0)
                    recordBin(pD, yD == 1.0)
                    recordBin(pB, yB == 1.0)
                } else if (scoreA != scoreB) {
                    modelErr += (pA - yA).pow(2)
                    val baseP = if (hAdv > 0.0) 0.54 else 0.50
                    baseErr += (baseP - yA).pow(2)
                    val realizedP = (if (yA == 1.0) pA else pB).coerceIn(1e-6, 1.0)
                    logLossSum += -ln(realizedP)
                    rpsSum += (pA - yA).pow(2)
                    recordBin(pA, yA == 1.0)
                    recordBin(pB, yB == 1.0)
                } else {
                    // Non-draw sport tie (e.g. NFL tie): neutral push, don't penalize as away win
                    n--
                }

                if (bankroll > 10.0) {
                    val isNonDrawPush = !sport.hasDraw && scoreA == scoreB
                    val candidates = buildList {
                        if (oddsA != null && oddsA in 1.10..10.0) add(Triple(pA, oddsA, if (isNonDrawPush) 0 else if (yA == 1.0) 1 else -1))
                        if (sport.hasDraw && oddsD != null && oddsD in 1.10..10.0) add(Triple(pD, oddsD, if (yD == 1.0) 1 else -1))
                        if (oddsB != null && oddsB in 1.10..10.0) add(Triple(pB, oddsB, if (isNonDrawPush) 0 else if (yB == 1.0) 1 else -1))
                    }
                    val best = candidates.maxByOrNull { OddsMath.expectedValue(it.first, it.second) }
                    if (best != null && OddsMath.expectedValue(best.first, best.second) >= 0.03) {
                        val stake = bankroll * OddsMath.fractionalKelly(best.first, best.second, 0.25, 0.04)
                        staked += stake
                        when (best.third) {
                            1 -> bankroll += stake * (best.second - 1.0)
                            -1 -> bankroll -= stake
                            else -> Unit
                        }
                    }
                }
            }

            // Post-match chronological state update (strictly after grading)
            val expScoreA = expectedEloScore(accA.elo + hAdv, accB.elo)
            val actual = when {
                yA == 1.0 -> 1.0
                yD == 1.0 -> 0.5
                else -> 0.0
            }
            val (bwElo, blElo) = if (scoreA >= scoreB) (accA.elo + hAdv) to accB.elo else accB.elo to (accA.elo + hAdv)
            val mov = computeMovMultiplier(sport, abs(scoreA - scoreB), bwElo, blElo)
            val delta = kFactor * mov * (actual - expScoreA)
            accA.elo += delta
            accB.elo -= delta

            val decay = 0.95
            accA.gp++; accB.gp++
            accA.hg++; if (scoreA > scoreB) accA.hw++
            accB.ag++; if (scoreB > scoreA) accB.aw++
            accA.decayedScored = accA.decayedScored * decay + scoreA
            accA.decayedConceded = accA.decayedConceded * decay + scoreB
            accA.decayWeightSum = accA.decayWeightSum * decay + 1.0
            accB.decayedScored = accB.decayedScored * decay + scoreB
            accB.decayedConceded = accB.decayedConceded * decay + scoreA
            accB.decayWeightSum = accB.decayWeightSum * decay + 1.0
            val sotA = if (flipSide) m.awaySot else m.homeSot
            val sotB = if (flipSide) m.homeSot else m.awaySot
            if (sotA != null) { accA.sotSum += sotA; accA.sotN++ }
            if (sotB != null) { accB.sotSum += sotB; accB.sotN++ }
            val surf = m.surface?.lowercase(Locale.ROOT) ?: ""
            when {
                "clay" in surf -> { accA.clayN++; accB.clayN++; if (scoreA > scoreB) accA.clayW++ else if (scoreB > scoreA) accB.clayW++ }
                "grass" in surf -> { accA.grassN++; accB.grassN++; if (scoreA > scoreB) accA.grassW++ else if (scoreB > scoreA) accB.grassW++ }
                "hard" in surf -> { accA.hardN++; accB.hardN++; if (scoreA > scoreB) accA.hardW++ else if (scoreB > scoreA) accB.hardW++ }
            }
        }

        val labels = listOf("0-20%", "20-40%", "40-60%", "60-80%", "80-100%")
        var totalBinSamples = 0
        var weightedCalErr = 0.0
        val bins = labels.mapIndexed { i, lbl ->
            val cnt = binCounts[i]
            val pct = if (cnt > 0) ((binHits[i].toDouble() / cnt) * 100.0).roundToInt() else 0
            if (cnt > 0) {
                totalBinSamples += cnt
                val avgConf = binProbSum[i] / cnt
                val empAcc = binHits[i].toDouble() / cnt
                weightedCalErr += cnt * abs(avgConf - empAcc)
            }
            ReliabilityBin(lbl, pct, cnt)
        }
        val ecePct = if (totalBinSamples > 0) (weightedCalErr / totalBinSamples) * 100.0 else 0.0

        if (n == 0) {
            return LeagueTrackRecord(
                sport = sport,
                leagueId = leagueId,
                leagueName = leagueName,
                gamesTested = 0,
                accuracyScore = 0,
                beatsSimpleGuess = false,
                simulatedProfitPct = 0.0,
                hasGradedOdds = false,
                reliabilityBins = bins,
                logLoss = 0.0,
                rps = 0.0,
                ecePct = 0.0,
            )
        }

        val denom = n.toDouble()
        val mBrier = modelErr / denom
        val bBrier = baseErr / denom
        val accScore = ((1.0 - mBrier) * 100.0).roundToInt().coerceIn(0, 99)
        val roi = if (staked > 0.0) ((bankroll - 1000.0) / staked) * 100.0 else 0.0

        return LeagueTrackRecord(
            sport = sport,
            leagueId = leagueId,
            leagueName = leagueName,
            gamesTested = n,
            accuracyScore = accScore,
            beatsSimpleGuess = mBrier < bBrier,
            simulatedProfitPct = roi,
            hasGradedOdds = staked > 0.0,
            reliabilityBins = bins,
            logLoss = logLossSum / denom,
            rps = rpsSum / denom,
            ecePct = ecePct,
        )
    }
}
