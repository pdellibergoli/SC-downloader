package com.streamingcommunity.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamingcommunity.downloader.core.model.DownloadStatus
import com.streamingcommunity.downloader.data.DownloadJobEntity
import com.streamingcommunity.downloader.ui.theme.AccentAmber
import com.streamingcommunity.downloader.ui.theme.AccentCyan
import com.streamingcommunity.downloader.ui.theme.AccentEmerald
import com.streamingcommunity.downloader.ui.theme.AccentRed
import java.io.File

@Composable
fun DownloadsScreen(
    jobs: List<DownloadJobEntity>,
    onCancelJob: (Long) -> Unit,
    onRetryJob: (Long) -> Unit,
    onRemoveJob: (Long) -> Unit,
    onClearFinished: () -> Unit,
    onOpenFile: (File) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Coda Download",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${jobs.size} elementi totali",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (jobs.any { it.status in listOf("COMPLETED", "FAILED", "CANCELLED") }) {
                TextButton(onClick = onClearFinished) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pulisci terminati")
                }
            }
        }

        if (jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Nessun download in coda",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    DownloadJobCard(
                        job = job,
                        onCancel = { onCancelJob(job.id) },
                        onRetry = { onRetryJob(job.id) },
                        onRemove = { onRemoveJob(job.id) },
                        onOpen = {
                            job.outputPath?.let { path ->
                                val f = File(path)
                                if (f.exists()) onOpenFile(f)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadJobCard(
    job: DownloadJobEntity,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit
) {
    val statusEnum = runCatching { DownloadStatus.valueOf(job.status) }
        .getOrDefault(DownloadStatus.QUEUED)

    val isActive = statusEnum in listOf(
        DownloadStatus.QUEUED,
        DownloadStatus.RESOLVING,
        DownloadStatus.DOWNLOADING_VIDEO,
        DownloadStatus.DOWNLOADING_AUDIO,
        DownloadStatus.DOWNLOADING_SUBTITLES,
        DownloadStatus.JOINING,
        DownloadStatus.REMUXING
    )

    val statusColor = when (statusEnum) {
        DownloadStatus.COMPLETED -> AccentEmerald
        DownloadStatus.FAILED -> AccentRed
        DownloadStatus.CANCELLED -> AccentAmber
        else -> AccentCyan
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = statusColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = statusEnum.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            text = "Audio: ${job.audioLanguagesCsv.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row {
                    if (statusEnum == DownloadStatus.COMPLETED && !job.outputPath.isNullOrBlank()) {
                        IconButton(onClick = onOpen) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Riproduci",
                                tint = AccentEmerald
                            )
                        }
                    }
                    if (statusEnum == DownloadStatus.FAILED || statusEnum == DownloadStatus.CANCELLED) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Riprova",
                                tint = AccentCyan
                            )
                        }
                    }
                    if (isActive) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Annulla",
                                tint = AccentAmber
                            )
                        }
                    } else {
                        IconButton(onClick = onRemove) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Rimuovi",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(10.dp))
                val indeterminate = statusEnum in listOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.RESOLVING,
                    DownloadStatus.JOINING,
                    DownloadStatus.REMUXING
                )
                if (indeterminate) {
                    LinearProgressIndicator(
                        color = AccentEmerald,
                        trackColor = AccentEmerald.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { (job.progressPercent / 100f).coerceIn(0f, 1f) },
                        color = AccentEmerald,
                        trackColor = AccentEmerald.copy(alpha = 0.2f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val segText = if (job.totalSegments > 0) {
                        "${job.completedSegments}/${job.totalSegments} seg (${job.progressPercent}%)"
                    } else {
                        "${job.progressPercent}%"
                    }
                    Text(
                        text = segText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val speedMb = job.speedBytesPerSec / (1024.0 * 1024.0)
                    val downloadedMb = job.downloadedBytes / (1024.0 * 1024.0)
                    val etaStr = if (job.etaSeconds > 0) " • ETA ${job.etaSeconds}s" else ""
                    Text(
                        text = "%.1f MB (%.1f MB/s)%s".format(downloadedMb, speedMb, etaStr),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!job.errorMessage.isNullOrBlank() && statusEnum != DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = job.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed
                )
            }
        }
    }
}
