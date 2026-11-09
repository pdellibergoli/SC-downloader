package com.streamingcommunity.downloader.service

import com.streamingcommunity.downloader.core.model.EpisodeInfo
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.model.MediaTitle
import com.streamingcommunity.downloader.core.network.AnimeUnityClient
import com.streamingcommunity.downloader.core.network.StreamingCommunityClient
import com.streamingcommunity.downloader.data.AppDatabase
import com.streamingcommunity.downloader.data.SeenEpisodeEntity
import com.streamingcommunity.downloader.data.SettingsRepository
import com.streamingcommunity.downloader.data.WatchEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class WatchlistManager(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val downloadManager: DownloadManagerService
) {
    private val watchDao = database.watchDao()

    val allWatchesFlow: Flow<List<WatchEntity>> = watchDao.observeAllWatches()

    fun observeWatch(source: String, externalId: String): Flow<WatchEntity?> =
        watchDao.observeWatch(source, externalId)

    private fun episodeKey(mediaType: String, season: Int?, episodeNumber: String): String {
        return if (mediaType == "tv") {
            "S%02dE%s".format(season ?: 1, episodeNumber)
        } else {
            "E$episodeNumber"
        }
    }

    private suspend fun enumerateCurrentEpisodes(watch: WatchEntity): List<Pair<String, EpisodeInfo>> {
        val settings = settingsRepository.getSettings()
        return if (watch.mediaType == "anime" || watch.source == MediaSource.ANIME_UNITY.id) {
            val eps = AnimeUnityClient.getEpisodes(watch.externalId, settings.animeUnityDomain)
            eps.map { ep -> Pair(episodeKey("anime", 1, ep.number), ep) }
        } else {
            val domain = settings.domain
            if (domain.isBlank()) {
                throw RuntimeException("Dominio StreamingCommunity non configurato")
            }
            val tvId = watch.externalId.toIntOrNull()
                ?: throw RuntimeException("ID serie non valido: ${watch.externalId}")
            val version = StreamingCommunityClient.getDomainVersion(domain).orEmpty()
            val token = StreamingCommunityClient.getToken(tvId, domain)
            val props = StreamingCommunityClient.getTitleProps(tvId, watch.slug, version, domain)
            val seasons = StreamingCommunityClient.extractSeasonNumbers(props)

            val found = mutableListOf<Pair<String, EpisodeInfo>>()
            for (season in seasons) {
                val episodes = StreamingCommunityClient.getSeasonEpisodes(
                    tvId = tvId,
                    tvSlug = watch.slug,
                    domain = domain,
                    version = version,
                    token = token,
                    seasonNumber = season
                )
                for (ep in episodes) {
                    found.add(Pair(episodeKey("tv", season, ep.number), ep))
                }
            }
            found
        }
    }

    suspend fun followTitle(
        title: MediaTitle,
        audioLanguages: List<String> = listOf("ita"),
        subtitleLanguages: List<String> = emptyList(),
        autoDownload: Boolean = true
    ): WatchEntity = withContext(Dispatchers.IO) {
        val existing = watchDao.getWatch(title.source.id, title.id)
        if (existing != null) return@withContext existing

        val entity = WatchEntity(
            source = title.source.id,
            mediaType = if (title.source == MediaSource.ANIME_UNITY) "anime" else "tv",
            externalId = title.id,
            slug = title.slug,
            title = title.name,
            year = title.year,
            poster = title.poster,
            animeType = title.mediaType.ifEmpty { "tv" },
            audioLanguagesCsv = audioLanguages.joinToString(",").ifEmpty { "ita" },
            subtitleLanguagesCsv = subtitleLanguages.joinToString(","),
            autoDownload = autoDownload,
            createdAtMs = System.currentTimeMillis()
        )
        val id = watchDao.insertWatch(entity)
        val saved = entity.copy(id = id)

        // Seed existing episodes as already seen so only future episodes trigger auto-download
        try {
            val current = enumerateCurrentEpisodes(saved)
            val seenEntities = current.map { (key, ep) ->
                SeenEpisodeEntity(
                    watchId = id,
                    episodeKey = key,
                    episodeId = ep.id,
                    season = ep.season,
                    episodeNumber = ep.number,
                    episodeName = ep.name
                )
            }
            watchDao.insertSeenEpisodes(seenEntities)
            val updated = saved.copy(
                knownEpisodesCount = current.size,
                lastCheckedAtMs = System.currentTimeMillis(),
                lastError = null
            )
            watchDao.updateWatch(updated)
            updated
        } catch (e: Exception) {
            val updated = saved.copy(
                lastCheckedAtMs = System.currentTimeMillis(),
                lastError = e.message
            )
            watchDao.updateWatch(updated)
            updated
        }
    }

    suspend fun unfollow(watchId: Long) = withContext(Dispatchers.IO) {
        watchDao.deleteWatchById(watchId)
    }

    suspend fun toggleAutoDownload(watch: WatchEntity, enabled: Boolean) = withContext(Dispatchers.IO) {
        watchDao.updateWatch(watch.copy(autoDownload = enabled))
    }

    suspend fun checkSingleWatch(watch: WatchEntity): Int = withContext(Dispatchers.IO) {
        try {
            val current = enumerateCurrentEpisodes(watch)
            val seenKeys = watchDao.getSeenEpisodeKeys(watch.id).toSet()
            val unseen = current.filter { it.first !in seenKeys }

            if (unseen.isNotEmpty()) {
                val newSeenEntities = unseen.map { (key, ep) ->
                    SeenEpisodeEntity(
                        watchId = watch.id,
                        episodeKey = key,
                        episodeId = ep.id,
                        season = ep.season,
                        episodeNumber = ep.number,
                        episodeName = ep.name
                    )
                }
                watchDao.insertSeenEpisodes(newSeenEntities)

                if (watch.autoDownload) {
                    val mediaTitle = MediaTitle(
                        id = watch.externalId,
                        name = watch.title,
                        type = watch.mediaType,
                        mediaType = watch.animeType,
                        slug = watch.slug,
                        releaseDate = watch.year,
                        poster = watch.poster,
                        source = MediaSource.fromId(watch.source)
                    )
                    val audioLangs = watch.audioLanguagesCsv.split(",").filter { it.isNotBlank() }
                        .ifEmpty { listOf("ita") }
                    val subLangs = watch.subtitleLanguagesCsv.split(",").filter { it.isNotBlank() }

                    for ((_, ep) in unseen) {
                        downloadManager.enqueueEpisode(
                            title = mediaTitle,
                            episode = ep,
                            season = ep.season,
                            audioLanguages = audioLangs,
                            subtitleLanguages = subLangs
                        )
                    }
                }
            }

            watchDao.updateWatch(
                watch.copy(
                    knownEpisodesCount = current.size,
                    newEpisodesCount = if (watch.autoDownload) 0 else (watch.newEpisodesCount + unseen.size),
                    lastCheckedAtMs = System.currentTimeMillis(),
                    lastError = null
                )
            )
            unseen.size
        } catch (e: Exception) {
            watchDao.updateWatch(
                watch.copy(
                    lastCheckedAtMs = System.currentTimeMillis(),
                    lastError = e.message ?: "Errore controllo episodi"
                )
            )
            0
        }
    }

    suspend fun checkAllWatches(): Int = withContext(Dispatchers.IO) {
        val watches = watchDao.getAllWatches()
        var totalNew = 0
        for (watch in watches) {
            totalNew += checkSingleWatch(watch)
        }
        totalNew
    }
}
