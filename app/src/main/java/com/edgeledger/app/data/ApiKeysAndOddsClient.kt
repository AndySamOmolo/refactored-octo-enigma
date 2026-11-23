package com.edgeledger.app.data

import com.edgeledger.app.engine.CsvAndFeedParsers
import com.edgeledger.app.engine.RawUpcomingEvent
import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.Sport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class UserApiKeys(
    val theOddsApiKey: String = "",
    val footballDataOrgKey: String = "",
    val oddsPapiKey: String = "",
    val cricketDataKey: String = "",
    val apiSportsKey: String = "",
    val balldontlieKey: String = "",
    val liveTennisApiKey: String = "",
    val openWeatherApiKey: String = "",
    val enablePolymarket: Boolean = true,
    val enableKalshi: Boolean = true,
    val enableManifold: Boolean = true,
) {
    fun hasAnyApiKey(): Boolean =
        theOddsApiKey.isNotBlank() ||
            footballDataOrgKey.isNotBlank() ||
            oddsPapiKey.isNotBlank() ||
            cricketDataKey.isNotBlank() ||
            apiSportsKey.isNotBlank() ||
            balldontlieKey.isNotBlank() ||
            liveTennisApiKey.isNotBlank() ||
            openWeatherApiKey.isNotBlank()

    override fun toString(): String =
        "UserApiKeys(" +
            "theOddsApiKey=${maskSecret(theOddsApiKey)}, " +
            "footballDataOrgKey=${maskSecret(footballDataOrgKey)}, " +
            "oddsPapiKey=${maskSecret(oddsPapiKey)}, " +
            "cricketDataKey=${maskSecret(cricketDataKey)}, " +
            "apiSportsKey=${maskSecret(apiSportsKey)}, " +
            "balldontlieKey=${maskSecret(balldontlieKey)}, " +
            "liveTennisApiKey=${maskSecret(liveTennisApiKey)}, " +
            "openWeatherApiKey=${maskSecret(openWeatherApiKey)}, " +
            "enablePolymarket=$enablePolymarket, " +
            "enableKalshi=$enableKalshi, " +
            "enableManifold=$enableManifold)"

    private fun maskSecret(secret: String): String =
        if (secret.isBlank()) "<empty>" else "<configured:${secret.length}ch>"
}

@Serializable
data class ProviderQuotaStatus(
    val provider: String,
    val callsUsed: Int,
    val freeLimitLabel: String,
    val configured: Boolean,
    val lastStatus: String,
)

class OptionalOddsAndDataClient(
    private val client: OkHttpClient,
    private val dao: EdgeLedgerDao? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val authClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val quotaMutex = Mutex()
    private val callsByProvider = ConcurrentHashMap<String, AtomicInteger>()
    private val statusByProvider = ConcurrentHashMap<String, String>()

    private fun sanitizeKey(raw: String): String =
        raw.trim().filter { it in '!'..'~' }

    private fun readBoundedBody(response: okhttp3.Response, maxBytes: Int = 5 * 1024 * 1024): String {
        val stream = response.body?.byteStream() ?: return ""
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        stream.use { input ->
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                total += r
                if (total > maxBytes) throw IOException("Response exceeds size limit")
                baos.write(buf, 0, r)
            }
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private data class CachedHttpResult(
        val body: String?,
        val fromCache: Boolean,
        val remainingHeader: String? = null,
    )

    private fun currentDailyWindow(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private fun currentMonthlyWindow(): String = LocalDate.now(ZoneOffset.UTC).toString().take(7)

    private suspend fun executeCachedGet(
        cacheKey: String,
        provider: String,
        requestBuilder: Request.Builder,
        ttlMs: Long,
        windowKey: String,
        hardLimit: Int,
        useAuthClient: Boolean = true,
    ): CachedHttpResult = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val cached = try { dao?.getApiCache(cacheKey) } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }
        if (cached != null && nowMs < cached.expiresAtEpochMs && cached.payloadJson.isNotBlank()) {
            val minsLeft = ((cached.expiresAtEpochMs - nowMs) / 60_000L).coerceAtLeast(1L)
            setStatus(provider, "Cached (${minsLeft}m TTL left)")
            return@withContext CachedHttpResult(body = cached.payloadJson, fromCache = true)
        }

        val existingQuota = quotaMutex.withLock { try { dao?.getApiQuota(provider) } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null } }
        val sameWindow = existingQuota != null && existingQuota.windowKey == windowKey
        val usedInWindow = if (sameWindow) existingQuota!!.callsUsed else 0
        val serverRem = if (sameWindow) existingQuota!!.serverRemaining else null
        val cooldownUntil = existingQuota?.cooldownUntilEpochMs ?: 0L

        if (nowMs < cooldownUntil) {
            val mins = ((cooldownUntil - nowMs) / 60_000L).coerceAtLeast(1L)
            setStatus(provider, "Cooldown (${mins}m) · using cache")
            return@withContext CachedHttpResult(body = cached?.payloadJson, fromCache = true)
        }
        if (usedInWindow >= hardLimit || (serverRem != null && serverRem <= 0)) {
            setStatus(provider, "Quota cap ($usedInWindow/$hardLimit) · cached")
            return@withContext CachedHttpResult(body = cached?.payloadJson, fromCache = true)
        }

        if (!cached?.etag.isNullOrBlank()) {
            requestBuilder.header("If-None-Match", cached!!.etag!!)
        }
        val req = requestBuilder.build()
        val httpClient = if (useAuthClient) authClient else client

        try {
            incrementCalls(provider)
            httpClient.newCall(req).execute().use { response ->
                val remainingStr = response.header("x-requests-remaining")
                val remainingInt = remainingStr?.toIntOrNull()
                val usedHeader = response.header("x-requests-used")?.toIntOrNull()
                val nextUsed = usedHeader ?: (usedInWindow + 1)
                if (usedHeader != null) {
                    callsByProvider.computeIfAbsent(provider) { AtomicInteger(0) }.set(usedHeader)
                }

                when {
                    response.code == 304 && cached != null -> {
                        val refreshed = cached.copy(fetchedAtEpochMs = nowMs, expiresAtEpochMs = nowMs + ttlMs)
                        runCatching { dao?.putApiCache(refreshed) }
                        val statusStr = "Cached (304 unchanged)"
                        setStatus(provider, statusStr)
                        runCatching {
                            dao?.upsertApiQuota(
                                ApiQuotaEntity(
                                    provider = provider,
                                    windowKey = windowKey,
                                    callsUsed = nextUsed,
                                    serverRemaining = remainingInt ?: serverRem,
                                    hardLimit = hardLimit,
                                    lastStatus = statusStr,
                                    cooldownUntilEpochMs = 0L,
                                    updatedAtEpochMs = nowMs,
                                )
                            )
                        }
                        CachedHttpResult(body = cached.payloadJson, fromCache = true, remainingHeader = remainingStr)
                    }
                    response.code == 429 -> {
                        val retrySec = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60L, 7200L) ?: 1800L
                        val untilMs = nowMs + retrySec * 1000L
                        val statusStr = "HTTP 429 · paused ${retrySec / 60}m"
                        setStatus(provider, statusStr)
                        runCatching {
                            dao?.upsertApiQuota(
                                ApiQuotaEntity(
                                    provider = provider,
                                    windowKey = windowKey,
                                    callsUsed = nextUsed,
                                    serverRemaining = remainingInt ?: serverRem,
                                    hardLimit = hardLimit,
                                    lastStatus = statusStr,
                                    cooldownUntilEpochMs = untilMs,
                                    updatedAtEpochMs = nowMs,
                                )
                            )
                        }
                        CachedHttpResult(body = cached?.payloadJson, fromCache = cached != null, remainingHeader = remainingStr)
                    }
                    response.isSuccessful -> {
                        val body = readBoundedBody(response)
                        val isApiErrorPayload = body.contains("\"status\":\"failure\"", ignoreCase = true) ||
                            (body.contains("\"errors\":{\"") && !body.contains("\"errors\":[]"))
                        if (body.isNotBlank() && !isApiErrorPayload) {
                            runCatching {
                                dao?.putApiCache(
                                    ApiResponseCacheEntity(
                                        cacheKey = cacheKey,
                                        provider = provider,
                                        payloadJson = body,
                                        fetchedAtEpochMs = nowMs,
                                        expiresAtEpochMs = nowMs + ttlMs,
                                        httpStatus = response.code,
                                        etag = response.header("ETag"),
                                    )
                                )
                            }
                        }
                        val remNote = remainingStr?.let { " · $it left" }.orEmpty()
                        val statusStr = "Live OK ($nextUsed/$hardLimit$remNote)"
                        runCatching {
                            dao?.upsertApiQuota(
                                ApiQuotaEntity(
                                    provider = provider,
                                    windowKey = windowKey,
                                    callsUsed = nextUsed,
                                    serverRemaining = remainingInt ?: serverRem,
                                    hardLimit = hardLimit,
                                    lastStatus = statusStr,
                                    cooldownUntilEpochMs = 0L,
                                    updatedAtEpochMs = nowMs,
                                )
                            )
                        }
                        CachedHttpResult(body = body.ifBlank { cached?.payloadJson }, fromCache = false, remainingHeader = remainingStr)
                    }
                    else -> {
                        val statusStr = "HTTP ${response.code}${remainingStr?.let { " ($it left)" }.orEmpty()}"
                        setStatus(provider, statusStr)
                        runCatching {
                            dao?.upsertApiQuota(
                                ApiQuotaEntity(
                                    provider = provider,
                                    windowKey = windowKey,
                                    callsUsed = nextUsed,
                                    serverRemaining = remainingInt ?: serverRem,
                                    hardLimit = hardLimit,
                                    lastStatus = statusStr,
                                    cooldownUntilEpochMs = 0L,
                                    updatedAtEpochMs = nowMs,
                                )
                            )
                        }
                        CachedHttpResult(body = cached?.payloadJson, fromCache = cached != null, remainingHeader = remainingStr)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(provider, if (cached != null) "Offline · using cache" else "Network error: ${sanitizeError(e)}")
            CachedHttpResult(body = cached?.payloadJson, fromCache = cached != null)
        }
    }

    suspend fun fetchTheOddsApi(
        sportKey: String,
        apiKey: String,
    ): Map<String, List<BookmakerOddsLine>> {
        val cleanKey = sanitizeKey(apiKey)
        val cleanSport = sportKey.trim()
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_THE_ODDS_API, "Not configured")
            return emptyMap()
        }
        if (cleanSport.isBlank()) {
            setStatus(PROVIDER_THE_ODDS_API, "Skipped: empty sportKey")
            return emptyMap()
        }
        val sportEnum = sportFromOddsKey(cleanSport)
        val requiresDraw = cleanSport.startsWith("soccer_", ignoreCase = true)

        return try {
            val url = "https://api.the-odds-api.com/v4/sports/"
                .toHttpUrl()
                .newBuilder()
                .addPathSegment(cleanSport)
                .addPathSegment("odds")
                .addQueryParameter("apiKey", cleanKey)
                .addQueryParameter("regions", "eu,us")
                .addQueryParameter("markets", "h2h")
                .addQueryParameter("oddsFormat", "decimal")
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()

            val cachedRes = executeCachedGet(
                cacheKey = "the_odds_api:$cleanSport",
                provider = PROVIDER_THE_ODDS_API,
                requestBuilder = requestBuilder,
                ttlMs = TTL_6_HOURS_MS,
                windowKey = currentMonthlyWindow(),
                hardLimit = 450,
                useAuthClient = true,
            )
            val remaining = cachedRes.remainingHeader
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyMap()
            run {

                val root = json.parseToJsonElement(body) as? JsonArray ?: run {
                    setStatus(PROVIDER_THE_ODDS_API, "Unexpected JSON payload")
                    return emptyMap()
                }

                val result = LinkedHashMap<String, List<BookmakerOddsLine>>()
                for (eventElem in root) {
                    val eventObj = eventElem as? JsonObject ?: continue
                    val rawHome = eventObj.stringOrNull("home_team")?.trim().orEmpty()
                    val rawAway = eventObj.stringOrNull("away_team")?.trim().orEmpty()
                    if (rawHome.isBlank() || rawAway.isBlank()) continue

                    val bookmakers = eventObj.arrayOrNull("bookmakers") ?: continue
                    val lines = mutableListOf<BookmakerOddsLine>()

                    for (bmElem in bookmakers) {
                        val bmObj = bmElem as? JsonObject ?: continue
                        val bookKey = bmObj.stringOrNull("key").orEmpty()
                        val bookTitle = bmObj.stringOrNull("title")?.takeIf { it.isNotBlank() }
                            ?: bookKey.ifBlank { "Unknown" }
                        val updatedLabel = bmObj.stringOrNull("last_update").orEmpty()

                        val markets = bmObj.arrayOrNull("markets") ?: continue
                        val h2hMarket = markets
                            .mapNotNull { it as? JsonObject }
                            .firstOrNull { it.stringOrNull("key").equals("h2h", ignoreCase = true) }
                            ?: continue

                        val outcomes = h2hMarket.arrayOrNull("outcomes") ?: continue
                        var homeOdds: Double? = null
                        var drawOdds: Double? = null
                        var awayOdds: Double? = null

                        for (outElem in outcomes) {
                            val outObj = outElem as? JsonObject ?: continue
                            val name = outObj.stringOrNull("name")?.trim().orEmpty()
                            val price = outObj.doubleOrNull("price") ?: continue
                            if (!price.isFinite() || price <= 1.0 || price >= 1000.0) continue

                            when {
                                name.equals(rawHome, ignoreCase = true) -> homeOdds = price
                                name.equals(rawAway, ignoreCase = true) -> awayOdds = price
                                name.equals("Draw", ignoreCase = true) ||
                                    name.equals("Tie", ignoreCase = true) -> drawOdds = price
                            }
                        }

                        val h = homeOdds
                        val a = awayOdds
                        if (h != null && a != null && (!requiresDraw || drawOdds != null)) {
                            lines.add(
                                BookmakerOddsLine(
                                    bookmaker = bookTitle,
                                    isSharp = isSharpBookmaker(bookKey),
                                    homeOdds = h,
                                    drawOdds = drawOdds,
                                    awayOdds = a,
                                    updatedLabel = updatedLabel,
                                ),
                            )
                        }
                    }

                    if (lines.isNotEmpty()) {
                        val normHome = CsvAndFeedParsers.normalizeTeam(rawHome, sportEnum)
                        val normAway = CsvAndFeedParsers.normalizeTeam(rawAway, sportEnum)
                        val matchKey = normalizeMatchKey(normHome, normAway)
                        result[matchKey] = lines.sortedByDescending { it.isSharp }
                    }
                }

                if (!cachedRes.fromCache) {
                    val quotaNote = remaining?.let { " · $it credits left" }.orEmpty()
                    setStatus(PROVIDER_THE_ODDS_API, "OK (${result.size} events$quotaNote)")
                }
                result
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_THE_ODDS_API, "Network error: ${sanitizeError(e)}")
            emptyMap()
        } catch (e: Exception) {
            setStatus(PROVIDER_THE_ODDS_API, "Parse error: ${sanitizeError(e)}")
            emptyMap()
        }
    }

    suspend fun fetchFootballDataOrgFixtures(apiKey: String): List<RawUpcomingEvent> {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_FOOTBALL_DATA, "Not configured")
            return emptyList()
        }

        return try {
            val today = LocalDate.now(ZoneOffset.UTC)
            val url = "https://api.football-data.org/v4/matches"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("dateFrom", today.toString())
                .addQueryParameter("dateTo", today.plusDays(7).toString())
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("X-Auth-Token", cleanKey)
                .header("Accept", "application/json")
                .get()

            val cachedRes = executeCachedGet(
                cacheKey = "football_data_org:matches:$today",
                provider = PROVIDER_FOOTBALL_DATA,
                requestBuilder = requestBuilder,
                ttlMs = TTL_6_HOURS_MS,
                windowKey = currentDailyWindow(),
                hardLimit = 120,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyList()
            run {

                val root = json.parseToJsonElement(body) as? JsonObject ?: run {
                    setStatus(PROVIDER_FOOTBALL_DATA, "Unexpected JSON payload")
                    return emptyList()
                }

                val matches = root.arrayOrNull("matches") ?: run {
                    setStatus(PROVIDER_FOOTBALL_DATA, "OK (0 matches)")
                    return emptyList()
                }

                val events = mutableListOf<RawUpcomingEvent>()
                for (matchElem in matches) {
                    val matchObj = matchElem as? JsonObject ?: continue
                    val matchStatus = matchObj.stringOrNull("status")?.uppercase(Locale.US).orEmpty()
                    if (matchStatus in EXCLUDED_FOOTBALL_STATUSES) continue

                    val matchId = matchObj.stringOrNull("id") ?: continue
                    val kickoffIso = matchObj.stringOrNull("utcDate").orEmpty()
                    if (kickoffIso.isBlank()) continue

                    val compObj = matchObj.objectOrNull("competition")
                    val rawCode = compObj?.stringOrNull("code")?.uppercase(Locale.US).orEmpty()
                    val leagueId = FOOTBALL_DATA_CODE_MAP[rawCode]
                        ?: rawCode.ifBlank { compObj?.stringOrNull("id") ?: "football_data" }
                    val leagueName = compObj?.stringOrNull("name") ?: leagueId

                    val homeObj = matchObj.objectOrNull("homeTeam")
                    val awayObj = matchObj.objectOrNull("awayTeam")
                    val rawHome = homeObj?.stringOrNull("name") ?: homeObj?.stringOrNull("shortName") ?: continue
                    val rawAway = awayObj?.stringOrNull("name") ?: awayObj?.stringOrNull("shortName") ?: continue
                    val homeTeam = CsvAndFeedParsers.normalizeTeam(rawHome, Sport.FOOTBALL)
                    val awayTeam = CsvAndFeedParsers.normalizeTeam(rawAway, Sport.FOOTBALL)
                    if (homeTeam.isBlank() || awayTeam.isBlank() || homeTeam == awayTeam) continue

                    events.add(
                        RawUpcomingEvent(
                            id = "fdo_$matchId",
                            sport = Sport.FOOTBALL,
                            leagueId = leagueId,
                            leagueName = leagueName,
                            kickoffIso = kickoffIso,
                            homeTeam = homeTeam,
                            awayTeam = awayTeam,
                            surface = null,
                            bookOdds = emptyList(),
                            homeLogoUrl = homeObj?.stringOrNull("crest"),
                            awayLogoUrl = awayObj?.stringOrNull("crest"),
                        ),
                    )
                }

                setStatus(PROVIDER_FOOTBALL_DATA, "OK (${events.size} fixtures)")
                events
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_FOOTBALL_DATA, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_FOOTBALL_DATA, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    suspend fun fetchPolymarketOdds(): List<Pair<String, Double>> {
        return try {
            // Gamma API caveat (2026): combining tag_id + order=volume24hr often returns an empty page.
            // Fetch sports tag_id=100639 & NBA tag_id=745 WITHOUT order=, plus top-100 active events post-filtered for sports.
            val combined = mutableListOf<Pair<String, Double>>()
            combined.addAll(
                fetchPolymarketEventsUrl(
                    "polymarket:events_sports_100639",
                    "https://gamma-api.polymarket.com/events?tag_id=100639&active=true&closed=false&limit=50",
                    requireSportsFilter = false,
                )
            )
            if (combined.size < 15) {
                combined.addAll(
                    fetchPolymarketEventsUrl(
                        "polymarket:events_nba_745",
                        "https://gamma-api.polymarket.com/events?tag_id=745&active=true&closed=false&limit=30",
                        requireSportsFilter = false,
                    )
                )
            }
            if (combined.size < 15) {
                combined.addAll(
                    fetchPolymarketEventsUrl(
                        "polymarket:events_top_active",
                        "https://gamma-api.polymarket.com/events?active=true&closed=false&order=volume24hr&ascending=false&limit=100",
                        requireSportsFilter = true,
                    )
                )
            }
            val resolved = combined.distinctBy { it.first.lowercase(Locale.US) }
            setStatus(PROVIDER_POLYMARKET, if (resolved.isNotEmpty()) "OK (${resolved.size} sports markets)" else "No active sports markets")
            resolved
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_POLYMARKET, SyncRepository.friendlyNetworkError(e))
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_POLYMARKET, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    private suspend fun fetchPolymarketEventsUrl(cacheKey: String, url: String, requireSportsFilter: Boolean = false): List<Pair<String, Double>> {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
        val cachedRes = executeCachedGet(
            cacheKey = cacheKey,
            provider = PROVIDER_POLYMARKET,
            requestBuilder = requestBuilder,
            ttlMs = TTL_30_MIN_MS,
            windowKey = currentDailyWindow(),
            hardLimit = 500,
            useAuthClient = false,
        )
        val body = cachedRes.body.orEmpty()
        if (body.isBlank()) return emptyList()
        return parsePolymarketEventsPayload(body, requireSportsFilter)
    }

    internal fun parsePolymarketEventsPayload(body: String, requireSportsFilter: Boolean = false): List<Pair<String, Double>> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        val pairs = mutableListOf<Pair<String, Double>>()
        for (elem in root) {
            val obj = elem as? JsonObject ?: continue
            // Supports both /events (with nested markets[]) and flat /markets arrays
            val nestedMarkets = obj.arrayOrNull("markets")
            val marketObjs = if (nestedMarkets != null && nestedMarkets.isNotEmpty()) {
                nestedMarkets.mapNotNull { it as? JsonObject }
            } else {
                listOf(obj)
            }
            val eventTitle = obj.stringOrNull("title")?.trim().orEmpty()
            for (marketObj in marketObjs) {
                if (marketObj.booleanOrNull("closed") == true) continue
                val question = marketObj.stringOrNull("question")?.trim().takeIf { !it.isNullOrBlank() }
                    ?: eventTitle
                if (question.isBlank() || isBlockedNonSportsMarket(question)) continue
                if (requireSportsFilter && !isSportsMarketQuestion(question, eventTitle)) continue
                val outcomes = parseStringOrJsonArray(marketObj["outcomes"])
                val yesProb = extractPolymarketYesProbability(marketObj) ?: continue
                if (!yesProb.isFinite() || yesProb !in 0.01..0.99) continue
                val hasYes = outcomes.any { it.equals("Yes", ignoreCase = true) }
                val label = if (!hasYes && outcomes.isNotEmpty() && outcomes[0].isNotBlank()) {
                    "$question (${outcomes[0]})"
                } else {
                    question
                }
                pairs.add(label to yesProb)
            }
        }
        return pairs
    }

    suspend fun fetchCricketMatches(apiKey: String): List<RawUpcomingEvent> {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_CRICKET_DATA, "Not configured")
            return emptyList()
        }

        return try {
            val url = "https://api.cricapi.com/v1/currentMatches"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("apikey", cleanKey)
                .addQueryParameter("offset", "0")
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()

            val cachedRes = executeCachedGet(
                cacheKey = "cricket_data:currentMatches",
                provider = PROVIDER_CRICKET_DATA,
                requestBuilder = requestBuilder,
                ttlMs = TTL_6_HOURS_MS,
                windowKey = currentDailyWindow(),
                hardLimit = 85,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyList()
            run {

                val root = json.parseToJsonElement(body) as? JsonObject ?: run {
                    setStatus(PROVIDER_CRICKET_DATA, "Unexpected JSON payload")
                    return emptyList()
                }

                val apiStatus = root.stringOrNull("status").orEmpty()
                if (apiStatus.equals("failure", ignoreCase = true)) {
                    setStatus(PROVIDER_CRICKET_DATA, root.stringOrNull("reason") ?: "API failure")
                    return emptyList()
                }

                val data = root.arrayOrNull("data") ?: run {
                    setStatus(PROVIDER_CRICKET_DATA, "OK (0 matches)")
                    return emptyList()
                }

                val events = mutableListOf<RawUpcomingEvent>()
                for (matchElem in data) {
                    val matchObj = matchElem as? JsonObject ?: continue
                    if (matchObj.booleanOrNull("matchEnded") == true) continue

                    val id = matchObj.stringOrNull("id")?.trim().orEmpty()
                    if (id.isBlank()) continue

                    val teams = extractCricketTeams(matchObj)
                    if (teams.size < 2 || teams[0] == teams[1]) continue

                    val matchType = matchObj.stringOrNull("matchType")?.trim()?.lowercase(Locale.US).orEmpty()
                    val leagueId = if (matchType.isNotBlank()) "cricket_$matchType" else "cricket"
                    val leagueName = matchObj.stringOrNull("name")
                        ?.substringAfter(",", "")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: matchType.uppercase(Locale.US).ifBlank { "Cricket" }

                    val kickoffIso = matchObj.stringOrNull("dateTimeGMT")?.takeIf { it.isNotBlank() }
                        ?: matchObj.stringOrNull("date").orEmpty()

                    events.add(
                        RawUpcomingEvent(
                            id = "cric_$id",
                            sport = Sport.CRICKET,
                            leagueId = leagueId,
                            leagueName = leagueName,
                            kickoffIso = kickoffIso,
                            homeTeam = teams[0],
                            awayTeam = teams[1],
                            surface = matchObj.stringOrNull("venue")?.takeIf { it.isNotBlank() },
                            bookOdds = emptyList(),
                        ),
                    )
                }

                setStatus(PROVIDER_CRICKET_DATA, "OK (${events.size} matches)")
                events
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_CRICKET_DATA, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_CRICKET_DATA, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    suspend fun fetchKalshiOdds(): List<Pair<String, Double>> {
        return try {
            val pairs = mutableListOf<Pair<String, Double>>()
            // Verified late-2026 Kalshi v2 sports series tickers (keyless REST on api.elections.kalshi.com)
            for (series in KALSHI_SPORTS_SERIES) {
                val url = "https://api.elections.kalshi.com/trade-api/v2/markets?status=open&series_ticker=$series&limit=30"
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                val cachedRes = executeCachedGet(
                    cacheKey = "kalshi:series:$series",
                    provider = PROVIDER_KALSHI,
                    requestBuilder = reqBuilder,
                    ttlMs = TTL_30_MIN_MS,
                    windowKey = currentDailyWindow(),
                    hardLimit = 600,
                    useAuthClient = false,
                )
                val body = cachedRes.body.orEmpty()
                if (body.isNotBlank()) {
                    pairs.addAll(parseKalshiMarketsPayload(body, requireSportsFilter = false))
                }
            }
            // Fallback general open-markets scan excluding multivariate combos (MVE)
            if (pairs.isEmpty()) {
                val fallbackUrl = "https://api.elections.kalshi.com/trade-api/v2/markets?status=open&mve_filter=exclude&limit=100"
                val reqBuilder = Request.Builder()
                    .url(fallbackUrl)
                    .header("Accept", "application/json")
                    .get()
                val cachedRes = executeCachedGet(
                    cacheKey = "kalshi:open_fallback",
                    provider = PROVIDER_KALSHI,
                    requestBuilder = reqBuilder,
                    ttlMs = TTL_30_MIN_MS,
                    windowKey = currentDailyWindow(),
                    hardLimit = 600,
                    useAuthClient = false,
                )
                val body = cachedRes.body.orEmpty()
                if (body.isNotBlank()) {
                    pairs.addAll(parseKalshiMarketsPayload(body, requireSportsFilter = true))
                }
            }
            val deduped = pairs.distinctBy { it.first.lowercase(Locale.US) }
            setStatus(PROVIDER_KALSHI, if (deduped.isNotEmpty()) "OK (${deduped.size} sports markets)" else "No open sports markets")
            deduped
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_KALSHI, SyncRepository.friendlyNetworkError(e))
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_KALSHI, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    internal fun parseKalshiMarketsPayload(body: String, requireSportsFilter: Boolean): List<Pair<String, Double>> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val markets = root.arrayOrNull("markets") ?: return emptyList()
        val pairs = mutableListOf<Pair<String, Double>>()
        for (marketElem in markets) {
            val marketObj = marketElem as? JsonObject ?: continue
            val title = marketObj.stringOrNull("title")?.takeIf { it.isNotBlank() }
                ?: marketObj.stringOrNull("ticker").orEmpty()
            if (title.isBlank()) continue
            val subtitle = marketObj.stringOrNull("yes_sub_title")?.takeIf { it.isNotBlank() }
                ?: marketObj.stringOrNull("subtitle")?.takeIf { it.isNotBlank() }
            if (requireSportsFilter) {
                val meta = listOfNotNull(
                    marketObj.stringOrNull("category"),
                    marketObj.stringOrNull("event_ticker"),
                    marketObj.stringOrNull("series_ticker"),
                    subtitle,
                ).joinToString(" ")
                if (!isSportsMarketQuestion(title, meta)) continue
            } else if (isBlockedNonSportsMarket(title)) {
                continue
            }
            val yesProb = extractKalshiYesProbability(marketObj) ?: continue
            if (yesProb.isFinite() && yesProb in 0.01..0.99) {
                val display = if (!subtitle.isNullOrBlank() && !title.contains(subtitle, ignoreCase = true)) {
                    "${title.trim()} ($subtitle)"
                } else {
                    title.trim()
                }
                pairs.add(display to yesProb)
            }
        }
        return pairs
    }

    suspend fun fetchOddsPapiFixtures(apiKey: String): List<JsonObject> {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_ODDSPAPI, "Not configured")
            return emptyList()
        }

        return try {
            val url = "https://api.oddspapi.io/v4/fixtures"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("apiKey", cleanKey)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()

            val cachedRes = executeCachedGet(
                cacheKey = "oddspapi:fixtures",
                provider = PROVIDER_ODDSPAPI,
                requestBuilder = requestBuilder,
                ttlMs = TTL_12_HOURS_MS,
                windowKey = currentMonthlyWindow(),
                hardLimit = 220,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyList()
            run {

                val parsed = json.parseToJsonElement(body)
                val fixtures = when (parsed) {
                    is JsonArray -> parsed.mapNotNull { it as? JsonObject }
                    is JsonObject -> parsed.arrayOrNull("fixtures")
                        ?.mapNotNull { it as? JsonObject }
                        ?: parsed.arrayOrNull("data")?.mapNotNull { it as? JsonObject }
                        ?: emptyList()
                    else -> emptyList()
                }

                setStatus(PROVIDER_ODDSPAPI, "OK (${fixtures.size} fixtures)")
                fixtures
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_ODDSPAPI, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_ODDSPAPI, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    suspend fun fetchApiSportsJson(
        apiKey: String,
        endpointUrl: String = "https://v3.football.api-sports.io/status",
    ): JsonObject? {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_API_SPORTS, "Not configured")
            return null
        }

        val parsedUrl = endpointUrl.toHttpUrlOrNull()
        if (parsedUrl == null || parsedUrl.scheme != "https" || parsedUrl.port != 443 || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty() || !parsedUrl.host.endsWith(".api-sports.io")) {
            setStatus(PROVIDER_API_SPORTS, "Blocked: untrusted endpoint")
            return null
        }

        return try {
            val requestBuilder = Request.Builder()
                .url(parsedUrl)
                .header("x-apisports-key", cleanKey)
                .header("Accept", "application/json")
                .get()

            val cachedRes = executeCachedGet(
                cacheKey = "api_sports:${parsedUrl.host}:${parsedUrl.encodedPath}?${parsedUrl.encodedQuery.orEmpty()}",
                provider = PROVIDER_API_SPORTS,
                requestBuilder = requestBuilder,
                ttlMs = TTL_6_HOURS_MS,
                windowKey = currentDailyWindow(),
                hardLimit = 85,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return null
            run {

                val obj = json.parseToJsonElement(body) as? JsonObject
                setStatus(PROVIDER_API_SPORTS, if (obj != null) "OK" else "Unexpected JSON payload")
                obj
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_API_SPORTS, "Network error: ${sanitizeError(e)}")
            null
        } catch (e: Exception) {
            setStatus(PROVIDER_API_SPORTS, "Parse error: ${sanitizeError(e)}")
            null
        }
    }

    suspend fun fetchManifoldSportsOdds(): List<Pair<String, Double>> {
        return try {
            val url = "https://api.manifold.markets/v0/search-markets?term=win&filter=open&sort=liquidity&limit=50"
            val reqBuilder = Request.Builder().url(url).header("Accept", "application/json").get()
            val cachedRes = executeCachedGet(
                cacheKey = "manifold:sports_open",
                provider = PROVIDER_MANIFOLD,
                requestBuilder = reqBuilder,
                ttlMs = TTL_30_MIN_MS,
                windowKey = currentDailyWindow(),
                hardLimit = 500,
                useAuthClient = false,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyList()
            val parsed = parseManifoldMarketsPayload(body)
            setStatus(PROVIDER_MANIFOLD, if (parsed.isNotEmpty()) "OK (${parsed.size} sports markets)" else "No active sports markets")
            parsed
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            setStatus(PROVIDER_MANIFOLD, "Error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    internal fun parseManifoldMarketsPayload(body: String): List<Pair<String, Double>> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, Double>>()
        for (elem in arr) {
            val obj = elem as? JsonObject ?: continue
            val q = obj.stringOrNull("question")?.trim().orEmpty()
            if (q.isBlank() || !isSportsMarketQuestion(q)) continue
            val prob = obj.doubleOrNull("probability")?.takeIf { it.isFinite() && it in 0.01..0.99 } ?: continue
            out.add(q to prob)
        }
        return out.distinctBy { it.first.lowercase(Locale.US) }
    }

    suspend fun fetchBalldontlieNbaGames(apiKey: String): List<RawUpcomingEvent> {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_BALLDONTLIE, "Not configured")
            return emptyList()
        }
        return try {
            val today = LocalDate.now(ZoneOffset.UTC)
            val url = "https://api.balldontlie.io/v1/games"
                .toHttpUrl().newBuilder()
                .addQueryParameter("start_date", today.toString())
                .addQueryParameter("end_date", today.plusDays(6).toString())
                .addQueryParameter("per_page", "50")
                .build()
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", cleanKey)
                .header("Accept", "application/json")
                .get()
            val cachedRes = executeCachedGet(
                cacheKey = "balldontlie:nba:$today",
                provider = PROVIDER_BALLDONTLIE,
                requestBuilder = reqBuilder,
                ttlMs = TTL_6_HOURS_MS,
                windowKey = currentDailyWindow(),
                hardLimit = 100,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyList()
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val data = root.arrayOrNull("data") ?: return emptyList()
            val out = mutableListOf<RawUpcomingEvent>()
            for (elem in data) {
                val g = elem as? JsonObject ?: continue
                val status = g.stringOrNull("status").orEmpty()
                if (status.equals("Final", ignoreCase = true)) continue
                val id = g.stringOrNull("id") ?: continue
                val home = CsvAndFeedParsers.normalizeTeam(g.objectOrNull("home_team")?.stringOrNull("full_name").orEmpty(), Sport.NBA)
                val away = CsvAndFeedParsers.normalizeTeam(g.objectOrNull("visitor_team")?.stringOrNull("full_name").orEmpty(), Sport.NBA)
                if (home.isBlank() || away.isBlank() || home == away) continue
                val kickoff = g.stringOrNull("datetime")?.takeIf { it.isNotBlank() } ?: "${g.stringOrNull("date").orEmpty()}T00:00:00Z"
                out.add(RawUpcomingEvent("bdl_$id", Sport.NBA, "NBA", "NBA", kickoff, home, away, null, emptyList()))
            }
            if (!cachedRes.fromCache) setStatus(PROVIDER_BALLDONTLIE, "OK (${out.size} NBA games)")
            out
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            setStatus(PROVIDER_BALLDONTLIE, "Error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    suspend fun fetchLiveTennisMatches(apiKey: String): List<RawUpcomingEvent> {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_LIVE_TENNIS, "Not configured")
            return emptyList()
        }
        return try {
            val today = LocalDate.now(ZoneOffset.UTC)
            val url = "https://livetennisapi.com/api/v1/matches"
                .toHttpUrl().newBuilder()
                .addQueryParameter("date", today.toString())
                .build()
            val reqBuilder = Request.Builder()
                .url(url)
                .header("X-API-Key", cleanKey)
                .header("Accept", "application/json")
                .get()
            val cachedRes = executeCachedGet(
                cacheKey = "livetennis:$today",
                provider = PROVIDER_LIVE_TENNIS,
                requestBuilder = reqBuilder,
                ttlMs = TTL_6_HOURS_MS,
                windowKey = currentDailyWindow(),
                hardLimit = 85,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return emptyList()
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val matches = root.arrayOrNull("matches") ?: root.arrayOrNull("data") ?: return emptyList()
            val out = mutableListOf<RawUpcomingEvent>()
            for (elem in matches) {
                val m = elem as? JsonObject ?: continue
                val id = m.stringOrNull("id") ?: continue
                val p1 = CsvAndFeedParsers.normalizeTeam(m.stringOrNull("player1").orEmpty(), Sport.TENNIS)
                val p2 = CsvAndFeedParsers.normalizeTeam(m.stringOrNull("player2").orEmpty(), Sport.TENNIS)
                if (p1.isBlank() || p2.isBlank() || p1 == p2) continue
                val tourney = m.stringOrNull("tournament")?.takeIf { it.isNotBlank() } ?: "ATP / WTA Tour"
                val kickoff = m.stringOrNull("startTime")?.takeIf { it.isNotBlank() } ?: "${today}T12:00:00Z"
                val surface = m.stringOrNull("surface")?.takeIf { it.isNotBlank() } ?: "Hard"
                out.add(RawUpcomingEvent("lta_$id", Sport.TENNIS, "tennis", tourney, kickoff, p1, p2, surface, emptyList()))
            }
            if (!cachedRes.fromCache) setStatus(PROVIDER_LIVE_TENNIS, "OK (${out.size} matches)")
            out
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            setStatus(PROVIDER_LIVE_TENNIS, "Error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    suspend fun fetchOpenWeatherByCoords(lat: Double, lon: Double, apiKey: String): Triple<Double, Double, Int>? {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) return null
        return try {
            val url = "https://api.openweathermap.org/data/2.5/weather"
                .toHttpUrl().newBuilder()
                .addQueryParameter("lat", String.format(Locale.US, "%.3f", lat))
                .addQueryParameter("lon", String.format(Locale.US, "%.3f", lon))
                .addQueryParameter("units", "metric")
                .addQueryParameter("appid", cleanKey)
                .build()
            val reqBuilder = Request.Builder().url(url).header("Accept", "application/json").get()
            val cachedRes = executeCachedGet(
                cacheKey = "openweather:${String.format(Locale.US, "%.2f,%.2f", lat, lon)}",
                provider = PROVIDER_OPENWEATHER,
                requestBuilder = reqBuilder,
                ttlMs = 3L * 3_600_000L,
                windowKey = currentDailyWindow(),
                hardLimit = 200,
                useAuthClient = true,
            )
            val body = cachedRes.body.orEmpty()
            if (body.isBlank()) return null
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val tempC = root.objectOrNull("main")?.doubleOrNull("temp") ?: return null
            val windMs = root.objectOrNull("wind")?.doubleOrNull("speed") ?: 0.0
            val rainMm = root.objectOrNull("rain")?.doubleOrNull("1h") ?: 0.0
            val rainPct = if (rainMm > 0.0) 80 else if (root.objectOrNull("clouds")?.doubleOrNull("all")?.let { it >= 80 } == true) 35 else 10
            Triple(tempC, windMs * 3.6, rainPct)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    fun getQuotaSummary(
        keys: UserApiKeys,
        persistedQuotas: List<ApiQuotaEntity> = emptyList(),
    ): List<ProviderQuotaStatus> {
        val byProvider = persistedQuotas.associateBy { it.provider }
        return listOf(
            buildQuotaStatus(PROVIDER_THE_ODDS_API, "500/mo (6h cache)", keys.theOddsApiKey.isNotBlank(), byProvider[PROVIDER_THE_ODDS_API]),
            buildQuotaStatus(PROVIDER_FOOTBALL_DATA, "10/min (6h cache)", keys.footballDataOrgKey.isNotBlank(), byProvider[PROVIDER_FOOTBALL_DATA]),
            buildQuotaStatus(PROVIDER_ODDSPAPI, "250/mo (12h cache)", keys.oddsPapiKey.isNotBlank(), byProvider[PROVIDER_ODDSPAPI]),
            buildQuotaStatus(PROVIDER_CRICKET_DATA, "100/day (6h cache)", keys.cricketDataKey.isNotBlank(), byProvider[PROVIDER_CRICKET_DATA]),
            buildQuotaStatus(PROVIDER_API_SPORTS, "100/day (6h cache)", keys.apiSportsKey.isNotBlank(), byProvider[PROVIDER_API_SPORTS]),
            buildQuotaStatus(PROVIDER_POLYMARKET, "Keyless (public)", keys.enablePolymarket, byProvider[PROVIDER_POLYMARKET]),
            buildQuotaStatus(PROVIDER_BALLDONTLIE, "Free tier (6h cache)", keys.balldontlieKey.isNotBlank(), byProvider[PROVIDER_BALLDONTLIE]),
            buildQuotaStatus(PROVIDER_LIVE_TENNIS, "100/day (6h cache)", keys.liveTennisApiKey.isNotBlank(), byProvider[PROVIDER_LIVE_TENNIS]),
            buildQuotaStatus(PROVIDER_OPENWEATHER, "1000/day (3h cache)", keys.openWeatherApiKey.isNotBlank(), byProvider[PROVIDER_OPENWEATHER]),
            buildQuotaStatus(PROVIDER_KALSHI, "Keyless (public)", keys.enableKalshi, byProvider[PROVIDER_KALSHI]),
            buildQuotaStatus(PROVIDER_MANIFOLD, "Keyless (public)", keys.enableManifold, byProvider[PROVIDER_MANIFOLD]),
        )
    }

    private fun buildQuotaStatus(
        provider: String,
        freeLimitLabel: String,
        configured: Boolean,
        persisted: ApiQuotaEntity?,
    ): ProviderQuotaStatus {
        val calls = maxOf(callsByProvider[provider]?.get() ?: 0, persisted?.callsUsed ?: 0)
        val defaultStatus = if (configured) "Ready (cached)" else "Not configured"
        val resolvedStatus = statusByProvider[provider]
            ?: persisted?.lastStatus?.takeIf { it.isNotBlank() }
            ?: defaultStatus
        return ProviderQuotaStatus(
            provider = provider,
            callsUsed = calls,
            freeLimitLabel = freeLimitLabel,
            configured = configured,
            lastStatus = resolvedStatus,
        )
    }

    private fun incrementCalls(provider: String) {
        callsByProvider.computeIfAbsent(provider) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun setStatus(provider: String, status: String) {
        statusByProvider[provider] = status
    }

    private fun extractPolymarketYesProbability(marketObj: JsonObject): Double? {
        val outcomes = parseStringOrJsonArray(marketObj["outcomes"])
        val prices = parseStringOrJsonArray(marketObj["outcomePrices"])
            .mapNotNull { it.toDoubleOrNull()?.takeIf { d -> d.isFinite() } }

        if (prices.isNotEmpty()) {
            val yesIndex = outcomes.indexOfFirst { it.equals("Yes", ignoreCase = true) }
            val resolvedIndex = if (yesIndex in prices.indices) yesIndex else 0
            return prices[resolvedIndex]
        }

        val bestBid = marketObj.doubleOrNull("bestBid")?.takeIf { it.isFinite() }
        val bestAsk = marketObj.doubleOrNull("bestAsk")?.takeIf { it.isFinite() }
        if (bestBid != null && bestAsk != null && bestBid in 0.0..1.0 && bestAsk in 0.0..1.0) {
            return (bestBid + bestAsk) / 2.0
        }
        return marketObj.doubleOrNull("lastTradePrice")?.takeIf { it.isFinite() }
    }

    /**
     * Kalshi v2 returns integer cents (0..100) for `yes_bid`, `yes_ask`, `last_price`.
     * Require an active, reasonably tight two-sided quote (`yesBid > 0 && yesAsk > 0 && spread <= 20c`).
     */
    internal fun extractKalshiYesProbability(marketObj: JsonObject): Double? {
        // March 2026 Kalshi v2 fixed-point dollar strings (0.0000..1.0000)
        val bidD = marketObj.dollarOrNull("yes_bid_dollars")
        val askD = marketObj.dollarOrNull("yes_ask_dollars")
        if (bidD != null && askD != null && bidD > 0.0 && askD > 0.0 && (askD - bidD) in 0.0..0.25) {
            return ((bidD + askD) / 2.0).coerceIn(0.01, 0.99)
        }
        val lastD = marketObj.dollarOrNull("last_price_dollars")
            ?: marketObj.dollarOrNull("yes_price_dollars")
        if (lastD != null && lastD in 0.01..0.99) return lastD
        if (bidD != null && bidD in 0.01..0.99) return bidD

        // Legacy integer-cent fallback (1..99)
        val yesBid = marketObj.doubleOrNull("yes_bid")?.takeIf { it.isFinite() }
        val yesAsk = marketObj.doubleOrNull("yes_ask")?.takeIf { it.isFinite() }
        if (yesBid != null && yesAsk != null && yesBid > 0.0 && yesAsk > 0.0 && (yesAsk - yesBid) in 0.0..20.0) {
            return ((yesBid + yesAsk) / 2.0) / 100.0
        }
        val lastPrice = marketObj.doubleOrNull("last_price")?.takeIf { it.isFinite() && it in 1.0..99.0 } ?: return null
        return lastPrice / 100.0
    }

    private fun JsonObject.dollarOrNull(key: String): Double? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return (prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull())?.takeIf { it.isFinite() }
    }

    private fun parseStringOrJsonArray(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> {
                val raw = element.contentOrNull?.trim().orEmpty()
                if (!raw.startsWith("[")) return emptyList()
                runCatching {
                    (json.parseToJsonElement(raw) as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                }.getOrDefault(emptyList())
            }
            else -> emptyList()
        }
    }

    private fun extractCricketTeams(matchObj: JsonObject): List<String> {
        val teams = matchObj.arrayOrNull("teams")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (teams.size >= 2) return teams
        return matchObj.arrayOrNull("teamInfo")
            ?.mapNotNull { (it as? JsonObject)?.stringOrNull("name")?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun isSharpBookmaker(key: String): Boolean =
        key.trim().lowercase(Locale.US) in SHARP_BOOK_KEYS

    private fun sportFromOddsKey(sportKey: String): Sport = when {
        sportKey.startsWith("soccer_", ignoreCase = true) -> Sport.FOOTBALL
        sportKey.startsWith("americanfootball_", ignoreCase = true) -> Sport.NFL
        sportKey.startsWith("basketball_", ignoreCase = true) -> Sport.NBA
        sportKey.startsWith("icehockey_", ignoreCase = true) -> Sport.NHL
        sportKey.startsWith("baseball_", ignoreCase = true) -> Sport.MLB
        sportKey.startsWith("tennis_", ignoreCase = true) -> Sport.TENNIS
        else -> Sport.FOOTBALL
    }

    private fun normalizeMatchKey(home: String, away: String): String =
        "${home.trim().lowercase(Locale.US).replace(WHITESPACE_REGEX, " ")}::${away.trim().lowercase(Locale.US).replace(WHITESPACE_REGEX, " ")}"

    private fun isBlockedNonSportsMarket(question: String): Boolean {
        val q = question.lowercase(Locale.US)
        val blocked = listOf(
            "trump", "biden", "xi jinping", "democrat", "republican", "election", "president",
            "senate", "congress", "governor", "nominee", "primary", "fed ", "interest rate",
            "tariff", "bitcoin", "btc", "ethereum", "crypto", "inflation", "recession", "ceasefire", "gaza", "nato", "supreme court", "cabinet", "pardon", "oscar", "grammy",
            "cpi", "gdp", "unemployment", "s&p", "nasdaq", "box office", "temperature",
        )
        return blocked.any { it in q }
    }

    internal fun isSportsMarketQuestion(question: String, extraMeta: String = ""): Boolean {
        if (isBlockedNonSportsMarket(question)) return false
        val combined = "$question $extraMeta".lowercase(Locale.US)
        return SPORTS_KEYWORD_REGEX.containsMatchIn(combined)
    }

    internal fun sanitizeError(e: Throwable): String {
        val raw = listOfNotNull(e.message, e.cause?.message)
            .firstOrNull { it.isNotBlank() }
            ?: e::class.simpleName
            ?: "Unknown error"
        return raw.replace(SECRET_QUERY_REGEX, "$1=REDACTED").take(120)
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.booleanOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    companion object {
        const val PROVIDER_THE_ODDS_API = "The Odds API"
        const val PROVIDER_FOOTBALL_DATA = "Football-Data.org"
        const val PROVIDER_ODDSPAPI = "OddsPapi"
        const val PROVIDER_CRICKET_DATA = "CricketData.org"
        const val PROVIDER_API_SPORTS = "API-Sports"
        const val PROVIDER_POLYMARKET = "Polymarket"
        const val PROVIDER_KALSHI = "Kalshi"
        const val PROVIDER_MANIFOLD = "Manifold Markets"
        const val PROVIDER_BALLDONTLIE = "Balldontlie.io"
        const val PROVIDER_LIVE_TENNIS = "Live Tennis API"
        const val PROVIDER_OPENWEATHER = "OpenWeatherMap"
        const val TTL_30_MIN_MS = 30L * 60_000L
        const val TTL_6_HOURS_MS = 6L * 3_600_000L
        private val KALSHI_SPORTS_SERIES = listOf(
            "KXNFLGAME", "KXNBAGAME", "KXNHLGAME", "KXMLBGAME",
            "KXEPLGAME", "KXUCLGAME", "KXUFCFIGHT",
        )
        const val TTL_12_HOURS_MS = 12L * 3_600_000L

        private val SHARP_BOOK_KEYS = setOf(
            "pinnacle",
            "betfair_ex_eu",
            "betfair_ex_uk",
            "betfair_ex_au",
            "betfair",
            "matchbook",
            "smarkets",
            "bookmaker",
            "lowvig",
            "circa",
            "betcris",
            "polymarket",
            "kalshi",
        )

        private val FOOTBALL_DATA_CODE_MAP = mapOf(
            "PL" to "E0",
            "ELC" to "E1",
            "PD" to "SP1",
            "BL1" to "D1",
            "SA" to "I1",
            "FL1" to "F1",
        )

        private val EXCLUDED_FOOTBALL_STATUSES = setOf(
            "FINISHED",
            "CANCELLED",
            "POSTPONED",
            "AWARDED",
            "SUSPENDED",
        )

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SECRET_QUERY_REGEX = Regex("(?i)(apiKey|apikey|token|key)(=|%3D)[^&\\s]+")
        private val SPORTS_KEYWORD_REGEX = Regex("\\b(nba|nfl|nhl|mlb|wnba|mls|ncaa|march madness|premier league|champions league|europa league|fa cup|la liga|bundesliga|serie a|ligue 1|world cup|super bowl|stanley cup|world series|nba finals|ufc|mma|boxing|formula 1|grand prix|atp|wta|wimbledon|us open|pga|golf|rugby|cricket|ipl|kxnfl|kxnba|kxnhl|kxmlb|kxepl|kxucl|kxufc|kxatp|kxf1|kxsoccer|playoffs|touchdown|home run)\\b")
    }
}
