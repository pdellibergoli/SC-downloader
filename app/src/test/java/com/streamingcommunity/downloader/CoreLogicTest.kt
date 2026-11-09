package com.streamingcommunity.downloader

import com.streamingcommunity.downloader.core.hls.AdaptiveLimiter
import com.streamingcommunity.downloader.core.hls.AesSegmentDecryptor
import com.streamingcommunity.downloader.core.hls.M3u8Parser
import com.streamingcommunity.downloader.core.naming.NamingEngine
import com.streamingcommunity.downloader.core.network.AnimeUnityClient
import com.streamingcommunity.downloader.core.network.AppUpdateManager
import com.streamingcommunity.downloader.core.network.DomainRecoveryManager
import com.streamingcommunity.downloader.core.network.StreamingCommunityClient
import com.streamingcommunity.downloader.core.network.VixcloudParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CoreLogicTest {

    @Test
    fun testM3u8MasterPlaylistParsing() {
        val sampleMaster = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="ita",NAME="Italian",DEFAULT=YES,URI="https://vixcloud.cc/playlist/audio/ita.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="eng",NAME="English",DEFAULT=NO,URI="audio/eng.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="ita",NAME="Italian",DEFAULT=NO,URI="subs/ita.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,AUDIO="audio",SUBTITLES="subs"
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
            https://vixcloud.cc/playlist/1080p/index.m3u8
        """.trimIndent()

        val parser = M3u8Parser()
        parser.parseData(sampleMaster, "https://vixcloud.cc/playlist/master.m3u8")

        assertEquals(2, parser.videoPlaylists.size)
        assertEquals("https://vixcloud.cc/playlist/1080p/index.m3u8", parser.getBestQuality())

        val langs = parser.availableLanguages()
        assertEquals(listOf("ita", "eng"), langs.audio)
        assertEquals(listOf("ita"), langs.subtitles)
        assertEquals("https://vixcloud.cc/playlist/audio/ita.m3u8", parser.getAudioTrackUrl("ita"))
        assertEquals("https://vixcloud.cc/playlist/audio/eng.m3u8", parser.getAudioTrackUrl("eng"))
    }

    @Test
    fun testAesCbcSegmentDecryption() {
        val keyBytes = ByteArray(16) { (it + 1).toByte() }
        val ivHex = "0x000102030405060708090a0b0c0d0e0f"
        val ivBytes = AesSegmentDecryptor.hexToBytes(ivHex)
        val plain = "MPEG-TS-SEGMENT-PAYLOAD-TEST-1234567890".toByteArray()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val encrypted = cipher.doFinal(plain)

        val decryptor = AesSegmentDecryptor(keyBytes)
        decryptor.parseIv(ivHex)
        val decrypted = decryptor.decryptTs(encrypted, 0)

        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun testAdaptiveLimiterAimd() {
        var fakeClock = 10_000L
        val limiter = AdaptiveLimiter(maximum = 16, clockMs = { fakeClock })
        assertEquals(16, limiter.allowance)

        limiter.penalise()
        assertEquals(8, limiter.allowance)

        // Within PENALTY_INTERVAL_MS, second penalty is ignored
        fakeClock += 500L
        limiter.penalise()
        assertEquals(8, limiter.allowance)

        // After PENALTY_INTERVAL_MS, halves again
        fakeClock += 2_000L
        limiter.penalise()
        assertEquals(4, limiter.allowance)

        // 4 rewards bring allowance from 4 to 5
        repeat(4) { limiter.reward() }
        assertEquals(5, limiter.allowance)
    }

    @Test
    fun testNamingEngineTemplatesAndPaths() {
        val withYear = NamingEngine.filmPath("/storage/Download", "Matrix", "1999")
        assertEquals("/storage/Download/Movies/Matrix (1999)/Matrix (1999).mp4", withYear)

        val withoutYear = NamingEngine.filmPath("/storage/Download", "Matrix", null)
        assertEquals("/storage/Download/Movies/Matrix/Matrix.mp4", withoutYear)

        val epPath = NamingEngine.episodePath("/storage/Download", "Breaking Bad", 2, "7", "2008", container = "mkv")
        assertEquals("/storage/Download/Series/Breaking Bad (2008)/Season 02/Breaking Bad S02E07.mkv", epPath)

        val animeEpPath = NamingEngine.animePath("/storage/Download", "One Piece", "1089", "TV", "1999")
        assertEquals("/storage/Download/Anime/One Piece (1999)/Season 01/One Piece S01E1089.mp4", animeEpPath)
    }

    @Test
    fun testVixcloudEmbedParsing() {
        val script = """
            window.video = {
                id: '123456',
                name: 'Sample Video'
            };
            window.streams = [];
            window.masterPlaylist = {
                params: {
                    'token': 'abc123xyz',
                    'expires': '1799999999',
                }
            };
        """.trimIndent()

        val urlEmbed = "https://vixcloud.co/embed/123456?canPlayFHD=1&lang=it"
        val (video, params) = VixcloudParser.parseEmbedContent(script, urlEmbed)

        assertEquals("123456", video.id)
        assertEquals("vixcloud.co", video.host)
        assertTrue(video.canPlayFhd)
        assertEquals("it", video.lang)
        assertEquals("abc123xyz", params.token)
        assertEquals("1799999999", params.expires)

        val m3u8Url = VixcloudParser.buildM3u8Url(video, params)
        assertEquals("https://vixcloud.co/playlist/123456?token=abc123xyz&expires=1799999999&h=1&lang=it", m3u8Url)
    }

    @Test
    fun testDomainRecoveryCheckShape() {
        assertNull(DomainRecoveryManager.checkShape("streamingcommunity.computer"))
        assertNull(DomainRecoveryManager.checkShape("www.streamingunity.co"))
        assertNotNull(DomainRecoveryManager.checkShape("192.168.1.1"))
        assertNotNull(DomainRecoveryManager.checkShape("streamingcommunity.attacker.tld"))
        assertNotNull(DomainRecoveryManager.checkShape("unrelated-site.com"))
    }

    @Test
    fun testInertiaAndAnimeNormalization() {
        val html = """
            <html><body>
            <div id="app" data-page="{&quot;version&quot;:&quot;abc123&quot;,&quot;props&quot;:{&quot;titles&quot;:[]}}"></div>
            </body></html>
        """.trimIndent()
        val parsed = StreamingCommunityClient.parseDataPage(html)
        assertEquals("abc123", parsed["version"]?.toString()?.trim('"'))

        val animeJson = Json.parseToJsonElement(
            """{"id":42,"title_eng":"Attack on Titan","slug":"attack-on-titan","type":"TV","episodes_count":25,"dub":1}"""
        ).jsonObject
        val norm = AnimeUnityClient.normalizeTitle(animeJson)
        assertNotNull(norm)
        assertEquals("42-attack-on-titan", norm!!.id)
        assertEquals("Attack on Titan", norm.name)
        assertTrue(norm.dubbed)
        assertTrue(norm.isSeries)
    }

    @Test
    fun testAppUpdateVersionComparisonAndParsing() {
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", "v1.0.1"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.2.0-beta1", "1.2.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.1", "v1.0.1"))
        assertFalse(AppUpdateManager.isNewerVersion("1.2.0", "v1.1.9"))

        val sampleReleaseJson = """
            {
              "tag_name": "v1.1.0",
              "name": "SC Downloader v1.1.0",
              "body": "- Filtro Home Film/Serie/Anime\n- Check aggiornamenti in-app",
              "html_url": "https://github.com/pdellibergoli/SC-downloader/releases/tag/v1.1.0",
              "published_at": "2026-11-09T12:00:00Z",
              "assets": [
                {
                  "name": "SC-Downloader-v1.1.0.apk",
                  "size": 15400123,
                  "browser_download_url": "https://github.com/pdellibergoli/SC-downloader/releases/download/v1.1.0/SC-Downloader-v1.1.0.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateManager.parseReleaseJson(sampleReleaseJson)
        assertNotNull(parsed)
        assertEquals("v1.1.0", parsed!!.tagName)
        assertEquals("1.1.0", parsed.versionName)
        assertEquals("SC-Downloader-v1.1.0.apk", parsed.apkFileName)
        assertEquals(
            "https://github.com/pdellibergoli/SC-downloader/releases/download/v1.1.0/SC-Downloader-v1.1.0.apk",
            parsed.apkDownloadUrl
        )
    }
}
