package com.streamingcommunity.downloader.service

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import com.streamingcommunity.downloader.core.hls.M3u8Downloader
import com.streamingcommunity.downloader.core.model.DownloadCancelledException
import com.streamingcommunity.downloader.core.model.DownloadStatus
import com.streamingcommunity.downloader.core.model.EpisodeInfo
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.model.MediaTitle
import com.streamingcommunity.downloader.core.naming.NamingEngine
import com.streamingcommunity.downloader.core.network.AnimeUnityClient
import com.streamingcommunity.downloader.core.network.StreamingCommunityClient
import com.streamingcommunity.downloader.core.network.VixcloudParser
import com.streamingcommunity.downloader.data.AppDatabase
import com.streamingcommunity.downloader.data.DownloadJobEntity
import com.streamingcommunity.downloader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DownloadManagerService(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val dao = database.downloadJobDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queueMutex = Mutex()

    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val runningTasks = ConcurrentHashMap<Long, Job>()
    private var dispatcherJob: Job? = null

    val allJobsFlow: Flow<List<DownloadJobEntity>> = dao.observeAllJobs()

    fun getMediaRootDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidate = File(publicDownloads, "StreamingCommunity")
        if (candidate.exists() || candidate.mkdirs()) {
            return candidate
        }
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StreamingCommunity")
        fallback.mkdirs()
        return fallback
    }

    private fun isJobActiveOrCompleted(job: DownloadJobEntity): Boolean {
        return job.status !in listOf(
            DownloadStatus.CANCELLED.name,
            DownloadStatus.FAILED.name
        )
    }

    private fun matchesEpisodeJob(
        job: DownloadJobEntity,
        season: Int,
        episode: EpisodeInfo
    ): Boolean {
        if (job.episodeId != null && episode.id > 0 && job.episodeId == episode.id) {
            return true
        }
        return job.season == season && job.episodeNumber == episode.number
    }

    suspend fun enqueueFilm(
        title: MediaTitle,
        audioLanguages: List<String> = listOf("ita"),
        subtitleLanguages: List<String> = emptyList()
    ): Long {
        val existingJobs = dao.getJobsForTitle(title.source.id, title.id)
            .filter { it.mediaType == "movie" }
        if (existingJobs.any { isJobActiveOrCompleted(it) }) {
            return -1L
        }
        existingJobs.forEach { dao.deleteById(it.id) }

        val entity = DownloadJobEntity(
            source = title.source.id,
            mediaType = "movie",
            externalId = title.id,
            slug = title.slug,
            title = title.name,
            year = title.year,
            poster = title.poster,
            audioLanguagesCsv = audioLanguages.joinToString(",").ifEmpty { "ita" },
            subtitleLanguagesCsv = subtitleLanguages.joinToString(","),
            status = DownloadStatus.QUEUED.name
        )
        val jobId = dao.insert(entity)
        triggerQueueProcessing()
        return jobId
    }

    suspend fun enqueueEpisode(
        title: MediaTitle,
        episode: EpisodeInfo,
        season: Int,
        audioLanguages: List<String> = listOf("ita"),
        subtitleLanguages: List<String> = emptyList()
    ): Long {
        val existingJobs = dao.getJobsForTitle(title.source.id, title.id)
            .filter { matchesEpisodeJob(it, season, episode) }
        if (existingJobs.any { isJobActiveOrCompleted(it) }) {
            return -1L
        }
        existingJobs.forEach { dao.deleteById(it.id) }

        val isAnime = title.source == MediaSource.ANIME_UNITY || title.type == "anime"
        val entity = DownloadJobEntity(
            source = title.source.id,
            mediaType = if (isAnime) "anime" else "tv",
            externalId = title.id,
            slug = title.slug,
            title = title.name,
            year = title.year,
            poster = title.poster,
            season = season,
            episodeNumber = episode.number,
            episodeId = episode.id,
            episodeName = episode.name,
            animeType = title.mediaType.ifEmpty { "tv" },
            audioLanguagesCsv = audioLanguages.joinToString(",").ifEmpty { "ita" },
            subtitleLanguagesCsv = subtitleLanguages.joinToString(","),
            status = DownloadStatus.QUEUED.name
        )
        val jobId = dao.insert(entity)
        triggerQueueProcessing()
        return jobId
    }

    suspend fun enqueueBatchEpisodes(
        title: MediaTitle,
        episodes: List<EpisodeInfo>,
        season: Int,
        audioLanguages: List<String> = listOf("ita"),
        subtitleLanguages: List<String> = emptyList()
    ): List<Long> {
        val existingJobs = dao.getJobsForTitle(title.source.id, title.id)
        val ids = mutableListOf<Long>()
        val isAnime = title.source == MediaSource.ANIME_UNITY || title.type == "anime"
        for (ep in episodes) {
            val matching = existingJobs.filter { matchesEpisodeJob(it, season, ep) }
            if (matching.any { isJobActiveOrCompleted(it) }) {
                continue
            }
            matching.forEach { dao.deleteById(it.id) }

            val entity = DownloadJobEntity(
                source = title.source.id,
                mediaType = if (isAnime) "anime" else "tv",
                externalId = title.id,
                slug = title.slug,
                title = title.name,
                year = title.year,
                poster = title.poster,
                season = season,
                episodeNumber = ep.number,
                episodeId = ep.id,
                episodeName = ep.name,
                animeType = title.mediaType.ifEmpty { "tv" },
                audioLanguagesCsv = audioLanguages.joinToString(",").ifEmpty { "ita" },
                subtitleLanguagesCsv = subtitleLanguages.joinToString(","),
                status = DownloadStatus.QUEUED.name
            )
            ids.add(dao.insert(entity))
        }
        if (ids.isNotEmpty()) {
            triggerQueueProcessing()
        }
        return ids
    }

    fun cancelJob(jobId: Long) {
        cancelFlags[jobId]?.set(true)
        runningTasks[jobId]?.cancel()
        scope.launch {
            dao.updateFinalState(
                id = jobId,
                status = DownloadStatus.CANCELLED.name,
                outputPath = null,
                errorMessage = "Annullato dall'utente",
                progressPercent = 0
            )
        }
    }

    suspend fun retryJob(jobId: Long) {
        val job = dao.getById(jobId) ?: return
        dao.update(
            job.copy(
                status = DownloadStatus.QUEUED.name,
                progressPercent = 0,
                completedSegments = 0,
                totalSegments = 0,
                downloadedBytes = 0L,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                errorMessage = null,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        triggerQueueProcessing()
    }

    suspend fun removeJob(jobId: Long) {
        cancelJob(jobId)
        dao.deleteById(jobId)
    }

    suspend fun clearFinished() {
        dao.clearFinishedJobs()
    }

    private fun computeDestinationPath(
        job: DownloadJobEntity,
        rootDir: String,
        settings: com.streamingcommunity.downloader.core.model.AppSettings
    ): String {
        return when (job.mediaType) {
            "movie" -> NamingEngine.filmPath(
                rootDir = rootDir,
                title = job.title,
                year = job.year,
                templates = settings.namingTemplates,
                container = settings.outputContainer
            )
            "tv" -> NamingEngine.episodePath(
                rootDir = rootDir,
                tvName = job.title,
                season = job.season ?: 1,
                episodeNumber = job.episodeNumber ?: "1",
                year = job.year,
                templates = settings.namingTemplates,
                container = settings.outputContainer
            )
            "anime" -> NamingEngine.animePath(
                rootDir = rootDir,
                animeName = job.title,
                episodeNumber = job.episodeNumber ?: "1",
                animeType = job.animeType,
                year = job.year,
                templates = settings.namingTemplates,
                container = settings.outputContainer
            )
            else -> File(rootDir, "${job.title}.${settings.outputContainer}").absolutePath
        }
    }

    suspend fun resumePendingIfNeeded() {
        val pending = dao.getActiveAndQueuedJobs()
        if (pending.isEmpty()) return

        val settings = settingsRepository.getSettings()
        val rootDir = getMediaRootDir().absolutePath
        var stillNeedsQueue = false

        for (job in pending) {
            val destPath = computeDestinationPath(job, rootDir, settings)
            val destFile = File(destPath)
            val tempDir = File(context.cacheDir, "hls_job_${job.id}")
            val combinedVideoTs = File(tempDir, "_video_combined.ts")

            // If the output file already exists on disk (and no pending _video_combined.ts is waiting),
            // mark it COMPLETED immediately (fixes jobs affected by the previous REMUXING 99% race).
            if (destFile.exists() && destFile.length() > 0L && !combinedVideoTs.exists()) {
                tempDir.deleteRecursively()
                try {
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                } catch (_: Throwable) {
                }
                dao.updateFinalState(
                    id = job.id,
                    status = DownloadStatus.COMPLETED.name,
                    outputPath = destFile.absolutePath,
                    errorMessage = null,
                    progressPercent = 100
                )
            } else {
                stillNeedsQueue = true
            }
        }

        if (stillNeedsQueue) {
            triggerQueueProcessing()
        }
    }

    fun triggerQueueProcessing() {
        startForegroundService()
        scope.launch {
            queueMutex.withLock {
                if (dispatcherJob?.isActive == true) return@withLock
                dispatcherJob = scope.launch {
                    processQueueLoop()
                }
            }
        }
    }

    private fun startForegroundService() {
        try {
            val intent = Intent(context, DownloadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun processQueueLoop() {
        val settings = settingsRepository.getSettings()
        val semaphore = Semaphore(settings.maxConcurrentDownloads.coerceIn(1, 5))

        while (true) {
            val pending = dao.getActiveAndQueuedJobs()
                .filter { it.id !in runningTasks.keys }

            if (pending.isEmpty() && runningTasks.isEmpty()) {
                break
            }

            if (pending.isEmpty()) {
                kotlinx.coroutines.delay(1000L)
                continue
            }

            for (jobEntity in pending) {
                if (jobEntity.id in runningTasks.keys) continue
                val cancelFlag = AtomicBoolean(false)
                cancelFlags[jobEntity.id] = cancelFlag

                val task = scope.launch {
                    semaphore.withPermit {
                        executeSingleJob(jobEntity.id, cancelFlag)
                    }
                }
                runningTasks[jobEntity.id] = task
                task.invokeOnCompletion {
                    runningTasks.remove(jobEntity.id)
                    cancelFlags.remove(jobEntity.id)
                }
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    private suspend fun executeSingleJob(jobId: Long, cancelFlag: AtomicBoolean) {
        val job = dao.getById(jobId) ?: return
        if (cancelFlag.get()) return

        val settings = settingsRepository.getSettings()
        val audioLanguages = job.audioLanguagesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("ita") }
        val subtitleLanguages = job.subtitleLanguagesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val rootDir = getMediaRootDir().absolutePath
        val destPath = computeDestinationPath(job, rootDir, settings)
        val tempDir = File(context.cacheDir, "hls_job_$jobId")
        val combinedVideoTs = File(tempDir, "_video_combined.ts")
        val existingAudioFiles = tempDir.listFiles()
            ?.filter { it.name.startsWith("_audio_") && it.name.endsWith(".ts") && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()
        var lastProgressUpdateJob: Job? = null

        try {
            val finalFile = if (combinedVideoTs.exists() && combinedVideoTs.length() > 0L && existingAudioFiles.isNotEmpty()) {
                // Fast-path: video and audio segments were already downloaded and concatenated.
                dao.updateProgress(
                    id = jobId,
                    status = DownloadStatus.REMUXING.name,
                    progressPercent = 99,
                    completedSegments = job.totalSegments.coerceAtLeast(1),
                    totalSegments = job.totalSegments.coerceAtLeast(1),
                    downloadedBytes = combinedVideoTs.length(),
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L
                )
                val existingSubs = tempDir.listFiles()
                    ?.filter { it.extension.equals("vtt", ignoreCase = true) && it.length() > 0L }
                    .orEmpty()
                val remuxed = com.streamingcommunity.downloader.core.hls.MpegTsRemuxer.remuxToContainer(
                    videoTsFile = combinedVideoTs,
                    externalAudioTsFiles = existingAudioFiles,
                    subtitleFiles = if (settings.subtitleMode == "embed") existingSubs else emptyList(),
                    outputFile = File(destPath)
                )
                if (settings.subtitleMode == "external") {
                    val videoDir = remuxed.parentFile ?: File(rootDir)
                    for (subFile in existingSubs) {
                        subFile.copyTo(File(videoDir, subFile.name), overwrite = true)
                    }
                }
                tempDir.deleteRecursively()
                remuxed
            } else {
                dao.updateProgress(
                    id = jobId,
                    status = DownloadStatus.RESOLVING.name,
                    progressPercent = 0,
                    completedSegments = 0,
                    totalSegments = 0,
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L
                )

                val streamSource = when (job.mediaType) {
                    "movie" -> {
                        if (settings.domain.isBlank()) {
                            throw RuntimeException("Dominio StreamingCommunity non configurato nelle Impostazioni")
                        }
                        val filmId = job.externalId.toIntOrNull()
                            ?: throw RuntimeException("ID film non valido: ${job.externalId}")
                        StreamingCommunityClient.resolveFilmStream(
                            idFilm = filmId,
                            domain = settings.domain,
                            videoHostOverride = settings.videoHost
                        )
                    }

                    "tv" -> {
                        if (settings.domain.isBlank()) {
                            throw RuntimeException("Dominio StreamingCommunity non configurato nelle Impostazioni")
                        }
                        val tvId = job.externalId.toIntOrNull()
                            ?: throw RuntimeException("ID serie non valido: ${job.externalId}")
                        val epId = job.episodeId
                            ?: throw RuntimeException("ID episodio mancante")
                        val season = job.season ?: 1
                        val epNumber = job.episodeNumber ?: "1"
                        val token = StreamingCommunityClient.getToken(tvId, settings.domain)
                        val epInfo = EpisodeInfo(
                            id = epId,
                            number = epNumber,
                            name = job.episodeName,
                            season = season
                        )
                        StreamingCommunityClient.resolveEpisodeStream(
                            tvId = tvId,
                            ep = epInfo,
                            tvName = job.title,
                            season = season,
                            domain = settings.domain,
                            token = token,
                            videoHostOverride = settings.videoHost
                        )
                    }

                    "anime" -> {
                        val epId = job.episodeId
                            ?: throw RuntimeException("ID episodio anime mancante")
                        AnimeUnityClient.resolveAnimeEpisodeStream(
                            episodeId = epId,
                            host = settings.animeUnityDomain,
                            videoHostOverride = settings.videoHost
                        )
                    }

                    else -> throw RuntimeException("Tipo media non supportato: ${job.mediaType}")
                }

                if (cancelFlag.get()) throw DownloadCancelledException()

                val audioTrackSources = VixcloudParser.collectAudioTracks(
                    m3u8Url = streamSource.m3u8Url,
                    referer = streamSource.referer,
                    audioLanguages = audioLanguages,
                    strict = false
                )

                val startTimeMs = System.currentTimeMillis()
                var lastDbUpdateMs = 0L

                M3u8Downloader.downloadM3u8(
                    m3u8IndexUrl = streamSource.m3u8Url,
                    keyHex = streamSource.keyHex,
                    outputFile = File(destPath),
                    tempDir = tempDir,
                    referer = streamSource.referer,
                    maxSegmentWorkers = settings.maxSegmentWorkers,
                    audioLanguages = audioLanguages,
                    subtitleLanguages = subtitleLanguages,
                    audioTrackUrls = audioTrackSources,
                    subtitleMode = settings.subtitleMode,
                    cancelFlag = cancelFlag
                ) { update ->
                    val now = System.currentTimeMillis()
                    if (now - lastDbUpdateMs >= 400L ||
                        update.completedSegments == update.totalSegments ||
                        update.status == DownloadStatus.JOINING ||
                        update.status == DownloadStatus.REMUXING
                    ) {
                        lastDbUpdateMs = now
                        val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(0.5)
                        val speed = if (update.status == DownloadStatus.REMUXING || update.status == DownloadStatus.JOINING) {
                            0L
                        } else {
                            (update.downloadedBytes / elapsedSec).toLong()
                        }
                        val percent = if (update.status == DownloadStatus.REMUXING || update.status == DownloadStatus.JOINING) {
                            99
                        } else if (update.totalSegments > 0) {
                            ((update.completedSegments * 100) / update.totalSegments).coerceIn(0, 99)
                        } else 0
                        val remainingSegs = (update.totalSegments - update.completedSegments).coerceAtLeast(0)
                        val segRate = update.completedSegments / elapsedSec
                        val eta = if (remainingSegs == 0) 0L else if (segRate > 0.1) (remainingSegs / segRate).toLong() else -1L

                        lastProgressUpdateJob = scope.launch {
                            dao.updateProgress(
                                id = jobId,
                                status = update.status.name,
                                progressPercent = percent,
                                completedSegments = update.completedSegments,
                                totalSegments = update.totalSegments,
                                downloadedBytes = update.downloadedBytes,
                                speedBytesPerSec = speed,
                                etaSeconds = eta
                            )
                        }
                    }
                }
            }

            // Ensure any in-flight progress update finishes before writing COMPLETED state
            try {
                lastProgressUpdateJob?.join()
            } catch (_: Throwable) {
            }

            // Scan into Android MediaStore so video apps see it immediately
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalFile.absolutePath),
                    null,
                    null
                )
            } catch (_: Throwable) {
            }

            dao.updateFinalState(
                id = jobId,
                status = DownloadStatus.COMPLETED.name,
                outputPath = finalFile.absolutePath,
                errorMessage = null,
                progressPercent = 100
            )
        } catch (e: DownloadCancelledException) {
            try {
                lastProgressUpdateJob?.cancel()
            } catch (_: Throwable) {
            }
            dao.updateFinalState(
                id = jobId,
                status = DownloadStatus.CANCELLED.name,
                outputPath = null,
                errorMessage = e.message ?: "Download annullato",
                progressPercent = 0
            )
        } catch (t: Throwable) {
            try {
                lastProgressUpdateJob?.cancel()
            } catch (_: Throwable) {
            }
            val status = if (cancelFlag.get()) DownloadStatus.CANCELLED else DownloadStatus.FAILED
            dao.updateFinalState(
                id = jobId,
                status = status.name,
                outputPath = null,
                errorMessage = t.message ?: "Errore sconosciuto durante il download",
                progressPercent = 0
            )
        }
    }
}
