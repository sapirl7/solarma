package app.solarma.data.local

import androidx.room.*
import app.solarma.wallet.PendingTransaction
import app.solarma.wallet.PendingTransactionDao
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for local alarm storage.
 */
@Entity(tableName = "alarms")
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
    
    /** NFC/QR tag hash (Base64) */
    val tagHash: String? = null,
    
    /** Whether alarm has onchain deposit */
    val hasDeposit: Boolean = false,
    
    /** Onchain alarm pubkey (Base58) */
    val onchainPubkey: String? = null,
    
    /** Created timestamp */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Last triggered timestamp */
    val lastTriggeredAt: Long? = null
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
}

/**
 * Room database for Solarma.
 */
@Database(
    entities = [AlarmEntity::class, PendingTransaction::class],
    version = 2,
    exportSchema = true
)
abstract class SolarmaDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun pendingTransactionDao(): PendingTransactionDao
}
