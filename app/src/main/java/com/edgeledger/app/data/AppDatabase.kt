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
    indices = [Index(value = ["fixtureId"])],
)
data class OddsHistoryEntity(
    val fixtureId: String,
    val recordedAtEpochMs: Long,
    val label: String,
    val homeProbPct: Double,
    val sharpHomeProbPct: Double?,
    val retailHomeProbPct: Double?,
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
}

@Database(
    entities = [
        SnapshotEntity::class,
        LockedPickEntity::class,
        PersonalBetEntity::class,
        OddsHistoryEntity::class,
    ],
    version = 3,
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

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edgeledger_offline.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
