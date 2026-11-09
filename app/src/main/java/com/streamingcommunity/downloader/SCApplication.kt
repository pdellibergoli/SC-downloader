package com.streamingcommunity.downloader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.streamingcommunity.downloader.core.network.DomainRecoveryManager
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import com.streamingcommunity.downloader.data.AppDatabase
import com.streamingcommunity.downloader.data.SettingsRepository
import com.streamingcommunity.downloader.service.BackgroundWorkScheduler
import com.streamingcommunity.downloader.service.DownloadManagerService
import com.streamingcommunity.downloader.service.WatchlistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SCApplication : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var downloadManager: DownloadManagerService
        private set

    lateinit var watchlistManager: WatchlistManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        downloadManager = DownloadManagerService(this, database, settingsRepository)
        watchlistManager = WatchlistManager(database, settingsRepository, downloadManager)

        appScope.launch {
            runCatching {
                downloadManager.resumePendingIfNeeded()
            }

            val settings = settingsRepository.getSettings()
            BackgroundWorkScheduler.scheduleAll(
                context = this@SCApplication,
                watchIntervalMinutes = settings.seriesWatchIntervalMinutes,
                domainCheckIntervalMinutes = settings.domainCheckIntervalMinutes
            )

            // If no domain is configured yet or auto-check is enabled, check on startup
            if (settings.domain.isBlank() || settings.domainAutoCheckEnabled) {
                DomainRecoveryManager.runCheck(
                    currentDomain = settings.domain,
                    autoApply = settings.domainAutoApply || settings.domain.isBlank(),
                    force = settings.domain.isBlank(),
                    onSaveDomain = { newDomain ->
                        settingsRepository.updateDomain(newDomain)
                    }
                )
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                HttpClientProvider.createImageClient {
                    settingsRepository.currentDomainSnapshot
                }
            }
            .crossfade(true)
            .build()
    }
}
