package app.solarma.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.wallet.PendingTransaction
import app.solarma.wallet.PendingTransactionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for transaction history screen.
 */
@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val transactionDao: PendingTransactionDao,
    ) : ViewModel() {
        val transactions: StateFlow<List<PendingTransaction>> =
            transactionDao.getAllTransactions()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        /**
         * Delete a confirmed transaction from history.
         */
        fun deleteTransaction(tx: PendingTransaction) {
            viewModelScope.launch {
                transactionDao.delete(tx)
            }
        }
    }
