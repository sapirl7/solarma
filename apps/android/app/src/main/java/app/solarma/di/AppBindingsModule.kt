package app.solarma.di

import app.solarma.wallet.AndroidNetworkChecker
import app.solarma.wallet.NetworkChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for binding interfaces to implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    @Singleton
    abstract fun bindNetworkChecker(impl: AndroidNetworkChecker): NetworkChecker
}
