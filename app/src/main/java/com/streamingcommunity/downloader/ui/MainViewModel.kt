package com.streamingcommunity.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamingcommunity.downloader.BuildConfig
import com.streamingcommunity.downloader.SCApplication
import com.streamingcommunity.downloader.core.model.AppSettings
import com.streamingcommunity.downloader.core.model.AppUpdateInfo
import com.streamingcommunity.downloader.core.model.AvailableTracks
import com.streamingcommunity.downloader.core.model.DomainCandidate
import com.streamingcommunity.downloader.core.model.EpisodeInfo
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.model.MediaTitle
import com.streamingcommunity.downloader.core.model.Shelf
import com.streamingcommunity.downloader.core.model.StreamSource
import com.streamingcommunity.downloader.core.model.TitleMetadata
import com.streamingcommunity.downloader.core.naming.NamingEngine
import com.streamingcommunity.downloader.core.network.AnimeUnityClient
import com.streamingcommunity.downloader.core.network.AppUpdateManager
import com.streamingcommunity.downloader.core.network.DomainRecoveryManager
import com.streamingcommunity.downloader.core.network.StreamingCommunityClient
import com.streamingcommunity.downloader.core.network.UpdateCheckOutcome
import com.streamingcommunity.downloader.core.network.VixcloudParser
import com.streamingcommunity.downloader.data.DownloadJobEntity
import com.streamingcommunity.downloader.data.WatchEntity
import com.streamingcommunity.downloader.service.BackgroundWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalMediaFile(
    val file: File,
    val name: String,
    val category: String, // "Movies", "Series", "Anime"
    val relativeFolder: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val extension: String
)

data class TitleDetailUiState(
    val title: MediaTitle,
    val isLoadingMetadata: Boolean = false,
    val metadata: TitleMetadata = TitleMetadata.EMPTY,
    val availableTracks: AvailableTracks = AvailableTracks(audio = listOf("ita")),
    val selectedAudioLanguages: Set<String> = setOf("ita"),
    val selectedSubtitleLanguages: Set<String> = emptySet(),
    val seasonNumbers: List<Int> = emptyList(),
    val selectedSeason: Int = 1,
    val isLoadingEpisodes: Boolean = false,
    val episodes: List<EpisodeInfo> = emptyList(),
    val errorMessage: String? = null
)

data class StreamingPlayerUiState(
    val title: MediaTitle,
    val displayTitle: String,
    val subtitleText: String? = null,
    val season: Int? = null,
    val currentEpisode: EpisodeInfo? = null,
    val seasonEpisodes: List<EpisodeInfo> = emptyList(),
    val preferredAudioLang: String? = null,
    val preferredSubtitleLang: String? = null,
    val isLoading: Boolean = true,
    val streamSource: StreamSource? = null,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SCApplication
    private val settingsRepository = app.settingsRepository
    private val downloadManager = app.downloadManager
    private val watchlistManager = app.watchlistManager

    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val downloadJobsState: StateFlow<List<DownloadJobEntity>> = downloadManager.allJobsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchesState: StateFlow<List<WatchEntity>> = watchlistManager.allWatchesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingDomainCandidate: StateFlow<DomainCandidate?> = DomainRecoveryManager.pendingCandidate

    private val _selectedSource = MutableStateFlow(MediaSource.STREAMING_COMMUNITY)
    val selectedSource: StateFlow<MediaSource> = _selectedSource.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _mediaTypeFilter = MutableStateFlow<String?>(null)
    val mediaTypeFilter: StateFlow<String?> = _mediaTypeFilter.asStateFlow()

    private val _dubbedOnlyFilter = MutableStateFlow(false)
    val dubbedOnlyFilter: StateFlow<Boolean> = _dubbedOnlyFilter.asStateFlow()

    private val _isLoadingHome = MutableStateFlow(false)
    val isLoadingHome: StateFlow<Boolean> = _isLoadingHome.asStateFlow()

    private val _shelves = MutableStateFlow<List<Shelf>>(emptyList())
    val shelves: StateFlow<List<Shelf>> = _shelves.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaTitle>>(emptyList())
    val searchResults: StateFlow<List<MediaTitle>> = _searchResults.asStateFlow()

    private val _searchPage = MutableStateFlow(1)
    val searchPage: StateFlow<Int> = _searchPage.asStateFlow()

    private val _exploreError = MutableStateFlow<String?>(null)
    val exploreError: StateFlow<String?> = _exploreError.asStateFlow()

    private val _detailState = MutableStateFlow<TitleDetailUiState?>(null)
    val detailState: StateFlow<TitleDetailUiState?> = _detailState.asStateFlow()

    private val _streamingState = MutableStateFlow<StreamingPlayerUiState?>(null)
    val streamingState: StateFlow<StreamingPlayerUiState?> = _streamingState.asStateFlow()
    private var streamingResolveJob: Job? = null

    private val _localFiles = MutableStateFlow<List<LocalMediaFile>>(emptyList())
    val localFiles: StateFlow<List<LocalMediaFile>> = _localFiles.asStateFlow()

    private val _isCheckingWatches = MutableStateFlow(false)
    val isCheckingWatches: StateFlow<Boolean> = _isCheckingWatches.asStateFlow()

    private val _isCheckingDomain = MutableStateFlow(false)
    val isCheckingDomain: StateFlow<Boolean> = _isCheckingDomain.asStateFlow()

    private val _isCheckingAppUpdate = MutableStateFlow(false)
    val isCheckingAppUpdate: StateFlow<Boolean> = _isCheckingAppUpdate.asStateFlow()

    private val _availableUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val availableUpdate: StateFlow<AppUpdateInfo?> = _availableUpdate.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0)
    val updateDownloadProgress: StateFlow<Int> = _updateDownloadProgress.asStateFlow()

    private val _downloadedUpdateFile = MutableStateFlow<File?>(null)
    val downloadedUpdateFile: StateFlow<File?> = _downloadedUpdateFile.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { s ->
                if (_shelves.value.isEmpty() && _searchQuery.value.isBlank()) {
                    loadHomeShelves()
                }
            }
        }
        viewModelScope.launch {
            val initialSettings = settingsRepository.getSettings()
            if (initialSettings.autoCheckAppUpdates) {
                checkAppUpdate(manual = false)
            }
        }
        refreshLocalLibrary()
    }

    fun showMessage(msg: String) {
        viewModelScope.launch {
            _snackbarMessage.emit(msg)
        }
    }

    fun selectSource(source: MediaSource) {
        if (_selectedSource.value == source) return
        _selectedSource.value = source
        _mediaTypeFilter.value = null
        _dubbedOnlyFilter.value = false
        _searchResults.value = emptyList()
        _exploreError.value = null
        if (_searchQuery.value.isNotBlank()) {
            performSearch(resetPage = true)
        } else {
            loadHomeShelves()
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _exploreError.value = null
            loadHomeShelves()
        }
    }

    fun updateMediaTypeFilter(filter: String?) {
        _mediaTypeFilter.value = filter
        if (_searchQuery.value.isNotBlank()) {
            performSearch(resetPage = true)
        }
    }

    fun updateDubbedFilter(dubbed: Boolean) {
        _dubbedOnlyFilter.value = dubbed
        if (_searchQuery.value.isNotBlank()) {
            performSearch(resetPage = true)
        }
    }

    fun loadHomeShelves() {
        viewModelScope.launch {
            _isLoadingHome.value = true
            _exploreError.value = null
            try {
                val settings = settingsRepository.getSettings()
                val source = _selectedSource.value
                val loaded = if (source == MediaSource.ANIME_UNITY) {
                    AnimeUnityClient.getHomeShelves(settings.animeUnityDomain)
                } else {
                    if (settings.domain.isBlank()) {
                        // Try automatic domain recovery first
                        val check = DomainRecoveryManager.runCheck(
                            currentDomain = "",
                            autoApply = true,
                            force = true,
                            onSaveDomain = { settingsRepository.updateDomain(it) }
                        )
                        val activeDomain = check.candidate ?: settingsRepository.getSettings().domain
                        if (activeDomain.isBlank()) {
                            _exploreError.value = "Configura il dominio StreamingCommunity nelle Impostazioni o premi 'Cerca dominio'."
                            emptyList()
                        } else {
                            StreamingCommunityClient.getHomeShelves(activeDomain)
                        }
                    } else {
                        StreamingCommunityClient.getHomeShelves(settings.domain)
                    }
                }
                _shelves.value = loaded
            } catch (e: Exception) {
                _exploreError.value = e.message ?: "Impossibile caricare la pagina iniziale"
            } finally {
                _isLoadingHome.value = false
            }
        }
    }

    fun performSearch(resetPage: Boolean = true) {
        val q = _searchQuery.value.trim()
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            loadHomeShelves()
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            _exploreError.value = null
            val targetPage = if (resetPage) 1 else (_searchPage.value + 1)
            try {
                val settings = settingsRepository.getSettings()
                val source = _selectedSource.value
                val results = if (source == MediaSource.ANIME_UNITY) {
                    AnimeUnityClient.search(
                        query = q,
                        host = settings.animeUnityDomain,
                        page = targetPage,
                        mediaType = _mediaTypeFilter.value,
                        dubbed = _dubbedOnlyFilter.value
                    )
                } else {
                    if (settings.domain.isBlank()) {
                        throw RuntimeException("Dominio StreamingCommunity non configurato nelle Impostazioni")
                    }
                    StreamingCommunityClient.search(
                        query = q,
                        domain = settings.domain,
                        page = targetPage,
                        mediaType = _mediaTypeFilter.value
                    )
                }
                _searchPage.value = targetPage
                _searchResults.value = if (resetPage) {
                    results
                } else {
                    (_searchResults.value + results).distinctBy { it.id }
                }
            } catch (e: Exception) {
                _exploreError.value = e.message ?: "Errore durante la ricerca"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun openWatchDetail(watch: WatchEntity) {
        val sourceEnum = MediaSource.fromId(watch.source)
        val numericId = watch.externalId.substringBefore("-").toIntOrNull()
        val mediaTitle = MediaTitle(
            id = watch.externalId,
            numericId = numericId,
            name = watch.title,
            type = if (sourceEnum == MediaSource.ANIME_UNITY) "anime" else "tv",
            mediaType = watch.animeType.ifEmpty { "TV" },
            slug = watch.slug,
            releaseDate = watch.year,
            poster = watch.poster,
            source = sourceEnum
        )
        openTitleDetail(mediaTitle)
    }

    fun openTitleDetail(title: MediaTitle) {
        val existingWatch = watchesState.value.firstOrNull {
            it.source == title.source.id && it.externalId == title.id
        }
        val initialAudio = existingWatch?.audioLanguagesCsv
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf("ita")
        val initialSubs = existingWatch?.subtitleLanguagesCsv
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        _detailState.value = TitleDetailUiState(
            title = title,
            selectedAudioLanguages = initialAudio,
            selectedSubtitleLanguages = initialSubs,
            isLoadingMetadata = true,
            isLoadingEpisodes = title.isSeries || title.source == MediaSource.ANIME_UNITY
        )

        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            if (title.source == MediaSource.ANIME_UNITY) {
                // If opened from Watchlist, plot/backdrop might be absent; try enriching via search by name
                val enrichedTitle = if (title.plot.isNullOrBlank()) {
                    runCatching {
                        AnimeUnityClient.search(title.name, settings.animeUnityDomain)
                            .firstOrNull { it.id == title.id }
                    }.getOrNull() ?: title
                } else {
                    title
                }

                val meta = TitleMetadata(
                    source = "animeunity",
                    plot = enrichedTitle.plot,
                    genres = enrichedTitle.genres,
                    rating = enrichedTitle.score,
                    backdrop = enrichedTitle.backdrop,
                    name = enrichedTitle.name.ifBlank { title.name },
                    poster = enrichedTitle.poster ?: title.poster,
                    type = enrichedTitle.mediaType.ifEmpty { "TV" },
                    originalName = enrichedTitle.originalName.ifEmpty { null },
                    status = enrichedTitle.status.ifEmpty { null },
                    year = enrichedTitle.year
                )
                _detailState.value = _detailState.value?.copy(
                    title = enrichedTitle.copy(poster = enrichedTitle.poster ?: title.poster),
                    isLoadingMetadata = false,
                    metadata = meta
                )

                try {
                    val eps = AnimeUnityClient.getEpisodes(title.id, settings.animeUnityDomain)
                    val tracks = if (eps.isNotEmpty()) {
                        runCatching {
                            AnimeUnityClient.getEpisodeLanguages(
                                episodeId = eps.first().id,
                                host = settings.animeUnityDomain,
                                videoHostOverride = settings.videoHost
                            )
                        }.getOrDefault(AvailableTracks(audio = if (enrichedTitle.dubbed) listOf("ita") else listOf("jpn")))
                    } else {
                        AvailableTracks(audio = if (enrichedTitle.dubbed) listOf("ita") else listOf("jpn"))
                    }

                    val defaultAudio = tracks.audio.firstOrNull() ?: if (enrichedTitle.dubbed) "ita" else "jpn"
                    val currentAudio = _detailState.value?.selectedAudioLanguages?.takeIf { it.isNotEmpty() }
                    _detailState.value = _detailState.value?.copy(
                        isLoadingEpisodes = false,
                        episodes = eps,
                        availableTracks = tracks,
                        selectedAudioLanguages = currentAudio ?: setOf(defaultAudio)
                    )
                } catch (e: Exception) {
                    _detailState.value = _detailState.value?.copy(
                        isLoadingEpisodes = false,
                        errorMessage = e.message ?: "Impossibile caricare episodi anime"
                    )
                }
            } else {
                // StreamingCommunity
                val domain = settings.domain
                val numericId = title.numericId ?: title.id.toIntOrNull()
                if (domain.isBlank() || numericId == null) {
                    _detailState.value = _detailState.value?.copy(
                        isLoadingMetadata = false,
                        isLoadingEpisodes = false,
                        errorMessage = "Dominio o ID non valido"
                    )
                    return@launch
                }

                try {
                    val version = StreamingCommunityClient.getDomainVersion(domain).orEmpty()
                    val meta = StreamingCommunityClient.getTitleMetadata(
                        mediaType = title.type,
                        titleId = numericId,
                        slug = title.slug,
                        siteVersion = version,
                        domain = domain
                    )
                    val seasons = if (title.type == "tv") {
                        meta.seasonNumbers.ifEmpty {
                            if (title.seasonsCount > 0) (1..title.seasonsCount).toList() else listOf(1)
                        }
                    } else emptyList()

                    val firstSeason = seasons.firstOrNull() ?: 1
                    _detailState.value = _detailState.value?.copy(
                        isLoadingMetadata = false,
                        metadata = meta,
                        seasonNumbers = seasons,
                        selectedSeason = firstSeason
                    )

                    if (title.type == "tv") {
                        loadSeasonEpisodesForDetail(numericId, title.slug, firstSeason, version, domain)
                    } else {
                        val tracks = runCatching {
                            StreamingCommunityClient.getFilmLanguages(numericId, domain, settings.videoHost)
                        }.getOrDefault(AvailableTracks(audio = listOf("ita")))
                        val defaultAudio = tracks.audio.firstOrNull() ?: "ita"
                        _detailState.value = _detailState.value?.copy(
                            isLoadingEpisodes = false,
                            availableTracks = tracks,
                            selectedAudioLanguages = setOf(defaultAudio)
                        )
                    }
                } catch (e: Exception) {
                    _detailState.value = _detailState.value?.copy(
                        isLoadingMetadata = false,
                        isLoadingEpisodes = false,
                        errorMessage = e.message ?: "Errore caricamento dettagli"
                    )
                }
            }
        }
    }

    fun selectSeasonInDetail(season: Int) {
        val current = _detailState.value ?: return
        if (current.selectedSeason == season && current.episodes.isNotEmpty()) return
        val numericId = current.title.numericId ?: current.title.id.toIntOrNull() ?: return

        _detailState.value = current.copy(
            selectedSeason = season,
            isLoadingEpisodes = true,
            errorMessage = null
        )

        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            val version = runCatching { StreamingCommunityClient.getDomainVersion(settings.domain).orEmpty() }
                .getOrDefault("")
            loadSeasonEpisodesForDetail(numericId, current.title.slug, season, version, settings.domain)
        }
    }

    private suspend fun loadSeasonEpisodesForDetail(
        tvId: Int,
        slug: String,
        season: Int,
        version: String,
        domain: String
    ) {
        try {
            val settings = settingsRepository.getSettings()
            val token = StreamingCommunityClient.getToken(tvId, domain)
            val eps = StreamingCommunityClient.getSeasonEpisodes(
                tvId = tvId,
                tvSlug = slug,
                domain = domain,
                version = version,
                token = token,
                seasonNumber = season
            )
            val tracks = if (eps.isNotEmpty()) {
                runCatching {
                    StreamingCommunityClient.getEpisodeLanguages(
                        tvId = tvId,
                        epId = eps.first().id,
                        domain = domain,
                        token = token,
                        videoHostOverride = settings.videoHost
                    )
                }.getOrDefault(AvailableTracks(audio = listOf("ita")))
            } else {
                AvailableTracks(audio = listOf("ita"))
            }

            val defaultAudio = tracks.audio.firstOrNull() ?: "ita"
            _detailState.value = _detailState.value?.copy(
                isLoadingEpisodes = false,
                episodes = eps,
                availableTracks = tracks,
                selectedAudioLanguages = _detailState.value?.selectedAudioLanguages?.takeIf { it.isNotEmpty() }
                    ?: setOf(defaultAudio)
            )
        } catch (e: Exception) {
            _detailState.value = _detailState.value?.copy(
                isLoadingEpisodes = false,
                errorMessage = e.message ?: "Impossibile caricare gli episodi della stagione $season"
            )
        }
    }

    fun toggleAudioLanguage(lang: String) {
        val current = _detailState.value ?: return
        val updated = current.selectedAudioLanguages.toMutableSet()
        if (lang in updated) {
            if (updated.size > 1) updated.remove(lang)
        } else {
            updated.add(lang)
        }
        _detailState.value = current.copy(selectedAudioLanguages = updated)
    }

    fun toggleSubtitleLanguage(lang: String) {
        val current = _detailState.value ?: return
        val updated = current.selectedSubtitleLanguages.toMutableSet()
        if (lang in updated) {
            updated.remove(lang)
        } else {
            updated.add(lang)
        }
        _detailState.value = current.copy(selectedSubtitleLanguages = updated)
    }

    fun closeTitleDetail() {
        _detailState.value = null
    }

    fun streamFilmFromDetail() {
        val detail = _detailState.value ?: return
        val audioPref = detail.selectedAudioLanguages.firstOrNull()
        val subPref = detail.selectedSubtitleLanguages.firstOrNull()
        val yearLabel = detail.metadata.year ?: detail.title.year

        _streamingState.value = StreamingPlayerUiState(
            title = detail.title,
            displayTitle = detail.title.name,
            subtitleText = yearLabel?.let { "Film ($it)" } ?: "Film",
            preferredAudioLang = audioPref,
            preferredSubtitleLang = subPref,
            isLoading = true
        )
        resolveCurrentStreamingSource()
    }

    fun streamEpisodeFromDetail(episode: EpisodeInfo) {
        val detail = _detailState.value ?: return
        val audioPref = detail.selectedAudioLanguages.firstOrNull()
        val subPref = detail.selectedSubtitleLanguages.firstOrNull()
        val epTitle = episode.name?.takeIf { it.isNotBlank() } ?: "Episodio ${episode.number}"
        val subtitleLabel = if (detail.title.source == MediaSource.ANIME_UNITY) {
            "Ep. ${episode.number} • $epTitle"
        } else {
            "S%02dE%s • %s".format(detail.selectedSeason, episode.number.padStart(2, '0'), epTitle)
        }

        _streamingState.value = StreamingPlayerUiState(
            title = detail.title,
            displayTitle = detail.title.name,
            subtitleText = subtitleLabel,
            season = detail.selectedSeason,
            currentEpisode = episode,
            seasonEpisodes = detail.episodes,
            preferredAudioLang = audioPref,
            preferredSubtitleLang = subPref,
            isLoading = true
        )
        resolveCurrentStreamingSource()
    }

    fun retryStreaming() {
        val current = _streamingState.value ?: return
        _streamingState.value = current.copy(
            isLoading = true,
            streamSource = null,
            errorMessage = null
        )
        resolveCurrentStreamingSource()
    }

    fun playPreviousStreamingEpisode() {
        val current = _streamingState.value ?: return
        val curEp = current.currentEpisode ?: return
        val idx = current.seasonEpisodes.indexOfFirst { it.id == curEp.id }
        if (idx > 0) {
            val prevEp = current.seasonEpisodes[idx - 1]
            switchStreamingEpisode(current, prevEp)
        }
    }

    fun playNextStreamingEpisode() {
        val current = _streamingState.value ?: return
        val curEp = current.currentEpisode ?: return
        val idx = current.seasonEpisodes.indexOfFirst { it.id == curEp.id }
        if (idx in 0 until (current.seasonEpisodes.size - 1)) {
            val nextEp = current.seasonEpisodes[idx + 1]
            switchStreamingEpisode(current, nextEp)
        }
    }

    private fun switchStreamingEpisode(current: StreamingPlayerUiState, targetEp: EpisodeInfo) {
        val epTitle = targetEp.name?.takeIf { it.isNotBlank() } ?: "Episodio ${targetEp.number}"
        val season = current.season ?: 1
        val subtitleLabel = if (current.title.source == MediaSource.ANIME_UNITY) {
            "Ep. ${targetEp.number} • $epTitle"
        } else {
            "S%02dE%s • %s".format(season, targetEp.number.padStart(2, '0'), epTitle)
        }
        _streamingState.value = current.copy(
            subtitleText = subtitleLabel,
            currentEpisode = targetEp,
            isLoading = true,
            streamSource = null,
            errorMessage = null
        )
        resolveCurrentStreamingSource()
    }

    fun closeStreamingPlayer() {
        streamingResolveJob?.cancel()
        streamingResolveJob = null
        _streamingState.value = null
    }

    private fun resolveCurrentStreamingSource() {
        streamingResolveJob?.cancel()
        val state = _streamingState.value ?: return

        streamingResolveJob = viewModelScope.launch {
            try {
                val settings = settingsRepository.getSettings()
                val source = when {
                    state.title.source == MediaSource.ANIME_UNITY -> {
                        val ep = state.currentEpisode
                            ?: throw RuntimeException("Episodio anime non selezionato")
                        AnimeUnityClient.resolveAnimeEpisodeStream(
                            episodeId = ep.id,
                            host = settings.animeUnityDomain,
                            videoHostOverride = settings.videoHost
                        )
                    }

                    state.currentEpisode != null -> {
                        if (settings.domain.isBlank()) {
                            throw RuntimeException("Dominio StreamingCommunity non configurato nelle Impostazioni")
                        }
                        val tvId = state.title.numericId ?: state.title.id.toIntOrNull()
                            ?: throw RuntimeException("ID serie non valido")
                        val season = state.season ?: 1
                        val token = StreamingCommunityClient.getToken(tvId, settings.domain)
                        StreamingCommunityClient.resolveEpisodeStream(
                            tvId = tvId,
                            ep = state.currentEpisode,
                            tvName = state.title.name,
                            season = season,
                            domain = settings.domain,
                            token = token,
                            videoHostOverride = settings.videoHost
                        )
                    }

                    else -> {
                        if (settings.domain.isBlank()) {
                            throw RuntimeException("Dominio StreamingCommunity non configurato nelle Impostazioni")
                        }
                        val filmId = state.title.numericId ?: state.title.id.toIntOrNull()
                            ?: throw RuntimeException("ID film non valido")
                        StreamingCommunityClient.resolveFilmStream(
                            idFilm = filmId,
                            domain = settings.domain,
                            videoHostOverride = settings.videoHost
                        )
                    }
                }

                _streamingState.value = _streamingState.value?.copy(
                    isLoading = false,
                    streamSource = source,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _streamingState.value = _streamingState.value?.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Impossibile risolvere il flusso streaming"
                )
            }
        }
    }

    fun downloadFilmFromDetail() {
        val detail = _detailState.value ?: return
        viewModelScope.launch {
            val enrichedTitle = detail.title.copy(
                releaseDate = detail.metadata.year ?: detail.title.releaseDate
            )
            val jobId = downloadManager.enqueueFilm(
                title = enrichedTitle,
                audioLanguages = detail.selectedAudioLanguages.toList(),
                subtitleLanguages = detail.selectedSubtitleLanguages.toList()
            )
            if (jobId == -1L) {
                showMessage("«${detail.title.name}» è già nella lista download")
            } else {
                showMessage("«${detail.title.name}» aggiunto alla coda di download")
            }
        }
    }

    fun downloadEpisodeFromDetail(episode: EpisodeInfo) {
        val detail = _detailState.value ?: return
        viewModelScope.launch {
            val enrichedTitle = detail.title.copy(
                releaseDate = detail.metadata.year ?: detail.title.releaseDate
            )
            val jobId = downloadManager.enqueueEpisode(
                title = enrichedTitle,
                episode = episode,
                season = detail.selectedSeason,
                audioLanguages = detail.selectedAudioLanguages.toList(),
                subtitleLanguages = detail.selectedSubtitleLanguages.toList()
            )
            if (jobId == -1L) {
                showMessage("Episodio ${episode.number} è già nella lista download")
            } else {
                showMessage("Episodio ${episode.number} aggiunto alla coda di download")
            }
        }
    }

    fun downloadWholeSeasonFromDetail() {
        val detail = _detailState.value ?: return
        if (detail.episodes.isEmpty()) return
        viewModelScope.launch {
            val enrichedTitle = detail.title.copy(
                releaseDate = detail.metadata.year ?: detail.title.releaseDate
            )
            val totalCount = detail.episodes.size
            val addedIds = downloadManager.enqueueBatchEpisodes(
                title = enrichedTitle,
                episodes = detail.episodes,
                season = detail.selectedSeason,
                audioLanguages = detail.selectedAudioLanguages.toList(),
                subtitleLanguages = detail.selectedSubtitleLanguages.toList()
            )
            val addedCount = addedIds.size
            val skippedCount = totalCount - addedCount
            when {
                addedCount == 0 -> {
                    showMessage("Tutti gli episodi sono già nella lista download")
                }
                skippedCount > 0 -> {
                    showMessage("$addedCount episodi aggiunti alla coda ($skippedCount già presenti saltati)")
                }
                else -> {
                    showMessage("$addedCount episodi aggiunti alla coda di download")
                }
            }
        }
    }

    fun cancelDownload(jobId: Long) {
        downloadManager.cancelJob(jobId)
    }

    fun retryDownload(jobId: Long) {
        viewModelScope.launch {
            downloadManager.retryJob(jobId)
        }
    }

    fun removeDownload(jobId: Long) {
        viewModelScope.launch {
            downloadManager.removeJob(jobId)
        }
    }

    fun clearFinishedDownloads() {
        viewModelScope.launch {
            downloadManager.clearFinished()
        }
    }

    fun followSeriesFromDetail() {
        val detail = _detailState.value ?: return
        viewModelScope.launch {
            val enrichedTitle = detail.title.copy(
                releaseDate = detail.metadata.year ?: detail.title.releaseDate
            )
            watchlistManager.followTitle(
                title = enrichedTitle,
                audioLanguages = detail.selectedAudioLanguages.toList(),
                subtitleLanguages = detail.selectedSubtitleLanguages.toList(),
                autoDownload = true
            )
            showMessage("Ora segui «${detail.title.name}»")
        }
    }

    fun unfollowWatch(watchId: Long) {
        viewModelScope.launch {
            watchlistManager.unfollow(watchId)
            showMessage("Serie rimossa dalle seguite")
        }
    }

    fun toggleWatchAutoDownload(watch: WatchEntity, enabled: Boolean) {
        viewModelScope.launch {
            watchlistManager.toggleAutoDownload(watch, enabled)
        }
    }

    fun checkAllWatchesNow() {
        if (_isCheckingWatches.value) return
        viewModelScope.launch {
            _isCheckingWatches.value = true
            try {
                val found = watchlistManager.checkAllWatches()
                if (found > 0) {
                    showMessage("Trovati $found nuovi episodi!")
                } else {
                    showMessage("Nessun nuovo episodio trovato")
                }
            } catch (e: Exception) {
                showMessage("Errore controllo serie: ${e.message}")
            } finally {
                _isCheckingWatches.value = false
            }
        }
    }

    fun refreshLocalLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootDir = downloadManager.getMediaRootDir()
            if (!rootDir.exists()) {
                _localFiles.value = emptyList()
                return@launch
            }
            val files = rootDir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                            !file.name.startsWith(".") &&
                            file.extension.lowercase() in listOf("mp4", "mkv", "ts")
                }
                .map { file ->
                    val rel = file.relativeTo(rootDir).path
                    val parts = rel.split(File.separatorChar)
                    val category = parts.firstOrNull() ?: "Media"
                    val folder = if (parts.size > 2) parts.drop(1).dropLast(1).joinToString("/") else ""
                    LocalMediaFile(
                        file = file,
                        name = file.nameWithoutExtension,
                        category = category,
                        relativeFolder = folder,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified(),
                        extension = file.extension.uppercase()
                    )
                }
                .sortedByDescending { it.lastModifiedMs }
                .toList()
            _localFiles.value = files
        }
    }

    fun deleteLocalFile(item: LocalMediaFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val parent = item.file.parentFile
            item.file.delete()
            if (parent != null && parent.list()?.isEmpty() == true) {
                parent.delete()
            }
            refreshLocalLibrary()
            showMessage("File eliminato")
        }
    }

    fun checkDomainRecoveryNow() {
        if (_isCheckingDomain.value) return
        viewModelScope.launch {
            _isCheckingDomain.value = true
            try {
                val settings = settingsRepository.getSettings()
                val res = DomainRecoveryManager.runCheck(
                    currentDomain = settings.domain,
                    autoApply = settings.domainAutoApply,
                    force = true,
                    onSaveDomain = { settingsRepository.updateDomain(it) }
                )
                when {
                    res.applied && res.candidate != null -> {
                        showMessage("Nuovo dominio applicato: ${res.candidate}")
                        loadHomeShelves()
                    }
                    res.candidate != null -> {
                        showMessage("Trovato nuovo dominio candidato: ${res.candidate}")
                    }
                    res.currentOk -> {
                        showMessage("Il dominio attuale (${res.current}) funziona regolarmente")
                    }
                    else -> {
                        showMessage("Nessun nuovo dominio valido trovato")
                    }
                }
            } catch (e: Exception) {
                showMessage("Errore ricerca dominio: ${e.message}")
            } finally {
                _isCheckingDomain.value = false
            }
        }
    }

    fun applyPendingDomainCandidate(candidateHost: String) {
        viewModelScope.launch {
            try {
                DomainRecoveryManager.verifyAndApplyCandidate(candidateHost) { newHost ->
                    settingsRepository.updateDomain(newHost)
                }
                showMessage("Dominio aggiornato a $candidateHost")
                loadHomeShelves()
            } catch (e: Exception) {
                showMessage("Impossibile applicare il dominio: ${e.message}")
            }
        }
    }

    fun dismissPendingDomainCandidate() {
        DomainRecoveryManager.clearPending()
    }

    fun saveAndVerifyStreamingCommunityDomain(rawHost: String) {
        viewModelScope.launch {
            try {
                val host = VixcloudParser.normalizeHost(rawHost)
                val version = withContext(Dispatchers.IO) {
                    StreamingCommunityClient.getDomainVersion(host)
                }
                if (version == null) {
                    showMessage("«$host» non risponde come StreamingCommunity")
                    return@launch
                }
                settingsRepository.updateDomain(host)
                StreamingCommunityClient.clearCaches()
                DomainRecoveryManager.clearPending()
                showMessage("Dominio StreamingCommunity salvato ($host)")
                loadHomeShelves()
            } catch (e: Exception) {
                showMessage(e.message ?: "Dominio non valido")
            }
        }
    }

    fun saveAndVerifyAnimeUnityDomain(rawHost: String) {
        viewModelScope.launch {
            try {
                val host = VixcloudParser.normalizeHost(rawHost)
                AnimeUnityClient.verifyAnimeUnity(host)
                settingsRepository.updateAnimeUnityDomain(host)
                AnimeUnityClient.forgetCsrf()
                showMessage("Dominio AnimeUnity salvato ($host)")
                if (_selectedSource.value == MediaSource.ANIME_UNITY) {
                    loadHomeShelves()
                }
            } catch (e: Exception) {
                showMessage(e.message ?: "Host AnimeUnity non valido")
            }
        }
    }

    fun saveAndVerifyVideoHost(rawHost: String) {
        viewModelScope.launch {
            try {
                val trimmed = rawHost.trim()
                if (trimmed.isEmpty()) {
                    settingsRepository.updateVideoHost("")
                    showMessage("Override video host rimosso (automatico da embed)")
                    return@launch
                }
                val host = VixcloudParser.normalizeHost(trimmed)
                withContext(Dispatchers.IO) {
                    VixcloudParser.verifyVideoHost(host)
                }
                settingsRepository.updateVideoHost(host)
                showMessage("Video host salvato ($host)")
            } catch (e: Exception) {
                showMessage(e.message ?: "Video host non valido")
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                settingsRepository.updateSettings(transform)
                val s = settingsRepository.getSettings()
                BackgroundWorkScheduler.scheduleAll(
                    context = app,
                    watchIntervalMinutes = s.seriesWatchIntervalMinutes,
                    domainCheckIntervalMinutes = s.domainCheckIntervalMinutes
                )
                showMessage("Impostazioni salvate")
            } catch (e: Exception) {
                showMessage(e.message ?: "Errore salvataggio impostazioni")
            }
        }
    }

    fun saveNamingTemplate(slot: String, template: String) {
        viewModelScope.launch {
            try {
                val valid = NamingEngine.validate(slot, template)
                settingsRepository.updateSettings { current ->
                    current.copy(namingTemplates = current.namingTemplates + (slot to valid))
                }
                showMessage("Modello '$slot' aggiornato")
            } catch (e: Exception) {
                showMessage(e.message ?: "Modello non valido")
            }
        }
    }

    fun saveGithubToken(rawToken: String) {
        viewModelScope.launch {
            settingsRepository.updateGithubToken(rawToken)
            showMessage(
                if (rawToken.trim().isEmpty()) {
                    "Token GitHub personalizzato rimosso"
                } else {
                    "Token GitHub salvato"
                }
            )
            checkAppUpdate(manual = true)
        }
    }

    fun checkAppUpdate(manual: Boolean = true) {
        if (_isCheckingAppUpdate.value) return
        viewModelScope.launch {
            _isCheckingAppUpdate.value = true
            try {
                val currentVer = BuildConfig.VERSION_NAME
                val userToken = settingsRepository.getSettings().githubToken
                when (val outcome = AppUpdateManager.checkForUpdates(currentVer, userToken)) {
                    is UpdateCheckOutcome.UpdateAvailable -> {
                        val prevTag = _availableUpdate.value?.tagName
                        _availableUpdate.value = outcome.info
                        if (prevTag != outcome.info.tagName) {
                            _downloadedUpdateFile.value = null
                            _updateDownloadProgress.value = 0
                        }
                        _showUpdateDialog.value = true
                    }
                    is UpdateCheckOutcome.UpToDate -> {
                        if (manual) {
                            showMessage("Hai già l'ultima versione installata (v$currentVer)")
                        }
                    }
                    is UpdateCheckOutcome.NoReleasesYet -> {
                        if (manual) {
                            showMessage("Nessuna release pubblicata su GitHub (versione attuale v$currentVer)")
                        }
                    }
                    is UpdateCheckOutcome.Error -> {
                        if (manual) {
                            showMessage("Errore controllo aggiornamenti: ${outcome.message}")
                        }
                    }
                }
            } finally {
                _isCheckingAppUpdate.value = false
            }
        }
    }

    fun downloadAppUpdate(onReadyToInstall: (File) -> Unit) {
        val info = _availableUpdate.value ?: return
        if (_isDownloadingUpdate.value) return

        val existingFile = _downloadedUpdateFile.value
        if (existingFile != null && existingFile.exists() && existingFile.length() > 0L) {
            onReadyToInstall(existingFile)
            return
        }

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _updateDownloadProgress.value = 0
            try {
                val userToken = settingsRepository.getSettings().githubToken
                val apkFile = AppUpdateManager.downloadApk(
                    updateInfo = info,
                    cacheDir = app.cacheDir,
                    userGithubToken = userToken,
                    onProgress = { pct -> _updateDownloadProgress.value = pct }
                )
                _downloadedUpdateFile.value = apkFile
                onReadyToInstall(apkFile)
            } catch (e: Exception) {
                showMessage(e.message ?: "Errore durante il download dell'aggiornamento")
            } finally {
                _isDownloadingUpdate.value = false
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun openUpdateDialog() {
        if (_availableUpdate.value != null) {
            _showUpdateDialog.value = true
        }
    }
}
