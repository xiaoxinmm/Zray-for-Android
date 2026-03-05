package com.zrayandroid.zray.core

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Zray 核心 Mock — 包含真实的本地 SOCKS5 端口监听。
 * 后续替换为 zraylib.aar 时，替换 start/stop 内部实现即可。
 */
object ZrayCoreMock {
    private const val TAG = "ZrayCore"

    @Volatile
    var isRunning = false
        private set

    private var serverSocket: ServerSocket? = null
    private var listenThread: Thread? = null

    // 配置
    private var remoteHost = ""
    private var remotePort = 64433
    private var userHash = ""

    fun start(config: String, socksPort: Int) {
        if (isRunning) {
            DebugLog.log("CORE", "已经在运行中，跳过")
            return
        }

        Log.i(TAG, "核心启动")
        DebugLog.log("CORE", "核心启动，SOCKS5 端口: $socksPort")

        // 解析配置
        try {
            val json = org.json.JSONObject(config)
            remoteHost = json.optString("remote_host", "")
            remotePort = json.optInt("remote_port", 64433)
            userHash = json.optString("user_hash", "")
            DebugLog.log("CORE", "远程服务器: $remoteHost:$remotePort")
        } catch (e: Exception) {
            DebugLog.log("CORE", "配置解析: ${e.message}")
        }

        isRunning = true

        // 启动本地 SOCKS5 监听
        listenThread = thread(name = "zray-socks5") {
            try {
                serverSocket = ServerSocket()
                serverSocket!!.reuseAddress = true
                serverSocket!!.bind(InetSocketAddress("127.0.0.1", socksPort))
                DebugLog.log("CORE", "SOCKS5 监听已启动: 127.0.0.1:$socksPort")

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = serverSocket!!.accept()
                        DebugLog.log("PROXY", "新连接: ${client.remoteSocketAddress}")
                        thread(name = "zray-conn") {
                            handleSocks5(client)
                        }
                    } catch (e: Exception) {
                        if (isRunning) DebugLog.log("CORE", "accept 异常: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("ERROR", "SOCKS5 监听失败: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        Log.i(TAG, "核心停止")
        DebugLog.log("CORE", "核心停止")
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenThread?.interrupt()
        listenThread = null
    }

    /**
     * 简单的 SOCKS5 处理。
     * 目前实现 CONNECT 命令，直连目标（后续替换为通过 Zray 远程服务器隧道）。
     */
    private fun handleSocks5(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. 握手
            val ver = input.read()
            if (ver != 5) { client.close(); return }
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            // 回复：不需要认证
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. 请求
            val reqVer = input.read()
            val cmd = input.read()
            input.read() // RSV
            val atyp = input.read()

            val targetHost: String
            val targetPort: Int

            when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    targetHost = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // Domain
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    targetHost = String(domain)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    targetHost = addr.joinToString(":") { "%02x".format(it) }
                }
                else -> { client.close(); return }
            }

            val portHi = input.read()
            val portLo = input.read()
            targetPort = (portHi shl 8) or portLo

            if (cmd != 0x01) { // 只支持 CONNECT
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0))
                output.flush()
                client.close()
                return
            }

            DebugLog.log("PROXY", "→ $targetHost:$targetPort")

            // TODO: 这里应该通过 Zray 远程隧道连接，目前直连
            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(targetHost, targetPort), 10000)
            } catch (e: Exception) {
                DebugLog.log("ERROR", "连接失败: $targetHost:$targetPort - ${e.message}")
                // 回复连接失败
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0))
                output.flush()
                client.close()
                return
            }

            // 回复成功
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0))
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
            DebugLog.log("ERROR", "SOCKS5 处理异常: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
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
