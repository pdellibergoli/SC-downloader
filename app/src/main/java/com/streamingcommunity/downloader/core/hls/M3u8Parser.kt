package com.streamingcommunity.downloader.core.hls

import com.streamingcommunity.downloader.core.model.AvailableTracks
import java.net.URI

data class VariantPlaylist(
    val uri: String,
    val bandwidth: Long,
    val resolution: String? = null
)

data class EncryptionKeyInfo(
    val method: String,
    val uri: String? = null,
    val iv: String? = null
)

data class MediaRendition(
    val type: String, // "AUDIO" or "SUBTITLES"
    val name: String? = null,
    val isDefault: Boolean = false,
    val language: String? = null,
    val uri: String? = null
)

class M3u8Parser {
    val segments = mutableListOf<String>()
    val videoPlaylists = mutableListOf<VariantPlaylist>()
    var keyInfo: EncryptionKeyInfo? = null
        private set
    val subtitlePlaylists = mutableListOf<MediaRendition>()
    val subtitleSegments = mutableListOf<String>()
    val audioTracks = mutableListOf<MediaRendition>()

    companion object {
        private val ATTR_REGEX = Regex("""([A-Z0-9-]+)=("([^"]*)"|([^,]*))""")

        val AUDIO_LANG_ALIASES: Map<String, Set<String>> = mapOf(
            "ita" to setOf("ita", "it", "Italian", "Italiano"),
            "eng" to setOf("eng", "en", "English"),
            "fra" to setOf("fra", "fr", "French", "Français"),
            "spa" to setOf("spa", "es", "Spanish", "Español"),
            "deu" to setOf("deu", "de", "German", "Deutsch"),
            "por" to setOf("por", "pt", "Portuguese", "Português"),
            "jpn" to setOf("jpn", "ja", "Japanese", "日本語")
        )

        val LANG_SHORT_MAP: Map<String, String> = mapOf(
            "ita" to "it", "eng" to "en", "fra" to "fr", "spa" to "es", "deu" to "de",
            "por" to "pt", "jpn" to "ja", "zho" to "zh", "ara" to "ar", "rus" to "ru",
            "kor" to "ko", "nld" to "nl", "pol" to "pl", "swe" to "sv", "nor" to "no",
            "dan" to "da", "fin" to "fi", "tur" to "tr", "ell" to "el", "ces" to "cs"
        )

        fun resolveUrl(baseUrl: String?, relativeOrAbsolute: String): String {
            val trimmed = relativeOrAbsolute.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || baseUrl.isNullOrBlank()) {
                return trimmed
            }
            return try {
                URI(baseUrl).resolve(trimmed).toString()
            } catch (e: Exception) {
                trimmed
            }
        }

        fun parseAttributes(attributeList: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (match in ATTR_REGEX.findAll(attributeList)) {
                val key = match.groupValues[1]
                val quoted = match.groupValues[3]
                val unquoted = match.groupValues[4]
                result[key] = if (match.groupValues[2].startsWith("\"")) quoted else unquoted
            }
            return result
        }
    }

    fun parseData(m3u8Content: String, baseUrl: String? = null) {
        segments.clear()
        videoPlaylists.clear()
        keyInfo = null
        subtitlePlaylists.clear()
        subtitleSegments.clear()
        audioTracks.clear()

        val lines = m3u8Content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter("#EXT-X-STREAM-INF:"))
                    val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
                        ?: attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                        ?: 0L
                    val resolution = attrs["RESOLUTION"]
                    if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                        val uri = resolveUrl(baseUrl, lines[i + 1])
                        videoPlaylists.add(VariantPlaylist(uri = uri, bandwidth = bandwidth, resolution = resolution))
                        i += 2
                        continue
                    }
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter("#EXT-X-KEY:"))
                    val method = attrs["METHOD"] ?: "NONE"
                    val uri = attrs["URI"]?.let { resolveUrl(baseUrl, it) }
                    val iv = attrs["IV"]
                    keyInfo = EncryptionKeyInfo(method = method, uri = uri, iv = iv)
                }

                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = parseAttributes(line.substringAfter("#EXT-X-MEDIA:"))
                    val type = attrs["TYPE"].orEmpty().uppercase()
                    val name = attrs["NAME"]
                    val isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true)
                    val language = attrs["LANGUAGE"]
                    val uri = attrs["URI"]?.let { resolveUrl(baseUrl, it) }

                    val rendition = MediaRendition(
                        type = type,
                        name = name,
                        isDefault = isDefault,
                        language = language,
                        uri = uri
                    )
                    if (type == "SUBTITLES") {
                        subtitlePlaylists.add(rendition)
                    } else if (type == "AUDIO") {
                        audioTracks.add(rendition)
                    }
                }

                !line.startsWith("#") -> {
                    val resolved = resolveUrl(baseUrl, line)
                    if (resolved.contains(".vtt", ignoreCase = true) || line.contains("vtt", ignoreCase = true)) {
                        subtitleSegments.add(resolved)
                    } else {
                        segments.add(resolved)
                    }
                }
            }
            i++
        }
    }

    fun getBestQuality(): String? {
        return videoPlaylists.maxByOrNull { it.bandwidth }?.uri
    }

    fun availableLanguages(): AvailableTracks {
        val audio = audioTracks.mapNotNull { it.language?.takeIf { l -> l.isNotBlank() } }.distinct()
        val subtitles = subtitlePlaylists
            .mapNotNull { it.language?.takeIf { l -> l.isNotBlank() && l != "auto" } }
            .distinct()
        return AvailableTracks(audio = audio, subtitles = subtitles)
    }

    fun getAudioTrackUrl(langCode: String): String? {
        val aliases = AUDIO_LANG_ALIASES[langCode.lowercase()] ?: setOf(langCode)
        for (track in audioTracks) {
            val trackLang = track.language.orEmpty()
            val trackName = track.name.orEmpty()
            if (aliases.any { it.equals(trackLang, ignoreCase = true) || it.equals(trackName, ignoreCase = true) }) {
                return track.uri
            }
        }
        return null
    }
}
