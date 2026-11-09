package com.streamingcommunity.downloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamingcommunity.downloader.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sc_downloader_settings")

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_DOMAIN = stringPreferencesKey("domain")
        private val KEY_ANIMEUNITY_DOMAIN = stringPreferencesKey("animeunity_domain")
        private val KEY_VIDEO_HOST = stringPreferencesKey("video_host")
        private val KEY_MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        private val KEY_MAX_SEGMENT_WORKERS = intPreferencesKey("max_segment_workers")
        private val KEY_WATCH_INTERVAL_MIN = intPreferencesKey("series_watch_interval_minutes")
        private val KEY_DOMAIN_AUTO_CHECK = booleanPreferencesKey("domain_auto_check_enabled")
        private val KEY_DOMAIN_AUTO_APPLY = booleanPreferencesKey("domain_auto_apply")
        private val KEY_DOMAIN_CHECK_INTERVAL_MIN = intPreferencesKey("domain_check_interval_minutes")
        private val KEY_AUTO_CHECK_APP_UPDATES = booleanPreferencesKey("auto_check_app_updates")
        private val KEY_OUTPUT_CONTAINER = stringPreferencesKey("output_container")
        private val KEY_SUBTITLE_MODE = stringPreferencesKey("subtitle_mode")
        private val KEY_NAMING_TEMPLATES = stringPreferencesKey("naming_templates_json")
    }

    @Volatile
    var currentDomainSnapshot: String = ""
        private set

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val domain = prefs[KEY_DOMAIN]?.trim().orEmpty()
        currentDomainSnapshot = domain

        val storedTemplates = prefs[KEY_NAMING_TEMPLATES]?.let {
            try {
                json.decodeFromString<Map<String, String>>(it)
            } catch (_: Exception) {
                null
            }
        }.orEmpty()

        AppSettings(
            domain = domain,
            animeUnityDomain = prefs[KEY_ANIMEUNITY_DOMAIN]?.trim()?.ifBlank { "www.animeunity.co" }
                ?: "www.animeunity.co",
            videoHost = prefs[KEY_VIDEO_HOST]?.trim().orEmpty(),
            maxConcurrentDownloads = (prefs[KEY_MAX_CONCURRENT_DOWNLOADS] ?: 2).coerceIn(1, 5),
            maxSegmentWorkers = (prefs[KEY_MAX_SEGMENT_WORKERS] ?: 12).coerceIn(2, 32),
            seriesWatchIntervalMinutes = (prefs[KEY_WATCH_INTERVAL_MIN] ?: 240).coerceAtLeast(15),
            domainAutoCheckEnabled = prefs[KEY_DOMAIN_AUTO_CHECK] ?: true,
            domainAutoApply = prefs[KEY_DOMAIN_AUTO_APPLY] ?: false,
            domainCheckIntervalMinutes = (prefs[KEY_DOMAIN_CHECK_INTERVAL_MIN] ?: 360).coerceAtLeast(30),
            autoCheckAppUpdates = prefs[KEY_AUTO_CHECK_APP_UPDATES] ?: true,
            outputContainer = prefs[KEY_OUTPUT_CONTAINER]?.lowercase()
                ?.takeIf { it in listOf("mp4", "mkv") } ?: "mp4",
            subtitleMode = prefs[KEY_SUBTITLE_MODE]?.lowercase()
                ?.takeIf { it in listOf("embed", "external") } ?: "embed",
            namingTemplates = AppSettings.DEFAULT_NAMING_TEMPLATES + storedTemplates
        )
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun updateDomain(domain: String) {
        val clean = domain.trim()
        currentDomainSnapshot = clean
        context.dataStore.edit { prefs ->
            prefs[KEY_DOMAIN] = clean
        }
    }

    suspend fun updateAnimeUnityDomain(domain: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANIMEUNITY_DOMAIN] = domain.trim().ifEmpty { "www.animeunity.co" }
        }
    }

    suspend fun updateVideoHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIDEO_HOST] = host.trim()
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = getSettings()
        val updated = transform(current)
        currentDomainSnapshot = updated.domain
        context.dataStore.edit { prefs ->
            prefs[KEY_DOMAIN] = updated.domain.trim()
            prefs[KEY_ANIMEUNITY_DOMAIN] = updated.animeUnityDomain.trim().ifEmpty { "www.animeunity.co" }
            prefs[KEY_VIDEO_HOST] = updated.videoHost.trim()
            prefs[KEY_MAX_CONCURRENT_DOWNLOADS] = updated.maxConcurrentDownloads.coerceIn(1, 5)
            prefs[KEY_MAX_SEGMENT_WORKERS] = updated.maxSegmentWorkers.coerceIn(2, 32)
            prefs[KEY_WATCH_INTERVAL_MIN] = updated.seriesWatchIntervalMinutes.coerceAtLeast(15)
            prefs[KEY_DOMAIN_AUTO_CHECK] = updated.domainAutoCheckEnabled
            prefs[KEY_DOMAIN_AUTO_APPLY] = updated.domainAutoApply
            prefs[KEY_DOMAIN_CHECK_INTERVAL_MIN] = updated.domainCheckIntervalMinutes.coerceAtLeast(30)
            prefs[KEY_AUTO_CHECK_APP_UPDATES] = updated.autoCheckAppUpdates
            prefs[KEY_OUTPUT_CONTAINER] = updated.outputContainer
            prefs[KEY_SUBTITLE_MODE] = updated.subtitleMode
            prefs[KEY_NAMING_TEMPLATES] = json.encodeToString(updated.namingTemplates)
        }
    }
}
