package com.edgeledger.app

import androidx.compose.ui.graphics.Color
import com.edgeledger.app.data.OddsHistoryEntity
import com.edgeledger.app.data.OptionalOddsAndDataClient
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.data.UserApiKeys
import com.edgeledger.app.engine.CsvAndFeedParsers
import com.edgeledger.app.engine.DevigMethod
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PlayerPropsEngine
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.engine.StakingMode
import com.edgeledger.app.engine.StatsMath
import com.edgeledger.app.model.HistoricalMatch
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
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLedgerEngineTest {

    @Test
    fun allFiveDevigMethodsRemoveOverroundCleanly() {
        for (method in DevigMethod.entries) {
            val res2 = OddsMath.deVig(1.90, 1.90, null, method)
            assertEquals(0.50, res2.fairHome, 0.01)
            assertEquals(0.50, res2.fairAway, 0.01)
            assertTrue(res2.holdPct > 4.0)

            val res3 = OddsMath.deVig(2.10, 3.40, 3.60, method)
            assertEquals(1.0, res3.fairHome + res3.fairDraw + res3.fairAway, 0.005)
        }
    }

    @Test
    fun statsMathExactPoissonNormalAndBinomialAreAccurate() {
        val p12 = StatsMath.poissonPmf(12, 10.5)
        assertTrue(p12 in 0.05..0.15)
        val over9 = StatsMath.poissonOverProb(10.5, 9.5)
        assertTrue(over9 in 0.50..0.70)

        val q15 = StatsMath.poissonQuantile(2.5, 0.15)
        val q85 = StatsMath.poissonQuantile(2.5, 0.85)
        assertTrue(q15 <= q85)

        assertEquals(0.5, StatsMath.normalCdf(0.0), 0.001)
        assertEquals(0.0, StatsMath.normalInvCdf(0.5), 0.01)
        assertEquals(1.645, StatsMath.normalInvCdf(0.95), 0.02)

        assertNull(OddsMath.americanToDecimalOrNull(0.0))
        assertNull(OddsMath.americanToDecimalOrNull(50.0))
        assertEquals(2.0, OddsMath.americanToDecimalOrNull(100.0)!!, 0.001)
        assertEquals(1.5, OddsMath.americanToDecimalOrNull(-200.0)!!, 0.001)
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

        val emptySig = OddsMath.computeSignificance(0, 0, 0.50)
        assertFalse(emptySig.beatsRandomChance)
    }

    @Test
    fun oddsFormattingSupportsAllFourModes() {
        assertEquals("2.00", OddsMath.formatOdds(2.0, OddsDisplayFormat.DECIMAL))
        assertEquals("+100", OddsMath.formatOdds(2.0, OddsDisplayFormat.AMERICAN))
        assertEquals("1/1", OddsMath.formatOdds(2.0, OddsDisplayFormat.FRACTIONAL))
        assertEquals("50.0%", OddsMath.formatOdds(2.0, OddsDisplayFormat.PERCENT))
    }

    @Test
    fun playerPropsUseOnlyLiveSyncedRostersExcludesGoalkeepersAndConservesXg() {
        val emptyProps = PlayerPropsEngine.generatePlayerProps(Sport.FOOTBALL, "Newcastle United", "Arsenal", 0.4, 1.8, 60, 82)
        assertTrue(emptyProps.isEmpty())

        val liveProps = PlayerPropsEngine.generatePlayerProps(
            sport = Sport.FOOTBALL,
            homeTeam = "Newcastle United",
            awayTeam = "Arsenal",
            projectedHomeScore = 0.4,
            projectedAwayScore = 1.8,
            homePower = 60,
            awayPower = 82,
            liveHomePlayers = listOf(
                LivePlayerEntry("Nick Pope", "GK"),
                LivePlayerEntry("Will Osula", "ST"),
                LivePlayerEntry("Anthony Elanga", "RW"),
            ),
            liveAwayPlayers = listOf(
                LivePlayerEntry("David Raya", "G"),
                LivePlayerEntry("Viktor Gyokeres", "ST"),
                LivePlayerEntry("Bukayo Saka", "RW"),
            ),
            fixtureId = "fx_123",
        )
        assertTrue(liveProps.isNotEmpty())
        assertFalse(liveProps.any { it.playerName == "Nick Pope" || it.playerName == "David Raya" })
        val homeGoalProp = liveProps.firstOrNull { it.team == "Newcastle United" && it.marketLabel.contains("Goal", ignoreCase = true) }
        assertNotNull(homeGoalProp)
        assertTrue("Lead striker xG must not exceed 50% of 0.4 team xG", (homeGoalProp?.projectedValue ?: 1.0) <= 0.25)

        val (lo, hi) = PlayerPropsEngine.computeUncertaintyInterval(0.60, 3)
        assertTrue(hi - lo >= 10)
    }

    @Test
    fun realLineMovementRequiresTwoSnapshotsAndDetectsSteam() {
        val single = listOf(
            OddsHistoryEntity(
                fixtureId = "fx1",
                recordedAtEpochMs = 1_000_000L,
                label = "Open",
                homeProbPct = 50.0,
                sharpHomeProbPct = 50.0,
                retailHomeProbPct = 50.0,
            ),
        )
        assertTrue(PlayerPropsEngine.buildRealLineMovementSeries(single).isEmpty())

        val twoWithSteam = listOf(
            OddsHistoryEntity(
                fixtureId = "fx1",
                recordedAtEpochMs = 1_000_000L,
                label = "Open",
                homeProbPct = 50.0,
                sharpHomeProbPct = 50.0,
                retailHomeProbPct = 50.0,
            ),
            OddsHistoryEntity(
                fixtureId = "fx1",
                recordedAtEpochMs = 2_000_000L,
                label = "Now",
                homeProbPct = 55.2,
                sharpHomeProbPct = 55.2,
                retailHomeProbPct = 51.5,
            ),
        )
        val series = PlayerPropsEngine.buildRealLineMovementSeries(twoWithSteam)
        assertEquals(2, series.size)
        assertTrue(series.last().isSteamMove)
    }

    @Test
    fun csvParserHandlesRfc4180QuotesAndRejectsMalformedRows() {
        val cols = CsvAndFeedParsers.parseCsvLine("""15/02/2026,"Team ""A"", FC",Away United,2,1""")
        assertEquals(5, cols.size)
        assertEquals("""Team "A", FC""", cols[1])

        val csv = """
            Date,HomeTeam,AwayTeam,FTHG,FTAG,PSH,PSD,PSA
            31/02/2026,Arsenal,Chelsea,2,1,1.90,3.50,4.20
            14/02/2026,Arsenal,Chelsea,-1,1,1.90,3.50,4.20
            15/02/2026,Wolves,Man City,1,2,5.00,4.00,1.65
        """.trimIndent()
        val matches = CsvAndFeedParsers.parseFootballDataCsv(csv, "E0")
        assertEquals(1, matches.size)
        assertEquals("Wolverhampton Wanderers", matches[0].homeTeam)
        assertEquals("Manchester City", matches[0].awayTeam)
    }

    @Test
    fun statFormattingAndShortTeamNamesAreSafe() {
        assertEquals("55%", formatStatValue(55.0, 0, "%"))
        assertEquals("12.4%", formatStatValue(12.4, 1, "%"))
        assertEquals("—", formatStatValue(Double.NaN, 1, ""))
        assertEquals("C. Palace", shortTeamName("Crystal Palace"))
        assertEquals("A. Villa", shortTeamName("Aston Villa"))
        assertEquals("Premier League", SyncRepository.readableLeagueName("E0", Sport.FOOTBALL))
        assertEquals("Formula 1", SyncRepository.readableLeagueName("F1", Sport.F1))
        assertEquals("Ligue 1", SyncRepository.readableLeagueName("F1", Sport.FOOTBALL))
    }

    @Test
    fun userApiKeysAndClientSecurityFiltersWork() {
        val keys = UserApiKeys(theOddsApiKey = "super-secret-12345")
        assertTrue(keys.hasAnyApiKey())
        assertFalse(keys.toString().contains("super-secret-12345"))

        val client = OptionalOddsAndDataClient(OkHttpClient())
        assertTrue(client.isSportsMarketQuestion("Will Arsenal beat Chelsea in the Premier League?"))
        assertFalse(client.isSportsMarketQuestion("Will Bitcoin hit $150k before the election?"))
        val redacted = client.sanitizeError(RuntimeException("Failed calling https://api.the-odds-api.com/v4/odds?apiKey=MY_SECRET_TOKEN_999&regions=eu"))
        assertFalse(redacted.contains("MY_SECRET_TOKEN_999"))
        assertTrue(redacted.contains("REDACTED"))
    }

    @Test
    fun footballAndUsSportsPredictionsAndBacktestAreCoherent() {
        val sampleHistory = (1..20).map { idx ->
            HistoricalMatch(
                id = "test_fb_$idx",
                sport = Sport.FOOTBALL,
                leagueId = "E0",
                dateIso = "2026-01-${idx.toString().padStart(2, '0')}",
                homeTeam = if (idx % 2 == 0) "Arsenal" else "Chelsea",
                awayTeam = if (idx % 2 == 0) "Chelsea" else "Arsenal",
                homeScore = if (idx % 3 == 0) 1 else 2,
                awayScore = 1,
                oddsHome = 2.05,
                oddsDraw = 3.40,
                oddsAway = 3.70,
            )
        }
        val profiles = PredictionEngine.buildTeamProfiles(sampleHistory, mapOf("Arsenal" to 1920.0, "Chelsea" to 1790.0))
        val h = profiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Arsenal")]
        val a = profiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Chelsea")]
        val m = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Chelsea", h, a)

        assertEquals(1.0, m.probHome + m.probDraw + m.probAway, 0.02)
        assertTrue(m.over05Pct >= m.over15Pct)
        assertTrue(m.topScorelines.size == 6)

        val track = PredictionEngine.runWalkForwardBacktest(Sport.FOOTBALL, "E0", "Premier League", sampleHistory)
        assertTrue(track.gamesTested > 0)
        assertTrue(track.hasGradedOdds)

        for (sp in listOf(Sport.NFL, Sport.NBA, Sport.NHL, Sport.MLB, Sport.CRICKET)) {
            val res = PredictionEngine.predictAllMarkets(sp, "Home", "Away", null, null)
            assertEquals(1.0, res.probHome + res.probAway, 0.02)
            assertTrue(res.topPicks.isNotEmpty())
            assertFalse(res.projectedSpreadDisplay.contains("-0.0"))
        }
    }

    @Test
    fun themeSchemaV1MigrationContrastAutoFixAndTeamPalettesWork() {
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

        assertEquals(50, PredictionEngine.eloToPowerScore(1500.0, Sport.TENNIS, false))

        val lightTokens = resolveTokens(ThemeConfigV1(mode = ThemeMode.LIGHT), isBatterySaverActive = true)
        assertFalse(lightTokens.isDark)

        val nba = PredictionEngine.predictAllMarkets(Sport.NBA, "Philadelphia 76ers", "Milwaukee Bucks", null, null)
        nba.topPicks.forEach { pk ->
            assertTrue("Expected pick >= 50%, got ${pk.label} (${pk.chancePct}%)", pk.chancePct >= 50)
        }
    }
}
