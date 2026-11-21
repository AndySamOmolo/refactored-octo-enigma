package com.edgeledger.app.data

import android.content.Context
import com.edgeledger.app.engine.CsvAndFeedParsers
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PlayerPropsEngine
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.engine.RawUpcomingEvent
import com.edgeledger.app.model.HeadToHeadGame
import com.edgeledger.app.model.HistoricalMatch
import com.edgeledger.app.model.LeagueTrackRecord
import com.edgeledger.app.model.LivePlayerEntry
import com.edgeledger.app.model.MatchFixture
import com.edgeledger.app.model.PredictionMarketQuote
import com.edgeledger.app.model.ProviderHealth
import com.edgeledger.app.model.Sport
import com.edgeledger.app.model.StadiumWeather
import com.edgeledger.app.model.TeamProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Serializable
data class AppModelSnapshot(
    val lastSyncEpochMs: Long,
    val isSeedData: Boolean,
    val totalHistoricalMatches: Int,
    val fixtures: List<MatchFixture>,
    val profiles: Map<String, TeamProfile>,
    val trackRecords: List<LeagueTrackRecord>,
    val providerHealth: List<ProviderHealth>,
    val predictionMarkets: List<PredictionMarketQuote> = emptyList(),
    val liveRosters: Map<String, List<LivePlayerEntry>> = emptyMap(),
)

class SyncRepository(
    context: Context,
    private val db: AppDatabase,
    private val prefsRepo: ThemeAndPrefsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val optionalClient = OptionalOddsAndDataClient(client)

    // Circuit Breaker failure counter per feed (DT-05)
    private val failureCounts = mutableMapOf<String, Int>()

    private val _snapshot = MutableStateFlow(buildOfflineSeedSnapshot())
    val snapshot: StateFlow<AppModelSnapshot> = _snapshot.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncStatusText = MutableStateFlow("")
    val syncStatusText: StateFlow<String> = _syncStatusText.asStateFlow()

    private val _providerQuotas = MutableStateFlow<List<ProviderQuotaStatus>>(emptyList())
    val providerQuotas: StateFlow<List<ProviderQuotaStatus>> = _providerQuotas.asStateFlow()

    companion object {
        fun readableLeagueName(leagueId: String, sport: Sport): String {
            if (sport == Sport.F1) return "Formula 1"
            if (sport == Sport.MMA) return "UFC / MMA"
            if (sport == Sport.CRICKET) return "Cricket · ICC"
            return when (leagueId.uppercase()) {
            "E0" -> "Premier League"
            "E1" -> "Championship"
            "SP1" -> "La Liga"
            "D1" -> "Bundesliga"
            "I1" -> "Serie A"
            "F1" -> "Ligue 1"
            "NFL" -> "NFL"
            "NBA" -> "NBA"
            "NHL" -> "NHL"
            "MLB" -> "MLB"
            "ATP" -> "ATP Tour"
            "CRI" -> "International Cricket"
            "F1R" -> "Formula 1"
            "UFC" -> "UFC / MMA"
            else -> leagueId.ifBlank { sport.label }
            }
        }
    }

    suspend fun hydrateFromRoom() = withContext(Dispatchers.IO) {
        val saved = db.dao().getSnapshot()
        if (saved != null) {
            runCatching { json.decodeFromString<AppModelSnapshot>(saved.payloadJson) }
                .onSuccess { _snapshot.value = it }
        } else {
            val seed = buildOfflineSeedSnapshot()
            _snapshot.value = seed
            persistSnapshot(seed)
        }
        _providerQuotas.value = optionalClient.getQuotaSummary(prefsRepo.apiKeys.value)
    }

    fun refreshQuotaSummary() {
        _providerQuotas.value = optionalClient.getQuotaSummary(prefsRepo.apiKeys.value)
    }

    private suspend fun persistSnapshot(snap: AppModelSnapshot) {
        db.dao().saveSnapshot(
            SnapshotEntity(
                id = 1,
                lastSyncEpochMs = snap.lastSyncEpochMs,
                isSeedData = snap.isSeedData,
                totalHistoricalMatches = snap.totalHistoricalMatches,
                payloadJson = json.encodeToString(snap),
            )
        )
        // Lock top picks immutably with SHA-256 hash (TR-02)
        val nowIso = Instant.ofEpochMilli(snap.lastSyncEpochMs).toString()
        val locked = snap.fixtures.mapNotNull { fx ->
            val top = fx.markets.topPicks.firstOrNull() ?: return@mapNotNull null
            val payload = "${fx.id}|${top.label}|${top.chancePct}|${fx.markets.modelVersion}|${fx.kickoffIso}"
            LockedPickEntity(
                hashSha256 = OddsMath.sha256Hex(payload),
                fixtureId = fx.id,
                sport = fx.sport.label,
                matchup = "${fx.homeTeam} vs ${fx.awayTeam}",
                pickLabel = top.label,
                chancePct = top.chancePct,
                modelVersion = fx.markets.modelVersion,
                lockedAtIso = nowIso,
            )
        }
        if (locked.isNotEmpty()) db.dao().insertLockedPicks(locked)
    }

    private fun fetchText(url: String, fallbackUrl: String? = null): String {
        fun call(u: String): String {
            val req = Request.Builder()
                .url(u)
                .header("User-Agent", "EdgeLedgerAndroid/5.0 (Personal Offline Predictor)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                return resp.body?.string() ?: error("Empty response")
            }
        }
        return try {
            call(url)
        } catch (e: Exception) {
            if (fallbackUrl != null) call(fallbackUrl) else throw e
        }
    }

    private fun fetchWeatherForTeam(homeTeam: String): StadiumWeather? {
        val loc = SeedData.STADIUM_GPS[homeTeam] ?: return null
        if (loc.isDome) return StadiumWeather(loc.stadium, 21, 0, 0, "Indoor Dome")
        return runCatching {
            val raw = fetchText(
                "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}&current=temperature_2m,wind_speed_10m,precipitation_probability&timezone=UTC"
            )
            val cur = json.parseToJsonElement(raw).jsonObject["current"]?.jsonObject ?: return null
            val temp = cur["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: 15
            val wind = cur["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: 10
            val rain = cur["precipitation_probability"]?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: 15
            val summary = when {
                wind >= 28 -> "Windy (May affect passing/kicking)"
                rain >= 55 -> "Rain Likely (Slick conditions)"
                else -> "Clear / Normal"
            }
            StadiumWeather(loc.stadium, temp, wind, rain, summary)
        }.getOrNull()
    }

    /**
     * Pulls all free 2026-audited keyless public sports feeds + optional user-entered API key
     * feeds directly on-device, fits all models locally, and persists atomically to Room SQLite.
     */
    suspend fun runOnDeviceWeeklySync(): AppModelSnapshot = withContext(Dispatchers.IO) {
        if (_syncing.value) return@withContext _snapshot.value
        _syncing.value = true
        try {
            val now = Instant.now()
            val today = LocalDate.now(ZoneOffset.UTC)
            val seasonStart = if (today.monthValue >= 7) today.year else today.year - 1
            val seasonCode = "${seasonStart.toString().takeLast(2)}${(seasonStart + 1).toString().takeLast(2)}"
            val keys = prefsRepo.apiKeys.value

            val liveHistory = mutableListOf<HistoricalMatch>()
            val liveUpcoming = mutableListOf<RawUpcomingEvent>()
            val liveStandingsProfiles = mutableMapOf<String, TeamProfile>()
            val clubElo = SeedData.CLUB_ELO_ANCHOR.toMutableMap()
            val health = mutableListOf<ProviderHealth>()
            var okCount = 0

            data class FeedSpec(val id: String, val name: String, val sport: Sport, val url: String, val fallback: String? = null, val action: (String) -> Int)

            val feeds = listOf(
                FeedSpec("fd-e0", "Football-Data.co.uk (Premier League)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/E0.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "E0"); liveHistory.addAll(list); list.size
                },
                FeedSpec("fd-sp1", "Football-Data.co.uk (La Liga)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/SP1.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "SP1"); liveHistory.addAll(list); list.size
                },
                FeedSpec("fd-d1", "Football-Data.co.uk (Bundesliga)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/D1.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "D1"); liveHistory.addAll(list); list.size
                },
                FeedSpec("clubelo", "ClubElo Global Ratings", Sport.FOOTBALL, "http://api.clubelo.com/$today", "http://api.clubelo.com/${today.minusDays(1)}") { raw ->
                    val map = CsvAndFeedParsers.parseClubEloCsv(raw); clubElo.putAll(map); map.size
                },
                FeedSpec("espn-epl", "ESPN Premier League Fixtures", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "E0", "Premier League")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-laliga", "ESPN La Liga Fixtures", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "SP1", "La Liga")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-bundesliga", "ESPN Bundesliga Fixtures", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/ger.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/ger.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "D1", "Bundesliga")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("nflverse", "nflverse NFL Games CSV", Sport.NFL, "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv") { raw ->
                    val list = CsvAndFeedParsers.parseNflverseGamesCsv(raw, today.year - 1); liveHistory.addAll(list); list.size
                },
                FeedSpec("espn-nfl", "ESPN NFL Scoreboard", Sport.NFL, "https://site.web.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.NFL, "NFL", "NFL")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-nba", "ESPN NBA Scoreboard", Sport.NBA, "https://site.web.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.NBA, "NBA", "NBA")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-nba-standings", "ESPN NBA Standings", Sport.NBA, "https://site.web.api.espn.com/apis/v2/sports/basketball/nba/standings", "https://site.api.espn.com/apis/v2/sports/basketball/nba/standings") { raw ->
                    val map = CsvAndFeedParsers.parseEspnNbaStandings(raw); liveStandingsProfiles.putAll(map); map.size
                },
                FeedSpec("nhl-api", "Official NHL Web API", Sport.NHL, "https://api-web.nhle.com/v1/schedule/now") { raw ->
                    val u = CsvAndFeedParsers.parseNhlSchedule(raw); liveUpcoming.addAll(u); u.size
                },
                FeedSpec("nhl-standings", "Official NHL Standings API", Sport.NHL, "https://api-web.nhle.com/v1/standings/now") { raw ->
                    val map = CsvAndFeedParsers.parseNhlStandings(raw); liveStandingsProfiles.putAll(map); map.size
                },
                FeedSpec("mlb-api", "Official MLB Stats API", Sport.MLB, "https://statsapi.mlb.com/api/v1/schedule?sportId=1&date=$today") { raw ->
                    val u = CsvAndFeedParsers.parseMlbSchedule(raw); liveUpcoming.addAll(u); u.size
                },
                FeedSpec("tml-atp", "TennisMyLife ATP CSV", Sport.TENNIS, "https://raw.githubusercontent.com/Tennismylife/TML-Database/master/${today.year}.csv") { raw ->
                    val list = CsvAndFeedParsers.parseTmlTennisCsv(raw); liveHistory.addAll(list); list.size
                },
            )

            for (feed in feeds) {
                val fails = failureCounts[feed.id] ?: 0
                if (fails >= 3) {
                    health.add(ProviderHealth(feed.id, feed.name, feed.sport, "CIRCUIT_OPEN", 0, now.toString(), "Paused after 3 failures"))
                    continue
                }
                _syncStatusText.value = "Syncing ${feed.name}..."
                try {
                    val raw = fetchText(feed.url, feed.fallback)
                    val count = feed.action(raw)
                    failureCounts[feed.id] = 0
                    okCount++
                    val st = if (count == 0 && feed.id == "mlb-api") "OFF-SEASON" else "LIVE"
                    val msg = if (st == "OFF-SEASON") "No scheduled games (Off-Season)" else "$count records parsed on-device"
                    health.add(ProviderHealth(feed.id, feed.name, feed.sport, st, count, now.toString(), msg))
                } catch (e: Exception) {
                    failureCounts[feed.id] = fails + 1
                    health.add(ProviderHealth(feed.id, feed.name, feed.sport, "OFFLINE", 0, now.toString(), e.message ?: "Offline fallback"))
                }
            }

            // Optional User-Keyed Feeds + Keyless Prediction Markets
            val liveOddsByMatch = mutableMapOf<String, List<com.edgeledger.app.model.BookmakerOddsLine>>()

            if (keys.theOddsApiKey.isNotBlank()) {
                _syncStatusText.value = "Fetching live odds (The Odds API)..."
                val oddsSports = listOf("soccer_epl", "americanfootball_nfl", "basketball_nba", "icehockey_nhl")
                var matchedEvents = 0
                for (spKey in oddsSports) {
                    val map = optionalClient.fetchTheOddsApi(spKey, keys.theOddsApiKey)
                    liveOddsByMatch.putAll(map)
                    matchedEvents += map.size
                }
                if (matchedEvents > 0) okCount++
                health.add(
                    ProviderHealth(
                        "the-odds-api",
                        "The Odds API (Live Bookmakers)",
                        Sport.FOOTBALL,
                        if (matchedEvents > 0) "LIVE" else "OFFLINE",
                        matchedEvents,
                        now.toString(),
                        if (matchedEvents > 0) "$matchedEvents matchups with live bookmaker odds" else "Check API key or monthly credits",
                    )
                )
            }

            if (keys.footballDataOrgKey.isNotBlank()) {
                _syncStatusText.value = "Fetching Football-Data.org fixtures..."
                val fdEvents = optionalClient.fetchFootballDataOrgFixtures(keys.footballDataOrgKey)
                if (fdEvents.isNotEmpty()) {
                    liveUpcoming.addAll(fdEvents)
                    okCount++
                }
                health.add(
                    ProviderHealth(
                        "football-data-org",
                        "Football-Data.org v4",
                        Sport.FOOTBALL,
                        if (fdEvents.isNotEmpty()) "LIVE" else "OFFLINE",
                        fdEvents.size,
                        now.toString(),
                        if (fdEvents.isNotEmpty()) "${fdEvents.size} official fixtures loaded" else "No scheduled matches returned",
                    )
                )
            }

            if (keys.cricketDataKey.isNotBlank()) {
                _syncStatusText.value = "Fetching CricketData.org matches..."
                val criEvents = optionalClient.fetchCricketMatches(keys.cricketDataKey)
                if (criEvents.isNotEmpty()) {
                    liveUpcoming.addAll(criEvents)
                    okCount++
                }
                health.add(
                    ProviderHealth(
                        "cricketdata-org",
                        "CricketData.org",
                        Sport.CRICKET,
                        if (criEvents.isNotEmpty()) "LIVE" else "OFFLINE",
                        criEvents.size,
                        now.toString(),
                        if (criEvents.isNotEmpty()) "${criEvents.size} cricket fixtures loaded" else "No active cricket matches",
                    )
                )
            }

            val livePredictionMarkets = mutableListOf<PredictionMarketQuote>()

            if (keys.enablePolymarket) {
                _syncStatusText.value = "Syncing Polymarket public odds..."
                val pm = optionalClient.fetchPolymarketOdds()
                if (pm.isNotEmpty()) {
                    okCount++
                    pm.take(20).forEach { (q, prob) ->
                        val yp = (prob * 100.0).roundToInt().coerceIn(1, 99)
                        livePredictionMarkets.add(PredictionMarketQuote("Polymarket", q, yp, 100 - yp))
                    }
                }
                health.add(
                    ProviderHealth(
                        "polymarket",
                        "Polymarket Public Markets",
                        Sport.FOOTBALL,
                        if (pm.isNotEmpty()) "LIVE" else "OFFLINE",
                        pm.size,
                        now.toString(),
                        if (pm.isNotEmpty()) "${pm.size} open public markets synced" else "Offline fallback",
                    )
                )
            }

            if (keys.enableKalshi) {
                _syncStatusText.value = "Syncing Kalshi public markets..."
                val ka = optionalClient.fetchKalshiOdds()
                if (ka.isNotEmpty()) {
                    okCount++
                    ka.take(20).forEach { (q, prob) ->
                        val yp = (prob * 100.0).roundToInt().coerceIn(1, 99)
                        livePredictionMarkets.add(PredictionMarketQuote("Kalshi", q, yp, 100 - yp))
                    }
                }
                health.add(
                    ProviderHealth(
                        "kalshi",
                        "Kalshi Public Market Data",
                        Sport.FOOTBALL,
                        if (ka.isNotEmpty()) "LIVE" else "OFFLINE",
                        ka.size,
                        now.toString(),
                        if (ka.isNotEmpty()) "${ka.size} open exchange markets synced" else "Offline fallback",
                    )
                )
            }

            _providerQuotas.value = optionalClient.getQuotaSummary(keys)

            _syncStatusText.value = "Fitting models & weather..."
            // 100% Live Dataset Policy: never inject synthetic seed games once live data is synced!
            val mergedHistory = liveHistory
            val cutoffMs = now.toEpochMilli() - 3L * 3600L * 1000L
            val baseUpcoming = liveUpcoming
                .distinctBy { "${it.sport.name}|${it.homeTeam}|${it.awayTeam}|${it.kickoffIso.take(10)}" }
                .filter { ev ->
                    val ts = runCatching { Instant.parse(ev.kickoffIso).toEpochMilli() }.getOrNull()
                    ts == null || ts >= cutoffMs
                }

            // Attach live bookmaker odds from The Odds API when matched by home/away team
            val upcomingWithOdds = baseUpcoming.map { ev ->
                val normKey = "${ev.homeTeam.trim().lowercase()}::${ev.awayTeam.trim().lowercase()}"
                val liveLines = liveOddsByMatch[normKey]
                if (!liveLines.isNullOrEmpty()) {
                    ev.copy(bookOdds = liveLines + ev.bookOdds)
                } else {
                    ev
                }
            }

            // Fetch live 2026-27 team rosters directly on-device for upcoming fixtures (zero stale transfers!)
            val liveRosters = mutableMapOf<String, List<LivePlayerEntry>>()
            _syncStatusText.value = "Syncing live 2026-27 team rosters..."
            val fetchedKeys = mutableSetOf<String>()
            for (ev in upcomingWithOdds.take(24)) {
                val hKey = PredictionEngine.profileKey(ev.sport, ev.homeTeam)
                val aKey = PredictionEngine.profileKey(ev.sport, ev.awayTeam)
                if (ev.homeLeaders.isNotEmpty()) liveRosters[hKey] = ev.homeLeaders
                if (ev.awayLeaders.isNotEmpty()) liveRosters[aKey] = ev.awayLeaders

                fun fetchTeamRoster(sport: Sport, teamName: String, teamId: String?, key: String) {
                    if (teamId.isNullOrBlank() || key in fetchedKeys) return
                    fetchedKeys.add(key)
                    val espnPath = when {
                        sport == Sport.FOOTBALL && ev.leagueId == "SP1" -> "soccer/esp.1"
                        sport == Sport.FOOTBALL && ev.leagueId == "D1" -> "soccer/ger.1"
                        sport == Sport.FOOTBALL -> "soccer/eng.1"
                        sport == Sport.NBA -> "basketball/nba"
                        sport == Sport.NFL -> "football/nfl"
                        else -> null
                    }
                    runCatching {
                        val players = when {
                            espnPath != null -> {
                                val raw = fetchText(
                                    "https://site.web.api.espn.com/apis/site/v2/sports/$espnPath/teams/$teamId/roster",
                                    "https://site.api.espn.com/apis/site/v2/sports/$espnPath/teams/$teamId/roster",
                                )
                                CsvAndFeedParsers.parseEspnRoster(raw)
                            }
                            sport == Sport.NHL -> {
                                val raw = fetchText("https://api-web.nhle.com/v1/roster/$teamId/current")
                                CsvAndFeedParsers.parseNhlRoster(raw)
                            }
                            else -> emptyList()
                        }
                        if (players.isNotEmpty()) {
                            val existing = liveRosters[key].orEmpty()
                            liveRosters[key] = (existing + players).distinctBy { it.name.lowercase() }
                        }
                    }
                }

                fetchTeamRoster(ev.sport, ev.homeTeam, ev.homeTeamId, hKey)
                fetchTeamRoster(ev.sport, ev.awayTeam, ev.awayTeamId, aKey)
            }

            val snap = compileSnapshot(
                history = mergedHistory,
                upcoming = upcomingWithOdds,
                clubElo = clubElo,
                health = health,
                isSeed = okCount == 0,
                fetchLiveWeather = okCount > 0,
                now = now,
                predictionMarkets = livePredictionMarkets,
                extraProfiles = liveStandingsProfiles,
                liveRosters = liveRosters,
            )
            _snapshot.value = snap
            persistSnapshot(snap)
            snap
        } finally {
            _syncing.value = false
            _syncStatusText.value = ""
        }
    }

    private fun buildOfflineSeedSnapshot(): AppModelSnapshot {
        val now = Instant.now()
        return AppModelSnapshot(
            lastSyncEpochMs = 0L,
            isSeedData = true,
            totalHistoricalMatches = 0,
            fixtures = emptyList(),
            profiles = emptyMap(),
            trackRecords = emptyList(),
            providerHealth = listOf(
                ProviderHealth("live-sync", "Live Public Datasets", Sport.FOOTBALL, "READY", 0, now.toString(), "Tap Update to download live 2026-27 datasets on your phone"),
            ),
            predictionMarkets = emptyList(),
            liveRosters = emptyMap(),
        )
    }

    private fun compileSnapshot(
        history: List<HistoricalMatch>,
        upcoming: List<RawUpcomingEvent>,
        clubElo: Map<String, Double>,
        health: List<ProviderHealth>,
        isSeed: Boolean,
        fetchLiveWeather: Boolean,
        now: Instant,
        predictionMarkets: List<PredictionMarketQuote> = emptyList(),
        extraProfiles: Map<String, TeamProfile> = emptyMap(),
        liveRosters: Map<String, List<LivePlayerEntry>> = emptyMap(),
    ): AppModelSnapshot {
        val userPrefs = prefsRepo.prefs.value
        val rawProfiles = (PredictionEngine.buildTeamProfiles(history, clubElo) + extraProfiles).toMutableMap()
        for (ev in upcoming) {
            val hKey = PredictionEngine.profileKey(ev.sport, ev.homeTeam)
            val aKey = PredictionEngine.profileKey(ev.sport, ev.awayTeam)
            if (!ev.homeLogoUrl.isNullOrBlank()) {
                rawProfiles[hKey]?.let { if (it.logoUrl.isNullOrBlank()) rawProfiles[hKey] = it.copy(logoUrl = ev.homeLogoUrl) }
            }
            if (!ev.awayLogoUrl.isNullOrBlank()) {
                rawProfiles[aKey]?.let { if (it.logoUrl.isNullOrBlank()) rawProfiles[aKey] = it.copy(logoUrl = ev.awayLogoUrl) }
            }
        }
        val profiles = rawProfiles.toMap()

        val trackRecords = history.groupBy { it.sport to it.leagueId }
            .filter { it.value.size >= 12 }
            .map { (key, matches) ->
                val cleanName = readableLeagueName(key.second, key.first)
                PredictionEngine.runWalkForwardBacktest(key.first, key.second, cleanName, matches)
            }
            .sortedByDescending { it.accuracyScore }

        val fixtures = upcoming.map { ev ->
            val pH = profiles[PredictionEngine.profileKey(ev.sport, ev.homeTeam)]
            val pA = profiles[PredictionEngine.profileKey(ev.sport, ev.awayTeam)]
            val sharpLine = ev.bookOdds.firstOrNull { it.isSharp } ?: ev.bookOdds.firstOrNull()
            val sharpDevig = sharpLine?.let {
                OddsMath.deVig(it.homeOdds, it.awayOdds, it.drawOdds, userPrefs.devigMethod)
            }
            val sharpFairHome = sharpDevig?.fairHome

            val markets = PredictionEngine.predictAllMarkets(
                sport = ev.sport,
                homeTeam = ev.homeTeam,
                awayTeam = ev.awayTeam,
                homeProfile = pH,
                awayProfile = pA,
                surface = ev.surface,
                sharpMarketHomeProb = sharpFairHome,
                marketBlendWeight = userPrefs.marketBlendWeight,
            )

            val retailLine = ev.bookOdds.firstOrNull { !it.isSharp } ?: sharpLine
            val retailFairHome = retailLine?.let {
                OddsMath.deVig(it.homeOdds, it.awayOdds, it.drawOdds, userPrefs.devigMethod).fairHome
            } ?: sharpFairHome ?: markets.probHome

            val h2h = history.filter {
                it.sport == ev.sport &&
                    ((it.homeTeam == ev.homeTeam && it.awayTeam == ev.awayTeam) ||
                        (it.homeTeam == ev.awayTeam && it.awayTeam == ev.homeTeam))
            }.sortedByDescending { it.dateIso }.take(5).map {
                HeadToHeadGame(it.dateIso, it.homeTeam, it.awayTeam, it.homeScore, it.awayScore)
            }

            val insights = PredictionEngine.generatePlainInsights(pH, pA, h2h, ev.sport, markets)
            val weather = if (fetchLiveWeather && (ev.sport == Sport.FOOTBALL || ev.sport == Sport.NFL)) {
                fetchWeatherForTeam(ev.homeTeam)
            } else {
                SeedData.STADIUM_GPS[ev.homeTeam]?.let {
                    StadiumWeather(it.stadium, 16, 11, 15, if (it.isDome) "Indoor Dome" else "Typical Conditions (Tap Update for live)")
                }
            }

            val bestEval = sharpLine?.let { line ->
                OddsMath.evaluateSides(
                    homeLabel = ev.homeTeam,
                    awayLabel = ev.awayTeam,
                    probHome = markets.probHome,
                    probDraw = markets.probDraw,
                    probAway = markets.probAway,
                    oddsHome = line.homeOdds,
                    oddsDraw = line.drawOdds,
                    oddsAway = line.awayOdds,
                    kellyFraction = userPrefs.riskStyle.kellyFraction,
                    maxStakePct = userPrefs.riskStyle.maxStakePct,
                    minEvThreshold = userPrefs.minEvPct / 100.0,
                    minProbFloor = userPrefs.minProbFloorPct / 100.0,
                    devigMethod = userPrefs.devigMethod,
                ).filter { it.isGoodValue }.maxByOrNull { it.expectedValue }
            }

            val hPower = pH?.powerScore ?: 50
            val aPower = pA?.powerScore ?: 50
            val hFormScore = (pH?.recentForm ?: emptyList()).takeLast(5).sumOf { if (it == "W") 1.0 else if (it == "D") 0.45 else 0.0 }
            val aFormScore = (pA?.recentForm ?: emptyList()).takeLast(5).sumOf { if (it == "W") 1.0 else if (it == "D") 0.45 else 0.0 }
            val hasWeatherImpact = (weather?.windKmh ?: 0) >= 24 || (weather?.rainChancePct ?: 0) >= 55

            val hLive = (ev.homeLeaders + liveRosters[PredictionEngine.profileKey(ev.sport, ev.homeTeam)].orEmpty()).distinctBy { it.name.lowercase() }
            val aLive = (ev.awayLeaders + liveRosters[PredictionEngine.profileKey(ev.sport, ev.awayTeam)].orEmpty()).distinctBy { it.name.lowercase() }
            val playerProps = PlayerPropsEngine.generatePlayerProps(
                sport = ev.sport,
                homeTeam = ev.homeTeam,
                awayTeam = ev.awayTeam,
                projectedHomeScore = markets.projectedHome,
                projectedAwayScore = markets.projectedAway,
                homePower = hPower,
                awayPower = aPower,
                liveHomePlayers = hLive,
                liveAwayPlayers = aLive,
            )
            val featureDrivers = PlayerPropsEngine.computeFeatureDrivers(
                sport = ev.sport,
                homeTeam = ev.homeTeam,
                awayTeam = ev.awayTeam,
                homePower = hPower,
                awayPower = aPower,
                homeWinPct = pH?.winPct ?: 50.0,
                awayWinPct = pA?.winPct ?: 50.0,
                homeFormScore = hFormScore,
                awayFormScore = aFormScore,
                hasWindOrRain = hasWeatherImpact,
            )
            val lineMovement = if (ev.bookOdds.size >= 2 && sharpFairHome != null) {
                PlayerPropsEngine.buildLineMovementSeries(
                    fixtureId = ev.id,
                    currentSharpHomeProb = sharpFairHome,
                    currentRetailHomeProb = retailFairHome,
                )
            } else {
                emptyList()
            }
            val sampleSize = minOf(pH?.gamesPlayed ?: 4, pA?.gamesPlayed ?: 4)
            val (rangeLow, rangeHigh) = PlayerPropsEngine.computeUncertaintyInterval(markets.probHome, sampleSize)

            MatchFixture(
                id = ev.id,
                sport = ev.sport,
                leagueId = ev.leagueId,
                leagueName = readableLeagueName(ev.leagueId, ev.sport),
                kickoffIso = ev.kickoffIso,
                homeTeam = ev.homeTeam,
                awayTeam = ev.awayTeam,
                surface = ev.surface,
                markets = markets,
                plainInsights = insights,
                bookmakerOdds = ev.bookOdds,
                h2hGames = h2h,
                weather = weather,
                bestValueLabel = bestEval?.label,
                bestValueEdgePct = bestEval?.let { it.expectedValue * 100.0 },
                bestSuggestedStakePct = bestEval?.suggestedStakeFraction,
                playerProps = playerProps,
                featureDrivers = featureDrivers,
                lineMovement = lineMovement,
                homeRangeLowPct = rangeLow,
                homeRangeHighPct = rangeHigh,
                marketHoldPct = sharpDevig?.holdPct ?: 0.0,
                homeLogoUrl = ev.homeLogoUrl ?: pH?.logoUrl,
                awayLogoUrl = ev.awayLogoUrl ?: pA?.logoUrl,
            )
        }.sortedByDescending { it.markets.topPicks.firstOrNull()?.chancePct ?: 0 }

        return AppModelSnapshot(
            lastSyncEpochMs = now.toEpochMilli(),
            isSeedData = isSeed,
            totalHistoricalMatches = history.size,
            fixtures = fixtures,
            profiles = profiles,
            trackRecords = trackRecords,
            providerHealth = health,
            predictionMarkets = predictionMarkets,
            liveRosters = liveRosters,
        )
    }
}
