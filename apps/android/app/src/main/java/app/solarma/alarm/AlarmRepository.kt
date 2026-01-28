package app.solarma.alarm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for alarm data.
 * Handles local persistence and syncs with onchain state.
 */
@Singleton
class AlarmRepository @Inject constructor() {
    
    // TODO: Implement Room database for alarm persistence
    // This is a stub for Hilt injection in AlarmService
    
    companion object {
        private const val TAG = "Solarma.AlarmRepository"
    }
}
