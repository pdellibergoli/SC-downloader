package com.streamingcommunity.downloader.core.hls

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesSegmentDecryptor(private val keyBytes: ByteArray) {

    var explicitIv: ByteArray? = null
        private set

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().removePrefix("0x").removePrefix("0X")
            val padded = if (clean.length % 2 != 0) "0$clean" else clean
            val result = ByteArray(padded.length / 2)
            for (i in result.indices) {
                val index = i * 2
                result[i] = padded.substring(index, index + 2).toInt(16).toByte()
            }
            return result
        }

        fun bytesToHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Returns true if [data] already starts with valid unencrypted MPEG-TS 188-byte sync bytes (0x47)
         * or an unencrypted ID3v2 / ADTS header.
         */
        fun looksAlreadyUnencrypted(data: ByteArray): Boolean {
            if (data.size >= 564 &&
                data[0] == 0x47.toByte() &&
                data[188] == 0x47.toByte() &&
                data[376] == 0x47.toByte()
            ) {
                return true
            }
            if (data.size >= 188 && data.size % 188 == 0 && data[0] == 0x47.toByte()) {
                return true
            }
            if (data.size >= 10 &&
                data[0] == 'I'.code.toByte() &&
                data[1] == 'D'.code.toByte() &&
                data[2] == '3'.code.toByte()
            ) {
                return true
            }
            return false
        }

        /**
         * Trims any trailing AES 16-byte block padding from a decrypted MPEG-TS segment
         * so that its size is an exact multiple of 188 bytes when it starts with 0x47.
         */
        fun trimTsPaddingIfNeeded(decrypted: ByteArray): ByteArray {
            if (decrypted.size >= 188 &&
                decrypted[0] == 0x47.toByte() &&
                decrypted.size % 188 != 0
            ) {
                val remainder = decrypted.size % 188
                // AES block padding at the end of a TS segment is at most 15 bytes
                if (remainder < 16) {
                    val aligned = (decrypted.size / 188) * 188
                    if (aligned >= 188 && decrypted[aligned - 188] == 0x47.toByte()) {
                        return decrypted.copyOf(aligned)
                    }
                }
            }
            return decrypted
        }
    }

    fun parseIv(rawIv: String?) {
        if (rawIv.isNullOrBlank()) {
            explicitIv = null
            return
        }
        val bytes = hexToBytes(rawIv)
        explicitIv = when {
            bytes.size == 16 -> bytes
            bytes.size < 16 -> ByteArray(16 - bytes.size) + bytes
            else -> bytes.copyOfRange(bytes.size - 16, bytes.size)
        }
    }

    private fun ivForSegment(segmentIndex: Int): ByteArray {
        explicitIv?.let { return it }
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(0L)
        buffer.putLong(segmentIndex.toLong())
        return buffer.array()
    }

    fun decryptTs(encryptedData: ByteArray, segmentIndex: Int = 0): ByteArray {
        if (encryptedData.isEmpty()) return encryptedData
        if (looksAlreadyUnencrypted(encryptedData)) {
            return trimTsPaddingIfNeeded(encryptedData)
        }

        val iv = ivForSegment(segmentIndex)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)

        val decrypted = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            val alignedLen = (encryptedData.size / 16) * 16
            if (alignedLen == 0) return encryptedData
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(encryptedData, 0, alignedLen)
        }
        return trimTsPaddingIfNeeded(decrypted)
    }
}
