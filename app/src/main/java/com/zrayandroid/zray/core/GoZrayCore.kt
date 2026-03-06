package com.zrayandroid.zray.core

import android.content.Context
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Go 子进程核心 — 调用预编译的 zray-client ARM64 二进制文件。
 *
 * 优点: 原生 uTLS Chrome 指纹伪装，高性能，与桌面端完全一致
 * 缺点: 需要预置/下载对应架构的二进制文件
 *
 * 工作原理:
 * 1. 将二进制文件放置于 context.filesDir/zray-client
 * 2. 生成临时 config.json 到同目录
 * 3. 以子进程方式启动 ./zray-client
 * 4. 通过 stdout/stderr 读取日志输出到 Logcat 和 DebugLog
 * 5. 停止时发送 SIGTERM 并等待进程退出
 */
class GoZrayCore(private val context: Context) : IZrayCore {

    override val coreType = CoreType.GO_CORE

    @Volatile private var running = false
    @Volatile private var latencyMs: Long = -1
    @Volatile private var currentSocksPort = 0

    private var process: Process? = null
    private var latencyJob: Job? = null
    private var stdoutJob: Job? = null

    // 协程作用域，用于管理子进程 I/O 读取
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var remoteHost = ""
    private var remotePort = 64433

    /** Go 二进制文件路径 */
    private val binaryFile: File
        get() = File(context.filesDir, "zray-client")

    /** 临时配置文件路径 */
    private val configFile: File
        get() = File(context.filesDir, "config.json")

    override fun start(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        DebugLog.log("GO-CORE", "启动, isRunning=$running, port=$socksPort")

        // 清理旧实例
        if (running || process != null) {
            DebugLog.log("GO-CORE", "清理旧实例")
            stop()
            try { Thread.sleep(200) } catch (e: Exception) {}
        }

        currentSocksPort = socksPort

        // 1. 从 assets 解压核心二进制（自动校验 MD5 + chmod）
        val binary = AssetExtractor.ensureExtracted(context)
        if (binary == null || !binary.exists()) {
            val msg = "Go 核心不可用：assets 中未内置或解压失败"
            DebugLog.log("ERROR", msg)
            onResult(false, msg)
            return
        }

        // 2. 生成配置文件
        try {
            val configJson = generateConfig(config, socksPort)
            configFile.writeText(configJson)
            DebugLog.log("GO-CORE", "配置写入: ${configFile.absolutePath}")
        } catch (e: Exception) {
            val msg = "配置文件写入失败: ${e.message}"
            DebugLog.log("ERROR", msg)
            onResult(false, msg)
            return
        }

        // 3. 启动子进程
        scope.launch {
            try {
                val pb = ProcessBuilder(binary.absolutePath, "-c", configFile.absolutePath)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)    // stderr 合并到 stdout，统一读取

                // 设置环境变量（如需要）
                pb.environment()["HOME"] = context.filesDir.absolutePath

                val proc = pb.start()
                process = proc
                running = true

                DebugLog.log("GO-CORE", "子进程已启动, PID=${getPid(proc)}")

                // 4. 启动 stdout 读取（stderr 已合并）
                stdoutJob = scope.launch { readStream(proc.inputStream.bufferedReader(), "GO-CORE") }

                // 5. 等待端口就绪（最多 10 秒）
                val portReady = waitForPort(socksPort, timeoutMs = 10000)
                if (portReady) {
                    DebugLog.log("GO-CORE", "端口 $socksPort 就绪")
                    startLatencyProbe()
                    withContext(Dispatchers.Main) { onResult(true, null) }
                } else {
                    // 检查进程是否已退出
                    if (!proc.isAlive) {
                        val exitCode = proc.exitValue()
                        val msg = "Go 进程异常退出, exitCode=$exitCode"
                        DebugLog.log("ERROR", msg)
                        running = false
                        withContext(Dispatchers.Main) { onResult(false, msg) }
                        return@launch
                    }
                    val msg = "端口 $socksPort 等待超时（进程仍在运行）"
                    DebugLog.log("WARN", msg)
                    // 进程还活着但端口没开，可能还在初始化，先报成功
                    startLatencyProbe()
                    withContext(Dispatchers.Main) { onResult(true, null) }
                }

                // 6. 监控进程退出
                val exitCode = proc.waitFor()
                running = false
                DebugLog.log("GO-CORE", "子进程退出, exitCode=$exitCode")

            } catch (e: Exception) {
                val msg = "Go 核心启动异常: ${e.message}"
                DebugLog.log("ERROR", msg)
                running = false
                withContext(Dispatchers.Main) { onResult(false, msg) }
            }
        }
    }

    override fun stop() {
        if (!running && process == null) return
        DebugLog.log("GO-CORE", "停止")
        running = false
        latencyMs = -1
        TrafficStats.reset()

        // 发送 SIGTERM 优雅终止
        val proc = process
        if (proc != null) {
            try {
                proc.destroy()
                DebugLog.log("GO-CORE", "已发送 SIGTERM")
                // 等待3秒，超时则强杀
                val waitThread = Thread {
                    try { proc.waitFor() } catch (e: Exception) { /* ignore */ }
                }
                waitThread.start()
                waitThread.join(3000)
                if (waitThread.isAlive) {
                    proc.destroyForcibly()
                    DebugLog.log("GO-CORE", "强制 SIGKILL")
                }
            } catch (e: Exception) {
                DebugLog.log("ERROR", "停止进程异常: ${e.message}")
                try { proc.destroyForcibly() } catch (e2: Exception) { /* ignore */ }
            }
        }

        process = null
        latencyJob?.cancel()
        stdoutJob?.cancel()
    }

    override fun isRunning() = running
    override fun getLatency() = latencyMs

    override fun getStatus() = CoreStatus(
        running = running,
        coreType = CoreType.GO_CORE,
        socksPort = currentSocksPort,
        latencyMs = latencyMs,
        remoteHost = remoteHost,
        remotePort = remotePort,
        error = if (!binaryFile.exists()) "二进制文件缺失" else null
    )

    /**
     * 检查 Go 二进制文件是否已就绪
     */
    fun isBinaryAvailable(): Boolean {
        // 优先检查已解压的文件，其次检查 assets 中是否内置
        return (binaryFile.exists() && binaryFile.canExecute()) || AssetExtractor.hasAsset(context)
    }

    /**
     * 获取二进制文件路径（供外部复制文件用）
     */
    fun getBinaryPath(): String = binaryFile.absolutePath

    // ==================== 私有方法 ====================

    /**
     * 生成 Go 客户端兼容的 config.json
     */
    private fun generateConfig(config: String, socksPort: Int): String {
        try {
            if (config.trimStart().startsWith("{")) {
                val json = org.json.JSONObject(config)
                remoteHost = json.optString("remote_host", "")
                remotePort = json.optInt("remote_port", 64433)
                val userHash = json.optString("user_hash", "")

                return """
                {
                    "smart_port": "127.0.0.1:$socksPort",
                    "global_port": "127.0.0.1:$socksPort",
                    "remote_host": "$remoteHost",
                    "remote_port": $remotePort,
                    "user_hash": "$userHash",
                    "geosite_path": ""
                }
                """.trimIndent()
            }
        } catch (e: Exception) {
            DebugLog.log("GO-CORE", "配置解析异常: ${e.message}")
        }

        // 回退：直接写入原始配置
        return config
    }

    /**
     * 协程读取进程输出流，逐行转发到 DebugLog 和 Logcat
     */
    private suspend fun readStream(reader: BufferedReader, tag: String) {
        withContext(Dispatchers.IO) {
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let {
                        DebugLog.log(tag, it)
                        android.util.Log.d("GoZrayCore", "[$tag] $it")
                    }
                }
            } catch (e: Exception) {
                if (running) DebugLog.log(tag, "流读取结束: ${e.message}")
            } finally {
                try { reader.close() } catch (e: Exception) {}
            }
        }
    }

    /**
     * 等待本地端口可连接
     */
    private suspend fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", port), 500)
                sock.close()
                return true
            } catch (e: Exception) {
                delay(300)
            }
        }
        return false
    }

    /**
     * 延迟探测协程
     */
    private fun startLatencyProbe() {
        latencyJob = scope.launch {
            while (isActive && running) {
                try {
                    if (remoteHost.isNotEmpty()) {
                        val start = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            val sock = Socket()
                            sock.connect(InetSocketAddress(remoteHost, remotePort), 5000)
                            sock.close()
                        }
                        latencyMs = System.currentTimeMillis() - start
                        DebugLog.log("LATENCY", "${latencyMs}ms → $remoteHost:$remotePort")
                    }
                } catch (e: Exception) {
                    latencyMs = -1
                }
                delay(5000)
            }
        }
    }

    /**
     * 获取进程 PID（反射，兼容低版本 Android）
     */
    private fun getPid(process: Process): Long {
        return try {
            // Android 9+ (API 28+)
            process.javaClass.getMethod("pid").invoke(process) as Long
        } catch (e: Exception) {
            try {
                // 旧版本反射
                val field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(process).toLong()
            } catch (e: Exception) { -1L }
        }
    }
}
