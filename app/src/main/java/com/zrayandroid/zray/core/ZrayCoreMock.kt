package com.zrayandroid.zray.core

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Zray 核心 — 本地 SOCKS5 代理，通过 Zray 协议转发流量。
 * 
 * Zray 协议: TLS + HTTP伪装 + 自定义头(时间戳+nonce+userHash) + padding + CMD + 地址
 */
object ZrayCoreMock {
    private const val TAG = "ZrayCore"
    private const val PROTOCOL_VERSION: Byte = 0x01
    private const val CMD_CONNECT: Byte = 0x01
    private const val ATYP_IPV4: Byte = 0x01
    private const val ATYP_DOMAIN: Byte = 0x03
    private const val ATYP_IPV6: Byte = 0x04

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var latencyMs: Long = -1
        private set

    private var serverSocket: ServerSocket? = null
    private var latencyThread: Thread? = null

    private var remoteHost = ""
    private var remotePort = 64433
    private var userHash = "" // 16 bytes

    // 信任所有证书（自签证书需要）
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    fun startAsync(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        DebugLog.log("CORE", "startAsync 调用, isRunning=$isRunning, serverSocket=${serverSocket != null}")

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
                remotePort = json.optInt("remote_port", 64433)
                userHash = json.optString("user_hash", "")
            } else {
                parseSimpleConfig(config)
            }
        } catch (e: Exception) {
            DebugLog.log("CORE", "配置解析异常: ${e.message}")
        }

        // 确保 userHash 是 16 字节
        if (userHash.length > 16) userHash = userHash.substring(0, 16)
        while (userHash.length < 16) userHash += "\u0000"

        DebugLog.log("CORE", "远程: $remoteHost:$remotePort, userHash长度=${userHash.length}")
        DebugLog.log("CORE", "尝试启动 SOCKS5 端口: $socksPort")

        thread(name = "zray-start") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", socksPort))
                serverSocket = ss
                isRunning = true

                DebugLog.log("CORE", "SOCKS5 监听成功: 127.0.0.1:$socksPort, isBound=${ss.isBound}")
                onResult(true, null)

                startLatencyProbe()

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = ss.accept()
                        thread(name = "zray-conn") { handleSocks5(client) }
                    } catch (e: Exception) {
                        if (isRunning) DebugLog.log("CORE", "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                val msg = "端口 $socksPort 启动失败: ${e.message}"
                DebugLog.log("ERROR", msg)
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
        TrafficStats.reset()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        latencyThread?.interrupt()
        latencyThread = null
    }

    private fun parseSimpleConfig(config: String) {
        val cleaned = config.trim()
            .removePrefix("zray://")
            .removePrefix("socks5://")
            .removePrefix("socks://")

        // 支持 host:port:userhash 或 host:port
        val parts = cleaned.split(":")
        if (parts.size >= 2) {
            remoteHost = parts[0]
            remotePort = parts[1].toIntOrNull() ?: 64433
            if (parts.size >= 3) userHash = parts[2]
            DebugLog.log("CORE", "解析: $remoteHost:$remotePort")
        }
    }

    private fun handleSocks5(client: Socket) {
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
                output.flush()
                client.close()
                return
            }

            DebugLog.log("PROXY", "→ $targetHost:$targetPort (via Zray $remoteHost:$remotePort)")

            // 通过 Zray 协议连接远程
            val remote = connectViaZray(atyp.toByte(), rawAddrBytes, targetPort, targetHost)
            if (remote == null) {
                DebugLog.log("ERROR", "Zray 转发失败: $targetHost:$targetPort")
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            // 成功
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // 双向 relay（带流量统计）
            TrafficStats.activeConns.incrementAndGet()
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()
            val t1 = thread(name = "relay-up") {
                try { relayWithStats(client.getInputStream(), remoteOut, isUpload = true) } catch (_: Exception) {}
                try { remote.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
            val t2 = thread(name = "relay-down") {
                try { relayWithStats(remoteIn, client.getOutputStream(), isUpload = false) } catch (_: Exception) {}
                try { remote.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
            t1.join()
            t2.join()
            TrafficStats.activeConns.decrementAndGet()
        } catch (e: Exception) {
            DebugLog.log("ERROR", "SOCKS5: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Zray 协议连接:
     * 1. TCP 连接远程
     * 2. TLS 握手 (InsecureSkipVerify)
     * 3. HTTP 伪装头
     * 4. Zray 协议头 (ver + timestamp + nonce + userHash)
     * 5. 随机 padding
     * 6. CMD + 目标地址 (port + atyp + raw)
     * 7. 返回连接用于 relay
     */
    private fun connectViaZray(atyp: Byte, rawAddr: ByteArray, targetPort: Int, targetHost: String): Socket? {
        return try {
            // 1. TCP 连接
            val tcpSocket = Socket()
            tcpSocket.connect(InetSocketAddress(remoteHost, remotePort), 10000)
            tcpSocket.tcpNoDelay = true

            // 2. TLS 握手
            val sslSocket = sslContext.socketFactory.createSocket(
                tcpSocket, remoteHost, remotePort, true
            ) as SSLSocket
            sslSocket.startHandshake()
            sslSocket.soTimeout = 30000

            val out = sslSocket.getOutputStream()

            // 3. HTTP 伪装头
            writeHttpCamo(out, remoteHost)

            // 4. Zray 协议头: ver(1) + timestamp(8) + nonce(8) + userHash(16) = 33 bytes
            val header = ByteArray(33)
            header[0] = PROTOCOL_VERSION
            val timestamp = System.currentTimeMillis() / 1000
            ByteBuffer.wrap(header, 1, 8).putLong(timestamp)
            val nonce = ByteArray(8)
            SecureRandom().nextBytes(nonce)
            System.arraycopy(nonce, 0, header, 9, 8)
            val hashBytes = userHash.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(hashBytes, 0, header, 17, minOf(hashBytes.size, 16))
            out.write(header)

            // 5. 随机 padding: padLen(1) + random(padLen)
            val padLen = (10 + (Math.random() * 50).toInt())
            out.write(padLen)
            val padding = ByteArray(padLen)
            SecureRandom().nextBytes(padding)
            out.write(padding)

            // 6. CMD
            out.write(CMD_CONNECT.toInt())

            // 7. 目标地址: port(2) + atyp(1) + rawAddr
            val portBuf = ByteArray(2)
            portBuf[0] = (targetPort shr 8).toByte()
            portBuf[1] = (targetPort and 0xFF).toByte()
            out.write(portBuf)
            out.write(atyp.toInt())
            out.write(rawAddr)

            out.flush()

            DebugLog.log("PROXY", "Zray 隧道建立: $targetHost:$targetPort via TLS→$remoteHost:$remotePort")
            sslSocket.soTimeout = 0
            sslSocket
        } catch (e: Exception) {
            DebugLog.log("ERROR", "Zray 连接异常: ${e.message}")
            null
        }
    }

    /**
     * HTTP 伪装头 — 模拟正常浏览器 GET 请求
     */
    private fun writeHttpCamo(out: OutputStream, host: String) {
        val paths = listOf(
            "/", "/index.html", "/api/v1/status", "/search?q=keyword",
            "/blog/2024/01/welcome", "/static/css/main.css", "/ws",
            "/login", "/dashboard", "/assets/logo.png",
            "/cdn-cgi/trace", "/favicon.ico", "/robots.txt"
        )
        val path = paths.random()
        val sb = StringBuilder()
        sb.append("GET $path HTTP/1.1\r\n")
        sb.append("Host: $host\r\n")
        sb.append("Connection: keep-alive\r\n")
        sb.append("Cache-Control: max-age=0\r\n")
        sb.append("sec-ch-ua: \"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"\r\n")
        sb.append("sec-ch-ua-mobile: ?0\r\n")
        sb.append("sec-ch-ua-platform: \"Windows\"\r\n")
        sb.append("Upgrade-Insecure-Requests: 1\r\n")
        sb.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36\r\n")
        sb.append("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8\r\n")
        sb.append("Sec-Fetch-Site: none\r\n")
        sb.append("Sec-Fetch-Mode: navigate\r\n")
        sb.append("Sec-Fetch-User: ?1\r\n")
        sb.append("Sec-Fetch-Dest: document\r\n")
        sb.append("Accept-Encoding: gzip, deflate, br\r\n")
        sb.append("Accept-Language: en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
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
                    }
                } catch (_: Exception) {
                    latencyMs = -1
                }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun relayWithStats(input: InputStream, output: OutputStream, isUpload: Boolean) {
        val buf = ByteArray(32 * 1024)
        val counter = if (isUpload) TrafficStats.uploadBytes else TrafficStats.downloadBytes
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
            counter.addAndGet(n.toLong())
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
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
