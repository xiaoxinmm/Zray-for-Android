package com.zrayandroid.zray.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 核心调度器 — 管理双核心的动态切换。
 *
 * 职责:
 * 1. 根据用户选择实例化对应核心 (Kotlin / Go)
 * 2. 安全切换核心：停旧→等端口释放→启新
 * 3. 线程安全：所有操作通过 Mutex 串行化
 * 4. 统一暴露 IZrayCore 接口给上层
 *
 * 使用方式:
 * ```kotlin
 * val manager = ZrayCoreManager(context)
 * manager.switchCore(CoreType.GO_CORE)
 * manager.start(config, port) { success, error -> ... }
 * ```
 */
class ZrayCoreManager(private val context: Context) {

    /** 当前活跃的核心实例 */
    @Volatile
    private var activeCore: IZrayCore? = null

    /** 当前选择的核心类型 */
    @Volatile
    var selectedCoreType: CoreType = CoreType.KOTLIN_CORE
        private set

    /** 是否允许不安全的 SSL 证书 */
    @Volatile
    var allowInsecureSsl: Boolean = true

    /** 操作互斥锁，保证核心切换的线程安全 */
    private val mutex = Mutex()

    /** 协程作用域 */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 切换核心类型。
     * 如果当前有核心在运行，会先停止旧核心再切换。
     *
     * @param type 目标核心类型
     * @param onComplete 切换完成回调，error 为 null 表示成功
     */
    fun switchCore(type: CoreType, onComplete: ((error: String?) -> Unit)? = null) {
        scope.launch {
            mutex.withLock {
                DebugLog.log("MANAGER", "切换核心: ${selectedCoreType.displayName} → ${type.displayName}")

                // 停止旧核心
                activeCore?.let { old ->
                    if (old.isRunning()) {
                        DebugLog.log("MANAGER", "停止旧核心: ${old.coreType.displayName}")
                        old.stop()

                        // 等待端口释放（最多 2 秒）
                        withContext(Dispatchers.IO) {
                            delay(500)
                            DebugLog.log("MANAGER", "等待端口释放...")
                            delay(500)
                        }
                    }
                }

                // 创建新核心实例
                selectedCoreType = type
                activeCore = createCore(type)

                DebugLog.log("MANAGER", "核心已切换为: ${type.displayName}")
                onComplete?.invoke(null)
            }
        }
    }

    /**
     * 启动当前核心。
     * 如果没有核心实例，自动创建默认核心。
     */
    fun start(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            mutex.withLock {
                // 确保有核心实例
                if (activeCore == null) {
                    activeCore = createCore(selectedCoreType)
                }

                val core = activeCore!!
                DebugLog.log("MANAGER", "启动核心: ${core.coreType.displayName}, 端口: $socksPort")

                // 应用 SSL 配置到 KotlinZrayCore
                if (core is KotlinZrayCore) {
                    core.allowInsecureSsl = allowInsecureSsl
                }

                // 在 IO 线程启动核心
                withContext(Dispatchers.IO) {
                    core.start(config, socksPort) { success, error ->
                        scope.launch(Dispatchers.Main) {
                            if (success) {
                                DebugLog.log("MANAGER", "核心启动成功: ${core.coreType.displayName}")
                            } else {
                                DebugLog.log("ERROR", "核心启动失败: $error")
                            }
                            onResult(success, error)
                        }
                    }
                }
            }
        }
    }

    /**
     * 停止当前核心。
     */
    fun stop() {
        // stop 需要立即执行，不走协程等待
        activeCore?.let { core ->
            DebugLog.log("MANAGER", "停止核心: ${core.coreType.displayName}")
            core.stop()
        }
    }

    /** 当前核心是否在运行 */
    fun isRunning(): Boolean = activeCore?.isRunning() == true

    /** 获取当前延迟 */
    fun getLatency(): Long = activeCore?.getLatency() ?: -1

    /** 获取当前核心状态 */
    fun getStatus(): CoreStatus = activeCore?.getStatus() ?: CoreStatus(
        running = false,
        coreType = selectedCoreType
    )

    /** 获取当前核心实例（用于直接访问特定功能） */
    fun getActiveCore(): IZrayCore? = activeCore

    /**
     * Go 核心是否可用（二进制文件是否存在）
     */
    fun isGoCoreAvailable(): Boolean {
        val goCore = GoZrayCore(context)
        return goCore.isBinaryAvailable()
    }

    /**
     * 获取 Go 核心二进制路径（供 UI 展示提示）
     */
    fun getGoBinaryPath(): String {
        return GoZrayCore(context).getBinaryPath()
    }

    /**
     * 释放所有资源
     */
    fun destroy() {
        stop()
        scope.cancel()
        activeCore = null
    }

    // ==================== 私有方法 ====================

    /**
     * 工厂方法：根据类型创建核心实例
     */
    private fun createCore(type: CoreType): IZrayCore {
        return when (type) {
            CoreType.KOTLIN_CORE -> {
                DebugLog.log("MANAGER", "创建 Kotlin 原生核心")
                KotlinZrayCore()
            }
            CoreType.GO_CORE -> {
                DebugLog.log("MANAGER", "创建 Go 子进程核心")
                GoZrayCore(context)
            }
        }
    }
}
