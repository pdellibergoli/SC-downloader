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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import com.streamingcommunity.downloader.data.WatchEntity
import com.streamingcommunity.downloader.ui.theme.AccentCyan
import com.streamingcommunity.downloader.ui.theme.AccentRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WatchlistScreen(
    watches: List<WatchEntity>,
    domain: String,
    isChecking: Boolean,
    onCheckAllNow: () -> Unit,
    onOpenWatch: (WatchEntity) -> Unit,
    onToggleAutoDownload: (WatchEntity, Boolean) -> Unit,
    onUnfollow: (Long) -> Unit
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
                    text = "Serie Seguite",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Tocca una serie per vedere stagioni ed episodi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onCheckAllNow,
                enabled = !isChecking && watches.isNotEmpty()
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Controlla ora")
            }
        }

        if (watches.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Bookmarks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Nessuna serie o anime seguito",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Apri una serie TV o un anime e premi 'Segui serie'",
                        style = MaterialTheme.typography.bodySmall,
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
                items(watches, key = { it.id }) { watch ->
                    WatchItemCard(
                        watch = watch,
                        domain = domain,
                        onClick = { onOpenWatch(watch) },
                        onToggleAutoDownload = { enabled -> onToggleAutoDownload(watch, enabled) },
                        onUnfollow = { onUnfollow(watch.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchItemCard(
    watch: WatchEntity,
    domain: String,
    onClick: () -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onUnfollow: () -> Unit
) {
    val posterUrl = HttpClientProvider.resolveImageUrl(watch.poster, domain)
    val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = watch.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(64.dp)
                        .height(94.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = watch.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${watch.source.uppercase()} • ${watch.knownEpisodesCount} episodi noti",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
                watch.lastCheckedAtMs?.let { ts ->
                    Text(
                        text = "Ultimo controllo: ${dateFormat.format(Date(ts))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!watch.lastError.isNullOrBlank()) {
                    Text(
                        text = watch.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Scarica nuovi ep. automaticamente",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = watch.autoDownload,
                        onCheckedChange = onToggleAutoDownload
                    )
                }
            }

            IconButton(onClick = onUnfollow) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Smetti di seguire",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
