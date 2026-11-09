package com.streamingcommunity.downloader.core.network

import kotlinx.coroutines.delay
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.pow

object HttpClientProvider {

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:132.0) Gecko/20100101 Firefox/132.0",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    fun randomUserAgent(): String = USER_AGENTS.random()

    class InMemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostCookies = store.getOrPut(url.host) { mutableListOf() }
            synchronized(hostCookies) {
                for (newCookie in cookies) {
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            synchronized(hostCookies) {
                hostCookies.removeAll { it.expiresAt < now }
                return hostCookies.toList()
            }
        }

        fun getCookieValue(host: String, name: String): String? {
            val hostCookies = store[host] ?: return null
            synchronized(hostCookies) {
                return hostCookies.firstOrNull { it.name == name }?.value
            }
        }

        fun clear() {
            store.clear()
        }
    }

    val cookieJar = InMemoryCookieJar()

    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
            .build()
    }

    fun newSegmentClient(poolSize: Int): OkHttpClient {
        val safePool = poolSize.coerceIn(2, 32)
        return sharedClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(safePool, 2, TimeUnit.MINUTES))
            .build()
    }

    private val SAFE_IMAGE_FILENAME_RE = Regex("""^[A-Za-z0-9._-]{1,200}$""")

    fun isSafeImageFilename(filename: String): Boolean {
        return filename.isNotBlank() &&
            ".." !in filename &&
            !filename.startsWith(".") &&
            SAFE_IMAGE_FILENAME_RE.matches(filename)
    }

    /**
     * Build an OkHttpClient for Coil that resolves StreamingCommunity image filenames
     * against `https://cdn.{domain}/images/{filename}` and falls back to
     * `https://{domain}/images/{filename}` with the required Referer header.
     */
    fun createImageClient(domainProvider: () -> String): OkHttpClient {
        val imageInterceptor = Interceptor { chain ->
            val original = chain.request()
            val domain = domainProvider().trim()

            // Detect our internal marker https://sc-image.local/{filename}
            if (original.url.host == "sc-image.local" && domain.isNotBlank()) {
                val filename = original.url.encodedPath.removePrefix("/").removePrefix("api/image/")
                if (!isSafeImageFilename(filename)) {
                    throw IOException("Rejected unsafe image filename")
                }
                val candidates = listOf(
                    "https://cdn.$domain/images/$filename",
                    "https://$domain/images/$filename"
                )
                var lastResponse: Response? = null
                for (candidateUrl in candidates) {
                    lastResponse?.close()
                    val req = original.newBuilder()
                        .url(candidateUrl)
                        .header("User-Agent", randomUserAgent())
                        .header("Referer", "https://$domain/")
                        .build()
                    try {
                        val res = chain.proceed(req)
                        val contentType = res.header("Content-Type").orEmpty()
                        if (res.isSuccessful && contentType.startsWith("image/")) {
                            return@Interceptor res
                        }
                        lastResponse = res
                    } catch (e: Exception) {
                        // Try next candidate
                    }
                }
                if (lastResponse != null) return@Interceptor lastResponse
            }

            val reqWithUa = original.newBuilder()
                .header("User-Agent", original.header("User-Agent") ?: randomUserAgent())
                .build()
            chain.proceed(reqWithUa)
        }

        return sharedClient.newBuilder()
            .addInterceptor(imageInterceptor)
            .build()
    }

    fun resolveImageUrl(raw: String?, domain: String): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val cleanFilename = trimmed
            .removePrefix("/api/image/")
            .removePrefix("/")
        if (!isSafeImageFilename(cleanFilename)) return null
        return "https://sc-image.local/$cleanFilename"
    }

    private fun isRetriableStatus(code: Int): Boolean = code == 429 || code >= 500

    suspend fun withRetry(
        what: String,
        attempts: Int = 4,
        baseDelayMs: Long = 1000L,
        block: () -> Response
    ): Response {
        var lastException: Exception? = null
        for (attempt in 0 until attempts) {
            val isLast = attempt == attempts - 1
            try {
                val response = block()
                if (!isRetriableStatus(response.code) || isLast) {
                    return response
                }
                response.close()
            } catch (e: SocketTimeoutException) {
                lastException = e
                if (isLast) throw e
            } catch (e: IOException) {
                lastException = e
                if (isLast) throw e
            }
            val delayMs = (baseDelayMs * 2.0.pow(attempt.toDouble())).toLong()
            delay(delayMs)
        }
        throw lastException ?: RuntimeException("Retry failed for $what")
    }

    fun executeGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = sharedClient
    ): Response {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }
        if ("User-Agent" !in headers && "user-agent" !in headers) {
            reqBuilder.header("User-Agent", randomUserAgent())
        }
        return client.newCall(reqBuilder.build()).execute()
    }
}
