package com.zrayandroid.zray.core

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Zray 核心 — 本地 SOCKS5 代理，通过远程服务器转发流量。
 */
object ZrayCoreMock {
    private const val TAG = "ZrayCore"

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var latencyMs: Long = -1
        private set

    private var serverSocket: ServerSocket? = null
    private var listenThread: Thread? = null
    private var latencyThread: Thread? = null

    private var remoteHost = ""
    private var remotePort = 10029
    private var userHash = ""
    private var proxyMode = "socks5" // socks5 链式转发

    /**
     * 启动核心，异步回调结果。
     */
    fun startAsync(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        DebugLog.log("CORE", "startAsync 调用, isRunning=$isRunning, serverSocket=${serverSocket != null}")

        // 强制清理旧状态
        if (isRunning || serverSocket != null) {
            DebugLog.log("CORE", "发现旧实例，先清理")
            stop()
            try { Thread.sleep(100) } catch (_: Exception) {}
        }

        // 解析配置
        try {
            if (config.trimStart().startsWith("{")) {
                val json = org.json.JSONObject(config)
                remoteHost = json.optString("remote_host", "")
                remotePort = json.optInt("remote_port", 10029)
                userHash = json.optString("user_hash", "")
                proxyMode = json.optString("proxy_mode", "socks5")
            } else {
                // 尝试解析 host:port 格式
                parseSimpleConfig(config)
            }
        } catch (e: Exception) {
            DebugLog.log("CORE", "配置解析异常: ${e.message}")
        }

        DebugLog.log("CORE", "远程服务器: $remoteHost:$remotePort, 模式: $proxyMode")
        DebugLog.log("CORE", "尝试启动 SOCKS5 端口: $socksPort")

        thread(name = "zray-start") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", socksPort))
                serverSocket = ss
                isRunning = true

                DebugLog.log("CORE", "SOCKS5 监听成功: 127.0.0.1:$socksPort, localPort=${ss.localPort}, isBound=${ss.isBound}")
                onResult(true, null)

                // 启动延迟探测
                startLatencyProbe()

                // 接受连接循环
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = ss.accept()
                        thread(name = "zray-conn") {
                            handleSocks5(client)
                        }
                    } catch (e: Exception) {
                        if (isRunning) DebugLog.log("CORE", "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                val msg = "端口 $socksPort 启动失败: ${e.message}"
                DebugLog.log("ERROR", msg)
                Log.e(TAG, msg, e)
                isRunning = false
                onResult(false, msg)
            }
        }
    }

    fun stop() {
        if (!isRunning && serverSocket == null) return
        DebugLog.log("CORE", "核心停止")
        isRunning = false
        latencyMs = -1
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenThread?.interrupt()
        listenThread = null
        latencyThread?.interrupt()
        latencyThread = null
    }

    private fun parseSimpleConfig(config: String) {
        // 支持格式: host:port 或 zray://host:port 或纯链接
        val cleaned = config.trim()
            .removePrefix("zray://")
            .removePrefix("socks5://")
            .removePrefix("socks://")

        val parts = cleaned.split(":")
        if (parts.size >= 2) {
            remoteHost = parts[0]
            remotePort = parts[1].toIntOrNull() ?: 10029
            DebugLog.log("CORE", "解析配置: $remoteHost:$remotePort")
        } else if (parts.size == 1 && parts[0].isNotEmpty()) {
            remoteHost = parts[0]
            DebugLog.log("CORE", "解析配置(默认端口): $remoteHost:$remotePort")
        }
    }

    private fun handleSocks5(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // === SOCKS5 握手 ===
            val ver = input.read()
            if (ver != 5) { client.close(); return }
            val nMethods = input.read()
            if (nMethods <= 0 || nMethods > 255) { client.close(); return }
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            // 回复: 无需认证
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // === SOCKS5 请求 ===
            val reqVer = input.read() // VER
            val cmd = input.read()     // CMD
            val rsv = input.read()     // RSV
            val atyp = input.read()    // ATYP

            val targetHost: String
            val rawAddrBytes: ByteArray // 保存原始地址字节用于转发

            when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    rawAddrBytes = addr
                    targetHost = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // 域名
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    rawAddrBytes = byteArrayOf(len.toByte()) + domain
                    targetHost = String(domain)
                }
                0x04 -> { // IPv6
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

            if (cmd != 0x01) { // 只支持 CONNECT
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            DebugLog.log("PROXY", "→ $targetHost:$targetPort${if (remoteHost.isNotEmpty()) " (via $remoteHost:$remotePort)" else " (直连)"}")

            val remote: Socket
            if (remoteHost.isNotEmpty()) {
                // === 通过远程服务器转发 (SOCKS5 链式) ===
                remote = connectViaRemote(atyp, rawAddrBytes, targetPort, targetHost)
                    ?: run {
                        DebugLog.log("ERROR", "远程转发失败: $targetHost:$targetPort")
                        output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        client.close()
                        return
                    }
            } else {
                // === 直连模式 (无远程服务器) ===
                remote = Socket()
                try {
                    remote.connect(InetSocketAddress(targetHost, targetPort), 10000)
                } catch (e: Exception) {
                    DebugLog.log("ERROR", "直连失败: $targetHost:$targetPort - ${e.message}")
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    client.close()
                    return
                }
            }

            // 回复客户端: 成功
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // 双向转发
            val t1 = thread(name = "relay-up") {
                try { relay(client.getInputStream(), remote.getOutputStream()) } catch (_: Exception) {}
                try { remote.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
            val t2 = thread(name = "relay-down") {
                try { relay(remote.getInputStream(), client.getOutputStream()) } catch (_: Exception) {}
                try { remote.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
            t1.join()
            t2.join()
        } catch (e: Exception) {
            DebugLog.log("ERROR", "SOCKS5: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * 通过远程 SOCKS5 服务器链式转发。
     * 向远程发送完整的 SOCKS5 握手+请求，建立隧道。
     */
    private fun connectViaRemote(atyp: Int, rawAddr: ByteArray, targetPort: Int, targetHost: String): Socket? {
        return try {
            val remote = Socket()
            remote.connect(InetSocketAddress(remoteHost, remotePort), 10000)
            remote.soTimeout = 30000

            val rIn = remote.getInputStream()
            val rOut = remote.getOutputStream()

            // SOCKS5 握手
            rOut.write(byteArrayOf(0x05, 0x01, 0x00)) // VER, 1 method, NO AUTH
            rOut.flush()

            val sVer = rIn.read()
            val sMethod = rIn.read()
            if (sVer != 0x05 || sMethod != 0x00) {
                DebugLog.log("ERROR", "远程握手失败: ver=$sVer method=$sMethod")
                remote.close()
                return null
            }

            // SOCKS5 CONNECT 请求
            val req = mutableListOf<Byte>()
            req.add(0x05) // VER
            req.add(0x01) // CMD: CONNECT
            req.add(0x00) // RSV
            req.add(atyp.toByte()) // ATYP
            rawAddr.forEach { req.add(it) }
            req.add((targetPort shr 8).toByte())
            req.add((targetPort and 0xFF).toByte())

            rOut.write(req.toByteArray())
            rOut.flush()

            // 读取回复
            val repVer = rIn.read()
            val repStatus = rIn.read()
            rIn.read() // RSV
            val repAtyp = rIn.read()

            // 跳过绑定地址
            when (repAtyp) {
                0x01 -> { val skip = ByteArray(4); rIn.readFully(skip) }
                0x03 -> { val len = rIn.read(); val skip = ByteArray(len); rIn.readFully(skip) }
                0x04 -> { val skip = ByteArray(16); rIn.readFully(skip) }
            }
            rIn.read() // port hi
            rIn.read() // port lo

            if (repStatus != 0x00) {
                DebugLog.log("ERROR", "远程拒绝连接: status=$repStatus, target=$targetHost:$targetPort")
                remote.close()
                return null
            }

            DebugLog.log("PROXY", "远程隧道建立: $targetHost:$targetPort via $remoteHost:$remotePort")
            remote.soTimeout = 0 // 隧道建立后取消超时
            remote
        } catch (e: Exception) {
            DebugLog.log("ERROR", "远程连接异常: ${e.message}")
            null
        }
    }

    private fun startLatencyProbe() {
        latencyThread = thread(name = "zray-latency", isDaemon = true) {
            while (isRunning) {
                try {
                    if (remoteHost.isNotEmpty()) {
                        val start = System.currentTimeMillis()
                        val sock = Socket()
                        sock.connect(InetSocketAddress(remoteHost, remotePort), 5000)
                        latencyMs = System.currentTimeMillis() - start
                        sock.close()
                        DebugLog.log("LATENCY", "${latencyMs}ms → $remoteHost:$remotePort")
                    } else {
                        val start = System.currentTimeMillis()
                        val sock = Socket()
                        sock.connect(InetSocketAddress("127.0.0.1", serverSocket?.localPort ?: 1081), 1000)
                        latencyMs = System.currentTimeMillis() - start
                        sock.close()
                    }
                } catch (e: Exception) {
                    latencyMs = -1
                }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
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
