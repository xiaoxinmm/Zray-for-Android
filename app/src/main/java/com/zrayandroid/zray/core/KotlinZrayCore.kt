package com.zrayandroid.zray.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore

/**
 * Kotlin 原生核心 — 纯 JVM 实现的 Zray 协议客户端。
 *
 * 协议流程: TCP → TLS 握手 → HTTP 伪装 → Zray 头(ver+ts+nonce+hash) → padding → CMD+地址 → relay
 * 优点: 无需 native 二进制，兼容性好，包体积小
 * 缺点: 无 uTLS 指纹伪装（Java SSLSocket 指纹特征明显）
 *
 * 并发模型: 使用 Kotlin Coroutines + Dispatchers.IO 替代原始 thread，
 * 避免高并发场景下线程爆炸 (OOM)。
 */
class KotlinZrayCore : IZrayCore {

    override val coreType = CoreType.KOTLIN_CORE

    companion object {
        private const val PROTOCOL_VERSION: Byte = 0x01
        private const val CMD_CONNECT: Byte = 0x01
    }

    @Volatile private var running = false
    @Volatile private var latencyMs: Long = -1
    @Volatile private var currentSocksPort = 0

    /** 证书错误是否已记录（每次连接会话只记录一次） */
    @Volatile private var certErrorLogged = false

    /** 运行时错误，供 UI 层轮询展示 */
    @Volatile
    var lastError: String? = null
        private set

    private var serverSocket: ServerSocket? = null
    private var latencyJob: Job? = null

    /** 是否允许不安全的 SSL 证书（跳过校验），默认 true 保持向后兼容 */
    @Volatile
    var allowInsecureSsl: Boolean = true

    // 协程作用域，用于管理所有并发任务
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 独立的 relay 调度器 — 允许高达 256 个并发阻塞线程，
    // 避免 relay 操作耗尽共享 Dispatchers.IO 线程池（默认 64 线程），
    // 导致新连接排队、网络断连。
    private val relayDispatcher = Dispatchers.IO.limitedParallelism(256)

    private var remoteHost = ""
    private var remotePort = 64433
    private var userHash = ""

    // 信任所有证书（自签证书）
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    /** 根据 allowInsecureSsl 配置动态构建 SSLContext */
    private fun buildSslContext(): SSLContext {
        return SSLContext.getInstance("TLS").apply {
            if (allowInsecureSsl) {
                init(null, trustAllCerts, SecureRandom())
            } else {
                // 使用系统默认的 TrustManager 进行严格证书校验
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                init(null, tmf.trustManagers, SecureRandom())
            }
        }
    }

    override fun start(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        DebugLog.log("KOTLIN-CORE", "启动, isRunning=$running, port=$socksPort")

        // 强制清理旧实例
        if (running || serverSocket != null) {
            DebugLog.log("KOTLIN-CORE", "清理旧实例")
            stop()
            try { Thread.sleep(100) } catch (e: Exception) {}
        }

        // 解析配置
        parseConfig(config)
        currentSocksPort = socksPort

        // 确保 userHash 16 字节
        if (userHash.length > 16) userHash = userHash.substring(0, 16)
        while (userHash.length < 16) userHash += "\u0000"

        DebugLog.log("KOTLIN-CORE", "远程: $remoteHost:$remotePort, SSL安全模式: ${!allowInsecureSsl}")

        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", socksPort))
                serverSocket = ss
                running = true

                DebugLog.log("KOTLIN-CORE", "SOCKS5 监听成功: 127.0.0.1:$socksPort")
                withContext(Dispatchers.Main) { onResult(true, null) }

                startLatencyProbe()

                // accept 循环
                while (running && isActive) {
                    try {
                        val client = ss.accept()
                        scope.launch { handleSocks5(client) }
                    } catch (e: Exception) {
                        if (running) DebugLog.log("KOTLIN-CORE", "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                val msg = "端口 $socksPort 启动失败: ${e.message}"
                DebugLog.log("ERROR", msg)
                running = false
                withContext(Dispatchers.Main) { onResult(false, msg) }
            }
        }
    }

    override fun stop() {
        if (!running && serverSocket == null) return
        DebugLog.log("KOTLIN-CORE", "停止")
        running = false
        latencyMs = -1
        certErrorLogged = false
        lastError = null
        TrafficStats.reset()
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        latencyJob?.cancel()
        latencyJob = null
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    override fun isRunning() = running
    override fun getLatency() = latencyMs

    override fun getStatus() = CoreStatus(
        running = running,
        coreType = CoreType.KOTLIN_CORE,
        socksPort = currentSocksPort,
        latencyMs = latencyMs,
        remoteHost = remoteHost,
        remotePort = remotePort
    )

    // ==================== 私有方法 ====================

    private fun parseConfig(config: String) {
        try {
            if (config.trimStart().startsWith("{")) {
                val jsonObj = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(config)
                remoteHost = jsonObj["remote_host"]?.jsonPrimitive?.content ?: ""
                remotePort = jsonObj["remote_port"]?.jsonPrimitive?.intOrNull ?: 64433
                userHash = jsonObj["user_hash"]?.jsonPrimitive?.content ?: ""
            } else {
                val cleaned = config.trim()
                    .removePrefix("zray://").removePrefix("socks5://").removePrefix("socks://")
                val parts = cleaned.split(":")
                if (parts.size >= 2) {
                    remoteHost = parts[0]
                    remotePort = parts[1].toIntOrNull() ?: 64433
                    if (parts.size >= 3) userHash = parts[2]
                }
            }
        } catch (e: Exception) {
            DebugLog.log("KOTLIN-CORE", "配置解析异常: ${e.message}")
        }
    }

    private suspend fun handleSocks5(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 握手
            val ver = input.read()
            if (ver != 5) { client.close(); return }
            val nMethods = input.read()
            if (nMethods <= 0 || nMethods > 255) { client.close(); return }
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // SOCKS5 请求
            input.read() // VER
            val cmd = input.read()
            input.read() // RSV
            val atyp = input.read()

            val targetHost: String
            val rawAddrBytes: ByteArray

            when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    rawAddrBytes = addr
                    targetHost = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> {
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    rawAddrBytes = byteArrayOf(len.toByte()) + domain
                    targetHost = String(domain)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    rawAddrBytes = addr
                    val parts = (0 until 8).map { i ->
                        "%x".format(((addr[i * 2].toInt() and 0xFF) shl 8) or (addr[i * 2 + 1].toInt() and 0xFF))
                    }
                    targetHost = parts.joinToString(":")
                }
                else -> { client.close(); return }
            }

            val portHi = input.read()
            val portLo = input.read()
            val targetPort = (portHi shl 8) or portLo

            if (cmd != 0x01) {
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush(); client.close(); return
            }

            DebugLog.log("PROXY", "→ $targetHost:$targetPort (Kotlin/TLS)")

            // Zray 协议连接远程
            val remote = connectViaZray(atyp.toByte(), rawAddrBytes, targetPort, targetHost)
            if (remote == null) {
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush(); client.close(); return
            }

            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // 双向 relay（带流量统计），使用专用 relay 调度器避免阻塞共享线程池
            TrafficStats.activeConns.incrementAndGet()
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()
            try {
                coroutineScope {
                    launch(relayDispatcher) {
                        try { relayWithStats(client.getInputStream(), remoteOut, true) } catch (_: Exception) {}
                        try { remote.close() } catch (_: Exception) {}
                        try { client.close() } catch (_: Exception) {}
                    }
                    launch(relayDispatcher) {
                        try { relayWithStats(remoteIn, client.getOutputStream(), false) } catch (_: Exception) {}
                        try { remote.close() } catch (_: Exception) {}
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } finally {
                TrafficStats.activeConns.decrementAndGet()
            }
        } catch (_: java.io.IOException) {
            // SocketException (Connection reset) / IOException (EBADF, Broken pipe, Read timed out)
            // — 正常的网络抖动或远端关闭，安静忽略
        } catch (e: Exception) {
            DebugLog.log("ERROR", "SOCKS5: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * 递归检查异常链中是否包含 SSL 证书校验错误。
     * 涵盖 SSLHandshakeException、CertificateException、CertPathValidatorException
     * 及各种 Android 版本中的异常包装差异。
     */
    private fun isCertificateError(e: Throwable): Boolean {
        // 关键字列表：覆盖各版本 Android 的异常信息差异
        val keywords = listOf("CertPathValidator", "Trust anchor", "certification path not found")

        // 1. 检查整个异常链
        var current: Throwable? = e
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            if (current is java.security.cert.CertificateException ||
                current is java.security.cert.CertPathValidatorException) {
                return true
            }
            // SSLHandshakeException 通常包装证书错误
            if (current is SSLHandshakeException) {
                val m = current.message ?: ""
                if (keywords.any { m.contains(it, ignoreCase = true) }) return true
            }
            current = current.cause
        }

        // 2. 兜底：检查原始异常的 message 和 toString()
        val msg = e.message ?: ""
        val str = e.toString()
        return keywords.any { kw -> msg.contains(kw, ignoreCase = true) || str.contains(kw, ignoreCase = true) }
    }

    /**
     * Zray 协议隧道: TLS → HTTP伪装 → 协议头 → padding → CMD+地址
     * 支持自动重试（网络中断/RST 场景）
     */
    private suspend fun connectViaZray(atyp: Byte, rawAddr: ByteArray, targetPort: Int, targetHost: String): Socket? {
        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            try {
                return connectViaZrayOnce(atyp, rawAddr, targetPort, targetHost)
            } catch (e: Exception) {
                val msg = e.message ?: ""

                // 证书校验失败：不可重试，提示用户
                if (isCertificateError(e)) {
                    val hint = "SSL 证书校验失败: 节点服务器的证书不受信任。\n请在「设置」中开启「允许不安全的 SSL 证书」后重新连接。"
                    if (!certErrorLogged) {
                        certErrorLogged = true
                        DebugLog.log("ERROR", hint)
                    }
                    // 始终更新 lastError，确保 UI 能展示提示
                    lastError = hint
                    return null
                }

                DebugLog.log("ERROR", "Zray 连接异常 (尝试$attempt/$maxRetries): $msg")
                // 可重试的错误：连接中断、超时、被拒绝
                val retryable = msg.contains("ECONNABORTED") ||
                        msg.contains("ECONNRESET") ||
                        msg.contains("ECONNREFUSED") ||
                        msg.contains("Connection reset") ||
                        msg.contains("Broken pipe") ||
                        msg.contains("timed out") ||
                        msg.contains("connect failed")
                if (!retryable || attempt == maxRetries) {
                    return null
                }
                // 退避等待
                delay((attempt * 500).toLong())
            }
        }
        return null
    }

    private suspend fun connectViaZrayOnce(atyp: Byte, rawAddr: ByteArray, targetPort: Int, targetHost: String): Socket {
        // 如果 remoteHost 是域名，使用安全 DNS 解析获取纯净 IP，防止 DNS 污染
        val resolvedHost = if (remoteHost.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            remoteHost
        } else {
            val ip = ZrayDnsResolver.resolveHost(remoteHost)
            DebugLog.log("PROXY", "节点域名解析: $remoteHost → $ip")
            ip
        }

        val tcpSocket = Socket()
        tcpSocket.connect(InetSocketAddress(resolvedHost, remotePort), 10000)
        tcpSocket.tcpNoDelay = true

        val sslCtx = buildSslContext()
        val sslSocket = sslCtx.socketFactory.createSocket(
            tcpSocket, remoteHost, remotePort, true
        ) as SSLSocket
        sslSocket.startHandshake()
        sslSocket.soTimeout = 30000

        val out = sslSocket.getOutputStream()

        // HTTP 伪装
        writeHttpCamo(out)
        // 协议头: ver(1) + ts(8) + nonce(8) + hash(16) = 33 bytes
        val header = ByteArray(33)
        header[0] = PROTOCOL_VERSION
        ByteBuffer.wrap(header, 1, 8).putLong(System.currentTimeMillis() / 1000)
        SecureRandom().nextBytes(header.sliceArray(9 until 17).also { System.arraycopy(it, 0, header, 9, 8) })
        val nonce = ByteArray(8); SecureRandom().nextBytes(nonce)
        System.arraycopy(nonce, 0, header, 9, 8)
        System.arraycopy(userHash.toByteArray(Charsets.ISO_8859_1), 0, header, 17, minOf(userHash.length, 16))
        out.write(header)
        // padding
        val padLen = 10 + (Math.random() * 50).toInt()
        out.write(padLen)
        val padding = ByteArray(padLen); SecureRandom().nextBytes(padding)
        out.write(padding)
        // CMD + 地址
        out.write(CMD_CONNECT.toInt())
        out.write(byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xFF).toByte()))
        out.write(atyp.toInt())
        out.write(rawAddr)
        out.flush()

        DebugLog.log("PROXY", "Zray/TLS 隧道: $targetHost:$targetPort")
        sslSocket.soTimeout = 0
        return sslSocket
    }

    private fun writeHttpCamo(out: OutputStream) {
        val paths = listOf("/", "/index.html", "/api/v1/status", "/search?q=keyword",
            "/blog/2024/01/welcome", "/static/css/main.css", "/ws", "/login",
            "/cdn-cgi/trace", "/favicon.ico", "/robots.txt")
        val path = paths.random()
        val sb = StringBuilder()
        sb.append("GET $path HTTP/1.1\r\n")
        sb.append("Host: $remoteHost\r\n")
        sb.append("Connection: keep-alive\r\nCache-Control: max-age=0\r\n")
        sb.append("sec-ch-ua: \"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\"\r\n")
        sb.append("sec-ch-ua-mobile: ?0\r\nsec-ch-ua-platform: \"Windows\"\r\n")
        sb.append("Upgrade-Insecure-Requests: 1\r\n")
        sb.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36\r\n")
        sb.append("Accept: text/html,application/xhtml+xml,*/*;q=0.8\r\n")
        sb.append("Accept-Encoding: gzip, deflate, br\r\nAccept-Language: en-US,en;q=0.9\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun startLatencyProbe() {
        latencyJob = scope.launch {
            while (isActive && running) {
                try {
                    if (remoteHost.isNotEmpty()) {
                        val start = System.currentTimeMillis()
                        val sock = Socket()
                        sock.connect(InetSocketAddress(remoteHost, remotePort), 5000)
                        latencyMs = System.currentTimeMillis() - start
                        sock.close()
                        DebugLog.log("LATENCY", "${latencyMs}ms → $remoteHost:$remotePort")
                    }
                } catch (e: Exception) { latencyMs = -1 }
                delay(5000)
            }
        }
    }

    private fun relayWithStats(input: InputStream, output: OutputStream, isUpload: Boolean) {
        val buf = ByteArray(32 * 1024)
        val counter = if (isUpload) TrafficStats.uploadBytes else TrafficStats.downloadBytes
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: java.io.IOException) {
            // Connection reset / EBADF / Broken pipe — 远端关闭或网络中断，视作流正常结束
        }
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw java.io.EOFException()
            offset += n
        }
    }
}
