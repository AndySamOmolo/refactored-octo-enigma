package com.edgeledger.app.data

import com.edgeledger.app.engine.CsvAndFeedParsers
import com.edgeledger.app.engine.RawUpcomingEvent
import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.Sport
import kotlinx.coroutines.CancellationException
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
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val callsByProvider = ConcurrentHashMap<String, AtomicInteger>()
    private val statusByProvider = ConcurrentHashMap<String, String>()

    private fun sanitizeKey(raw: String): String =
        raw.trim().filter { it in ' '..'~' }

    fun fetchTheOddsApi(
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
                .addPathSegment("")
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

            incrementCalls(PROVIDER_THE_ODDS_API)
            client.newCall(request).execute().use { response ->
                val remaining = response.header("x-requests-remaining")
                val usedHeader = response.header("x-requests-used")?.toIntOrNull()
                if (usedHeader != null) {
                    callsByProvider.computeIfAbsent(PROVIDER_THE_ODDS_API) { AtomicInteger(0) }.set(usedHeader)
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

                val quotaNote = remaining?.let { " · $it credits left" }.orEmpty()
                setStatus(PROVIDER_THE_ODDS_API, "OK (${result.size} events$quotaNote)")
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

    fun fetchFootballDataOrgFixtures(apiKey: String): List<RawUpcomingEvent> {
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

            val request = Request.Builder()
                .url(url)
                .header("X-Auth-Token", cleanKey)
                .header("Accept", "application/json")
                .get()
                .build()

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

    fun fetchPolymarketOdds(): List<Pair<String, Double>> {
        return try {
            val request = Request.Builder()
                .url("https://gamma-api.polymarket.com/markets?closed=false&limit=60")
                .header("Accept", "application/json")
                .get()
                .build()

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
                    if (question.isBlank() || !isSportsMarketQuestion(question)) continue

                    val yesProb = extractPolymarketYesProbability(marketObj) ?: continue
                    if (yesProb.isFinite() && yesProb in 0.01..0.99) {
                        pairs.add(question to yesProb)
                    }
                }

                setStatus(PROVIDER_POLYMARKET, "OK (${pairs.size} sports markets)")
                pairs
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_POLYMARKET, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_POLYMARKET, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    fun fetchCricketMatches(apiKey: String): List<RawUpcomingEvent> {
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

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

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

    fun fetchKalshiOdds(): List<Pair<String, Double>> {
        return try {
            val request = Request.Builder()
                .url("https://api.elections.kalshi.com/trade-api/v2/markets?status=open&limit=60")
                .header("Accept", "application/json")
                .get()
                .build()

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
                    val title = marketObj.stringOrNull("title")?.takeIf { it.isNotBlank() }
                        ?: marketObj.stringOrNull("ticker").orEmpty()
                    if (title.isBlank() || !isSportsMarketQuestion(title)) continue

                    val yesProb = extractKalshiYesProbability(marketObj) ?: continue
                    if (yesProb.isFinite() && yesProb in 0.01..0.99) {
                        pairs.add(title.trim() to yesProb)
                    }
                }

                setStatus(PROVIDER_KALSHI, "OK (${pairs.size} sports markets)")
                pairs
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            setStatus(PROVIDER_KALSHI, "Network error: ${sanitizeError(e)}")
            emptyList()
        } catch (e: Exception) {
            setStatus(PROVIDER_KALSHI, "Parse error: ${sanitizeError(e)}")
            emptyList()
        }
    }

    fun fetchOddsPapiFixtures(apiKey: String): List<JsonObject> {
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

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

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

    fun fetchApiSportsJson(
        apiKey: String,
        endpointUrl: String = "https://v3.football.api-sports.io/status",
    ): JsonObject? {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            setStatus(PROVIDER_API_SPORTS, "Not configured")
            return null
        }

        val parsedUrl = endpointUrl.toHttpUrlOrNull()
        if (parsedUrl == null || parsedUrl.scheme != "https" || !parsedUrl.host.endsWith(".api-sports.io")) {
            setStatus(PROVIDER_API_SPORTS, "Blocked: untrusted endpoint")
            return null
        }

        return try {
            val request = Request.Builder()
                .url(parsedUrl)
                .header("x-apisports-key", cleanKey)
                .header("Accept", "application/json")
                .get()
                .build()

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

    fun getQuotaSummary(keys: UserApiKeys): List<ProviderQuotaStatus> = listOf(
        buildQuotaStatus(PROVIDER_THE_ODDS_API, "500 credits/mo", keys.theOddsApiKey.isNotBlank()),
        buildQuotaStatus(PROVIDER_FOOTBALL_DATA, "10 calls/min (Free Forever)", keys.footballDataOrgKey.isNotBlank()),
        buildQuotaStatus(PROVIDER_ODDSPAPI, "250 req/mo", keys.oddsPapiKey.isNotBlank()),
        buildQuotaStatus(PROVIDER_CRICKET_DATA, "100 hits/day", keys.cricketDataKey.isNotBlank()),
        buildQuotaStatus(PROVIDER_API_SPORTS, "100 req/day per sport", keys.apiSportsKey.isNotBlank()),
        buildQuotaStatus(PROVIDER_POLYMARKET, "Keyless (~300 req/10s)", keys.enablePolymarket),
        buildQuotaStatus(PROVIDER_KALSHI, "Keyless (public REST)", keys.enableKalshi),
    )

    private fun buildQuotaStatus(provider: String, freeLimitLabel: String, configured: Boolean): ProviderQuotaStatus {
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
    private fun extractKalshiYesProbability(marketObj: JsonObject): Double? {
        val yesBid = marketObj.doubleOrNull("yes_bid")?.takeIf { it.isFinite() }
        val yesAsk = marketObj.doubleOrNull("yes_ask")?.takeIf { it.isFinite() }
        if (yesBid != null && yesAsk != null && yesBid > 0.0 && yesAsk > 0.0 && (yesAsk - yesBid) in 0.0..20.0) {
            return ((yesBid + yesAsk) / 2.0) / 100.0
        }
        val lastPrice = marketObj.doubleOrNull("last_price")?.takeIf { it.isFinite() && it in 1.0..99.0 } ?: return null
        return lastPrice / 100.0
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

    internal fun isSportsMarketQuestion(question: String): Boolean {
        val q = question.lowercase(Locale.US)
        val blocked = listOf(
            "trump", "biden", "xi jinping", "democrat", "republican", "election", "president",
            "senate", "congress", "governor", "nominee", "primary", "fed ", "interest rate",
            "tariff", "bitcoin", "btc", "ethereum", "crypto", "inflation", "recession", "ukraine",
            "china", "israel", "gaza", "nato", "supreme court", "cabinet", "pardon", "oscar", "grammy",
        )
        if (blocked.any { it in q }) return false
        val allowed = listOf(
            "nba", "nfl", "nhl", "mlb", "premier league", "champions league", "la liga", "bundesliga",
            "serie a", "ligue 1", "world cup", "super bowl", "stanley cup", "world series",
            "ufc", "mma", "formula 1", "f1", "grand prix", "atp", "wta", "wimbledon", "us open",
            "cricket", "ipl", " vs ", " vs. ", "beat ", "playoffs", "mvp", "fc ", "united", "city",
        )
        return allowed.any { it in q }
    }

    internal fun sanitizeError(e: Throwable): String {
        val raw = e.message ?: e::class.simpleName ?: "Unknown error"
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
        private val SECRET_QUERY_REGEX = Regex("(?i)(apiKey|apikey|token|key)=[^&\\s]+")
    }
}
