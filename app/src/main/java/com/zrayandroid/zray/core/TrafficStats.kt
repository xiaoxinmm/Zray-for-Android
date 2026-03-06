package com.zrayandroid.zray.core

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局流量统计
 */
object TrafficStats {
    val uploadBytes = AtomicLong(0)
    val downloadBytes = AtomicLong(0)
    val activeConns = AtomicLong(0)

    // 速度（每秒更新）
    @Volatile var uploadSpeed: Long = 0
    @Volatile var downloadSpeed: Long = 0

    private var lastUp: Long = 0
    private var lastDown: Long = 0

    fun reset() {
        uploadBytes.set(0)
        downloadBytes.set(0)
        activeConns.set(0)
        uploadSpeed = 0
        downloadSpeed = 0
        lastUp = 0
        lastDown = 0
    }

    /** 每秒调用一次，计算速度 */
    fun tick() {
        val up = uploadBytes.get()
        val down = downloadBytes.get()
        uploadSpeed = up - lastUp
        downloadSpeed = down - lastDown
        lastUp = up
        lastDown = down
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
            else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024))
        }
    }
}
