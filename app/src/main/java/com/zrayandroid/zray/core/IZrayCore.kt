package com.zrayandroid.zray.core

/**
 * Zray 代理核心抽象接口。
 * 所有核心实现（Kotlin 原生 / Go 子进程）必须实现此接口。
 */
interface IZrayCore {

    /** 核心类型标识 */
    val coreType: CoreType

    /**
     * 异步启动核心。
     * @param config 配置 JSON 字符串
     * @param socksPort 本地 SOCKS5 监听端口
     * @param onResult 回调：success=true 启动成功，false 启动失败并附带错误信息
     */
    fun start(config: String, socksPort: Int, onResult: (success: Boolean, error: String?) -> Unit)

    /** 停止核心，释放端口和资源 */
    fun stop()

    /** 当前运行状态 */
    fun isRunning(): Boolean

    /** 最近一次延迟探测结果（ms），-1 表示未知 */
    fun getLatency(): Long

    /** 获取核心状态摘要（用于 UI 展示） */
    fun getStatus(): CoreStatus
}

/** 核心类型枚举 */
enum class CoreType(val displayName: String) {
    KOTLIN_CORE("Kotlin 原生核心"),
    GO_CORE("Go 高性能核心")
}

/** 核心运行状态 */
data class CoreStatus(
    val running: Boolean,
    val coreType: CoreType,
    val socksPort: Int = 0,
    val latencyMs: Long = -1,
    val remoteHost: String = "",
    val remotePort: Int = 0,
    val error: String? = null
)
