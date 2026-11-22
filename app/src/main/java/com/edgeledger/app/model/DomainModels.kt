package com.edgeledger.app.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class Sport(val label: String, val shortCode: String, val hasDraw: Boolean) {
    FOOTBALL("Football", "SOC", true),
    NFL("NFL", "NFL", false),
    NBA("NBA", "NBA", false),
    NHL("NHL", "NHL", false),
    MLB("MLB", "MLB", false),
    TENNIS("Tennis", "ATP", false),
    CRICKET("Cricket", "CRI", false),
    F1("Formula 1", "F1", false),
    MMA("MMA / UFC", "MMA", false),
}

@Serializable
enum class OddsDisplayFormat(val label: String) {
    DECIMAL("Decimal"),
    AMERICAN("American"),
    FRACTIONAL("Fractional"),
    PERCENT("Win %"),
}

@Serializable
enum class RiskStyle(val label: String, val kellyFraction: Double, val maxStakePct: Double) {
    SAFE("Safe", 0.15, 0.03),
    BALANCED("Balanced", 0.25, 0.05),
    BOLD("Bold", 0.40, 0.07),
}

@Serializable
enum class ConfidenceLevel(val label: String) {
    STRONG("Strong"),
    GOOD("Good"),
    LEAN("Lean"),
}

@Serializable
enum class DevigMethod(val label: String) {
    SHIN("Shin (Sharp)"),
    MULTIPLICATIVE("Multiplicative"),
    POWER("Power"),
    ADDITIVE("Additive"),
    ODDS_RATIO("Odds-Ratio"),
}

@Serializable
enum class StakingMode(val label: String) {
    FRACTIONAL_KELLY("Smart Kelly"),
    FLAT("Flat Stake"),
    PERCENT_BANKROLL("% of Balance"),
}

@Immutable
@Serializable
data class PlayerPropPrediction(
    val id: String,
    val playerName: String,
    val team: String,
    val position: String,
    val marketLabel: String,
    val lineLabel: String,
    val projectedValue: Double,
    val overChancePct: Int,
    val rangeLow: Double,
    val rangeHigh: Double,
    val confidenceLabel: String,
    val plainReason: String,
)

@Immutable
@Serializable
data class FeatureDriver(
    val label: String,
    val impactPct: Double,
    val isPositive: Boolean,
    val detail: String,
)

@Immutable
@Serializable
data class LineMovePoint(
    val timeLabel: String,
    val sharpHomeProbPct: Float,
    val retailHomeProbPct: Float,
    val isSteamMove: Boolean = false,
)

@Immutable
@Serializable
data class HistoricalMatch(
    val id: String,
    val sport: Sport,
    val leagueId: String,
    val dateIso: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val surface: String? = null,
    val oddsHome: Double? = null,
    val oddsDraw: Double? = null,
    val oddsAway: Double? = null,
    val homeHtGoals: Int? = null,
    val awayHtGoals: Int? = null,
    val homeShots: Int? = null,
    val awayShots: Int? = null,
    val homeSot: Int? = null,
    val awaySot: Int? = null,
    val homeCorners: Int? = null,
    val awayCorners: Int? = null,
    val homeFouls: Int? = null,
    val awayFouls: Int? = null,
    val homeYellows: Int? = null,
    val awayYellows: Int? = null,
    val homeReds: Int? = null,
    val awayReds: Int? = null,
    val spreadLine: Double? = null,
    val totalLine: Double? = null,
    val homeRestDays: Int? = null,
    val awayRestDays: Int? = null,
    val homeAces: Int? = null,
    val awayAces: Int? = null,
    val homeDoubleFaults: Int? = null,
    val awayDoubleFaults: Int? = null,
    val homeFirstServeWonPct: Double? = null,
    val awayFirstServeWonPct: Double? = null,
)

@Immutable
@Serializable
data class TeamProfile(
    val team: String,
    val sport: Sport,
    val leagueId: String,
    val elo: Double = 1500.0,
    val clubElo: Double? = null,
    val powerScore: Int = 50,
    val gamesPlayed: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val winPct: Double = 50.0,
    val homeWinPct: Double = 55.0,
    val awayWinPct: Double = 45.0,
    val avgScored: Double = 1.4,
    val avgConceded: Double = 1.3,
    val recentForm: List<String> = emptyList(),
    val shotsPerGame: Double = 12.5,
    val shotsOnTargetPerGame: Double = 4.5,
    val cornersPerGame: Double = 5.2,
    val foulsPerGame: Double = 11.2,
    val cardsPerGame: Double = 1.9,
    val redCardsPerGame: Double = 0.08,
    val firstHalfGoalRatePct: Double = 45.0,
    val cleanSheetPct: Double = 30.0,
    val bttsPct: Double = 52.0,
    val over25Pct: Double = 54.0,
    val atsCoverPct: Double = 50.0,
    val overTotalPct: Double = 50.0,
    val avgRestDays: Double = 7.0,
    val hardWinPct: Double = 55.0,
    val clayWinPct: Double = 55.0,
    val grassWinPct: Double = 55.0,
    val avgAces: Double = 6.5,
    val avgDoubleFaults: Double = 2.4,
    val firstServeWinPct: Double = 73.0,
    val logoUrl: String? = null,
)

@Immutable
@Serializable
data class BookmakerOddsLine(
    val bookmaker: String,
    val isSharp: Boolean,
    val homeOdds: Double,
    val drawOdds: Double? = null,
    val awayOdds: Double,
    val updatedLabel: String = "Synced",
)

@Immutable
@Serializable
data class StadiumWeather(
    val stadium: String,
    val tempC: Int,
    val windKmh: Int,
    val rainChancePct: Int,
    val summary: String,
)

@Immutable
@Serializable
data class MarketPickItem(
    val id: String,
    val category: String,
    val label: String,
    val chancePct: Int,
    val confidence: ConfidenceLevel,
    val plainReason: String,
)

@Immutable
@Serializable
data class ExactScorelineItem(
    val score: String,
    val chancePct: Int,
)

@Immutable
@Serializable
data class FullMatchMarkets(
    val projectedHome: Double,
    val projectedAway: Double,
    val probHome: Double,
    val probDraw: Double,
    val probAway: Double,
    val modelVersion: String = "v8.0.0",
    val doubleChanceHomeOrDrawPct: Int = 0,
    val doubleChanceAwayOrDrawPct: Int = 0,
    val drawNoBetHomePct: Int = 0,
    val over05Pct: Int = 0,
    val over15Pct: Int = 0,
    val over25Pct: Int = 0,
    val over35Pct: Int = 0,
    val over45Pct: Int = 0,
    val bttsYesPct: Int = 0,
    val homeOver15Pct: Int = 0,
    val awayOver15Pct: Int = 0,
    val homeCleanSheetPct: Int = 0,
    val homeWinToNilPct: Int = 0,
    val homeWinAndOver15Pct: Int = 0,
    val homeWinAndBttsPct: Int = 0,
    val homeWinBy2PlusPct: Int = 0,
    val awayPlus15Pct: Int = 0,
    val firstHalfOver05Pct: Int = 0,
    val firstHalfOver15Pct: Int = 0,
    val secondHalfOver05Pct: Int = 0,
    val topHtFt: List<Pair<String, Int>> = emptyList(),
    val projectedCorners: Double = 0.0,
    val cornersOver85Pct: Int = 0,
    val cornersOver95Pct: Int = 0,
    val projectedCards: Double = 0.0,
    val cardsOver35Pct: Int = 0,
    val redCardChancePct: Int = 0,
    val projectedFouls: Double = 0.0,
    val foulsOver215Pct: Int = 0,
    val projectedShotsTotal: Double = 0.0,
    val projectedSotTotal: Double = 0.0,
    val topScorelines: List<ExactScorelineItem> = emptyList(),
    val projectedScoreDisplay: String = "",
    val projectedSpreadDisplay: String = "",
    val altSpreadLabel: String = "",
    val altSpreadPct: Int = 0,
    val projectedTotal: Double = 0.0,
    val totalLine: Double = 0.0,
    val totalOverPct: Int = 0,
    val halftimeDisplay: String = "",
    val closeGameLabel: String = "",
    val closeGamePct: Int = 0,
    val overtimeChancePct: Int = 0,
    val firstSetHomePct: Int = 0,
    val straightSetsPct: Int = 0,
    val goesToThreeSetsPct: Int = 0,
    val gameHandicapLabel: String = "",
    val gameHandicapPct: Int = 0,
    val tiebreakChancePct: Int = 0,
    val projectedAces: Double = 0.0,
    val projectedDoubleFaults: Double = 0.0,
    val projectedTotalGames: Double = 0.0,
    val totalGamesLine: Double = 22.5,
    val totalGamesOverPct: Int = 0,
    val topPicks: List<MarketPickItem> = emptyList(),
)

@Immutable
@Serializable
data class HeadToHeadGame(
    val dateIso: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
)

@Immutable
@Serializable
data class MatchFixture(
    val id: String,
    val sport: Sport,
    val leagueId: String,
    val leagueName: String,
    val kickoffIso: String,
    val homeTeam: String,
    val awayTeam: String,
    val surface: String? = null,
    val markets: FullMatchMarkets,
    val plainInsights: List<String>,
    val bookmakerOdds: List<BookmakerOddsLine>,
    val h2hGames: List<HeadToHeadGame>,
    val weather: StadiumWeather? = null,
    val bestValueLabel: String? = null,
    val bestValueEdgePct: Double? = null,
    val bestSuggestedStakePct: Double? = null,
    val playerProps: List<PlayerPropPrediction> = emptyList(),
    val featureDrivers: List<FeatureDriver> = emptyList(),
    val lineMovement: List<LineMovePoint> = emptyList(),
    val homeRangeLowPct: Int = 0,
    val homeRangeHighPct: Int = 0,
    val marketHoldPct: Double = 0.0,
    val homeLogoUrl: String? = null,
    val awayLogoUrl: String? = null,
)

@Immutable
@Serializable
data class ReliabilityBin(
    val rangeLabel: String,
    val actualWinPct: Int,
    val count: Int,
)

@Immutable
@Serializable
data class LeagueTrackRecord(
    val sport: Sport,
    val leagueId: String,
    val leagueName: String,
    val gamesTested: Int,
    val accuracyScore: Int,
    val beatsSimpleGuess: Boolean,
    val simulatedProfitPct: Double,
    val hasGradedOdds: Boolean = true,
    val reliabilityBins: List<ReliabilityBin>,
)

@Immutable
@Serializable
data class ProviderHealth(
    val id: String,
    val name: String,
    val sport: Sport,
    val status: String, // "LIVE", "DEGRADED", "OFFLINE", "CIRCUIT_OPEN", "OFF-SEASON"
    val recordsLoaded: Int,
    val lastUpdatedIso: String,
    val note: String,
)

@Immutable
@Serializable
data class PredictionMarketQuote(
    val source: String, // "Polymarket" or "Kalshi"
    val question: String,
    val yesProbPct: Int,
    val noProbPct: Int,
)

@Immutable
@Serializable
data class LivePlayerEntry(
    val name: String,
    val position: String,
    val statCategory: String = "",
    val statValue: Double? = null,
)
