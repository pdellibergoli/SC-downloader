package com.streamingcommunity.downloader.core.network

import com.streamingcommunity.downloader.core.model.AvailableTracks
import com.streamingcommunity.downloader.core.model.EpisodeInfo
import com.streamingcommunity.downloader.core.model.MediaSource
import com.streamingcommunity.downloader.core.model.MediaTitle
import com.streamingcommunity.downloader.core.model.Shelf
import com.streamingcommunity.downloader.core.model.StreamSource
import com.streamingcommunity.downloader.core.model.TitleMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object StreamingCommunityClient {

    private const val RESULT_CAP = 60
    private const val SHELF_CAP = 30
    private const val DEFAULT_LOCALE = "/it"
    private const val SHELF_TTL_MS = 15 * 60 * 1000L
    private const val METADATA_TTL_HIT_MS = 6 * 60 * 60 * 1000L
    private const val METADATA_TTL_MISS_MS = 10 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val localeByHost = ConcurrentHashMap<String, String>()
    private val shelfCache = ConcurrentHashMap<String, Pair<Long, List<Shelf>>>()
    private val metadataCache = ConcurrentHashMap<String, Pair<Long, TitleMetadata>>()

    private val SHELF_LABELS = mapOf(
        "trending" to "Di tendenza",
        "latest" to "Aggiunti di recente",
        "top10" to "Top 10 di oggi"
    )

    fun clearCaches() {
        shelfCache.clear()
        metadataCache.clear()
    }

    fun locale(domain: String): String = localeByHost[domain] ?: DEFAULT_LOCALE

    fun siteUrl(domain: String, path: String = ""): String =
        "https://$domain${locale(domain)}$path"

    fun parseDataPage(html: String): JsonObject {
        val doc = Jsoup.parse(html)
        val appDiv = doc.getElementById("app")
        val raw = appDiv?.attr("data-page")
        if (raw.isNullOrBlank()) {
            throw NoSuchElementException("the page carries no data-page payload")
        }
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun buildUrlWithParams(baseUrl: String, params: Map<String, String>): String {
        if (params.isEmpty()) return baseUrl
        val builder = baseUrl.toHttpUrl().newBuilder()
        for ((k, v) in params) {
            builder.addQueryParameter(k, v)
        }
        return builder.build().toString()
    }

    fun getLocalized(
        domain: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): Response {
        val currentLocale = locale(domain)
        val firstUrl = buildUrlWithParams("https://$domain$currentLocale$path", params)
        val firstResp = HttpClientProvider.executeGet(firstUrl, headers)
        if (firstResp.code != 404) {
            return firstResp
        }

        val otherLocale = if (currentLocale.isEmpty()) DEFAULT_LOCALE else ""
        val retryUrl = buildUrlWithParams("https://$domain$otherLocale$path", params)
        val retryResp = HttpClientProvider.executeGet(retryUrl, headers)
        if (retryResp.code == 404) {
            retryResp.close()
            return firstResp
        }
        firstResp.close()
        localeByHost[domain] = otherLocale
        return retryResp
    }

    fun fetchSiteProps(
        domain: String,
        path: String = "",
        params: Map<String, String> = emptyMap()
    ): JsonObject {
        val headers = mapOf("User-Agent" to HttpClientProvider.randomUserAgent())
        getLocalized(domain, path, params, headers).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code} fetching $domain$path")
            }
            val html = resp.body?.string().orEmpty()
            return parseDataPage(html)["props"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    suspend fun getDomainVersion(domain: String): String? = withContext(Dispatchers.IO) {
        val url = "https://$domain"
        val headers = mapOf("User-Agent" to HttpClientProvider.randomUserAgent())
        val html = try {
            HttpClientProvider.executeGet(url, headers).use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("Cannot reach $domain: HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            throw RuntimeException("Cannot reach $domain: ${e.message}", e)
        }

        try {
            val payload = parseDataPage(html)
            payload["version"]?.jsonPrimitive?.contentOrNull ?: ""
        } catch (e: NoSuchElementException) {
            ""
        } catch (e: Exception) {
            null
        }
    }

    private fun extractImageFilename(images: JsonArray?, targetType: String): String? {
        if (images == null) return null
        for (el in images) {
            val obj = el as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val filename = obj["filename"]?.jsonPrimitive?.contentOrNull
            if (type == targetType && !filename.isNullOrBlank()) {
                return filename
            }
        }
        return null
    }

    fun normalizeTitle(raw: JsonObject): MediaTitle? {
        val idPrim = raw["id"] as? JsonPrimitive ?: return null
        val idNum = idPrim.intOrNull ?: idPrim.contentOrNull?.toIntOrNull() ?: return null
        val name = raw["name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (name.isNullOrEmpty()) return null

        val type = raw["type"]?.jsonPrimitive?.contentOrNull ?: "movie"
        val slug = raw["slug"]?.jsonPrimitive?.contentOrNull ?: ""
        val score = raw["score"]?.jsonPrimitive?.doubleOrNull
        val releaseDate = raw["release_date"]?.jsonPrimitive?.contentOrNull
        val lastAirDate = raw["last_air_date"]?.jsonPrimitive?.contentOrNull
        val age = raw["age"]?.jsonPrimitive?.intOrNull
        val seasonsCount = raw["seasons_count"]?.jsonPrimitive?.intOrNull ?: 0
        val images = raw["images"] as? JsonArray
        val poster = extractImageFilename(images, "poster")

        return MediaTitle(
            id = idNum.toString(),
            numericId = idNum,
            name = name,
            type = type,
            slug = slug,
            score = score,
            releaseDate = releaseDate,
            lastAirDate = lastAirDate,
            age = age,
            seasonsCount = seasonsCount,
            poster = poster,
            source = MediaSource.STREAMING_COMMUNITY
        )
    }

    suspend fun search(
        query: String,
        domain: String,
        page: Int = 1,
        mediaType: String? = null
    ): List<MediaTitle> = withContext(Dispatchers.IO) {
        val params = mutableMapOf("q" to query)
        if (page > 1) {
            params["page"] = page.toString()
        }
        val props = fetchSiteProps(domain, "/search", params)
        val rawTitles = (props["titles"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        val filtered = if (!mediaType.isNullOrBlank()) {
            rawTitles.filter { it["type"]?.jsonPrimitive?.contentOrNull == mediaType }
        } else {
            rawTitles
        }

        filtered.take(RESULT_CAP).mapNotNull { normalizeTitle(it) }
    }

    suspend fun getHomeShelves(domain: String): List<Shelf> = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val cached = shelfCache[domain]
        if (cached != null && cached.first > now) {
            return@withContext cached.second
        }

        val shelves = try {
            val props = fetchSiteProps(domain)
            val sliders = (props["sliders"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
            val result = mutableListOf<Shelf>()
            for (slider in sliders) {
                val key = slider["name"]?.jsonPrimitive?.contentOrNull
                    ?: slider["label"]?.jsonPrimitive?.contentOrNull
                    ?: continue
                val fallbackLabel = slider["label"]?.jsonPrimitive?.contentOrNull
                val rawItems = (slider["titles"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                val items = rawItems.take(SHELF_CAP).mapNotNull { normalizeTitle(it) }
                if (items.isNotEmpty()) {
                    result.add(
                        Shelf(
                            key = key,
                            title = SHELF_LABELS[key] ?: fallbackLabel ?: key,
                            items = items
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        shelfCache[domain] = Pair(now + SHELF_TTL_MS, shelves)
        shelves
    }

    fun getTitleProps(
        idFilm: Int,
        slug: String,
        siteVersion: String,
        domain: String
    ): JsonObject {
        val headers = mapOf(
            "X-Inertia" to "true",
            "X-Inertia-Version" to siteVersion,
            "User-Agent" to HttpClientProvider.randomUserAgent()
        )
        getLocalized(domain, "/titles/$idFilm-$slug", headers = headers).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Cannot fetch title info: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            return root["props"]?.jsonObject?.get("title")?.jsonObject
                ?: throw RuntimeException("Missing props.title in response")
        }
    }

    fun extractSeasonNumbers(props: JsonObject): List<Int> {
        val numbers = mutableSetOf<Int>()
        val seasonsArr = props["seasons"] as? JsonArray
        if (seasonsArr != null) {
            for (el in seasonsArr) {
                val sObj = el as? JsonObject ?: continue
                val num = sObj["number"]?.jsonPrimitive?.intOrNull
                    ?: sObj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (num != null && num > 0) {
                    numbers.add(num)
                }
            }
        }
        if (numbers.isNotEmpty()) {
            return numbers.sorted()
        }
        val count = props["seasons_count"]?.jsonPrimitive?.intOrNull ?: 0
        return if (count > 0) (1..count).toList() else emptyList()
    }

    private fun extractPeople(entries: JsonElement?): List<String> {
        val arr = entries as? JsonArray ?: return emptyList()
        val names = mutableListOf<String>()
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
            if (!name.isNullOrEmpty() && name !in names) {
                names.add(name)
            }
        }
        return names.take(20)
    }

    private fun extractYear(props: JsonObject): String? {
        var date = props["release_date"]?.jsonPrimitive?.contentOrNull
        val type = props["type"]?.jsonPrimitive?.contentOrNull
        if (date.isNullOrBlank() && type == "movie") {
            date = props["last_air_date"]?.jsonPrimitive?.contentOrNull
        }
        val sub = date?.take(4)
        return if (sub != null && sub.length == 4 && sub.all { it.isDigit() }) sub else null
    }

    suspend fun getTitleMetadata(
        mediaType: String,
        titleId: Int,
        slug: String,
        siteVersion: String,
        domain: String
    ): TitleMetadata = withContext(Dispatchers.IO) {
        if (domain.isBlank() || slug.isBlank()) return@withContext TitleMetadata.EMPTY
        val cacheKey = "$domain|$mediaType|$titleId"
        val now = System.currentTimeMillis()
        metadataCache[cacheKey]?.let { (exp, hit) ->
            if (exp > now) return@withContext hit
        }

        val metadata = try {
            val props = getTitleProps(titleId, slug, siteVersion, domain)
            val images = props["images"] as? JsonArray
            val plot = props["plot"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val hasSource = plot != null || (images != null && images.isNotEmpty())
            val score = props["score"]?.jsonPrimitive?.doubleOrNull
                ?.let { (it * 10.0).roundToInt() / 10.0 }
            val runtime = props["runtime"]?.jsonPrimitive?.intOrNull
            val backdrop = extractImageFilename(images, "background")
                ?: extractImageFilename(images, "cover")
            val logo = extractImageFilename(images, "logo")
            val poster = extractImageFilename(images, "poster")

            val trailers = props["trailers"] as? JsonArray
            val firstTrailer = trailers?.firstOrNull() as? JsonObject
            val youtubeId = firstTrailer?.get("youtube_id")?.jsonPrimitive?.contentOrNull
            val trailerUrl = youtubeId?.takeIf { it.isNotBlank() }
                ?.let { "https://www.youtube.com/watch?v=$it" }

            val genres = (props["genres"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                ?: emptyList()

            val propType = props["type"]?.jsonPrimitive?.contentOrNull
            TitleMetadata(
                source = if (hasSource) "site" else "none",
                plot = plot,
                genres = genres,
                rating = score,
                runtime = runtime,
                backdrop = backdrop,
                logo = logo,
                trailerUrl = trailerUrl,
                tmdbId = props["tmdb_id"]?.jsonPrimitive?.intOrNull,
                name = props["name"]?.jsonPrimitive?.contentOrNull,
                poster = poster,
                type = propType,
                seasonsCount = props["seasons_count"]?.jsonPrimitive?.intOrNull,
                seasonNumbers = if (propType == "tv") extractSeasonNumbers(props) else emptyList(),
                cast = extractPeople(props["main_actors"]),
                directors = extractPeople(props["main_directors"]),
                originalName = props["original_name"]?.jsonPrimitive?.contentOrNull,
                originalLanguage = props["original_language"]?.jsonPrimitive?.contentOrNull,
                status = props["status"]?.jsonPrimitive?.contentOrNull,
                quality = props["quality"]?.jsonPrimitive?.contentOrNull,
                age = props["age"]?.jsonPrimitive?.intOrNull,
                year = extractYear(props)
            )
        } catch (e: Exception) {
            TitleMetadata.EMPTY
        }

        val ttl = if (metadata.source != "none") METADATA_TTL_HIT_MS else METADATA_TTL_MISS_MS
        metadataCache[cacheKey] = Pair(now + ttl, metadata)
        metadata
    }

    suspend fun getToken(tvId: Int, domain: String): String = withContext(Dispatchers.IO) {
        val ua = HttpClientProvider.randomUserAgent()
        for (path in listOf("/it/watch/$tvId", "/watch/$tvId")) {
            val url = "https://$domain$path"
            HttpClientProvider.executeGet(url, mapOf("User-Agent" to ua)).use {
                val rawToken = HttpClientProvider.cookieJar.getCookieValue(domain, "XSRF-TOKEN")
                if (!rawToken.isNullOrBlank()) {
                    return@withContext URLDecoder.decode(rawToken, "UTF-8")
                }
            }
        }
        throw RuntimeException("XSRF-TOKEN cookie not found after page visit")
    }

    suspend fun getSeasonEpisodes(
        tvId: Int,
        tvSlug: String,
        domain: String,
        version: String,
        token: String,
        seasonNumber: Int
    ): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val headers = mapOf(
            "authority" to domain,
            "referer" to siteUrl(domain, "/titles/$tvId-$tvSlug"),
            "user-agent" to HttpClientProvider.randomUserAgent(),
            "x-inertia" to "true",
            "x-inertia-version" to version,
            "x-xsrf-token" to token
        )

        getLocalized(
            domain = domain,
            path = "/titles/$tvId-$tvSlug/season-$seasonNumber",
            headers = headers
        ).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Cannot fetch season info: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val loaded = root["props"]?.jsonObject?.get("loadedSeason")?.jsonObject
                ?: throw RuntimeException("Missing loadedSeason in response")

            val loadedNum = loaded["number"]?.jsonPrimitive?.contentOrNull
            if (loadedNum != null && loadedNum != seasonNumber.toString()) {
                throw RuntimeException("Season $seasonNumber not found (site answered with season $loadedNum)")
            }

            val episodesArr = loaded["episodes"] as? JsonArray ?: return@withContext emptyList()
            episodesArr.mapNotNull { el ->
                val ep = el as? JsonObject ?: return@mapNotNull null
                val id = ep["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val number = ep["number"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = ep["name"]?.jsonPrimitive?.contentOrNull
                val plot = ep["plot"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val duration = ep["duration"]?.jsonPrimitive?.intOrNull
                val images = ep["images"] as? JsonArray
                val still = listOf("cover", "still", "background").firstNotNullOfOrNull { kind ->
                    extractImageFilename(images, kind)
                }
                EpisodeInfo(
                    id = id,
                    number = number,
                    name = name,
                    plot = plot,
                    duration = duration,
                    still = still,
                    season = seasonNumber
                )
            }
        }
    }

    private fun getFilmIframe(idTitle: Int, domain: String, videoHostOverride: String = ""): Pair<String, String> {
        val ua = HttpClientProvider.randomUserAgent()
        var iframeHtml: String? = null
        var lastStatus = 0
        for (path in listOf("/iframe/$idTitle", "/it/iframe/$idTitle")) {
            HttpClientProvider.executeGet("https://$domain$path", mapOf("User-Agent" to ua)).use { resp ->
                lastStatus = resp.code
                if (resp.isSuccessful) {
                    iframeHtml = resp.body?.string()
                }
            }
            if (!iframeHtml.isNullOrBlank()) break
        }
        if (iframeHtml.isNullOrBlank()) {
            throw RuntimeException("Cannot fetch iframe: HTTP $lastStatus")
        }

        val src = Jsoup.parse(iframeHtml!!).selectFirst("iframe")?.attr("src")
            ?: throw RuntimeException("Iframe tag not found")
        val urlEmbed = VixcloudParser.applyVideoHost(src, videoHostOverride)
        val scriptText = VixcloudParser.fetchVixcloudEmbedScript(urlEmbed, referer = "https://$domain/")
        return Pair(scriptText, urlEmbed)
    }

    private fun getEpisodeIframe(
        tvId: Int,
        epId: Long,
        domain: String,
        token: String,
        videoHostOverride: String = ""
    ): Pair<String, String> {
        val ua = HttpClientProvider.randomUserAgent()
        val params = mapOf("episode_id" to epId.toString(), "next_episode" to "1")
        val headers = mapOf(
            "Referer" to siteUrl(domain, "/watch/$tvId?e=$epId"),
            "User-Agent" to ua,
            "Cookie" to "XSRF-TOKEN=$token"
        )
        var iframeHtml: String? = null
        var lastStatus = 0

        for (path in listOf("/iframe/$tvId", "/it/iframe/$tvId")) {
            val url = buildUrlWithParams("https://$domain$path", params)
            HttpClientProvider.executeGet(url, headers).use { resp ->
                lastStatus = resp.code
                if (resp.isSuccessful) {
                    iframeHtml = resp.body?.string()
                }
            }
            if (!iframeHtml.isNullOrBlank()) break
        }
        if (iframeHtml.isNullOrBlank()) {
            throw RuntimeException("Cannot fetch episode iframe: HTTP $lastStatus")
        }

        val src = Jsoup.parse(iframeHtml!!).selectFirst("iframe")?.attr("src")
            ?: throw RuntimeException("Episode iframe tag not found")
        val urlEmbed = VixcloudParser.applyVideoHost(src, videoHostOverride)
        val scriptText = VixcloudParser.fetchVixcloudEmbedScript(urlEmbed, referer = "https://$domain/")
        return Pair(scriptText, urlEmbed)
    }

    suspend fun getFilmLanguages(
        idFilm: Int,
        domain: String,
        videoHostOverride: String = ""
    ): AvailableTracks = withContext(Dispatchers.IO) {
        val (embedContent, urlEmbed) = getFilmIframe(idFilm, domain, videoHostOverride)
        val (video, params) = VixcloudParser.parseEmbedContent(embedContent, urlEmbed)
        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        val referer = VixcloudParser.embedReferer(video, params)
        val explicitLang = VixcloudParser.parseQueryParams(URI(urlEmbed).rawQuery)["lang"]?.firstOrNull()
        VixcloudParser.fetchMasterLanguages(m3u8Url, referer, explicitLang)
    }

    suspend fun getEpisodeLanguages(
        tvId: Int,
        epId: Long,
        domain: String,
        token: String,
        videoHostOverride: String = ""
    ): AvailableTracks = withContext(Dispatchers.IO) {
        val (embedContent, urlEmbed) = getEpisodeIframe(tvId, epId, domain, token, videoHostOverride)
        val (video, params) = VixcloudParser.parseEmbedContent(embedContent, urlEmbed)
        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        val referer = VixcloudParser.embedReferer(video, params)
        val explicitLang = VixcloudParser.parseQueryParams(URI(urlEmbed).rawQuery)["lang"]?.firstOrNull()
        VixcloudParser.fetchMasterLanguages(m3u8Url, referer, explicitLang)
    }

    suspend fun resolveFilmStream(
        idFilm: Int,
        domain: String,
        videoHostOverride: String = ""
    ): StreamSource = withContext(Dispatchers.IO) {
        val (embedContent, urlEmbed) = getFilmIframe(idFilm, domain, videoHostOverride)
        val (video, params) = VixcloudParser.parseEmbedContent(embedContent, urlEmbed)
        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        val keyHex = VixcloudParser.fetchM3u8KeyHex(video, urlEmbed)
        StreamSource(
            m3u8Url = m3u8Url,
            keyHex = keyHex,
            referer = urlEmbed,
            provider = "vixcloud"
        )
    }

    suspend fun resolveEpisodeStream(
        tvId: Int,
        ep: EpisodeInfo,
        tvName: String,
        season: Int,
        domain: String,
        token: String,
        videoHostOverride: String = ""
    ): StreamSource = withContext(Dispatchers.IO) {
        val (embedContent, urlEmbed) = getEpisodeIframe(tvId, ep.id, domain, token, videoHostOverride)
        val (video, params) = VixcloudParser.parseEmbedContent(embedContent, urlEmbed)
        val encodedTitle = URLEncoder.encode(tvName, "UTF-8")
        val encodedEpName = URLEncoder.encode(ep.name.orEmpty(), "UTF-8")
        val embedReferer = "https://${video.host}/embed/${video.id}" +
                "?token=${params.token}&title=$encodedTitle" +
                "&referer=1&expires=${params.expires}" +
                "&description=S$season%3AE${ep.number}+$encodedEpName&nextEpisode=1"

        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        val keyHex = VixcloudParser.fetchM3u8KeyHex(video, embedReferer)
        StreamSource(
            m3u8Url = m3u8Url,
            keyHex = keyHex,
            referer = embedReferer,
            provider = "vixcloud"
        )
    }
}
