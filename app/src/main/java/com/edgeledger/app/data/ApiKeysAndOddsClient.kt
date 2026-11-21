package com.edgeledger.app.data

import com.edgeledger.app.engine.RawUpcomingEvent
import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.Sport
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Optional user-supplied free-tier API keys and keyless prediction-market toggles.
 * Persist in Android Keystore-backed encrypted preferences; [toString] redacts all secrets.
 */
@Serializable
data class UserApiKeys(
    val theOddsApiKey: String = "",
    val footballDataOrgKey: String = "",
    val oddsPapiKey: String = "",
    val cricketDataKey: String = "",
    val apiSportsKey: String = "",
    val enablePolymarket: Boolean = true,
    val enableKalshi: Boolean = true,
) {
    fun hasAnyApiKey(): Boolean =
        theOddsApiKey.isNotBlank() ||
            footballDataOrgKey.isNotBlank() ||
            oddsPapiKey.isNotBlank() ||
            cricketDataKey.isNotBlank() ||
            apiSportsKey.isNotBlank()

    override fun toString(): String =
        "UserApiKeys(" +
            "theOddsApiKey=${maskSecret(theOddsApiKey)}, " +
            "footballDataOrgKey=${maskSecret(footballDataOrgKey)}, " +
            "oddsPapiKey=${maskSecret(oddsPapiKey)}, " +
            "cricketDataKey=${maskSecret(cricketDataKey)}, " +
            "apiSportsKey=${maskSecret(apiSportsKey)}, " +
            "enablePolymarket=$enablePolymarket, " +
            "enableKalshi=$enableKalshi)"

    private fun maskSecret(secret: String): String =
        if (secret.isBlank()) "<empty>" else "<configured:${secret.length}ch>"
}

/**
 * Per-provider quota and health snapshot surfaced in the API Health & Quota screen.
 */
@Serializable
data class ProviderQuotaStatus(
    val provider: String,
    val callsUsed: Int,
    val freeLimitLabel: String,
    val configured: Boolean,
    val lastStatus: String,
)

/**
 * Safe, non-crashing OkHttp 4.12 + kotlinx.serialization client for optional user-entered
 * free-tier sports/odds APIs and keyless prediction markets.
 *
 * Call all network methods off the Main thread (e.g. `Dispatchers.IO` or WorkManager).
 */
class OptionalOddsAndDataClient(
    private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val callsByProvider = ConcurrentHashMap<String, AtomicInteger>()
    private val statusByProvider = ConcurrentHashMap<String, String>()

    /**
     * Fetches pre-match 1X2 / H2H decimal odds from The Odds API v4.
     *
     * Endpoint:
     * `https://api.the-odds-api.com/v4/sports/{sportKey}/odds/?apiKey={key}&regions=eu,us&markets=h2h&oddsFormat=decimal`
     *
     * @return Map keyed by normalized lowercase `"$home::$away"` to bookmaker odds lines,
     *         tagging sharp books/exchanges (Pinnacle, Betfair, Matchbook, etc.) with `isSharp = true`.
     */
    fun fetchTheOddsApi(
        sportKey: String,
        apiKey: String,
    ): Map<String, List<BookmakerOddsLine>> {
        val cleanKey = apiKey.trim()
        val cleanSport = sportKey.trim()
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_THE_ODDS_API, "Not configured")
            return emptyMap()
        }
        if (cleanSport.isBlank()) {
            setStatus(PROVIDER_THE_ODDS_API, "Skipped: empty sportKey")
            return emptyMap()
        }

        val url = "https://api.the-odds-api.com/v4/sports/"
            .toHttpUrl()
            .newBuilder()
            .addPathSegment(cleanSport)
            .addPathSegment("odds")
            .addPathSegment("") // trailing slash matches /odds/
            .addQueryParameter("apiKey", cleanKey)
            .addQueryParameter("regions", "eu,us")
            .addQueryParameter("markets", "h2h")
            .addQueryParameter("oddsFormat", "decimal")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_THE_ODDS_API)
            client.newCall(request).execute().use { response ->
                val remaining = response.header("x-requests-remaining")
                val usedHeader = response.header("x-requests-used")?.toIntOrNull()
                if (usedHeader != null) {
                    callsByProvider.computeIfAbsent(PROVIDER_THE_ODDS_API) { AtomicInteger(0) }
                        .set(usedHeader)
                }

                if (!response.isSuccessful) {
                    setStatus(
                        PROVIDER_THE_ODDS_API,
                        "HTTP ${response.code}${remaining?.let { " ($it credits left)" }.orEmpty()}",
                    )
                    return emptyMap()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_THE_ODDS_API, "Empty response")
                    return emptyMap()
                }

                val root = json.parseToJsonElement(body) as? JsonArray ?: run {
                    setStatus(PROVIDER_THE_ODDS_API, "Unexpected JSON payload")
                    return emptyMap()
                }

                val result = LinkedHashMap<String, List<BookmakerOddsLine>>()
                for (eventElem in root) {
                    val eventObj = eventElem as? JsonObject ?: continue
                    val homeTeam = eventObj.stringOrNull("home_team")?.trim().orEmpty()
                    val awayTeam = eventObj.stringOrNull("away_team")?.trim().orEmpty()
                    if (homeTeam.isBlank() || awayTeam.isBlank()) continue

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
                            if (price <= 1.0) continue

                            when {
                                name.equals(homeTeam, ignoreCase = true) -> homeOdds = price
                                name.equals(awayTeam, ignoreCase = true) -> awayOdds = price
                                name.equals("Draw", ignoreCase = true) ||
                                    name.equals("Tie", ignoreCase = true) -> drawOdds = price
                            }
                        }

                        val h = homeOdds
                        val a = awayOdds
                        if (h != null && a != null) {
                            lines.add(
                                BookmakerOddsLine(
                                    bookmaker = bookTitle,
                                    isSharp = isSharpBookmaker(bookKey, bookTitle),
                                    homeOdds = h,
                                    drawOdds = drawOdds,
                                    awayOdds = a,
                                    updatedLabel = updatedLabel,
                                ),
                            )
                        }
                    }

                    if (lines.isNotEmpty()) {
                        val matchKey = normalizeMatchKey(homeTeam, awayTeam)
                        // Sharp books first for downstream fair-value & CLV consumers
                        result[matchKey] = lines.sortedByDescending { it.isSharp }
                    }
                }

                val quotaNote = remaining?.let { " · $it credits left" }.orEmpty()
                setStatus(PROVIDER_THE_ODDS_API, "OK (${result.size} events$quotaNote)")
                result
            }
        } catch (e: IOException) {
            setStatus(PROVIDER_THE_ODDS_API, "Network error: ${sanitizeError(e)}")
            emptyMap()
        } catch (e: Exception) {
            setStatus(PROVIDER_THE_ODDS_API, "Parse error: ${sanitizeError(e)}")
            emptyMap()
        }
    }

    /**
     * Fetches upcoming football fixtures from Football-Data.org v4.
     *
     * Endpoint: `https://api.football-data.org/v4/matches` with `X-Auth-Token` header.
     * Attribution: Football data provided by the Football-Data.org API.
     */
    fun fetchFootballDataOrgFixtures(apiKey: String): List<RawUpcomingEvent> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_FOOTBALL_DATA, "Not configured")
            return emptyList()
        }

        val request = Request.Builder()
            .url("https://api.football-data.org/v4/matches")
            .header("X-Auth-Token", cleanKey)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_FOOTBALL_DATA)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    setStatus(PROVIDER_FOOTBALL_DATA, "HTTP ${response.code}")
                    return emptyList()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_FOOTBALL_DATA, "Empty response")
                    return emptyList()
                }

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
                    val leagueId = compObj?.stringOrNull("code")
                        ?: compObj?.stringOrNull("id")
                        ?: "football_data"
                    val leagueName = compObj?.stringOrNull("name") ?: leagueId

                    val homeObj = matchObj.objectOrNull("homeTeam")
                    val awayObj = matchObj.objectOrNull("awayTeam")
                    val homeTeam = homeObj?.stringOrNull("name")
                        ?: homeObj?.stringOrNull("shortName")
                        ?: continue
                    val awayTeam = awayObj?.stringOrNull("name")
                        ?: awayObj?.stringOrNull("shortName")
                        ?: continue

                    if (homeTeam.isBlank() || awayTeam.isBlank()) continue

                    events.add(
                        RawUpcomingEvent(
                            id = "fdo_$matchId",
                            sport = Sport.FOOTBALL,
                            leagueId = leagueId,
                            leagueName = leagueName,
                            kickoffIso = kickoffIso,
                            homeTeam = homeTeam.trim(),
                            awayTeam = awayTeam.trim(),
                            surface = "",
                            bookOdds = emptyList(),
                        ),
                    )
                }

                setStatus(PROVIDER_FOOTBALL_DATA, "OK (${events.size} fixtures)")
                events
            }
        } catch (e: IOException) {
            setStatus(PROVIDER_FOOTBALL_DATA, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_FOOTBALL_DATA, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    /**
     * Fetches open binary prediction markets from Polymarket's keyless Gamma API.
     *
     * Endpoint: `https://gamma-api.polymarket.com/markets?closed=false&limit=30`
     *
     * @return List of `(marketQuestion, impliedYesProbability)` pairs where probability is in `[0.0, 1.0]`.
     */
    fun fetchPolymarketOdds(): List<Pair<String, Double>> {
        val request = Request.Builder()
            .url("https://gamma-api.polymarket.com/markets?closed=false&limit=30")
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_POLYMARKET)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    setStatus(PROVIDER_POLYMARKET, "HTTP ${response.code}")
                    return emptyList()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_POLYMARKET, "Empty response")
                    return emptyList()
                }

                val markets = json.parseToJsonElement(body) as? JsonArray ?: run {
                    setStatus(PROVIDER_POLYMARKET, "Unexpected JSON payload")
                    return emptyList()
                }

                val pairs = mutableListOf<Pair<String, Double>>()
                for (marketElem in markets) {
                    val marketObj = marketElem as? JsonObject ?: continue
                    val question = marketObj.stringOrNull("question")?.trim().orEmpty()
                    if (question.isBlank()) continue

                    val yesProb = extractPolymarketYesProbability(marketObj) ?: continue
                    if (yesProb in 0.0..1.0) {
                        pairs.add(question to yesProb)
                    }
                }

                setStatus(PROVIDER_POLYMARKET, "OK (${pairs.size} markets)")
                pairs
            }
        } catch (e: IOException) {
            setStatus(PROVIDER_POLYMARKET, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_POLYMARKET, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    /**
     * Fetches current and upcoming cricket matches from CricketData.org (CricAPI v1).
     *
     * Endpoint: `https://api.cricapi.com/v1/currentMatches?apikey={key}&offset=0`
     */
    fun fetchCricketMatches(apiKey: String): List<RawUpcomingEvent> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_CRICKET_DATA, "Not configured")
            return emptyList()
        }

        val url = "https://api.cricapi.com/v1/currentMatches"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("apikey", cleanKey)
            .addQueryParameter("offset", "0")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_CRICKET_DATA)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    setStatus(PROVIDER_CRICKET_DATA, "HTTP ${response.code}")
                    return emptyList()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_CRICKET_DATA, "Empty response")
                    return emptyList()
                }

                val root = json.parseToJsonElement(body) as? JsonObject ?: run {
                    setStatus(PROVIDER_CRICKET_DATA, "Unexpected JSON payload")
                    return emptyList()
                }

                val apiStatus = root.stringOrNull("status").orEmpty()
                if (apiStatus.equals("failure", ignoreCase = true)) {
                    val reason = root.stringOrNull("reason") ?: "API failure"
                    setStatus(PROVIDER_CRICKET_DATA, reason)
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
                    if (teams.size < 2) continue

                    val matchType = matchObj.stringOrNull("matchType")
                        ?.trim()
                        ?.lowercase(Locale.US)
                        .orEmpty()
                    val leagueId = if (matchType.isNotBlank()) "cricket_$matchType" else "cricket"
                    val leagueName = matchObj.stringOrNull("name")
                        ?.substringAfter(",", "")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: matchType.uppercase(Locale.US).ifBlank { "Cricket" }

                    val kickoffIso = matchObj.stringOrNull("dateTimeGMT")
                        ?.takeIf { it.isNotBlank() }
                        ?: matchObj.stringOrNull("date").orEmpty()
                    val venue = matchObj.stringOrNull("venue").orEmpty()

                    events.add(
                        RawUpcomingEvent(
                            id = "cric_$id",
                            sport = Sport.CRICKET,
                            leagueId = leagueId,
                            leagueName = leagueName,
                            kickoffIso = kickoffIso,
                            homeTeam = teams[0],
                            awayTeam = teams[1],
                            surface = venue,
                            bookOdds = emptyList(),
                        ),
                    )
                }

                setStatus(PROVIDER_CRICKET_DATA, "OK (${events.size} matches)")
                events
            }
        } catch (e: IOException) {
            setStatus(PROVIDER_CRICKET_DATA, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_CRICKET_DATA, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    /**
     * Fetches open prediction markets from Kalshi's keyless public market-data REST API.
     *
     * Endpoint: `https://external-api.kalshi.com/trade-api/v2/markets?status=open&limit=25`
     *
     * @return List of `(marketTitle, impliedYesProbability)` pairs where probability is in `[0.0, 1.0]`.
     */
    fun fetchKalshiOdds(): List<Pair<String, Double>> {
        val request = Request.Builder()
            .url("https://external-api.kalshi.com/trade-api/v2/markets?status=open&limit=25")
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_KALSHI)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    setStatus(PROVIDER_KALSHI, "HTTP ${response.code}")
                    return emptyList()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_KALSHI, "Empty response")
                    return emptyList()
                }

                val root = json.parseToJsonElement(body) as? JsonObject ?: run {
                    setStatus(PROVIDER_KALSHI, "Unexpected JSON payload")
                    return emptyList()
                }

                val markets = root.arrayOrNull("markets") ?: run {
                    setStatus(PROVIDER_KALSHI, "OK (0 markets)")
                    return emptyList()
                }

                val pairs = mutableListOf<Pair<String, Double>>()
                for (marketElem in markets) {
                    val marketObj = marketElem as? JsonObject ?: continue
                    val title = marketObj.stringOrNull("title")
                        ?.takeIf { it.isNotBlank() }
                        ?: marketObj.stringOrNull("ticker").orEmpty()
                    if (title.isBlank()) continue

                    val yesProb = extractKalshiYesProbability(marketObj) ?: continue
                    if (yesProb in 0.0..1.0) {
                        pairs.add(title.trim() to yesProb)
                    }
                }

                setStatus(PROVIDER_KALSHI, "OK (${pairs.size} markets)")
                pairs
            }
        } catch (e: IOException) {
            setStatus(PROVIDER_KALSHI, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_KALSHI, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    /**
     * Fetches pre-game fixtures from OddsPapi v4 (supports sharp books like Pinnacle/Betfair).
     *
     * Endpoint: `https://api.oddspapi.io/v4/fixtures?apiKey={key}`
     *
     * @return Raw JSON array elements (or empty list on error) while updating OddsPapi quota status.
     */
    fun fetchOddsPapiFixtures(apiKey: String): List<JsonObject> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_ODDSPAPI, "Not configured")
            return emptyList()
        }

        val url = "https://api.oddspapi.io/v4/fixtures"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("apiKey", cleanKey)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_ODDSPAPI)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    setStatus(PROVIDER_ODDSPAPI, "HTTP ${response.code}")
                    return emptyList()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_ODDSPAPI, "Empty response")
                    return emptyList()
                }

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
        } catch (e: IOException) {
            setStatus(PROVIDER_ODDSPAPI, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_ODDSPAPI, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    /**
     * Performs a lightweight authenticated GET against an API-Sports endpoint using `x-apisports-key`.
     * Defaults to the free `/status` endpoint on API-Football v3 (0 quota cost).
     */
    fun fetchApiSportsJson(
        apiKey: String,
        endpointUrl: String = "https://v3.football.api-sports.io/status",
    ): JsonObject? {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_API_SPORTS, "Not configured")
            return null
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .header("x-apisports-key", cleanKey)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            incrementCalls(PROVIDER_API_SPORTS)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    setStatus(PROVIDER_API_SPORTS, "HTTP ${response.code}")
                    return null
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    setStatus(PROVIDER_API_SPORTS, "Empty response")
                    return null
                }

                val obj = json.parseToJsonElement(body) as? JsonObject
                setStatus(PROVIDER_API_SPORTS, if (obj != null) "OK" else "Unexpected JSON payload")
                obj
            }
        } catch (e: IOException) {
            setStatus(PROVIDER_API_SPORTS, "Network error: ${sanitizeError(e)}")
            null
        } catch (e: Exception) {
            setStatus(PROVIDER_API_SPORTS, "Parse error: ${sanitizeError(e)}")
            null
        }
    }

    /**
     * Returns the current quota and health summary for every supported optional provider.
     */
    fun getQuotaSummary(keys: UserApiKeys): List<ProviderQuotaStatus> = listOf(
        buildQuotaStatus(
            provider = PROVIDER_THE_ODDS_API,
            freeLimitLabel = "500 credits/mo",
            configured = keys.theOddsApiKey.isNotBlank(),
        ),
        buildQuotaStatus(
            provider = PROVIDER_FOOTBALL_DATA,
            freeLimitLabel = "10 calls/min (Free Forever)",
            configured = keys.footballDataOrgKey.isNotBlank(),
        ),
        buildQuotaStatus(
            provider = PROVIDER_ODDSPAPI,
            freeLimitLabel = "250 req/mo",
            configured = keys.oddsPapiKey.isNotBlank(),
        ),
        buildQuotaStatus(
            provider = PROVIDER_CRICKET_DATA,
            freeLimitLabel = "100 hits/day",
            configured = keys.cricketDataKey.isNotBlank(),
        ),
        buildQuotaStatus(
            provider = PROVIDER_API_SPORTS,
            freeLimitLabel = "100 req/day per sport",
            configured = keys.apiSportsKey.isNotBlank(),
        ),
        buildQuotaStatus(
            provider = PROVIDER_POLYMARKET,
            freeLimitLabel = "Keyless (~300 req/10s)",
            configured = keys.enablePolymarket,
        ),
        buildQuotaStatus(
            provider = PROVIDER_KALSHI,
            freeLimitLabel = "Keyless (public REST)",
            configured = keys.enableKalshi,
        ),
    )

    // -------------------------------------------------------------------------
    // Internal parsing & normalization helpers
    // -------------------------------------------------------------------------

    private fun buildQuotaStatus(
        provider: String,
        freeLimitLabel: String,
        configured: Boolean,
    ): ProviderQuotaStatus {
        val calls = callsByProvider[provider]?.get() ?: 0
        val defaultStatus = if (configured) "Ready" else "Disabled / No key"
        return ProviderQuotaStatus(
            provider = provider,
            callsUsed = calls,
            freeLimitLabel = freeLimitLabel,
            configured = configured,
            lastStatus = statusByProvider[provider] ?: defaultStatus,
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
            .mapNotNull { it.toDoubleOrNull() }

        if (prices.isNotEmpty()) {
            val yesIndex = outcomes.indexOfFirst { it.equals("Yes", ignoreCase = true) }
            val resolvedIndex = if (yesIndex in prices.indices) yesIndex else 0
            return prices[resolvedIndex]
        }

        // Fallback to order-book midpoint or last trade price when outcomePrices is absent
        val bestBid = marketObj.doubleOrNull("bestBid")
        val bestAsk = marketObj.doubleOrNull("bestAsk")
        if (bestBid != null && bestAsk != null && bestBid in 0.0..1.0 && bestAsk in 0.0..1.0) {
            return (bestBid + bestAsk) / 2.0
        }
        return marketObj.doubleOrNull("lastTradePrice")
    }

    private fun extractKalshiYesProbability(marketObj: JsonObject): Double? {
        // Kalshi v2 returns prices in cents (0..100) or dollar strings (e.g. "0.54")
        val yesBid = marketObj.doubleOrNull("yes_bid")
        val yesAsk = marketObj.doubleOrNull("yes_ask")
        if (yesBid != null && yesAsk != null && (yesBid > 0.0 || yesAsk > 0.0)) {
            val mid = (yesBid + yesAsk) / 2.0
            return if (mid > 1.0) mid / 100.0 else mid
        }
        val lastPrice = marketObj.doubleOrNull("last_price") ?: return null
        return if (lastPrice > 1.0) lastPrice / 100.0 else lastPrice
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

    private fun isSharpBookmaker(key: String, title: String): Boolean {
        val normalizedKey = key.trim().lowercase(Locale.US)
        val normalizedTitle = title.trim().lowercase(Locale.US)
        return SHARP_BOOK_KEYWORDS.any { sharp ->
            normalizedKey.contains(sharp) || normalizedTitle.contains(sharp)
        }
    }

    private fun normalizeMatchKey(home: String, away: String): String =
        "${normalizeTeamName(home)}::${normalizeTeamName(away)}"

    private fun normalizeTeamName(name: String): String =
        name.trim().lowercase(Locale.US).replace(WHITESPACE_REGEX, " ")


    private fun isSportsMarketQuestion(question: String): Boolean {
        val q = question.lowercase(Locale.US)
        val blocked = listOf(
            "trump", "biden", "xi jinping", "democrat", "republican", "election", "president",
            "senate", "congress", "governor", "nominee", "primary", "fed ", "interest rate",
            "tariff", "bitcoin", "btc", "ethereum", "crypto", "inflation", "recession", "ukraine",
            "china", "israel", "gaza", "nato", "supreme court", "cabinet", "pardon", "oscar", "grammy"
        )
        if (blocked.any { it in q }) return false
        val allowed = listOf(
            "nba", "nfl", "nhl", "mlb", "premier league", "champions league", "la liga", "bundesliga",
            "serie a", "ligue 1", "world cup", "super bowl", "stanley cup", "world series",
            "ufc", "mma", "formula 1", "f1", "grand prix", "atp", "wta", "wimbledon", "us open",
            "cricket", "ipl", " vs ", " vs. ", "beat ", "playoffs", "mvp", "fc ", "united", "city"
        )
        return allowed.any { it in q }
    }

    private fun sanitizeError(e: Throwable): String {
        val raw = e.message?.take(120) ?: e::class.simpleName ?: "Unknown error"
        return raw.replace(SECRET_QUERY_REGEX, "$1=REDACTED")
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        this[key] as? JsonArray

    companion object {
        const val PROVIDER_THE_ODDS_API = "The Odds API"
        const val PROVIDER_FOOTBALL_DATA = "Football-Data.org"
        const val PROVIDER_ODDSPAPI = "OddsPapi"
        const val PROVIDER_CRICKET_DATA = "CricketData.org"
        const val PROVIDER_API_SPORTS = "API-Sports"
        const val PROVIDER_POLYMARKET = "Polymarket"
        const val PROVIDER_KALSHI = "Kalshi"

        private val SHARP_BOOK_KEYWORDS = setOf(
            "pinnacle",
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

        private val EXCLUDED_FOOTBALL_STATUSES = setOf(
            "FINISHED",
            "CANCELLED",
            "POSTPONED",
            "AWARDED",
            "SUSPENDED",
        )

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SECRET_QUERY_REGEX = Regex("(?i)(apiKey|apikey|token|key)=[^&\\s]+")
    }
}
