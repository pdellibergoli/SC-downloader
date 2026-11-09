package com.streamingcommunity.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.streamingcommunity.downloader.core.model.DownloadStatus
import com.streamingcommunity.downloader.ui.MainViewModel
import com.streamingcommunity.downloader.ui.screens.DownloadsScreen
import com.streamingcommunity.downloader.ui.screens.ExploreScreen
import com.streamingcommunity.downloader.ui.screens.LibraryScreen
import com.streamingcommunity.downloader.ui.screens.SettingsScreen
import com.streamingcommunity.downloader.ui.screens.TitleDetailSheet
import com.streamingcommunity.downloader.ui.screens.WatchlistScreen
import com.streamingcommunity.downloader.ui.theme.SCDownloaderTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        setContent {
            SCDownloaderTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                var showBatteryDialog by rememberSaveable {
                    mutableStateOf(!isIgnoringBatteryOptimizations())
                }

                val settings by viewModel.settingsState.collectAsState()
                val selectedSource by viewModel.selectedSource.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val mediaTypeFilter by viewModel.mediaTypeFilter.collectAsState()
                val dubbedOnlyFilter by viewModel.dubbedOnlyFilter.collectAsState()
                val isLoadingHome by viewModel.isLoadingHome.collectAsState()
                val isSearching by viewModel.isSearching.collectAsState()
                val shelves by viewModel.shelves.collectAsState()
                val searchResults by viewModel.searchResults.collectAsState()
                val exploreError by viewModel.exploreError.collectAsState()
                val pendingDomainCandidate by viewModel.pendingDomainCandidate.collectAsState()
                val detailState by viewModel.detailState.collectAsState()
                val downloadJobs by viewModel.downloadJobsState.collectAsState()
                val watches by viewModel.watchesState.collectAsState()
                val localFiles by viewModel.localFiles.collectAsState()
                val isCheckingWatches by viewModel.isCheckingWatches.collectAsState()
                val isCheckingDomain by viewModel.isCheckingDomain.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.snackbarMessage.collect { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                if (showBatteryDialog && !isIgnoringBatteryOptimizations()) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog = false },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = null
                            )
                        },
                        title = {
                            Text("Download in background senza restrizioni")
                        },
                        text = {
                            Text(
                                "Per mantenere attivi i download e il controllo automatico dei nuovi episodi anche quando l'app è in background o lo schermo è spento, imposta l'utilizzo della batteria su «Senza restrizioni»."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showBatteryDialog = false
                                    requestUnrestrictedBattery()
                                }
                            ) {
                                Text("Attiva ora")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatteryDialog = false }) {
                                Text("Più tardi")
                            }
                        }
                    )
                }

                val activeDownloadsCount = downloadJobs.count {
                    it.status in listOf(
                        DownloadStatus.QUEUED.name,
                        DownloadStatus.RESOLVING.name,
                        DownloadStatus.DOWNLOADING_VIDEO.name,
                        DownloadStatus.DOWNLOADING_AUDIO.name,
                        DownloadStatus.DOWNLOADING_SUBTITLES.name,
                        DownloadStatus.JOINING.name,
                        DownloadStatus.REMUXING.name
                    )
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = {
                                    selectedTab = 0
                                    viewModel.closeTitleDetail()
                                },
                                icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                                label = { Text("Esplora") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = {
                                    selectedTab = 1
                                    viewModel.closeTitleDetail()
                                },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (activeDownloadsCount > 0) {
                                                Badge { Text(activeDownloadsCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                    }
                                },
                                label = { Text("Download") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = {
                                    selectedTab = 2
                                    viewModel.closeTitleDetail()
                                },
                                icon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                                label = { Text("Seguite") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = {
                                    selectedTab = 3
                                    viewModel.closeTitleDetail()
                                    viewModel.refreshLocalLibrary()
                                },
                                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                                label = { Text("Libreria") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = {
                                    selectedTab = 4
                                    viewModel.closeTitleDetail()
                                },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Impostazioni") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            0 -> ExploreScreen(
                                selectedSource = selectedSource,
                                domain = settings.domain,
                                searchQuery = searchQuery,
                                mediaTypeFilter = mediaTypeFilter,
                                dubbedOnlyFilter = dubbedOnlyFilter,
                                isLoadingHome = isLoadingHome,
                                isSearching = isSearching,
                                shelves = shelves,
                                searchResults = searchResults,
                                exploreError = exploreError,
                                pendingDomainCandidate = pendingDomainCandidate,
                                onSelectSource = viewModel::selectSource,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onPerformSearch = viewModel::performSearch,
                                onFilterChange = viewModel::updateMediaTypeFilter,
                                onDubbedFilterChange = viewModel::updateDubbedFilter,
                                onOpenTitle = viewModel::openTitleDetail,
                                onRefreshHome = viewModel::loadHomeShelves,
                                onCheckDomainNow = viewModel::checkDomainRecoveryNow,
                                onApplyCandidate = viewModel::applyPendingDomainCandidate,
                                onDismissCandidate = viewModel::dismissPendingDomainCandidate
                            )

                            1 -> DownloadsScreen(
                                jobs = downloadJobs,
                                onCancelJob = viewModel::cancelDownload,
                                onRetryJob = viewModel::retryDownload,
                                onRemoveJob = viewModel::removeDownload,
                                onClearFinished = viewModel::clearFinishedDownloads,
                                onOpenFile = ::openVideoFile
                            )

                            2 -> WatchlistScreen(
                                watches = watches,
                                domain = settings.domain,
                                isChecking = isCheckingWatches,
                                onCheckAllNow = viewModel::checkAllWatchesNow,
                                onOpenWatch = viewModel::openWatchDetail,
                                onToggleAutoDownload = viewModel::toggleWatchAutoDownload,
                                onUnfollow = viewModel::unfollowWatch
                            )

                            3 -> LibraryScreen(
                                files = localFiles,
                                onRefresh = viewModel::refreshLocalLibrary,
                                onPlayFile = ::openVideoFile,
                                onShareFile = ::shareVideoFile,
                                onDeleteFile = viewModel::deleteLocalFile
                            )

                            4 -> SettingsScreen(
                                settings = settings,
                                isCheckingDomain = isCheckingDomain,
                                onSaveScDomain = viewModel::saveAndVerifyStreamingCommunityDomain,
                                onSaveAuDomain = viewModel::saveAndVerifyAnimeUnityDomain,
                                onSaveVideoHost = viewModel::saveAndVerifyVideoHost,
                                onCheckDomainNow = viewModel::checkDomainRecoveryNow,
                                onUpdateSettings = viewModel::updateSettings,
                                onSaveNamingTemplate = viewModel::saveNamingTemplate
                            )
                        }

                        detailState?.let { currentDetail ->
                            val isFollowed = watches.any {
                                it.source == currentDetail.title.source.id &&
                                    it.externalId == currentDetail.title.id
                            }
                            val titleJobs = downloadJobs.filter {
                                it.source == currentDetail.title.source.id &&
                                    it.externalId == currentDetail.title.id
                            }
                            TitleDetailSheet(
                                state = currentDetail,
                                domain = settings.domain,
                                isFollowed = isFollowed,
                                titleJobs = titleJobs,
                                onDismiss = viewModel::closeTitleDetail,
                                onSelectSeason = viewModel::selectSeasonInDetail,
                                onToggleAudioLang = viewModel::toggleAudioLanguage,
                                onToggleSubtitleLang = viewModel::toggleSubtitleLanguage,
                                onDownloadFilm = viewModel::downloadFilmFromDetail,
                                onDownloadEpisode = viewModel::downloadEpisodeFromDetail,
                                onDownloadWholeSeason = viewModel::downloadWholeSeasonFromDetail,
                                onFollowSeries = viewModel::followSeriesFromDetail
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestUnrestrictedBattery() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                viewModel.showMessage("Impossibile aprire le impostazioni batteria")
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun openVideoFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val mime = if (file.extension.equals("mkv", ignoreCase = true)) {
                "video/x-matroska"
            } else {
                "video/mp4"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Apri video con"))
        } catch (e: Exception) {
            viewModel.showMessage("Nessun player video trovato per aprire il file")
        }
    }

    private fun shareVideoFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi video"))
        } catch (e: Exception) {
            viewModel.showMessage("Impossibile condividere il file")
        }
    }
}
