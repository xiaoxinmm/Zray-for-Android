package com.zrayandroid.zray.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocketFactory

/**
 * DNS 协议类型
 */
enum class DnsProtocol {
    UDP, DOH, DOT
}

/**
 * 高级 DNS 解析器 — 支持 UDP / DoH (DNS over HTTPS) / DoT (DNS over TLS)。
 *
 * 所有方法均为挂起函数，在 Dispatchers.IO 上执行网络 I/O。
 * 异常被内部捕获并返回 null，不会导致调用方崩溃。
 */
object ZrayDnsResolver {

    /** 当前 DNS 协议 */
    @Volatile
    var protocol: DnsProtocol = DnsProtocol.DOH

    /** DNS 服务器地址：
     *  - UDP: IP 地址，如 "8.8.8.8"
     *  - DoH: 完整 URL，如 "https://dns.alidns.com/dns-query"
     *  - DoT: IP 地址，如 "1.1.1.1" 或 "1.1.1.1:853"
     */
    @Volatile
    var server: String = "https://dns.alidns.com/dns-query"

    /** VPN protect 回调 — 由 VpnService 设置，用于保护 DNS socket 不走 VPN 通道 */
    @Volatile
    var protectSocket: ((DatagramSocket) -> Boolean)? = null

    @Volatile
    var protectSocketFd: ((java.net.Socket) -> Boolean)? = null

    /**
     * 解析原始 DNS 报文（接收 UDP payload，返回 DNS 响应）。
     */
    suspend fun resolveRaw(queryBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            when (protocol) {
                DnsProtocol.UDP -> resolveViaUdp(queryBytes)
                DnsProtocol.DOH -> resolveViaDoH(queryBytes)
                DnsProtocol.DOT -> resolveViaDoT(queryBytes)
            }
        } catch (e: Exception) {
            DebugLog.log("DNS", "resolveRaw 失败 (${protocol.name}): ${e.message}")
            null
        }
    }

    /**
     * 域名解析辅助方法 — 将域名解析为 IPv4 地址字符串。
     * 手动构造 DNS A 记录查询报文，调用 resolveRaw，解析响应报文提取 IP。
     */
    suspend fun resolveHost(domain: String): String = withContext(Dispatchers.IO) {
        try {
            // 如果已经是 IP 地址，直接返回
            if (domain.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                return@withContext domain
            }

            val query = buildDnsQuery(domain)
            val response = resolveRaw(query)
            if (response != null) {
                val ip = parseDnsARecord(response)
                if (ip != null) {
                    DebugLog.log("DNS", "解析 $domain → $ip (${protocol.name})")
                    return@withContext ip
                }
            }

            // 回退到系统 DNS
            DebugLog.log("DNS", "高级 DNS 解析失败，回退系统 DNS: $domain")
            val addr = InetAddress.getByName(domain)
            addr.hostAddress ?: domain
        } catch (e: Exception) {
            DebugLog.log("DNS", "resolveHost 失败: $domain: ${e.message}")
            // 最终回退：返回原始域名，让系统处理
            domain
        }
    }

    // ==================== UDP DNS ====================

    private fun resolveViaUdp(queryBytes: ByteArray): ByteArray? {
        val addr = parseUdpServer()
        val socket = DatagramSocket()
        try {
            protectSocket?.invoke(socket)
            socket.soTimeout = 5000
            val packet = DatagramPacket(queryBytes, queryBytes.size, InetAddress.getByName(addr.first), addr.second)
            socket.send(packet)

            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            return buf.copyOf(resp.length)
        } finally {
            socket.close()
        }
    }

    private fun parseUdpServer(): Pair<String, Int> {
        val s = server.trim()
        val parts = s.split(":")
        return if (parts.size == 2) {
            Pair(parts[0], parts[1].toIntOrNull() ?: 53)
        } else {
            Pair(s, 53)
        }
    }

    // ==================== DoH (DNS over HTTPS) ====================

    private fun resolveViaDoH(queryBytes: ByteArray): ByteArray? {
        val url = URL(server.trim())
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            conn.outputStream.use { it.write(queryBytes) }

            if (conn.responseCode != 200) {
                DebugLog.log("DNS", "DoH 响应码: ${conn.responseCode}")
                return null
            }

            val baos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    baos.write(buf, 0, n)
                }
            }
            return baos.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    // ==================== DoT (DNS over TLS) ====================

    private fun resolveViaDoT(queryBytes: ByteArray): ByteArray? {
        val (host, port) = parseDotServer()
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory

        val rawSocket = java.net.Socket()
        try {
            protectSocketFd?.invoke(rawSocket)
            rawSocket.connect(InetSocketAddress(host, port), 5000)

            val sslSocket = factory.createSocket(rawSocket, host, port, true) as javax.net.ssl.SSLSocket
            sslSocket.soTimeout = 5000
            sslSocket.startHandshake()

            val output = sslSocket.getOutputStream()
            val input = sslSocket.getInputStream()

            // RFC 7858: TCP/TLS 上的 DNS 报文需要 2 字节长度前缀
            val lenPrefix = ByteArray(2)
            lenPrefix[0] = (queryBytes.size shr 8).toByte()
            lenPrefix[1] = (queryBytes.size and 0xFF).toByte()
            output.write(lenPrefix)
            output.write(queryBytes)
            output.flush()

            // 读取 2 字节长度
            val respLenBuf = ByteArray(2)
            readFully(input, respLenBuf)
            val respLen = ((respLenBuf[0].toInt() and 0xFF) shl 8) or (respLenBuf[1].toInt() and 0xFF)

            if (respLen <= 0 || respLen > 65535) return null

            // 读取响应报文
            val respBuf = ByteArray(respLen)
            readFully(input, respBuf)

            sslSocket.close()
            return respBuf
        } finally {
            try { rawSocket.close() } catch (_: Exception) {}
        }
    }

    private fun parseDotServer(): Pair<String, Int> {
        val s = server.trim()
        val parts = s.split(":")
        return if (parts.size == 2) {
            Pair(parts[0], parts[1].toIntOrNull() ?: 853)
        } else {
            Pair(s, 853)
        }
    }

    // ==================== DNS 报文构造与解析 ====================

    /**
     * 构造一个最简 DNS A 记录查询报文。
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Transaction ID (随机)
        val txId = (Math.random() * 0xFFFF).toInt()
        baos.write(txId shr 8)
        baos.write(txId and 0xFF)

        // Flags: 标准递归查询 (RD=1)
        baos.write(0x01)
        baos.write(0x00)

        // Questions: 1
        baos.write(0x00)
        baos.write(0x01)

        // Answer/Authority/Additional RRs: 0
        baos.write(0x00); baos.write(0x00)
        baos.write(0x00); baos.write(0x00)
        baos.write(0x00); baos.write(0x00)

        // QNAME: 编码域名
        for (label in domain.split(".")) {
            baos.write(label.length)
            baos.write(label.toByteArray(Charsets.US_ASCII))
        }
        baos.write(0x00) // 终止

        // QTYPE: A (1)
        baos.write(0x00)
        baos.write(0x01)

        // QCLASS: IN (1)
        baos.write(0x00)
        baos.write(0x01)

        return baos.toByteArray()
    }

    /**
     * 从 DNS 响应报文中解析第一个 A 记录（Type=1, Class=1）的 IPv4 地址。
     * 极简实现，仅支持 A 记录解析。
     */
    private fun parseDnsARecord(response: ByteArray): String? {
        if (response.size < 12) return null

        val buf = ByteBuffer.wrap(response)

        // 跳过 Header: Transaction ID(2) + Flags(2) + QDCOUNT(2) + ANCOUNT(2) + NSCOUNT(2) + ARCOUNT(2)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (anCount == 0) return null

        // 跳过 Questions 段
        var pos = 12
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        for (i in 0 until qdCount) {
            pos = skipDnsName(response, pos) ?: return null
            pos += 4 // QTYPE(2) + QCLASS(2)
        }

        // 解析 Answer 段
        for (i in 0 until anCount) {
            if (pos >= response.size) return null

            // 跳过 NAME（可能是压缩指针）
            pos = skipDnsName(response, pos) ?: return null

            if (pos + 10 > response.size) return null

            val rtype = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            val rclass = ((response[pos + 2].toInt() and 0xFF) shl 8) or (response[pos + 3].toInt() and 0xFF)
            // TTL: pos+4..pos+7
            val rdLength = ((response[pos + 8].toInt() and 0xFF) shl 8) or (response[pos + 9].toInt() and 0xFF)
            pos += 10

            if (rtype == 1 && rclass == 1 && rdLength == 4) {
                // A record — 4 bytes IPv4
                if (pos + 4 > response.size) return null
                return "${response[pos].toInt() and 0xFF}.${response[pos + 1].toInt() and 0xFF}.${response[pos + 2].toInt() and 0xFF}.${response[pos + 3].toInt() and 0xFF}"
            }

            pos += rdLength
        }

        return null
    }

    /**
     * 跳过 DNS 名称字段（支持压缩指针）。
     * 返回跳过后的偏移量，如果解析失败返回 null。
     */
    private fun skipDnsName(data: ByteArray, startPos: Int): Int? {
        var pos = startPos
        var jumped = false
        var maxLoops = 128 // 防止无限循环

        while (pos < data.size && maxLoops-- > 0) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                // 名称终止
                return if (jumped) startPos + 2 else pos + 1
            }
            if ((len and 0xC0) == 0xC0) {
                // 压缩指针：2 字节
                if (!jumped) {
                    // 第一次遇到指针，记录返回位置
                    val returnPos = pos + 2
                    val offset = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos = offset
                    jumped = true
                    // 但最终要返回指针后面的位置
                    if (maxLoops <= 0) return null
                    // 继续解析指针指向的名称，最终返回 returnPos
                    // 简化：直接跟随指针但记录返回位置
                    var innerPos = pos
                    while (innerPos < data.size && maxLoops-- > 0) {
                        val innerLen = data[innerPos].toInt() and 0xFF
                        if (innerLen == 0) return returnPos
                        if ((innerLen and 0xC0) == 0xC0) {
                            return returnPos
                        }
                        innerPos += innerLen + 1
                    }
                    return returnPos
                } else {
                    return startPos + 2
                }
            }
            pos += len + 1
        }
        return null
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw java.io.EOFException("DNS stream ended prematurely")
            offset += n
        }
    }
}
