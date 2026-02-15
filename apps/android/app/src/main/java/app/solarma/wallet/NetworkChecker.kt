package app.solarma.wallet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction for network availability checks.
 * Allows TransactionProcessor and OnchainAlarmService to be tested
 * without Android Context.
 */
interface NetworkChecker {
    fun isNetworkAvailable(): Boolean
}

/**
 * Production implementation using Android ConnectivityManager.
 */
@Singleton
class AndroidNetworkChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkChecker {
    override fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
