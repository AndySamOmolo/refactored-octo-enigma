package com.edgeledger.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
 */
@Entity(tableName = "locked_picks")
data class LockedPickEntity(
    @PrimaryKey val hashSha256: String,
    val fixtureId: String,
    val sport: String,
    val matchup: String,
    val pickLabel: String,
    val chancePct: Int,
    val modelVersion: String,
    val lockedAtIso: String,
)

/**
 * Personal bet tracker with Closing Line Value & status (TR-03).
 */
@Entity(tableName = "personal_bets")
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
    val status: String = "PENDING", // PENDING, WON, LOST, VOID
    val notes: String = "",
)

@Dao
interface EdgeLedgerDao {
    @Query("SELECT * FROM app_snapshot WHERE id = 1")
    suspend fun getSnapshot(): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(snapshot: SnapshotEntity)

    @Query("SELECT * FROM locked_picks ORDER BY lockedAtIso DESC LIMIT 100")
    fun observeLockedPicks(): Flow<List<LockedPickEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLockedPicks(picks: List<LockedPickEntity>)

    @Query("SELECT * FROM personal_bets ORDER BY createdAtIso DESC")
    fun observePersonalBets(): Flow<List<PersonalBetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBet(bet: PersonalBetEntity)

    @Query("UPDATE personal_bets SET status = :status WHERE id = :id")
    suspend fun updateBetStatus(id: String, status: String)

    @Query("UPDATE personal_bets SET closingOdds = :closingOdds WHERE id = :id")
    suspend fun updateClosingOdds(id: String, closingOdds: Double)

    @Query("DELETE FROM personal_bets WHERE id = :id")
    suspend fun deleteBet(id: String)
}

@Database(
    entities = [SnapshotEntity::class, LockedPickEntity::class, PersonalBetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): EdgeLedgerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edgeledger_offline.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
