package com.streamingcommunity.downloader.core.hls

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object MpegTsRemuxer {

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)

    private class DirectByteArrayOutputStream(initialCapacity: Int = 256 * 1024) :
        ByteArrayOutputStream(initialCapacity) {
        fun rawBuffer(): ByteArray = buf
        fun length(): Int = count
    }

    private class TsProbeResult(
        var sps: ByteArray? = null,
        var pps: ByteArray? = null,
        var minVideoPtsUs: Long = Long.MAX_VALUE,
        var videoPtsSamplesCount: Int = 0,
        var frameDurationUs: Long = 40_000L, // default 25fps
        var aacCsd0: ByteArray? = null,
        var aacSampleRate: Int = 48000,
        var aacChannelCount: Int = 2,
        var minAudioPtsUs: Long = Long.MAX_VALUE
    )

    /**
     * Remuxes a concatenated MPEG-TS video file (and optional separate MPEG-TS audio tracks)
     * into the destination `.mp4` or `.mkv` file using a streaming 2-pass architecture
     * with constant ~2 MB memory footprint and exact B-frame presentation timestamps.
     */
    fun remuxToContainer(
        videoTsFile: File,
        externalAudioTsFiles: List<File>,
        subtitleFiles: List<File>,
        outputFile: File
    ): File {
        outputFile.parentFile?.mkdirs()

        // 1. Try system ffmpeg if present (e.g. Termux / custom environment)
        val ffmpegBin = findSystemFfmpeg()
        if (ffmpegBin != null) {
            val ok = runFfmpegRemux(ffmpegBin, videoTsFile, externalAudioTsFiles, subtitleFiles, outputFile)
            if (ok && outputFile.exists() && outputFile.length() > 0) {
                return outputFile
            }
        }

        // 2. Try native streaming Kotlin MPEG-TS demuxer + Android MediaMuxer (produces standard MP4)
        val tempMp4 = File(outputFile.parentFile, ".${outputFile.nameWithoutExtension}.muxing.mp4")
        try {
            val muxedOk = remuxWithStreamingTsDemuxer(
                videoTsFile = videoTsFile,
                audioTsFile = externalAudioTsFiles.firstOrNull(),
                outputFile = tempMp4
            ) || remuxWithMediaExtractor(
                videoTsFile = videoTsFile,
                audioTsFile = externalAudioTsFiles.firstOrNull(),
                outputFile = tempMp4
            )

            if (muxedOk && tempMp4.exists() && tempMp4.length() > 0) {
                if (outputFile.exists()) outputFile.delete()
                if (!tempMp4.renameTo(outputFile)) {
                    tempMp4.copyTo(outputFile, overwrite = true)
                    tempMp4.delete()
                }
                return outputFile
            }
        } catch (t: Throwable) {
            tempMp4.delete()
        }

        // 3. Safe fallback: move/copy the decrypted concatenated MPEG-TS stream directly
        if (outputFile.exists()) outputFile.delete()
        if (!videoTsFile.renameTo(outputFile)) {
            videoTsFile.copyTo(outputFile, overwrite = true)
        }
        return outputFile
    }

    private fun findSystemFfmpeg(): String? {
        val envPath = System.getenv("FFMPEG_PATH")
        if (!envPath.isNullOrBlank() && File(envPath).canExecute()) {
            return envPath
        }
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/ffmpeg",
            "/system/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/opt/homebrew/bin/ffmpeg",
            "/usr/bin/ffmpeg"
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun runFfmpegRemux(
        ffmpegBin: String,
        videoTsFile: File,
        externalAudioTsFiles: List<File>,
        subtitleFiles: List<File>,
        outputFile: File
    ): Boolean {
        return try {
            val isMkv = outputFile.extension.equals("mkv", ignoreCase = true)
            val staged = File(outputFile.parentFile, ".${outputFile.nameWithoutExtension}.remuxing.${outputFile.extension}")
            val cmd = mutableListOf(
                ffmpegBin, "-y",
                "-fflags", "+genpts",
                "-avoid_negative_ts", "make_zero",
                "-i", videoTsFile.absolutePath
            )
            for (audio in externalAudioTsFiles) {
                cmd.addAll(listOf("-i", audio.absolutePath))
            }
            for (sub in subtitleFiles) {
                cmd.addAll(listOf("-i", sub.absolutePath))
            }
            cmd.addAll(listOf("-map", "0:v:0"))
            if (externalAudioTsFiles.isNotEmpty()) {
                for (i in externalAudioTsFiles.indices) {
                    cmd.addAll(listOf("-map", "${i + 1}:a:0"))
                }
            } else {
                cmd.addAll(listOf("-map", "0:a?"))
            }
            for (i in subtitleFiles.indices) {
                cmd.addAll(listOf("-map", "${externalAudioTsFiles.size + 1 + i}:0"))
            }
            cmd.addAll(listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "192k"))
            if (subtitleFiles.isNotEmpty()) {
                cmd.addAll(listOf("-c:s", if (isMkv) "copy" else "mov_text"))
            }
            if (!isMkv) {
                cmd.addAll(listOf("-movflags", "+faststart"))
            }
            cmd.add(staged.absolutePath)

            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val exitCode = process.waitFor()
            if (exitCode == 0 && staged.exists() && staged.length() > 0) {
                if (outputFile.exists()) outputFile.delete()
                staged.renameTo(outputFile)
                true
            } else {
                staged.delete()
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Pass 1: Quickly probes the first GOPs of the TS file(s) for H.264 SPS/PPS, minimum PTS
     * (accounting for open-GOP B-frames), frame duration, and AAC CSD-0.
     * Pass 2: Streams PES packets directly from disk into Android MediaMuxer using a single
     * reusable direct ByteBuffer while preserving exact B-frame presentation timestamps.
     */
    private fun remuxWithStreamingTsDemuxer(
        videoTsFile: File,
        audioTsFile: File?,
        outputFile: File
    ): Boolean {
        val hasExternalAudio = audioTsFile != null && audioTsFile.exists() && audioTsFile.length() > 0
        val videoProbe = probeTsFile(
            tsFile = videoTsFile,
            needVideo = true,
            needAudio = !hasExternalAudio
        )
        if (videoProbe.sps == null || videoProbe.pps == null) {
            return false
        }

        val audioProbe = if (hasExternalAudio) {
            probeTsFile(
                tsFile = audioTsFile!!,
                needVideo = false,
                needAudio = true
            )
        } else {
            videoProbe
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return try {
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(videoProbe.sps!!))
                setByteBuffer("csd-1", ByteBuffer.wrap(videoProbe.pps!!))
            }
            val videoTrackIndex = muxer.addTrack(videoFormat)

            var audioTrackIndex = -1
            if (audioProbe.aacCsd0 != null) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    audioProbe.aacSampleRate,
                    audioProbe.aacChannelCount
                ).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(audioProbe.aacCsd0!!))
                }
                audioTrackIndex = muxer.addTrack(audioFormat)
            }

            muxer.start()

            val minVideoPts = if (videoProbe.minVideoPtsUs != Long.MAX_VALUE) {
                videoProbe.minVideoPtsUs
            } else {
                0L
            }
            val minAudioPts = if (audioProbe.minAudioPtsUs != Long.MAX_VALUE) {
                audioProbe.minAudioPtsUs
            } else {
                minVideoPts
            }

            val writtenVideoSamples: Int
            if (hasExternalAudio) {
                writtenVideoSamples = streamTsFileToMuxer(
                    tsFile = videoTsFile,
                    muxer = muxer,
                    videoTrackIndex = videoTrackIndex,
                    audioTrackIndex = -1,
                    minVideoPts = minVideoPts,
                    minAudioPts = minAudioPts,
                    frameDurationUs = videoProbe.frameDurationUs,
                    sampleRate = audioProbe.aacSampleRate
                )
                if (audioTrackIndex >= 0) {
                    streamTsFileToMuxer(
                        tsFile = audioTsFile!!,
                        muxer = muxer,
                        videoTrackIndex = -1,
                        audioTrackIndex = audioTrackIndex,
                        minVideoPts = minVideoPts,
                        minAudioPts = minAudioPts,
                        frameDurationUs = videoProbe.frameDurationUs,
                        sampleRate = audioProbe.aacSampleRate
                    )
                }
            } else {
                writtenVideoSamples = streamTsFileToMuxer(
                    tsFile = videoTsFile,
                    muxer = muxer,
                    videoTrackIndex = videoTrackIndex,
                    audioTrackIndex = audioTrackIndex,
                    minVideoPts = minVideoPts,
                    minAudioPts = minAudioPts,
                    frameDurationUs = videoProbe.frameDurationUs,
                    sampleRate = audioProbe.aacSampleRate
                )
            }

            muxer.stop()
            writtenVideoSamples > 0
        } catch (t: Throwable) {
            false
        } finally {
            try {
                muxer.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Reads the next 188-byte MPEG-TS packet starting with 0x47 sync byte.
     * If there are any stray padding/ID3 bytes between segments, automatically resynchronizes
     * to the next 0x47 byte so no TS packets are lost due to alignment shifts.
     */
    private fun readSyncedTsPacket(input: BufferedInputStream, packet: ByteArray): Boolean {
        while (true) {
            val first = input.read()
            if (first < 0) return false
            if (first == 0x47) {
                packet[0] = 0x47.toByte()
                var total = 1
                while (total < 188) {
                    val n = input.read(packet, total, 188 - total)
                    if (n <= 0) return false
                    total += n
                }
                return true
            }
        }
    }

    private fun probeTsFile(
        tsFile: File,
        needVideo: Boolean,
        needAudio: Boolean
    ): TsProbeResult {
        val probe = TsProbeResult()
        var pmtPid = -1
        var videoPid = -1
        var audioPid = -1

        val videoPesBuffer = DirectByteArrayOutputStream(128 * 1024)
        var videoCurrentPtsUs = -1L
        val collectedVideoPts = mutableListOf<Long>()

        val audioPesBuffer = DirectByteArrayOutputStream(32 * 1024)
        var audioCurrentPtsUs = -1L

        val packet = ByteArray(188)
        var packetsRead = 0
        val maxProbePackets = 60_000 // ~11 MB max scan for headers

        BufferedInputStream(FileInputStream(tsFile), 64 * 1024).use { input ->
            while (packetsRead < maxProbePackets) {
                if (!readSyncedTsPacket(input, packet)) break
                packetsRead++

                val pusi = (packet[1].toInt() and 0x40) != 0
                val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                val adaptationControl = (packet[3].toInt() ushr 4) and 0x03

                var offset = 4
                if (adaptationControl == 2 || adaptationControl == 3) {
                    val adaptationLength = packet[4].toInt() and 0xFF
                    offset += 1 + adaptationLength
                }
                if (adaptationControl == 2 || offset >= 188) continue

                // Fallback PID discovery from PES stream_id if PAT/PMT was not yet seen
                if (pusi && offset + 4 <= 188 &&
                    (packet[offset].toInt() and 0xFF) == 0x00 &&
                    (packet[offset + 1].toInt() and 0xFF) == 0x00 &&
                    (packet[offset + 2].toInt() and 0xFF) == 0x01
                ) {
                    val streamId = packet[offset + 3].toInt() and 0xFF
                    if (needVideo && videoPid == -1 && (streamId and 0xF0) == 0xE0) {
                        videoPid = pid
                    } else if (needAudio && audioPid == -1 && (streamId and 0xE0) == 0xC0) {
                        audioPid = pid
                    }
                }

                when (pid) {
                    0 -> {
                        val parsedPmt = parsePatPacket(packet, offset, pusi)
                        if (parsedPmt != -1) pmtPid = parsedPmt
                    }
                    pmtPid -> {
                        val pids = parsePmtPacket(packet, offset, pusi)
                        if (pids.first != -1) videoPid = pids.first
                        if (pids.second != -1) audioPid = pids.second
                    }
                    videoPid -> if (needVideo && videoPid != -1) {
                        if (pusi) {
                            if (videoPesBuffer.length() > 0) {
                                probeH264Pes(
                                    videoPesBuffer.rawBuffer(),
                                    videoPesBuffer.length(),
                                    videoCurrentPtsUs,
                                    probe,
                                    collectedVideoPts
                                )
                                videoPesBuffer.reset()
                            }
                            val pesHeader = parsePesHeader(packet, offset)
                            if (pesHeader != null) {
                                if (pesHeader.second >= 0) videoCurrentPtsUs = pesHeader.second
                                val payloadStart = pesHeader.first
                                if (payloadStart < 188) {
                                    videoPesBuffer.write(packet, payloadStart, 188 - payloadStart)
                                }
                            }
                        } else if (videoPesBuffer.length() > 0) {
                            videoPesBuffer.write(packet, offset, 188 - offset)
                        }
                    }
                    audioPid -> if (needAudio && audioPid != -1) {
                        if (pusi) {
                            if (audioPesBuffer.length() > 0) {
                                probeAdtsPes(audioPesBuffer.rawBuffer(), audioPesBuffer.length(), audioCurrentPtsUs, probe)
                                audioPesBuffer.reset()
                            }
                            val pesHeader = parsePesHeader(packet, offset)
                            if (pesHeader != null) {
                                if (pesHeader.second >= 0) audioCurrentPtsUs = pesHeader.second
                                val payloadStart = pesHeader.first
                                if (payloadStart < 188) {
                                    audioPesBuffer.write(packet, payloadStart, 188 - payloadStart)
                                }
                            }
                        } else if (audioPesBuffer.length() > 0) {
                            audioPesBuffer.write(packet, offset, 188 - offset)
                        }
                    }
                }

                // Wait for at least 30 video PTS samples so open-GOP B-frames are captured in minVideoPtsUs
                val videoDone = !needVideo ||
                    (probe.sps != null && probe.pps != null && probe.videoPtsSamplesCount >= 30)
                val audioDone = !needAudio ||
                    (probe.aacCsd0 != null && probe.minAudioPtsUs != Long.MAX_VALUE)
                if (videoDone && audioDone) {
                    break
                }
            }
        }

        if (needVideo && videoPesBuffer.length() > 0 && (probe.sps == null || probe.pps == null)) {
            probeH264Pes(
                videoPesBuffer.rawBuffer(),
                videoPesBuffer.length(),
                videoCurrentPtsUs,
                probe,
                collectedVideoPts
            )
        }
        if (needAudio && audioPesBuffer.length() > 0 && probe.aacCsd0 == null) {
            probeAdtsPes(audioPesBuffer.rawBuffer(), audioPesBuffer.length(), audioCurrentPtsUs, probe)
        }

        if (collectedVideoPts.size >= 4) {
            val sorted = collectedVideoPts.sorted()
            val deltas = (1 until sorted.size).map { sorted[it] - sorted[it - 1] }.filter { it in 10_000L..100_000L }
            if (deltas.isNotEmpty()) {
                probe.frameDurationUs = deltas.sorted()[deltas.size / 2]
            }
        }

        return probe
    }

    private fun streamTsFileToMuxer(
        tsFile: File,
        muxer: MediaMuxer,
        videoTrackIndex: Int,
        audioTrackIndex: Int,
        minVideoPts: Long,
        minAudioPts: Long,
        frameDurationUs: Long,
        sampleRate: Int
    ): Int {
        val streamVideo = videoTrackIndex >= 0
        val streamAudio = audioTrackIndex >= 0

        var pmtPid = -1
        var videoPid = -1
        var audioPid = -1

        val videoPesBuffer = DirectByteArrayOutputStream(256 * 1024)
        var videoCurrentPtsUs = -1L
        var fallbackNextVideoPtsUs = 0L
        var videoSamplesWritten = 0

        // Unwrap 33-bit 90kHz PTS if wraparound occurs (~26.5 hours)
        var videoPtsWrapOffsetUs = 0L
        var prevRawVideoPtsUs = -1L

        val audioPesBuffer = DirectByteArrayOutputStream(64 * 1024)
        var audioCurrentPtsUs = minAudioPts
        var lastAudioPts = -1L

        var sampleBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        val packet = ByteArray(188)

        fun unwrapVideoPts(rawPtsUs: Long): Long {
            if (rawPtsUs < 0) return -1L
            if (prevRawVideoPtsUs > 90_000_000_000L && rawPtsUs < 5_000_000_000L) {
                videoPtsWrapOffsetUs += (1L shl 33) * 1_000_000L / 90_000L
            }
            prevRawVideoPtsUs = rawPtsUs
            return rawPtsUs + videoPtsWrapOffsetUs
        }

        fun flushCurrentVideoPes() {
            val len = videoPesBuffer.length()
            if (len <= 0) return
            val raw = videoPesBuffer.rawBuffer()

            if (sampleBuffer.capacity() < len + 1024) {
                sampleBuffer = ByteBuffer.allocateDirect((len + 65536).coerceAtLeast(sampleBuffer.capacity() * 2))
            }

            // Compute base PTS for this PES packet WITHOUT clobbering B-frame out-of-order PTS!
            val basePts = if (videoCurrentPtsUs >= 0L) {
                (videoCurrentPtsUs - minVideoPts).coerceAtLeast(0L)
            } else {
                fallbackNextVideoPtsUs
            }

            val framesWritten = writeH264AccessUnitsToMuxer(
                data = raw,
                dataLen = len,
                basePtsUs = basePts,
                frameDurationUs = frameDurationUs,
                muxer = muxer,
                videoTrackIndex = videoTrackIndex,
                sampleBuffer = sampleBuffer,
                bufferInfo = bufferInfo
            )
            if (framesWritten > 0) {
                videoSamplesWritten += framesWritten
                fallbackNextVideoPtsUs = maxOf(fallbackNextVideoPtsUs, basePts + framesWritten * frameDurationUs)
            }
            videoPesBuffer.reset()
        }

        fun flushCurrentAudioPes() {
            val len = audioPesBuffer.length()
            if (len <= 0) return
            val raw = audioPesBuffer.rawBuffer()
            lastAudioPts = writeAdtsFramesToMuxer(
                data = raw,
                dataLen = len,
                basePtsUs = audioCurrentPtsUs,
                minAudioPts = minAudioPts,
                lastAudioPtsInitial = lastAudioPts,
                defaultSampleRate = sampleRate,
                muxer = muxer,
                audioTrackIndex = audioTrackIndex,
                sampleBuffer = sampleBuffer,
                bufferInfo = bufferInfo
            )
            audioPesBuffer.reset()
        }

        BufferedInputStream(FileInputStream(tsFile), 128 * 1024).use { input ->
            while (readSyncedTsPacket(input, packet)) {
                val pusi = (packet[1].toInt() and 0x40) != 0
                val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                val adaptationControl = (packet[3].toInt() ushr 4) and 0x03

                var offset = 4
                if (adaptationControl == 2 || adaptationControl == 3) {
                    val adaptationLength = packet[4].toInt() and 0xFF
                    offset += 1 + adaptationLength
                }
                if (adaptationControl == 2 || offset >= 188) continue

                if (pusi && offset + 4 <= 188 &&
                    (packet[offset].toInt() and 0xFF) == 0x00 &&
                    (packet[offset + 1].toInt() and 0xFF) == 0x00 &&
                    (packet[offset + 2].toInt() and 0xFF) == 0x01
                ) {
                    val streamId = packet[offset + 3].toInt() and 0xFF
                    if (streamVideo && videoPid == -1 && (streamId and 0xF0) == 0xE0) {
                        videoPid = pid
                    } else if (streamAudio && audioPid == -1 && (streamId and 0xE0) == 0xC0) {
                        audioPid = pid
                    }
                }

                when (pid) {
                    0 -> {
                        val parsedPmt = parsePatPacket(packet, offset, pusi)
                        if (parsedPmt != -1) pmtPid = parsedPmt
                    }
                    pmtPid -> {
                        val pids = parsePmtPacket(packet, offset, pusi)
                        if (pids.first != -1) videoPid = pids.first
                        if (pids.second != -1) audioPid = pids.second
                    }
                    videoPid -> if (streamVideo && videoPid != -1) {
                        if (pusi) {
                            flushCurrentVideoPes()
                            val pesHeader = parsePesHeader(packet, offset)
                            if (pesHeader != null) {
                                videoCurrentPtsUs = unwrapVideoPts(pesHeader.second)
                                val payloadStart = pesHeader.first
                                if (payloadStart < 188) {
                                    videoPesBuffer.write(packet, payloadStart, 188 - payloadStart)
                                }
                            }
                        } else if (videoPesBuffer.length() > 0) {
                            videoPesBuffer.write(packet, offset, 188 - offset)
                        }
                    }
                    audioPid -> if (streamAudio && audioPid != -1) {
                        if (pusi) {
                            flushCurrentAudioPes()
                            val pesHeader = parsePesHeader(packet, offset)
                            if (pesHeader != null) {
                                audioCurrentPtsUs = pesHeader.second
                                val payloadStart = pesHeader.first
                                if (payloadStart < 188) {
                                    audioPesBuffer.write(packet, payloadStart, 188 - payloadStart)
                                }
                            }
                        } else if (audioPesBuffer.length() > 0) {
                            audioPesBuffer.write(packet, offset, 188 - offset)
                        }
                    }
                }
            }
        }

        if (streamVideo && videoPesBuffer.length() > 0) {
            flushCurrentVideoPes()
        }
        if (streamAudio && audioPesBuffer.length() > 0) {
            flushCurrentAudioPes()
        }

        return videoSamplesWritten
    }

    private fun parsePatPacket(packet: ByteArray, offset: Int, pusi: Boolean): Int {
        var secOffset = offset
        if (pusi) {
            val pointerField = packet[secOffset].toInt() and 0xFF
            secOffset += 1 + pointerField
        }
        if (secOffset + 12 <= 188 && (packet[secOffset].toInt() and 0xFF) == 0x00) {
            val sectionLen = ((packet[secOffset + 1].toInt() and 0x0F) shl 8) or
                (packet[secOffset + 2].toInt() and 0xFF)
            val end = (secOffset + 3 + sectionLen - 4).coerceAtMost(188)
            var pos = secOffset + 8
            while (pos + 4 <= end) {
                val progNum = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                val pPid = ((packet[pos + 2].toInt() and 0x1F) shl 8) or (packet[pos + 3].toInt() and 0xFF)
                if (progNum != 0) {
                    return pPid
                }
                pos += 4
            }
        }
        return -1
    }

    private fun parsePmtPacket(packet: ByteArray, offset: Int, pusi: Boolean): Pair<Int, Int> {
        var videoPid = -1
        var audioPid = -1
        var secOffset = offset
        if (pusi) {
            val pointerField = packet[secOffset].toInt() and 0xFF
            secOffset += 1 + pointerField
        }
        if (secOffset + 12 <= 188 && (packet[secOffset].toInt() and 0xFF) == 0x02) {
            val sectionLen = ((packet[secOffset + 1].toInt() and 0x0F) shl 8) or
                (packet[secOffset + 2].toInt() and 0xFF)
            val progInfoLen = ((packet[secOffset + 10].toInt() and 0x0F) shl 8) or
                (packet[secOffset + 11].toInt() and 0xFF)
            val end = (secOffset + 3 + sectionLen - 4).coerceAtMost(188)
            var pos = secOffset + 12 + progInfoLen
            while (pos + 5 <= end) {
                val streamType = packet[pos].toInt() and 0xFF
                val elemPid = ((packet[pos + 1].toInt() and 0x1F) shl 8) or (packet[pos + 2].toInt() and 0xFF)
                val esInfoLen = ((packet[pos + 3].toInt() and 0x0F) shl 8) or (packet[pos + 4].toInt() and 0xFF)
                if (streamType == 0x1B || streamType == 0x24) {
                    videoPid = elemPid
                } else if (streamType == 0x0F || streamType == 0x11 || streamType == 0x03 || streamType == 0x04) {
                    audioPid = elemPid
                }
                pos += 5 + esInfoLen
            }
        }
        return Pair(videoPid, audioPid)
    }

    private fun parsePesHeader(packet: ByteArray, offset: Int): Pair<Int, Long>? {
        if (offset + 9 > 188) return null
        if ((packet[offset].toInt() and 0xFF) != 0x00 ||
            (packet[offset + 1].toInt() and 0xFF) != 0x00 ||
            (packet[offset + 2].toInt() and 0xFF) != 0x01
        ) {
            return null
        }
        val flags2 = packet[offset + 7].toInt() and 0xFF
        val headerDataLen = packet[offset + 8].toInt() and 0xFF
        val payloadStart = offset + 9 + headerDataLen
        var ptsUs = -1L

        if ((flags2 and 0x80) != 0 && offset + 14 <= 188) {
            val b0 = (packet[offset + 9].toLong() and 0x0E) shl 29
            val b1 = (packet[offset + 10].toLong() and 0xFF) shl 22
            val b2 = (packet[offset + 11].toLong() and 0xFE) shl 14
            val b3 = (packet[offset + 12].toLong() and 0xFF) shl 7
            val b4 = (packet[offset + 13].toLong() and 0xFE) ushr 1
            val pts90k = b0 or b1 or b2 or b3 or b4
            ptsUs = (pts90k * 1_000_000L) / 90_000L
        }
        return Pair(payloadStart, ptsUs)
    }

    private fun findAnnexBStartCode(data: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i <= limit - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) return i
                if (i <= limit - 4 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
            }
            i++
        }
        return -1
    }

    private fun probeH264Pes(
        data: ByteArray,
        dataLen: Int,
        ptsUs: Long,
        probe: TsProbeResult,
        collectedVideoPts: MutableList<Long>
    ) {
        if (ptsUs >= 0L) {
            if (ptsUs < probe.minVideoPtsUs) {
                probe.minVideoPtsUs = ptsUs
            }
            probe.videoPtsSamplesCount++
            if (collectedVideoPts.size < 60) {
                collectedVideoPts.add(ptsUs)
            }
        }
        var scPos = findAnnexBStartCode(data, 0, dataLen)
        while (scPos >= 0) {
            val scLen = if (data[scPos + 2].toInt() == 1) 3 else 4
            val nalStart = scPos + scLen
            val nextScPos = findAnnexBStartCode(data, nalStart, dataLen)
            val nalEnd = if (nextScPos >= 0) nextScPos else dataLen

            if (nalStart < nalEnd) {
                val nalType = data[nalStart].toInt() and 0x1F
                if (nalType == 7 && probe.sps == null) {
                    val nalLen = nalEnd - nalStart
                    val spsBytes = ByteArray(4 + nalLen)
                    System.arraycopy(START_CODE_4, 0, spsBytes, 0, 4)
                    System.arraycopy(data, nalStart, spsBytes, 4, nalLen)
                    probe.sps = spsBytes
                } else if (nalType == 8 && probe.pps == null) {
                    val nalLen = nalEnd - nalStart
                    val ppsBytes = ByteArray(4 + nalLen)
                    System.arraycopy(START_CODE_4, 0, ppsBytes, 0, 4)
                    System.arraycopy(data, nalStart, ppsBytes, 4, nalLen)
                    probe.pps = ppsBytes
                }
            }
            scPos = nextScPos
        }
    }

    /**
     * Writes H.264 access units from [data] (0 until [dataLen]) into [muxer].
     * Splits on Access Unit Delimiters (nalType == 9) if multiple frames are packed into a single PES packet,
     * and preserves exact B-frame presentation timestamps.
     */
    private fun writeH264AccessUnitsToMuxer(
        data: ByteArray,
        dataLen: Int,
        basePtsUs: Long,
        frameDurationUs: Long,
        muxer: MediaMuxer,
        videoTrackIndex: Int,
        sampleBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ): Int {
        var framesWritten = 0
        var hasVclInCurrentAu = false
        var isCurrentAuKeyFrame = false
        sampleBuffer.clear()

        fun emitCurrentAu() {
            if (hasVclInCurrentAu && sampleBuffer.position() > 0) {
                sampleBuffer.flip()
                val pts = basePtsUs + framesWritten * frameDurationUs
                bufferInfo.set(
                    0,
                    sampleBuffer.limit(),
                    pts,
                    if (isCurrentAuKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
                muxer.writeSampleData(videoTrackIndex, sampleBuffer, bufferInfo)
                framesWritten++
            }
            sampleBuffer.clear()
            hasVclInCurrentAu = false
            isCurrentAuKeyFrame = false
        }

        var scPos = findAnnexBStartCode(data, 0, dataLen)
        while (scPos >= 0) {
            val scLen = if (data[scPos + 2].toInt() == 1) 3 else 4
            val nalStart = scPos + scLen
            val nextScPos = findAnnexBStartCode(data, nalStart, dataLen)
            val nalEnd = if (nextScPos >= 0) nextScPos else dataLen

            if (nalStart < nalEnd) {
                val nalType = data[nalStart].toInt() and 0x1F
                if (nalType == 9 && hasVclInCurrentAu) {
                    // Access Unit Delimiter marks start of a new frame within the same PES
                    emitCurrentAu()
                } else if (nalType == 1 || nalType == 5 || nalType == 6 || nalType == 7 || nalType == 8) {
                    if (nalType == 1 || nalType == 5) {
                        hasVclInCurrentAu = true
                    }
                    if (nalType == 5) {
                        isCurrentAuKeyFrame = true
                    }
                    val nalLen = nalEnd - nalStart
                    if (sampleBuffer.remaining() >= 4 + nalLen) {
                        sampleBuffer.put(START_CODE_4)
                        sampleBuffer.put(data, nalStart, nalLen)
                    }
                }
            }
            scPos = nextScPos
        }

        emitCurrentAu()
        return framesWritten
    }

    private fun probeAdtsPes(data: ByteArray, dataLen: Int, basePtsUs: Long, probe: TsProbeResult) {
        if (basePtsUs >= 0L && basePtsUs < probe.minAudioPtsUs) {
            probe.minAudioPtsUs = basePtsUs
        }
        var pos = 0
        while (pos + 7 <= dataLen) {
            if ((data[pos].toInt() and 0xFF) != 0xFF || (data[pos + 1].toInt() and 0xF6) != 0xF0) {
                pos++
                continue
            }
            val protectionAbsent = data[pos + 1].toInt() and 0x01
            val profile = ((data[pos + 2].toInt() ushr 6) and 0x03) + 1
            val freqIdx = (data[pos + 2].toInt() ushr 2) and 0x0F
            if (freqIdx > 12) {
                pos++
                continue
            }
            val channelCfg = ((data[pos + 2].toInt() and 0x01) shl 2) or
                ((data[pos + 3].toInt() ushr 6) and 0x03)

            val frameLen = ((data[pos + 3].toInt() and 0x03) shl 11) or
                ((data[pos + 4].toInt() and 0xFF) shl 3) or
                ((data[pos + 5].toInt() ushr 5) and 0x07)

            val headerLen = if (protectionAbsent == 1) 7 else 9
            if (frameLen <= headerLen || pos + frameLen > dataLen) {
                pos++
                continue
            }

            val sampleRate = SAMPLE_RATES.getOrElse(freqIdx) { 48000 }
            if (probe.aacCsd0 == null) {
                probe.aacSampleRate = sampleRate
                probe.aacChannelCount = if (channelCfg > 0) channelCfg else 2
                val csd0 = ((profile and 0x1F) shl 11) or
                    ((freqIdx and 0x0F) shl 7) or
                    ((probe.aacChannelCount and 0x0F) shl 3)
                probe.aacCsd0 = byteArrayOf(
                    ((csd0 ushr 8) and 0xFF).toByte(),
                    (csd0 and 0xFF).toByte()
                )
                return
            }
            pos += frameLen
        }
    }

    private fun writeAdtsFramesToMuxer(
        data: ByteArray,
        dataLen: Int,
        basePtsUs: Long,
        minAudioPts: Long,
        lastAudioPtsInitial: Long,
        defaultSampleRate: Int,
        muxer: MediaMuxer,
        audioTrackIndex: Int,
        sampleBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ): Long {
        var pos = 0
        var frameIdx = 0
        var lastAudioPts = lastAudioPtsInitial

        while (pos + 7 <= dataLen) {
            if ((data[pos].toInt() and 0xFF) != 0xFF || (data[pos + 1].toInt() and 0xF6) != 0xF0) {
                pos++
                continue
            }
            val protectionAbsent = data[pos + 1].toInt() and 0x01
            val freqIdx = (data[pos + 2].toInt() ushr 2) and 0x0F
            if (freqIdx > 12) {
                pos++
                continue
            }
            val frameLen = ((data[pos + 3].toInt() and 0x03) shl 11) or
                ((data[pos + 4].toInt() and 0xFF) shl 3) or
                ((data[pos + 5].toInt() ushr 5) and 0x07)

            val headerLen = if (protectionAbsent == 1) 7 else 9
            if (frameLen <= headerLen || pos + frameLen > dataLen) {
                pos++
                continue
            }

            val rawOffset = pos + headerLen
            val rawLen = frameLen - headerLen
            if (rawLen > 0 && rawLen <= sampleBuffer.capacity()) {
                sampleBuffer.clear()
                sampleBuffer.put(data, rawOffset, rawLen)
                sampleBuffer.flip()

                val sampleRate = SAMPLE_RATES.getOrElse(freqIdx) { defaultSampleRate }
                val frameDurationUs = (1024L * 1_000_000L) / sampleRate
                var pts = if (basePtsUs >= 0L) {
                    val rawPts = basePtsUs + frameIdx * frameDurationUs
                    (rawPts - minAudioPts).coerceAtLeast(0L)
                } else {
                    (lastAudioPts + frameDurationUs).coerceAtLeast(0L)
                }
                if (pts <= lastAudioPts) {
                    pts = lastAudioPts + frameDurationUs
                }
                lastAudioPts = pts

                bufferInfo.set(0, rawLen, pts, 0)
                muxer.writeSampleData(audioTrackIndex, sampleBuffer, bufferInfo)
            }
            frameIdx++
            pos += frameLen
        }
        return lastAudioPts
    }

    private fun remuxWithMediaExtractor(
        videoTsFile: File,
        audioTsFile: File?,
        outputFile: File
    ): Boolean {
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(videoTsFile.absolutePath)
            var videoTrackIdx = -1
            var embeddedAudioTrackIdx = -1

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrackIdx == -1) {
                    videoTrackIdx = i
                } else if (mime.startsWith("audio/") && embeddedAudioTrackIdx == -1) {
                    embeddedAudioTrackIdx = i
                }
            }
            if (videoTrackIdx == -1) return false

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIdx))

            var muxAudioTrack = -1
            var externalAudioTrackIdx = -1
            if (audioTsFile != null && audioTsFile.exists()) {
                audioExtractor = MediaExtractor().apply {
                    setDataSource(audioTsFile.absolutePath)
                }
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        externalAudioTrackIdx = i
                        muxAudioTrack = muxer.addTrack(format)
                        break
                    }
                }
            } else if (embeddedAudioTrackIdx != -1) {
                muxAudioTrack = muxer.addTrack(videoExtractor.getTrackFormat(embeddedAudioTrackIdx))
            }

            muxer.start()
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            videoExtractor.selectTrack(videoTrackIdx)
            while (true) {
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.flags = if ((videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(muxVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            if (muxAudioTrack != -1) {
                val activeAudioExtractor = if (externalAudioTrackIdx != -1 && audioExtractor != null) {
                    audioExtractor.selectTrack(externalAudioTrackIdx)
                    audioExtractor
                } else {
                    videoExtractor.unselectTrack(videoTrackIdx)
                    videoExtractor.selectTrack(embeddedAudioTrackIdx)
                    videoExtractor
                }
                while (true) {
                    val sampleSize = activeAudioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = activeAudioExtractor.sampleTime.coerceAtLeast(0L)
                    bufferInfo.flags = 0
                    muxer.writeSampleData(muxAudioTrack, buffer, bufferInfo)
                    activeAudioExtractor.advance()
                }
            }

            muxer.stop()
            true
        } catch (t: Throwable) {
            false
        } finally {
            try { videoExtractor.release() } catch (_: Throwable) {}
            try { audioExtractor?.release() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }
}
