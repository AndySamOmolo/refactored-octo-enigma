package com.edgeledger.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "app_snapshot")
data class SnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val lastSyncEpochMs: Long,
    val isSeedData: Boolean,
    val totalHistoricalMatches: Int,
    val payloadJson: String,
)

/**
 * Immutable SHA-256 locked model pick ledger (TR-02).
 * Primary key is [fixtureId] so at most one pre-kickoff lock exists per fixture.
 */
@Entity(
    tableName = "locked_picks",
    indices = [Index(value = ["lockedAtEpochMs"]), Index(value = ["hashSha256"])],
)
data class LockedPickEntity(
    @PrimaryKey val fixtureId: String,
    val hashSha256: String,
    val sport: String,
    val matchup: String,
    val pickLabel: String,
    val chancePct: Int,
    val modelVersion: String,
    val lockedAtIso: String,
    val lockedAtEpochMs: Long = 0L,
)

/**
 * Personal bet tracker with Closing Line Value, status & actual cash-out P&L (TR-03).
 */
@Entity(
    tableName = "personal_bets",
    indices = [Index(value = ["createdAtEpochMs"]), Index(value = ["fixtureId"])],
)
data class PersonalBetEntity(
    @PrimaryKey val id: String,
    val createdAtIso: String,
    val fixtureId: String?,
    val sport: String,
    val leagueName: String,
    val matchup: String,
    val selection: String,
    val decimalOdds: Double,
    val closingOdds: Double? = null,
    val modelProb: Double,
    val stakeAmount: Double,
    val status: String = "PENDING", // PENDING, WON, LOST, VOID, PUSH, HALF_WIN, HALF_LOSS, CASH_OUT
    val notes: String = "",
    val isPaper: Boolean = false,
    val bookmaker: String = "Manual",
    val settledPnl: Double? = null,
    val createdAtEpochMs: Long = 0L,
)

/**
 * Real observed timestamped odds snapshots per fixture across live syncs.
 * Replaces fabricated PRNG line-movement history.
 */
@Entity(
    tableName = "odds_history",
    primaryKeys = ["fixtureId", "recordedAtEpochMs"],
    indices = [Index(value = ["recordedAtEpochMs"])],
)
data class OddsHistoryEntity(
    val fixtureId: String,
    val recordedAtEpochMs: Long,
    val label: String,
    val homeProbPct: Double,
    val sharpHomeProbPct: Double?,
    val retailHomeProbPct: Double?,
)

/**
 * Permanent append-only ledger of pre-kickoff model predictions (never deleted/pruned).
 * Auto-snapshotted before kickoff and auto-graded once actual final scores arrive.
 */
@Entity(
    tableName = "prediction_history",
    indices = [
        Index(value = ["kickoffEpochMs"]),
        Index(value = ["resultStatus", "kickoffEpochMs"]),
        Index(value = ["sport", "leagueId"]),
    ],
)
data class PredictionHistoryEntity(
    @PrimaryKey val fixtureId: String,
    val sport: String,
    val leagueId: String,
    val leagueName: String,
    val kickoffIso: String,
    val kickoffEpochMs: Long,
    val homeTeam: String,
    val awayTeam: String,
    val topPickLabel: String,
    val topPickCategory: String,
    val topPickChancePct: Int,
    val probHome: Double,
    val probDraw: Double,
    val probAway: Double,
    val over25Pct: Int,
    val bttsYesPct: Int,
    val projectedHome: Double,
    val projectedAway: Double,
    val bestValueLabel: String? = null,
    val bestValueEdgePct: Double? = null,
    val modelVersion: String,
    val hashSha256: String,
    val lockedAtEpochMs: Long,
    val actualHomeScore: Int? = null,
    val actualAwayScore: Int? = null,
    val resultStatus: String = "PENDING", // PENDING, WON, LOST, PUSH
    val brierScore: Double? = null,
    val gradedAtEpochMs: Long? = null,
)

/**
 * Persistent TTL + ETag response cache for API-keyed sports providers so repeated syncs
 * never waste free-tier request quotas or trigger HTTP 429 rate limits.
 */
@Entity(
    tableName = "api_response_cache",
    indices = [Index(value = ["provider", "expiresAtEpochMs"]), Index(value = ["expiresAtEpochMs"])],
)
data class ApiResponseCacheEntity(
    @PrimaryKey val cacheKey: String,
    val provider: String,
    val payloadJson: String,
    val fetchedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val httpStatus: Int,
    val etag: String? = null,
)

/**
 * Persistent per-provider rate-limit & free-tier quota ledger across app restarts.
 */
@Entity(tableName = "api_quota_ledger")
data class ApiQuotaEntity(
    @PrimaryKey val provider: String,
    val windowKey: String,
    val callsUsed: Int,
    val serverRemaining: Int? = null,
    val hardLimit: Int,
    val lastStatus: String,
    val cooldownUntilEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
)

@Dao
interface EdgeLedgerDao {
    @Query("SELECT * FROM app_snapshot WHERE id = 1")
    suspend fun getSnapshot(): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(snapshot: SnapshotEntity)

    @Query("SELECT * FROM locked_picks ORDER BY lockedAtEpochMs DESC, lockedAtIso DESC LIMIT 100")
    fun observeLockedPicks(): Flow<List<LockedPickEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLockedPicks(picks: List<LockedPickEntity>)

    @Query("DELETE FROM locked_picks WHERE lockedAtEpochMs > 0 AND lockedAtEpochMs < :cutoffEpochMs")
    suspend fun pruneOldLockedPicks(cutoffEpochMs: Long)

    @Query("SELECT * FROM personal_bets ORDER BY createdAtEpochMs DESC, createdAtIso DESC")
    fun observePersonalBets(): Flow<List<PersonalBetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBet(bet: PersonalBetEntity)

    @Query("UPDATE personal_bets SET status = :status, settledPnl = :settledPnl WHERE id = :id")
    suspend fun updateBetStatus(id: String, status: String, settledPnl: Double? = null)

    @Query("UPDATE personal_bets SET closingOdds = :closingOdds WHERE id = :id")
    suspend fun updateClosingOdds(id: String, closingOdds: Double)

    @Query("DELETE FROM personal_bets WHERE id = :id")
    suspend fun deleteBet(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOddsHistory(entries: List<OddsHistoryEntity>)

    @Query("SELECT * FROM odds_history WHERE fixtureId IN (:fixtureIds) ORDER BY recordedAtEpochMs ASC")
    suspend fun getOddsHistoryForFixtures(fixtureIds: List<String>): List<OddsHistoryEntity>

    @Query("DELETE FROM odds_history WHERE recordedAtEpochMs < :cutoffEpochMs")
    suspend fun pruneOldOddsHistory(cutoffEpochMs: Long)

    // Permanent append-only prediction history (no DELETE method by design)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPredictionHistory(entries: List<PredictionHistoryEntity>)

    @Query("SELECT * FROM prediction_history WHERE resultStatus = 'PENDING' AND kickoffEpochMs > 0 AND kickoffEpochMs <= :cutoffEpochMs")
    suspend fun getPendingPredictionsPastKickoff(cutoffEpochMs: Long): List<PredictionHistoryEntity>

    @Query(
        """
        UPDATE prediction_history
        SET actualHomeScore = :homeScore,
            actualAwayScore = :awayScore,
            resultStatus = :status,
            brierScore = :brier,
            gradedAtEpochMs = :gradedAtMs
        WHERE fixtureId = :fixtureId AND resultStatus = 'PENDING'
        """
    )
    suspend fun gradePrediction(
        fixtureId: String,
        homeScore: Int,
        awayScore: Int,
        status: String,
        brier: Double,
        gradedAtMs: Long,
    )

    @Query("SELECT * FROM prediction_history ORDER BY kickoffEpochMs DESC, lockedAtEpochMs DESC LIMIT 500")
    fun observePredictionHistory(): Flow<List<PredictionHistoryEntity>>

    // Persistent API response cache & quota ledger
    @Query("SELECT * FROM api_response_cache WHERE cacheKey = :key")
    suspend fun getApiCache(key: String): ApiResponseCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putApiCache(entry: ApiResponseCacheEntity)

    @Query("DELETE FROM api_response_cache WHERE expiresAtEpochMs < :cutoffEpochMs")
    suspend fun pruneExpiredApiCache(cutoffEpochMs: Long)

    @Query("SELECT * FROM api_quota_ledger WHERE provider = :provider")
    suspend fun getApiQuota(provider: String): ApiQuotaEntity?

    @Query("SELECT * FROM api_quota_ledger ORDER BY provider ASC")
    fun observeApiQuotas(): Flow<List<ApiQuotaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApiQuota(entry: ApiQuotaEntity)
}

@Database(
    entities = [
        SnapshotEntity::class,
        LockedPickEntity::class,
        PersonalBetEntity::class,
        OddsHistoryEntity::class,
        PredictionHistoryEntity::class,
        ApiResponseCacheEntity::class,
        ApiQuotaEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): EdgeLedgerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Non-destructive migration from Room v2 -> v3:
         * - Preserves all user bets in personal_bets (adds settledPnl + createdAtEpochMs + indexes)
         * - Rebuilds locked_picks with fixtureId PK (deduplicating prior multi-locks per fixture)
         * - Creates odds_history table for real multi-sync line movement tracking
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_snapshot` (
                        `id` INTEGER NOT NULL,
                        `lastSyncEpochMs` INTEGER NOT NULL,
                        `isSeedData` INTEGER NOT NULL,
                        `totalHistoricalMatches` INTEGER NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `locked_picks` (
                        `hashSha256` TEXT NOT NULL,
                        `fixtureId` TEXT NOT NULL,
                        `sport` TEXT NOT NULL,
                        `matchup` TEXT NOT NULL,
                        `pickLabel` TEXT NOT NULL,
                        `chancePct` INTEGER NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `lockedAtIso` TEXT NOT NULL,
                        PRIMARY KEY(`hashSha256`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_bets` (
                        `id` TEXT NOT NULL,
                        `createdAtIso` TEXT NOT NULL,
                        `fixtureId` TEXT,
                        `sport` TEXT NOT NULL,
                        `leagueName` TEXT NOT NULL,
                        `matchup` TEXT NOT NULL,
                        `selection` TEXT NOT NULL,
                        `decimalOdds` REAL NOT NULL,
                        `closingOdds` REAL,
                        `modelProb` REAL NOT NULL,
                        `stakeAmount` REAL NOT NULL,
                        `status` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `isPaper` INTEGER NOT NULL,
                        `bookmaker` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personal_bets ADD COLUMN settledPnl REAL")
                db.execSQL("ALTER TABLE personal_bets ADD COLUMN createdAtEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_bets_createdAtEpochMs ON personal_bets(createdAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_bets_fixtureId ON personal_bets(fixtureId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS locked_picks_new (
                        fixtureId TEXT NOT NULL PRIMARY KEY,
                        hashSha256 TEXT NOT NULL,
                        sport TEXT NOT NULL,
                        matchup TEXT NOT NULL,
                        pickLabel TEXT NOT NULL,
                        chancePct INTEGER NOT NULL,
                        modelVersion TEXT NOT NULL,
                        lockedAtIso TEXT NOT NULL,
                        lockedAtEpochMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO locked_picks_new (fixtureId, hashSha256, sport, matchup, pickLabel, chancePct, modelVersion, lockedAtIso, lockedAtEpochMs)
                    SELECT fixtureId, hashSha256, sport, matchup, pickLabel, chancePct, modelVersion, lockedAtIso, 0
                    FROM locked_picks
                    ORDER BY lockedAtIso ASC
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE locked_picks")
                db.execSQL("ALTER TABLE locked_picks_new RENAME TO locked_picks")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locked_picks_lockedAtEpochMs ON locked_picks(lockedAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locked_picks_hashSha256 ON locked_picks(hashSha256)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS odds_history (
                        fixtureId TEXT NOT NULL,
                        recordedAtEpochMs INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        homeProbPct REAL NOT NULL,
                        sharpHomeProbPct REAL,
                        retailHomeProbPct REAL,
                        PRIMARY KEY(fixtureId, recordedAtEpochMs)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_odds_history_fixtureId ON odds_history(fixtureId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_odds_history_fixtureId`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_odds_history_recordedAtEpochMs` ON `odds_history` (`recordedAtEpochMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prediction_history` (
                        `fixtureId` TEXT NOT NULL,
                        `sport` TEXT NOT NULL,
                        `leagueId` TEXT NOT NULL,
                        `leagueName` TEXT NOT NULL,
                        `kickoffIso` TEXT NOT NULL,
                        `kickoffEpochMs` INTEGER NOT NULL,
                        `homeTeam` TEXT NOT NULL,
                        `awayTeam` TEXT NOT NULL,
                        `topPickLabel` TEXT NOT NULL,
                        `topPickCategory` TEXT NOT NULL,
                        `topPickChancePct` INTEGER NOT NULL,
                        `probHome` REAL NOT NULL,
                        `probDraw` REAL NOT NULL,
                        `probAway` REAL NOT NULL,
                        `over25Pct` INTEGER NOT NULL,
                        `bttsYesPct` INTEGER NOT NULL,
                        `projectedHome` REAL NOT NULL,
                        `projectedAway` REAL NOT NULL,
                        `bestValueLabel` TEXT,
                        `bestValueEdgePct` REAL,
                        `modelVersion` TEXT NOT NULL,
                        `hashSha256` TEXT NOT NULL,
                        `lockedAtEpochMs` INTEGER NOT NULL,
                        `actualHomeScore` INTEGER,
                        `actualAwayScore` INTEGER,
                        `resultStatus` TEXT NOT NULL,
                        `brierScore` REAL,
                        `gradedAtEpochMs` INTEGER,
                        PRIMARY KEY(`fixtureId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_history_kickoffEpochMs` ON `prediction_history` (`kickoffEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_history_resultStatus` ON `prediction_history` (`resultStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_history_sport_leagueId` ON `prediction_history` (`sport`, `leagueId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `api_response_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `fetchedAtEpochMs` INTEGER NOT NULL,
                        `expiresAtEpochMs` INTEGER NOT NULL,
                        `httpStatus` INTEGER NOT NULL,
                        `etag` TEXT,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_response_cache_provider_expiresAtEpochMs` ON `api_response_cache` (`provider`, `expiresAtEpochMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `api_quota_ledger` (
                        `provider` TEXT NOT NULL,
                        `windowKey` TEXT NOT NULL,
                        `callsUsed` INTEGER NOT NULL,
                        `serverRemaining` INTEGER,
                        `hardLimit` INTEGER NOT NULL,
                        `lastStatus` TEXT NOT NULL,
                        `cooldownUntilEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`provider`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_prediction_history_resultStatus`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_history_resultStatus_kickoffEpochMs` ON `prediction_history` (`resultStatus`, `kickoffEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_response_cache_expiresAtEpochMs` ON `api_response_cache` (`expiresAtEpochMs`)")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edgeledger_offline.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
