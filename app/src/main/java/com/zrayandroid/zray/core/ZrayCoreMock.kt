package com.zrayandroid.zray.core

import android.util.Log

/**
 * Zray 核心 Mock。
 * 后续替换为真实 zraylib.aar 中的调用即可。
 */
object ZrayCoreMock {
    private const val TAG = "ZrayCore"

    @Volatile
    var isRunning = false
        private set

    fun start(config: String) {
        Log.i(TAG, "核心启动，配置: $config")
        DebugLog.log("CORE", "核心启动")
        DebugLog.log("CORE", "配置: ${if (config.length > 50) config.take(50) + "..." else config}")
        isRunning = true
        DebugLog.log("CORE", "SOCKS5 代理已启动")
        // TODO: 替换为 zraylib.Zraylib.start(config)
    }

    fun stop() {
        Log.i(TAG, "核心停止")
        DebugLog.log("CORE", "核心停止")
        isRunning = false
        // TODO: 替换为 zraylib.Zraylib.stop()
    }
}
