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
        isRunning = true
        // TODO: 替换为 zraylib.Zraylib.start(config)
    }

    fun stop() {
        Log.i(TAG, "核心停止")
        isRunning = false
        // TODO: 替换为 zraylib.Zraylib.stop()
    }
}
