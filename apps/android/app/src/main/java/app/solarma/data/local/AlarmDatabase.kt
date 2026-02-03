package app.solarma.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.solarma.wallet.PendingTransaction
import app.solarma.wallet.PendingTransactionDao
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for local alarm storage.
 */
@Entity(
    tableName = "alarms",
    indices = [
        Index(value = ["alarmTimeMillis"]),
        Index(value = ["isEnabled"]),
        Index(value = ["onchainAlarmId"])
    ]
)
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Scheduled alarm time in milliseconds */
    val alarmTimeMillis: Long,
    
    /** Human-readable label */
    val label: String = "",
    
    /** Whether alarm is enabled */
    val isEnabled: Boolean = true,
    
    /** Days of week bitmask (Mon=1, Tue=2, Wed=4, ...) */
    val repeatDays: Int = 0,
    
    /** Wake proof type: 0=None, 1=Steps, 2=NFC, 3=QR */
    val wakeProofType: Int = 0,
    
    /** Target steps for step challenge */
    val targetSteps: Int = 20,
    
    /** NFC tag hash (SHA-256 hex) */
    val tagHash: String? = null,
    
    /** QR code string for QR wake proof */
    val qrCode: String? = null,
    
    /** Whether alarm has onchain deposit */
    val hasDeposit: Boolean = false,
    
    /** Deposit amount in SOL */
    val depositAmount: Double = 0.0,

    /** Deposit amount in lamports (source of truth) */
    val depositLamports: Long = 0,

    /** Penalty route (0=burn, 1=donate, 2=buddy) */
    val penaltyRoute: Int = 0,

    /** Penalty destination (buddy/treasury address) */
    val penaltyDestination: String? = null,

    /** Onchain alarm pubkey (Base58) */
    val onchainPubkey: String? = null,

    /** Onchain alarm id used for PDA derivation */
    val onchainAlarmId: Long? = null,

    /** Onchain snooze count (for penalty calculations) */
    val snoozeCount: Int = 0,
    
    /** Created timestamp */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Last triggered timestamp */
    val lastTriggeredAt: Long? = null,

    /** Last completed timestamp */
    val lastCompletedAt: Long? = null
)

/**
 * DAO for alarm operations.
 */
@Dao
interface AlarmDao {
    
    @Query("SELECT * FROM alarms WHERE isEnabled = 1 ORDER BY alarmTimeMillis ASC")
    fun getEnabledAlarms(): Flow<List<AlarmEntity>>
    
    @Query("SELECT * FROM alarms ORDER BY alarmTimeMillis ASC")
    fun getAllAlarms(): Flow<List<AlarmEntity>>
    
    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE onchainPubkey = :pubkey")
    suspend fun getByOnchainPubkey(pubkey: String): AlarmEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: AlarmEntity): Long
    
    @Update
    suspend fun update(alarm: AlarmEntity)
    
    @Delete
    suspend fun delete(alarm: AlarmEntity)
    
    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("UPDATE alarms SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
    
    @Query("UPDATE alarms SET lastTriggeredAt = :timestamp WHERE id = :id")
    suspend fun updateLastTriggered(id: Long, timestamp: Long)

    @Query("UPDATE alarms SET lastCompletedAt = :timestamp WHERE id = :id")
    suspend fun updateLastCompleted(id: Long, timestamp: Long)
}

/**
 * Room database for Solarma.
 */
@Database(
    entities = [AlarmEntity::class, PendingTransaction::class, StatsEntity::class],
    version = 6,
    exportSchema = false
)
abstract class SolarmaDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun statsDao(): StatsDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN depositLamports INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN penaltyRoute INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN penaltyDestination TEXT")
                db.execSQL("ALTER TABLE alarms ADD COLUMN onchainAlarmId INTEGER")

                db.execSQL(
                    "UPDATE alarms SET depositLamports = CAST(depositAmount * 1000000000 AS INTEGER) " +
                        "WHERE depositLamports = 0 AND depositAmount > 0"
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS index_alarms_alarmTimeMillis ON alarms(alarmTimeMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alarms_isEnabled ON alarms(isEnabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alarms_onchainAlarmId ON alarms(onchainAlarmId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN lastCompletedAt INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeCount INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
