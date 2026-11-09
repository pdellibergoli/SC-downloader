package com.streamingcommunity.downloader.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamingcommunity.downloader.SCApplication
import com.streamingcommunity.downloader.core.network.DomainRecoveryManager
import java.util.concurrent.TimeUnit

class WatchlistPollWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SCApplication ?: return Result.success()
        return try {
            app.watchlistManager.checkAllWatches()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class DomainCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SCApplication ?: return Result.success()
        val settings = app.settingsRepository.getSettings()
        if (!settings.domainAutoCheckEnabled) return Result.success()

        return try {
            DomainRecoveryManager.runCheck(
                currentDomain = settings.domain,
                autoApply = settings.domainAutoApply,
                force = false,
                onSaveDomain = { newDomain ->
                    app.settingsRepository.updateDomain(newDomain)
                }
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object BackgroundWorkScheduler {
    private const val WATCHLIST_WORK_NAME = "sc_watchlist_periodic_poll"
    private const val DOMAIN_CHECK_WORK_NAME = "sc_domain_periodic_check"

    fun scheduleAll(
        context: Context,
        watchIntervalMinutes: Int = 240,
        domainCheckIntervalMinutes: Int = 360
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val watchRequest = PeriodicWorkRequestBuilder<WatchlistPollWorker>(
            watchIntervalMinutes.coerceAtLeast(15).toLong(),
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        val domainRequest = PeriodicWorkRequestBuilder<DomainCheckWorker>(
            domainCheckIntervalMinutes.coerceAtLeast(30).toLong(),
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            WATCHLIST_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            watchRequest
        )
        wm.enqueueUniquePeriodicWork(
            DOMAIN_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            domainRequest
        )
    }
}
