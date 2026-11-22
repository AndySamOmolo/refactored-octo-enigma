package com.edgeledger.app.engine

import com.edgeledger.app.model.OddsDisplayFormat
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

// Backwards-compatible typealiases preserving existing imports from com.edgeledger.app.engine
typealias DevigMethod = com.edgeledger.app.model.DevigMethod
typealias StakingMode = com.edgeledger.app.model.StakingMode

/**
 * Shared statistical helper functions used across PredictionEngine, OddsMath, and PlayerPropsEngine.
 */
object StatsMath {
    /** Abramowitz & Stegun 7.1.26 approximation of erf(x) (max error < 1.5e-7). */
    fun erf(x: Double): Double {
        if (x.isNaN()) return 0.0
        if (!x.isFinite()) return if (x > 0) 1.0 else -1.0
        val sign = if (x < 0.0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t)
        return sign * (1.0 - poly * exp(-ax * ax))
    }

    fun normalCdf(z: Double): Double {
        if (z.isNaN()) return 0.5
        if (!z.isFinite()) return if (z > 0) 1.0 else 0.0
        return 0.5 * (1.0 + erf(z / sqrt(2.0)))
    }

    fun normalCdf(x: Double, mean: Double, sd: Double): Double {
        if (!x.isFinite() || !mean.isFinite() || !sd.isFinite()) return 0.5
        return normalCdf((x - mean) / max(0.01, sd))
    }

    /** Acklam rational approximation of the inverse standard normal CDF. */
    fun normalInvCdf(p: Double): Double {
        val x = if (p.isFinite()) p.coerceIn(1e-6, 1.0 - 1e-6) else 0.5
        val a = doubleArrayOf(
            -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
        )
        val b = doubleArrayOf(
            -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01,
        )
        val c = doubleArrayOf(
            -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
        )
        val d = doubleArrayOf(
            7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00,
        )
        val pLow = 0.02425
        val pHigh = 1.0 - pLow
        return when {
            x < pLow -> {
                val q = sqrt(-2.0 * ln(x))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
            x <= pHigh -> {
                val q = x - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
            }
            else -> {
                val q = sqrt(-2.0 * ln(1.0 - x))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
        }
    }

    /** Exact log-factorial via log-gamma summation (safe for any non-negative k). */
    fun logFactorial(k: Int): Double {
        if (k <= 1) return 0.0
        var sum = 0.0
        for (i in 2..k) sum += ln(i.toDouble())
        return sum
    }

    /** Numerically stable Poisson PMF in log space (never clamps at 8!). */
    fun poissonPmf(k: Int, lambda: Double): Double {
        if (k < 0 || !lambda.isFinite()) return 0.0
        val safeL = max(1e-6, lambda)
        return exp(k * ln(safeL) - safeL - logFactorial(k))
    }

    /** Exact P(X > line) = 1 - P(X <= floor(line)) using iterative recurrence without factorial overflow. */
    fun poissonOverProb(mean: Double, line: Double): Double {
        if (!mean.isFinite() || !line.isFinite()) return 0.5
        val safeL = max(0.01, mean)
        val maxU = floor(line).toInt()
        if (maxU < 0) return 0.99
        if (safeL > 100.0) {
            return (1.0 - normalCdf(maxU + 0.5, safeL, sqrt(safeL))).coerceIn(0.01, 0.99)
        }
        var term = exp(-safeL)
        var under = term
        for (k in 1..maxU) {
            term *= safeL / k.toDouble()
            under += term
            if (term < 1e-15 && k > safeL) break
        }
        return (1.0 - under).coerceIn(0.01, 0.99)
    }

    /** Exact Poisson quantile: smallest k >= 0 with P(X <= k) >= q. */
    fun poissonQuantile(mean: Double, q: Double): Int {
        if (!mean.isFinite() || mean <= 0.0) return 0
        val target = q.coerceIn(0.001, 0.999)
        if (mean > 100.0) {
            return max(0, (mean + normalInvCdf(target) * sqrt(mean)).roundToInt())
        }
        var term = exp(-mean)
        var cdf = term
        var k = 0
        while (cdf < target && k < 500) {
            k++
            term *= mean / k.toDouble()
            cdf += term
        }
        return k
    }
}

data class DevigResult(
    val fairHome: Double,
    val fairDraw: Double,
    val fairAway: Double,
    val overround: Double,
    val holdPct: Double,
)

data class SideValueEval(
    val label: String,
    val modelProb: Double,
    val fairMarketProb: Double,
    val decimalOdds: Double,
    val expectedValue: Double,
    val edgePctPoints: Double,
    val suggestedStakeFraction: Double,
    val isGoodValue: Boolean,
)

data class ParlayResult(
    val combinedOdds: Double,
    val combinedChancePct: Int,
    val potentialPayout: Double,
    val potentialProfit: Double,
    val estimatedVigPct: Double,
    val correlationWarning: String?,
)

data class ArbitrageResult(
    val isArbitrage: Boolean,
    val profitPct: Double,
    val stakeA: Double,
    val stakeB: Double,
    val guaranteedPayout: Double,
    val guaranteedProfit: Double,
)

data class SignificanceResult(
    val zScore: Double,
    val pValue: Double,
    val beatsRandomChance: Boolean,
    val plainLabel: String,
)

object OddsMath {

    private const val EPSILON = 1e-12
    private const val MIN_PROB_FLOOR = 1e-6
    private const val SOLVER_ITERATIONS = 64
    private const val MAX_FRACTIONAL_DENOMINATOR = 20
    private const val FRACTIONAL_MATCH_TOLERANCE = 0.005
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** Expected value per 1 unit staked: (p * O) - 1. Returns -1.0 on invalid inputs. */
    fun expectedValue(modelProb: Double, decimalOdds: Double): Double {
        if (!modelProb.isFinite() || !decimalOdds.isFinite() ||
            modelProb !in 0.0..1.0 || decimalOdds <= 1.0
        ) {
            return -1.0
        }
        return (modelProb * decimalOdds) - 1.0
    }

    /**
     * Converts valid American odds (|american| >= 100) to decimal odds.
     * Returns null on NaN, Infinity, 0, or invalid (-100, +100) values.
     */
    fun americanToDecimalOrNull(american: Double): Double? {
        if (!american.isFinite() || abs(american) < 100.0) return null
        return if (american > 0.0) 1.0 + (american / 100.0) else 1.0 + (100.0 / (-american))
    }

    /** Backwards-compatible converter returning Double.NaN for invalid American odds. */
    fun americanToDecimal(american: Double): Double =
        americanToDecimalOrNull(american) ?: Double.NaN

    /**
     * Removes bookmaker overround (vig) from 2-way or 3-way decimal odds using [method].
     * Invalid non-null drawOdds returns a 3-way uniform fallback rather than silently dropping Draw.
     */
    fun deVig(
        homeOdds: Double,
        awayOdds: Double,
        drawOdds: Double? = null,
        method: DevigMethod = DevigMethod.SHIN,
    ): DevigResult {
        val isThreeWay = drawOdds != null
        val validDraw = drawOdds != null && drawOdds.isFinite() && drawOdds > 1.0
        val validEnds = homeOdds.isFinite() && awayOdds.isFinite() && homeOdds > 1.0 && awayOdds > 1.0
        if (!validEnds || (isThreeWay && !validDraw)) {
            return if (isThreeWay) {
                DevigResult(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0, 1.0, 0.0)
            } else {
                DevigResult(0.5, 0.0, 0.5, 1.0, 0.0)
            }
        }

        val qHome = 1.0 / homeOdds
        val qAway = 1.0 / awayOdds
        val qDraw = if (isThreeWay) 1.0 / drawOdds!! else 0.0
        val rawProbs = if (isThreeWay) doubleArrayOf(qHome, qDraw, qAway) else doubleArrayOf(qHome, qAway)

        val overround = rawProbs.sum()
        val holdPct = if (overround > EPSILON) ((overround - 1.0) / overround) * 100.0 else 0.0

        val fairProbs = if (overround <= 1.0 + EPSILON) {
            normalize(rawProbs)
        } else {
            when (method) {
                DevigMethod.SHIN -> solveShin(rawProbs, overround)
                DevigMethod.MULTIPLICATIVE -> normalize(rawProbs)
                DevigMethod.POWER -> solvePower(rawProbs)
                DevigMethod.ADDITIVE -> solveAdditive(rawProbs, overround)
                DevigMethod.ODDS_RATIO -> solveOddsRatio(rawProbs)
            }
        }

        return if (isThreeWay) {
            DevigResult(fairProbs[0], fairProbs[1], fairProbs[2], overround, holdPct)
        } else {
            DevigResult(fairProbs[0], 0.0, fairProbs[1], overround, holdPct)
        }
    }

    fun deVigShin(
        homeOdds: Double,
        awayOdds: Double,
        drawOdds: Double? = null,
    ): DevigResult = deVig(homeOdds, awayOdds, drawOdds, DevigMethod.SHIN)

    fun fractionalKelly(
        modelProb: Double,
        decimalOdds: Double,
        fraction: Double = 0.25,
        maxCap: Double = 0.05,
    ): Double {
        if (!modelProb.isFinite() || !decimalOdds.isFinite() ||
            !fraction.isFinite() || !maxCap.isFinite() ||
            modelProb <= 0.0 || modelProb >= 1.0 ||
            decimalOdds <= 1.0 || fraction <= 0.0 || maxCap <= 0.0
        ) {
            return 0.0
        }
        val safeCap = maxCap.coerceAtMost(1.0)
        val netOdds = decimalOdds - 1.0
        val fullKelly = (modelProb * decimalOdds - 1.0) / netOdds
        if (fullKelly <= 0.0) return 0.0
        return (fullKelly * fraction).coerceIn(0.0, safeCap)
    }

    fun recommendedStake(
        bankroll: Double,
        modelProb: Double,
        decimalOdds: Double,
        mode: StakingMode,
        kellyFraction: Double = 0.25,
        maxCap: Double = 0.05,
        flatUnitPct: Double = 0.02,
    ): Double {
        if (!bankroll.isFinite() || bankroll <= 0.0 ||
            !maxCap.isFinite() || maxCap <= 0.0 ||
            !modelProb.isFinite() || modelProb !in 0.0..1.0 ||
            !decimalOdds.isFinite() || decimalOdds <= 1.0
        ) {
            return 0.0
        }
        val safeCap = maxCap.coerceAtMost(1.0)
        val stakeFraction = when (mode) {
            StakingMode.FRACTIONAL_KELLY -> fractionalKelly(
                modelProb = modelProb,
                decimalOdds = decimalOdds,
                fraction = kellyFraction,
                maxCap = safeCap,
            )
            StakingMode.FLAT,
            StakingMode.PERCENT_BANKROLL -> {
                if (!flatUnitPct.isFinite() || flatUnitPct <= 0.0 || expectedValue(modelProb, decimalOdds) <= 0.0) 0.0
                else flatUnitPct.coerceIn(0.0, safeCap)
            }
        }
        return bankroll * stakeFraction
    }

    fun evaluateSides(
        homeLabel: String,
        awayLabel: String,
        probHome: Double,
        probDraw: Double,
        probAway: Double,
        oddsHome: Double,
        oddsDraw: Double? = null,
        oddsAway: Double,
        kellyFraction: Double = 0.25,
        maxStakePct: Double = 0.05,
        minEvThreshold: Double = 0.02,
        minProbFloor: Double = 0.20,
        devigMethod: DevigMethod = DevigMethod.SHIN,
    ): List<SideValueEval> {
        val safeMinEv = if (minEvThreshold.isFinite()) minEvThreshold else 0.02
        val safeMinProb = if (minProbFloor.isFinite()) minProbFloor.coerceIn(0.0, 1.0) else 0.20
        val hasDrawOdds = oddsDraw != null && oddsDraw.isFinite() && oddsDraw > 1.0 && probDraw.isFinite() && probDraw > 0.0

        val devig = deVig(
            homeOdds = oddsHome,
            awayOdds = oddsAway,
            drawOdds = if (hasDrawOdds) oddsDraw else null,
            method = devigMethod,
        )

        // When evaluating a 2-way Draw-No-Bet line on a sport with probDraw > 0, renormalize H/A over (pH + pA)
        val safeH = if (probHome.isFinite()) probHome.coerceIn(0.0, 1.0) else 0.0
        val safeA = if (probAway.isFinite()) probAway.coerceIn(0.0, 1.0) else 0.0
        val safeD = if (probDraw.isFinite()) probDraw.coerceIn(0.0, 1.0) else 0.0
        val isTwoWayDnb = oddsDraw == null && safeD > 0.0
        val twoWaySum = safeH + safeA
        val effHomeProb = if (isTwoWayDnb && twoWaySum > EPSILON) safeH / twoWaySum else safeH
        val effAwayProb = if (isTwoWayDnb && twoWaySum > EPSILON) safeA / twoWaySum else safeA

        fun evalSide(
            label: String,
            modelProb: Double,
            fairProb: Double,
            decimalOdds: Double,
        ): SideValueEval {
            val safeProb = if (modelProb.isFinite()) modelProb.coerceIn(0.0, 1.0) else 0.0
            val safeOdds = if (decimalOdds.isFinite() && decimalOdds > 1.0) decimalOdds else 1.0
            val ev = expectedValue(safeProb, safeOdds)
            val edgePctPoints = (safeProb - fairProb) * 100.0
            val stakeFraction = fractionalKelly(
                modelProb = safeProb,
                decimalOdds = safeOdds,
                fraction = kellyFraction,
                maxCap = maxStakePct,
            )
            val isGoodValue = safeOdds > 1.0 && ev >= safeMinEv && safeProb >= safeMinProb

            return SideValueEval(
                label = label,
                modelProb = safeProb,
                fairMarketProb = fairProb,
                decimalOdds = safeOdds,
                expectedValue = ev,
                edgePctPoints = edgePctPoints,
                suggestedStakeFraction = stakeFraction,
                isGoodValue = isGoodValue,
            )
        }

        val results = mutableListOf<SideValueEval>()
        results += evalSide(homeLabel, effHomeProb, devig.fairHome, oddsHome)
        if (hasDrawOdds) {
            results += evalSide("Draw", safeD, devig.fairDraw, oddsDraw!!)
        }
        results += evalSide(awayLabel, effAwayProb, devig.fairAway, oddsAway)
        return results
    }

    /** Formats decimal odds safely using Long bounds so extreme odds never saturate Int. */
    fun formatOdds(decimalOdds: Double, format: OddsDisplayFormat): String {
        if (!decimalOdds.isFinite() || decimalOdds <= 1.0) return "--"
        val bounded = decimalOdds.coerceIn(1.001, 10000.0)

        return when (format) {
            OddsDisplayFormat.DECIMAL -> String.format(Locale.US, "%.2f", bounded)
            OddsDisplayFormat.AMERICAN -> {
                if (bounded >= 2.0) {
                    val american = ((bounded - 1.0) * 100.0).roundToLong()
                    "+$american"
                } else {
                    val american = (-100.0 / (bounded - 1.0)).roundToLong()
                    american.toString()
                }
            }
            OddsDisplayFormat.FRACTIONAL -> formatFractionalOdds(bounded - 1.0)
            OddsDisplayFormat.PERCENT -> {
                val impliedPct = 100.0 / bounded
                String.format(Locale.US, "%.1f%%", impliedPct)
            }
        }
    }

    /**
     * Calculates combined parlay odds, win probability, payout, profit, and estimated vig.
     * Each leg pair is `(decimalOdds, winProbability)`.
     */
    fun calculateParlay(
        legs: List<Pair<Double, Double>>,
        stake: Double,
        sameGameWarning: Boolean = false,
    ): ParlayResult {
        val warning = if (sameGameWarning) {
            "Same-game picks can be linked, so your true chance of winning may differ from the combined estimate."
        } else {
            null
        }

        val safeStake = if (stake.isFinite() && stake > 0.0) stake else 0.0
        var combinedOdds = 1.0
        var combinedProb = 1.0
        var validLegs = 0

        for ((legOdds, legProb) in legs) {
            if (!legOdds.isFinite() || legOdds <= 1.0) continue
            val nextOdds = combinedOdds * legOdds
            if (!nextOdds.isFinite()) break
            combinedOdds = nextOdds

            val resolvedProb = if (legProb.isFinite() && legProb > 0.0 && legProb <= 1.0) {
                legProb
            } else {
                1.0 / legOdds
            }
            combinedProb *= resolvedProb
            validLegs++
        }

        if (validLegs == 0) {
            return ParlayResult(
                combinedOdds = 1.0,
                combinedChancePct = 0,
                potentialPayout = 0.0,
                potentialProfit = 0.0,
                estimatedVigPct = 0.0,
                correlationWarning = warning,
            )
        }

        val combinedChancePct = (combinedProb * 100.0).roundToInt().coerceIn(0, 100)
        val potentialPayout = (safeStake * combinedOdds).takeIf { it.isFinite() } ?: 0.0
        val potentialProfit = (potentialPayout - safeStake).coerceAtLeast(0.0)
        val fairCombinedOdds = if (combinedProb > EPSILON) 1.0 / combinedProb else combinedOdds
        val estimatedVigPct = if (fairCombinedOdds > EPSILON && combinedOdds > EPSILON) {
            ((1.0 - (combinedOdds / fairCombinedOdds)).coerceIn(0.0, 1.0)) * 100.0
        } else {
            0.0
        }

        return ParlayResult(
            combinedOdds = combinedOdds,
            combinedChancePct = combinedChancePct,
            potentialPayout = potentialPayout,
            potentialProfit = potentialProfit,
            estimatedVigPct = estimatedVigPct,
            correlationWarning = warning,
        )
    }

    /** Detects two-way arbitrage and computes equalized-payout stakes and guaranteed return (EN-05). */
    fun calculateArbitrage(
        oddsA: Double,
        oddsB: Double,
        totalStake: Double,
    ): ArbitrageResult {
        if (!oddsA.isFinite() || !oddsB.isFinite() || !totalStake.isFinite() ||
            oddsA <= 1.0 || oddsB <= 1.0 || totalStake <= 0.0
        ) {
            return ArbitrageResult(false, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val invA = 1.0 / oddsA
        val invB = 1.0 / oddsB
        val invSum = invA + invB
        val isArbitrage = invSum < 1.0 - EPSILON

        if (!isArbitrage) {
            return ArbitrageResult(
                isArbitrage = false,
                profitPct = ((1.0 / invSum) - 1.0) * 100.0,
                stakeA = 0.0,
                stakeB = 0.0,
                guaranteedPayout = 0.0,
                guaranteedProfit = 0.0,
            )
        }

        val stakeA = totalStake * (invA / invSum)
        val stakeB = totalStake * (invB / invSum)
        val guaranteedPayout = totalStake / invSum
        val guaranteedProfit = guaranteedPayout - totalStake
        val profitPct = ((1.0 / invSum) - 1.0) * 100.0

        return ArbitrageResult(
            isArbitrage = true,
            profitPct = profitPct,
            stakeA = stakeA,
            stakeB = stakeB,
            guaranteedPayout = guaranteedPayout,
            guaranteedProfit = guaranteedProfit,
        )
    }

    /**
     * Evaluates whether a pick record beats random chance using exact one-sided binomial p-value
     * for total <= 100 and Yates continuity-corrected z-test for total > 100.
     */
    fun computeSignificance(
        wins: Int,
        total: Int,
        expectedWinRate: Double = 0.50,
    ): SignificanceResult {
        if (total <= 0 || wins < 0 || wins > total ||
            !expectedWinRate.isFinite() || expectedWinRate <= 0.0 || expectedWinRate >= 1.0
        ) {
            return SignificanceResult(0.0, 1.0, false, "Not enough settled picks yet")
        }

        val n = total.toDouble()
        val observed = wins.toDouble() / n
        val stdError = sqrt(expectedWinRate * (1.0 - expectedWinRate) / n)
        val zScore = if (stdError > EPSILON) (observed - expectedWinRate) / stdError else 0.0

        val pValue = if (total <= 100) {
            exactBinomialUpperTail(wins, total, expectedWinRate)
        } else {
            val continuityAdjusted = ((wins - 0.5) / n - expectedWinRate) / max(EPSILON, stdError)
            (1.0 - StatsMath.normalCdf(continuityAdjusted)).coerceIn(0.0, 1.0)
        }

        val beatsRandomChance = total >= 10 && zScore > 0.0 && pValue < 0.05
        val plainLabel = when {
            total < 10 -> "Small sample — keep tracking picks"
            zScore > 0.0 && pValue < 0.01 -> "Strong edge"
            zScore > 0.0 && pValue < 0.05 -> "Real skill"
            zScore > 0.0 && pValue < 0.15 -> "Promising trend — needs more picks"
            zScore >= -1.0 -> "In line with random chance"
            else -> "Below avg"
        }

        return SignificanceResult(zScore, pValue, beatsRandomChance, plainLabel)
    }

    private fun exactBinomialUpperTail(kMin: Int, n: Int, p: Double): Double {
        val logN = StatsMath.logFactorial(n)
        val logP = ln(p)
        val logQ = ln(1.0 - p)
        var sum = 0.0
        for (k in kMin..n) {
            val logC = logN - StatsMath.logFactorial(k) - StatsMath.logFactorial(n - k)
            sum += exp(logC + k * logP + (n - k) * logQ)
        }
        return sum.coerceIn(0.0, 1.0)
    }

    fun computeClvPct(takenOdds: Double, closingOdds: Double): Double {
        if (!takenOdds.isFinite() || !closingOdds.isFinite() ||
            takenOdds <= 1.0 || closingOdds <= 1.0
        ) {
            return 0.0
        }
        return ((takenOdds / closingOdds) - 1.0) * 100.0
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }

    /** Canonical pre-kickoff SHA-256 hash binding fixture, matchup, pick, probability, model version, and timestamp. */
    fun buildLockedPickHash(
        fixtureId: String,
        matchup: String,
        pickLabel: String,
        chancePct: Int,
        modelVersion: String,
        lockedAtIso: String,
    ): String = sha256Hex("$fixtureId|$matchup|$pickLabel|$chancePct|$modelVersion|$lockedAtIso")

    // -------------------------------------------------------------------------
    // Internal De-Vig Solvers
    // -------------------------------------------------------------------------

    private fun normalize(values: DoubleArray): DoubleArray {
        val sum = values.sum()
        if (sum <= EPSILON) {
            val uniform = 1.0 / values.size.toDouble()
            return DoubleArray(values.size) { uniform }
        }
        return DoubleArray(values.size) { i -> values[i] / sum }
    }

    private fun solveShin(rawProbs: DoubleArray, overround: Double): DoubleArray {
        var lo = 0.0
        var hi = 1.0 - 1e-9

        fun shinProbs(z: Double): DoubleArray {
            val denom = 2.0 * (1.0 - z)
            return DoubleArray(rawProbs.size) { i ->
                val q = rawProbs[i]
                val disc = z * z + 4.0 * (1.0 - z) * (q * q / overround)
                ((sqrt(disc) - z) / denom).coerceAtLeast(MIN_PROB_FLOOR)
            }
        }

        repeat(SOLVER_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            if (shinProbs(mid).sum() > 1.0) lo = mid else hi = mid
        }
        return normalize(shinProbs(0.5 * (lo + hi)))
    }

    private fun solvePower(rawProbs: DoubleArray): DoubleArray {
        var lo = 1.0
        var hi = 2.0

        fun powerSum(k: Double): Double {
            var sum = 0.0
            for (q in rawProbs) sum += q.pow(k)
            return sum
        }

        while (powerSum(hi) > 1.0 && hi < 1e4) {
            hi *= 2.0
        }

        repeat(SOLVER_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            if (powerSum(mid) > 1.0) lo = mid else hi = mid
        }
        val k = 0.5 * (lo + hi)
        return normalize(DoubleArray(rawProbs.size) { i -> rawProbs[i].pow(k) })
    }

    private fun solveAdditive(rawProbs: DoubleArray, overround: Double): DoubleArray {
        val marginPerOutcome = (overround - 1.0) / rawProbs.size.toDouble()
        val adjusted = DoubleArray(rawProbs.size) { i ->
            (rawProbs[i] - marginPerOutcome).coerceAtLeast(MIN_PROB_FLOOR)
        }
        return normalize(adjusted)
    }

    private fun solveOddsRatio(rawProbs: DoubleArray): DoubleArray {
        var lo = 1.0
        var hi = 2.0

        fun ratioProbs(c: Double): DoubleArray {
            return DoubleArray(rawProbs.size) { i ->
                val q = rawProbs[i]
                val denom = q + c * (1.0 - q)
                if (denom > EPSILON) q / denom else MIN_PROB_FLOOR
            }
        }

        while (ratioProbs(hi).sum() > 1.0 && hi < 1e6) {
            hi *= 2.0
        }

        repeat(SOLVER_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            if (ratioProbs(mid).sum() > 1.0) lo = mid else hi = mid
        }
        return normalize(ratioProbs(0.5 * (lo + hi)))
    }

    // -------------------------------------------------------------------------
    // Internal Formatting Helpers
    // -------------------------------------------------------------------------

    private fun formatFractionalOdds(netOdds: Double): String {
        if (!netOdds.isFinite() || netOdds <= 0.0) return "--"

        var bestNum = 1L
        var bestDen = 1L
        var bestErr = Double.POSITIVE_INFINITY

        for (den in 1L..MAX_FRACTIONAL_DENOMINATOR.toLong()) {
            val num = (netOdds * den).roundToLong().coerceAtLeast(1L)
            val err = abs(netOdds - num.toDouble() / den.toDouble())
            if (err < bestErr) {
                bestErr = err
                bestNum = num
                bestDen = den
            }
        }

        if (bestErr <= FRACTIONAL_MATCH_TOLERANCE) {
            val g = gcd(bestNum, bestDen)
            return "${bestNum / g}/${bestDen / g}"
        }

        val scale = if (netOdds < 0.01) 1000L else 100L
        val scaledNum = (netOdds * scale).roundToLong().coerceAtLeast(1L)
        val g = gcd(scaledNum, scale)
        return "${scaledNum / g}/${scale / g}"
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val r = x % y
            x = y
            y = r
        }
        return if (x == 0L) 1L else x
    }
}
