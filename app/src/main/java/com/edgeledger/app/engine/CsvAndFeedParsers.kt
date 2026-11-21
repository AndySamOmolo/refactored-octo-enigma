package com.edgeledger.app.engine

import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.HistoricalMatch
import com.edgeledger.app.model.Sport
import com.edgeledger.app.model.TeamProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

data class RawUpcomingEvent(
    val id: String,
    val sport: Sport,
    val leagueId: String,
    val leagueName: String,
    val kickoffIso: String,
    val homeTeam: String,
    val awayTeam: String,
    val surface: String? = null,
    val bookOdds: List<BookmakerOddsLine> = emptyList(),
)

object CsvAndFeedParsers {
    private val json = Json { ignoreUnknownKeys = true }

    private val FOOTBALL_ALIASES = mapOf(
        "man city" to "Manchester City",
        "man united" to "Manchester United",
        "man utd" to "Manchester United",
        "nott'm forest" to "Nottingham Forest",
        "wolves" to "Wolverhampton",
        "spurs" to "Tottenham",
        "tottenham hotspur" to "Tottenham",
        "newcastle" to "Newcastle United",
        "west ham" to "West Ham United",
        "ath madrid" to "Atletico Madrid",
        "bayern munich" to "Bayern Munich",
        "bayern münchen" to "Bayern Munich",
        "dortmund" to "Borussia Dortmund",
        "leverkusen" to "Bayer Leverkusen",
        "inter" to "Inter Milan",
        "internazionale" to "Inter Milan",
        "milan" to "AC Milan",
        "paris sg" to "Paris Saint-Germain",
        "psg" to "Paris Saint-Germain",
    )

    // Complete 32-team NFL map covering nflverse codes, ESPN codes, and legacy franchise codes
    val NFL_ABBREV_TO_FULL: Map<String, String> = mapOf(
        "ARI" to "Arizona Cardinals",
        "ATL" to "Atlanta Falcons",
        "BAL" to "Baltimore Ravens",
        "BUF" to "Buffalo Bills",
        "CAR" to "Carolina Panthers",
        "CHI" to "Chicago Bears",
        "CIN" to "Cincinnati Bengals",
        "CLE" to "Cleveland Browns",
        "DAL" to "Dallas Cowboys",
        "DEN" to "Denver Broncos",
        "DET" to "Detroit Lions",
        "GB" to "Green Bay Packers",
        "GNB" to "Green Bay Packers",
        "HOU" to "Houston Texans",
        "IND" to "Indianapolis Colts",
        "JAX" to "Jacksonville Jaguars",
        "JAC" to "Jacksonville Jaguars",
        "KC" to "Kansas City Chiefs",
        "KAN" to "Kansas City Chiefs",
        "LA" to "Los Angeles Rams",
        "LAR" to "Los Angeles Rams",
        "STL" to "Los Angeles Rams",
        "LAC" to "Los Angeles Chargers",
        "SD" to "Los Angeles Chargers",
        "LV" to "Las Vegas Raiders",
        "LVR" to "Las Vegas Raiders",
        "OAK" to "Las Vegas Raiders",
        "MIA" to "Miami Dolphins",
        "MIN" to "Minnesota Vikings",
        "NE" to "New England Patriots",
        "NWE" to "New England Patriots",
        "NO" to "New Orleans Saints",
        "NOR" to "New Orleans Saints",
        "NYG" to "New York Giants",
        "NYJ" to "New York Jets",
        "PHI" to "Philadelphia Eagles",
        "PIT" to "Pittsburgh Steelers",
        "SEA" to "Seattle Seahawks",
        "SF" to "San Francisco 49ers",
        "SFO" to "San Francisco 49ers",
        "TB" to "Tampa Bay Buccaneers",
        "TAM" to "Tampa Bay Buccaneers",
        "TEN" to "Tennessee Titans",
        "WAS" to "Washington Commanders",
        "WSH" to "Washington Commanders",
    )

    // Complete 32-team NHL official 3-letter abbreviation map (including Utah Mammoth UTA)
    val NHL_ABBREV_TO_FULL: Map<String, String> = mapOf(
        "ANA" to "Anaheim Ducks",
        "BOS" to "Boston Bruins",
        "BUF" to "Buffalo Sabres",
        "CGY" to "Calgary Flames",
        "CAR" to "Carolina Hurricanes",
        "CHI" to "Chicago Blackhawks",
        "COL" to "Colorado Avalanche",
        "CBJ" to "Columbus Blue Jackets",
        "DAL" to "Dallas Stars",
        "DET" to "Detroit Red Wings",
        "EDM" to "Edmonton Oilers",
        "FLA" to "Florida Panthers",
        "LAK" to "Los Angeles Kings",
        "MIN" to "Minnesota Wild",
        "MTL" to "Montreal Canadiens",
        "NSH" to "Nashville Predators",
        "NJD" to "New Jersey Devils",
        "NYI" to "New York Islanders",
        "NYR" to "New York Rangers",
        "OTT" to "Ottawa Senators",
        "PHI" to "Philadelphia Flyers",
        "PIT" to "Pittsburgh Penguins",
        "SJS" to "San Jose Sharks",
        "SEA" to "Seattle Kraken",
        "STL" to "St. Louis Blues",
        "TBL" to "Tampa Bay Lightning",
        "TOR" to "Toronto Maple Leafs",
        "UTA" to "Utah Mammoth",
        "VAN" to "Vancouver Canucks",
        "VGK" to "Vegas Golden Knights",
        "WSH" to "Washington Capitals",
        "WPG" to "Winnipeg Jets",
    )

    val NHL_CITY_TO_FULL: Map<String, String> = mapOf(
        "anaheim" to "Anaheim Ducks",
        "boston" to "Boston Bruins",
        "buffalo" to "Buffalo Sabres",
        "calgary" to "Calgary Flames",
        "carolina" to "Carolina Hurricanes",
        "chicago" to "Chicago Blackhawks",
        "colorado" to "Colorado Avalanche",
        "columbus" to "Columbus Blue Jackets",
        "dallas" to "Dallas Stars",
        "detroit" to "Detroit Red Wings",
        "edmonton" to "Edmonton Oilers",
        "florida" to "Florida Panthers",
        "los angeles" to "Los Angeles Kings",
        "minnesota" to "Minnesota Wild",
        "montreal" to "Montreal Canadiens",
        "montréal" to "Montreal Canadiens",
        "montréal canadiens" to "Montreal Canadiens",
        "nashville" to "Nashville Predators",
        "new jersey" to "New Jersey Devils",
        "ny islanders" to "New York Islanders",
        "ny rangers" to "New York Rangers",
        "ottawa" to "Ottawa Senators",
        "philadelphia" to "Philadelphia Flyers",
        "pittsburgh" to "Pittsburgh Penguins",
        "san jose" to "San Jose Sharks",
        "seattle" to "Seattle Kraken",
        "st. louis" to "St. Louis Blues",
        "st louis" to "St. Louis Blues",
        "tampa bay" to "Tampa Bay Lightning",
        "toronto" to "Toronto Maple Leafs",
        "utah" to "Utah Mammoth",
        "utah hockey club" to "Utah Mammoth",
        "vancouver" to "Vancouver Canucks",
        "vegas" to "Vegas Golden Knights",
        "washington" to "Washington Capitals",
        "winnipeg" to "Winnipeg Jets",
    )

    fun canonicalTeam(raw: String, sport: Sport? = null): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return ""
        val lower = clean.lowercase()
        val upper = clean.uppercase()
        return when (sport) {
            Sport.NFL -> NFL_ABBREV_TO_FULL[upper] ?: clean
            Sport.NHL -> NHL_ABBREV_TO_FULL[upper] ?: NHL_CITY_TO_FULL[lower] ?: clean
            Sport.FOOTBALL -> FOOTBALL_ALIASES[lower] ?: clean
            null -> FOOTBALL_ALIASES[lower] ?: clean
            else -> clean
        }
    }

    fun parseCsv(text: String): List<Map<String, String>> {
        val cleaned = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < cleaned.length) {
            val ch = cleaned[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < cleaned.length && cleaned[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else field.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> {
                    current.add(field.toString().trim())
                    field.clear()
                }
                ch == '\n' -> {
                    current.add(field.toString().trim())
                    field.clear()
                    if (current.any { it.isNotEmpty() }) rows.add(current.toList())
                    current.clear()
                }
                ch != '\r' -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current.add(field.toString().trim())
            if (current.any { it.isNotEmpty() }) rows.add(current.toList())
        }
        if (rows.size < 2) return emptyList()
        val headers = rows.first()
        return rows.drop(1).map { vals ->
            buildMap {
                headers.forEachIndexed { idx, h -> put(h, vals.getOrNull(idx) ?: "") }
            }
        }
    }

    private fun parseDate(raw: String): String? {
        val s = raw.trim()
        if (Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) return s.take(10)
        val parts = s.split("/")
        if (parts.size != 3) return null
        val dd = parts[0].padStart(2, '0')
        val mm = parts[1].padStart(2, '0')
        val yyyy = if (parts[2].length == 2) "20${parts[2]}" else parts[2]
        return "$yyyy-$mm-$dd"
    }

    private fun dOpt(v: String?): Double? = v?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 1.01 }
    private fun iOpt(v: String?): Int? = v?.toIntOrNull()

    fun parseFootballDataCsv(csv: String, leagueId: String): List<HistoricalMatch> =
        parseCsv(csv).mapNotNull { r ->
            val date = parseDate(r["Date"] ?: "") ?: return@mapNotNull null
            val home = canonicalTeam(r["HomeTeam"] ?: r["Home"] ?: "", Sport.FOOTBALL)
            val away = canonicalTeam(r["AwayTeam"] ?: r["Away"] ?: "", Sport.FOOTBALL)
            val hg = iOpt(r["FTHG"] ?: r["HG"]) ?: return@mapNotNull null
            val ag = iOpt(r["FTAG"] ?: r["AG"]) ?: return@mapNotNull null
            if (home.isBlank() || away.isBlank()) return@mapNotNull null

            HistoricalMatch(
                id = "fd-$leagueId-$date-$home-$away",
                sport = Sport.FOOTBALL,
                leagueId = leagueId,
                dateIso = date,
                homeTeam = home,
                awayTeam = away,
                homeScore = hg,
                awayScore = ag,
                oddsHome = dOpt(r["AvgH"]) ?: dOpt(r["B365H"]) ?: dOpt(r["PSH"]),
                oddsDraw = dOpt(r["AvgD"]) ?: dOpt(r["B365D"]) ?: dOpt(r["PSD"]),
                oddsAway = dOpt(r["AvgA"]) ?: dOpt(r["B365A"]) ?: dOpt(r["PSA"]),
                homeHtGoals = iOpt(r["HTHG"]),
                awayHtGoals = iOpt(r["HTAG"]),
                homeShots = iOpt(r["HS"]),
                awayShots = iOpt(r["AS"]),
                homeSot = iOpt(r["HST"]),
                awaySot = iOpt(r["AST"]),
                homeCorners = iOpt(r["HC"]),
                awayCorners = iOpt(r["AC"]),
                homeFouls = iOpt(r["HF"]),
                awayFouls = iOpt(r["AF"]),
                homeYellows = iOpt(r["HY"]),
                awayYellows = iOpt(r["AY"]),
                homeReds = iOpt(r["HR"]),
                awayReds = iOpt(r["AR"]),
            )
        }

    fun parseClubEloCsv(csv: String): Map<String, Double> = buildMap {
        for (r in parseCsv(csv)) {
            val club = canonicalTeam(r["Club"] ?: "", Sport.FOOTBALL)
            val elo = r["Elo"]?.toDoubleOrNull()
            if (club.isNotBlank() && elo != null) put(club, elo)
        }
    }

    fun parseNflverseGamesCsv(csv: String, minSeason: Int = 2024): List<HistoricalMatch> =
        parseCsv(csv).mapNotNull { r ->
            val season = iOpt(r["season"]) ?: return@mapNotNull null
            if (season < minSeason) return@mapNotNull null
            val date = (r["gameday"] ?: "").take(10)
            val home = canonicalTeam(r["home_team"] ?: "", Sport.NFL)
            val away = canonicalTeam(r["away_team"] ?: "", Sport.NFL)
            val hs = iOpt(r["home_score"]) ?: return@mapNotNull null
            val ascore = iOpt(r["away_score"]) ?: return@mapNotNull null
            if (date.isBlank() || home.isBlank() || away.isBlank()) return@mapNotNull null

            HistoricalMatch(
                id = r["game_id"] ?: "nfl-$date-$home-$away",
                sport = Sport.NFL,
                leagueId = "NFL",
                dateIso = date,
                homeTeam = home,
                awayTeam = away,
                homeScore = hs,
                awayScore = ascore,
                oddsHome = r["home_moneyline"]?.toDoubleOrNull()?.let { OddsMath.americanToDecimal(it) },
                oddsAway = r["away_moneyline"]?.toDoubleOrNull()?.let { OddsMath.americanToDecimal(it) },
                spreadLine = r["spread_line"]?.toDoubleOrNull(),
                totalLine = r["total_line"]?.toDoubleOrNull(),
                homeRestDays = iOpt(r["home_rest"]),
                awayRestDays = iOpt(r["away_rest"]),
            )
        }

    fun parseTmlTennisCsv(csv: String): List<HistoricalMatch> =
        parseCsv(csv).mapIndexedNotNull { idx, r ->
            val w = canonicalTeam(r["winner_name"] ?: "", Sport.TENNIS)
            val l = canonicalTeam(r["loser_name"] ?: "", Sport.TENNIS)
            val rawD = (r["tourney_date"] ?: "").trim()
            if (w.isBlank() || l.isBlank() || rawD.length < 8) return@mapIndexedNotNull null
            val date = "${rawD.substring(0, 4)}-${rawD.substring(4, 6)}-${rawD.substring(6, 8)}"
            val surfRaw = (r["surface"] ?: "Hard").lowercase()
            val surf = when {
                "clay" in surfRaw -> "Clay"
                "grass" in surfRaw -> "Grass"
                else -> "Hard"
            }
            val wIn = r["w_1stIn"]?.toDoubleOrNull()
            val wWon = r["w_1stWon"]?.toDoubleOrNull()
            val lIn = r["l_1stIn"]?.toDoubleOrNull()
            val lWon = r["l_1stWon"]?.toDoubleOrNull()

            HistoricalMatch(
                id = "atp-$date-$idx-$w-$l",
                sport = Sport.TENNIS,
                leagueId = "ATP",
                dateIso = date,
                homeTeam = w,
                awayTeam = l,
                homeScore = 2,
                awayScore = 0,
                surface = surf,
                homeAces = iOpt(r["w_ace"]),
                awayAces = iOpt(r["l_ace"]),
                homeDoubleFaults = iOpt(r["w_df"]),
                awayDoubleFaults = iOpt(r["l_df"]),
                homeFirstServeWonPct = if (wIn != null && wIn > 0 && wWon != null) (wWon / wIn) * 100.0 else null,
                awayFirstServeWonPct = if (lIn != null && lIn > 0 && lWon != null) (lWon / lIn) * 100.0 else null,
            )
        }

    fun parseEspnScoreboard(
        rawJson: String,
        sport: Sport,
        leagueId: String,
        leagueName: String,
    ): Pair<List<HistoricalMatch>, List<RawUpcomingEvent>> {
        val hist = mutableListOf<HistoricalMatch>()
        val up = mutableListOf<RawUpcomingEvent>()
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return hist to up
        val events = root["events"] as? JsonArray ?: return hist to up

        for (el in events) {
            val ev = el as? JsonObject ?: continue
            val id = ev["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val dateIso = ev["date"]?.jsonPrimitive?.contentOrNull ?: ""
            val comp = (ev["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
            val comps = comp["competitors"] as? JsonArray ?: continue
            if (comps.size < 2) continue

            val homeObj = comps.firstOrNull { (it as? JsonObject)?.get("homeAway")?.jsonPrimitive?.contentOrNull == "home" } as? JsonObject
                ?: comps[0] as? JsonObject ?: continue
            val awayObj = comps.firstOrNull { (it as? JsonObject)?.get("homeAway")?.jsonPrimitive?.contentOrNull == "away" } as? JsonObject
                ?: comps[1] as? JsonObject ?: continue

            fun teamName(c: JsonObject): String {
                val t = c["team"] as? JsonObject
                val a = c["athlete"] as? JsonObject
                val raw = t?.get("displayName")?.jsonPrimitive?.contentOrNull
                    ?: a?.get("displayName")?.jsonPrimitive?.contentOrNull
                    ?: t?.get("abbreviation")?.jsonPrimitive?.contentOrNull
                    ?: ""
                return canonicalTeam(raw, sport)
            }

            val hName = teamName(homeObj)
            val aName = teamName(awayObj)
            if (hName.isBlank() || aName.isBlank()) continue

            val completed = (comp["status"] as? JsonObject)
                ?.get("type")?.jsonObject
                ?.get("completed")?.jsonPrimitive?.booleanOrNull == true

            val hScore = homeObj["score"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val aScore = awayObj["score"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

            if (completed && hScore != null && aScore != null) {
                hist.add(
                    HistoricalMatch(
                        id = "espn-$leagueId-$id",
                        sport = sport,
                        leagueId = leagueId,
                        dateIso = dateIso.take(10),
                        homeTeam = hName,
                        awayTeam = aName,
                        homeScore = hScore,
                        awayScore = aScore,
                    )
                )
            } else {
                up.add(
                    RawUpcomingEvent(
                        id = "espn-$leagueId-$id",
                        sport = sport,
                        leagueId = leagueId,
                        leagueName = leagueName,
                        kickoffIso = dateIso,
                        homeTeam = hName,
                        awayTeam = aName,
                        surface = if (sport == Sport.TENNIS) "Hard" else null,
                    )
                )
            }
        }
        return hist to up
    }

    private fun resolveNhlTeamObject(teamObj: JsonObject?): String? {
        if (teamObj == null) return null
        val abbrev = teamObj["abbrev"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        if (!abbrev.isNullOrBlank()) {
            NHL_ABBREV_TO_FULL[abbrev]?.let { return it }
        }
        val place = (teamObj["placeName"] as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull?.trim()
        val common = (teamObj["commonName"] as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull?.trim()
        if (!place.isNullOrBlank() && !common.isNullOrBlank()) {
            return canonicalTeam("$place $common", Sport.NHL)
        }
        if (!place.isNullOrBlank()) {
            return canonicalTeam(place, Sport.NHL)
        }
        return null
    }

    fun parseNhlSchedule(rawJson: String): List<RawUpcomingEvent> {
        val out = mutableListOf<RawUpcomingEvent>()
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return out
        val weeks = root["gameWeek"] as? JsonArray ?: return out
        for (day in weeks) {
            val games = (day as? JsonObject)?.get("games") as? JsonArray ?: continue
            for (g in games) {
                val obj = g as? JsonObject ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val start = obj["startTimeUTC"]?.jsonPrimitive?.contentOrNull ?: ""
                val h = resolveNhlTeamObject(obj["homeTeam"] as? JsonObject) ?: continue
                val a = resolveNhlTeamObject(obj["awayTeam"] as? JsonObject) ?: continue
                out.add(RawUpcomingEvent("nhl-$id", Sport.NHL, "NHL", "NHL", start, h, a))
            }
        }
        return out
    }

    /**
     * Parses https://api-web.nhle.com/v1/standings/now into calibrated TeamProfile records
     * keyed by PredictionEngine.profileKey(Sport.NHL, fullTeamName).
     */
    fun parseNhlStandings(rawJson: String): Map<String, TeamProfile> {
        val out = mutableMapOf<String, TeamProfile>()
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return out
        val standings = root["standings"] as? JsonArray ?: return out

        for (el in standings) {
            val s = el as? JsonObject ?: continue
            val abbrev = (s["teamAbbrev"] as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
            val teamNameRaw = (s["teamName"] as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull?.trim()
            val placeRaw = (s["placeName"] as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull?.trim()
            val commonRaw = (s["teamCommonName"] as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull?.trim()

            val fullName = abbrev?.let { NHL_ABBREV_TO_FULL[it] }
                ?: teamNameRaw?.let { canonicalTeam(it, Sport.NHL) }
                ?: if (!placeRaw.isNullOrBlank() && !commonRaw.isNullOrBlank()) canonicalTeam("$placeRaw $commonRaw", Sport.NHL) else null
                ?: continue

            val gp = s["gamesPlayed"]?.jsonPrimitive?.intOrNull ?: 0
            val w = s["wins"]?.jsonPrimitive?.intOrNull ?: 0
            val l = s["losses"]?.jsonPrimitive?.intOrNull ?: 0
            val otl = s["otLosses"]?.jsonPrimitive?.intOrNull ?: 0
            val gf = s["goalFor"]?.jsonPrimitive?.intOrNull ?: 0
            val ga = s["goalAgainst"]?.jsonPrimitive?.intOrNull ?: 0
            val ptsPct = s["pointPctg"]?.jsonPrimitive?.doubleOrNull
                ?: if (gp > 0) (w * 2.0 + otl) / (gp * 2.0) else 0.55

            val homeGp = s["homeGamesPlayed"]?.jsonPrimitive?.intOrNull ?: 0
            val homeW = s["homeWins"]?.jsonPrimitive?.intOrNull ?: 0
            val roadGp = s["roadGamesPlayed"]?.jsonPrimitive?.intOrNull ?: 0
            val roadW = s["roadWins"]?.jsonPrimitive?.intOrNull ?: 0

            val l10W = s["l10Wins"]?.jsonPrimitive?.intOrNull ?: 0
            val l10L = s["l10Losses"]?.jsonPrimitive?.intOrNull ?: 0
            val l10Otl = s["l10OtLosses"]?.jsonPrimitive?.intOrNull ?: 0

            val safeGp = max(1, gp).toDouble()
            val avgScored = if (gp > 0) gf / safeGp else 3.05
            val avgConceded = if (gp > 0) ga / safeGp else 3.05
            val gdPerGame = avgScored - avgConceded

            val sampleWeight = gp.toDouble() / (gp + 12.0)
            val elo = (1500.0 + sampleWeight * ((ptsPct - 0.55) * 420.0 + gdPerGame * 75.0))
                .coerceIn(1260.0, 1760.0)

            val recent5 = buildList {
                val total10 = max(1, l10W + l10L + l10Otl)
                val wSlots = ((l10W.toDouble() / total10) * 5.0).toInt().coerceIn(0, 5)
                val dSlots = ((l10Otl.toDouble() / total10) * 5.0).toInt().coerceIn(0, 5 - wSlots)
                val lSlots = (5 - wSlots - dSlots).coerceAtLeast(0)
                repeat(lSlots) { add("L") }
                repeat(dSlots) { add("D") }
                repeat(wSlots) { add("W") }
            }

            out[PredictionEngine.profileKey(Sport.NHL, fullName)] = TeamProfile(
                team = fullName,
                sport = Sport.NHL,
                leagueId = "NHL",
                elo = elo,
                clubElo = null,
                powerScore = PredictionEngine.eloToPowerScore(elo, Sport.NHL, isClubEloAnchored = false),
                gamesPlayed = gp,
                wins = w,
                draws = otl,
                losses = l,
                winPct = if (gp > 0) (w / safeGp) * 100.0 else 50.0,
                homeWinPct = if (homeGp > 0) (homeW.toDouble() / homeGp) * 100.0 else 55.0,
                awayWinPct = if (roadGp > 0) (roadW.toDouble() / roadGp) * 100.0 else 45.0,
                avgScored = avgScored,
                avgConceded = avgConceded,
                recentForm = recent5,
            )
        }
        return out
    }

    fun parseMlbSchedule(rawJson: String): List<RawUpcomingEvent> {
        val out = mutableListOf<RawUpcomingEvent>()
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return out
        val dates = root["dates"] as? JsonArray ?: return out
        for (d in dates) {
            val games = (d as? JsonObject)?.get("games") as? JsonArray ?: continue
            for (g in games) {
                val obj = g as? JsonObject ?: continue
                val id = obj["gamePk"]?.jsonPrimitive?.contentOrNull ?: continue
                val date = obj["gameDate"]?.jsonPrimitive?.contentOrNull ?: ""
                val teams = obj["teams"] as? JsonObject ?: continue
                val h = ((teams["home"] as? JsonObject)?.get("team") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: continue
                val a = ((teams["away"] as? JsonObject)?.get("team") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: continue
                out.add(RawUpcomingEvent("mlb-$id", Sport.MLB, "MLB", "MLB", date, canonicalTeam(h, Sport.MLB), canonicalTeam(a, Sport.MLB)))
            }
        }
        return out
    }
}
