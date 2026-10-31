package app.solarma

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Main application class for Solarma.
 * Uses Hilt for dependency injection.
 *
 * Provides WorkManager's configuration so @HiltWorker workers (alarm restore after
 * reboot, deadline slash, device-key claim, reminders) get their dependencies;
 * the default initializer is removed in the manifest.
 */
@HiltAndroidApp
class SolarmaApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
