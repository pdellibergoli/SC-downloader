package com.streamingcommunity.downloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.streamingcommunity.downloader.MainActivity
import com.streamingcommunity.downloader.SCApplication
import com.streamingcommunity.downloader.core.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "sc_download_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL_JOB = "com.streamingcommunity.downloader.ACTION_CANCEL_JOB"
        const val EXTRA_JOB_ID = "extra_job_id"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_JOB) {
            val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
            if (jobId != -1L) {
                (application as SCApplication).downloadManager.cancelJob(jobId)
            }
            return START_NOT_STICKY
        }

        val initialNotification = buildProgressNotification(
            title = "SC Downloader attivo",
            subtitle = "Preparazione coda download...",
            progress = 0,
            indeterminate = true,
            activeJobId = null
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeActiveJobs()
        return START_NOT_STICKY
    }

    private fun observeActiveJobs() {
        val app = application as SCApplication
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        serviceScope.launch {
            app.downloadManager.allJobsFlow.collectLatest { jobs ->
                val activeJobs = jobs.filter {
                    it.status in listOf(
                        DownloadStatus.QUEUED.name,
                        DownloadStatus.RESOLVING.name,
                        DownloadStatus.DOWNLOADING_VIDEO.name,
                        DownloadStatus.DOWNLOADING_AUDIO.name,
                        DownloadStatus.DOWNLOADING_SUBTITLES.name,
                        DownloadStatus.JOINING.name,
                        DownloadStatus.REMUXING.name
                    )
                }

                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }

                val current = activeJobs.firstOrNull { it.status != DownloadStatus.QUEUED.name }
                    ?: activeJobs.first()

                val statusEnum = runCatching { DownloadStatus.valueOf(current.status) }
                    .getOrDefault(DownloadStatus.DOWNLOADING_VIDEO)

                val speedMb = current.speedBytesPerSec / (1024.0 * 1024.0)
                val speedText = if (speedMb >= 0.1) "%.1f MB/s".format(speedMb) else ""
                val queueBadge = if (activeJobs.size > 1) " (+${activeJobs.size - 1} in coda)" else ""

                val subtitle = listOf(
                    statusEnum.label,
                    "${current.progressPercent}%",
                    speedText
                ).filter { it.isNotBlank() }.joinToString(" • ") + queueBadge

                val notification = buildProgressNotification(
                    title = current.displayTitle,
                    subtitle = subtitle,
                    progress = current.progressPercent,
                    indeterminate = statusEnum == DownloadStatus.RESOLVING ||
                            statusEnum == DownloadStatus.JOINING ||
                            statusEnum == DownloadStatus.REMUXING,
                    activeJobId = current.id
                )
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildProgressNotification(
        title: String,
        subtitle: String,
        progress: Int,
        indeterminate: Boolean,
        activeJobId: Long?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingOpen)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)

        if (activeJobId != null) {
            val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_JOB
                putExtra(EXTRA_JOB_ID, activeJobId)
            }
            val pendingCancel = PendingIntent.getService(
                this,
                activeJobId.toInt(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Annulla",
                pendingCancel
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Media",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Avanzamento download film, serie TV e anime"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
