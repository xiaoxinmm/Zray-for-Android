package com.zrayandroid.zray.core

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ZA:// 加密链接解析 — 与 Go 服务端完全兼容。
 * 支持 v2 (Base64URL + JSON/Binary) 和 v1 (Base26 legacy)。
 */
object ZALinkParser {
    private const val DEFAULT_KEY = "ZRaySecretKey!!!"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class LinkConfig(
        val host: String,
        val port: Int,
        val userHash: String,
        val smartPort: Int = 1080,
        val globalPort: Int = 1081,
        val tfo: Boolean = false
    )

    /**
     * 解析 ZA:// 链接，返回配置。
     * @param link ZA://xxx 格式
     * @param key 解密密钥，为空则使用默认密钥
     */
    fun parse(link: String, key: String = ""): LinkConfig {
        val actualKey = if (key.isEmpty()) {
            DebugLog.log("SECURITY", "⚠️ 使用默认密钥解析 ZA 链接。建议在服务端自定义密钥以提高安全性。")
            DEFAULT_KEY
        } else {
            key
        }
        val upper = link.uppercase()
        if (!upper.startsWith("ZA://")) {
            throw IllegalArgumentException("无效 ZA 链接：缺少 ZA:// 前缀")
        }
        val body = link.substring(5)

        // v1 检测：全大写字母且很长
        if (isAllUpperAlpha(body) && body.length > 80) {
            return parseLegacyV1(body, actualKey)
        }

        // v2: Base64URL
        val encrypted = try {
            Base64.decode(body, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: Exception) {
            // 尝试 v1
            return parseLegacyV1(body.uppercase(), actualKey)
        }

        val data = decrypt(encrypted, deriveKey(actualKey))

        // 二进制格式 (0x02 开头)
        if (data.isNotEmpty() && data[0] == 0x02.toByte()) {
            return decodeBinary(data)
        }

        // JSON 格式 — 使用 kotlinx.serialization 解析
        val jsonObj = json.decodeFromString<JsonObject>(String(data))
        return LinkConfig(
            host = jsonObj["h"]?.jsonPrimitive?.content ?: "",
            port = jsonObj["p"]?.jsonPrimitive?.intOrNull ?: 64433,
            userHash = jsonObj["u"]?.jsonPrimitive?.content ?: "",
            smartPort = jsonObj["s"]?.jsonPrimitive?.intOrNull ?: 1080,
            globalPort = jsonObj["g"]?.jsonPrimitive?.intOrNull ?: 1081,
            tfo = jsonObj["t"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    /**
     * 检测字符串是否是 ZA 链接
     */
    fun isZALink(text: String): Boolean {
        return text.trim().uppercase().startsWith("ZA://")
    }

    private fun decodeBinary(data: ByteArray): LinkConfig {
        if (data.size < 9) throw IllegalArgumentException("二进制数据太短")
        val flags = data[1]
        val ip = "${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}.${data[4].toInt() and 0xFF}.${data[5].toInt() and 0xFF}"
        val port = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val userHash = String(data, 8, data.size - 8)
        return LinkConfig(
            host = ip,
            port = port,
            userHash = userHash,
            tfo = (flags.toInt() and 0x01) != 0
        )
    }

    private fun deriveKey(key: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(key.toByteArray())
    }

    private fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        // AES-256-GCM: nonce(12) + encrypted + tag(16)
        val nonceSize = 12
        if (ciphertext.size < nonceSize + 16) {
            throw IllegalArgumentException("密文太短")
        }
        val nonce = ciphertext.sliceArray(0 until nonceSize)
        val encrypted = ciphertext.sliceArray(nonceSize until ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce) // 128-bit tag
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(encrypted)
    }

    private fun isAllUpperAlpha(s: String): Boolean {
        return s.all { it in 'A'..'Z' }
    }

    private fun parseLegacyV1(body: String, key: String): LinkConfig {
        val encrypted = base26ToBytes(body)
        val data = decrypt(encrypted, deriveKey(key))

        // 使用 kotlinx.serialization 解析 JSON
        val jsonObj = json.decodeFromString<JsonObject>(String(data))
        return LinkConfig(
            host = jsonObj["h"]?.jsonPrimitive?.content ?: "",
            port = jsonObj["p"]?.jsonPrimitive?.intOrNull ?: 64433,
            userHash = jsonObj["u"]?.jsonPrimitive?.content ?: "",
            smartPort = jsonObj["s"]?.jsonPrimitive?.intOrNull ?: 1080,
            globalPort = jsonObj["g"]?.jsonPrimitive?.intOrNull ?: 1081,
            tfo = jsonObj["t"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private fun base26ToBytes(s: String): ByteArray {
        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(26)
        for (c in s) {
            if (c !in 'A'..'Z') throw IllegalArgumentException("无效字符: $c")
            n = n.multiply(base).add(BigInteger.valueOf((c - 'A').toLong()))
        }
        var data = n.toByteArray()
        // 去掉前导 0x01 标记
        if (data.isNotEmpty() && data[0] == 0x01.toByte()) {
            data = data.sliceArray(1 until data.size)
        }
        return data
    }
}
