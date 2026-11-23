package com.edgeledger.app

import androidx.compose.ui.graphics.Color
import com.edgeledger.app.data.OddsHistoryEntity
import com.edgeledger.app.data.OptionalOddsAndDataClient
import com.edgeledger.app.ui.screens.GamesFilterSort
import com.edgeledger.app.ui.screens.GameSortMode
import com.edgeledger.app.data.ApiQuotaEntity
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
import com.edgeledger.app.model.StadiumWeather
import com.edgeledger.app.theme.SurfaceStyle
import com.edgeledger.app.theme.ThemeCodec
import com.edgeledger.app.theme.ThemeConfigV1
import com.edgeledger.app.theme.ThemeMode
import com.edgeledger.app.theme.ThemePresets
import com.edgeledger.app.theme.autoFixContrastHex
import com.edgeledger.app.theme.computeContrastRatio
import com.edgeledger.app.theme.parseHexColor
import com.edgeledger.app.theme.resolveTokens
import com.edgeledger.app.ui.components.formatLocalKickoff
import com.edgeledger.app.ui.components.formatStatValue
import com.edgeledger.app.ui.components.shortTeamName
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.edgeledger.app.data.CacheSpillStore
import com.edgeledger.app.data.WeeklySyncWorker
import com.edgeledger.app.data.suspendRunCatching
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
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

    @Test
    fun kickoffFormatterNhlShortNamesAndEspnNflStandingsWork() {
        // ESPN ISO timestamp without seconds parses cleanly instead of showing raw YYYY-MM-DD
        val formatted = formatLocalKickoff("2026-11-22T18:00Z")
        assertFalse("Should not fall back to raw ISO date: $formatted", formatted.startsWith("2026-"))
        assertTrue(formatted.contains("·"))

        // NHL short names never truncate mid-word
        assertEquals("Rangers", shortTeamName("New York Rangers"))
        assertEquals("Lightning", shortTeamName("Tampa Bay Lightning"))
        assertEquals("Hurricanes", shortTeamName("Carolina Hurricanes"))
        assertEquals("Sabres", shortTeamName("Buffalo Sabres"))

        // ESPN NFL Standings parser handles division-nested schema
        val sampleNflJson = """
            {"children":[{"children":[{"standings":{"entries":[
              {"team":{"displayName":"Kansas City Chiefs","logos":[{"href":"https://a.espncdn.com/i/teamlogos/nfl/500/kc.png"}]},
               "stats":[{"name":"wins","value":9},{"name":"losses","value":1},{"name":"pointsFor","value":260},{"name":"pointsAgainst","value":180},{"name":"streak","displayValue":"W4"}]}
            ]}}]}]}
        """.trimIndent()
        val nflProfiles = CsvAndFeedParsers.parseEspnNflStandings(sampleNflJson)
        val kc = nflProfiles[PredictionEngine.profileKey(Sport.NFL, "Kansas City Chiefs")]
        assertNotNull(kc)
        assertTrue((kc?.powerScore ?: 50) > 65)
        assertEquals(26.0, kc?.avgScored ?: 0.0, 0.01)
        assertEquals(4, kc?.recentForm?.size)
    }

    @Test
    fun regressionTestsForAuditCriticalFixesPass() {
        // C-05 / M6: NaN guards in erf & normalCdf
        assertEquals(0.0, StatsMath.erf(Double.NaN), 0.0001)
        assertEquals(0.5, StatsMath.normalCdf(Double.NaN), 0.0001)

        // M7: Poisson normal approx for large mean (>100) never underflows
        val largeOver = StatsMath.poissonOverProb(200.0, 195.5)
        assertTrue(largeOver in 0.55..0.75)
        val largeQ = StatsMath.poissonQuantile(200.0, 0.50)
        assertTrue(largeQ in 195..205)

        // C3: Low-xG football player props never throw IllegalArgumentException
        val lowXgProps = PlayerPropsEngine.generatePlayerProps(
            sport = Sport.FOOTBALL,
            homeTeam = "Underdog FC",
            awayTeam = "Giant FC",
            projectedHomeScore = 0.05,
            projectedAwayScore = 2.8,
            homePower = 30,
            awayPower = 90,
            liveHomePlayers = listOf(LivePlayerEntry("Striker A", "ST"), LivePlayerEntry("Mid B", "MID")),
            liveAwayPlayers = listOf(LivePlayerEntry("Striker C", "ST")),
        )
        assertTrue(lowXgProps.isNotEmpty())

        // C-04: Tennis Bo3 projected games stay physically realistic (<= 15 per player)
        val tennis = PredictionEngine.predictAllMarkets(Sport.TENNIS, "Player A", "Player B", null, null, surface = "Hard")
        assertTrue(tennis.projectedHome in 4.0..15.0)
        assertTrue(tennis.projectedAway in 4.0..15.0)

        // C-14: Empty JSON object must not reset theme
        assertNull(ThemeCodec.decodeOrNull("{}"))

        // C5: Word-boundary sports filter rejects ambiguous non-sports headlines
        val client = OptionalOddsAndDataClient(OkHttpClient())
        assertFalse(client.isSportsMarketQuestion("Will United States GDP beat expectations?"))
        assertTrue(client.isSportsMarketQuestion("Will Arsenal win the Premier League?"))

        // H35: Color-blind mode remaps semantic win/loss tokens
        val darkTokens = resolveTokens(ThemeConfigV1(mode = ThemeMode.DARK))
        val deutTokens = resolveTokens(ThemeConfigV1(mode = ThemeMode.DEUTERANOPIA))
        assertTrue(darkTokens.colors.win != deutTokens.colors.win)
    }

    @Test
    fun predictionHistoryAutoGradingHandlesAllMarkets() {
        val (winStatus, winBrier) = SyncRepository.gradePredictionOutcome(
            topPickLabel = "Arsenal to Win",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            actualHomeScore = 2,
            actualAwayScore = 1,
            probHome = 0.65,
            probDraw = 0.20,
            probAway = 0.15,
        )
        assertEquals("WON", winStatus)
        assertTrue(winBrier in 0.0..0.25)

        val (bttsWin, _) = SyncRepository.gradePredictionOutcome(
            topPickLabel = "Both Teams to Score",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            actualHomeScore = 2,
            actualAwayScore = 1,
            probHome = 0.50,
            probDraw = 0.25,
            probAway = 0.25,
        )
        assertEquals("WON", bttsWin)

        val (underLoss, _) = SyncRepository.gradePredictionOutcome(
            topPickLabel = "Under 2.5 Goals",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            actualHomeScore = 2,
            actualAwayScore = 1,
            probHome = 0.50,
            probDraw = 0.25,
            probAway = 0.25,
        )
        assertEquals("LOST", underLoss)
    }

    @Test
    fun gamesFilterSortOrdersByBttsOverUnderAndDraw() {
        val mHighBtts = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Liverpool", "Man City", null, null)
            .copy(bttsYesPct = 76, over25Pct = 74, probDraw = 0.20)
        val mLowBtts = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Getafe", "Cadiz", null, null)
            .copy(bttsYesPct = 38, over25Pct = 32, probDraw = 0.34)
        val fxHigh = com.edgeledger.app.model.MatchFixture(
            id = "fx1", sport = Sport.FOOTBALL, leagueId = "E0", leagueName = "Premier League",
            kickoffIso = "2026-11-23T15:00:00Z", homeTeam = "Liverpool", awayTeam = "Man City", markets = mHighBtts, plainInsights = emptyList(), bookmakerOdds = emptyList(), h2hGames = emptyList(),
        )
        val fxLow = com.edgeledger.app.model.MatchFixture(
            id = "fx2", sport = Sport.FOOTBALL, leagueId = "SP1", leagueName = "La Liga",
            kickoffIso = "2026-11-23T17:00:00Z", homeTeam = "Getafe", awayTeam = "Cadiz", markets = mLowBtts, plainInsights = emptyList(), bookmakerOdds = emptyList(), h2hGames = emptyList(),
        )

        val byBtts = GamesFilterSort.sortFixtures(listOf(fxLow, fxHigh), GameSortMode.BTTS_HIGH) { 50 to 50 }
        assertEquals("fx1", byBtts.first().id)

        val byUnder25 = GamesFilterSort.sortFixtures(listOf(fxHigh, fxLow), GameSortMode.UNDER_25_HIGH) { 50 to 50 }
        assertEquals("fx2", byUnder25.first().id)

        val byDraw = GamesFilterSort.sortFixtures(listOf(fxHigh, fxLow), GameSortMode.DRAW_HIGH) { 50 to 50 }
        assertEquals("fx2", byDraw.first().id)

        val byLeague = GamesFilterSort.sortFixtures(listOf(fxHigh, fxLow), GameSortMode.LEAGUE_AZ) { 50 to 50 }
        assertEquals("fx2", byLeague.first().id) // La Liga before Premier League

        val byLatest = GamesFilterSort.sortFixtures(listOf(fxHigh, fxLow), GameSortMode.KICKOFF_LATE) { 50 to 50 }
        assertEquals("fx2", byLatest.first().id)

        val byBttsNo = GamesFilterSort.sortFixtures(listOf(fxHigh, fxLow), GameSortMode.BTTS_NO_HIGH) { 50 to 50 }
        assertEquals("fx2", byBttsNo.first().id)
    }

    @Test
    fun apiQuotaSummaryMergesPersistedRoomUsage() {
        val client = OptionalOddsAndDataClient(OkHttpClient())
        val persisted = listOf(
            ApiQuotaEntity(
                provider = OptionalOddsAndDataClient.PROVIDER_THE_ODDS_API,
                windowKey = "2026-11",
                callsUsed = 42,
                serverRemaining = 458,
                hardLimit = 450,
                lastStatus = "Cached (240m TTL left)",
                cooldownUntilEpochMs = 0L,
                updatedAtEpochMs = 1_700_000_000_000L,
            )
        )
        val summary = client.getQuotaSummary(UserApiKeys(theOddsApiKey = "testkey123"), persisted)
        val oddsRow = summary.first { it.provider == OptionalOddsAndDataClient.PROVIDER_THE_ODDS_API }
        assertEquals(42, oddsRow.callsUsed)
        assertTrue(oddsRow.lastStatus.contains("Cached"))
    }

    @Test
    fun kalshi2026DollarFieldsAndPolymarketEventsParseCleanly() {
        val client = OptionalOddsAndDataClient(OkHttpClient())

        // Kalshi March 2026 fixed-point dollar strings (yes_bid_dollars / yes_ask_dollars)
        val kalshiJson = """
            {"markets":[
                {"ticker":"KXNFLGAME-26NOV22-KC","title":"Will Kansas City win?","yes_sub_title":"Chiefs","yes_bid_dollars":"0.6200","yes_ask_dollars":"0.6600"}
            ]}
        """.trimIndent()
        val kalshiParsed = client.parseKalshiMarketsPayload(kalshiJson, requireSportsFilter = false)
        assertEquals(1, kalshiParsed.size)
        assertTrue(kalshiParsed[0].second in 0.63..0.65)
        assertTrue(kalshiParsed[0].first.contains("Chiefs"))

        // Polymarket /events nested markets[] with team-vs-team outcomes
        val polyJson = "[{\"title\":\"Arsenal vs Chelsea\",\"markets\":[{\"question\":\"Arsenal vs Chelsea\",\"closed\":false,\"outcomes\":\"[\\\"Arsenal\\\",\\\"Chelsea\\\"]\",\"outcomePrices\":\"[\\\"0.58\\\",\\\"0.42\\\"]\"}]}]"
        val polyParsed = client.parsePolymarketEventsPayload(polyJson)
        assertEquals(1, polyParsed.size)
        assertTrue(polyParsed[0].first.contains("Arsenal"))
        assertTrue(polyParsed[0].second in 0.57..0.59)
    }

    @Test
    fun newKeylessParsersHandleJolpicaMlbNhlTennisAndCricket() {
        val jolpicaJson = """
            {"MRData":{"StandingsTable":{"StandingsLists":[{"DriverStandings":[
                {"points":"395","wins":"8","Driver":{"givenName":"Max","familyName":"Verstappen"}},
                {"points":"330","wins":"4","Driver":{"givenName":"Lando","familyName":"Norris"}}
            ]}]}}}
        """.trimIndent()
        val f1Profiles = CsvAndFeedParsers.parseJolpicaF1Standings(jolpicaJson)
        assertEquals(2, f1Profiles.size)
        assertTrue((f1Profiles[PredictionEngine.profileKey(Sport.F1, "Max Verstappen")]?.powerScore ?: 0) > (f1Profiles[PredictionEngine.profileKey(Sport.F1, "Lando Norris")]?.powerScore ?: 0))

        val mlbJson = """
            {"records":[{"teamRecords":[{"team":{"id":119,"name":"Los Angeles Dodgers"},"wins":98,"losses":64,"runsScored":842,"runsAllowed":686}]}]}
        """.trimIndent()
        val mlbProfiles = CsvAndFeedParsers.parseMlbStandings(mlbJson)
        assertEquals(1, mlbProfiles.size)
        assertTrue((mlbProfiles[PredictionEngine.profileKey(Sport.MLB, "Los Angeles Dodgers")]?.elo ?: 0.0) > 1550.0)

        val nhlJson = """
            {"gameWeek":[{"date":"2026-11-21","games":[
                {"id":2026020301,"gameState":"FINAL","startTimeUTC":"2026-11-21T23:00:00Z",
                 "homeTeam":{"abbrev":"TOR","placeName":{"default":"Toronto"},"commonName":{"default":"Maple Leafs"},"score":4},
                 "awayTeam":{"abbrev":"MTL","placeName":{"default":"Montreal"},"commonName":{"default":"Canadiens"},"score":2}}
            ]}]}
        """.trimIndent()
        val nhlHist = CsvAndFeedParsers.parseNhlCompletedGames(nhlJson)
        assertEquals(1, nhlHist.size)
        assertEquals(4, nhlHist[0].homeScore)

        val cricJson = """
            {"matches":[{"objectId":"9991","state":"PRE","startTime":"2026-11-23T09:00:00Z",
             "series":{"name":"The Ashes"},"ground":{"smallName":"MCG"},
             "teams":[{"team":{"longName":"Australia"}},{"team":{"longName":"England"}}]}]}
        """.trimIndent()
        val cricEvents = CsvAndFeedParsers.parseEspnCricinfoCurrent(cricJson)
        assertEquals(1, cricEvents.size)
        assertEquals("Australia", cricEvents[0].homeTeam)
    }

    @Test
    fun v840AuditFixesVerifyF1H2hOnlyPropDedupAndUnconditionalDnbEv() {
        // CRIT-03: F1 H2H-only branch must never treat championship points as match scores
        val f1Home = com.edgeledger.app.model.TeamProfile(
            team = "Max Verstappen", sport = Sport.F1, leagueId = "f1",
            elo = 1720.0, powerScore = 95, gamesPlayed = 10, avgScored = 395.0, avgConceded = 0.0,
        )
        val f1Away = com.edgeledger.app.model.TeamProfile(
            team = "Lando Norris", sport = Sport.F1, leagueId = "f1",
            elo = 1660.0, powerScore = 85, gamesPlayed = 10, avgScored = 330.0, avgConceded = 0.0,
        )
        val f1Pred = PredictionEngine.predictAllMarkets(Sport.F1, "Max Verstappen", "Lando Norris", f1Home, f1Away)
        assertEquals(0.0, f1Pred.projectedHome, 0.0001)
        assertEquals(0.0, f1Pred.projectedAway, 0.0001)
        assertEquals("", f1Pred.projectedScoreDisplay)
        assertTrue(f1Pred.probHome > f1Pred.probAway)

        // CRIT-05: Multi-category NBA star must not produce duplicate Points props
        val multiCatLeaders = listOf(
            LivePlayerEntry("Nikola Jokic", "C", "points", 29.5),
            LivePlayerEntry("Nikola Jokic", "C", "rebounds", 12.8),
            LivePlayerEntry("Nikola Jokic", "C", "assists", 9.6),
            LivePlayerEntry("Jamal Murray", "PG", "points", 21.0),
        )
        val nbaProps = PlayerPropsEngine.generatePlayerProps(
            sport = Sport.NBA,
            homeTeam = "Denver Nuggets",
            awayTeam = "Miami Heat",
            projectedHomeScore = 116.0,
            projectedAwayScore = 109.0,
            homePower = 78,
            awayPower = 65,
            liveHomePlayers = multiCatLeaders,
            liveAwayPlayers = emptyList(),
        )
        val jokicProps = nbaProps.filter { it.playerName == "Nikola Jokic" }
        assertEquals(1, jokicProps.size)

        // Unconditional DNB EV must be scaled by (1 - probDraw)
        val sides = OddsMath.evaluateSides(
            homeLabel = "Home",
            awayLabel = "Away",
            probHome = 0.45,
            probDraw = 0.30,
            probAway = 0.25,
            oddsHome = 1.65,
            oddsDraw = null,
            oddsAway = 2.40,
        )
        val homeEval = sides.first { it.label == "Home" }
        // Conditional pH = 0.45 / 0.70 = 0.642857; condEV = 0.642857*1.65 - 1 = 0.0607; unconditional EV = 0.0607 * 0.70 = 0.0425
        assertTrue(homeEval.expectedValue in 0.038..0.047)
    }

    @Test
    fun v850KeylessEloSoccerStandingsOpenLigaDbAndManifoldParseCleanly() {
        val fplCsv = "id,name,elo\n1,Arsenal,1965.4\n2,Man City,1942.1\n"
        val eloMap = CsvAndFeedParsers.parseFplCoreEloCsv(fplCsv)
        assertEquals(2, eloMap.size)
        assertTrue((eloMap["Arsenal"] ?: 0.0) > 1950.0)

        val espnSocJson = """
            {"children":[{"standings":{"entries":[
                {"team":{"displayName":"Arsenal"},"stats":[
                    {"name":"gamesPlayed","value":12},{"name":"wins","value":9},{"name":"ties","value":2},{"name":"losses","value":1},
                    {"name":"pointsFor","value":26},{"name":"pointsAgainst","value":9},{"name":"points","value":29}
                ]}
            ]}}]}
        """.trimIndent()
        val socProfiles = CsvAndFeedParsers.parseEspnSoccerStandings(espnSocJson, "E0")
        assertEquals(1, socProfiles.size)
        assertTrue((socProfiles[PredictionEngine.profileKey(Sport.FOOTBALL, "Arsenal")]?.elo ?: 0.0) > 1600.0)

        val oldbJson = """
            [{"matchID":9901,"matchDateTimeUTC":"2026-11-22T14:30:00Z","matchIsFinished":true,
              "team1":{"teamName":"FC Bayern München"},"team2":{"teamName":"Borussia Dortmund"},
              "matchResults":[{"resultTypeID":1,"pointsTeam1":1,"pointsTeam2":0},{"resultTypeID":2,"pointsTeam1":3,"pointsTeam2":1}]}]
        """.trimIndent()
        val (h, u) = CsvAndFeedParsers.parseOpenLigaDbMatches(oldbJson)
        assertEquals(1, h.size)
        assertEquals(0, u.size)
        assertEquals(3, h[0].homeScore)

        val client = OptionalOddsAndDataClient(OkHttpClient())
        val manifoldJson = """[{"question":"Will Arsenal win the Premier League?","probability":0.41}]"""
        val mf = client.parseManifoldMarketsPayload(manifoldJson)
        assertEquals(1, mf.size)
        assertTrue(mf[0].second in 0.40..0.42)
    }

    @Test
    fun v860SharpEngineConservationShrinkageMovAndHtFtCoherence() {
        val nbaBlowoutMov = PredictionEngine.computeMovMultiplier(Sport.NBA, 20, 1700.0, 1400.0)
        val nbaUpsetMov = PredictionEngine.computeMovMultiplier(Sport.NBA, 20, 1400.0, 1700.0)
        assertTrue(nbaBlowoutMov in 0.8..2.35)
        assertTrue(nbaUpsetMov > nbaBlowoutMov)

        val pHome = com.edgeledger.app.model.TeamProfile(
            team = "Arsenal", sport = Sport.FOOTBALL, leagueId = "E0",
            elo = 1650.0, powerScore = 82, gamesPlayed = 15, avgScored = 2.0, avgConceded = 0.9,
        )
        val pAway = com.edgeledger.app.model.TeamProfile(
            team = "Everton", sport = Sport.FOOTBALL, leagueId = "E0",
            elo = 1480.0, powerScore = 48, gamesPlayed = 15, avgScored = 1.1, avgConceded = 1.5,
        )
        val unblended = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Everton", pHome, pAway, sharpMarketHomeProb = null, marketBlendWeight = 0.0)
        val blended = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Everton", pHome, pAway, sharpMarketHomeProb = 0.78, marketBlendWeight = 0.35)
        assertEquals(unblended.projectedHome + unblended.projectedAway, blended.projectedHome + blended.projectedAway, 0.05)

        assertTrue(unblended.topHtFt.isNotEmpty())
        assertTrue(unblended.topHtFt.first().second <= (unblended.probHome * 100.0).toInt() + 2)

        val oneGame = listOf(
            HistoricalMatch("g1", Sport.FOOTBALL, "E0", "2026-11-20", "SmallSample FC", "Other FC", 6, 0)
        )
        val shrunk = PredictionEngine.buildTeamProfiles(oneGame)[PredictionEngine.profileKey(Sport.FOOTBALL, "SmallSample FC")]!!
        assertTrue(shrunk.avgScored in 1.8..2.6)
    }

    @Test
    fun v870ExpandedSoccerExtraCsvWorldEloAndFplInjuriesParseCleanly() {
        // Football-Data.co.uk Global Extra CSV schema (Home/Away/HG/AG + PSCH closing odds)
        val extraCsv = """
            Country,League,Season,Date,Time,Home,Away,HG,AG,Res,PSCH,PSCD,PSCA
            Brazil,Serie A,2026,18/10/2026,20:00,Flamengo,Palmeiras,2,1,H,2.15,3.25,3.60
            Brazil,Serie A,2021,10/05/2021,20:00,Flamengo,Palmeiras,1,0,H,2.00,3.20,3.80
        """.trimIndent()
        val bra = CsvAndFeedParsers.parseFootballDataCsv(extraCsv, "BRA", minYear = 2024)
        assertEquals(1, bra.size)
        assertEquals("Flamengo", bra[0].homeTeam)
        assertEquals(2, bra[0].homeScore)
        assertEquals(2.15, bra[0].oddsHome!!, 0.001)

        // World Football Elo TSV (eloratings.net)
        val worldTsv = "1\t1\tArgentina\t2125\t0\n2\t2\tSpain\t2110\t0\n"
        val wElo = CsvAndFeedParsers.parseWorldFootballEloTsv(worldTsv)
        assertEquals(2, wElo.size)
        assertEquals(2125.0, wElo["Argentina"]!!, 0.01)

        // FPL Bootstrap-Static live injuries
        val fplJson = """
            {"teams":[{"id":1,"name":"Arsenal"}],
             "elements":[{"web_name":"Saka","team":1,"status":"d","chance_of_playing_next_round":25,"selected_by_percent":"32.4","minutes":900},
                         {"web_name":"BenchKid","team":1,"status":"i","chance_of_playing_next_round":0,"selected_by_percent":"0.1","minutes":0}]}
        """.trimIndent()
        val inj = CsvAndFeedParsers.parseFplBootstrapInjuries(fplJson)
        assertEquals(1, inj["Arsenal"]?.size)
        assertTrue(inj["Arsenal"]!!.first().contains("Saka"))
    }

    @Test
    fun v870MoneyPuckTennisAbstractGrecoUfcSquiggleAflOpenDotaAndNcaaParseCleanly() {
        // MoneyPuck NHL Team xG CSV
        val mpCsv = "team,situation,games_played,xGoalsPercentage,xGoalsFor,xGoalsAgainst\nEDM,all,20,0.58,68.0,49.2\nEDM,5on5,20,0.55,50.0,41.0\n"
        val mp = CsvAndFeedParsers.parseMoneyPuckTeamsCsv(mpCsv)
        assertEquals(1, mp.size)
        val edm = mp[PredictionEngine.profileKey(Sport.NHL, "Edmonton Oilers")]
        assertNotNull(edm)
        assertTrue((edm?.elo ?: 0.0) > 1550.0)

        // Tennis Abstract HTML Elo table
        val taHtml = "<table><tr><td>1</td><td><a href='#'>Jannik Sinner</a></td><td>25.2</td><td>2245.6</td></tr></table>"
        val ta = CsvAndFeedParsers.parseTennisAbstractEloHtml(taHtml)
        assertEquals(2245.6, ta["Jannik Sinner"]!!, 0.01)

        // Greco1899 UFC CSV
        val ufcCsv = "EVENT,BOUT,OUTCOME,WEIGHTCLASS,METHOD\nUFC 310,Alex Pereira vs. Magomed Ankalaev,W/L,Light Heavyweight,KO/TKO\n"
        val ufc = CsvAndFeedParsers.parseGrecoUfcResultsCsv(ufcCsv)
        assertEquals(1, ufc.size)
        assertEquals(Sport.MMA, ufc[0].sport)
        assertEquals(1, ufc[0].homeScore)

        // Squiggle AFL JSON
        val aflJson = """
            {"games":[
                {"id":101,"date":"2026-08-10 14:10:00","hteam":"Geelong","ateam":"Sydney","hscore":98,"ascore":82,"complete":100},
                {"id":102,"date":"2026-11-28 14:10:00","hteam":"Carlton","ateam":"Collingwood","complete":0,"venue":"MCG"}
            ]}
        """.trimIndent()
        val (aflH, aflU) = CsvAndFeedParsers.parseSquiggleAflGames(aflJson)
        assertEquals(1, aflH.size)
        assertEquals(1, aflU.size)
        assertEquals(Sport.AFL, aflU[0].sport)

        // OpenDota Teams & Pro Matches
        val dotaTeams = """[{"name":"Team Liquid","rating":1560.5,"wins":120,"losses":60,"logo_url":"https://cdn.example.com/tl.png"}]"""
        val dotaProf = CsvAndFeedParsers.parseOpenDotaTeams(dotaTeams)
        assertEquals(1, dotaProf.size)
        val dotaMatches = """[{"match_id":8001,"radiant_name":"Team Liquid","dire_name":"Gaimin Gladiators","radiant_win":true,"start_time":1763856000}]"""
        val dotaH = CsvAndFeedParsers.parseOpenDotaProMatches(dotaMatches)
        assertEquals(1, dotaH.size)
        assertEquals(Sport.ESPORTS, dotaH[0].sport)

        // henrygd/ncaa-api scoreboard
        val ncaaJson = """{"games":[{"game":{"gameID":"555","gameState":"pre","startTimeEpoch":"1764000000","home":{"names":{"short":"Georgia"}},"away":{"names":{"short":"Texas"}}}}]}"""
        val (_, ncaaU) = CsvAndFeedParsers.parseNcaaApiScoreboard(ncaaJson, Sport.NFL, "NCAAF", "NCAA Football")
        assertEquals(1, ncaaU.size)
        assertEquals("Georgia", ncaaU[0].homeTeam)
    }

    @Test
    fun v870SxBetSportsGameOddsParlayCfbdAndNewSportsCoherent() {
        val client = OptionalOddsAndDataClient(OkHttpClient())
        val sxJson = """{"data":{"markets":[{"teamOneName":"Chiefs","teamTwoName":"Bills","outcomeOneName":"Chiefs","impliedOdds":"0.57"}]}}"""
        val sx = client.parseSxBetMarketsPayload(sxJson)
        assertEquals(1, sx.size)
        assertTrue(sx[0].second in 0.56..0.58)

        val sgoJson = """{"data":[{"leagueID":"NBA","teams":{"home":{"names":{"long":"Boston Celtics"}},"away":{"names":{"long":"Miami Heat"}}},"odds":{"points-home-game-ml-home":{"fairOdds":"-200"},"points-away-game-ml-away":{"fairOdds":"+180"}}}]}"""
        val sgo = client.parseSportsGameOddsPayload(sgoJson)
        assertEquals(1, sgo.size)
        assertTrue(sgo.values.first().first().isSharp)

        val cfbdJson = """[{"homeTeam":"Georgia","awayTeam":"Alabama","lines":[{"provider":"consensus","homeMoneyline":-150,"awayMoneyline":130}]}]"""
        val cfbd = client.parseCfbdLinesPayload(cfbdJson)
        assertEquals(1, cfbd.size)

        val keys = UserApiKeys(sportsGameOddsKey = "secret-sgo-99", parlayApiKey = "secret-parlay-88", cfbdApiKey = "secret-cfbd-77")
        assertTrue(keys.hasAnyApiKey())
        assertFalse(keys.toString().contains("secret-sgo-99"))
        assertFalse(keys.toString().contains("secret-parlay-88"))
        assertFalse(keys.toString().contains("secret-cfbd-77"))

        // New Sport enum verticals predict cleanly
        for (sp in listOf(Sport.RUGBY, Sport.AFL, Sport.ESPORTS, Sport.GOLF)) {
            val pred = PredictionEngine.predictAllMarkets(sp, "Team A", "Team B", null, null)
            assertEquals(1.0, pred.probHome + pred.probAway, 0.02)
            assertTrue(pred.topPicks.isNotEmpty())
        }
    }


    @Test
    fun auditHardening_threeWayArbitrageAndConfigurableFlatStake() {
        val arb = OddsMath.calculateThreeWayArbitrage(3.30, 3.60, 3.40, 100.0)
        assertTrue(arb.isArbitrage)
        assertTrue(arb.profitPct > 0.0)
        assertEquals(100.0, arb.stakeHome + arb.stakeDraw + arb.stakeAway, 1e-6)
        assertTrue(arb.guaranteedProfit > 0.0)

        val noArb = OddsMath.calculateThreeWayArbitrage(1.80, 3.30, 4.20, 100.0)
        assertFalse(noArb.isArbitrage)

        val customFlat = OddsMath.recommendedStake(
            bankroll = 2000.0,
            modelProb = 0.60,
            decimalOdds = 2.00,
            mode = StakingMode.FLAT,
            maxCap = 0.10,
            flatUnitAmount = 75.0,
        )
        assertEquals(75.0, customFlat, 1e-6)
    }

    @Test
    fun auditHardening_productionAlignedBacktestReportsCalibrationMetrics() {
        val matches = (1..36).map { i ->
            val day = java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong()).toString()
            HistoricalMatch(
                id = "bt_$i",
                sport = Sport.FOOTBALL,
                leagueId = "E0",
                dateIso = day,
                homeTeam = if (i % 2 == 0) "Arsenal" else "Chelsea",
                awayTeam = if (i % 2 == 0) "Chelsea" else "Arsenal",
                homeScore = if (i % 3 == 0) 1 else 2,
                awayScore = 1,
                oddsHome = 2.10,
                oddsDraw = 3.40,
                oddsAway = 3.60,
            )
        }
        val rec = PredictionEngine.runWalkForwardBacktest(Sport.FOOTBALL, "E0", "Premier League", matches)
        assertTrue(rec.gamesTested > 0)
        assertTrue(rec.logLoss.isFinite() && rec.logLoss > 0.0)
        assertTrue(rec.rps.isFinite() && rec.rps in 0.0..1.0)
        assertTrue(rec.ecePct.isFinite() && rec.ecePct in 0.0..100.0)
        assertTrue(PredictionEngine.leagueDixonColesRho("I1") < PredictionEngine.leagueDixonColesRho("D1"))
    }

    @Test
    fun auditHardening_cacheSpillStoreQuietHoursAndCancellationPropagation() {
        val tmpDir = Files.createTempDirectory("edgeledger_cache_test").toFile()
        try {
            val small = "small_payload_json"
            val encSmall = CacheSpillStore.encodePayload(tmpDir, "k_small", small)
            assertEquals(small, encSmall)
            assertEquals(small, CacheSpillStore.decodePayload(tmpDir, encSmall))

            val large = "x".repeat(600_000)
            val encLarge = CacheSpillStore.encodePayload(tmpDir, "k_large", large)
            assertNotNull(encLarge)
            assertTrue(encLarge!!.startsWith(CacheSpillStore.FILE_MARKER_PREFIX))
            assertEquals(large, CacheSpillStore.decodePayload(tmpDir, encLarge))
        } finally {
            tmpDir.deleteRecursively()
        }

        assertTrue(WeeklySyncWorker.isWithinQuietHours(23, 22, 7))
        assertTrue(WeeklySyncWorker.isWithinQuietHours(6, 22, 7))
        assertFalse(WeeklySyncWorker.isWithinQuietHours(14, 22, 7))

        var rethrown = false
        try {
            suspendRunCatching { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    @Test
    fun quantUpgrades_weatherAltitudeInjuriesAndNegBinOverdispersion() {
        val nb85 = StatsMath.negBinOverProb(10.0, 8.5, dispersionAlpha = 0.045)
        val nb95 = StatsMath.negBinOverProb(10.0, 9.5, dispersionAlpha = 0.045)
        assertTrue(nb85 in 0.05..0.95)
        assertTrue(nb85 > nb95)

        val calmFb = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Chelsea", null, null)
        val windyFb = PredictionEngine.predictAllMarkets(
            Sport.FOOTBALL, "Arsenal", "Chelsea", null, null,
            weather = StadiumWeather("Emirates", 11, 36, 70, "Windy"),
        )
        assertTrue(windyFb.projectedHome + windyFb.projectedAway < calmFb.projectedHome + calmFb.projectedAway)

        val seaLevelMlb = PredictionEngine.predictAllMarkets(Sport.MLB, "Rockies", "Dodgers", null, null)
        val coorsMlb = PredictionEngine.predictAllMarkets(
            Sport.MLB, "Rockies", "Dodgers", null, null,
            weather = StadiumWeather("Coors Field", 24, 8, 5, "Clear · High Altitude (1609m)"),
        )
        assertTrue(coorsMlb.projectedTotal > seaLevelMlb.projectedTotal)

        val healthy = PredictionEngine.predictAllMarkets(Sport.FOOTBALL, "Arsenal", "Chelsea", null, null)
        val injured = PredictionEngine.predictAllMarkets(
            Sport.FOOTBALL, "Arsenal", "Chelsea", null, null,
            homeInjuryCount = 3,
        )
        assertTrue(injured.probHome < healthy.probHome)
    }
}
