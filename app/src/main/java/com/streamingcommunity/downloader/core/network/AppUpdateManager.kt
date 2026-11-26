package com.streamingcommunity.downloader.core.network

import com.streamingcommunity.downloader.core.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

sealed class UpdateCheckOutcome {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckOutcome()
    data class UpToDate(val currentVersion: String) : UpdateCheckOutcome()
    data class NoReleasesYet(val currentVersion: String) : UpdateCheckOutcome()
    data class Error(val message: String) : UpdateCheckOutcome()
}

object AppUpdateManager {

    const val GITHUB_OWNER = "pdellibergoli"
    const val GITHUB_REPO = "SC-Cinema"
    const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    fun resolveEffectiveToken(userToken: String = ""): String {
        return userToken.trim()
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
            .trim()
    }

    /**
     * Returns true if [candidateVersion] is strictly newer than [currentVersion].
     * Supports semantic versions such as "1.0.0", "v1.2.1", "1.2.0-beta1".
     */
    fun isNewerVersion(currentVersion: String, candidateVersion: String): Boolean {
        val currentClean = normalizeVersion(currentVersion)
        val candidateClean = normalizeVersion(candidateVersion)
        if (currentClean.isEmpty() || candidateClean.isEmpty()) return false
        if (currentClean == candidateClean) return false

        val currentMain = currentClean.substringBefore("-")
        val candidateMain = candidateClean.substringBefore("-")

        val currentParts = currentMain.split(".").map { it.toIntOrNull() ?: 0 }
        val candidateParts = candidateMain.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(currentParts.size, candidateParts.size)

        for (i in 0 until maxLen) {
            val cur = currentParts.getOrElse(i) { 0 }
            val cand = candidateParts.getOrElse(i) { 0 }
            if (cand > cur) return true
            if (cand < cur) return false
        }

        // Same numeric version: stable release (no pre-release suffix) is newer than pre-release
        val currentPre = currentClean.substringAfter("-", "")
        val candidatePre = candidateClean.substringAfter("-", "")
        if (currentPre.isNotEmpty() && candidatePre.isEmpty()) return true
        if (currentPre.isEmpty() && candidatePre.isNotEmpty()) return false
        return candidatePre > currentPre
    }

    fun parseReleaseJson(rawJson: String): AppUpdateInfo? {
        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return null
        return parseReleaseObject(obj)
    }

    private fun parseReleaseObject(obj: JsonObject): AppUpdateInfo? {
        val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (tagName.isEmpty()) return null

        val versionName = normalizeVersion(tagName)
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Versione $versionName"
        val body = obj["body"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Nuovo aggiornamento disponibile su GitHub Releases."
        val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: RELEASES_PAGE_URL
        val publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull

        val assets = (obj["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val apkAsset = assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.endsWith(".apk", ignoreCase = true)
        }

        // Prefer the GitHub API asset endpoint (`url`) so private repository downloads work
        // with `Accept: application/octet-stream` + `Authorization: Bearer <token>`,
        // falling back to `browser_download_url` if `url` is absent.
        val apiAssetUrl = apkAsset?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val browserDownloadUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val apkDownloadUrl = apiAssetUrl ?: browserDownloadUrl
        val apkFileName = apkAsset?.get("name")?.jsonPrimitive?.contentOrNull
        val apkSizeBytes = apkAsset?.get("size")?.jsonPrimitive?.longOrNull ?: 0L

        return AppUpdateInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            releaseNotes = body,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkDownloadUrl,
            apkFileName = apkFileName,
            apkSizeBytes = apkSizeBytes,
            publishedAt = publishedAt
        )
    }

    suspend fun checkForUpdates(
        currentVersion: String,
        userGithubToken: String = ""
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        try {
            val token = resolveEffectiveToken(userGithubToken)
            val reqBuilder = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SC-Downloader-Android/$currentVersion")
            if (token.isNotEmpty()) {
                reqBuilder.header("Authorization", "Bearer $token")
            }

            HttpClientProvider.sharedClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 404) {
                    return@withContext UpdateCheckOutcome.NoReleasesYet(currentVersion)
                }
                if (response.code == 401 || response.code == 403) {
                    return@withContext UpdateCheckOutcome.Error("Token GitHub non valido o scaduto (HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckOutcome.Error("GitHub API HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val releaseInfo = parseReleaseJson(body)
                    ?: return@withContext UpdateCheckOutcome.Error("Risposta release non valida")

                if (isNewerVersion(currentVersion, releaseInfo.versionName)) {
                    UpdateCheckOutcome.UpdateAvailable(releaseInfo)
                } else {
                    UpdateCheckOutcome.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Error(e.message ?: "Errore di connessione a GitHub")
        }
    }

    suspend fun downloadApk(
        updateInfo: AppUpdateInfo,
        cacheDir: File,
        userGithubToken: String = "",
        onProgress: (percent: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val downloadUrl = updateInfo.apkDownloadUrl
            ?: throw IOException("Nessun file APK allegato alla release ${updateInfo.tagName}")

        val token = resolveEffectiveToken(userGithubToken)
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val safeName = (updateInfo.apkFileName ?: "SC-Downloader-${updateInfo.versionName}.apk")
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val targetFile = File(updatesDir, safeName)
        val tempFile = File(updatesDir, "$safeName.part")

        // Clean old partial files
        updatesDir.listFiles()?.forEach { file ->
            if (file.name != safeName) {
                runCatching { file.delete() }
            }
        }

        val reqBuilder = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "SC-Downloader-Android")
        if (token.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        HttpClientProvider.sharedClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download APK fallito (HTTP ${response.code})")
            }
            val body = response.body ?: throw IOException("Risposta vuota durante il download dell'APK")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: updateInfo.apkSizeBytes

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var lastReportedPercent = -1

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        onProgress(100)
        targetFile
    }
}
