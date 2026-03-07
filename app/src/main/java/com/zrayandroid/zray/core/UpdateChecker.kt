package com.zrayandroid.zray.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * GitHub Release 更新检查
 */
object UpdateChecker {
    private const val REPO_API = "https://api.github.com/repos/xiaoxinmm/Zray-for-Android/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val htmlUrl: String
    )

    /**
     * 异步检查更新
     * @param currentVersion 当前版本号，如 "1.2.0"
     * @param onResult 回调，null 表示无更新或检查失败
     */
    fun checkAsync(currentVersion: String, onResult: (UpdateInfo?) -> Unit) {
        thread(name = "update-check") {
            try {
                val conn = URL(REPO_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/vnd.github+json")

                if (conn.responseCode != 200) {
                    onResult(null)
                    return@thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                val jsonObj = json.decodeFromString<JsonObject>(body)
                val tagName = (jsonObj["tag_name"]?.jsonPrimitive?.content ?: "").removePrefix("v")
                val htmlUrl = jsonObj["html_url"]?.jsonPrimitive?.content ?: ""
                val notes = jsonObj["body"]?.jsonPrimitive?.content ?: ""

                // 找 APK 下载链接
                var apkUrl = htmlUrl
                val assets = jsonObj["assets"]?.jsonArray
                if (assets != null) {
                    for (element in assets) {
                        val asset = element.jsonObject
                        val name = asset["name"]?.jsonPrimitive?.content ?: ""
                        if (name.endsWith(".apk")) {
                            apkUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: htmlUrl
                            break
                        }
                    }
                }

                if (tagName.isNotEmpty() && isNewer(tagName, currentVersion)) {
                    onResult(UpdateInfo(
                        version = tagName,
                        downloadUrl = apkUrl,
                        releaseNotes = notes,
                        htmlUrl = htmlUrl
                    ))
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                DebugLog.log("UPDATE", "检查跳过（网络不可达）")
                onResult(null)
            }
        }
    }

    /**
     * 比较版本号 a > b
     */
    private fun isNewer(a: String, b: String): Boolean {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av > bv) return true
            if (av < bv) return false
        }
        return false
    }
}
