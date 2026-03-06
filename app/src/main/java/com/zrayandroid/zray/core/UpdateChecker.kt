package com.zrayandroid.zray.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * GitHub Release 更新检查
 */
object UpdateChecker {
    private const val REPO_API = "https://api.github.com/repos/xiaoxinmm/Zray-for-Android/releases/latest"

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
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")

                if (conn.responseCode != 200) {
                    onResult(null)
                    return@thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").removePrefix("v")
                val htmlUrl = json.optString("html_url", "")
                val notes = json.optString("body", "")

                // 找 APK 下载链接
                var apkUrl = htmlUrl
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", htmlUrl)
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
                DebugLog.log("UPDATE", "检查失败: ${e.message}")
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
