package com.zrayandroid.zray.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 资产解压器 — 从 APK assets 解压 Go 核心二进制到 filesDir。
 *
 * 触发条件：
 * 1. 目标文件不存在
 * 2. 目标文件 MD5 与 assets 内的不一致（核心版本更新）
 *
 * 线程安全：通过 synchronized 保证只解压一次。
 */
object AssetExtractor {

    private const val ASSET_NAME = "zray-core"
    private const val TAG = "EXTRACTOR"

    @Volatile
    private var extracted = false

    /**
     * 确保核心二进制已解压到 filesDir 并可执行。
     * 可在任意线程调用，内部同步。
     *
     * @return 解压后的 File，失败返回 null
     */
    @Synchronized
    fun ensureExtracted(context: Context): File? {
        val target = File(context.filesDir, ASSET_NAME)

        // 快速路径：已解压且文件存在
        if (extracted && target.exists() && target.canExecute()) {
            return target
        }

        try {
            // 检查 assets 中是否存在核心文件
            val assetList = context.assets.list("") ?: emptyArray()
            if (ASSET_NAME !in assetList) {
                DebugLog.log(TAG, "assets 中不存在 $ASSET_NAME，此构建未内置 Go 核心")
                return null
            }

            // 计算 assets 中的 MD5
            val assetMd5 = context.assets.open(ASSET_NAME).use { md5(it) }

            // 如果目标文件已存在且 MD5 一致，跳过解压
            if (target.exists()) {
                val targetMd5 = target.inputStream().use { md5(it) }
                if (assetMd5 == targetMd5) {
                    DebugLog.log(TAG, "核心文件已是最新 (MD5: ${assetMd5.take(8)}...)")
                    ensureExecutable(target)
                    extracted = true
                    return target
                }
                DebugLog.log(TAG, "核心文件 MD5 不一致，重新解压")
            } else {
                DebugLog.log(TAG, "核心文件不存在，首次解压")
            }

            // 解压：先写临时文件再原子重命名，避免中途断电导致损坏
            val tmp = File(context.filesDir, "$ASSET_NAME.tmp")
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        output.write(buf, 0, len)
                    }
                }
            }

            // 原子重命名
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                DebugLog.log(TAG, "重命名失败，尝试直接复制")
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            ensureExecutable(target)

            val size = target.length() / 1024
            DebugLog.log(TAG, "解压完成: ${target.absolutePath} (${size}KB)")
            extracted = true
            return target

        } catch (e: Exception) {
            DebugLog.log("ERROR", "核心解压失败: ${e.message}")
            return null
        }
    }

    /**
     * 检查 assets 中是否包含 Go 核心
     */
    fun hasAsset(context: Context): Boolean {
        return try {
            val list = context.assets.list("") ?: emptyArray()
            ASSET_NAME in list
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 赋予执行权限
     */
    private fun ensureExecutable(file: File) {
        if (!file.canExecute()) {
            val ok = file.setExecutable(true, false)
            if (!ok) {
                // 回退到 chmod
                Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
            }
            DebugLog.log(TAG, "已赋予执行权限")
        }
    }

    /**
     * 计算输入流的 MD5 hex
     */
    private fun md5(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(8192)
        var len: Int
        while (input.read(buf).also { len = it } != -1) {
            digest.update(buf, 0, len)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
