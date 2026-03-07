package com.zrayandroid.zray.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 核心调度器 — 管理 Go 子进程核心的生命周期。
 *
 * 职责:
 * 1. 创建 GoZrayCore 实例
 * 2. 安全启停核心
 * 3. 线程安全：所有操作通过 Mutex 串行化
 * 4. 统一暴露 IZrayCore 接口给上层
 */
class ZrayCoreManager(private val context: Context) {

    /** 当前活跃的核心实例 */
    @Volatile
    private var activeCore: IZrayCore? = null

    /** 操作互斥锁，保证核心操作的线程安全 */
    private val mutex = Mutex()

    /** 协程作用域 */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 启动当前核心。
     * 如果没有核心实例，自动创建。
     */
    fun start(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            mutex.withLock {
                // 确保有核心实例
                if (activeCore == null) {
                    activeCore = createCore()
                }

                val core = activeCore!!
                DebugLog.log("MANAGER", "启动 Go 核心, 端口: $socksPort")

                // 在 IO 线程启动核心
                withContext(Dispatchers.IO) {
                    core.start(config, socksPort) { success, error ->
                        scope.launch(Dispatchers.Main) {
                            if (success) {
                                DebugLog.log("MANAGER", "核心启动成功")
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
            DebugLog.log("MANAGER", "停止核心")
            core.stop()
        }
    }

    /** 当前核心是否在运行 */
    fun isRunning(): Boolean = activeCore?.isRunning() == true

    /** 获取当前延迟 */
    fun getLatency(): Long = activeCore?.getLatency() ?: -1

    /** 获取当前核心状态 */
    fun getStatus(): CoreStatus = activeCore?.getStatus() ?: CoreStatus(
        running = false
    )

    /** 获取当前核心实例 */
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
     * 创建 Go 核心实例
     */
    private fun createCore(): IZrayCore {
        DebugLog.log("MANAGER", "创建 Go 核心")
        return GoZrayCore(context)
    }
}
