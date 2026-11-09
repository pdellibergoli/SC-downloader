package com.streamingcommunity.downloader.core.network

import com.streamingcommunity.downloader.core.hls.AesSegmentDecryptor
import com.streamingcommunity.downloader.core.hls.M3u8Downloader
import com.streamingcommunity.downloader.core.hls.M3u8Parser
import com.streamingcommunity.downloader.core.model.AudioTrackSource
import com.streamingcommunity.downloader.core.model.AvailableTracks
import com.streamingcommunity.downloader.core.model.MissingAudioTrackException
import com.streamingcommunity.downloader.core.model.SubtitleTrackSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

data class ParsedVixcloudVideo(
    val id: String,
    val host: String,
    val canPlayFhd: Boolean,
    val scz: Boolean,
    val lang: String
)

data class ParsedVixcloudParams(
    val token: String,
    val expires: String
)

object VixcloudParser {

    private val VIDEO_ID_REGEX = Regex("""window\.video\s*=\s*\{[^}]*?\bid\s*:\s*['"]?(\d+)['"]?""", RegexOption.DOT_MATCHES_ALL)
    private val PARAMS_REGEX = Regex("""params\s*:\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
    private val HOSTNAME_REGEX = Regex("""^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$""")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun normalizeHost(value: String): String {
        var raw = value.trim().lowercase()
        if ("://" in raw) {
            val uri = try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("Inserisci solo il nome host, ad esempio www.animeunity.co")
            }
            val scheme = uri.scheme?.lowercase()
            val path = uri.path.orEmpty()
            if (scheme !in listOf("http", "https") ||
                path !in listOf("", "/") ||
                !uri.query.isNullOrEmpty() ||
                uri.port != -1
            ) {
                throw IllegalArgumentException("Inserisci solo il nome host, ad esempio www.animeunity.co")
            }
            raw = uri.host.orEmpty()
        }
        raw = raw.trimEnd('/')
        if (!HOSTNAME_REGEX.matches(raw)) {
            throw IllegalArgumentException("Host non valido: ${value.trim().take(100)}")
        }
        return raw
    }

    fun embedHost(urlEmbed: String): String {
        val uri = try {
            URI(urlEmbed)
        } catch (e: Exception) {
            throw RuntimeException("Unexpected embed URL: ${urlEmbed.take(100)}")
        }
        val host = uri.host?.lowercase()
        if (uri.scheme?.lowercase() != "https" || host.isNullOrBlank()) {
            throw RuntimeException("Unexpected embed URL: ${urlEmbed.take(100)}")
        }
        return host
    }

    fun applyVideoHost(urlEmbed: String, videoHostOverride: String): String {
        val override = videoHostOverride.trim()
        if (override.isEmpty()) return urlEmbed
        return try {
            val uri = URI(urlEmbed)
            if (uri.host.isNullOrBlank()) return urlEmbed
            URI("https", uri.userInfo, override, -1, uri.path, uri.query, uri.fragment).toString()
        } catch (e: Exception) {
            urlEmbed
        }
    }

    fun embedReferer(video: ParsedVixcloudVideo, params: ParsedVixcloudParams): String {
        return "https://${video.host}/embed/${video.id}?token=${params.token}&expires=${params.expires}"
    }

    fun fetchVixcloudEmbedScript(urlEmbed: String, referer: String? = null): String {
        val headers = mutableMapOf(
            "User-Agent" to HttpClientProvider.randomUserAgent(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        if (!referer.isNullOrBlank()) {
            headers["Referer"] = referer
        }

        HttpClientProvider.executeGet(urlEmbed, headers).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Impossibile caricare embed video: HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(html)
            val body = doc.body()
                ?: throw RuntimeException("Empty embed page body from the video host")
            val script = body.selectFirst("script")
                ?: throw RuntimeException("Video non disponibile (nessun tag script nell'embed)")
            return script.data().ifEmpty { script.html() }
        }
    }

    fun parseEmbedContent(embedContent: String, urlEmbed: String): Pair<ParsedVixcloudVideo, ParsedVixcloudParams> {
        val idMatch = VIDEO_ID_REGEX.find(embedContent)
            ?: throw RuntimeException("Cannot find video ID in embed. Snippet: ${embedContent.take(300)}")
        val videoId = idMatch.groupValues[1]
        val host = embedHost(urlEmbed)

        val queryMap = parseQueryParams(URI(urlEmbed).rawQuery)
        val canPlayFhd = queryMap.containsKey("canPlayFHD") && !queryMap["canPlayFHD"].isNullOrEmpty()
        val scz = queryMap.containsKey("scz") && !queryMap["scz"].isNullOrEmpty()
        val lang = queryMap["lang"]?.firstOrNull() ?: "it"

        val paramsMatch = PARAMS_REGEX.find(embedContent)
            ?: throw RuntimeException("Cannot find params in embed. Snippet: ${embedContent.take(300)}")
        val paramsRaw = paramsMatch.groupValues[1]
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        val jsonWinParam = ("{$paramsRaw}")
            .replace(",}", "}")
            .replace("'", "\"")

        val parsedObj = json.parseToJsonElement(jsonWinParam).jsonObject
        val token = parsedObj["token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing token in embed params")
        val expires = parsedObj["expires"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing expires in embed params")

        return Pair(
            ParsedVixcloudVideo(
                id = videoId,
                host = host,
                canPlayFhd = canPlayFhd,
                scz = scz,
                lang = lang
            ),
            ParsedVixcloudParams(
                token = token,
                expires = expires
            )
        )
    }

    fun buildM3u8Url(
        video: ParsedVixcloudVideo,
        params: ParsedVixcloudParams,
        addB1: Boolean = false
    ): String {
        val sb = StringBuilder("https://${video.host}/playlist/${video.id}?")
        if (addB1) sb.append("b=1&")
        sb.append("token=${params.token}&expires=${params.expires}")
        if (video.canPlayFhd) sb.append("&h=1")
        if (video.scz) sb.append("&scz=1")
        sb.append("&lang=${video.lang.ifBlank { "it" }}")
        return sb.toString()
    }

    suspend fun fetchM3u8KeyHex(
        video: ParsedVixcloudVideo,
        referer: String
    ): String {
        val url = "https://${video.host}/storage/enc.key"
        val headers = mapOf(
            "User-Agent" to HttpClientProvider.randomUserAgent(),
            "Referer" to referer
        )
        val response = HttpClientProvider.withRetry("enc.key") {
            HttpClientProvider.executeGet(url, headers)
        }
        response.use { resp ->
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes()
                    ?: throw RuntimeException("Empty encryption key response")
                return AesSegmentDecryptor.bytesToHex(bytes)
            }
            throw RuntimeException("Cannot fetch encryption key: HTTP ${resp.code}")
        }
    }

    fun fetchMasterLanguages(m3u8Url: String, referer: String, explicitLang: String? = null): AvailableTracks {
        val (content, effectiveUrl) = M3u8Downloader.fetchTextWithB1Fallback(m3u8Url, referer)
        val parser = M3u8Parser().apply { parseData(content, effectiveUrl) }
        val tracks = parser.availableLanguages()
        return tracks.copy(defaultLang = explicitLang)
    }

    fun collectAudioTracks(
        m3u8Url: String,
        referer: String,
        audioLanguages: List<String>,
        strict: Boolean = false
    ): List<AudioTrackSource> {
        val tracks = mutableListOf<AudioTrackSource>()
        try {
            val (content, effectiveUrl) = M3u8Downloader.fetchTextWithB1Fallback(m3u8Url, referer)
            val parser = M3u8Parser().apply { parseData(content, effectiveUrl) }

            if (parser.audioTracks.isEmpty()) {
                return tracks
            }

            for (lang in audioLanguages) {
                val url = parser.getAudioTrackUrl(lang)
                if (url != null) {
                    tracks.add(AudioTrackSource(url = url, language = lang))
                    continue
                }
                val available = parser.audioTracks.mapNotNull { it.language ?: it.name }
                if (strict) {
                    throw MissingAudioTrackException(lang, available)
                }
            }
        } catch (e: MissingAudioTrackException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
        }
        return tracks
    }

    fun collectSubtitleTracks(
        m3u8Url: String,
        referer: String,
        subtitleLanguages: List<String>
    ): List<SubtitleTrackSource> {
        val tracks = mutableListOf<SubtitleTrackSource>()
        try {
            val (content, effectiveUrl) = M3u8Downloader.fetchTextWithB1Fallback(m3u8Url, referer)
            val parser = M3u8Parser().apply { parseData(content, effectiveUrl) }
            for (lang in subtitleLanguages) {
                val match = parser.subtitlePlaylists.firstOrNull { it.language == lang }
                val uri = match?.uri
                if (!uri.isNullOrBlank()) {
                    tracks.add(SubtitleTrackSource(uri = uri, language = lang))
                }
            }
        } catch (_: Exception) {
        }
        return tracks
    }

    fun verifyVideoHost(host: String) {
        val url = "https://$host/storage/enc.key"
        val headers = mapOf("User-Agent" to HttpClientProvider.randomUserAgent())
        try {
            HttpClientProvider.executeGet(url, headers).use { resp ->
                val bytes = resp.body?.bytes()
                if (!resp.isSuccessful || bytes == null || bytes.size != 16) {
                    throw RuntimeException("$host non serve la chiave video (HTTP ${resp.code})")
                }
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("$host non raggiungibile: ${e.message}")
        }
    }

    fun parseQueryParams(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, MutableList<String>>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val parts = pair.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }
}
