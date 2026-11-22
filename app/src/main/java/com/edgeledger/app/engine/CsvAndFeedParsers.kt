package com.edgeledger.app.engine

import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.HistoricalMatch
import com.edgeledger.app.model.LivePlayerEntry
import com.edgeledger.app.model.Sport
import com.edgeledger.app.model.TeamProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
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
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,
    val homeLeaders: List<LivePlayerEntry> = emptyList(),
    val awayLeaders: List<LivePlayerEntry> = emptyList(),
    val homeLogoUrl: String? = null,
    val awayLogoUrl: String? = null,
)

object CsvAndFeedParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val whitespaceRegex = Regex("\\s+")
    private val footballSuffixRegex = Regex("(?i)\\s+(fc|afc|cf|sc)$")
    private val footballPrefixRegex = Regex("(?i)^(fc|afc|cf|sc)\\s+")

    private val FOOTBALL_ALIASES = mapOf(
        "man city" to "Manchester City",
        "manchester city" to "Manchester City",
        "man united" to "Manchester United",
        "man utd" to "Manchester United",
        "manchester utd" to "Manchester United",
        "nott'm forest" to "Nottingham Forest",
        "nottingham forest" to "Nottingham Forest",
        "wolves" to "Wolverhampton Wanderers",
        "wolverhampton" to "Wolverhampton Wanderers",
        "wolverhampton wanderers" to "Wolverhampton Wanderers",
        "spurs" to "Tottenham Hotspur",
        "tottenham" to "Tottenham Hotspur",
        "tottenham hotspur" to "Tottenham Hotspur",
        "newcastle" to "Newcastle United",
        "newcastle utd" to "Newcastle United",
        "west ham" to "West Ham United",
        "brighton" to "Brighton & Hove Albion",
        "brighton & hove albion" to "Brighton & Hove Albion",
        "brighton and hove albion" to "Brighton & Hove Albion",
        "leicester" to "Leicester City",
        "ipswich" to "Ipswich Town",
        "ath madrid" to "Atletico Madrid",
        "atletico de madrid" to "Atletico Madrid",
        "atlético madrid" to "Atletico Madrid",
        "ath bilbao" to "Athletic Club",
        "athletic bilbao" to "Athletic Club",
        "betis" to "Real Betis",
        "sociedad" to "Real Sociedad",
        "celta" to "Celta Vigo",
        "vallecano" to "Rayo Vallecano",
        "espanol" to "Espanyol",
        "bayern munich" to "Bayern Munich",
        "bayern münchen" to "Bayern Munich",
        "dortmund" to "Borussia Dortmund",
        "m'gladbach" to "Borussia Monchengladbach",
        "monchengladbach" to "Borussia Monchengladbach",
        "leverkusen" to "Bayer Leverkusen",
        "ein frankfurt" to "Eintracht Frankfurt",
        "rb leipzig" to "RB Leipzig",
        "stuttgart" to "VfB Stuttgart",
        "inter" to "Inter Milan",
        "internazionale" to "Inter Milan",
        "milan" to "AC Milan",
        "paris sg" to "Paris Saint-Germain",
        "psg" to "Paris Saint-Germain",
    )

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
        "SF" to "San Francisco 49ers",
        "SFO" to "San Francisco 49ers",
        "SEA" to "Seattle Seahawks",
        "TB" to "Tampa Bay Buccaneers",
        "TAM" to "Tampa Bay Buccaneers",
        "TEN" to "Tennessee Titans",
        "WAS" to "Washington Commanders",
        "WSH" to "Washington Commanders",
    )

    val NHL_ABBREV_TO_FULL: Map<String, String> = mapOf(
        "ANA" to "Anaheim Ducks",
        "BOS" to "Boston Bruins",
        "BUF" to "Buffalo Sabres",
        "CAR" to "Carolina Hurricanes",
        "CBJ" to "Columbus Blue Jackets",
        "CGY" to "Calgary Flames",
        "CHI" to "Chicago Blackhawks",
        "COL" to "Colorado Avalanche",
        "DAL" to "Dallas Stars",
        "DET" to "Detroit Red Wings",
        "EDM" to "Edmonton Oilers",
        "FLA" to "Florida Panthers",
        "LAK" to "Los Angeles Kings",
        "MIN" to "Minnesota Wild",
        "MTL" to "Montreal Canadiens",
        "NJD" to "New Jersey Devils",
        "NSH" to "Nashville Predators",
        "NYI" to "New York Islanders",
        "NYR" to "New York Rangers",
        "OTT" to "Ottawa Senators",
        "PHI" to "Philadelphia Flyers",
        "PIT" to "Pittsburgh Penguins",
        "SEA" to "Seattle Kraken",
        "SJS" to "San Jose Sharks",
        "STL" to "St. Louis Blues",
        "TBL" to "Tampa Bay Lightning",
        "TOR" to "Toronto Maple Leafs",
        "UTA" to "Utah Hockey Club",
        "VAN" to "Vancouver Canucks",
        "VGK" to "Vegas Golden Knights",
        "WPG" to "Winnipeg Jets",
        "WSH" to "Washington Capitals",
    )

    private val NBA_ALIASES = mapOf(
        "la clippers" to "Los Angeles Clippers",
        "la lakers" to "Los Angeles Lakers",
    )

    private val NHL_ALIASES = mapOf(
        "st louis blues" to "St. Louis Blues",
        "montreal canadiens" to "Montreal Canadiens",
        "montréal canadiens" to "Montreal Canadiens",
        "utah hockey club" to "Utah Hockey Club",
        "utah mammoth" to "Utah Hockey Club",
    )

    fun canonicalTeam(rawName: String, sport: Sport): String = normalizeTeam(rawName, sport)

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    private fun stripDiacritics(input: String): String =
        DIACRITICS_REGEX.replace(Normalizer.normalize(input, Normalizer.Form.NFD), "")

    private fun safeHttpsUrl(url: String?): String? =
        url?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }

    fun normalizeTeam(rawName: String, sport: Sport): String {
        val cleaned = stripDiacritics(rawName).trim().replace(whitespaceRegex, " ")
        if (cleaned.isBlank()) return ""
        return when (sport) {
            Sport.NFL -> {
                val upper = cleaned.uppercase(Locale.ROOT)
                NFL_ABBREV_TO_FULL[upper] ?: cleaned
            }
            Sport.NHL -> {
                val upper = cleaned.uppercase(Locale.ROOT)
                NHL_ABBREV_TO_FULL[upper] ?: NHL_ALIASES[cleaned.lowercase(Locale.ROOT)] ?: cleaned
            }
            Sport.FOOTBALL -> {
                val lower = cleaned.lowercase(Locale.ROOT)
                FOOTBALL_ALIASES[lower]?.let { return it }
                val stripped = cleaned
                    .replace(footballSuffixRegex, "")
                    .replace(footballPrefixRegex, "")
                    .trim()
                FOOTBALL_ALIASES[stripped.lowercase(Locale.ROOT)] ?: stripped.ifBlank { cleaned }
            }
            Sport.NBA -> NBA_ALIASES[cleaned.lowercase(Locale.ROOT)] ?: cleaned
            else -> cleaned
        }
    }

    fun parseCsvLine(line: String): List<String> {
        val cleanLine = line.trimEnd('\r')
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        while (i < cleanLine.length) {
            val ch = cleanLine[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < cleanLine.length && cleanLine[i + 1] == '"') {
                            cur.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        cur.append(ch)
                    }
                }
                ch == '"' && atFieldStart -> {
                    inQuotes = true
                    atFieldStart = false
                }
                ch == ',' -> {
                    out.add(cur.toString().trim())
                    cur.setLength(0)
                    atFieldStart = true
                }
                else -> {
                    cur.append(ch)
                    if (!ch.isWhitespace()) atFieldStart = false
                }
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }

    private fun validDecimalOdds(raw: String?): Double? {
        val d = raw?.trim()?.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d > 1.0 && d < 1000.0) d else null
    }

    private fun parseFootballDateIso(rawDate: String): String? {
        val parts = rawDate.trim().split("/")
        if (parts.size != 3) return null
        val d = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val rawY = parts[2].toIntOrNull() ?: return null
        val y = when { rawY in 70..99 -> 1900 + rawY; rawY in 0..69 -> 2000 + rawY; else -> rawY }
        return runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()
    }

    private fun parseIsoDateSafe(raw: String): String? {
        val s = raw.trim().take(10)
        if (s.length != 10) return null
        return runCatching { LocalDate.parse(s).toString() }.getOrNull()
    }

    fun parseFootballDataCsv(raw: String, leagueId: String): List<HistoricalMatch> {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val headers = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val idx = headers.withIndex().associate { (i, h) -> h.trim() to i }
        fun col(cols: List<String>, name: String): String =
            idx[name]?.let { cols.getOrNull(it) }.orEmpty().trim()

        val out = mutableListOf<HistoricalMatch>()
        for (rowIdx in 1 until lines.size) {
            val cols = parseCsvLine(lines[rowIdx])
            val dateIso = parseFootballDateIso(col(cols, "Date")) ?: continue
            val home = normalizeTeam(col(cols, "HomeTeam"), Sport.FOOTBALL)
            val away = normalizeTeam(col(cols, "AwayTeam"), Sport.FOOTBALL)
            if (home.isBlank() || away.isBlank() || home == away) continue

            val fthg = col(cols, "FTHG").toIntOrNull() ?: continue
            val ftag = col(cols, "FTAG").toIntOrNull() ?: continue
            if (fthg < 0 || ftag < 0) continue

            val oh = validDecimalOdds(col(cols, "PSH").ifBlank { col(cols, "B365H") })
            val od = validDecimalOdds(col(cols, "PSD").ifBlank { col(cols, "B365D") })
            val oa = validDecimalOdds(col(cols, "PSA").ifBlank { col(cols, "B365A") })

            out.add(
                HistoricalMatch(
                    id = "fd_${leagueId}_${dateIso}_${rowIdx}",
                    sport = Sport.FOOTBALL,
                    leagueId = leagueId,
                    dateIso = dateIso,
                    homeTeam = home,
                    awayTeam = away,
                    homeScore = fthg,
                    awayScore = ftag,
                    oddsHome = oh,
                    oddsDraw = od,
                    oddsAway = oa,
                    homeHtGoals = col(cols, "HTHG").toIntOrNull()?.takeIf { it >= 0 },
                    awayHtGoals = col(cols, "HTAG").toIntOrNull()?.takeIf { it >= 0 },
                    homeShots = col(cols, "HS").toIntOrNull()?.takeIf { it >= 0 },
                    awayShots = col(cols, "AS").toIntOrNull()?.takeIf { it >= 0 },
                    homeSot = col(cols, "HST").toIntOrNull()?.takeIf { it >= 0 },
                    awaySot = col(cols, "AST").toIntOrNull()?.takeIf { it >= 0 },
                    homeCorners = col(cols, "HC").toIntOrNull()?.takeIf { it >= 0 },
                    awayCorners = col(cols, "AC").toIntOrNull()?.takeIf { it >= 0 },
                    homeFouls = col(cols, "HF").toIntOrNull()?.takeIf { it >= 0 },
                    awayFouls = col(cols, "AF").toIntOrNull()?.takeIf { it >= 0 },
                    homeYellows = col(cols, "HY").toIntOrNull()?.takeIf { it >= 0 },
                    awayYellows = col(cols, "AY").toIntOrNull()?.takeIf { it >= 0 },
                    homeReds = col(cols, "HR").toIntOrNull()?.takeIf { it >= 0 },
                    awayReds = col(cols, "AR").toIntOrNull()?.takeIf { it >= 0 },
                )
            )
        }
        return out
    }

    fun parseClubEloCsv(raw: String): Map<String, Double> {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyMap()
        val headers = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val clubIdx = headers.indexOfFirst { it.equals("Club", ignoreCase = true) }
        val eloIdx = headers.indexOfFirst { it.equals("Elo", ignoreCase = true) }
        if (clubIdx < 0 || eloIdx < 0) return emptyMap()

        val map = LinkedHashMap<String, Double>()
        for (i in 1 until lines.size) {
            val cols = parseCsvLine(lines[i])
            val club = cols.getOrNull(clubIdx)?.trim().orEmpty()
            val elo = cols.getOrNull(eloIdx)?.trim()?.toDoubleOrNull() ?: continue
            if (club.isBlank() || !elo.isFinite() || elo !in 800.0..2600.0) continue
            map[normalizeTeam(club, Sport.FOOTBALL)] = elo
        }
        return map
    }

    fun parseNflverseGamesCsv(raw: String, minSeason: Int): List<HistoricalMatch> {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val headers = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val idx = headers.withIndex().associate { (i, h) -> h.trim() to i }
        fun col(cols: List<String>, name: String): String =
            idx[name]?.let { cols.getOrNull(it) }.orEmpty().trim()

        val out = mutableListOf<HistoricalMatch>()
        for (i in 1 until lines.size) {
            val cols = parseCsvLine(lines[i])
            val season = col(cols, "season").toIntOrNull() ?: continue
            if (season < minSeason) continue
            val dateIso = parseIsoDateSafe(col(cols, "gameday")) ?: continue
            val hScore = col(cols, "home_score").toIntOrNull() ?: continue
            val aScore = col(cols, "away_score").toIntOrNull() ?: continue
            if (hScore < 0 || aScore < 0) continue

            val home = normalizeTeam(col(cols, "home_team"), Sport.NFL)
            val away = normalizeTeam(col(cols, "away_team"), Sport.NFL)
            if (home.isBlank() || away.isBlank() || home == away) continue

            val homeMl = col(cols, "home_moneyline").toDoubleOrNull()?.takeIf { it.isFinite() }
            val awayMl = col(cols, "away_moneyline").toDoubleOrNull()?.takeIf { it.isFinite() }
            val oh = homeMl?.let { OddsMath.americanToDecimalOrNull(it) }
            val oa = awayMl?.let { OddsMath.americanToDecimalOrNull(it) }

            out.add(
                HistoricalMatch(
                    id = col(cols, "game_id").ifBlank { "nfl_${dateIso}_$i" },
                    sport = Sport.NFL,
                    leagueId = "NFL",
                    dateIso = dateIso,
                    homeTeam = home,
                    awayTeam = away,
                    homeScore = hScore,
                    awayScore = aScore,
                    oddsHome = oh,
                    oddsAway = oa,
                    spreadLine = col(cols, "spread_line").toDoubleOrNull()?.takeIf { it.isFinite() },
                    totalLine = col(cols, "total_line").toDoubleOrNull()?.takeIf { it.isFinite() },
                    homeRestDays = col(cols, "home_rest").toIntOrNull()?.takeIf { it in 0..30 },
                    awayRestDays = col(cols, "away_rest").toIntOrNull()?.takeIf { it in 0..30 },
                )
            )
        }
        return out
    }

    fun parseTmlTennisCsv(raw: String): List<HistoricalMatch> {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val headers = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val idx = headers.withIndex().associate { (i, h) -> h.trim() to i }
        fun col(cols: List<String>, name: String): String =
            idx[name]?.let { cols.getOrNull(it) }.orEmpty().trim()

        val out = mutableListOf<HistoricalMatch>()
        for (i in 1 until lines.size) {
            val cols = parseCsvLine(lines[i])
            val rawDate = col(cols, "tourney_date")
            if (rawDate.length != 8) continue
            val y = rawDate.substring(0, 4).toIntOrNull() ?: continue
            val m = rawDate.substring(4, 6).toIntOrNull() ?: continue
            val d = rawDate.substring(6, 8).toIntOrNull() ?: continue
            val dateIso = runCatching { LocalDate.of(y, m, d).toString() }.getOrNull() ?: continue

            val winner = col(cols, "winner_name")
            val loser = col(cols, "loser_name")
            if (winner.isBlank() || loser.isBlank() || winner == loser) continue

            val w1stIn = col(cols, "w_1stIn").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val w1stWon = col(cols, "w_1stWon").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            val l1stIn = col(cols, "l_1stIn").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val l1stWon = col(cols, "l_1stWon").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

            val wPct = if (w1stIn != null && w1stWon != null) (w1stWon / w1stIn * 100.0).coerceIn(0.0, 100.0) else null
            val lPct = if (l1stIn != null && l1stWon != null) (l1stWon / l1stIn * 100.0).coerceIn(0.0, 100.0) else null

            out.add(
                HistoricalMatch(
                    id = "atp_${dateIso}_$i",
                    sport = Sport.TENNIS,
                    leagueId = "ATP",
                    dateIso = dateIso,
                    homeTeam = winner,
                    awayTeam = loser,
                    homeScore = 1,
                    awayScore = 0,
                    surface = col(cols, "surface").ifBlank { "Hard" },
                    homeAces = col(cols, "w_ace").toIntOrNull()?.takeIf { it >= 0 },
                    awayAces = col(cols, "l_ace").toIntOrNull()?.takeIf { it >= 0 },
                    homeDoubleFaults = col(cols, "w_df").toIntOrNull()?.takeIf { it >= 0 },
                    awayDoubleFaults = col(cols, "l_df").toIntOrNull()?.takeIf { it >= 0 },
                    homeFirstServeWonPct = wPct,
                    awayFirstServeWonPct = lPct,
                )
            )
        }
        return out
    }

    fun parseEspnScoreboard(
        raw: String,
        sport: Sport,
        leagueId: String,
        leagueName: String,
    ): Pair<List<HistoricalMatch>, List<RawUpcomingEvent>> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyList<HistoricalMatch>() to emptyList()
        val events = root.arr("events") ?: return emptyList<HistoricalMatch>() to emptyList()

        val completed = mutableListOf<HistoricalMatch>()
        val upcoming = mutableListOf<RawUpcomingEvent>()

        for (evElem in events) {
            val ev = evElem as? JsonObject ?: continue
            val id = ev.str("id") ?: continue
            val kickoff = ev.str("date") ?: continue
            val comp = ev.arr("competitions")?.firstOrNull() as? JsonObject ?: continue
            val competitors = comp.arr("competitors")?.mapNotNull { it as? JsonObject } ?: continue
            if (competitors.size < 2) continue

            val homeObj = competitors.firstOrNull { it.str("homeAway").equals("home", ignoreCase = true) } ?: competitors[0]
            val awayObj = competitors.firstOrNull { it.str("homeAway").equals("away", ignoreCase = true) }
                ?: competitors.firstOrNull { it !== homeObj }
                ?: continue
            if (homeObj === awayObj) continue

            val homeTeamObj = homeObj.obj("team") ?: homeObj.obj("athlete")
            val awayTeamObj = awayObj.obj("team") ?: awayObj.obj("athlete")
            val rawHome = homeTeamObj?.str("displayName") ?: homeTeamObj?.str("shortDisplayName") ?: homeTeamObj?.str("fullName") ?: continue
            val rawAway = awayTeamObj?.str("displayName") ?: awayTeamObj?.str("shortDisplayName") ?: awayTeamObj?.str("fullName") ?: continue
            val hName = normalizeTeam(rawHome, sport)
            val aName = normalizeTeam(rawAway, sport)
            if (hName.isBlank() || aName.isBlank() || hName == aName) continue

            val statusType = comp.obj("status")?.obj("type") ?: ev.obj("status")?.obj("type")
            val state = statusType?.str("state")?.lowercase(Locale.ROOT).orEmpty()
            val statusName = statusType?.str("name")?.uppercase(Locale.ROOT).orEmpty()
            if (statusName.contains("POSTPONED") || statusName.contains("CANCEL") || statusName.contains("ABANDON")) continue
            val isCompleted = statusType?.bool("completed") == true || state == "post"

            if (isCompleted) {
                val hScore = homeObj.str("score")?.substringBefore("/")?.trim()?.toIntOrNull()
                val aScore = awayObj.str("score")?.substringBefore("/")?.trim()?.toIntOrNull()
                val dateIso = parseIsoDateSafe(kickoff)
                if (hScore != null && aScore != null && hScore >= 0 && aScore >= 0 && dateIso != null) {
                    completed.add(
                        HistoricalMatch(
                            id = "espn_${leagueId}_$id",
                            sport = sport,
                            leagueId = leagueId,
                            dateIso = dateIso,
                            homeTeam = hName,
                            awayTeam = aName,
                            homeScore = hScore,
                            awayScore = aScore,
                        )
                    )
                }
            } else if (state == "pre") {
                val oddsLines = parseEspnOdds(comp, sport)
                upcoming.add(
                    RawUpcomingEvent(
                        id = "espn_${leagueId}_$id",
                        sport = sport,
                        leagueId = leagueId,
                        leagueName = leagueName,
                        kickoffIso = kickoff,
                        homeTeam = hName,
                        awayTeam = aName,
                        bookOdds = oddsLines,
                        homeTeamId = homeTeamObj?.str("id"),
                        awayTeamId = awayTeamObj?.str("id"),
                        homeLeaders = extractEspnLeaders(homeObj),
                        awayLeaders = extractEspnLeaders(awayObj),
                        homeLogoUrl = safeHttpsUrl(homeTeamObj?.str("logo")),
                        awayLogoUrl = safeHttpsUrl(awayTeamObj?.str("logo")),
                    )
                )
            }
        }
        return completed to upcoming
    }

    private fun parseEspnOdds(comp: JsonObject, sport: Sport): List<BookmakerOddsLine> {
        val oddsArr = comp.arr("odds") ?: return emptyList()
        val out = mutableListOf<BookmakerOddsLine>()
        for (elem in oddsArr) {
            val obj = elem as? JsonObject ?: continue
            val provider = obj.obj("provider")?.str("name")?.takeIf { it.isNotBlank() } ?: "ESPN Odds"
            val homeMl = obj.obj("homeTeamOdds")?.dbl("moneyLine")
            val awayMl = obj.obj("awayTeamOdds")?.dbl("moneyLine")
            val drawMl = obj.obj("drawOdds")?.dbl("moneyLine")
            val oh = homeMl?.let { OddsMath.americanToDecimalOrNull(it) } ?: continue
            val oa = awayMl?.let { OddsMath.americanToDecimalOrNull(it) } ?: continue
            val od = drawMl?.let { OddsMath.americanToDecimalOrNull(it) }
            if (sport.hasDraw && od == null) continue
            out.add(
                BookmakerOddsLine(
                    bookmaker = provider,
                    isSharp = false,
                    homeOdds = oh,
                    drawOdds = od,
                    awayOdds = oa,
                    updatedLabel = "ESPN",
                )
            )
        }
        return out
    }

    private fun extractEspnLeaders(competitor: JsonObject): List<LivePlayerEntry> {
        val leadersArr = competitor.arr("leaders") ?: return emptyList()
        val result = mutableListOf<LivePlayerEntry>()
        for (catElem in leadersArr) {
            val catObj = catElem as? JsonObject ?: continue
            val catName = catObj.str("name") ?: catObj.str("shortDisplayName") ?: ""
            val inner = catObj.arr("leaders") ?: continue
            for (lElem in inner) {
                val lObj = lElem as? JsonObject ?: continue
                val athlete = lObj.obj("athlete") ?: continue
                val name = athlete.str("displayName") ?: athlete.str("fullName") ?: continue
                if (name.isBlank()) continue
                val pos = athlete.obj("position")?.str("abbreviation").orEmpty()
                val statVal = lObj.dbl("value")?.takeIf { it.isFinite() }
                result.add(LivePlayerEntry(name.trim(), pos.trim(), catName.trim(), statVal))
            }
        }
        return result
    }


    fun parseEspnNflStandings(raw: String): Map<String, TeamProfile> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        val children = root.arr("children") ?: return emptyMap()
        val out = LinkedHashMap<String, TeamProfile>()

        fun collectEntries(node: JsonObject): List<JsonObject> {
            val direct = node.obj("standings")?.arr("entries")?.mapNotNull { it as? JsonObject }.orEmpty()
            if (direct.isNotEmpty()) return direct
            return node.arr("children")
                ?.mapNotNull { it as? JsonObject }
                ?.flatMap { div -> div.obj("standings")?.arr("entries")?.mapNotNull { it as? JsonObject }.orEmpty() }
                .orEmpty()
        }

        for (confElem in children) {
            val conf = confElem as? JsonObject ?: continue
            for (entry in collectEntries(conf)) {
                val teamObj = entry.obj("team") ?: continue
                val rawName = teamObj.str("displayName") ?: continue
                val teamName = normalizeTeam(rawName, Sport.NFL)
                if (teamName.isBlank()) continue

                val stats = entry.arr("stats")?.mapNotNull { it as? JsonObject }.orEmpty()
                fun statVal(name: String): Double? =
                    stats.firstOrNull { it.str("name").equals(name, ignoreCase = true) }
                        ?.dbl("value")
                        ?.takeIf { it.isFinite() }
                fun statDisplay(name: String): String? =
                    stats.firstOrNull { it.str("name").equals(name, ignoreCase = true) }
                        ?.str("displayValue")

                val wins = (statVal("wins") ?: 0.0).toInt().coerceAtLeast(0)
                val losses = (statVal("losses") ?: 0.0).toInt().coerceAtLeast(0)
                val ties = (statVal("ties") ?: 0.0).toInt().coerceAtLeast(0)
                val gp = wins + losses + ties
                val safeGp = max(1, gp)

                val winPct = (
                    statVal("winPercent")?.let { it * 100.0 }
                        ?: if (gp > 0) ((wins + 0.5 * ties) / safeGp * 100.0) else 50.0
                    ).coerceIn(0.0, 100.0)

                val ppg = statVal("avgPointsFor")
                    ?: statVal("pointsFor")?.let { if (gp > 0) it / safeGp else null }
                    ?: 22.5
                val oppPpg = statVal("avgPointsAgainst")
                    ?: statVal("pointsAgainst")?.let { if (gp > 0) it / safeGp else null }
                    ?: 22.5

                val diffBoost = if (gp > 0) ((ppg - oppPpg) * 3.5).coerceIn(-80.0, 80.0) else 0.0
                val elo = (1500.0 + (winPct - 50.0) * 5.0 + diffBoost).coerceIn(1150.0, 1850.0)
                val power = ((elo - 1250.0) / 5.0).toInt().coerceIn(20, 99)
                val streak = parseStreakCode(statDisplay("streak"))
                val logo = teamObj.arr("logos")?.firstOrNull()?.let { (it as? JsonObject)?.str("href") }

                out[PredictionEngine.profileKey(Sport.NFL, teamName)] = TeamProfile(
                    team = teamName,
                    sport = Sport.NFL,
                    leagueId = "NFL",
                    elo = elo,
                    powerScore = power,
                    gamesPlayed = gp,
                    wins = wins,
                    losses = losses,
                    winPct = winPct,
                    homeWinPct = (winPct + 3.5).coerceIn(10.0, 95.0),
                    awayWinPct = (winPct - 3.5).coerceIn(5.0, 90.0),
                    avgScored = ppg,
                    avgConceded = oppPpg,
                    recentForm = streak,
                    logoUrl = logo,
                )
            }
        }
        return out
    }

    fun parseEspnNbaStandings(raw: String): Map<String, TeamProfile> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        val children = root.arr("children") ?: return emptyMap()
        val out = LinkedHashMap<String, TeamProfile>()

        for (confElem in children) {
            val conf = confElem as? JsonObject ?: continue
            val entries = conf.obj("standings")?.arr("entries") ?: continue
            for (entryElem in entries) {
                val entry = entryElem as? JsonObject ?: continue
                val teamObj = entry.obj("team") ?: continue
                val rawName = teamObj.str("displayName") ?: continue
                val teamName = normalizeTeam(rawName, Sport.NBA)
                if (teamName.isBlank()) continue

                val stats = entry.arr("stats")?.mapNotNull { it as? JsonObject }.orEmpty()
                fun statVal(name: String): Double? =
                    stats.firstOrNull { it.str("name").equals(name, ignoreCase = true) }
                        ?.dbl("value")
                        ?.takeIf { it.isFinite() }
                fun statDisplay(name: String): String? =
                    stats.firstOrNull { it.str("name").equals(name, ignoreCase = true) }
                        ?.str("displayValue")

                val wins = (statVal("wins") ?: 0.0).toInt().coerceAtLeast(0)
                val losses = (statVal("losses") ?: 0.0).toInt().coerceAtLeast(0)
                val gp = wins + losses
                val safeGp = max(1, gp)
                val winPct = (statVal("winPercent")?.let { it * 100.0 } ?: (wins.toDouble() / safeGp * 100.0)).coerceIn(0.0, 100.0)
                val ppg = statVal("avgPointsFor") ?: 113.0
                val oppPpg = statVal("avgPointsAgainst") ?: 113.0
                val elo = (1500.0 + (winPct - 50.0) * 6.0).coerceIn(1150.0, 1850.0)
                val power = ((elo - 1250.0) / 5.0).toInt().coerceIn(20, 99)
                val streak = parseStreakCode(statDisplay("streak"))
                val logo = teamObj.arr("logos")?.firstOrNull()?.let { (it as? JsonObject)?.str("href") }

                out[PredictionEngine.profileKey(Sport.NBA, teamName)] = TeamProfile(
                    team = teamName,
                    sport = Sport.NBA,
                    leagueId = "NBA",
                    elo = elo,
                    powerScore = power,
                    gamesPlayed = gp,
                    wins = wins,
                    losses = losses,
                    winPct = winPct,
                    homeWinPct = (winPct + 4.0).coerceIn(10.0, 95.0),
                    awayWinPct = (winPct - 4.0).coerceIn(5.0, 90.0),
                    avgScored = ppg,
                    avgConceded = oppPpg,
                    recentForm = streak,
                    logoUrl = logo,
                )
            }
        }
        return out
    }

    fun parseNhlSchedule(raw: String): List<RawUpcomingEvent> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val gameWeek = root.arr("gameWeek") ?: return emptyList()
        val out = mutableListOf<RawUpcomingEvent>()

        for (dayElem in gameWeek) {
            val dayObj = dayElem as? JsonObject ?: continue
            val games = dayObj.arr("games") ?: continue
            for (gElem in games) {
                val g = gElem as? JsonObject ?: continue
                val gameState = g.str("gameState")?.uppercase(Locale.ROOT).orEmpty()
                if (gameState !in setOf("FUT", "PRE", "SCHEDULED")) continue

                val id = g.str("id") ?: continue
                val kickoff = g.str("startTimeUTC") ?: continue
                val homeObj = g.obj("homeTeam") ?: continue
                val awayObj = g.obj("awayTeam") ?: continue
                val hPlace = homeObj.obj("placeName")?.str("default").orEmpty()
                val hCommon = homeObj.obj("commonName")?.str("default").orEmpty()
                val aPlace = awayObj.obj("placeName")?.str("default").orEmpty()
                val aCommon = awayObj.obj("commonName")?.str("default").orEmpty()
                val hRaw = if (hCommon.startsWith(hPlace, ignoreCase = true)) hCommon else "$hPlace $hCommon".trim()
                val aRaw = if (aCommon.startsWith(aPlace, ignoreCase = true)) aCommon else "$aPlace $aCommon".trim()
                val hName = normalizeTeam(hRaw, Sport.NHL)
                val aName = normalizeTeam(aRaw, Sport.NHL)
                if (hName.isBlank() || aName.isBlank() || hName == aName) continue

                out.add(
                    RawUpcomingEvent(
                        id = "nhl_$id",
                        sport = Sport.NHL,
                        leagueId = "NHL",
                        leagueName = "NHL",
                        kickoffIso = kickoff,
                        homeTeam = hName,
                        awayTeam = aName,
                        homeTeamId = homeObj.str("abbrev"),
                        awayTeamId = awayObj.str("abbrev"),
                        homeLogoUrl = homeObj.str("logo"),
                        awayLogoUrl = awayObj.str("logo"),
                    )
                )
            }
        }
        return out
    }

    fun parseNhlStandings(raw: String): Map<String, TeamProfile> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        val standings = root.arr("standings") ?: return emptyMap()
        val out = LinkedHashMap<String, TeamProfile>()

        for (elem in standings) {
            val st = elem as? JsonObject ?: continue
            val rawName = st.obj("teamName")?.str("default") ?: continue
            val teamName = normalizeTeam(rawName, Sport.NHL)
            if (teamName.isBlank()) continue

            val gp = (st.int("gamesPlayed") ?: 0).coerceAtLeast(0)
            val safeGp = max(1, gp)
            val wins = (st.int("wins") ?: 0).coerceAtLeast(0)
            val losses = ((st.int("losses") ?: 0) + (st.int("otLosses") ?: 0)).coerceAtLeast(0)
            val gf = (st.dbl("goalFor") ?: 0.0).coerceAtLeast(0.0)
            val ga = (st.dbl("goalAgainst") ?: 0.0).coerceAtLeast(0.0)
            val winPct = if (gp > 0) (wins.toDouble() / safeGp * 100.0).coerceIn(0.0, 100.0) else 50.0
            val elo = (1500.0 + (winPct - 50.0) * 5.0).coerceIn(1200.0, 1800.0)
            val power = ((elo - 1250.0) / 5.0).toInt().coerceIn(20, 99)

            val streakCode = st.str("streakCode")?.uppercase(Locale.ROOT).orEmpty()
            val streakCount = (st.int("streakCount") ?: 0).coerceIn(0, 5)
            val recentForm = if (streakCode in setOf("W", "L") && streakCount > 0) {
                List(streakCount) { streakCode }
            } else {
                emptyList()
            }

            out[PredictionEngine.profileKey(Sport.NHL, teamName)] = TeamProfile(
                team = teamName,
                sport = Sport.NHL,
                leagueId = "NHL",
                elo = elo,
                powerScore = power,
                gamesPlayed = gp,
                wins = wins,
                losses = losses,
                winPct = winPct,
                homeWinPct = (winPct + 3.0).coerceIn(10.0, 95.0),
                awayWinPct = (winPct - 3.0).coerceIn(5.0, 90.0),
                avgScored = if (gp > 0) gf / safeGp else 3.05,
                avgConceded = if (gp > 0) ga / safeGp else 3.05,
                recentForm = recentForm,
                logoUrl = st.str("teamLogo"),
            )
        }
        return out
    }

    fun parseMlbSchedule(raw: String): List<RawUpcomingEvent> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val dates = root.arr("dates") ?: return emptyList()
        val out = mutableListOf<RawUpcomingEvent>()

        for (dElem in dates) {
            val dObj = dElem as? JsonObject ?: continue
            val games = dObj.arr("games") ?: continue
            for (gElem in games) {
                val g = gElem as? JsonObject ?: continue
                val abstractState = g.obj("status")?.str("abstractGameState").orEmpty()
                if (!abstractState.equals("Preview", ignoreCase = true)) continue

                val pk = g.str("gamePk") ?: continue
                val kickoff = g.str("gameDate") ?: continue
                val teams = g.obj("teams") ?: continue
                val homeTeamObj = teams.obj("home")?.obj("team") ?: continue
                val awayTeamObj = teams.obj("away")?.obj("team") ?: continue
                val hName = normalizeTeam(homeTeamObj.str("name") ?: continue, Sport.MLB)
                val aName = normalizeTeam(awayTeamObj.str("name") ?: continue, Sport.MLB)
                if (hName.isBlank() || aName.isBlank() || hName == aName) continue

                val hId = homeTeamObj.str("id")
                val aId = awayTeamObj.str("id")
                out.add(
                    RawUpcomingEvent(
                        id = "mlb_$pk",
                        sport = Sport.MLB,
                        leagueId = "MLB",
                        leagueName = "MLB",
                        kickoffIso = kickoff,
                        homeTeam = hName,
                        awayTeam = aName,
                        homeTeamId = hId,
                        awayTeamId = aId,
                        homeLogoUrl = hId?.let { "https://www.mlbstatic.com/team-logos/$it.svg" },
                        awayLogoUrl = aId?.let { "https://www.mlbstatic.com/team-logos/$it.svg" },
                    )
                )
            }
        }
        return out
    }

    fun parseEspnRoster(raw: String): List<LivePlayerEntry> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val athletes = root.arr("athletes") ?: return emptyList()
        val out = mutableListOf<LivePlayerEntry>()

        fun extractAthlete(obj: JsonObject) {
            val name = obj.str("displayName") ?: obj.str("fullName") ?: return
            if (name.isBlank()) return
            val pos = obj.obj("position")?.str("abbreviation").orEmpty()
            out.add(LivePlayerEntry(name = name.trim(), position = pos.trim()))
        }

        for (elem in athletes) {
            val obj = elem as? JsonObject ?: continue
            val items = obj.arr("items")
            if (items != null) {
                for (item in items) {
                    (item as? JsonObject)?.let(::extractAthlete)
                }
            } else {
                extractAthlete(obj)
            }
        }
        return out.distinctBy { it.name.lowercase(Locale.ROOT) }
    }

    fun parseNhlRoster(raw: String): List<LivePlayerEntry> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<LivePlayerEntry>()
        for (groupKey in listOf("forwards", "defensemen")) {
            val arr = root.arr(groupKey) ?: continue
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val first = obj.obj("firstName")?.str("default").orEmpty()
                val last = obj.obj("lastName")?.str("default").orEmpty()
                val full = "$first $last".trim()
                if (full.isBlank()) continue
                val pos = obj.str("positionCode") ?: if (groupKey == "forwards") "F" else "D"
                out.add(LivePlayerEntry(name = full, position = pos))
            }
        }
        return out.distinctBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun parseStreakCode(display: String?): List<String> {
        val s = display?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (s.length < 2) return emptyList()
        val code = s.first()
        if (code != 'W' && code != 'L') return emptyList()
        val count = s.drop(1).toIntOrNull()?.coerceIn(1, 5) ?: return emptyList()
        return List(count) { code.toString() }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
}
