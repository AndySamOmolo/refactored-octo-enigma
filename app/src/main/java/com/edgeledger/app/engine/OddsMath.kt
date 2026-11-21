package com.edgeledger.app.engine

import com.edgeledger.app.model.OddsDisplayFormat
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DeVigResult(
    val overround: Double,
    val fairHome: Double,
    val fairDraw: Double?,
    val fairAway: Double,
)

data class ValueEvaluation(
    val side: String,
    val label: String,
    val modelProb: Double,
    val fairMarketProb: Double,
    val decimalOdds: Double,
    val expectedValue: Double,
    val suggestedStakeFraction: Double,
    val isGoodValue: Boolean,
)

data class ParlayResult(
    val combinedOdds: Double,
    val combinedChancePct: Int,
    val potentialPayout: Double,
    val potentialProfit: Double,
)

object OddsMath {
    fun formatOdds(decimalOdds: Double, format: OddsDisplayFormat): String {
        val d = max(1.01, if (decimalOdds.isFinite()) decimalOdds else 2.0)
        return when (format) {
            OddsDisplayFormat.DECIMAL -> String.format("%.2f", d)
            OddsDisplayFormat.PERCENT -> "${(100.0 / d).roundToInt()}%"
            OddsDisplayFormat.AMERICAN -> {
                if (d >= 2.0) "+${((d - 1.0) * 100.0).roundToInt()}"
                else "${(-100.0 / (d - 1.0)).roundToInt()}"
            }
            OddsDisplayFormat.FRACTIONAL -> {
                val net = d - 1.0
                val denoms = intArrayOf(1, 2, 4, 5, 8, 10, 20)
                var bestNum = max(1, net.roundToInt())
                var bestDen = 1
                var bestErr = Double.MAX_VALUE
                for (den in denoms) {
                    val num = max(1, (net * den).roundToInt())
                    val err = abs(num.toDouble() / den - net)
                    if (err < bestErr) {
                        bestErr = err
                        bestNum = num
                        bestDen = den
                    }
                }
                "$bestNum/$bestDen"
            }
        }
    }

    fun americanToDecimal(ml: Double): Double? {
        if (!ml.isFinite() || ml == 0.0) return null
        return if (ml > 0) 1.0 + ml / 100.0 else 1.0 + 100.0 / abs(ml)
    }

    /**
     * Removes bookmaker overround using Shin's method (preferred for favorites/longshots).
     */
    fun deVigShin(oddsHome: Double, oddsAway: Double, oddsDraw: Double? = null): DeVigResult {
        val invs = mutableListOf(1.0 / max(1.01, oddsHome))
        if (oddsDraw != null && oddsDraw > 1.01) invs.add(1.0 / oddsDraw)
        invs.add(1.0 / max(1.01, oddsAway))

        val sum = invs.sum()
        val fair = if (sum > 1.001) {
            var lo = 0.0
            var hi = 0.5
            repeat(24) {
                val z = (lo + hi) / 2.0
                val s = invs.sumOf { q ->
                    (sqrt(z * z + (4.0 * (1.0 - z) * q * q) / sum) - z) / (2.0 * (1.0 - z))
                }
                if (s > 1.0) lo = z else hi = z
            }
            val z = (lo + hi) / 2.0
            val raw = invs.map { q ->
                (sqrt(z * z + (4.0 * (1.0 - z) * q * q) / sum) - z) / (2.0 * (1.0 - z))
            }
            val norm = raw.sum().coerceAtLeast(1e-9)
            raw.map { it / norm }
        } else {
            invs.map { it / sum }
        }

        return if (oddsDraw != null && oddsDraw > 1.01) {
            DeVigResult(sum, fair[0], fair[1], fair[2])
        } else {
            DeVigResult(sum, fair[0], null, fair[1])
        }
    }

    fun expectedValue(modelProb: Double, decimalOdds: Double): Double {
        if (decimalOdds <= 1.01 || modelProb <= 0.0) return -1.0
        return modelProb * decimalOdds - 1.0
    }

    fun fractionalKelly(
        modelProb: Double,
        decimalOdds: Double,
        fraction: Double = 0.25,
        maxCap: Double = 0.05,
    ): Double {
        val b = decimalOdds - 1.0
        if (b <= 0.01 || modelProb <= 0.0 || modelProb >= 1.0) return 0.0
        val q = 1.0 - modelProb
        val full = (b * modelProb - q) / b
        if (full <= 0.0) return 0.0
        return min(maxCap, max(0.0, full * fraction))
    }

    fun evaluateSides(
        homeLabel: String,
        awayLabel: String,
        probHome: Double,
        probDraw: Double,
        probAway: Double,
        oddsHome: Double,
        oddsDraw: Double?,
        oddsAway: Double,
        kellyFraction: Double,
        maxStakePct: Double,
        minEdge: Double = 0.03,
    ): List<ValueEvaluation> {
        val devig = deVigShin(oddsHome, oddsAway, oddsDraw)
        val rows = mutableListOf(
            Triple("HOME", homeLabel, Triple(probHome, devig.fairHome, oddsHome)),
        )
        if (oddsDraw != null && oddsDraw > 1.01 && probDraw > 0.0) {
            rows.add(Triple("DRAW", "Draw", Triple(probDraw, devig.fairDraw ?: (1.0 / oddsDraw), oddsDraw)))
        }
        rows.add(Triple("AWAY", awayLabel, Triple(probAway, devig.fairAway, oddsAway)))

        return rows.map { (side, label, nums) ->
            val (p, fair, odds) = nums
            val ev = expectedValue(p, odds)
            val stake = fractionalKelly(p, odds, kellyFraction, maxStakePct)
            ValueEvaluation(
                side = side,
                label = label,
                modelProb = p,
                fairMarketProb = fair,
                decimalOdds = odds,
                expectedValue = ev,
                suggestedStakeFraction = stake,
                isGoodValue = ev >= minEdge && stake > 0.0,
            )
        }
    }

    fun calculateParlay(legs: List<Pair<Double, Double>>, stake: Double): ParlayResult {
        val combinedOdds = legs.fold(1.0) { acc, (odds, _) -> acc * max(1.01, odds) }
        val combinedProb = legs.fold(1.0) { acc, (_, prob) -> acc * prob.coerceIn(0.01, 0.99) }
        val payout = stake * combinedOdds
        return ParlayResult(
            combinedOdds = (combinedOdds * 100.0).roundToInt() / 100.0,
            combinedChancePct = (combinedProb * 100.0).roundToInt().coerceIn(1, 99),
            potentialPayout = (payout * 100.0).roundToInt() / 100.0,
            potentialProfit = ((payout - stake) * 100.0).roundToInt() / 100.0,
        )
    }

    /**
     * Closing Line Value in percentage points: positive means you beat the closing market.
     */
    fun closingLineValuePct(takenOdds: Double, closingOdds: Double): Double {
        if (takenOdds <= 1.01 || closingOdds <= 1.01) return 0.0
        return ((takenOdds / closingOdds) - 1.0) * 100.0
    }

    /**
     * SHA-256 tamper-evident hash for locking pre-match model predictions (TR-02).
     */
    fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
