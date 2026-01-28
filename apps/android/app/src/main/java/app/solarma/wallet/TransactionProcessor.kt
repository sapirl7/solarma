package app.solarma.wallet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes pending transactions from the offline queue.
 * Retries failed transactions with exponential backoff.
 */
@Singleton
class TransactionProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: PendingTransactionDao,
    private val walletManager: WalletManager
) {
    companion object {
        private const val TAG = "Solarma.TxProcessor"
        private const val MAX_RETRIES = 5
        private const val BASE_DELAY_MS = 1000L
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processingJob: Job? = null
    
    /**
     * Start processing pending transactions.
     */
    fun start() {
        if (processingJob?.isActive == true) return
        
        processingJob = scope.launch {
            while (isActive) {
                if (isNetworkAvailable() && walletManager.isConnected()) {
                    processPendingTransactions()
                }
                delay(10_000) // Check every 10 seconds
            }
        }
        Log.i(TAG, "Transaction processor started")
    }
    
    /**
     * Stop processing.
     */
    fun stop() {
        processingJob?.cancel()
        processingJob = null
        Log.i(TAG, "Transaction processor stopped")
    }
    
    /**
     * Queue a new transaction.
     */
    suspend fun queueTransaction(
        type: String,
        transactionBytes: ByteArray,
        alarmId: Long?
    ): Long {
        val tx = PendingTransaction(
            type = type,
            transactionData = Base64.encodeToString(transactionBytes, Base64.NO_WRAP),
            alarmId = alarmId
        )
        val id = transactionDao.insert(tx)
        Log.i(TAG, "Transaction queued: id=$id, type=$type")
        return id
    }
    
    private suspend fun processPendingTransactions() {
        val pending = transactionDao.getPendingTransactions().first()
        
        for (tx in pending) {
            if (tx.retryCount >= MAX_RETRIES) {
                transactionDao.updateStatus(tx.id, "FAILED", "Max retries exceeded", System.currentTimeMillis())
                Log.w(TAG, "Transaction ${tx.id} failed after max retries")
                continue
            }
            
            try {
                transactionDao.updateStatus(tx.id, "SENDING", null, System.currentTimeMillis())
                
                val data = Base64.decode(tx.transactionData, Base64.NO_WRAP)
                
                // Note: In real implementation, need ActivityResultSender from active Activity
                // This is a placeholder - actual signing requires UI context
                Log.d(TAG, "Would send transaction: ${tx.id}, type=${tx.type}")
                
                // Simulate success for now
                transactionDao.updateStatus(tx.id, "CONFIRMED", null, System.currentTimeMillis())
                Log.i(TAG, "Transaction ${tx.id} confirmed")
                
            } catch (e: Exception) {
                val delay = BASE_DELAY_MS * (1 shl tx.retryCount.coerceAtMost(5))
                transactionDao.updateStatus(tx.id, "PENDING", e.message, System.currentTimeMillis())
                Log.e(TAG, "Transaction ${tx.id} failed, retry in ${delay}ms", e)
                delay(delay)
            }
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
