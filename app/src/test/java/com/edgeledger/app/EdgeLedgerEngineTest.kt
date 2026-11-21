package com.edgeledger.app

import androidx.compose.ui.graphics.Color
import com.edgeledger.app.data.SeedData
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.data.UserApiKeys
import com.edgeledger.app.engine.DevigMethod
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PlayerPropsEngine
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.engine.StakingMode
import com.edgeledger.app.model.OddsDisplayFormat
import com.edgeledger.app.model.Sport
import com.edgeledger.app.theme.SurfaceStyle
import com.edgeledger.app.theme.ThemeCodec
import com.edgeledger.app.theme.ThemeConfigV1
import com.edgeledger.app.theme.ThemeMode
import com.edgeledger.app.theme.ThemePresets
import com.edgeledger.app.theme.autoFixContrastHex
import com.edgeledger.app.theme.computeContrastRatio
import com.edgeledger.app.theme.parseHexColor
import com.edgeledger.app.theme.resolveTokens
import com.edgeledger.app.ui.components.formatStatValue
import com.edgeledger.app.ui.components.shortTeamName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLedgerEngineTest {

    @Test
    fun allFiveDevigMethodsRemoveOverroundCleanly() {
        for (method in DevigMethod.entries) {
            val res = OddsMath.deVig(1.90, 1.90, null, method)
            assertEquals(0.50, res.fairHome, 0.01)
            assertEquals(0.50, res.fairAway, 0.01)
            assertTrue(res.holdPct > 4.0)
        }
    }

    @Test
    fun fractionalKellyAndStakingModesRespectCaps() {
        val stake = OddsMath.fractionalKelly(0.60, 2.00, fraction = 0.25, maxCap = 0.05)
        assertEquals(0.05, stake, 0.0001)
        val flat = OddsMath.recommendedStake(1000.0, 0.60, 2.00, StakingMode.FLAT, flatUnitPct = 0.02)
        assertEquals(20.0, flat, 0.01)
    }

    @Test
    fun arbitrageAndSignificanceMathWork() {
        val arb = OddsMath.calculateArbitrage(2.10, 2.10, 100.0)
        assertTrue(arb.isArbitrage)
        assertTrue(arb.guaranteedProfit > 0.0)

        val sig = OddsMath.computeSignificance(65, 100, 0.50)
        assertTrue(sig.beatsRandomChance)
        assertTrue(sig.zScore > 2.0)
    }

    @Test
    fun oddsFormattingSupportsAllFourModes() {
        assertEquals("2.00", OddsMath.formatOdds(2.0, OddsDisplayFormat.DECIMAL))
        assertEquals("+100", OddsMath.formatOdds(2.0, OddsDisplayFormat.AMERICAN))
        assertEquals("1/1", OddsMath.formatOdds(2.0, OddsDisplayFormat.FRACTIONAL))
        assertEquals("50%", OddsMath.formatOdds(2.0, OddsDisplayFormat.PERCENT))
    }

    @Test
    fun playerPropsAndLineMovementGenerateForAllSports() {
        for (sp in listOf(Sport.FOOTBALL, Sport.NBA, Sport.NFL, Sport.NHL, Sport.MLB, Sport.TENNIS, Sport.CRICKET)) {
            val props = PlayerPropsEngine.generatePlayerProps(sp, "Arsenal", "Chelsea", 1.8, 1.2, 78, 65)
            assertTrue("Expected props for $sp", props.isNotEmpty())
            props.forEach { p ->
                assertTrue(p.overChancePct in 1..99)
                assertTrue(p.rangeHigh >= p.rangeLow)
            }
        }
        val series = PlayerPropsEngine.buildLineMovementSeries("fx-1", 0.58, 0.55)
        assertEquals(6, series.size)
        val (lo, hi) = PlayerPropsEngine.computeUncertaintyInterval(0.60, 3)
        assertTrue(hi - lo >= 10)
    }

    @Test
    fun statFormattingWithPercentSuffixNeverCrashes() {
        assertEquals("55%", formatStatValue(55.0, 0, "%"))
        assertEquals("12.4%", formatStatValue(12.4, 1, "%"))
        assertEquals("2.1", formatStatValue(2.1, 1, ""))
    }

    @Test
    fun shortTeamNamesAndLeagueLabelsAreClean() {
        assertEquals("C. Palace", shortTeamName("Crystal Palace"))
        assertEquals("A. Villa", shortTeamName("Aston Villa"))
        assertEquals("Premier League", SyncRepository.readableLeagueName("E0", Sport.FOOTBALL))
        assertEquals("La Liga", SyncRepository.readableLeagueName("SP1", Sport.FOOTBALL))
    }

    @Test
    fun userApiKeysRedactsSecretsInToString() {
        val keys = UserApiKeys(theOddsApiKey = "super-secret-12345")
        assertTrue(keys.hasAnyApiKey())
        assertFalse(keys.toString().contains("super-secret-12345"))
    }

    @Test
    fun footballAndUsSportsPredictionsAreCoherent() {
        val profiles = PredictionEngine.buildTeamProfiles(SeedData.buildSeedHistory(), SeedData.CLUB_ELO_ANCHOR)
        val h = profiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Arsenal")]
        val a = profiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Chelsea")]
        val m = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Chelsea", h, a)

        assertEquals(1.0, m.probHome + m.probDraw + m.probAway, 0.02)
        assertTrue(m.over05Pct >= m.over15Pct)
        assertTrue(m.topScorelines.size == 6)

        for (sp in listOf(Sport.NFL, Sport.NBA, Sport.NHL, Sport.MLB, Sport.CRICKET)) {
            val res = PredictionEngine.predictAllMarkets(sp, "Home", "Away", null, null)
            assertEquals(1.0, res.probHome + res.probAway, 0.02)
            assertTrue(res.topPicks.isNotEmpty())
        }
    }

    @Test
    fun themeSchemaV1MigrationContrastAutoFixAndTeamPalettesWork() {
        // Legacy v1 JSON with old COLORBLIND enum migrates cleanly to v2 DEUTERANOPIA
        val legacyV1 = """{"schemaVersion":1,"id":"legacy","name":"Old CB","mode":"COLORBLIND","accentHex":"#10B981"}"""
        val migrated = ThemeCodec.decodeOrNull(legacyV1)
        assertNotNull(migrated)
        assertEquals(2, migrated?.schemaVersion)
        assertEquals(ThemeMode.DEUTERANOPIA, migrated?.mode)

        val fixedHex = autoFixContrastHex("#111111", Color(0xFF000000))
        assertTrue(computeContrastRatio(parseHexColor(fixedHex), Color(0xFF000000)) >= 4.5f)

        assertTrue(ThemePresets.teamPalettes.size >= 8)
        for (mode in ThemeMode.entries) {
            val tokens = resolveTokens(ThemeConfigV1(mode = mode, surfaceStyle = SurfaceStyle.ELEVATED))
            assertTrue(tokens.textSurfaceContrast >= 7.0f)
        }
    }
}
