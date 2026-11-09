package com.streamingcommunity.downloader.core.hls

import com.streamingcommunity.downloader.core.model.AudioTrackSource
import com.streamingcommunity.downloader.core.model.DownloadCancelledException
import com.streamingcommunity.downloader.core.model.DownloadStatus
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class DownloadProgressUpdate(
    val completedSegments: Int,
    val totalSegments: Int,
    val downloadedBytes: Long,
    val status: DownloadStatus
)

object M3u8Downloader {

    private val RETRIABLE_SEGMENT_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)
    private const val MAX_SEGMENT_BACKOFF_SEC = 8.0
    private const val FAILURE_ABORT_STREAK = 200
    private const val MAX_RETRY_AFTER_SEC = 30.0

    private val budgetLock = Any()
    private var sharedBudget: AdaptiveLimiter? = null

    private val destinationLocks = ConcurrentHashMap<String, Mutex>()

    fun segmentBudget(size: Int): AdaptiveLimiter {
        synchronized(budgetLock) {
            val current = sharedBudget
            return if (current == null) {
                AdaptiveLimiter(size).also { sharedBudget = it }
            } else {
                current.resize(size)
                current
            }
        }
    }

    private fun destinationMutex(path: String): Mutex {
        val normalized = File(path).absolutePath.lowercase()
        return destinationLocks.getOrPut(normalized) { Mutex() }
    }

    private fun segmentBackoffMs(attempt: Int): Long {
        val base = min(2.0.pow(attempt.toDouble()), MAX_SEGMENT_BACKOFF_SEC)
        val jitter = 0.5 + Random.nextDouble()
        return (base * jitter * 1000.0).toLong()
    }

    private fun parseRetryAfterMs(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        val secs = headerValue.trim().toDoubleOrNull() ?: return null
        val clamped = secs.coerceIn(0.0, MAX_RETRY_AFTER_SEC)
        return (clamped * 1000.0).toLong()
    }

    fun fetchTextWithB1Fallback(
        url: String,
        referer: String? = null,
        userAgent: String = HttpClientProvider.randomUserAgent(),
        client: OkHttpClient = HttpClientProvider.sharedClient
    ): Pair<String, String> {
        val headers = mutableMapOf("User-Agent" to userAgent)
        if (!referer.isNullOrBlank()) {
            headers["Referer"] = referer
        }

        HttpClientProvider.executeGet(url, headers, client).use { resp ->
            if (resp.isSuccessful) {
                return Pair(resp.body?.string().orEmpty(), url)
            }
            if (resp.code == 403 && !url.contains("b=1")) {
                val b1Url = if ("?" in url) "$url&b=1" else "$url?b=1"
                HttpClientProvider.executeGet(b1Url, headers, client).use { b1Resp ->
                    if (b1Resp.isSuccessful) {
                        return Pair(b1Resp.body?.string().orEmpty(), b1Url)
                    }
                    throw RuntimeException("Failed to fetch M3U8 ($b1Url): HTTP ${b1Resp.code}")
                }
            }
            throw RuntimeException("Failed to fetch M3U8 ($url): HTTP ${resp.code}")
        }
    }

    suspend fun downloadM3u8(
        m3u8IndexUrl: String,
        keyHex: String?,
        outputFile: File,
        tempDir: File,
        referer: String? = null,
        maxSegmentWorkers: Int = 12,
        audioLanguages: List<String> = listOf("ita"),
        subtitleLanguages: List<String> = emptyList(),
        audioTrackUrls: List<AudioTrackSource> = emptyList(),
        subtitleMode: String = "embed",
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onProgress: (DownloadProgressUpdate) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val mutex = destinationMutex(outputFile.absolutePath)
        mutex.withLock {
            executeDownloadM3u8(
                m3u8IndexUrl = m3u8IndexUrl,
                keyHex = keyHex,
                outputFile = outputFile,
                tempDir = tempDir,
                referer = referer,
                maxSegmentWorkers = maxSegmentWorkers,
                audioLanguages = audioLanguages,
                subtitleLanguages = subtitleLanguages,
                audioTrackUrls = audioTrackUrls,
                subtitleMode = subtitleMode,
                cancelFlag = cancelFlag,
                onProgress = onProgress
            )
        }
    }

    private suspend fun executeDownloadM3u8(
        m3u8IndexUrl: String,
        keyHex: String?,
        outputFile: File,
        tempDir: File,
        referer: String?,
        maxSegmentWorkers: Int,
        audioLanguages: List<String>,
        subtitleLanguages: List<String>,
        audioTrackUrls: List<AudioTrackSource>,
        subtitleMode: String,
        cancelFlag: AtomicBoolean,
        onProgress: (DownloadProgressUpdate) -> Unit
    ): File {
        val keyBytes = keyHex?.takeIf { it.isNotBlank() }?.let { AesSegmentDecryptor.hexToBytes(it) }
        val videoDir = outputFile.parentFile ?: File(".")
        val videoStem = outputFile.nameWithoutExtension
        tempDir.mkdirs()
        videoDir.mkdirs()

        val createdSubtitleFiles = mutableListOf<File>()

        try {
            // 1. Download subtitles if requested
            if (subtitleLanguages.isNotEmpty()) {
                onProgress(DownloadProgressUpdate(0, 1, 0L, DownloadStatus.DOWNLOADING_SUBTITLES))
                try {
                    val (masterContent, effectiveUrl) = fetchTextWithB1Fallback(m3u8IndexUrl, referer)
                    val masterParser = M3u8Parser().apply { parseData(masterContent, effectiveUrl) }
                    val subs = downloadSubtitleTracks(
                        parser = masterParser,
                        allowedLanguages = subtitleLanguages,
                        tempDir = tempDir,
                        videoStem = videoStem,
                        referer = referer
                    )
                    createdSubtitleFiles.addAll(subs)
                } catch (_: Exception) {
                    // Subtitles are optional; do not fail main video download if unavailable
                }
            }

            // 2. Download video segments (or reuse already joined _video_combined.ts on resume)
            val combinedVideoTs = File(tempDir, "_video_combined.ts")
            var totalVideoSegments = 1
            var totalVideoBytes = combinedVideoTs.length()
            if (!combinedVideoTs.exists() || combinedVideoTs.length() == 0L) {
                val videoTempDir = File(tempDir, "video")
                val videoSegmentsDownloader = SegmentStreamDownloader(
                    url = m3u8IndexUrl,
                    keyBytes = keyBytes,
                    tempFolder = videoTempDir,
                    referer = referer,
                    workers = maxSegmentWorkers,
                    cancelFlag = cancelFlag,
                    status = DownloadStatus.DOWNLOADING_VIDEO,
                    onProgress = onProgress
                )
                videoSegmentsDownloader.fetchPlaylistInfo()
                videoSegmentsDownloader.downloadAllSegments()

                totalVideoSegments = videoSegmentsDownloader.segments.size
                totalVideoBytes = videoSegmentsDownloader.totalBytesDownloaded.get()

                onProgress(
                    DownloadProgressUpdate(
                        completedSegments = totalVideoSegments,
                        totalSegments = totalVideoSegments,
                        downloadedBytes = totalVideoBytes,
                        status = DownloadStatus.JOINING
                    )
                )
                videoSegmentsDownloader.concatSegmentsToFile(combinedVideoTs)
            }

            // 3. Download separate audio tracks if present
            val combinedAudioTsFiles = mutableListOf<File>()
            for ((index, track) in audioTrackUrls.withIndex()) {
                if (cancelFlag.get()) throw DownloadCancelledException()
                val combinedAudioTs = File(tempDir, "_audio_$index.ts")
                if (!combinedAudioTs.exists() || combinedAudioTs.length() == 0L) {
                    val audioTempDir = File(tempDir, "audio_$index")
                    val audioDownloader = SegmentStreamDownloader(
                        url = track.url,
                        keyBytes = keyBytes,
                        tempFolder = audioTempDir,
                        referer = referer,
                        workers = maxSegmentWorkers,
                        cancelFlag = cancelFlag,
                        status = DownloadStatus.DOWNLOADING_AUDIO,
                        onProgress = onProgress
                    )
                    audioDownloader.fetchPlaylistInfo()
                    audioDownloader.downloadAllSegments()
                    audioDownloader.concatSegmentsToFile(combinedAudioTs)
                }
                combinedAudioTsFiles.add(combinedAudioTs)
            }

            if (cancelFlag.get()) throw DownloadCancelledException()

            // 4. Remux into destination container (.mp4 or .mkv)
            onProgress(
                DownloadProgressUpdate(
                    completedSegments = totalVideoSegments,
                    totalSegments = totalVideoSegments,
                    downloadedBytes = totalVideoBytes,
                    status = DownloadStatus.REMUXING
                )
            )

            val embedSubs = if (subtitleMode == "embed") createdSubtitleFiles else emptyList()
            val finalFile = MpegTsRemuxer.remuxToContainer(
                videoTsFile = combinedVideoTs,
                externalAudioTsFiles = combinedAudioTsFiles,
                subtitleFiles = embedSubs,
                outputFile = outputFile
            )

            // 5. Place external subtitles beside the video if configured
            if (subtitleMode == "external") {
                for (subFile in createdSubtitleFiles) {
                    if (subFile.exists()) {
                        val targetSub = File(videoDir, subFile.name)
                        subFile.copyTo(targetSub, overwrite = true)
                    }
                }
            }

            // Remove superseded sibling (.mp4 vs .mkv)
            val otherExt = if (finalFile.extension.equals("mkv", ignoreCase = true)) "mp4" else "mkv"
            val superseded = File(videoDir, "$videoStem.$otherExt")
            if (superseded.exists() && superseded != finalFile) {
                superseded.delete()
            }

            tempDir.deleteRecursively()
            return finalFile
        } catch (t: Throwable) {
            for (sub in createdSubtitleFiles) {
                sub.delete()
                File(videoDir, sub.name).delete()
            }
            outputFile.delete()
            if (t is CancellationException || t is DownloadCancelledException) {
                tempDir.deleteRecursively()
                if (videoDir.exists() && videoDir.list()?.isEmpty() == true) {
                    videoDir.delete()
                }
                throw DownloadCancelledException()
            }
            throw RuntimeException(t.message ?: "Errore durante il download/muxing", t)
        }
    }

    private fun downloadSubtitleTracks(
        parser: M3u8Parser,
        allowedLanguages: List<String>,
        tempDir: File,
        videoStem: String,
        referer: String?
    ): List<File> {
        val downloaded = mutableListOf<File>()
        if (parser.subtitlePlaylists.isEmpty()) return downloaded
        tempDir.mkdirs()

        for (subInfo in parser.subtitlePlaylists) {
            val langCode = subInfo.language.orEmpty()
            if (langCode !in allowedLanguages) continue
            val subUri = subInfo.uri ?: continue

            val langShort = M3u8Parser.LANG_SHORT_MAP[langCode] ?: langCode
            val outFile = File(tempDir, "$videoStem.$langShort.vtt")

            try {
                val (subPlaylistText, effectiveSubUrl) = fetchTextWithB1Fallback(subUri, referer)
                val subParser = M3u8Parser().apply { parseData(subPlaylistText, effectiveSubUrl) }
                val vttUrl = subParser.subtitleSegments.firstOrNull() ?: continue

                HttpClientProvider.executeGet(vttUrl).use { vttResp ->
                    if (vttResp.isSuccessful) {
                        val bytes = vttResp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            outFile.writeBytes(bytes)
                            downloaded.add(outFile)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore individual subtitle error
            }
        }
        return downloaded
    }

    private class SegmentStreamDownloader(
        private val url: String,
        private val keyBytes: ByteArray?,
        private val tempFolder: File,
        private val referer: String?,
        private val workers: Int,
        private val cancelFlag: AtomicBoolean,
        private val status: DownloadStatus,
        private val onProgress: (DownloadProgressUpdate) -> Unit
    ) {
        private val decryptor = keyBytes?.let { AesSegmentDecryptor(it) }
        private val budget = segmentBudget(workers)
        private val sessionClient = HttpClientProvider.newSegmentClient(workers)
        private val userAgent = HttpClientProvider.randomUserAgent()
        private val maxRetry = 3

        val segments = mutableListOf<String>()
        private val failedSegments = Collections.synchronizedSet(mutableSetOf<Int>())
        private val savedCount = AtomicInteger(0)
        private val failureStreak = AtomicInteger(0)
        private val abortFlag = AtomicBoolean(false)
        val totalBytesDownloaded = AtomicLong(0L)

        init {
            tempFolder.mkdirs()
        }

        private fun headers(): Map<String, String> {
            val map = mutableMapOf("User-Agent" to userAgent)
            if (!referer.isNullOrBlank()) {
                map["Referer"] = referer
            }
            return map
        }

        private suspend fun fetchM3u8WithRetries(targetUrl: String, what: String): Pair<String, String> {
            var currentUrl = targetUrl
            var triedWithB1 = "b=1" in targetUrl
            var lastStatus: String = "nessuna risposta"

            for (attempt in 0 until 3) {
                val isLast = attempt == 2
                try {
                    HttpClientProvider.executeGet(currentUrl, headers(), sessionClient).use { resp ->
                        if (resp.isSuccessful) {
                            return Pair(resp.body?.string().orEmpty(), currentUrl)
                        }
                        lastStatus = resp.code.toString()
                        if (resp.code == 403 && !triedWithB1) {
                            currentUrl = if ("?" in currentUrl) "$currentUrl&b=1" else "$currentUrl?b=1"
                            triedWithB1 = true
                            return@use
                        }
                    }
                } catch (e: Exception) {
                    lastStatus = e.message ?: "errore di rete"
                }
                if (!isLast) {
                    delay(2000L)
                }
            }
            throw RuntimeException("Impossibile scaricare playlist $what M3U8: HTTP $lastStatus")
        }

        suspend fun fetchPlaylistInfo() {
            val (playlistText, effectiveUrl) = fetchM3u8WithRetries(url, "playlist")
            val parser = M3u8Parser().apply { parseData(playlistText, effectiveUrl) }

            if (decryptor != null && parser.keyInfo != null) {
                decryptor.parseIv(parser.keyInfo?.iv)
            }

            when {
                parser.segments.isNotEmpty() -> {
                    segments.clear()
                    segments.addAll(parser.segments)
                }
                parser.videoPlaylists.isNotEmpty() -> {
                    val bestUrl = parser.getBestQuality()
                        ?: throw RuntimeException("Nessuna variante video valida trovata nella master playlist")
                    val (renditionText, effectiveRenditionUrl) = fetchM3u8WithRetries(bestUrl, "rendition")
                    val rp = M3u8Parser().apply { parseData(renditionText, effectiveRenditionUrl) }
                    if (decryptor != null && rp.keyInfo != null) {
                        decryptor.parseIv(rp.keyInfo?.iv)
                    }
                    segments.clear()
                    segments.addAll(rp.segments)
                }
                else -> {
                    throw RuntimeException("La playlist M3U8 non contiene segmenti né varianti video")
                }
            }
        }

        private suspend fun fetchSegmentBytes(tsUrl: String): ByteArray? {
            for (attempt in 0 until maxRetry) {
                if (cancelFlag.get()) return null
                var askedForDelayMs: Long? = null

                try {
                    val reqBuilder = Request.Builder().url(tsUrl)
                    for ((k, v) in headers()) {
                        reqBuilder.header(k, v)
                    }
                    val resp = budget.withSlot {
                        sessionClient.newCall(reqBuilder.build()).execute()
                    }
                    resp.use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                budget.reward()
                                return bytes
                            }
                        }
                        if (response.code !in RETRIABLE_SEGMENT_STATUS) {
                            return null
                        }
                        budget.penalise()
                        askedForDelayMs = parseRetryAfterMs(response.header("Retry-After"))
                    }
                } catch (e: Exception) {
                    budget.penalise()
                }

                if (attempt < maxRetry - 1) {
                    if (abortFlag.get() || cancelFlag.get()) return null
                    val waitMs = askedForDelayMs ?: segmentBackoffMs(attempt)
                    delay(waitMs)
                }
            }
            return null
        }

        private fun writeDecryptedSegment(index: Int, encryptedContent: ByteArray) {
            val tsFile = File(tempFolder, "$index.ts")
            val finalBytes = if (decryptor != null) {
                decryptor.decryptTs(encryptedContent, index)
            } else {
                encryptedContent
            }
            tsFile.writeBytes(finalBytes)
        }

        private fun recordSuccess(bytesCount: Int) {
            val completed = savedCount.incrementAndGet()
            failureStreak.set(0)
            val totalBytes = totalBytesDownloaded.addAndGet(bytesCount.toLong())
            onProgress(
                DownloadProgressUpdate(
                    completedSegments = completed,
                    totalSegments = segments.size,
                    downloadedBytes = totalBytes,
                    status = status
                )
            )
        }

        private fun recordFailure(index: Int) {
            failedSegments.add(index)
            val streak = failureStreak.incrementAndGet()
            if (streak >= FAILURE_ABORT_STREAK) {
                abortFlag.set(true)
            }
        }

        private suspend fun downloadSingleSegment(index: Int) {
            if (cancelFlag.get() || abortFlag.get()) return
            val tsFile = File(tempFolder, "$index.ts")
            if (tsFile.exists() && tsFile.length() > 0) {
                recordSuccess(tsFile.length().toInt())
                return
            }

            val bytes = fetchSegmentBytes(segments[index])
            if (bytes == null || bytes.isEmpty()) {
                recordFailure(index)
                return
            }

            writeDecryptedSegment(index, bytes)
            recordSuccess(bytes.size)
        }

        suspend fun downloadAllSegments() {
            failedSegments.clear()
            savedCount.set(0)
            failureStreak.set(0)
            abortFlag.set(false)

            val dispatcher = Dispatchers.IO.limitedParallelism(workers.coerceIn(1, 32))
            val scope = CoroutineScope(dispatcher + SupervisorJob())

            val jobs = segments.indices.map { index ->
                scope.async {
                    downloadSingleSegment(index)
                }
            }
            jobs.awaitAll()

            if (cancelFlag.get()) {
                throw DownloadCancelledException()
            }
            if (abortFlag.get()) {
                throw RuntimeException(
                    "Download interrotto: la fonte ha rifiutato ${failedSegments.size} segmenti su ${segments.size}. Riprova più tardi."
                )
            }

            retryFailedSequentially()
            requireEverySegment()
        }

        private suspend fun retryFailedSequentially() {
            if (failedSegments.isEmpty()) return
            val toRetry = failedSegments.sorted()
            val stillFailed = mutableSetOf<Int>()

            for (index in toRetry) {
                if (cancelFlag.get()) throw DownloadCancelledException()
                delay(500L)
                val bytes = fetchSegmentBytes(segments[index])
                if (bytes != null && bytes.isNotEmpty()) {
                    writeDecryptedSegment(index, bytes)
                    recordSuccess(bytes.size)
                } else {
                    stillFailed.add(index)
                }
            }
            failedSegments.clear()
            failedSegments.addAll(stillFailed)
        }

        private fun missingSegments(): List<Int> {
            val present = tempFolder.listFiles()
                ?.mapNotNull { file ->
                    if (file.extension == "ts" && file.length() > 0) {
                        file.nameWithoutExtension.toIntOrNull()
                    } else null
                }
                ?.toSet()
                ?: emptySet()

            return segments.indices.filter { it !in present }
        }

        private fun requireEverySegment() {
            val missing = missingSegments()
            if (missing.isEmpty()) return
            throw RuntimeException(
                "Download incompleto: ${missing.size} segmenti su ${segments.size} non scaricati. " +
                        "Il file non è stato creato per non salvarlo corrotto."
            )
        }

        fun concatSegmentsToFile(combinedTsFile: File) {
            if (cancelFlag.get()) throw DownloadCancelledException()
            requireEverySegment()

            combinedTsFile.parentFile?.mkdirs()
            val stagingFile = File(combinedTsFile.parentFile, "${combinedTsFile.name}.part")
            val copyBuf = ByteArray(64 * 1024)
            BufferedOutputStream(FileOutputStream(stagingFile), 256 * 1024).use { out ->
                for (index in segments.indices) {
                    val segFile = File(tempFolder, "$index.ts")
                    segFile.inputStream().use { input ->
                        while (true) {
                            val n = input.read(copyBuf)
                            if (n <= 0) break
                            out.write(copyBuf, 0, n)
                        }
                    }
                }
            }
            if (combinedTsFile.exists()) combinedTsFile.delete()
            if (!stagingFile.renameTo(combinedTsFile)) {
                stagingFile.copyTo(combinedTsFile, overwrite = true)
                stagingFile.delete()
            }
            tempFolder.deleteRecursively()
        }
    }
}
