package com.streamingcommunity.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamingcommunity.downloader.core.model.DomainCandidate
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.model.MediaTitle
import com.streamingcommunity.downloader.core.model.Shelf
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import com.streamingcommunity.downloader.ui.theme.AccentAmber
import com.streamingcommunity.downloader.ui.theme.AccentCyan

@Composable
fun ExploreScreen(
    selectedSource: MediaSource,
    domain: String,
    searchQuery: String,
    mediaTypeFilter: String?,
    dubbedOnlyFilter: Boolean,
    isLoadingHome: Boolean,
    isSearching: Boolean,
    shelves: List<Shelf>,
    searchResults: List<MediaTitle>,
    exploreError: String?,
    pendingDomainCandidate: DomainCandidate?,
    onSelectSource: (MediaSource) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: (Boolean) -> Unit,
    onFilterChange: (String?) -> Unit,
    onDubbedFilterChange: (Boolean) -> Unit,
    onOpenTitle: (MediaTitle) -> Unit,
    onRefreshHome: () -> Unit,
    onCheckDomainNow: () -> Unit,
    onApplyCandidate: (String) -> Unit,
    onDismissCandidate: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top header + Source switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "SC Downloader",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (selectedSource == MediaSource.STREAMING_COMMUNITY) {
                        domain.ifBlank { "Dominio non impostato" }
                    } else {
                        "AnimeUnity"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaSource.entries.forEach { src ->
                    val selected = src == selectedSource
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectSource(src) },
                        label = {
                            Text(
                                text = if (src == MediaSource.STREAMING_COMMUNITY) "Streaming" else "AnimeUnity",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        // Pending Domain Candidate Banner
        if (pendingDomainCandidate != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF422006))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AccentAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Nuovo dominio rilevato: ${pendingDomainCandidate.host}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApplyCandidate(pendingDomainCandidate.host) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black)
                        ) {
                            Text("Applica dominio", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = onDismissCandidate) {
                            Text("Ignora")
                        }
                    }
                }
            }
        }

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = {
                Text(
                    if (selectedSource == MediaSource.STREAMING_COMMUNITY)
                        "Cerca film o serie TV..."
                    else
                        "Cerca anime su AnimeUnity..."
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Cerca")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Cancella")
                    }
                } else {
                    IconButton(onClick = onRefreshHome) {
                        Icon(Icons.Default.Refresh, contentDescription = "Ricarica")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    onPerformSearch(true)
                }
            )
        )

        // Filter row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedSource == MediaSource.STREAMING_COMMUNITY) {
                val filters = listOf(
                    null to "Tutti",
                    "movie" to "Film",
                    "tv" to "Serie TV"
                )
                items(filters) { (key, label) ->
                    FilterChip(
                        selected = mediaTypeFilter == key,
                        onClick = { onFilterChange(key) },
                        label = { Text(label) }
                    )
                }
            } else {
                val animeFilters = listOf(
                    null to "Tutti",
                    "tv" to "TV",
                    "movie" to "Movie",
                    "ova" to "OVA",
                    "ona" to "ONA"
                )
                items(animeFilters) { (key, label) ->
                    FilterChip(
                        selected = mediaTypeFilter == key,
                        onClick = { onFilterChange(key) },
                        label = { Text(label) }
                    )
                }
                item {
                    FilterChip(
                        selected = dubbedOnlyFilter,
                        onClick = { onDubbedFilterChange(!dubbedOnlyFilter) },
                        label = { Text("Solo ITA (Dub)") }
                    )
                }
            }
        }

        // Content body
        when {
            isSearching || (isLoadingHome && shelves.isEmpty()) -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            !exploreError.isNullOrBlank() && searchResults.isEmpty() && shelves.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AccentAmber,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = exploreError,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onCheckDomainNow) {
                                Text("Cerca dominio automatico")
                            }
                            OutlinedButton(onClick = onRefreshHome) {
                                Text("Riprova")
                            }
                        }
                    }
                }
            }

            searchQuery.isNotBlank() -> {
                if (searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Premi Invio sulla tastiera o Cerca per avviare",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { onPerformSearch(true) }) {
                                Text("Cerca «$searchQuery»")
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 115.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(searchResults, key = { "${it.source.id}_${it.id}" }) { item ->
                            MediaPosterCard(
                                item = item,
                                domain = domain,
                                onClick = { onOpenTitle(item) }
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(onClick = { onPerformSearch(false) }) {
                                    Text("Carica altri risultati")
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(shelves, key = { it.key }) { shelf ->
                        Column {
                            Text(
                                text = shelf.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(shelf.items, key = { "${shelf.key}_${it.id}" }) { item ->
                                    MediaPosterCard(
                                        item = item,
                                        domain = domain,
                                        modifier = Modifier.width(130.dp),
                                        onClick = { onOpenTitle(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaPosterCard(
    item: MediaTitle,
    domain: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val posterUrl = HttpClientProvider.resolveImageUrl(item.poster, domain)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.Center)
                )
            }

            // Type badge
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                val badgeText = when {
                    item.source == MediaSource.ANIME_UNITY -> {
                        val base = item.mediaType.ifEmpty { "Anime" }
                        if (item.dubbed) "$base • ITA" else base
                    }
                    item.type == "tv" -> "SERIE"
                    else -> "FILM"
                }
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Rating badge
            if (item.score != null && item.score > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = AccentAmber,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "%.1f".format(item.score),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Bottom gradient for year
            item.year?.let { yr ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = yr,
                        fontSize = 10.sp,
                        color = Color.LightGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
