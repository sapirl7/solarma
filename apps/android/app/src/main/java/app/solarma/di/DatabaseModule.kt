package app.solarma.di

import android.content.Context
import androidx.room.Room
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.SolarmaDatabase
import app.solarma.data.local.StatsDao
import app.solarma.wallet.PendingTransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for database dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): SolarmaDatabase {
        return Room.databaseBuilder(
            context,
            SolarmaDatabase::class.java,
            "solarma_database"
        )
        .addMigrations(
            SolarmaDatabase.MIGRATION_3_4,
            SolarmaDatabase.MIGRATION_4_5,
            SolarmaDatabase.MIGRATION_5_6
        )
        .build()
    }
    
    @Provides
    @Singleton
    fun provideAlarmDao(database: SolarmaDatabase): AlarmDao {
        return database.alarmDao()
    }
    
    @Provides
    @Singleton
    fun providePendingTransactionDao(database: SolarmaDatabase): PendingTransactionDao {
        return database.pendingTransactionDao()
    }
    
    @Provides
    @Singleton
    fun provideStatsDao(database: SolarmaDatabase): StatsDao {
        return database.statsDao()
    }
}
