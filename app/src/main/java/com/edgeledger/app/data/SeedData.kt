package com.edgeledger.app.data

import com.edgeledger.app.engine.RawUpcomingEvent
import com.edgeledger.app.model.BookmakerOddsLine
import com.edgeledger.app.model.HistoricalMatch
import com.edgeledger.app.model.Sport
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class StadiumLocation(
    val stadium: String,
    val lat: Double,
    val lon: Double,
    val isDome: Boolean = false,
)

object SeedData {
    val CLUB_ELO_ANCHOR: Map<String, Double> = mapOf(
        "Arsenal" to 2048.0,
        "Bayern Munich" to 2041.0,
        "Barcelona" to 2034.0,
        "Manchester City" to 2003.0,
        "Real Madrid" to 2002.0,
        "Paris Saint-Germain" to 1990.0,
        "Inter Milan" to 1949.0,
        "Liverpool" to 1938.0,
        "Atletico Madrid" to 1911.0,
        "Brighton" to 1910.0,
        "Borussia Dortmund" to 1901.0,
        "Aston Villa" to 1899.0,
        "Chelsea" to 1886.0,
        "Bayer Leverkusen" to 1863.0,
        "Newcastle United" to 1853.0,
        "Napoli" to 1818.0,
        "Juventus" to 1830.0,
    )

    val STADIUM_GPS: Map<String, StadiumLocation> = mapOf(
        "Arsenal" to StadiumLocation("Emirates Stadium", 51.5549, -0.1084),
        "Liverpool" to StadiumLocation("Anfield", 53.4308, -2.9608),
        "Chelsea" to StadiumLocation("Stamford Bridge", 51.4817, -0.1910),
        "Manchester City" to StadiumLocation("Etihad Stadium", 53.4831, -2.2004),
        "Barcelona" to StadiumLocation("Camp Nou", 41.3809, 2.1228),
        "Real Madrid" to StadiumLocation("Santiago Bernabeu", 40.4530, -3.6883),
        "Bayern Munich" to StadiumLocation("Allianz Arena", 48.2188, 11.6247),
        "Inter Milan" to StadiumLocation("San Siro", 45.4781, 9.1240),
        "Buffalo Bills" to StadiumLocation("Highmark Stadium", 42.7738, -78.7870),
        "Kansas City Chiefs" to StadiumLocation("Arrowhead Stadium", 39.0489, -94.4839),
        "Detroit Lions" to StadiumLocation("Ford Field", 42.3400, -83.0456, isDome = true),
    )

    private fun noise(seed: Int): Double {
        val x = sin(seed * 12.9898 + 78.233) * 43758.5453
        return x - floor(x)
    }

    fun buildSeedHistory(now: Instant = Instant.now()): List<HistoricalMatch> {
        val leagues = listOf(
            Triple(Sport.FOOTBALL, "E0", listOf("Arsenal" to 1.95, "Manchester City" to 1.90, "Liverpool" to 1.85, "Aston Villa" to 1.63, "Chelsea" to 1.60, "Brighton" to 1.56, "Newcastle United" to 1.54, "Tottenham" to 1.50)),
            Triple(Sport.FOOTBALL, "SP1", listOf("Barcelona" to 1.94, "Real Madrid" to 1.92, "Atletico Madrid" to 1.72, "Athletic Club" to 1.56, "Villarreal" to 1.54, "Real Sociedad" to 1.48)),
            Triple(Sport.FOOTBALL, "D1", listOf("Bayern Munich" to 2.00, "Bayer Leverkusen" to 1.76, "Borussia Dortmund" to 1.70, "RB Leipzig" to 1.66, "Stuttgart" to 1.56, "Eintracht Frankfurt" to 1.52)),
            Triple(Sport.NFL, "NFL", listOf("Kansas City Chiefs" to 1.85, "Buffalo Bills" to 1.82, "Baltimore Ravens" to 1.78, "Detroit Lions" to 1.76, "Philadelphia Eagles" to 1.72, "Green Bay Packers" to 1.62)),
            Triple(Sport.NBA, "NBA", listOf("Boston Celtics" to 1.88, "Oklahoma City Thunder" to 1.86, "Cleveland Cavaliers" to 1.76, "Denver Nuggets" to 1.72, "New York Knicks" to 1.68, "Los Angeles Lakers" to 1.60)),
            Triple(Sport.NHL, "NHL", listOf("Florida Panthers" to 1.80, "Edmonton Oilers" to 1.78, "Dallas Stars" to 1.75, "Carolina Hurricanes" to 1.72, "Winnipeg Jets" to 1.70)),
            Triple(Sport.MLB, "MLB", listOf("Los Angeles Dodgers" to 1.84, "New York Yankees" to 1.74, "Philadelphia Phillies" to 1.70, "Baltimore Orioles" to 1.66, "Atlanta Braves" to 1.65)),
            Triple(Sport.TENNIS, "ATP", listOf("Jannik Sinner" to 1.95, "Carlos Alcaraz" to 1.93, "Alexander Zverev" to 1.80, "Novak Djokovic" to 1.78, "Taylor Fritz" to 1.70, "Daniil Medvedev" to 1.68)),
            Triple(Sport.CRICKET, "ICC", listOf("India" to 1.90, "Australia" to 1.86, "England" to 1.78, "South Africa" to 1.76, "New Zealand" to 1.72)),
            Triple(Sport.F1, "F1", listOf("Max Verstappen" to 1.94, "Lando Norris" to 1.88, "Charles Leclerc" to 1.84, "Oscar Piastri" to 1.80, "Lewis Hamilton" to 1.76)),
            Triple(Sport.MMA, "UFC", listOf("Islam Makhachev" to 1.94, "Alex Pereira" to 1.90, "Ilia Topuria" to 1.88, "Dricus Du Plessis" to 1.82, "Tom Aspinall" to 1.86)),
        )

        val out = mutableListOf<HistoricalMatch>()
        var seq = 1
        val surfaces = listOf("Hard", "Clay", "Grass")

        for ((sport, leagueId, teams) in leagues) {
            var daysAgo = 120L
            for (round in 0..1) {
                for (i in teams.indices) {
                    for (j in i + 1 until teams.size) {
                        val h = if (round == 0) teams[i] else teams[j]
                        val a = if (round == 0) teams[j] else teams[i]
                        val u1 = noise(seq * 17 + i * 31 + j * 13)
                        val u2 = noise(seq * 29 + i * 19 + j * 41)
                        val date = now.minus(daysAgo, ChronoUnit.DAYS).toString().take(10)
                        daysAgo = max(2L, daysAgo - 2L)

                        when (sport) {
                            Sport.FOOTBALL -> {
                                val hs = max(0, min(5, (1.45 * (h.second / a.second) + (u1 - 0.48) * 2.1).roundToInt()))
                                val ascore = max(0, min(4, (1.15 * (a.second / h.second) + (u2 - 0.5) * 1.9).roundToInt()))
                                val pH = (0.45 + (h.second - a.second) * 0.48).coerceIn(0.18, 0.76)
                                out.add(
                                    HistoricalMatch(
                                        id = "seed-$leagueId-$seq", sport = sport, leagueId = leagueId, dateIso = date,
                                        homeTeam = h.first, awayTeam = a.first, homeScore = hs, awayScore = ascore,
                                        oddsHome = 1.0 / (pH * 1.05), oddsDraw = 3.55, oddsAway = 1.0 / ((1.0 - pH - 0.26).coerceAtLeast(0.12) * 1.05),
                                        homeHtGoals = min(hs, (hs * 0.45).roundToInt()), awayHtGoals = min(ascore, (ascore * 0.45).roundToInt()),
                                        homeShots = (10 + h.second * 3 + (u1 - 0.5) * 5).roundToInt(),
                                        awayShots = (8 + a.second * 2.8 + (u2 - 0.5) * 5).roundToInt(),
                                        homeSot = max(hs, (4 + h.second * 1.2 + u1 * 2).roundToInt()),
                                        awaySot = max(ascore, (3 + a.second * 1.1 + u2 * 2).roundToInt()),
                                        homeCorners = (4 + h.second * 1.5 + u1 * 2).roundToInt(),
                                        awayCorners = (3 + a.second * 1.4 + u2 * 2).roundToInt(),
                                        homeFouls = (10 + u1 * 4).roundToInt(), awayFouls = (11 + u2 * 4).roundToInt(),
                                        homeYellows = (1 + u1 * 2).roundToInt(), awayYellows = (2 + u2 * 2).roundToInt(),
                                        homeReds = if (u1 > 0.94) 1 else 0, awayReds = if (u2 > 0.93) 1 else 0,
                                    )
                                )
                            }
                            Sport.NFL -> {
                                val diff = (h.second - a.second) * 18.0 + 2.5
                                val hs = max(7, (23 + diff * 0.5 + (u1 - 0.5) * 14).roundToInt())
                                var ascore = max(3, (21 - diff * 0.5 + (u2 - 0.5) * 14).roundToInt())
                                if (hs == ascore) ascore -= 3
                                out.add(
                                    HistoricalMatch(
                                        id = "seed-nfl-$seq", sport = sport, leagueId = leagueId, dateIso = date,
                                        homeTeam = h.first, awayTeam = a.first, homeScore = hs, awayScore = ascore,
                                        spreadLine = (diff * 0.7), totalLine = 45.5, homeRestDays = 7, awayRestDays = 7,
                                    )
                                )
                            }
                            Sport.NBA -> {
                                val hs = (114 + (h.second - a.second) * 10 + (u1 - 0.5) * 14).roundToInt()
                                var ascore = (111 - (h.second - a.second) * 10 + (u2 - 0.5) * 14).roundToInt()
                                if (hs == ascore) ascore -= 2
                                out.add(HistoricalMatch("seed-nba-$seq", sport, leagueId, date, h.first, a.first, hs, ascore))
                            }
                            Sport.NHL -> {
                                val hs = max(1, (3.2 + (h.second - a.second) * 2 + (u1 - 0.5) * 2.5).roundToInt())
                                var ascore = max(0, (2.7 - (h.second - a.second) * 2 + (u2 - 0.5) * 2.5).roundToInt())
                                if (hs == ascore) ascore = max(0, ascore - 1)
                                out.add(HistoricalMatch("seed-nhl-$seq", sport, leagueId, date, h.first, a.first, hs, ascore))
                            }
                            Sport.MLB -> {
                                val hs = max(1, (4.7 + (h.second - a.second) * 2.2 + (u1 - 0.5) * 4).roundToInt())
                                var ascore = max(0, (4.2 - (h.second - a.second) * 2.2 + (u2 - 0.5) * 4).roundToInt())
                                if (hs == ascore) ascore = max(0, ascore - 1)
                                out.add(HistoricalMatch("seed-mlb-$seq", sport, leagueId, date, h.first, a.first, hs, ascore))
                            }
                            Sport.TENNIS -> {
                                val pH = (0.5 + (h.second - a.second) * 0.9).coerceIn(0.2, 0.85)
                                val win = u1 < pH
                                out.add(
                                    HistoricalMatch(
                                        id = "seed-atp-$seq", sport = sport, leagueId = leagueId, dateIso = date,
                                        homeTeam = h.first, awayTeam = a.first,
                                        homeScore = if (win) 2 else 0, awayScore = if (win) 0 else 2,
                                        surface = surfaces[seq % 3],
                                        homeAces = (6 + h.second * 2).roundToInt(), awayAces = (5 + a.second * 2).roundToInt(),
                                        homeDoubleFaults = 2, awayDoubleFaults = 3,
                                        homeFirstServeWonPct = 70.0 + h.second * 3.0, awayFirstServeWonPct = 69.0 + a.second * 3.0,
                                    )
                                )
                            }
                            Sport.CRICKET -> {
                                val hs = (170 + (h.second - a.second) * 25 + (u1 - 0.5) * 26).roundToInt()
                                val ascore = (164 - (h.second - a.second) * 25 + (u2 - 0.5) * 26).roundToInt()
                                out.add(HistoricalMatch("seed-cri-$seq", sport, leagueId, date, h.first, a.first, hs, ascore))
                            }
                            else -> {
                                val win = u1 < (0.5 + (h.second - a.second) * 0.9).coerceIn(0.2, 0.85)
                                out.add(HistoricalMatch("seed-${sport.name}-$seq", sport, leagueId, date, h.first, a.first, if (win) 1 else 0, if (win) 0 else 1))
                            }
                        }
                        seq++
                    }
                }
            }
        }
        return out
    }

    fun buildSeedUpcoming(now: Instant = Instant.now()): List<RawUpcomingEvent> {
        fun d(days: Long) = now.plus(days, ChronoUnit.DAYS).toString().take(16) + "Z"
        return listOf(
            RawUpcomingEvent("fix-e0-1", Sport.FOOTBALL, "E0", "Premier League", d(1), "Arsenal", "Chelsea", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.78, 3.85, 4.75),
                BookmakerOddsLine("Polymarket (Exchange)", true, 1.80, 3.75, 4.65),
                BookmakerOddsLine("Soft Book Avg", false, 1.72, 3.70, 4.50),
            )),
            RawUpcomingEvent("fix-e0-2", Sport.FOOTBALL, "E0", "Premier League", d(1), "Liverpool", "Manchester City", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 2.38, 3.45, 2.98),
                BookmakerOddsLine("Soft Book Avg", false, 2.30, 3.35, 2.90),
            )),
            RawUpcomingEvent("fix-sp1-1", Sport.FOOTBALL, "SP1", "La Liga", d(2), "Barcelona", "Atletico Madrid", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.90, 3.70, 4.15),
                BookmakerOddsLine("Soft Book Avg", false, 1.84, 3.55, 4.00),
            )),
            RawUpcomingEvent("fix-d1-1", Sport.FOOTBALL, "D1", "Bundesliga", d(2), "Bayern Munich", "Bayer Leverkusen", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.83, 3.95, 4.25),
            )),
            RawUpcomingEvent("fix-nfl-1", Sport.NFL, "NFL", "NFL", d(2), "Buffalo Bills", "Kansas City Chiefs", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.92, null, 1.96),
                BookmakerOddsLine("Kalshi (Exchange)", true, 1.94, null, 1.95),
            )),
            RawUpcomingEvent("fix-nba-1", Sport.NBA, "NBA", "NBA", d(1), "Boston Celtics", "Denver Nuggets", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.64, null, 2.38),
            )),
            RawUpcomingEvent("fix-nhl-1", Sport.NHL, "NHL", "NHL", d(1), "Florida Panthers", "Edmonton Oilers", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.88, null, 2.02),
            )),
            RawUpcomingEvent("fix-mlb-1", Sport.MLB, "MLB", "MLB", d(2), "Los Angeles Dodgers", "New York Yankees", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.75, null, 2.18),
            )),
            RawUpcomingEvent("fix-atp-1", Sport.TENNIS, "ATP", "ATP Tour", d(1), "Jannik Sinner", "Carlos Alcaraz", surface = "Hard", bookOdds = listOf(
                BookmakerOddsLine("Pinnacle (Sharp)", true, 1.85, null, 2.04),
            )),
            RawUpcomingEvent("fix-cri-1", Sport.CRICKET, "ICC", "T20 International", d(2), "India", "Australia", bookOdds = listOf(
                BookmakerOddsLine("Exchange Avg", true, 1.82, null, 2.08),
            )),
            RawUpcomingEvent("fix-f1-1", Sport.F1, "F1", "Formula 1 H2H", d(3), "Max Verstappen", "Lando Norris", bookOdds = listOf(
                BookmakerOddsLine("Exchange Avg", true, 1.76, null, 2.14),
            )),
            RawUpcomingEvent("fix-mma-1", Sport.MMA, "UFC", "UFC Main Event", d(3), "Islam Makhachev", "Ilia Topuria", bookOdds = listOf(
                BookmakerOddsLine("Sharp Book", true, 1.70, null, 2.25),
            )),
        )
    }
}
