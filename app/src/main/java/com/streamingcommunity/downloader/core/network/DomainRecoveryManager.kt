package com.streamingcommunity.downloader.core.network

import com.streamingcommunity.downloader.core.model.DomainCandidate
import com.streamingcommunity.downloader.core.model.DomainCheckResult
import com.streamingcommunity.downloader.core.model.RejectedCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

object DomainRecoveryManager {

    const val CANDIDATES_URL = "https://telegra.ph/Link-Aggiornato-StreamingCommunity-09-29"
    private val NAME_PATTERN = Regex("""^streaming(community|unity)[a-z0-9-]{0,12}$""")
    private val HOST_RE = Regex("""^[a-z0-9.-]{4,253}$""")
    private val IPV4_LITERAL_RE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val LOCAL_SUFFIXES = listOf(".local", ".internal", ".localhost", ".home", ".lan")
    private const val MAX_CANDIDATES = 5
    private const val MIN_CHECK_INTERVAL_MS = 600_000L

    private val lock = Any()
    private var lastCheckAtMs: Long? = null

    private val _pendingCandidate = MutableStateFlow<DomainCandidate?>(null)
    val pendingCandidate: StateFlow<DomainCandidate?> = _pendingCandidate.asStateFlow()

    fun clearPending() {
        _pendingCandidate.value = null
    }

    fun fetchCandidates(url: String = CANDIDATES_URL): List<String> {
        val headers = mapOf("User-Agent" to HttpClientProvider.randomUserAgent())
        HttpClientProvider.executeGet(url, headers).use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Cannot fetch domain candidates: HTTP ${resp.code}")
            }
            val html = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(html)
            val hosts = mutableListOf<String>()

            for (anchor in doc.select("a[href]")) {
                val href = anchor.attr("href").trim()
                if (href.isEmpty()) continue
                val uri = try {
                    URI(href)
                } catch (_: Exception) {
                    continue
                }
                if (uri.scheme?.lowercase() != "https") continue
                val host = uri.host?.lowercase()?.trim('.') ?: continue
                if (host.isNotEmpty() && host !in hosts) {
                    hosts.add(host)
                    if (hosts.size >= MAX_CANDIDATES) break
                }
            }
            return hosts
        }
    }

    fun checkShape(host: String): String? {
        if (host.isEmpty() || !HOST_RE.matches(host)) {
            return "forma non valida"
        }
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) {
            return "nome locale"
        }
        if (IPV4_LITERAL_RE.matches(host) || ":" in host) {
            return "indirizzo IP"
        }

        var labels = host.split(".")
        if (labels.size == 3 && labels[0] == "www") {
            labels = labels.drop(1)
        }
        if (labels.size != 2) {
            return "non è un dominio di secondo livello"
        }

        val name = labels[0]
        if (!NAME_PATTERN.matches(name)) {
            return "nome «$name» non riconosciuto"
        }
        return null
    }

    private fun isPublicAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isMulticastAddress ||
            addr.isAnyLocalAddress
        ) {
            return false
        }
        if (addr is Inet4Address) {
            val bytes = addr.address
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // CGNAT 100.64.0.0/10, 0.0.0.0/8, Benchmarking 198.18.0.0/15, Reserved >= 240.0.0.0
            if (b0 == 0 || b0 == 127 || b0 >= 224) return false
            if (b0 == 100 && (b1 in 64..127)) return false
            if (b0 == 192 && b1 == 0) return false
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false
        }
        return true
    }

    fun resolvesToPublic(host: String): Boolean {
        return try {
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) return false
            addresses.all { isPublicAddress(it) }
        } catch (e: Exception) {
            false
        }
    }

    fun isPlausible(host: String): Pair<Boolean, String> {
        val reason = checkShape(host)
        if (reason != null) return Pair(false, reason)
        if (!resolvesToPublic(host)) {
            return Pair(false, "risolve a un indirizzo non pubblico")
        }
        return Pair(true, "")
    }

    suspend fun verify(host: String): String? {
        return try {
            val version = StreamingCommunityClient.getDomainVersion(host)
            version?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun verifyAndApplyCandidate(
        host: String,
        onSaveDomain: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val (ok, reason) = isPlausible(host)
        if (!ok) {
            throw RuntimeException("Dominio non adottabile: $reason")
        }
        val version = verify(host)
            ?: throw RuntimeException("«$host» non risponde come la sorgente attesa")

        onSaveDomain(host)
        StreamingCommunityClient.clearCaches()
        clearPending()
        version
    }

    suspend fun runCheck(
        currentDomain: String,
        autoApply: Boolean,
        force: Boolean = false,
        onSaveDomain: suspend (String) -> Unit
    ): DomainCheckResult = withContext(Dispatchers.IO) {
        val trimmedCurrent = currentDomain.trim()
        val now = System.currentTimeMillis()

        if (!force) {
            synchronized(lock) {
                val last = lastCheckAtMs
                if (last != null && now - last < MIN_CHECK_INTERVAL_MS) {
                    return@withContext DomainCheckResult(current = trimmedCurrent, checked = false)
                }
            }
        }

        synchronized(lock) {
            lastCheckAtMs = now
        }

        val currentOk = if (trimmedCurrent.isNotEmpty()) {
            verify(trimmedCurrent) != null
        } else {
            false
        }

        if (currentOk && !force) {
            clearPending()
            return@withContext DomainCheckResult(
                current = trimmedCurrent,
                currentOk = true,
                checked = true
            )
        }

        val candidates = try {
            fetchCandidates()
        } catch (e: Exception) {
            return@withContext DomainCheckResult(
                current = trimmedCurrent,
                currentOk = currentOk,
                checked = true
            )
        }

        val rejected = mutableListOf<RejectedCandidate>()
        var foundCandidate: String? = null
        var foundVersion: String? = null

        for (host in candidates) {
            if (host == trimmedCurrent) continue
            val (ok, reason) = isPlausible(host)
            if (!ok) {
                rejected.add(RejectedCandidate(host, reason))
                continue
            }
            val version = verify(host)
            if (version == null) {
                rejected.add(RejectedCandidate(host, "non verificato"))
                continue
            }
            foundCandidate = host
            foundVersion = version
            break
        }

        if (foundCandidate == null || foundVersion == null) {
            return@withContext DomainCheckResult(
                current = trimmedCurrent,
                currentOk = currentOk,
                rejected = rejected,
                checked = true
            )
        }

        if (autoApply) {
            try {
                verifyAndApplyCandidate(foundCandidate, onSaveDomain)
                return@withContext DomainCheckResult(
                    current = foundCandidate,
                    currentOk = true,
                    candidate = foundCandidate,
                    version = foundVersion,
                    applied = true,
                    rejected = rejected,
                    checked = true
                )
            } catch (_: Exception) {
            }
        }

        _pendingCandidate.value = DomainCandidate(
            host = foundCandidate,
            version = foundVersion,
            foundAtEpochMs = System.currentTimeMillis()
        )

        DomainCheckResult(
            current = trimmedCurrent,
            currentOk = currentOk,
            candidate = foundCandidate,
            version = foundVersion,
            applied = false,
            rejected = rejected,
            checked = true
        )
    }
}
