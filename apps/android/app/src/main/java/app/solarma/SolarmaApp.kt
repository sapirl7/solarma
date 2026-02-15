package app.solarma

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Main application class for Solarma.
 * Uses Hilt for dependency injection.
 */
@HiltAndroidApp
class SolarmaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize any app-wide components here
    }
}
