package app.solarma.di

import android.content.Context
import androidx.room.Room
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.SolarmaDatabase
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
        .fallbackToDestructiveMigration()
        .build()
    }
    
    @Provides
    @Singleton
    fun provideAlarmDao(database: SolarmaDatabase): AlarmDao {
        return database.alarmDao()
    }
}
