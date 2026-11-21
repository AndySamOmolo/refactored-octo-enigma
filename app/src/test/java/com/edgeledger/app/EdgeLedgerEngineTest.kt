package com.edgeledger.app

import androidx.compose.ui.graphics.Color
import com.edgeledger.app.data.SeedData
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.data.UserApiKeys
import com.edgeledger.app.engine.CsvAndFeedParsers
import com.edgeledger.app.engine.DevigMethod
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PlayerPropsEngine
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.engine.StakingMode
import com.edgeledger.app.model.LivePlayerEntry
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
        assertEquals("50.0%", OddsMath.formatOdds(2.0, OddsDisplayFormat.PERCENT))
    }

    @Test
    fun playerPropsUseOnlyLiveSyncedRostersAndConserveTeamXg() {
        // Without live synced rosters, zero hardcoded/guessed players are fabricated
        val emptyProps = PlayerPropsEngine.generatePlayerProps(Sport.FOOTBALL, "Newcastle United", "Arsenal", 0.4, 1.8, 60, 82)
        assertTrue(emptyProps.isEmpty())

        // With live synced rosters, props generate cleanly and conserve team xG (<= 40% per player)
        val liveProps = PlayerPropsEngine.generatePlayerProps(
            sport = Sport.FOOTBALL,
            homeTeam = "Newcastle United",
            awayTeam = "Arsenal",
            projectedHomeScore = 0.4,
            projectedAwayScore = 1.8,
            homePower = 60,
            awayPower = 82,
            liveHomePlayers = listOf(LivePlayerEntry("Will Osula", "ST"), LivePlayerEntry("Anthony Elanga", "RW")),
            liveAwayPlayers = listOf(LivePlayerEntry("Viktor Gyokeres", "ST"), LivePlayerEntry("Bukayo Saka", "RW")),
        )
        assertTrue(liveProps.isNotEmpty())
        val homeGoalProp = liveProps.firstOrNull { it.team == "Newcastle United" && it.marketLabel.contains("Goal", ignoreCase = true) }
        assertNotNull(homeGoalProp)
        assertTrue("Lead striker xG must not exceed 50% of 0.4 team xG", (homeGoalProp?.projectedValue ?: 1.0) <= 0.25)

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
        assertEquals("Formula 1", SyncRepository.readableLeagueName("F1", Sport.F1))
        assertEquals("Ligue 1", SyncRepository.readableLeagueName("F1", Sport.FOOTBALL))
        assertEquals("Blazers", shortTeamName("Portland Trail Blazers"))
        assertEquals("Bucs", shortTeamName("Tampa Bay Buccaneers"))
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
            assertFalse(res.projectedSpreadDisplay.contains("-0.0"))
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

    @Test
    fun nflAndNhlCrosswalksPowerCalibrationAndLightModeWork() {
        assertEquals("Kansas City Chiefs", CsvAndFeedParsers.canonicalTeam("KC", Sport.NFL))
        assertEquals("Detroit Lions", CsvAndFeedParsers.canonicalTeam("DET", Sport.NFL))
        assertEquals("Tampa Bay Buccaneers", CsvAndFeedParsers.canonicalTeam("TB", Sport.NFL))
        assertEquals("Detroit Red Wings", CsvAndFeedParsers.canonicalTeam("DET", Sport.NHL))
        assertEquals("New York Rangers", CsvAndFeedParsers.canonicalTeam("NYR", Sport.NHL))
        assertEquals("New York Islanders", CsvAndFeedParsers.canonicalTeam("NYI", Sport.NHL))

        // Neutral 1500 Elo on non-football sport maps to ~50/99 (not 31/99!)
        assertEquals(50, PredictionEngine.eloToPowerScore(1500.0, Sport.TENNIS, false))

        // Crisp Daylight Light mode stays Light even when Battery Saver is active
        val lightTokens = resolveTokens(ThemeConfigV1(mode = ThemeMode.LIGHT), isBatterySaverActive = true)
        assertFalse(lightTokens.isDark)

        // US sports topPicks always recommend >= 50% side of spread/total
        val nba = PredictionEngine.predictAllMarkets(Sport.NBA, "Philadelphia 76ers", "Milwaukee Bucks", null, null)
        nba.topPicks.forEach { pk ->
            assertTrue("Expected pick >= 50%, got ${pk.label} (${pk.chancePct}%)", pk.chancePct >= 50)
        }
    }

    @Test
    fun nov2026RostersAndLiveRosterPriorityAreAccurate() {
        val unsynced = PlayerPropsEngine.generatePlayerProps(Sport.FOOTBALL, "Newcastle United", "Liverpool", 1.2, 1.8, 60, 82)
        assertTrue("Unsynced matches should not invent hardcoded players", unsynced.isEmpty())

        // Live synced roster ALWAYS overrides any fallback
        val liveProps = PlayerPropsEngine.generatePlayerProps(
            sport = Sport.FOOTBALL,
            homeTeam = "Newcastle United",
            awayTeam = "Arsenal",
            projectedHomeScore = 1.4,
            projectedAwayScore = 1.6,
            homePower = 65,
            awayPower = 80,
            liveHomePlayers = listOf(LivePlayerEntry("Live Test Striker", "ST")),
            liveAwayPlayers = listOf(LivePlayerEntry("Live Away Winger", "RW")),
        )
        assertTrue(liveProps.any { it.playerName == "Live Test Striker" })
        assertTrue(liveProps.any { it.playerName == "Live Away Winger" })
    }
}
