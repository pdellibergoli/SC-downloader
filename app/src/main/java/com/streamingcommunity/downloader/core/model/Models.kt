package com.streamingcommunity.downloader.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaSource(val id: String, val label: String) {
    STREAMING_COMMUNITY("streamingcommunity", "StreamingCommunity"),
    ANIME_UNITY("animeunity", "AnimeUnity");

    companion object {
        fun fromId(id: String): MediaSource =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STREAMING_COMMUNITY
    }
}

@Serializable
data class MediaTitle(
    val id: String,
    val numericId: Int? = null,
    val name: String,
    val type: String, // "movie", "tv", "anime"
    val mediaType: String = "", // For AnimeUnity: "Movie", "TV", "OVA", "ONA", "Special"
    val slug: String = "",
    val score: Double? = null,
    val releaseDate: String? = null,
    val lastAirDate: String? = null,
    val age: Int? = null,
    val seasonsCount: Int = 0,
    val episodesCount: Int = 0,
    val poster: String? = null,
    val backdrop: String? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val dubbed: Boolean = false,
    val studio: String = "",
    val status: String = "",
    val season: String = "",
    val originalName: String = "",
    val source: MediaSource = MediaSource.STREAMING_COMMUNITY
) {
    val year: String?
        get() {
            val raw = releaseDate ?: if (type == "movie") lastAirDate else null
            val sub = raw?.take(4)
            return if (sub != null && sub.length == 4 && sub.all { it.isDigit() }) sub else null
        }

    val isSeries: Boolean
        get() = when {
            source == MediaSource.ANIME_UNITY -> {
                val kind = mediaType.trim().lowercase()
                kind != "movie" && kind != "film"
            }
            else -> type.equals("tv", ignoreCase = true)
        }
}

@Serializable
data class Shelf(
    val key: String,
    val title: String,
    val items: List<MediaTitle>
)

@Serializable
data class TitleMetadata(
    val source: String = "none",
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtime: Int? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val trailerUrl: String? = null,
    val tmdbId: Int? = null,
    val name: String? = null,
    val poster: String? = null,
    val type: String? = null,
    val seasonsCount: Int? = null,
    val seasonNumbers: List<Int> = emptyList(),
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val originalName: String? = null,
    val originalLanguage: String? = null,
    val status: String? = null,
    val quality: String? = null,
    val age: Int? = null,
    val year: String? = null
) {
    companion object {
        val EMPTY = TitleMetadata()
    }
}

@Serializable
data class EpisodeInfo(
    val id: Long,
    val number: String,
    val name: String? = null,
    val plot: String? = null,
    val duration: Int? = null,
    val still: String? = null,
    val season: Int = 1
)

@Serializable
data class StreamSource(
    val m3u8Url: String,
    val keyHex: String?,
    val referer: String,
    val provider: String = "vixcloud"
)

@Serializable
data class AvailableTracks(
    val audio: List<String> = emptyList(),
    val subtitles: List<String> = emptyList(),
    val defaultLang: String? = null
)

@Serializable
data class AudioTrackSource(
    val url: String,
    val language: String
)

@Serializable
data class SubtitleTrackSource(
    val uri: String,
    val language: String
)

@Serializable
data class DomainCandidate(
    val host: String,
    val version: String,
    val foundAtEpochMs: Long
)

@Serializable
data class RejectedCandidate(
    val host: String,
    val reason: String
)

@Serializable
data class DomainCheckResult(
    val current: String,
    val currentOk: Boolean = false,
    val candidate: String? = null,
    val version: String? = null,
    val applied: Boolean = false,
    val rejected: List<RejectedCandidate> = emptyList(),
    val checked: Boolean = false
)

@Serializable
data class AppSettings(
    val domain: String = "",
    val animeUnityDomain: String = "www.animeunity.co",
    val videoHost: String = "",
    val maxConcurrentDownloads: Int = 2,
    val maxSegmentWorkers: Int = 12,
    val seriesWatchIntervalMinutes: Int = 240,
    val domainAutoCheckEnabled: Boolean = true,
    val domainAutoApply: Boolean = false,
    val domainCheckIntervalMinutes: Int = 360,
    val outputContainer: String = "mp4", // "mp4" or "mkv"
    val subtitleMode: String = "embed", // "embed" or "external"
    val namingTemplates: Map<String, String> = DEFAULT_NAMING_TEMPLATES
) {
    companion object {
        val DEFAULT_NAMING_TEMPLATES = mapOf(
            "film_folder" to "{title}[ ({year})]",
            "film_file" to "{title}[ ({year})]",
            "series_folder" to "{title}[ ({year})]",
            "season_folder" to "Season {season2}",
            "episode_file" to "{title} S{season2}E{episode2}",
            "anime_folder" to "{title}[ ({year})]",
            "anime_season_folder" to "Season 01",
            "anime_episode_file" to "{title} S01E{episode2}",
            "anime_movie_file" to "{title}"
        )
    }
}

enum class DownloadStatus(val label: String) {
    QUEUED("In coda"),
    RESOLVING("Risoluzione stream"),
    DOWNLOADING_VIDEO("Download video"),
    DOWNLOADING_AUDIO("Download audio"),
    DOWNLOADING_SUBTITLES("Download sottotitoli"),
    JOINING("Unione segmenti"),
    REMUXING("Muxing tracce"),
    COMPLETED("Completato"),
    FAILED("Errore"),
    CANCELLED("Annullato")
}

class MissingAudioTrackException(
    val language: String,
    val available: List<String>
) : RuntimeException(
    "Traccia audio '$language' non disponibile (presenti: ${available.joinToString(", ").ifEmpty { "nessuna" }})"
)

class DownloadCancelledException(message: String = "Download annullato dall'utente") :
    RuntimeException(message)
