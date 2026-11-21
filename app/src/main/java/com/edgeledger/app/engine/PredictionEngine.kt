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
import com.edgeledger.app.model.TeamProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PredictionEngine {
    private val FACTORIALS = doubleArrayOf(1.0, 1.0, 2.0, 6.0, 24.0, 120.0, 720.0, 5040.0, 40320.0)

    fun eloToPowerScore(elo: Double): Int {
        val clamped = elo.coerceIn(1200.0, 2150.0)
        return (((clamped - 1200.0) / (2150.0 - 1200.0)) * 99.0).roundToInt().coerceIn(1, 99)
    }

    fun expectedEloScore(ratingA: Double, ratingB: Double): Double =
        1.0 / (1.0 + 10.0.pow((ratingB - ratingA) / 400.0))

    fun profileKey(sport: Sport, team: String): String = "${sport.name}::$team"

    private fun poissonPmf(k: Int, lambda: Double): Double {
        val safeL = max(0.04, lambda)
        val fact = if (k in FACTORIALS.indices) FACTORIALS[k] else 40320.0
        return (safeL.pow(k) * exp(-safeL)) / fact
    }

    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t)
        return sign * (1.0 - poly * exp(-ax * ax))
    }

    private fun normalCdf(x: Double, mean: Double, sd: Double): Double =
        0.5 * (1.0 + erf((x - mean) / (max(0.01, sd) * sqrt(2.0))))

    private fun poissonOverProb(mean: Double, line: Double): Double {
        val maxU = line.toInt()
        var under = 0.0
        for (k in 0..maxU) under += poissonPmf(k, mean)
        return (1.0 - under).coerceIn(0.03, 0.97)
    }

    private fun toPct(p: Double): Int = (p * 100.0).roundToInt().coerceIn(1, 99)

    private fun conf(p: Double): ConfidenceLevel = when {
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
        Sport.TENNIS, Sport.F1, Sport.MMA -> 0.0
    }

    /**
     * Fits Multi-Sport Elo + ClubElo anchor + full rich team stat profiles from historical matches.
     */
    fun buildTeamProfiles(
        matches: List<HistoricalMatch>,
        clubEloMap: Map<String, Double> = emptyMap(),
    ): Map<String, TeamProfile> {
        val sorted = matches.sortedBy { it.dateIso }

        class Acc(val team: String, val sport: Sport, val leagueId: String, var elo: Double) {
            var gp = 0; var w = 0; var d = 0; var l = 0
            var hg = 0; var hw = 0; var ag = 0; var aw = 0
            var scored = 0.0; var conceded = 0.0
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
            return map.getOrPut(k) {
                val initElo = if (sport == Sport.FOOTBALL) clubEloMap[team] ?: 1500.0 else 1500.0
                Acc(team, sport, leagueId, initElo)
            }
        }

        for (m in sorted) {
            val h = get(m.sport, m.leagueId, m.homeTeam)
            val a = get(m.sport, m.leagueId, m.awayTeam)
            val hAdv = homeAdvFor(m.sport)
            val expH = expectedEloScore(h.elo + hAdv, a.elo)
            val actualH = when {
                m.homeScore > m.awayScore -> 1.0
                m.homeScore == m.awayScore -> 0.5
                else -> 0.0
            }
            val margin = abs(m.homeScore - m.awayScore)
            val mov = if (margin == 0) 1.0 else ln(margin + 1.0) * 0.35 + 0.85
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
                        if (m.homeScore + m.awayScore != tl.toInt()) {
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

        return map.mapValues { (_, acc) ->
            val clubAnchor = if (acc.sport == Sport.FOOTBALL) clubEloMap[acc.team] else null
            val finalElo = if (clubAnchor != null) acc.elo * 0.65 + clubAnchor * 0.35 else acc.elo
            val gp = max(1, acc.gp).toDouble()
            TeamProfile(
                team = acc.team,
                sport = acc.sport,
                leagueId = acc.leagueId,
                elo = finalElo,
                clubElo = clubAnchor,
                powerScore = eloToPowerScore(finalElo),
                gamesPlayed = acc.gp,
                wins = acc.w,
                draws = acc.d,
                losses = acc.l,
                winPct = (acc.w / gp) * 100.0,
                homeWinPct = if (acc.hg > 0) (acc.hw.toDouble() / acc.hg) * 100.0 else 55.0,
                awayWinPct = if (acc.ag > 0) (acc.aw.toDouble() / acc.ag) * 100.0 else 45.0,
                avgScored = acc.scored / gp,
                avgConceded = acc.conceded / gp,
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
        marketBlendWeight: Double = 0.0, // 0 = pure model, 0.3 = 30% sharp market blend
    ): FullMatchMarkets {
        val hElo = homeProfile?.elo ?: 1500.0
        val aElo = awayProfile?.elo ?: 1500.0

        if (sport == Sport.FOOTBALL) {
            val lgAvg = 1.38
            val hAtk = ((homeProfile?.avgScored ?: 1.45) * 0.7 + (homeProfile?.shotsOnTargetPerGame ?: 4.6) * 0.31 * 0.3)
            val hDef = homeProfile?.avgConceded ?: 1.25
            val aAtk = ((awayProfile?.avgScored ?: 1.30) * 0.7 + (awayProfile?.shotsOnTargetPerGame ?: 4.2) * 0.30 * 0.3)
            val aDef = awayProfile?.avgConceded ?: 1.35

            val rFactor = ((hElo - aElo) / 850.0).coerceIn(-0.32, 0.32)
            val lambda = (((hAtk * aDef) / lgAvg) * 1.14 * (1.0 + rFactor * 0.55)).coerceIn(0.30, 4.0)
            val mu = (((aAtk * hDef) / lgAvg) * (1.0 - rFactor * 0.55)).coerceIn(0.24, 3.6)

            // Coherent joint scoreline grid (Dixon-Coles low-score correlation rho = -0.09)
            val rho = -0.09
            val mat = Array(8) { DoubleArray(8) }
            var totalMass = 0.0
            for (h in 0..7) {
                for (a in 0..7) {
                    val tau = when {
                        h == 0 && a == 0 -> max(0.05, 1.0 - lambda * mu * rho)
                        h == 0 && a == 1 -> max(0.05, 1.0 + lambda * rho)
                        h == 1 && a == 0 -> max(0.05, 1.0 + mu * rho)
                        h == 1 && a == 1 -> max(0.05, 1.0 - rho)
                        else -> 1.0
                    }
                    val p = poissonPmf(h, lambda) * poissonPmf(a, mu) * tau
                    mat[h][a] = p
                    totalMass += p
                }
            }

            var pH = 0.0; var pD = 0.0; var pA = 0.0
            var o05 = 0.0; var o15 = 0.0; var o25 = 0.0; var o35 = 0.0; var o45 = 0.0; var btts = 0.0
            var hO15 = 0.0; var aO15 = 0.0; var hCs = 0.0; var hWtn = 0.0
            var hWinO15 = 0.0; var hWinBtts = 0.0; var h2p = 0.0; var aPlus15 = 0.0
            val scores = mutableListOf<Pair<String, Double>>()

            for (h in 0..7) {
                for (a in 0..7) {
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
                    scores.add("$h-$a" to p)
                }
            }

            if (sharpMarketHomeProb != null && marketBlendWeight > 0.0) {
                val w = marketBlendWeight.coerceIn(0.0, 0.5)
                pH = pH * (1.0 - w) + sharpMarketHomeProb * w
                val rem = max(0.02, 1.0 - pH)
                val ratioD = pD / max(0.01, pD + pA)
                pD = rem * ratioD
                pA = rem * (1.0 - ratioD)
            }

            val topScores = scores.sortedByDescending { it.second }.take(6)
                .map { ExactScorelineItem(it.first, toPct(it.second)) }

            val h1Exp = lambda * 0.44
            val a1Exp = mu * 0.43
            val ht05 = 1.0 - exp(-(h1Exp + a1Exp))
            val ht15 = poissonOverProb(h1Exp + a1Exp, 1.5)
            val sh05 = 1.0 - exp(-((lambda - h1Exp) + (mu - a1Exp)))

            val projCorners = (homeProfile?.cornersPerGame ?: 5.4) + (awayProfile?.cornersPerGame ?: 4.7)
            val projCards = (homeProfile?.cardsPerGame ?: 1.9) + (awayProfile?.cardsPerGame ?: 2.0)
            val projFouls = (homeProfile?.foulsPerGame ?: 11.1) + (awayProfile?.foulsPerGame ?: 11.6)
            val redChance = 1.0 - exp(-((homeProfile?.redCardsPerGame ?: 0.08) + (awayProfile?.redCardsPerGame ?: 0.09)))
            val projShots = (homeProfile?.shotsPerGame ?: 13.0) + (awayProfile?.shotsPerGame ?: 11.4)
            val projSot = (homeProfile?.shotsOnTargetPerGame ?: 4.8) + (awayProfile?.shotsOnTargetPerGame ?: 4.1)

            val topHtFt = listOf(
                "$homeTeam / $homeTeam" to toPct(pH * 0.62),
                "Draw / $homeTeam" to toPct(pH * 0.34),
                "Draw / Draw" to toPct(pD * 0.64),
                "Draw / $awayTeam" to toPct(pA * 0.36),
                "$awayTeam / $awayTeam" to toPct(pA * 0.58),
            ).sortedByDescending { it.second }.take(3)

            val picks = buildList {
                if (pH >= pA && pH >= 0.48) add(MarketPickItem("fb_h", "Match Result", "$homeTeam to Win", toPct(pH), conf(pH), "$homeTeam project ${String.format("%.1f", lambda)} goals at home."))
                else if (pA > pH && pA >= 0.48) add(MarketPickItem("fb_a", "Match Result", "$awayTeam to Win", toPct(pA), conf(pA), "$awayTeam hold the stronger attacking edge."))
                val dc = max(pH + pD, pA + pD)
                val dcTeam = if (pH >= pA) homeTeam else awayTeam
                if (dc >= 0.68) add(MarketPickItem("fb_dc", "Double Chance", "$dcTeam or Draw", toPct(dc), conf(dc), "$dcTeam have strong form and should avoid defeat."))
                if (o25 >= 0.55) add(MarketPickItem("fb_o25", "Goals", "Over 2.5 Goals", toPct(o25), conf(o25), "Both teams combine for ${String.format("%.1f", lambda + mu)} projected goals."))
                else if (1.0 - o25 >= 0.56) add(MarketPickItem("fb_u25", "Goals", "Under 2.5 Goals", toPct(1.0 - o25), conf(1.0 - o25), "Tight defenses project around ${String.format("%.1f", lambda + mu)} total goals."))
                if (btts >= 0.55) add(MarketPickItem("fb_btts", "Both Teams Score", "Both Teams to Score", toPct(btts), conf(btts), "Both sides create steady chances on target."))
                if (ht05 >= 0.68) add(MarketPickItem("fb_1h", "First Half", "1st Half Over 0.5 Goals", toPct(ht05), conf(ht05), "Fast-starting attacks point to an early goal."))
                val c95 = poissonOverProb(projCorners, 9.5)
                if (c95 >= 0.56) add(MarketPickItem("fb_cor", "Corners", "Over 9.5 Corners", toPct(c95), conf(c95), "Projected total is ${String.format("%.1f", projCorners)} corners."))
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
                cornersOver85Pct = toPct(poissonOverProb(projCorners, 8.5)),
                cornersOver95Pct = toPct(poissonOverProb(projCorners, 9.5)),
                projectedCards = projCards,
                cardsOver35Pct = toPct(poissonOverProb(projCards, 3.5)),
                redCardChancePct = toPct(redChance.coerceIn(0.06, 0.35)),
                projectedFouls = projFouls,
                foulsOver215Pct = toPct(poissonOverProb(projFouls, 21.5)),
                projectedShotsTotal = projShots,
                projectedSotTotal = projSot,
                topScorelines = topScores,
                topPicks = picks,
            )
        }

        if (sport == Sport.TENNIS) {
            val holdA = ((homeProfile?.firstServeWinPct ?: 73.0) / 100.0 * 1.12).coerceIn(0.62, 0.94)
            val holdB = ((awayProfile?.firstServeWinPct ?: 73.0) / 100.0 * 1.12).coerceIn(0.62, 0.94)
            val baseA = expectedEloScore(hElo, aElo)
            val pSetA = (baseA * 0.75 + (0.5 + (holdA - holdB) * 1.4) * 0.25).coerceIn(0.12, 0.88)
            val pSetB = 1.0 - pSetA
            val straightA = pSetA * pSetA
            val straightB = pSetB * pSetB
            val threeSets = 2.0 * pSetA * pSetB
            var matchA = straightA + threeSets * pSetA
            if (sharpMarketHomeProb != null && marketBlendWeight > 0.0) {
                val w = marketBlendWeight.coerceIn(0.0, 0.5)
                matchA = matchA * (1.0 - w) + sharpMarketHomeProb * w
            }
            val expSets = 2.0 * (straightA + straightB) + 3.0 * threeSets
            val surfBase = when (surface) { "Grass" -> 10.0; "Clay" -> 9.3; else -> 9.6 }
            val projGames = expSets * surfBase
            val gLine = if (surface == "Clay") 21.5 else 22.5
            val overG = 1.0 - normalCdf(gLine, projGames, 4.3)
            val avgHold = (holdA + holdB) / 2.0
            val tbChance = (1.0 - (1.0 - (0.08 + max(0.0, avgHold - 0.72) * 1.5).coerceIn(0.08, 0.42)).pow(expSets)).coerceIn(0.14, 0.78)
            val favName = if (matchA >= 0.5) homeTeam else awayTeam
            val favProb = max(matchA, 1.0 - matchA)
            val hcapProb = (1.0 - normalCdf(2.5, abs(pSetA - 0.5) * 9.5, 3.4)).coerceIn(0.25, 0.86)
            val projAces = (homeProfile?.avgAces ?: 6.8) + (awayProfile?.avgAces ?: 6.4)
            val projDf = (homeProfile?.avgDoubleFaults ?: 2.4) + (awayProfile?.avgDoubleFaults ?: 2.5)

            return FullMatchMarkets(
                projectedHome = matchA * 100.0,
                projectedAway = (1.0 - matchA) * 100.0,
                probHome = matchA,
                probDraw = 0.0,
                probAway = 1.0 - matchA,
                firstSetHomePct = toPct(pSetA),
                straightSetsPct = toPct(straightA + straightB),
                goesToThreeSetsPct = toPct(threeSets),
                gameHandicapLabel = "${favName.substringBefore(' ')} -2.5 Games",
                gameHandicapPct = toPct(hcapProb),
                tiebreakChancePct = toPct(tbChance),
                projectedAces = projAces,
                projectedDoubleFaults = projDf,
                projectedTotalGames = projGames,
                totalGamesLine = gLine,
                totalGamesOverPct = toPct(overG),
                topPicks = listOf(
                    MarketPickItem("atp_w", "Match Result", "$favName to Win", toPct(favProb), conf(favProb), "Stronger ${surface ?: "Hard"}-court serve and overall form."),
                    MarketPickItem("atp_s1", "First Set", "$favName to Win 1st Set", toPct(max(pSetA, pSetB)), conf(max(pSetA, pSetB)), "Higher projected service hold rate."),
                    MarketPickItem("atp_sets", "Sets", if (straightA + straightB >= 0.58) "Straight Sets (2-0)" else "Goes to 3 Sets", toPct(max(straightA + straightB, threeSets)), conf(max(straightA + straightB, threeSets)), "Projected ${String.format("%.1f", projGames)} games (${toPct(tbChance)}% tiebreak chance)."),
                ),
            )
        }

        // US Team Sports + Cricket / F1 / MMA
        val baseWinH = expectedEloScore(hElo + homeAdvFor(sport), aElo)
        val winH = if (sharpMarketHomeProb != null && marketBlendWeight > 0.0) {
            val w = marketBlendWeight.coerceIn(0.0, 0.5)
            baseWinH * (1.0 - w) + sharpMarketHomeProb * w
        } else baseWinH

        val cfg: Array<Any> = when (sport) {
            Sport.NFL -> arrayOf(23.5, 21.0, 13.5, 13.0, 3.5, "One-Score Game (<=7 pts)", 7.0, 0.08, "Points")
            Sport.NBA -> arrayOf(115.0, 111.5, 12.2, 17.0, 5.5, "Close Finish (<=6 pts)", 6.0, 0.06, "Points")
            Sport.NHL -> arrayOf(3.2, 2.8, 2.15, 2.2, 1.5, "1-Goal Game", 1.5, 0.23, "Goals")
            Sport.MLB -> arrayOf(4.7, 4.3, 3.35, 3.5, 1.5, "Close Game (<=2 runs)", 2.0, 0.09, "Runs")
            Sport.CRICKET -> arrayOf(172.0, 165.0, 22.0, 28.0, 15.5, "Close Finish (<=15 runs)", 15.0, 0.04, "Runs")
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

        val pH = ((homeProfile?.avgScored ?: defScoreH) + (awayProfile?.avgConceded ?: defScoreA)) / 2.0
        val pA = ((awayProfile?.avgScored ?: defScoreA) + (homeProfile?.avgConceded ?: defScoreH)) / 2.0
        val margin = pH - pA
        val total = pH + pA
        val line = total.toInt() + 0.5
        val overP = 1.0 - normalCdf(line, total, totalSd)
        val fav = if (winH >= 0.5) homeTeam else awayTeam
        val favProb = max(winH, 1.0 - winH)
        val altP = (1.0 - normalCdf(altSpreadVal, abs(margin), marginSd)).coerceIn(0.12, 0.90)
        val closeP = (normalCdf(closeBand, margin, marginSd) - normalCdf(-closeBand, margin, marginSd)).coerceIn(0.15, 0.85)
        val otP = (otBase * exp(-((margin / marginSd).pow(2)))).coerceIn(0.03, 0.28)
        val decFmt = if (sport == Sport.NHL || sport == Sport.MLB) "%.1f" else "%.0f"
        val scoreDisp = "${String.format(decFmt, pH)} – ${String.format(decFmt, pA)}"
        val htDisp = "${String.format(decFmt, pH * 0.49)} – ${String.format(decFmt, pA * 0.49)}"

        return FullMatchMarkets(
            projectedHome = pH,
            projectedAway = pA,
            probHome = winH,
            probDraw = 0.0,
            probAway = 1.0 - winH,
            projectedScoreDisplay = scoreDisp,
            projectedSpreadDisplay = "$fav -${String.format("%.1f", abs(margin))}",
            altSpreadLabel = "$fav -$altSpreadVal",
            altSpreadPct = toPct(altP),
            projectedTotal = total,
            totalLine = line,
            totalOverPct = toPct(overP),
            halftimeDisplay = htDisp,
            closeGameLabel = closeLabel as String,
            closeGamePct = toPct(closeP),
            overtimeChancePct = toPct(otP),
            topPicks = listOf(
                MarketPickItem("${sport.name}_w", "Match Result", "$fav to Win", toPct(favProb), conf(favProb), "Projected score $scoreDisp based on current scoring and defense."),
                MarketPickItem("${sport.name}_sp", "Spread", "$fav -$altSpreadVal", toPct(altP), conf(altP), "Projected margin favors $fav by ${String.format("%.1f", abs(margin))}."),
                MarketPickItem("${sport.name}_tot", "Total", "${if (overP >= 0.5) "Over" else "Under"} $line ${unit as String}", toPct(max(overP, 1.0 - overP)), conf(max(overP, 1.0 - overP)), "Combined scoring pace projects ${String.format("%.1f", total)}."),
            ),
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
            if (sport != Sport.TENNIS && sport != Sport.F1 && sport != Sport.MMA && home.homeWinPct >= 56.0) {
                add("${home.team} is tough to beat at home, winning ${home.homeWinPct.roundToInt()}% of home games.")
            }
            if (sport == Sport.FOOTBALL) {
                add("${home.team} averages ${String.format("%.1f", home.shotsOnTargetPerGame)} shots on target and ${String.format("%.1f", home.cornersPerGame)} corners per match.")
                if (home.over25Pct >= 55.0 && away.over25Pct >= 55.0) {
                    add("Both teams play open, high-scoring football (${((home.over25Pct + away.over25Pct) / 2.0).roundToInt()}% Over 2.5 games).")
                }
            } else if (sport == Sport.NFL) {
                add("${fav.team} covers the spread ${fav.atsCoverPct.roundToInt()}% of the time and averages ${String.format("%.1f", fav.avgScored)} points.")
            } else if (sport == Sport.TENNIS) {
                add("${fav.team} wins ${fav.firstServeWinPct.roundToInt()}% of first-serve points and fires ${String.format("%.1f", fav.avgAces)} aces per match.")
            }
        }
        if (h2h.size >= 2) {
            val hw = h2h.count { it.homeScore > it.awayScore }
            val aw = h2h.count { it.awayScore > it.homeScore }
            add("Recent head-to-head: ${h2h.size} meetings ($hw Home wins, $aw Away wins).")
        }
    }.take(4)

    /**
     * Strict chronological walk-forward backtester (PR-05, PR-08).
     */
    fun runWalkForwardBacktest(
        sport: Sport,
        leagueId: String,
        leagueName: String,
        matches: List<HistoricalMatch>,
    ): LeagueTrackRecord {
        val sorted = matches.sortedBy { it.dateIso }
        val elo = mutableMapOf<String, Double>()
        val warmup = min(15, sorted.size / 4)
        val hAdv = homeAdvFor(sport)
        var n = 0
        var modelErr = 0.0
        var baseErr = 0.0
        var bankroll = 1000.0
        var staked = 0.0
        val binHits = IntArray(5)
        val binCounts = IntArray(5)

        for ((idx, m) in sorted.withIndex()) {
            val rH = elo[m.homeTeam] ?: 1500.0
            val rA = elo[m.awayTeam] ?: 1500.0
            val rawH = expectedEloScore(rH + hAdv, rA)
            val yH = if (m.homeScore > m.awayScore) 1.0 else 0.0
            val yD = if (m.homeScore == m.awayScore) 1.0 else 0.0

            if (idx >= warmup) {
                n++
                modelErr += (rawH - yH).pow(2)
                baseErr += (0.52 - yH).pow(2)
                val bIdx = (rawH * 5.0).toInt().coerceIn(0, 4)
                binCounts[bIdx]++
                if (yH == 1.0) binHits[bIdx]++

                val oH = m.oddsHome
                if (oH != null && oH > 1.05) {
                    val ev = OddsMath.expectedValue(rawH, oH)
                    if (ev >= 0.03) {
                        val stake = bankroll * OddsMath.fractionalKelly(rawH, oH, 0.25, 0.05)
                        staked += stake
                        bankroll += if (yH == 1.0) stake * (oH - 1.0) else -stake
                    }
                }
            }

            val actual = if (yH == 1.0) 1.0 else if (yD == 1.0) 0.5 else 0.0
            val d = 22.0 * (actual - rawH)
            elo[m.homeTeam] = rH + d
            elo[m.awayTeam] = rA - d
        }

        val denom = max(1, n).toDouble()
        val mBrier = modelErr / denom
        val bBrier = baseErr / denom
        val accScore = ((1.0 - mBrier * 0.75) * 100.0).roundToInt().coerceIn(45, 98)
        val roi = if (staked > 0.0) ((bankroll - 1000.0) / staked) * 100.0 else 0.0
        val labels = listOf("0-20%", "20-40%", "40-60%", "60-80%", "80-100%")
        val bins = (0..4).filter { binCounts[it] > 0 }.map { i ->
            ReliabilityBin(labels[i], ((binHits[i].toDouble() / binCounts[i]) * 100.0).roundToInt(), binCounts[i])
        }

        return LeagueTrackRecord(
            sport = sport,
            leagueId = leagueId,
            leagueName = leagueName,
            gamesTested = n,
            accuracyScore = accScore,
            beatsSimpleGuess = mBrier < bBrier,
            simulatedProfitPct = (roi * 10.0).roundToInt() / 10.0,
            reliabilityBins = bins,
        )
    }
}
