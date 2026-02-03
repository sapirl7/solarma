package app.solarma.wallet

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pending transaction for offline queue.
 */
@Entity(tableName = "pending_transactions")
data class PendingTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Transaction type: CREATE_ALARM, CLAIM, SNOOZE, SLASH */
    val type: String,
    
    /** Serialized transaction bytes (Base64) - legacy field, no longer used */
    val transactionData: String = "",
    
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
    
    @Query("UPDATE pending_transactions SET status = :status, lastError = :error, lastAttemptAt = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?, timestamp: Long)

    @Query("UPDATE pending_transactions SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE type = :type AND alarmId = :alarmId AND status IN ('PENDING','SENDING')")
    suspend fun countActiveByTypeAndAlarm(type: String, alarmId: Long): Int
    
    @Query("DELETE FROM pending_transactions WHERE status = 'CONFIRMED'")
    suspend fun deleteConfirmed()
    
    @Query("SELECT COUNT(*) FROM pending_transactions WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE type = :type AND alarmId = :alarmId AND status IN ('PENDING','SENDING')")
    fun observeActiveCount(type: String, alarmId: Long): Flow<Int>
}

/**
 * Transaction queue service for managing pending transactions.
 */
@Singleton
class TransactionQueue @Inject constructor(
    private val pendingTransactionDao: PendingTransactionDao
) {
    /**
     * Enqueue a transaction intent for processing.
     * @return Transaction queue ID
     */
    suspend fun enqueue(type: String, alarmId: Long? = null): Long {
        val tx = PendingTransaction(
            type = type,
            alarmId = alarmId
        )
        return pendingTransactionDao.insert(tx)
    }
    
    /**
     * Get all pending transactions.
     */
    fun getPendingTransactions(): Flow<List<PendingTransaction>> {
        return pendingTransactionDao.getPendingTransactions()
    }
    
    /**
     * Mark transaction as confirmed.
     */
    suspend fun markConfirmed(id: Long) {
        pendingTransactionDao.updateStatus(id, "CONFIRMED", null, System.currentTimeMillis())
    }
    
    /**
     * Mark transaction as failed.
     */
    suspend fun markFailed(id: Long, error: String) {
        pendingTransactionDao.updateStatus(id, "FAILED", error, System.currentTimeMillis())
        pendingTransactionDao.incrementRetry(id)
    }

    suspend fun markPendingRetry(id: Long, error: String) {
        pendingTransactionDao.updateStatus(id, "PENDING", error, System.currentTimeMillis())
        pendingTransactionDao.incrementRetry(id)
    }

    suspend fun hasActive(type: String, alarmId: Long): Boolean {
        return pendingTransactionDao.countActiveByTypeAndAlarm(type, alarmId) > 0
    }

    fun observeActive(type: String, alarmId: Long): Flow<Int> {
        return pendingTransactionDao.observeActiveCount(type, alarmId)
    }
}
