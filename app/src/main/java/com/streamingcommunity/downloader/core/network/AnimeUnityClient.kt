package com.streamingcommunity.downloader.core.network

import com.streamingcommunity.downloader.core.model.AvailableTracks
import com.streamingcommunity.downloader.core.model.EpisodeInfo
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.model.MediaTitle
import com.streamingcommunity.downloader.core.model.Shelf
import com.streamingcommunity.downloader.core.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

object AnimeUnityClient {

    const val DEFAULT_HOST = "www.animeunity.co"
    private const val BATCH_SIZE = 120
    private const val ARCHIVE_PAGE = 30
    private const val MAX_RESULTS = 60
    private const val SHELF_CAP = 30
    private const val CSRF_TTL_MS = 10 * 60 * 1000L
    private const val SHELF_TTL_MS = 15 * 60 * 1000L

    private val TYPES = mapOf(
        "movie" to "Movie",
        "tv" to "TV",
        "ova" to "OVA",
        "ona" to "ONA",
        "special" to "Special"
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cachedCsrf: Pair<Long, String>? = null
    private val shelfCache = ConcurrentHashMap<String, Pair<Long, List<Shelf>>>()

    fun forgetCsrf() {
        cachedCsrf = null
        shelfCache.clear()
    }

    fun fetchHomeHtml(host: String): String {
        val url = "https://$host/"
        HttpClientProvider.executeGet(url).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("AnimeUnity home failed: HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            try {
                val doc = Jsoup.parse(html)
                val meta = doc.selectFirst("meta[name=csrf-token]")
                val content = meta?.attr("content")
                if (!content.isNullOrBlank()) {
                    cachedCsrf = Pair(System.currentTimeMillis() + CSRF_TTL_MS, content)
                }
            } catch (_: Exception) {
            }
            return html
        }
    }

    private fun csrfToken(host: String): String? {
        val now = System.currentTimeMillis()
        val current = cachedCsrf
        if (current != null && current.first > now) {
            return current.second
        }
        cachedCsrf = null
        fetchHomeHtml(host)
        return cachedCsrf?.second
    }

    private fun ajaxHeaders(host: String, token: String?): Map<String, String> {
        val map = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://$host/archivio",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        if (!token.isNullOrBlank()) {
            map["X-CSRF-TOKEN"] = token
        }
        return map
    }

    private fun parseGenres(value: JsonElement?): List<String> {
        if (value == null) return emptyList()
        val arr: JsonArray = when (value) {
            is JsonArray -> value
            is JsonPrimitive -> {
                val raw = value.contentOrNull ?: return emptyList()
                try {
                    json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
                } catch (_: Exception) {
                    return emptyList()
                }
            }
            else -> return emptyList()
        }
        val names = mutableListOf<String>()
        for (el in arr) {
            val name = when (el) {
                is JsonObject -> el["name"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> el.contentOrNull
                else -> null
            }?.trim()
            if (!name.isNullOrEmpty()) {
                names.add(name)
            }
        }
        return names
    }

    fun normalizeTitle(raw: JsonObject): MediaTitle? {
        val titleStr = (raw["title_eng"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: raw["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: raw["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
            ?.trim()
            ?: return null

        val idPrim = raw["id"] as? JsonPrimitive ?: return null
        val idNum = idPrim.intOrNull ?: idPrim.contentOrNull?.toIntOrNull() ?: return null
        val slug = raw["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val fullId = if (slug.isNotEmpty()) "$idNum-$slug" else idNum.toString()

        val poster = raw["imageurl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: raw["cover"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: raw["poster"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: raw["image"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val dubPrim = raw["dub"] as? JsonPrimitive
        val dubbed = dubPrim?.booleanOrNull
            ?: (dubPrim?.intOrNull != null && dubPrim.intOrNull != 0)
            ?: (dubPrim?.contentOrNull == "1" || dubPrim?.contentOrNull.equals("true", ignoreCase = true))

        val score = raw["score"]?.jsonPrimitive?.doubleOrNull
            ?: raw["vote"]?.jsonPrimitive?.doubleOrNull

        val releaseDate = raw["date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: raw["release_date"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val jpTitle = raw["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val originalName = if (jpTitle != titleStr) jpTitle else ""

        return MediaTitle(
            id = fullId,
            numericId = idNum,
            name = titleStr,
            type = "anime",
            mediaType = raw["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            slug = slug,
            score = score,
            releaseDate = releaseDate,
            episodesCount = raw["episodes_count"]?.jsonPrimitive?.intOrNull ?: 0,
            poster = poster,
            backdrop = raw["imageurl_cover"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            plot = raw["plot"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            genres = parseGenres(raw["genres"]),
            dubbed = dubbed,
            studio = raw["studio"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = raw["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            season = raw["season"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            originalName = originalName,
            source = MediaSource.ANIME_UNITY
        )
    }

    private fun normalizeTitles(records: List<JsonObject>): List<MediaTitle> {
        val results = mutableListOf<MediaTitle>()
        val seen = mutableSetOf<String>()
        for (raw in records) {
            val item = normalizeTitle(raw) ?: continue
            if (item.id in seen) continue
            seen.add(item.id)
            results.add(item)
            if (results.size >= MAX_RESULTS) break
        }
        return results
    }

    private fun postArchive(host: String, bodyObj: JsonObject): String {
        val url = "https://$host/archivio/get-animes"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyStr = bodyObj.toString()

        fun executePost(token: String?): okhttp3.Response {
            val reqBuilder = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(mediaType))
            for ((k, v) in ajaxHeaders(host, token)) {
                reqBuilder.header(k, v)
            }
            return HttpClientProvider.sharedClient.newCall(reqBuilder.build()).execute()
        }

        executePost(csrfToken(host)).use { firstResp ->
            if (firstResp.code !in listOf(403, 419)) {
                if (!firstResp.isSuccessful) {
                    throw RuntimeException("AnimeUnity search failed: HTTP ${firstResp.code}")
                }
                return firstResp.body?.string().orEmpty()
            }
        }

        // Renew CSRF token once on 403/419
        cachedCsrf = null
        executePost(csrfToken(host)).use { retryResp ->
            if (!retryResp.isSuccessful) {
                throw RuntimeException("AnimeUnity search failed: HTTP ${retryResp.code}")
            }
            return retryResp.body?.string().orEmpty()
        }
    }

    suspend fun search(
        query: String,
        host: String = DEFAULT_HOST,
        page: Int = 1,
        mediaType: String? = null,
        dubbed: Boolean = false
    ): List<MediaTitle> = withContext(Dispatchers.IO) {
        val offset = ((page.coerceAtLeast(1)) - 1) * ARCHIVE_PAGE
        val payloadObj = buildJsonObject {
            put("title", query)
            put("offset", offset)
            if (dubbed) {
                put("dubbed", 1)
            }
            if (!mediaType.isNullOrBlank()) {
                put("type", TYPES[mediaType.lowercase()] ?: mediaType)
            }
        }

        val responseText = postArchive(host, payloadObj)
        val parsed = json.parseToJsonElement(responseText)
        val recordsArr = when (parsed) {
            is JsonArray -> parsed
            is JsonObject -> parsed["records"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val rawRecords = recordsArr.mapNotNull { it as? JsonObject }
        normalizeTitles(rawRecords)
    }

    private fun extractAttributeJson(doc: Document, attribute: String, tagName: String? = null): JsonElement? {
        val node = if (!tagName.isNullOrBlank()) {
            doc.selectFirst("$tagName[$attribute]") ?: doc.selectFirst("[$attribute]")
        } else {
            doc.selectFirst("[$attribute]")
        } ?: return null

        val raw = node.attr(attribute)
        if (raw.isBlank()) return null
        return try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getHomeShelves(host: String = DEFAULT_HOST): List<Shelf> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        shelfCache[host]?.let { (exp, hit) ->
            if (exp > now) return@withContext hit
        }

        val shelves = try {
            val html = fetchHomeHtml(host)
            val doc = Jsoup.parse(html)

            val latestRaw = (extractAttributeJson(doc, "items-json") as? JsonObject)
                ?.get("data") as? JsonArray
                ?: JsonArray(emptyList())

            val latest = mutableListOf<MediaTitle>()
            val seen = mutableSetOf<String>()
            for (row in latestRaw) {
                val animeObj = (row as? JsonObject)?.get("anime") as? JsonObject ?: continue
                val item = normalizeTitle(animeObj)
                if (item != null && item.id !in seen) {
                    seen.add(item.id)
                    latest.add(item)
                }
            }

            val featuredRaw = extractAttributeJson(doc, "animes", "the-carousel") as? JsonArray
                ?: JsonArray(emptyList())
            val featured = featuredRaw.mapNotNull { (it as? JsonObject)?.let(::normalizeTitle) }

            val result = mutableListOf<Shelf>()
            if (latest.isNotEmpty()) {
                result.add(Shelf(key = "latest_episodes", title = "Ultimi episodi", items = latest.take(SHELF_CAP)))
            }
            if (featured.isNotEmpty()) {
                result.add(Shelf(key = "featured", title = "In evidenza", items = featured.take(SHELF_CAP)))
            }
            result
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        shelfCache[host] = Pair(now + SHELF_TTL_MS, shelves)
        shelves
    }

    suspend fun getEpisodes(animeId: String, host: String = DEFAULT_HOST): List<EpisodeInfo> =
        withContext(Dispatchers.IO) {
            val infoUrl = "https://$host/info_api/$animeId"
            val episodesCount = HttpClientProvider.executeGet(infoUrl).use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("AnimeUnity info_api failed: HTTP ${resp.code}")
                }
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["episodes_count"]?.jsonPrimitive?.intOrNull ?: 0
            }

            if (episodesCount <= 0) return@withContext emptyList()

            val result = mutableListOf<EpisodeInfo>()
            var start = 0
            while (start <= episodesCount) {
                val end = (start + BATCH_SIZE - 1).coerceAtMost(episodesCount)
                val batchUrl = "https://$host/info_api/$animeId/0".toHttpUrl().newBuilder()
                    .addQueryParameter("start_range", start.toString())
                    .addQueryParameter("end_range", end.toString())
                    .build()
                    .toString()

                HttpClientProvider.executeGet(batchUrl).use { batchResp ->
                    if (batchResp.isSuccessful) {
                        val batchJson = json.parseToJsonElement(batchResp.body?.string().orEmpty()).jsonObject
                        val epsArr = batchJson["episodes"] as? JsonArray ?: JsonArray(emptyList())
                        for (el in epsArr) {
                            val epObj = el as? JsonObject ?: continue
                            val id = epObj["id"]?.jsonPrimitive?.longOrNull ?: continue
                            val number = epObj["number"]?.jsonPrimitive?.contentOrNull ?: "1"
                            result.add(
                                EpisodeInfo(
                                    id = id,
                                    number = number,
                                    name = "Episodio $number",
                                    season = 1
                                )
                            )
                        }
                    }
                }
                start += BATCH_SIZE
            }
            result
        }

    private fun getEmbedContent(
        episodeId: Long,
        host: String,
        videoHostOverride: String = ""
    ): Pair<String, String> {
        val embedEndpoint = "https://$host/embed-url/$episodeId"
        val rawEmbedUrl = HttpClientProvider.executeGet(embedEndpoint).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Cannot fetch anime embed-url: HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty().trim()
        }

        if (!rawEmbedUrl.startsWith("http")) {
            throw RuntimeException("Unexpected embed-url response: ${rawEmbedUrl.take(100)}")
        }

        val embedUrl = VixcloudParser.applyVideoHost(rawEmbedUrl, videoHostOverride)
        val scriptText = VixcloudParser.fetchVixcloudEmbedScript(embedUrl, referer = "https://$host/")
        return Pair(scriptText, embedUrl)
    }

    suspend fun getEpisodeLanguages(
        episodeId: Long,
        host: String = DEFAULT_HOST,
        videoHostOverride: String = ""
    ): AvailableTracks = withContext(Dispatchers.IO) {
        val (embedContent, embedUrl) = getEmbedContent(episodeId, host, videoHostOverride)
        val (video, params) = VixcloudParser.parseEmbedContent(embedContent, embedUrl)
        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        VixcloudParser.fetchMasterLanguages(m3u8Url, embedUrl)
    }

    suspend fun resolveAnimeEpisodeStream(
        episodeId: Long,
        host: String = DEFAULT_HOST,
        videoHostOverride: String = ""
    ): StreamSource = withContext(Dispatchers.IO) {
        val (embedContent, embedUrl) = getEmbedContent(episodeId, host, videoHostOverride)
        val (video, params) = VixcloudParser.parseEmbedContent(embedContent, embedUrl)
        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        val keyHex = VixcloudParser.fetchM3u8KeyHex(video, embedUrl)
        StreamSource(
            m3u8Url = m3u8Url,
            keyHex = keyHex,
            referer = embedUrl,
            provider = "vixcloud"
        )
    }

    suspend fun verifyAnimeUnity(host: String) = withContext(Dispatchers.IO) {
        val html = try {
            HttpClientProvider.executeGet("https://$host/").use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("$host non raggiungibile: HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("$host non raggiungibile: ${e.message}")
        }
        if ("animeunity" !in html.lowercase()) {
            throw RuntimeException("$host risponde, ma non sembra AnimeUnity")
        }
    }
}
