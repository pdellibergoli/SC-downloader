package com.streamingcommunity.downloader.core.hls

import com.streamingcommunity.downloader.core.model.StreamSource
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Lightweight localhost (127.0.0.1) HLS proxy server so external Android video players
 * (including stock OEM players like Samsung Video Player that ignore Intent HTTP headers)
 * can play Vixcloud AES-128 HLS streams seamlessly without needing custom Referer/User-Agent headers.
 */
object LocalHlsProxyServer {

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var boundPort: Int = 0
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var activeSource: StreamSource? = null

    @Volatile
    private var activeUserAgent: String = HttpClientProvider.randomUserAgent()

    private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")

    /**
     * Starts the localhost proxy server if not already running, updates the active [StreamSource],
     * and returns the local proxy URL (`http://127.0.0.1:<port>/stream.m3u8`).
     */
    fun prepareStream(source: StreamSource, userAgent: String = HttpClientProvider.randomUserAgent()): String {
        activeSource = source
        activeUserAgent = userAgent
        val port = ensureServerStarted()
        return "http://127.0.0.1:$port/stream.m3u8"
    }

    private fun ensureServerStarted(): Int {
        synchronized(lock) {
            val existing = serverSocket
            if (existing != null && !existing.isClosed && boundPort > 0) {
                return boundPort
            }
            val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            boundPort = socket.localPort

            executor.execute {
                acceptLoop(socket)
            }
            return boundPort
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                executor.execute {
                    handleClient(client)
                }
            } catch (_: Exception) {
                if (server.isClosed) break
            }
        }
    }

    fun encodeTargetUrl(url: String): String {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(url.toByteArray(StandardCharsets.UTF_8))
    }

    fun decodeTargetUrl(encoded: String): String? {
        return runCatching {
            val clean = URLDecoder.decode(encoded, "UTF-8")
            String(Base64.getUrlDecoder().decode(clean), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Rewrites all playlist, key, and segment URIs in [m3u8Content] (resolved against [baseUrl])
     * to point to `http://127.0.0.1:[port]/...`.
     */
    fun rewriteM3u8Playlist(m3u8Content: String, baseUrl: String, port: Int): String {
        val localBase = "http://127.0.0.1:$port"
        val lines = m3u8Content.lines()
        val out = StringBuilder(m3u8Content.length + 1024)
        var expectVariantPlaylistUri = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                out.append("\n")
                continue
            }

            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    expectVariantPlaylistUri = true
                    out.append(line).append("\n")
                }

                line.startsWith("#EXT-X-MEDIA:") -> {
                    val rewritten = URI_ATTR_REGEX.replace(line) { match ->
                        val resolved = M3u8Parser.resolveUrl(baseUrl, match.groupValues[1])
                        val enc = encodeTargetUrl(resolved)
                        """URI="$localBase/playlist.m3u8?u=$enc""""
                    }
                    out.append(rewritten).append("\n")
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val rewritten = URI_ATTR_REGEX.replace(line) { match ->
                        val resolved = M3u8Parser.resolveUrl(baseUrl, match.groupValues[1])
                        val enc = encodeTargetUrl(resolved)
                        """URI="$localBase/enc.key?u=$enc""""
                    }
                    out.append(rewritten).append("\n")
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val rewritten = URI_ATTR_REGEX.replace(line) { match ->
                        val resolved = M3u8Parser.resolveUrl(baseUrl, match.groupValues[1])
                        val enc = encodeTargetUrl(resolved)
                        """URI="$localBase/segment.ts?u=$enc""""
                    }
                    out.append(rewritten).append("\n")
                }

                line.startsWith("#") -> {
                    out.append(line).append("\n")
                }

                else -> {
                    val resolved = M3u8Parser.resolveUrl(baseUrl, line)
                    val enc = encodeTargetUrl(resolved)
                    if (expectVariantPlaylistUri) {
                        expectVariantPlaylistUri = false
                        out.append("$localBase/playlist.m3u8?u=$enc\n")
                    } else if (resolved.contains(".vtt", ignoreCase = true) || line.contains("vtt", ignoreCase = true)) {
                        out.append("$localBase/segment.vtt?u=$enc\n")
                    } else {
                        out.append("$localBase/segment.ts?u=$enc\n")
                    }
                }
            }
        }
        return out.toString()
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val out = BufferedOutputStream(client.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val h = reader.readLine()
                if (h.isNullOrEmpty()) break
                if (h.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = h.substringAfter(":").trim()
                }
            }

            val source = activeSource
            if (source == null) {
                writeSimpleResponse(out, 404, "Not Found", "text/plain", "No active stream".toByteArray())
                return
            }

            val pathOnly = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")
            val encodedTarget = query.split("&")
                .firstOrNull { it.startsWith("u=") }
                ?.substringAfter("u=")

            try {
                when {
                    pathOnly == "/stream.m3u8" -> {
                        servePlaylist(out, method, source.m3u8Url, source)
                    }

                    pathOnly == "/playlist.m3u8" -> {
                        val targetUrl = encodedTarget?.let { decodeTargetUrl(it) }
                        if (targetUrl.isNullOrBlank()) {
                            writeSimpleResponse(out, 400, "Bad Request", "text/plain", "Missing u".toByteArray())
                            return
                        }
                        servePlaylist(out, method, targetUrl, source)
                    }

                    pathOnly == "/enc.key" -> {
                        serveEncryptionKey(out, method, encodedTarget?.let { decodeTargetUrl(it) }, source)
                    }

                    pathOnly.startsWith("/segment.") -> {
                        val targetUrl = encodedTarget?.let { decodeTargetUrl(it) }
                        if (targetUrl.isNullOrBlank()) {
                            writeSimpleResponse(out, 400, "Bad Request", "text/plain", "Missing u".toByteArray())
                            return
                        }
                        val defaultContentType = if (pathOnly.endsWith(".vtt")) "text/vtt" else "video/mp2t"
                        serveBinarySegment(out, method, targetUrl, source, rangeHeader, defaultContentType)
                    }

                    else -> {
                        writeSimpleResponse(out, 404, "Not Found", "text/plain", "Unknown route".toByteArray())
                    }
                }
            } catch (_: Exception) {
                // Client disconnected or upstream failed
            }
        }
    }

    private fun servePlaylist(
        out: BufferedOutputStream,
        method: String,
        targetUrl: String,
        source: StreamSource
    ) {
        val (content, actualUrl) = M3u8Downloader.fetchTextWithB1Fallback(
            url = targetUrl,
            referer = source.referer,
            userAgent = activeUserAgent,
            client = HttpClientProvider.sharedClient
        )
        val rewritten = rewriteM3u8Playlist(content, actualUrl, boundPort)
        val bodyBytes = rewritten.toByteArray(StandardCharsets.UTF_8)
        writeSimpleResponse(
            out = out,
            statusCode = 200,
            statusText = "OK",
            contentType = "application/vnd.apple.mpegurl; charset=utf-8",
            body = if (method == "HEAD") ByteArray(0) else bodyBytes,
            explicitContentLength = bodyBytes.size
        )
    }

    private fun serveEncryptionKey(
        out: BufferedOutputStream,
        method: String,
        targetUrl: String?,
        source: StreamSource
    ) {
        val preloadedBytes = source.keyHex
            ?.takeIf { it.length == 32 }
            ?.let { runCatching { AesSegmentDecryptor.hexToBytes(it) }.getOrNull() }

        val keyBytes = if (preloadedBytes != null && preloadedBytes.size == 16) {
            preloadedBytes
        } else if (!targetUrl.isNullOrBlank()) {
            val req = Request.Builder()
                .url(targetUrl)
                .header("Referer", source.referer)
                .header("User-Agent", activeUserAgent)
                .build()
            HttpClientProvider.sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    writeSimpleResponse(out, resp.code, "Upstream Error", "text/plain", ByteArray(0))
                    return
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        } else {
            ByteArray(0)
        }

        writeSimpleResponse(
            out = out,
            statusCode = 200,
            statusText = "OK",
            contentType = "application/octet-stream",
            body = if (method == "HEAD") ByteArray(0) else keyBytes,
            explicitContentLength = keyBytes.size
        )
    }

    private fun serveBinarySegment(
        out: BufferedOutputStream,
        method: String,
        targetUrl: String,
        source: StreamSource,
        rangeHeader: String?,
        defaultContentType: String
    ) {
        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("Referer", source.referer)
            .header("User-Agent", activeUserAgent)
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        HttpClientProvider.sharedClient.newCall(reqBuilder.build()).execute().use { resp ->
            val code = resp.code
            val statusText = resp.message.ifBlank { if (code == 206) "Partial Content" else "OK" }
            val contentType = resp.header("Content-Type")?.takeIf { it.isNotBlank() } ?: defaultContentType
            val contentLength = resp.body?.contentLength() ?: -1L
            val contentRange = resp.header("Content-Range")

            val headers = StringBuilder()
            headers.append("HTTP/1.1 $code $statusText\r\n")
            headers.append("Content-Type: $contentType\r\n")
            if (contentLength >= 0) {
                headers.append("Content-Length: $contentLength\r\n")
            }
            if (!contentRange.isNullOrBlank()) {
                headers.append("Content-Range: $contentRange\r\n")
            }
            headers.append("Accept-Ranges: bytes\r\n")
            headers.append("Access-Control-Allow-Origin: *\r\n")
            headers.append("Connection: close\r\n\r\n")

            out.write(headers.toString().toByteArray(StandardCharsets.US_ASCII))
            if (method != "HEAD") {
                resp.body?.byteStream()?.let { inputStream ->
                    val bufferedIn = BufferedInputStream(inputStream)
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = bufferedIn.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                    }
                }
            }
            out.flush()
        }
    }

    private fun writeSimpleResponse(
        out: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        explicitContentLength: Int = body.size
    ) {
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $explicitContentLength\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }
}
