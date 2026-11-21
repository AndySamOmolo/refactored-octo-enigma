package com.edgeledger.app.engine

import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.HistoricalMatch
import com.edgeledger.app.model.Sport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    private val ALIASES = mapOf(
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

    fun canonicalTeam(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        return ALIASES[clean.lowercase()] ?: clean
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
            val home = canonicalTeam(r["HomeTeam"] ?: r["Home"] ?: "")
            val away = canonicalTeam(r["AwayTeam"] ?: r["Away"] ?: "")
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
            val club = canonicalTeam(r["Club"] ?: "")
            val elo = r["Elo"]?.toDoubleOrNull()
            if (club.isNotBlank() && elo != null) put(club, elo)
        }
    }

    fun parseNflverseGamesCsv(csv: String, minSeason: Int = 2024): List<HistoricalMatch> =
        parseCsv(csv).mapNotNull { r ->
            val season = iOpt(r["season"]) ?: return@mapNotNull null
            if (season < minSeason) return@mapNotNull null
            val date = (r["gameday"] ?: "").take(10)
            val home = canonicalTeam(r["home_team"] ?: "")
            val away = canonicalTeam(r["away_team"] ?: "")
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
            val w = canonicalTeam(r["winner_name"] ?: "")
            val l = canonicalTeam(r["loser_name"] ?: "")
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
                return canonicalTeam(
                    t?.get("displayName")?.jsonPrimitive?.contentOrNull
                        ?: a?.get("displayName")?.jsonPrimitive?.contentOrNull
                        ?: ""
                )
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
                val h = ((obj["homeTeam"] as? JsonObject)?.get("placeName") as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull ?: continue
                val a = ((obj["awayTeam"] as? JsonObject)?.get("placeName") as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull ?: continue
                out.add(RawUpcomingEvent("nhl-$id", Sport.NHL, "NHL", "NHL", start, canonicalTeam(h), canonicalTeam(a)))
            }
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
                out.add(RawUpcomingEvent("mlb-$id", Sport.MLB, "MLB", "MLB", date, canonicalTeam(h), canonicalTeam(a)))
            }
        }
        return out
    }
}
