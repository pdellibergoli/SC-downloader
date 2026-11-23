package com.streamingcommunity.downloader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamingcommunity.downloader.core.model.DownloadStatus
import com.streamingcommunity.downloader.core.model.EpisodeInfo
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import com.streamingcommunity.downloader.data.DownloadJobEntity
import com.streamingcommunity.downloader.data.PlaybackProgressEntity
import com.streamingcommunity.downloader.ui.TitleDetailUiState
import com.streamingcommunity.downloader.ui.buildStreamingMediaKey
import com.streamingcommunity.downloader.ui.theme.AccentCyan
import com.streamingcommunity.downloader.ui.theme.AccentEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleDetailSheet(
    state: TitleDetailUiState,
    domain: String,
    isFollowed: Boolean,
    titleJobs: List<DownloadJobEntity>,
    playbackProgressMap: Map<String, PlaybackProgressEntity> = emptyMap(),
    onDismiss: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleAudioLang: (String) -> Unit,
    onToggleSubtitleLang: (String) -> Unit,
    onStreamFilm: () -> Unit,
    onDownloadFilm: () -> Unit,
    onStreamEpisode: (EpisodeInfo) -> Unit,
    onDownloadEpisode: (EpisodeInfo) -> Unit,
    onDownloadWholeSeason: () -> Unit,
    onFollowSeries: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val backdropUrl = HttpClientProvider.resolveImageUrl(
        state.metadata.backdrop ?: state.title.backdrop ?: state.title.poster,
        domain
    )

    val activeOrCompletedJobs = titleJobs.filter {
        it.status !in listOf(DownloadStatus.CANCELLED.name, DownloadStatus.FAILED.name)
    }

    fun findJobForEpisode(ep: EpisodeInfo): DownloadJobEntity? {
        return activeOrCompletedJobs.firstOrNull { job ->
            (job.episodeId != null && ep.id > 0 && job.episodeId == ep.id) ||
                (job.season == state.selectedSeason && job.episodeNumber == ep.number)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Hero Banner
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    if (backdropUrl != null) {
                        AsyncImage(
                            model = backdropUrl,
                            contentDescription = state.title.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.35f),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Indietro",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.White)
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = state.title.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        val metaLine = listOfNotNull(
                            state.metadata.year ?: state.title.year,
                            state.metadata.rating?.let { "★ $it" },
                            state.metadata.runtime?.let { "${it} min" },
                            state.metadata.quality
                        ).joinToString(" • ")

                        if (metaLine.isNotEmpty()) {
                            Text(
                                text = metaLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentCyan
                            )
                        }
                    }
                }
            }

            // Genres + Synopsis
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val genres = state.metadata.genres.ifEmpty { state.title.genres }
                    if (genres.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            genres.forEach { genre ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = genre,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    val plot = state.metadata.plot ?: state.title.plot
                    if (!plot.isNullOrBlank()) {
                        Text(
                            text = plot,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (state.metadata.cast.isNotEmpty()) {
                        Text(
                            text = "Cast: ${state.metadata.cast.take(6).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Audio & Subtitle Track Selection
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    val audioList = state.availableTracks.audio.ifEmpty { listOf("ita") }
                    Text(
                        text = "Tracce Audio",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(audioList) { lang ->
                            FilterChip(
                                selected = lang in state.selectedAudioLanguages,
                                onClick = { onToggleAudioLang(lang) },
                                label = { Text(lang.uppercase()) }
                            )
                        }
                    }

                    if (state.availableTracks.subtitles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sottotitoli",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.availableTracks.subtitles) { subLang ->
                                FilterChip(
                                    selected = subLang in state.selectedSubtitleLanguages,
                                    onClick = { onToggleSubtitleLang(subLang) },
                                    label = { Text(subLang.uppercase()) }
                                )
                            }
                        }
                    }
                }
            }

            // Actions for Movie vs Series/Anime
            if (!state.title.isSeries && state.title.source == MediaSource.STREAMING_COMMUNITY) {
                val filmJob = activeOrCompletedJobs.firstOrNull { it.mediaType == "movie" }
                val filmProgress = playbackProgressMap[buildStreamingMediaKey(state.title, season = null, episode = null)]
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (filmProgress != null && (filmProgress.completed || filmProgress.positionMs >= 5_000L)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (filmProgress.completed) Icons.Default.CheckCircle else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = if (filmProgress.completed) AccentEmerald else AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (filmProgress.completed) {
                                        "Film già visto completamente"
                                    } else {
                                        "In corso • riprendi da ${formatPlaybackTime(filmProgress.positionMs)}"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (filmProgress.completed) AccentEmerald else AccentCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!filmProgress.completed && filmProgress.durationMs > 0L) {
                                LinearProgressIndicator(
                                    progress = { (filmProgress.positionMs.toFloat() / filmProgress.durationMs.toFloat()).coerceIn(0.02f, 1f) },
                                    color = AccentCyan,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onStreamFilm,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val playLabel = when {
                                    filmProgress?.completed == true -> "Riguarda"
                                    filmProgress != null && filmProgress.positionMs >= 5_000L ->
                                        "Riprendi (${formatPlaybackTime(filmProgress.positionMs)})"
                                    else -> "Guarda ora"
                                }
                                Text(
                                    text = playLabel,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = onDownloadFilm,
                                enabled = filmJob == null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        filmJob == null -> Icons.Default.Download
                                        filmJob.status == DownloadStatus.COMPLETED.name -> Icons.Default.CheckCircle
                                        else -> Icons.Default.Schedule
                                    },
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        filmJob == null -> "Scarica Film"
                                        filmJob.status == DownloadStatus.COMPLETED.name -> "Scaricato"
                                        filmJob.status == DownloadStatus.QUEUED.name -> "In coda"
                                        else -> "${filmJob.progressPercent}%"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                // Series / Anime controls: Follow + Season selector + Download All
                val totalSeasonEps = state.episodes.size
                val alreadyInQueueCount = state.episodes.count { findJobForEpisode(it) != null }
                val remainingEpsCount = (totalSeasonEps - alreadyInQueueCount).coerceAtLeast(0)

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onFollowSeries,
                                enabled = !isFollowed,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isFollowed) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.Default.BookmarkAdd,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isFollowed) "Serie seguita" else "Segui serie")
                            }

                            if (state.episodes.isNotEmpty()) {
                                Button(
                                    onClick = onDownloadWholeSeason,
                                    enabled = remainingEpsCount > 0,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (remainingEpsCount == 0) Icons.Default.CheckCircle else Icons.Default.Download,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val buttonLabel = when {
                                        remainingEpsCount == 0 -> {
                                            "In coda ($totalSeasonEps/$totalSeasonEps)"
                                        }
                                        alreadyInQueueCount > 0 -> {
                                            if (state.title.source == MediaSource.ANIME_UNITY) {
                                                "Scarica restanti ($remainingEpsCount)"
                                            } else {
                                                "Scarica S%02d (+%d)".format(state.selectedSeason, remainingEpsCount)
                                            }
                                        }
                                        else -> {
                                            if (state.title.source == MediaSource.ANIME_UNITY) {
                                                "Scarica tutti ($totalSeasonEps)"
                                            } else {
                                                "Scarica S%02d (%d)".format(state.selectedSeason, totalSeasonEps)
                                            }
                                        }
                                    }
                                    Text(buttonLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        if (state.seasonNumbers.size > 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Stagioni",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                items(state.seasonNumbers) { sNum ->
                                    FilterChip(
                                        selected = sNum == state.selectedSeason,
                                        onClick = { onSelectSeason(sNum) },
                                        label = { Text("Stagione $sNum") }
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.isLoadingEpisodes) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (!state.errorMessage.isNullOrBlank()) {
                    item {
                        Text(
                            text = state.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    val watchedCount = state.episodes.count { ep ->
                        val key = buildStreamingMediaKey(state.title, state.selectedSeason, ep)
                        playbackProgressMap[key]?.completed == true
                    }
                    val inProgressCount = state.episodes.count { ep ->
                        val key = buildStreamingMediaKey(state.title, state.selectedSeason, ep)
                        val p = playbackProgressMap[key]
                        p != null && !p.completed && p.positionMs >= 5_000L
                    }
                    if (watchedCount > 0 || inProgressCount > 0) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (watchedCount > 0) Icons.Default.CheckCircle else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = if (watchedCount > 0) AccentEmerald else AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                val summaryParts = buildList {
                                    if (watchedCount > 0) {
                                        add("$watchedCount/${state.episodes.size} visti")
                                    }
                                    if (inProgressCount > 0) {
                                        add("$inProgressCount in corso")
                                    }
                                }
                                Text(
                                    text = summaryParts.joinToString(" • "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (watchedCount > 0) AccentEmerald else AccentCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    items(state.episodes, key = { it.id }) { ep ->
                        val epKey = buildStreamingMediaKey(state.title, state.selectedSeason, ep)
                        EpisodeRowItem(
                            episode = ep,
                            domain = domain,
                            existingJob = findJobForEpisode(ep),
                            playbackProgress = playbackProgressMap[epKey],
                            onStream = { onStreamEpisode(ep) },
                            onDownload = { onDownloadEpisode(ep) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRowItem(
    episode: EpisodeInfo,
    domain: String,
    existingJob: DownloadJobEntity?,
    playbackProgress: PlaybackProgressEntity?,
    onStream: () -> Unit,
    onDownload: () -> Unit
) {
    val stillUrl = HttpClientProvider.resolveImageUrl(episode.still, domain)
    val isWatched = playbackProgress?.completed == true
    val isInProgress = playbackProgress != null && !playbackProgress.completed && playbackProgress.positionMs >= 5_000L
    val progressRatio = if (playbackProgress != null && playbackProgress.durationMs > 0L) {
        (playbackProgress.positionMs.toFloat() / playbackProgress.durationMs.toFloat()).coerceIn(0.03f, 1f)
    } else 0f

    Card(
        onClick = onStream,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stillUrl != null) {
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(58.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = stillUrl,
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isWatched) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                .padding(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Visto",
                                tint = AccentEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(AccentEmerald)
                        )
                    } else if (isInProgress && progressRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressRatio)
                                    .height(3.dp)
                                    .background(AccentCyan)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${episode.number}. ${episode.name ?: "Episodio ${episode.number}"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isWatched) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!episode.plot.isNullOrBlank()) {
                    Text(
                        text = episode.plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (episode.duration != null && episode.duration > 0) {
                        Text(
                            text = "${episode.duration} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentCyan
                        )
                    }
                    if (isWatched) {
                        Surface(
                            color = AccentEmerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = AccentEmerald,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Visto",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else if (isInProgress && playbackProgress != null) {
                        Text(
                            text = "Riprendi • ${formatPlaybackTime(playbackProgress.positionMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (stillUrl == null && isInProgress && progressRatio > 0f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progressRatio },
                        color = AccentCyan,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = onStream) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Guarda episodio in streaming",
                    tint = AccentCyan
                )
            }

            when {
                existingJob == null -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Scarica episodio",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                existingJob.status == DownloadStatus.COMPLETED.name -> {
                    Surface(
                        color = AccentEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Scaricato",
                                tint = AccentEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Scaricato",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                existingJob.status == DownloadStatus.QUEUED.name -> {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "In coda",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "In coda",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                else -> {
                    Surface(
                        color = AccentCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${existingJob.progressPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
