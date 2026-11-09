package com.streamingcommunity.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamingcommunity.downloader.core.model.AppSettings
import com.streamingcommunity.downloader.core.naming.NamingEngine
import com.streamingcommunity.downloader.ui.theme.AccentCyan
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    isCheckingDomain: Boolean,
    onSaveScDomain: (String) -> Unit,
    onSaveAuDomain: (String) -> Unit,
    onSaveVideoHost: (String) -> Unit,
    onCheckDomainNow: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSaveNamingTemplate: (String, String) -> Unit
) {
    var scDomainInput by remember { mutableStateOf(settings.domain) }
    var auDomainInput by remember { mutableStateOf(settings.animeUnityDomain) }
    var videoHostInput by remember { mutableStateOf(settings.videoHost) }

    LaunchedEffect(settings.domain) { scDomainInput = settings.domain }
    LaunchedEffect(settings.animeUnityDomain) { auDomainInput = settings.animeUnityDomain }
    LaunchedEffect(settings.videoHost) { videoHostInput = settings.videoHost }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Impostazioni",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // 1. Source Domains & Recovery
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Sorgenti e Recupero Dominio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = scDomainInput,
                        onValueChange = { scDomainInput = it },
                        label = { Text("Dominio StreamingCommunity") },
                        placeholder = { Text("es. streamingcommunity.computer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSaveScDomain(scDomainInput) }) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verifica e Salva")
                        }
                        OutlinedButton(
                            onClick = onCheckDomainNow,
                            enabled = !isCheckingDomain
                        ) {
                            if (isCheckingDomain) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Trova nuovo dominio")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Controllo automatico dominio", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Cerca il nuovo indirizzo quando il sito non risponde",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.domainAutoCheckEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings { it.copy(domainAutoCheckEnabled = enabled) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Applica nuovo dominio automaticamente", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Adotta il dominio verificato senza chiedere conferma",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.domainAutoApply,
                            onCheckedChange = { enabled ->
                                onUpdateSettings { it.copy(domainAutoApply = enabled) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = auDomainInput,
                        onValueChange = { auDomainInput = it },
                        label = { Text("Host AnimeUnity") },
                        placeholder = { Text("www.animeunity.co") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(onClick = { onSaveAuDomain(auDomainInput) }) {
                        Text("Salva Host AnimeUnity")
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = videoHostInput,
                        onValueChange = { videoHostInput = it },
                        label = { Text("Override Video Host (opzionale)") },
                        placeholder = { Text("Lascia vuoto per seguire l'embed (es. vixcloud.co)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(onClick = { onSaveVideoHost(videoHostInput) }) {
                        Text("Salva Video Host")
                    }
                }
            }
        }

        // 2. Container & Subtitles
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Formato Output e Sottotitoli",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Contenitore Video", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("mp4" to "MP4 (Consigliato per Android)", "mkv" to "MKV (Matroska)").forEach { (key, label) ->
                            FilterChip(
                                selected = settings.outputContainer == key,
                                onClick = { onUpdateSettings { it.copy(outputContainer = key) } },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Modalità Sottotitoli", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("embed" to "Incorporati nel video", "external" to "File .vtt accanto al video").forEach { (key, label) ->
                            FilterChip(
                                selected = settings.subtitleMode == key,
                                onClick = { onUpdateSettings { it.copy(subtitleMode = key) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Performance & Concurrency
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Prestazioni Download",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Download simultanei: ${settings.maxConcurrentDownloads}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = settings.maxConcurrentDownloads.toFloat(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(maxConcurrentDownloads = v.roundToInt()) }
                        },
                        valueRange = 1f..4f,
                        steps = 2
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Worker segmenti HLS (Adaptive Limiter max): ${settings.maxSegmentWorkers}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = settings.maxSegmentWorkers.toFloat(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(maxSegmentWorkers = v.roundToInt()) }
                        },
                        valueRange = 2f..24f,
                        steps = 10
                    )
                }
            }
        }

        // 4. Naming Templates
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Modelli Nomi File (Naming Templates)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Segnaposto: {title}, {year}, {season}, {season2}, {episode}, {episode2} e gruppi opzionali [ ({year})]",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val editableSlots = listOf(
                        "film_file" to "File Film",
                        "episode_file" to "File Episodio Serie",
                        "anime_episode_file" to "File Episodio Anime"
                    )

                    for ((slot, label) in editableSlots) {
                        var tplInput by remember(settings.namingTemplates[slot]) {
                            mutableStateOf(settings.namingTemplates[slot].orEmpty())
                        }
                        OutlinedTextField(
                            value = tplInput,
                            onValueChange = { tplInput = it },
                            label = { Text(label) },
                            supportingText = {
                                Text("Anteprima: ${NamingEngine.preview(slot, tplInput)}")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = { onSaveNamingTemplate(slot, tplInput) }) {
                                Text("Salva modello")
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}
