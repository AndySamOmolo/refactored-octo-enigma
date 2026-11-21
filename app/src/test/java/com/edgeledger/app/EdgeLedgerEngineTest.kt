package com.edgeledger.app

import androidx.compose.ui.graphics.Color
import com.edgeledger.app.data.SeedData
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.model.OddsDisplayFormat
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.ThemeCodec
import com.edgeledger.app.theme.ThemeConfigV1
import com.edgeledger.app.theme.ThemeMode
import com.edgeledger.app.theme.autoFixContrastColor
import com.edgeledger.app.theme.computeContrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLedgerEngineTest {

    @Test
    fun shinDevigRemovesOverroundCleanly() {
        val res = OddsMath.deVigShin(1.90, 1.90)
        assertEquals(0.50, res.fairHome, 0.005)
        assertEquals(0.50, res.fairAway, 0.005)
        assertTrue(res.overround > 1.04)
    }

    @Test
    fun fractionalKellyRespectsMaxCap() {
        val stake = OddsMath.fractionalKelly(0.60, 2.00, fraction = 0.25, maxCap = 0.05)
        assertEquals(0.05, stake, 0.0001)
    }

    @Test
    fun oddsFormattingSupportsAllFourModes() {
        assertEquals("2.00", OddsMath.formatOdds(2.0, OddsDisplayFormat.DECIMAL))
        assertEquals("+100", OddsMath.formatOdds(2.0, OddsDisplayFormat.AMERICAN))
        assertEquals("1/1", OddsMath.formatOdds(2.0, OddsDisplayFormat.FRACTIONAL))
        assertEquals("50%", OddsMath.formatOdds(2.0, OddsDisplayFormat.PERCENT))
    }

    @Test
    fun parlayCalculatorCombinesLegsCorrectly() {
        val p = OddsMath.calculateParlay(listOf(2.0 to 0.5, 2.0 to 0.5), 10.0)
        assertEquals(4.0, p.combinedOdds, 0.01)
        assertEquals(40.0, p.potentialPayout, 0.01)
        assertEquals(25, p.combinedChancePct)
    }

    @Test
    fun footballMultiMarketProbabilitiesAreCoherent() {
        val profiles = PredictionEngine.buildTeamProfiles(SeedData.buildSeedHistory(), SeedData.CLUB_ELO_ANCHOR)
        val h = profiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Arsenal")]
        val a = profiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Chelsea")]
        val m = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Chelsea", h, a)

        assertEquals(1.0, m.probHome + m.probDraw + m.probAway, 0.02)
        assertTrue(m.over05Pct >= m.over15Pct)
        assertTrue(m.over15Pct >= m.over25Pct)
        assertTrue(m.over25Pct >= m.over35Pct)
        assertTrue(m.topScorelines.size == 6)
        assertTrue(m.topPicks.isNotEmpty())
    }

    @Test
    fun themeJsonRoundTripAndContrastAutoFix() {
        val orig = ThemeConfigV1(id = "custom", name = "My AMOLED", mode = ThemeMode.AMOLED, accentHex = "#10B981")
        val encoded = ThemeCodec.encode(orig)
        val decoded = ThemeCodec.decodeOrNull(encoded)
        assertNotNull(decoded)
        assertEquals(orig.accentHex, decoded?.accentHex)

        val fixed = autoFixContrastColor(Color(0xFF111111), Color(0xFF000000))
        assertTrue(computeContrastRatio(fixed, Color(0xFF000000)) >= 4.5)
    }
}
