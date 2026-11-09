package com.streamingcommunity.downloader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "download_jobs")
data class DownloadJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val source: String, // "streamingcommunity" or "animeunity"
    val mediaType: String, // "movie", "tv", "anime"
    val externalId: String,
    val slug: String = "",
    val title: String,
    val year: String? = null,
    val poster: String? = null,
    val season: Int? = null,
    val episodeNumber: String? = null,
    val episodeId: Long? = null,
    val episodeName: String? = null,
    val animeType: String = "tv",
    val audioLanguagesCsv: String = "ita",
    val subtitleLanguagesCsv: String = "",
    val status: String = "QUEUED",
    val progressPercent: Int = 0,
    val completedSegments: Int = 0,
    val totalSegments: Int = 0,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val outputPath: String? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val displayTitle: String
        get() = buildString {
            append(title)
            if (mediaType == "tv" && season != null && !episodeNumber.isNullOrBlank()) {
                append(" - S%02dE%s".format(season, episodeNumber.padStart(2, '0')))
                if (!episodeName.isNullOrBlank()) {
                    append(" ($episodeName)")
                }
            } else if (mediaType == "anime" && !episodeNumber.isNullOrBlank() &&
                animeType.lowercase() !in listOf("movie", "film")
            ) {
                append(" - Ep. ${episodeNumber.padStart(2, '0')}")
            } else if (!year.isNullOrBlank()) {
                append(" ($year)")
            }
        }
}

@Entity(
    tableName = "watches",
    indices = [Index(value = ["source", "externalId"], unique = true)]
)
data class WatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val source: String,
    val mediaType: String, // "tv" or "anime"
    val externalId: String,
    val slug: String = "",
    val title: String,
    val year: String? = null,
    val poster: String? = null,
    val animeType: String = "tv",
    val audioLanguagesCsv: String = "ita",
    val subtitleLanguagesCsv: String = "",
    val autoDownload: Boolean = true,
    val knownEpisodesCount: Int = 0,
    val newEpisodesCount: Int = 0,
    val lastCheckedAtMs: Long? = null,
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "seen_episodes",
    primaryKeys = ["watchId", "episodeKey"],
    foreignKeys = [
        ForeignKey(
            entity = WatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["watchId"])]
)
data class SeenEpisodeEntity(
    val watchId: Long,
    val episodeKey: String, // e.g., "S01E01" or "E01"
    val episodeId: Long,
    val season: Int = 1,
    val episodeNumber: String,
    val episodeName: String? = null,
    val seenAtMs: Long = System.currentTimeMillis()
)
