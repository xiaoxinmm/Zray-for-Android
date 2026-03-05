package com.zrayandroid.zray.core

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Zray 核心 Mock — 真实本地 SOCKS5 端口监听。
 */
object ZrayCoreMock {
    private const val TAG = "ZrayCore"

    @Volatile
    var isRunning = false
        private set

    private var serverSocket: ServerSocket? = null
    private var listenThread: Thread? = null

    private var remoteHost = ""
    private var remotePort = 64433
    private var userHash = ""

    /**
     * 启动核心，异步回调结果。
     * onResult: true=成功, false=失败(附带错误信息)
     */
    fun startAsync(config: String, socksPort: Int, onResult: (Boolean, String?) -> Unit) {
        if (isRunning) {
            onResult(true, null)
            return
        }

        // 解析配置
        try {
            if (config.trimStart().startsWith("{")) {
                val json = org.json.JSONObject(config)
                remoteHost = json.optString("remote_host", "")
                remotePort = json.optInt("remote_port", 64433)
                userHash = json.optString("user_hash", "")
            } else {
                DebugLog.log("CORE", "非JSON配置，使用原始链接模式")
            }
        } catch (e: Exception) {
            DebugLog.log("CORE", "配置解析异常: ${e.message}")
        }

        DebugLog.log("CORE", "尝试启动 SOCKS5 端口: $socksPort")

        thread(name = "zray-start") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", socksPort))
                serverSocket = ss
                isRunning = true

                DebugLog.log("CORE", "SOCKS5 监听成功: 127.0.0.1:$socksPort")
                onResult(true, null)

                // 接受连接循环
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = ss.accept()
                        DebugLog.log("PROXY", "新连接: ${client.remoteSocketAddress}")
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
        if (!isRunning) return
        DebugLog.log("CORE", "核心停止")
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenThread?.interrupt()
        listenThread = null
    }

    private fun handleSocks5(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 握手
            val ver = input.read()
            if (ver != 5) { client.close(); return }
            val nMethods = input.read()
            if (nMethods <= 0 || nMethods > 255) { client.close(); return }
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 请求
            input.read() // VER
            val cmd = input.read()
            input.read() // RSV
            val atyp = input.read()

            val targetHost: String = when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> {
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    String(domain)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    addr.joinToString(":") { "%02x".format(it) }
                }
                else -> { client.close(); return }
            }

            val portHi = input.read()
            val portLo = input.read()
            val targetPort = (portHi shl 8) or portLo

            if (cmd != 0x01) {
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0))
                output.flush()
                client.close()
                return
            }

            DebugLog.log("PROXY", "→ $targetHost:$targetPort")

            // TODO: 通过 Zray 远程隧道，目前直连
            val remote = Socket()
            try {
                remote.connect(InetSocketAddress(targetHost, targetPort), 10000)
            } catch (e: Exception) {
                DebugLog.log("ERROR", "连接失败: $targetHost:$targetPort - ${e.message}")
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0))
                output.flush()
                client.close()
                return
            }

            // 成功
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0))
            output.flush()

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
