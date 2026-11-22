package com.edgeledger.app.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import com.edgeledger.app.engine.CsvAndFeedParsers
import com.edgeledger.app.engine.OddsMath
import com.edgeledger.app.engine.PlayerPropsEngine
import com.edgeledger.app.engine.PredictionEngine
import com.edgeledger.app.engine.RawUpcomingEvent
import com.edgeledger.app.model.BookmakerOddsLine
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
import com.edgeledger.app.widget.TopPicksWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

private data class CircuitState(
    val consecutiveFailures: Int,
    val openedAtEpochMs: Long,
)

class SyncRepository(
    context: Context,
    private val db: AppDatabase,
    private val prefsRepo: ThemeAndPrefsRepository,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val optionalClient = OptionalOddsAndDataClient(client)
    private val circuitStates = ConcurrentHashMap<String, CircuitState>()
    private val syncingLock = AtomicBoolean(false)

    private val _snapshot = MutableStateFlow(buildEmptyReadySnapshot())
    val snapshot: StateFlow<AppModelSnapshot> = _snapshot.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncStatusText = MutableStateFlow("")
    val syncStatusText: StateFlow<String> = _syncStatusText.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _providerQuotas = MutableStateFlow<List<ProviderQuotaStatus>>(emptyList())
    val providerQuotas: StateFlow<List<ProviderQuotaStatus>> = _providerQuotas.asStateFlow()

    companion object {
        private const val SNAPSHOT_FILE_NAME = "edgeledger_snapshot.json.gz"
        private const val SNAPSHOT_MARKER_FILE_BACKED = "FILE_BACKED"
        private const val MAX_BODY_BYTES = 15 * 1024 * 1024
        private const val CIRCUIT_FAILURE_THRESHOLD = 3
        private const val CIRCUIT_COOLDOWN_MS = 15L * 60_000L

        private val sharedJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val snapshotFileMutex = Mutex()
        private val ODDS_TIME_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(ZoneOffset.UTC)


        internal fun friendlyNetworkError(e: Exception): String {
            return when (e) {
                is UnknownHostException -> "DNS lookup failed (host unreachable)"
                is SocketTimeoutException -> "Connection timed out"
                is ConnectException -> "Connection refused"
                is SSLException -> "Secure connection (TLS) failed"
                is IOException -> {
                    val msg = e.message?.trim().orEmpty()
                    when {
                        msg == "HTTP 403" -> "Access blocked (HTTP 403)"
                        msg == "HTTP 404" -> "Feed not found (HTTP 404)"
                        msg == "HTTP 429" -> "Rate limited (HTTP 429)"
                        msg.startsWith("HTTP 5") -> "Server error ($msg)"
                        msg.startsWith("HTTP ") -> msg
                        msg.isNotBlank() -> truncateAtWord(msg, 64)
                        else -> "Network error"
                    }
                }
                else -> {
                    val msg = e.message?.trim().orEmpty()
                    if (msg.isNotBlank()) truncateAtWord(msg, 64) else "Unavailable"
                }
            }
        }

        private fun truncateAtWord(text: String, maxLen: Int): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.length <= maxLen) return clean
            val slice = clean.substring(0, maxLen)
            val lastSpace = slice.lastIndexOf(' ')
            return if (lastSpace >= maxLen / 2) "${slice.substring(0, lastSpace)}…" else "${slice.trimEnd()}…"
        }

        fun readableLeagueName(leagueId: String, sport: Sport): String {
            if (sport == Sport.F1) return "Formula 1"
            if (sport == Sport.MMA) return "UFC / MMA"
            if (sport == Sport.CRICKET) return "Cricket · ICC"
            return when (leagueId.uppercase(Locale.US)) {
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

        suspend fun readSnapshotFromDisk(context: Context, db: AppDatabase): AppModelSnapshot? =
            withContext(Dispatchers.IO) {
                val file = File(context.applicationContext.filesDir, SNAPSHOT_FILE_NAME)
                snapshotFileMutex.withLock {
                    if (file.exists() && file.length() > 0L) {
                        val decoded = runCatching {
                            GZIPInputStream(FileInputStream(file)).bufferedReader(Charsets.UTF_8).use {
                                sharedJson.decodeFromString<AppModelSnapshot>(it.readText())
                            }
                        }.getOrNull()
                        if (decoded != null) return@withContext decoded
                    }
                }
                val saved = runCatching { db.dao().getSnapshot() }.getOrNull() ?: return@withContext null
                if (saved.payloadJson.startsWith("{")) {
                    return@withContext runCatching {
                        sharedJson.decodeFromString<AppModelSnapshot>(saved.payloadJson)
                    }.getOrNull()
                }
                null
            }
    }

    suspend fun hydrateFromRoom() = withContext(Dispatchers.IO) {
        prefsRepo.awaitLoaded()
        val loaded = readSnapshotFromDisk(appContext, db)
        _snapshot.value = loaded ?: buildEmptyReadySnapshot()
        _providerQuotas.value = optionalClient.getQuotaSummary(prefsRepo.apiKeys.value)
    }

    fun refreshQuotaSummary() {
        _providerQuotas.value = optionalClient.getQuotaSummary(prefsRepo.apiKeys.value)
    }

    fun clearSyncError() {
        _syncError.value = null
    }

    private suspend fun persistSnapshot(
        snap: AppModelSnapshot,
        oddsHistoryBatch: List<OddsHistoryEntity>,
    ) = withContext(Dispatchers.IO) {
        val target = File(appContext.filesDir, SNAPSHOT_FILE_NAME)
        val tmp = File(appContext.filesDir, "$SNAPSHOT_FILE_NAME.tmp")
        val encoded = json.encodeToString(snap)
        snapshotFileMutex.withLock {
            GZIPOutputStream(FileOutputStream(tmp)).bufferedWriter(Charsets.UTF_8).use { it.write(encoded) }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }

        val nowMs = snap.lastSyncEpochMs
        val nowIso = Instant.ofEpochMilli(nowMs).toString()
        val locked = snap.fixtures.mapNotNull { fx ->
            val top = fx.markets.topPicks.firstOrNull() ?: return@mapNotNull null
            val kickoffMs = runCatching { Instant.parse(fx.kickoffIso).toEpochMilli() }.getOrNull()
            if (kickoffMs != null && kickoffMs <= nowMs) return@mapNotNull null
            val matchup = "${fx.homeTeam} vs ${fx.awayTeam}"
            val hash = OddsMath.buildLockedPickHash(
                fixtureId = fx.id,
                matchup = matchup,
                pickLabel = top.label,
                chancePct = top.chancePct,
                modelVersion = fx.markets.modelVersion,
                lockedAtIso = nowIso,
            )
            LockedPickEntity(
                fixtureId = fx.id,
                hashSha256 = hash,
                sport = fx.sport.label,
                matchup = "${fx.homeTeam} vs ${fx.awayTeam}",
                pickLabel = top.label,
                chancePct = top.chancePct,
                modelVersion = fx.markets.modelVersion,
                lockedAtIso = nowIso,
                lockedAtEpochMs = nowMs,
            )
        }

        db.withTransaction {
            db.dao().saveSnapshot(
                SnapshotEntity(
                    id = 1,
                    lastSyncEpochMs = snap.lastSyncEpochMs,
                    isSeedData = snap.isSeedData,
                    totalHistoricalMatches = snap.totalHistoricalMatches,
                    payloadJson = SNAPSHOT_MARKER_FILE_BACKED,
                )
            )
            if (locked.isNotEmpty()) db.dao().insertLockedPicks(locked)
            if (oddsHistoryBatch.isNotEmpty()) db.dao().insertOddsHistory(oddsHistoryBatch)
            db.dao().pruneOldOddsHistory(nowMs - 30L * 24L * 3600L * 1000L)
        }
    }

    private suspend fun executeCancellable(req: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private suspend fun readBoundedText(response: Response): String {
        val stream = response.body?.byteStream() ?: throw IOException("Empty response")
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        stream.use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > MAX_BODY_BYTES) throw IOException("Response exceeds 15 MB limit")
                baos.write(buf, 0, read)
            }
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private suspend fun fetchSingleUrl(url: String): String {
        var attempt = 0
        while (true) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "EdgeLedgerAndroid/8.0 (Personal Offline Predictor)")
                .build()
            var retryDelayMs: Long? = null
            executeCancellable(req).use { resp ->
                if ((resp.code == 429 || resp.code in 502..504) && attempt == 0) {
                    val retrySec = resp.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 10L) ?: 2L
                    attempt++
                    retryDelayMs = retrySec * 1000L
                } else if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                } else {
                    return readBoundedText(resp)
                }
            }
            if (retryDelayMs != null) delay(retryDelayMs!!)
        }
    }

    private suspend fun fetchText(url: String, fallbackUrl: String? = null): String {
        return try {
            fetchSingleUrl(url)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (fallbackUrl != null) fetchSingleUrl(fallbackUrl) else throw e
        }
    }

    private suspend fun fetchWeatherForTeam(homeTeam: String): StadiumWeather? {
        val loc = SeedData.STADIUM_GPS[homeTeam] ?: return null
        if (loc.isDome) return StadiumWeather(loc.stadium, 21, 0, 0, "Indoor Dome")
        return try {
            val raw = fetchText(
                "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}&current=temperature_2m,wind_speed_10m,precipitation_probability&timezone=UTC"
            )
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val cur = root["current"] as? JsonObject ?: return null
            val temp = (cur["temperature_2m"] as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: return null
            val wind = (cur["wind_speed_10m"] as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: 0
            val rain = (cur["precipitation_probability"] as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: 0
            val summary = when {
                wind >= 28 -> "Windy (May affect passing/kicking)"
                rain >= 55 -> "Rain Likely (Slick conditions)"
                else -> "Clear / Normal"
            }
            StadiumWeather(loc.stadium, temp, wind, rain, summary)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    suspend fun runOnDeviceWeeklySync(): AppModelSnapshot {
        runOnDeviceWeeklySyncWithStatus(manual = true)
        return _snapshot.value
    }

    suspend fun runOnDeviceWeeklySyncWithStatus(manual: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!syncingLock.compareAndSet(false, true)) return@withContext true
        _syncing.value = true
        _syncError.value = null
        if (manual) circuitStates.clear()
        try {
            prefsRepo.awaitLoaded()
            val now = Instant.now()
            val nowMs = now.toEpochMilli()
            val today = LocalDate.now(ZoneOffset.UTC)
            val seasonStart = if (today.monthValue >= 7) today.year else today.year - 1
            val seasonCode = "${seasonStart.toString().takeLast(2)}${(seasonStart + 1).toString().takeLast(2)}"
            val prevSeasonCode = "${(seasonStart - 1).toString().takeLast(2)}${seasonStart.toString().takeLast(2)}"
            val keys = prefsRepo.apiKeys.value
            val userPrefs = prefsRepo.prefs.value

            val liveHistory = java.util.Collections.synchronizedList(mutableListOf<HistoricalMatch>())
            val liveUpcoming = java.util.Collections.synchronizedList(mutableListOf<RawUpcomingEvent>())
            val liveStandingsProfiles = ConcurrentHashMap<String, TeamProfile>()
            val clubElo = ConcurrentHashMap<String, Double>()
            val health = java.util.Collections.synchronizedList(mutableListOf<ProviderHealth>())
            val okCounter = java.util.concurrent.atomic.AtomicInteger(0)

            data class FeedSpec(
                val id: String,
                val name: String,
                val sport: Sport,
                val url: String,
                val fallback: String? = null,
                val action: (String) -> Int,
            )

            val feeds = listOf(
                FeedSpec("fd-e0", "Football-Data.co.uk (Premier League)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/E0.csv", "https://www.football-data.co.uk/mmz4281/$prevSeasonCode/E0.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "E0"); liveHistory.addAll(list); list.size
                },
                FeedSpec("fd-sp1", "Football-Data.co.uk (La Liga)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/SP1.csv", "https://www.football-data.co.uk/mmz4281/$prevSeasonCode/SP1.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "SP1"); liveHistory.addAll(list); list.size
                },
                FeedSpec("fd-d1", "Football-Data.co.uk (Bundesliga)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/D1.csv", "https://www.football-data.co.uk/mmz4281/$prevSeasonCode/D1.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "D1"); liveHistory.addAll(list); list.size
                },
                FeedSpec("fd-i1", "Football-Data.co.uk (Serie A)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/I1.csv", "https://www.football-data.co.uk/mmz4281/$prevSeasonCode/I1.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "I1"); liveHistory.addAll(list); list.size
                },
                FeedSpec("fd-f1", "Football-Data.co.uk (Ligue 1)", Sport.FOOTBALL, "https://www.football-data.co.uk/mmz4281/$seasonCode/F1.csv", "https://www.football-data.co.uk/mmz4281/$prevSeasonCode/F1.csv") { raw ->
                    val list = CsvAndFeedParsers.parseFootballDataCsv(raw, "F1"); liveHistory.addAll(list); list.size
                },
                FeedSpec("clubelo", "ClubElo Global Ratings", Sport.FOOTBALL, "https://api.clubelo.com/$today", "http://api.clubelo.com/${today.minusDays(1)}") { raw ->
                    val map = CsvAndFeedParsers.parseClubEloCsv(raw); clubElo.putAll(map); map.size
                },
                FeedSpec("espn-epl", "ESPN Premier League", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "E0", "Premier League")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-laliga", "ESPN La Liga", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "SP1", "La Liga")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-bundesliga", "ESPN Bundesliga", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/ger.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/ger.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "D1", "Bundesliga")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-seriea", "ESPN Serie A", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/ita.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/ita.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "I1", "Serie A")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-ligue1", "ESPN Ligue 1", Sport.FOOTBALL, "https://site.web.api.espn.com/apis/site/v2/sports/soccer/fra.1/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/fra.1/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.FOOTBALL, "F1", "Ligue 1")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("nflverse", "nflverse NFL Games CSV", Sport.NFL, "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv") { raw ->
                    val list = CsvAndFeedParsers.parseNflverseGamesCsv(raw, today.year - 1); liveHistory.addAll(list); list.size
                },
                FeedSpec("espn-nfl", "ESPN NFL Scoreboard", Sport.NFL, "https://site.web.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard", "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard") { raw ->
                    val (h, u) = CsvAndFeedParsers.parseEspnScoreboard(raw, Sport.NFL, "NFL", "NFL")
                    liveHistory.addAll(h); liveUpcoming.addAll(u); h.size + u.size
                },
                FeedSpec("espn-nfl-standings", "ESPN NFL Standings", Sport.NFL, "https://site.web.api.espn.com/apis/v2/sports/football/nfl/standings", "https://site.api.espn.com/apis/v2/sports/football/nfl/standings") { raw ->
                    val map = CsvAndFeedParsers.parseEspnNflStandings(raw); liveStandingsProfiles.putAll(map); map.size
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
                FeedSpec("nhl-standings", "Official NHL Standings", Sport.NHL, "https://api-web.nhle.com/v1/standings/now") { raw ->
                    val map = CsvAndFeedParsers.parseNhlStandings(raw); liveStandingsProfiles.putAll(map); map.size
                },
                FeedSpec("mlb-api", "Official MLB Stats API", Sport.MLB, "https://statsapi.mlb.com/api/v1/schedule?sportId=1&startDate=$today&endDate=${today.plusDays(6)}") { raw ->
                    val u = CsvAndFeedParsers.parseMlbSchedule(raw); liveUpcoming.addAll(u); u.size
                },
                FeedSpec("tml-atp", "TennisMyLife ATP CSV", Sport.TENNIS, "https://raw.githubusercontent.com/Tennismylife/TML-Database/master/${today.year}.csv", "https://raw.githubusercontent.com/Tennismylife/TML-Database/master/${today.year - 1}.csv") { raw ->
                    val list = CsvAndFeedParsers.parseTmlTennisCsv(raw); liveHistory.addAll(list); list.size
                },
            )

            _syncStatusText.value = "Syncing live public datasets..."
            val sem = Semaphore(6)
            coroutineScope {
                feeds.map { feed ->
                    async {
                        sem.withPermit {
                            val cs = circuitStates[feed.id]
                            if (cs != null && cs.consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD && (nowMs - cs.openedAtEpochMs) < CIRCUIT_COOLDOWN_MS) {
                                health.add(ProviderHealth(feed.id, feed.name, feed.sport, "CIRCUIT_OPEN", 0, now.toString(), "Cooling down (15 min)"))
                                return@withPermit
                            }
                            try {
                                val raw = fetchText(feed.url, feed.fallback)
                                val count = feed.action(raw)
                                circuitStates.remove(feed.id)
                                okCounter.incrementAndGet()
                                val st = if (count == 0 && feed.id == "mlb-api") "OFF-SEASON" else "LIVE"
                                val msg = if (st == "OFF-SEASON") "No scheduled games (Off-Season)" else "$count records parsed"
                                health.add(ProviderHealth(feed.id, feed.name, feed.sport, st, count, now.toString(), msg))
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                val prevFails = circuitStates[feed.id]?.consecutiveFailures ?: 0
                                circuitStates[feed.id] = CircuitState(prevFails + 1, nowMs)
                                health.add(ProviderHealth(feed.id, feed.name, feed.sport, "OFFLINE", 0, now.toString(), friendlyNetworkError(e)))
                            }
                        }
                    }
                }.awaitAll()
            }

            val liveOddsByMatch = mutableMapOf<String, List<BookmakerOddsLine>>()
            if (keys.theOddsApiKey.isNotBlank()) {
                _syncStatusText.value = "Fetching live odds (The Odds API)..."
                val oddsSports = listOf("soccer_epl", "soccer_spain_la_liga", "soccer_germany_bundesliga", "americanfootball_nfl", "basketball_nba", "icehockey_nhl")
                var matchedEvents = 0
                for (spKey in oddsSports) {
                    val map = optionalClient.fetchTheOddsApi(spKey, keys.theOddsApiKey)
                    liveOddsByMatch.putAll(map)
                    matchedEvents += map.size
                }
                if (matchedEvents > 0) okCounter.incrementAndGet()
                health.add(
                    ProviderHealth(
                        "the-odds-api",
                        "The Odds API",
                        Sport.FOOTBALL,
                        if (matchedEvents > 0) "LIVE" else "OFFLINE",
                        matchedEvents,
                        now.toString(),
                        if (matchedEvents > 0) "$matchedEvents matchups with live odds" else "Check key or credits",
                    )
                )
            }

            if (keys.footballDataOrgKey.isNotBlank()) {
                _syncStatusText.value = "Fetching Football-Data.org..."
                val fdEvents = optionalClient.fetchFootballDataOrgFixtures(keys.footballDataOrgKey)
                if (fdEvents.isNotEmpty()) {
                    liveUpcoming.addAll(fdEvents)
                    okCounter.incrementAndGet()
                }
                health.add(
                    ProviderHealth(
                        "football-data-org",
                        "Football-Data.org v4",
                        Sport.FOOTBALL,
                        if (fdEvents.isNotEmpty()) "LIVE" else "OFFLINE",
                        fdEvents.size,
                        now.toString(),
                        if (fdEvents.isNotEmpty()) "${fdEvents.size} official fixtures" else "No scheduled matches",
                    )
                )
            }

            if (keys.oddsPapiKey.isNotBlank()) {
                _syncStatusText.value = "Checking OddsPapi v4..."
                val opFixtures = optionalClient.fetchOddsPapiFixtures(keys.oddsPapiKey)
                if (opFixtures.isNotEmpty()) okCounter.incrementAndGet()
                health.add(
                    ProviderHealth(
                        "oddspapi",
                        "OddsPapi v4",
                        Sport.FOOTBALL,
                        if (opFixtures.isNotEmpty()) "LIVE" else "OFFLINE",
                        opFixtures.size,
                        now.toString(),
                        if (opFixtures.isNotEmpty()) "${opFixtures.size} sharp fixtures" else "Check key or quota",
                    )
                )
            }

            if (keys.apiSportsKey.isNotBlank()) {
                _syncStatusText.value = "Checking API-Sports status..."
                val statusObj = optionalClient.fetchApiSportsJson(keys.apiSportsKey)
                if (statusObj != null) okCounter.incrementAndGet()
                health.add(
                    ProviderHealth(
                        "api-sports",
                        "API-Sports",
                        Sport.FOOTBALL,
                        if (statusObj != null) "LIVE" else "OFFLINE",
                        if (statusObj != null) 1 else 0,
                        now.toString(),
                        if (statusObj != null) "API-Sports authenticated" else "Check API-Sports key",
                    )
                )
            }

            if (keys.cricketDataKey.isNotBlank()) {
                _syncStatusText.value = "Fetching CricketData.org..."
                val criEvents = optionalClient.fetchCricketMatches(keys.cricketDataKey)
                if (criEvents.isNotEmpty()) {
                    liveUpcoming.addAll(criEvents)
                    okCounter.incrementAndGet()
                }
                health.add(
                    ProviderHealth(
                        "cricketdata-org",
                        "CricketData.org",
                        Sport.CRICKET,
                        if (criEvents.isNotEmpty()) "LIVE" else "OFFLINE",
                        criEvents.size,
                        now.toString(),
                        if (criEvents.isNotEmpty()) "${criEvents.size} cricket fixtures" else "No active matches",
                    )
                )
            }

            val livePredictionMarkets = mutableListOf<PredictionMarketQuote>()
            if (keys.enablePolymarket) {
                val pm = optionalClient.fetchPolymarketOdds()
                if (pm.isNotEmpty()) {
                    okCounter.incrementAndGet()
                    pm.take(20).forEach { (q, prob) ->
                        val yp = (prob * 100.0).roundToInt().coerceIn(1, 99)
                        livePredictionMarkets.add(PredictionMarketQuote("Polymarket", q, yp, 100 - yp))
                    }
                }
                health.add(
                    ProviderHealth(
                        "polymarket",
                        "Polymarket Sports",
                        Sport.FOOTBALL,
                        if (pm.isNotEmpty()) "LIVE" else "OFFLINE",
                        pm.size,
                        now.toString(),
                        if (pm.isNotEmpty()) "${pm.size} sports markets" else "Unavailable",
                    )
                )
            }

            if (keys.enableKalshi) {
                val ka = optionalClient.fetchKalshiOdds()
                if (ka.isNotEmpty()) {
                    okCounter.incrementAndGet()
                    ka.take(20).forEach { (q, prob) ->
                        val yp = (prob * 100.0).roundToInt().coerceIn(1, 99)
                        livePredictionMarkets.add(PredictionMarketQuote("Kalshi", q, yp, 100 - yp))
                    }
                }
                health.add(
                    ProviderHealth(
                        "kalshi",
                        "Kalshi Sports",
                        Sport.FOOTBALL,
                        if (ka.isNotEmpty()) "LIVE" else "OFFLINE",
                        ka.size,
                        now.toString(),
                        if (ka.isNotEmpty()) "${ka.size} sports markets" else "Unavailable",
                    )
                )
            }

            _providerQuotas.value = optionalClient.getQuotaSummary(keys)

            val okCount = okCounter.get()
            if (okCount == 0) {
                _syncError.value = "All live feeds are unreachable. Showing last cached slate."
                return@withContext false
            }

            val cutoffMs = nowMs - 3L * 3600L * 1000L
            val baseUpcoming = liveUpcoming
                .distinctBy { "${it.sport.name}|${it.homeTeam}|${it.awayTeam}|${it.kickoffIso.take(10)}" }
                .filter { ev ->
                    val ts = runCatching { Instant.parse(ev.kickoffIso).toEpochMilli() }.getOrNull()
                    ts == null || ts >= cutoffMs
                }

            val upcomingWithOdds = baseUpcoming.map { ev ->
                val normKey = "${ev.homeTeam.trim().lowercase(Locale.US)}::${ev.awayTeam.trim().lowercase(Locale.US)}"
                val liveLines = liveOddsByMatch[normKey]
                if (!liveLines.isNullOrEmpty()) ev.copy(bookOdds = liveLines + ev.bookOdds) else ev
            }

            val liveRosters = ConcurrentHashMap<String, List<LivePlayerEntry>>()
            _syncStatusText.value = "Syncing live team rosters..."
            val fetchedKeys = ConcurrentHashMap.newKeySet<String>()
            coroutineScope {
                upcomingWithOdds.flatMap { ev ->
                    val hKey = PredictionEngine.profileKey(ev.sport, ev.homeTeam)
                    val aKey = PredictionEngine.profileKey(ev.sport, ev.awayTeam)
                    if (ev.homeLeaders.isNotEmpty()) liveRosters[hKey] = ev.homeLeaders
                    if (ev.awayLeaders.isNotEmpty()) liveRosters[aKey] = ev.awayLeaders
                    listOf(
                        Triple(ev, ev.homeTeamId, hKey),
                        Triple(ev, ev.awayTeamId, aKey),
                    )
                }.mapNotNull { (ev, teamId, key) ->
                    if (teamId.isNullOrBlank() || !fetchedKeys.add(key)) return@mapNotNull null
                    async {
                        sem.withPermit {
                            fetchRosterForTeam(ev.sport, ev.leagueId, teamId, key, liveRosters)
                        }
                    }
                }.awaitAll()
            }

            _syncStatusText.value = "Fetching live stadium weather..."
            val weatherMap = ConcurrentHashMap<String, StadiumWeather>()
            coroutineScope {
                upcomingWithOdds
                    .filter { it.sport == Sport.FOOTBALL || it.sport == Sport.NFL }
                    .map { it.homeTeam }
                    .distinct()
                    .map { homeTeam ->
                        async {
                            sem.withPermit {
                                fetchWeatherForTeam(homeTeam)?.let { weatherMap[homeTeam] = it }
                            }
                        }
                    }.awaitAll()
            }

            // Batch-load prior Room odds history for all upcoming fixtures
            val allFixtureIds = upcomingWithOdds.map { it.id }
            val priorGrouped = if (allFixtureIds.isNotEmpty()) {
                try {
                    allFixtureIds.chunked(500).flatMap { chunk ->
                        db.dao().getOddsHistoryForFixtures(chunk)
                    }.groupBy { it.fixtureId }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val oddsHistoryMap = HashMap<String, List<OddsHistoryEntity>>()
            val newOddsSnapshots = mutableListOf<OddsHistoryEntity>()
            val timeLabel = ODDS_TIME_FMT.format(now)

            for (ev in upcomingWithOdds) {
                val sharp = ev.bookOdds.firstOrNull { it.isSharp } ?: ev.bookOdds.firstOrNull()
                val retail = ev.bookOdds.firstOrNull { !it.isSharp } ?: sharp
                val newEntry = sharp?.let { s ->
                    val sProb = OddsMath.deVig(s.homeOdds, s.awayOdds, s.drawOdds, userPrefs.devigMethod).fairHome * 100.0
                    val rProb = retail?.let { r ->
                        OddsMath.deVig(r.homeOdds, r.awayOdds, r.drawOdds, userPrefs.devigMethod).fairHome * 100.0
                    } ?: sProb
                    OddsHistoryEntity(
                        fixtureId = ev.id,
                        recordedAtEpochMs = nowMs,
                        label = timeLabel,
                        homeProbPct = sProb,
                        sharpHomeProbPct = sProb,
                        retailHomeProbPct = rProb,
                    )
                }
                if (newEntry != null) newOddsSnapshots.add(newEntry)
                val prior = priorGrouped[ev.id].orEmpty()
                val combined = if (newEntry != null) prior + newEntry else prior
                if (combined.size >= 2) oddsHistoryMap[ev.id] = combined
            }

            // Semantic deduplication across CSV + ESPN feeds (prefer richer CSV records over scoreboard-only)
            val dedupedHistory = liveHistory.toList()
                .groupBy { "${it.sport.name}|${it.dateIso}|${it.homeTeam.lowercase(Locale.ROOT)}|${it.awayTeam.lowercase(Locale.ROOT)}" }
                .values
                .map { dupes -> dupes.maxByOrNull { (if (it.oddsHome != null) 4 else 0) + (if (it.homeShots != null) 2 else 0) + (if (!it.id.startsWith("espn_")) 1 else 0) }!! }

            _syncStatusText.value = "Running on-device prediction models..."
            val prevSnap = _snapshot.value
            val succeededSports = health.filter { it.status == "LIVE" || it.status == "OFF-SEASON" }.map { it.sport }.toSet()
            val compiled = withContext(Dispatchers.Default) {
                compileSnapshot(
                    history = dedupedHistory,
                    upcoming = upcomingWithOdds,
                    clubElo = clubElo.toMap(),
                    health = health.toList(),
                    now = now,
                    predictionMarkets = livePredictionMarkets,
                    standingsProfiles = liveStandingsProfiles.toMap(),
                    liveRosters = liveRosters.toMap(),
                    weatherByHomeTeam = weatherMap.toMap(),
                    oddsHistoryByFixture = oddsHistoryMap,
                )
            }

            // Partial-sync resilience: keep prior cached fixtures/profiles/trackRecords for any sport whose feeds all failed
            val snap = if (!prevSnap.isSeedData && prevSnap.fixtures.isNotEmpty()) {
                val preservedFixtures = prevSnap.fixtures.filter { it.sport !in succeededSports }
                val preservedProfiles = prevSnap.profiles.filterValues { it.sport !in succeededSports }
                val preservedTracks = prevSnap.trackRecords.filter { it.sport !in succeededSports }
                compiled.copy(
                    fixtures = (compiled.fixtures + preservedFixtures).distinctBy { it.id },
                    profiles = preservedProfiles + compiled.profiles,
                    trackRecords = (compiled.trackRecords + preservedTracks).distinctBy { it.sport to it.leagueId },
                )
            } else {
                compiled
            }
            persistSnapshot(snap, newOddsSnapshots)
            _snapshot.value = snap
            try {
                TopPicksWidget().updateAll(appContext)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Non-fatal widget refresh error
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            _syncError.value = friendlyNetworkError(e)
            false
        } finally {
            syncingLock.set(false)
            _syncing.value = false
            _syncStatusText.value = ""
        }
    }

    private suspend fun fetchRosterForTeam(
        sport: Sport,
        leagueId: String,
        teamId: String,
        key: String,
        liveRosters: ConcurrentHashMap<String, List<LivePlayerEntry>>,
    ) {
        val espnPath = when {
            sport == Sport.FOOTBALL && leagueId == "E0" -> "soccer/eng.1"
            sport == Sport.FOOTBALL && leagueId == "E1" -> "soccer/eng.2"
            sport == Sport.FOOTBALL && leagueId == "SP1" -> "soccer/esp.1"
            sport == Sport.FOOTBALL && leagueId == "D1" -> "soccer/ger.1"
            sport == Sport.FOOTBALL && leagueId == "I1" -> "soccer/ita.1"
            sport == Sport.FOOTBALL && leagueId == "F1" -> "soccer/fra.1"
            sport == Sport.NBA -> "basketball/nba"
            sport == Sport.NFL -> "football/nfl"
            else -> null
        }
        try {
            val players = when {
                espnPath != null -> {
                    val primaryUrl = "https://site.web.api.espn.com/apis/site/v2/sports/$espnPath/teams/"
                        .toHttpUrl().newBuilder().addPathSegment(teamId).addPathSegment("roster").build().toString()
                    val fallbackUrl = "https://site.api.espn.com/apis/site/v2/sports/$espnPath/teams/"
                        .toHttpUrl().newBuilder().addPathSegment(teamId).addPathSegment("roster").build().toString()
                    CsvAndFeedParsers.parseEspnRoster(fetchText(primaryUrl, fallbackUrl))
                }
                sport == Sport.NHL -> {
                    val url = "https://api-web.nhle.com/v1/roster/"
                        .toHttpUrl().newBuilder().addPathSegment(teamId).addPathSegment("current").build().toString()
                    CsvAndFeedParsers.parseNhlRoster(fetchText(url))
                }
                else -> emptyList()
            }
            if (players.isNotEmpty()) {
                liveRosters.merge(key, players) { existing, incoming ->
                    (existing + incoming).distinctBy { it.name.lowercase(Locale.ROOT) }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Non-fatal per-team roster miss
        }
    }

    private fun buildEmptyReadySnapshot(): AppModelSnapshot {
        val now = Instant.now()
        return AppModelSnapshot(
            lastSyncEpochMs = 0L,
            isSeedData = true,
            totalHistoricalMatches = 0,
            fixtures = emptyList(),
            profiles = emptyMap(),
            trackRecords = emptyList(),
            providerHealth = listOf(
                ProviderHealth(
                    "live-sync",
                    "Live Public Datasets",
                    Sport.FOOTBALL,
                    "READY",
                    0,
                    now.toString(),
                    "Tap Update to download live sports datasets on your device",
                ),
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
        now: Instant,
        predictionMarkets: List<PredictionMarketQuote>,
        standingsProfiles: Map<String, TeamProfile>,
        liveRosters: Map<String, List<LivePlayerEntry>>,
        weatherByHomeTeam: Map<String, StadiumWeather>,
        oddsHistoryByFixture: Map<String, List<OddsHistoryEntity>>,
    ): AppModelSnapshot {
        val userPrefs = prefsRepo.prefs.value
        val historicalProfiles = PredictionEngine.buildTeamProfiles(history, clubElo)
        val mergedProfiles = historicalProfiles.toMutableMap()

        for ((k, sp) in standingsProfiles) {
            val existing = mergedProfiles[k]
            mergedProfiles[k] = if (existing == null || existing.gamesPlayed < sp.gamesPlayed) {
                sp.copy(
                    elo = if (existing != null && existing.gamesPlayed >= 5) existing.elo else sp.elo,
                    logoUrl = sp.logoUrl ?: existing?.logoUrl,
                )
            } else {
                existing.copy(logoUrl = existing.logoUrl ?: sp.logoUrl)
            }
        }

        for (ev in upcoming) {
            val hKey = PredictionEngine.profileKey(ev.sport, ev.homeTeam)
            val aKey = PredictionEngine.profileKey(ev.sport, ev.awayTeam)
            if (!ev.homeLogoUrl.isNullOrBlank()) {
                mergedProfiles[hKey]?.let { if (it.logoUrl.isNullOrBlank()) mergedProfiles[hKey] = it.copy(logoUrl = ev.homeLogoUrl) }
            }
            if (!ev.awayLogoUrl.isNullOrBlank()) {
                mergedProfiles[aKey]?.let { if (it.logoUrl.isNullOrBlank()) mergedProfiles[aKey] = it.copy(logoUrl = ev.awayLogoUrl) }
            }
        }
        val profiles = mergedProfiles.toMap()

        val trackRecords = history.groupBy { it.sport to it.leagueId }
            .filter { it.value.size >= 12 }
            .map { (key, matches) ->
                PredictionEngine.runWalkForwardBacktest(
                    sport = key.first,
                    leagueId = key.second,
                    leagueName = readableLeagueName(key.second, key.first),
                    matches = matches,
                )
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

            val h2h = history.filter {
                it.sport == ev.sport &&
                    ((it.homeTeam == ev.homeTeam && it.awayTeam == ev.awayTeam) ||
                        (it.homeTeam == ev.awayTeam && it.awayTeam == ev.homeTeam))
            }.sortedByDescending { it.dateIso }.take(5).map {
                HeadToHeadGame(it.dateIso, it.homeTeam, it.awayTeam, it.homeScore, it.awayScore)
            }

            val insights = PredictionEngine.generatePlainInsights(pH, pA, h2h, ev.sport, markets)
            val weather = weatherByHomeTeam[ev.homeTeam]

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

            val hLive = (ev.homeLeaders + liveRosters[PredictionEngine.profileKey(ev.sport, ev.homeTeam)].orEmpty())
                .distinctBy { it.name.lowercase(Locale.ROOT) }
            val aLive = (ev.awayLeaders + liveRosters[PredictionEngine.profileKey(ev.sport, ev.awayTeam)].orEmpty())
                .distinctBy { it.name.lowercase(Locale.ROOT) }

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
                fixtureId = ev.id,
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
            val lineMovement = PlayerPropsEngine.buildRealLineMovementSeries(
                history = oddsHistoryByFixture[ev.id].orEmpty(),
            )
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
            isSeedData = false,
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
