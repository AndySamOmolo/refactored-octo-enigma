package com.edgeledger.app.data

/**
 * Pure static stadium GPS coordinates used exclusively to query live Open-Meteo weather.
 * Zero synthetic fixtures, zero hardcoded ratings, and zero hardcoded rosters exist here.
 */
data class StadiumLocation(
    val stadium: String,
    val lat: Double,
    val lon: Double,
    val isDome: Boolean = false,
)

object SeedData {
    val STADIUM_GPS: Map<String, StadiumLocation> = mapOf(
        "Arsenal" to StadiumLocation("Emirates Stadium", 51.5549, -0.1084),
        "Liverpool" to StadiumLocation("Anfield", 53.4308, -2.9608),
        "Chelsea" to StadiumLocation("Stamford Bridge", 51.4817, -0.1910),
        "Manchester City" to StadiumLocation("Etihad Stadium", 53.4831, -2.2004),
        "Manchester United" to StadiumLocation("Old Trafford", 53.4631, -2.2913),
        "Tottenham Hotspur" to StadiumLocation("Tottenham Hotspur Stadium", 51.6042, -0.0662),
        "Newcastle United" to StadiumLocation("St James' Park", 54.9756, -1.6217),
        "Aston Villa" to StadiumLocation("Villa Park", 52.5092, -1.8847),
        "Barcelona" to StadiumLocation("Camp Nou", 41.3809, 2.1228),
        "Real Madrid" to StadiumLocation("Santiago Bernabeu", 40.4530, -3.6883),
        "Atletico Madrid" to StadiumLocation("Metropolitano", 40.4362, -3.5995),
        "Bayern Munich" to StadiumLocation("Allianz Arena", 48.2188, 11.6247),
        "Borussia Dortmund" to StadiumLocation("Signal Iduna Park", 51.4926, 7.4519),
        "Inter Milan" to StadiumLocation("San Siro", 45.4781, 9.1240),
        "AC Milan" to StadiumLocation("San Siro", 45.4781, 9.1240),
        "Paris Saint-Germain" to StadiumLocation("Parc des Princes", 48.8414, 2.2530),
        "Buffalo Bills" to StadiumLocation("Highmark Stadium", 42.7738, -78.7870),
        "Kansas City Chiefs" to StadiumLocation("Arrowhead Stadium", 39.0489, -94.4839),
        "Detroit Lions" to StadiumLocation("Ford Field", 42.3400, -83.0456, isDome = true),
    )
}
