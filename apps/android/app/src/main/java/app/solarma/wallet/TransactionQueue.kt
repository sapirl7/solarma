package app.solarma.wallet

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Pending transaction for offline queue.
 */
@Entity(tableName = "pending_transactions")
data class PendingTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Transaction type: CREATE_ALARM, CLAIM, SNOOZE, SLASH */
    val type: String,
    
    /** Serialized transaction bytes (Base64) */
    val transactionData: String,
    
    /** Alarm ID this transaction relates to */
    val alarmId: Long?,
    
    /** Number of retry attempts */
    val retryCount: Int = 0,
    
    /** Last error message if any */
    val lastError: String? = null,
    
    /** Created timestamp */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Last attempt timestamp */
    val lastAttemptAt: Long? = null,
    
    /** Transaction status: PENDING, SENDING, CONFIRMED, FAILED */
    val status: String = "PENDING"
)

/**
 * DAO for pending transactions.
 */
@Dao
interface PendingTransactionDao {
    
    @Query("SELECT * FROM pending_transactions WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingTransactions(): Flow<List<PendingTransaction>>
    
    @Query("SELECT * FROM pending_transactions ORDER BY createdAt DESC")
    fun getAllTransactions(): Flow<List<PendingTransaction>>
    
    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getById(id: Long): PendingTransaction?
    
    @Insert
    suspend fun insert(tx: PendingTransaction): Long
    
    @Update
    suspend fun update(tx: PendingTransaction)
    
    @Delete
    suspend fun delete(tx: PendingTransaction)
    
    @Query("UPDATE pending_transactions SET status = :status, lastError = :error, retryCount = retryCount + 1, lastAttemptAt = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?, timestamp: Long)
    
    @Query("DELETE FROM pending_transactions WHERE status = 'CONFIRMED'")
    suspend fun deleteConfirmed()
    
    @Query("SELECT COUNT(*) FROM pending_transactions WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int
}
