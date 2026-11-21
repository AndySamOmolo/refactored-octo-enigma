package com.edgeledger.app.engine

import com.edgeledger.app.model.OddsDisplayFormat
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Supported market de-vigging (vig-removal) algorithms (PR-07).
 */
@Serializable
enum class DevigMethod(val label: String) {
    SHIN("Shin (Sharp)"),
    MULTIPLICATIVE("Multiplicative"),
    POWER("Power"),
    ADDITIVE("Additive"),
    ODDS_RATIO("Odds-Ratio")
}

/**
 * Bankroll staking strategies for bet sizing (TR-04).
 */
@Serializable
enum class StakingMode(val label: String) {
    FRACTIONAL_KELLY("Smart Kelly"),
    FLAT("Flat Stake"),
    PERCENT_BANKROLL("% of Balance")
}

/**
 * Fair market probabilities and bookmaker margin metrics derived from decimal odds.
 */
data class DevigResult(
    val fairHome: Double,
    val fairDraw: Double,
    val fairAway: Double,
    val overround: Double,
    val holdPct: Double
)

/**
 * Expected value, market edge, and stake sizing evaluation for a single match outcome.
 */
data class SideValueEval(
    val label: String,
    val modelProb: Double,
    val fairMarketProb: Double,
    val decimalOdds: Double,
    val expectedValue: Double,
    val edgePctPoints: Double,
    val suggestedStakeFraction: Double,
    val isGoodValue: Boolean
)

/**
 * Multi-leg parlay payout, combined win chance, estimated vig, and correlation warning.
 */
data class ParlayResult(
    val combinedOdds: Double,
    val combinedChancePct: Int,
    val potentialPayout: Double,
    val potentialProfit: Double,
    val estimatedVigPct: Double,
    val correlationWarning: String?
)

/**
 * Two-way arbitrage detection and equalized-payout stake split (EN-05).
 */
data class ArbitrageResult(
    val isArbitrage: Boolean,
    val profitPct: Double,
    val stakeA: Double,
    val stakeB: Double,
    val guaranteedPayout: Double,
    val guaranteedProfit: Double
)

/**
 * Statistical significance summary comparing realized win rate against random chance (TR-01).
 */
data class SignificanceResult(
    val zScore: Double,
    val pValue: Double,
    val beatsRandomChance: Boolean,
    val plainLabel: String
)

/**
 * Core odds conversion, de-vigging, staking, parlay, arbitrage, CLV, and significance math.
 */
object OddsMath {

    fun expectedValue(modelProb: Double, decimalOdds: Double): Double =
        (modelProb * decimalOdds) - 1.0

    fun americanToDecimal(american: Double): Double = when {
        american > 0.0 -> 1.0 + (american / 100.0)
        american < 0.0 -> 1.0 + (100.0 / (-american))
        else -> 2.0
    }


    private const val EPSILON = 1e-12
    private const val MIN_PROB_FLOOR = 1e-6
    private const val SOLVER_ITERATIONS = 64
    private const val MAX_FRACTIONAL_DENOMINATOR = 20
    private const val FRACTIONAL_MATCH_TOLERANCE = 0.005
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /**
     * Removes bookmaker overround (vig) from 2-way or 3-way decimal odds using the selected [method].
     */
    fun deVig(
        homeOdds: Double,
        awayOdds: Double,
        drawOdds: Double? = null,
        method: DevigMethod = DevigMethod.SHIN
    ): DevigResult {
        val hasDraw = drawOdds != null && drawOdds.isFinite() && drawOdds > 1.0
        if (!homeOdds.isFinite() || !awayOdds.isFinite() || homeOdds <= 1.0 || awayOdds <= 1.0) {
            return if (hasDraw) {
                DevigResult(
                    fairHome = 1.0 / 3.0,
                    fairDraw = 1.0 / 3.0,
                    fairAway = 1.0 / 3.0,
                    overround = 1.0,
                    holdPct = 0.0
                )
            } else {
                DevigResult(
                    fairHome = 0.5,
                    fairDraw = 0.0,
                    fairAway = 0.5,
                    overround = 1.0,
                    holdPct = 0.0
                )
            }
        }

        val qHome = 1.0 / homeOdds
        val qAway = 1.0 / awayOdds
        val qDraw = if (hasDraw) 1.0 / drawOdds!! else 0.0
        val rawProbs = if (hasDraw) doubleArrayOf(qHome, qDraw, qAway) else doubleArrayOf(qHome, qAway)

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

        return if (hasDraw) {
            DevigResult(
                fairHome = fairProbs[0],
                fairDraw = fairProbs[1],
                fairAway = fairProbs[2],
                overround = overround,
                holdPct = holdPct
            )
        } else {
            DevigResult(
                fairHome = fairProbs[0],
                fairDraw = 0.0,
                fairAway = fairProbs[1],
                overround = overround,
                holdPct = holdPct
            )
        }
    }

    /**
     * Backwards-compatible Shin de-vigging entry point delegating to [deVig] with [DevigMethod.SHIN].
     */
    fun deVigShin(
        homeOdds: Double,
        awayOdds: Double,
        drawOdds: Double? = null
    ): DevigResult = deVig(homeOdds, awayOdds, drawOdds, DevigMethod.SHIN)

    /**
     * Computes fractional Kelly bankroll stake fraction, clamped to `[0.0, maxCap]`.
     */
    fun fractionalKelly(
        modelProb: Double,
        decimalOdds: Double,
        fraction: Double = 0.25,
        maxCap: Double = 0.05
    ): Double {
        if (!modelProb.isFinite() || !decimalOdds.isFinite() ||
            !fraction.isFinite() || !maxCap.isFinite() ||
            modelProb <= 0.0 || modelProb >= 1.0 ||
            decimalOdds <= 1.0 || fraction <= 0.0 || maxCap <= 0.0
        ) {
            return 0.0
        }
        val netOdds = decimalOdds - 1.0
        val fullKelly = (modelProb * decimalOdds - 1.0) / netOdds
        if (fullKelly <= 0.0) return 0.0
        return (fullKelly * fraction).coerceIn(0.0, maxCap)
    }

    /**
     * Computes the recommended stake amount in currency units for the selected [StakingMode].
     *
     * For [StakingMode.FLAT], pass the base/starting bankroll; for [StakingMode.PERCENT_BANKROLL],
     * pass the current bankroll balance.
     */
    fun recommendedStake(
        bankroll: Double,
        modelProb: Double,
        decimalOdds: Double,
        mode: StakingMode,
        kellyFraction: Double = 0.25,
        maxCap: Double = 0.05,
        flatUnitPct: Double = 0.02
    ): Double {
        if (!bankroll.isFinite() || bankroll <= 0.0 || !maxCap.isFinite() || maxCap <= 0.0) {
            return 0.0
        }
        val stakeFraction = when (mode) {
            StakingMode.FRACTIONAL_KELLY -> fractionalKelly(
                modelProb = modelProb,
                decimalOdds = decimalOdds,
                fraction = kellyFraction,
                maxCap = maxCap
            )
            StakingMode.FLAT,
            StakingMode.PERCENT_BANKROLL -> {
                if (!flatUnitPct.isFinite() || flatUnitPct <= 0.0) 0.0
                else flatUnitPct.coerceIn(0.0, maxCap)
            }
        }
        return bankroll * stakeFraction
    }

    /**
     * Evaluates each available match outcome (Home, optional Draw, Away) for EV, fair-market edge,
     * and suggested Kelly stake fraction.
     */
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
        devigMethod: DevigMethod = DevigMethod.SHIN
    ): List<SideValueEval> {
        val devig = deVig(
            homeOdds = oddsHome,
            awayOdds = oddsAway,
            drawOdds = oddsDraw,
            method = devigMethod
        )

        fun evalSide(
            label: String,
            modelProb: Double,
            fairProb: Double,
            decimalOdds: Double
        ): SideValueEval {
            val safeProb = if (modelProb.isFinite()) modelProb.coerceIn(0.0, 1.0) else 0.0
            val safeOdds = if (decimalOdds.isFinite() && decimalOdds > 1.0) decimalOdds else 1.0
            val ev = if (safeOdds > 1.0) safeProb * safeOdds - 1.0 else -1.0
            val edgePctPoints = (safeProb - fairProb) * 100.0
            val stakeFraction = fractionalKelly(
                modelProb = safeProb,
                decimalOdds = safeOdds,
                fraction = kellyFraction,
                maxCap = maxStakePct
            )
            val isGoodValue = safeOdds > 1.0 &&
                ev >= minEvThreshold &&
                safeProb >= minProbFloor

            return SideValueEval(
                label = label,
                modelProb = safeProb,
                fairMarketProb = fairProb,
                decimalOdds = safeOdds,
                expectedValue = ev,
                edgePctPoints = edgePctPoints,
                suggestedStakeFraction = stakeFraction,
                isGoodValue = isGoodValue
            )
        }

        val results = mutableListOf<SideValueEval>()
        results += evalSide(homeLabel, probHome, devig.fairHome, oddsHome)
        if (oddsDraw != null && oddsDraw.isFinite() && oddsDraw > 1.0 && probDraw > 0.0) {
            results += evalSide("Draw", probDraw, devig.fairDraw, oddsDraw)
        }
        results += evalSide(awayLabel, probAway, devig.fairAway, oddsAway)
        return results
    }

    /**
     * Formats decimal odds into the requested [OddsDisplayFormat] without unsafe `%` format specifiers.
     */
    fun formatOdds(decimalOdds: Double, format: OddsDisplayFormat): String {
        if (!decimalOdds.isFinite() || decimalOdds <= 1.0) return "--"

        return when (format) {
            OddsDisplayFormat.DECIMAL -> String.format(Locale.US, "%.2f", decimalOdds)
            OddsDisplayFormat.AMERICAN -> {
                if (decimalOdds >= 2.0) {
                    val american = ((decimalOdds - 1.0) * 100.0).roundToInt()
                    "+$american"
                } else {
                    val american = (-100.0 / (decimalOdds - 1.0)).roundToInt()
                    american.toString()
                }
            }
            OddsDisplayFormat.FRACTIONAL -> formatFractionalOdds(decimalOdds - 1.0)
            OddsDisplayFormat.PERCENT -> {
                val impliedPct = 100.0 / decimalOdds
                val formatted = String.format(Locale.US, "%.1f", impliedPct)
                "$formatted%"
            }
        }
    }

    /**
     * Calculates combined parlay odds, win probability, payout, profit, and estimated vig.
     *
     * Each leg pair is `(decimalOdds, winProbability)`. Pairs with `(winProbability, decimalOdds)`
     * are also handled safely when one value is in `(0, 1]` and the other is `> 1`.
     */
    fun calculateParlay(
        legs: List<Pair<Double, Double>>,
        stake: Double,
        sameGameWarning: Boolean = false
    ): ParlayResult {
        val warning = if (sameGameWarning) {
            "Same-game picks can be linked, so your true chance of winning may differ from the combined estimate."
        } else {
            null
        }

        if (legs.isEmpty()) {
            return ParlayResult(
                combinedOdds = 1.0,
                combinedChancePct = 0,
                potentialPayout = 0.0,
                potentialProfit = 0.0,
                estimatedVigPct = 0.0,
                correlationWarning = warning
            )
        }

        val safeStake = if (stake.isFinite() && stake > 0.0) stake else 0.0
        var combinedOdds = 1.0
        var combinedProb = 1.0

        for ((first, second) in legs) {
            val (legOdds, legProb) = if (first > 1.0 && second <= 1.0) {
                first to second
            } else if (second > 1.0 && first <= 1.0) {
                second to first
            } else {
                first to second
            }

            if (!legOdds.isFinite() || legOdds <= 1.0) continue
            combinedOdds *= legOdds

            val resolvedProb = if (legProb.isFinite() && legProb > 0.0 && legProb <= 1.0) {
                legProb
            } else {
                1.0 / legOdds
            }
            combinedProb *= resolvedProb
        }

        val combinedChancePct = (combinedProb * 100.0).roundToInt().coerceIn(0, 100)
        val potentialPayout = safeStake * combinedOdds
        val potentialProfit = potentialPayout - safeStake
        val estimatedVigPct = ((1.0 - combinedProb * combinedOdds).coerceAtLeast(0.0)) * 100.0

        return ParlayResult(
            combinedOdds = combinedOdds,
            combinedChancePct = combinedChancePct,
            potentialPayout = potentialPayout,
            potentialProfit = potentialProfit,
            estimatedVigPct = estimatedVigPct,
            correlationWarning = warning
        )
    }

    /**
     * Detects two-way arbitrage and computes equalized-payout stakes and guaranteed return (EN-05).
     */
    fun calculateArbitrage(
        oddsA: Double,
        oddsB: Double,
        totalStake: Double
    ): ArbitrageResult {
        if (!oddsA.isFinite() || !oddsB.isFinite() || !totalStake.isFinite() ||
            oddsA <= 1.0 || oddsB <= 1.0 || totalStake <= 0.0
        ) {
            return ArbitrageResult(
                isArbitrage = false,
                profitPct = 0.0,
                stakeA = 0.0,
                stakeB = 0.0,
                guaranteedPayout = 0.0,
                guaranteedProfit = 0.0
            )
        }

        val invA = 1.0 / oddsA
        val invB = 1.0 / oddsB
        val invSum = invA + invB

        val stakeA = totalStake * (invA / invSum)
        val stakeB = totalStake * (invB / invSum)
        val guaranteedPayout = totalStake / invSum
        val guaranteedProfit = guaranteedPayout - totalStake
        val profitPct = ((1.0 / invSum) - 1.0) * 100.0
        val isArbitrage = invSum < 1.0 - EPSILON

        return ArbitrageResult(
            isArbitrage = isArbitrage,
            profitPct = profitPct,
            stakeA = stakeA,
            stakeB = stakeB,
            guaranteedPayout = guaranteedPayout,
            guaranteedProfit = guaranteedProfit
        )
    }

    /**
     * Evaluates whether a pick record beats random chance using a one-sided binomial z-test (TR-01).
     */
    fun computeSignificance(
        wins: Int,
        total: Int,
        expectedWinRate: Double = 0.50
    ): SignificanceResult {
        if (total <= 0 || wins < 0 || wins > total ||
            !expectedWinRate.isFinite() || expectedWinRate <= 0.0 || expectedWinRate >= 1.0
        ) {
            return SignificanceResult(
                zScore = 0.0,
                pValue = 1.0,
                beatsRandomChance = false,
                plainLabel = "Not enough settled picks yet"
            )
        }

        val observed = wins.toDouble() / total.toDouble()
        val stdError = sqrt(expectedWinRate * (1.0 - expectedWinRate) / total.toDouble())
        val zScore = if (stdError > EPSILON) (observed - expectedWinRate) / stdError else 0.0
        val pValue = (1.0 - normalCdf(zScore)).coerceIn(0.0, 1.0)
        val beatsRandomChance = total >= 10 && zScore > 0.0 && pValue < 0.05

        val plainLabel = when {
            total < 10 -> "Small sample — keep tracking picks"
            zScore > 0.0 && pValue < 0.01 -> "Strong edge — very unlikely to be luck"
            zScore > 0.0 && pValue < 0.05 -> "Likely real skill — beats random chance"
            zScore > 0.0 && pValue < 0.15 -> "Promising trend — needs more picks"
            zScore >= -1.0 -> "In line with random chance"
            else -> "Below expected win rate"
        }

        return SignificanceResult(
            zScore = zScore,
            pValue = pValue,
            beatsRandomChance = beatsRandomChance,
            plainLabel = plainLabel
        )
    }

    /**
     * Computes Closing Line Value (CLV) percentage from taken vs. closing decimal odds.
     * Positive values indicate beating the closing line.
     */
    fun computeClvPct(takenOdds: Double, closingOdds: Double): Double {
        if (!takenOdds.isFinite() || !closingOdds.isFinite() ||
            takenOdds <= 1.0 || closingOdds <= 1.0
        ) {
            return 0.0
        }
        return ((takenOdds / closingOdds) - 1.0) * 100.0
    }

    /**
     * Returns the lowercase 64-character SHA-256 hex digest of [input] encoded as UTF-8.
     */
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

    /**
     * Shin (1991/1993) insider-trading de-vig solver via bisection on insider share z in [0, 1).
     */
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
            val sum = shinProbs(mid).sum()
            if (sum > 1.0) {
                lo = mid
            } else {
                hi = mid
            }
        }

        return normalize(shinProbs(0.5 * (lo + hi)))
    }

    /**
     * Power (logarithmic) de-vig solver finding k > 1 such that sum(q_i^k) = 1.
     */
    private fun solvePower(rawProbs: DoubleArray): DoubleArray {
        var lo = 1.0
        var hi = 2.0

        fun powerSum(k: Double): Double {
            var sum = 0.0
            for (q in rawProbs) {
                sum += q.pow(k)
            }
            return sum
        }

        while (powerSum(hi) > 1.0 && hi < 128.0) {
            hi *= 2.0
        }

        repeat(SOLVER_ITERATIONS) {
            val mid = 0.5 * (lo + hi)
            if (powerSum(mid) > 1.0) {
                lo = mid
            } else {
                hi = mid
            }
        }

        val k = 0.5 * (lo + hi)
        return normalize(DoubleArray(rawProbs.size) { i -> rawProbs[i].pow(k) })
    }

    /**
     * Additive (equal-margin) de-vig solver subtracting (S - 1) / n from each implied probability.
     */
    private fun solveAdditive(rawProbs: DoubleArray, overround: Double): DoubleArray {
        val marginPerOutcome = (overround - 1.0) / rawProbs.size.toDouble()
        val adjusted = DoubleArray(rawProbs.size) { i ->
            (rawProbs[i] - marginPerOutcome).coerceAtLeast(MIN_PROB_FLOOR)
        }
        return normalize(adjusted)
    }

    /**
     * Odds-Ratio (Cheung) de-vig solver finding c > 1 such that sum(q_i / (q_i + c * (1 - q_i))) = 1.
     */
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
            if (ratioProbs(mid).sum() > 1.0) {
                lo = mid
            } else {
                hi = mid
            }
        }

        return normalize(ratioProbs(0.5 * (lo + hi)))
    }

    // -------------------------------------------------------------------------
    // Internal Math & Formatting Helpers
    // -------------------------------------------------------------------------

    private fun formatFractionalOdds(netOdds: Double): String {
        if (!netOdds.isFinite() || netOdds <= 0.0) return "--"

        var bestNum = 1
        var bestDen = 1
        var bestErr = Double.POSITIVE_INFINITY

        for (den in 1..MAX_FRACTIONAL_DENOMINATOR) {
            val num = (netOdds * den).roundToInt().coerceAtLeast(1)
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

        val scaledNum = (netOdds * 100.0).roundToInt().coerceAtLeast(1)
        val scaledDen = 100
        val g = gcd(scaledNum, scaledDen)
        return "${scaledNum / g}/${scaledDen / g}"
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val r = x % y
            x = y
            y = r
        }
        return if (x == 0) 1 else x
    }

    private fun normalCdf(z: Double): Double {
        return 0.5 * (1.0 + erf(z / sqrt(2.0)))
    }

    /**
     * Abramowitz & Stegun 7.1.26 approximation of the error function (max error < 1.5e-7).
     */
    private fun erf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        val y = 1.0 - poly * exp(-ax * ax)
        return sign * y
    }
}
