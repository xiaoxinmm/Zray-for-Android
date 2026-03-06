package com.zrayandroid.zray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.zrayandroid.zray.MainActivity
import com.zrayandroid.zray.R
import com.zrayandroid.zray.core.DebugLog
import com.zrayandroid.zray.core.PerAppMode
import com.zrayandroid.zray.core.ProxyMode
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ZrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.zrayandroid.zray.VPN_START"
        const val ACTION_STOP = "com.zrayandroid.zray.VPN_STOP"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_MODE = "proxy_mode"
        const val EXTRA_PER_APP_MODE = "per_app_mode"
        const val EXTRA_SELECTED_APPS = "selected_apps"
        const val CHANNEL_ID = "zray_vpn"
        const val NOTIFICATION_ID = 2
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val VPN_MTU = 1500
        val isRunning = AtomicBoolean(false)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var socksPort = 1081
    private val sessions = ConcurrentHashMap<Int, TcpSession>()
    private var seqCounter = 100000

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DebugLog.log("VPN", "ZrayVpnService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_START -> {
                socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1081)
                val mode = try { ProxyMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") } catch (_: Exception) { ProxyMode.VPN_GLOBAL }
                val perAppMode = try { PerAppMode.valueOf(intent.getStringExtra(EXTRA_PER_APP_MODE) ?: "") } catch (_: Exception) { PerAppMode.WHITELIST }
                val apps = intent.getStringArrayListExtra(EXTRA_SELECTED_APPS)?.toSet() ?: emptySet()
                startVpn(mode, perAppMode, apps)
            }
        }
        return START_STICKY
    }

    private fun startVpn(mode: ProxyMode, perAppMode: PerAppMode, selectedApps: Set<String>) {
        if (running.get()) return
        DebugLog.log("VPN", "启动 VPN: mode=$mode, port=$socksPort, apps=${selectedApps.size}")

        val builder = Builder()
            .setSession("Zray VPN")
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, 0)
            .addDnsServer(VPN_DNS)
            .addDnsServer("8.8.4.4")

        // 分应用
        if (mode == ProxyMode.VPN_PER_APP && selectedApps.isNotEmpty()) {
            when (perAppMode) {
                PerAppMode.WHITELIST -> {
                    for (pkg in selectedApps) {
                        try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                    }
                }
                PerAppMode.BLACKLIST -> {
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                    for (pkg in selectedApps) {
                        try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                    }
                }
            }
        } else {
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }

        vpnInterface = try { builder.establish() } catch (e: Exception) {
            DebugLog.log("ERROR", "VPN 创建失败: ${e.message}"); return
        }
        if (vpnInterface == null) { DebugLog.log("ERROR", "VPN 接口为空"); return }

        running.set(true)
        isRunning.set(true)

        val notif = buildNotification("Zray VPN 运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        workerThread = thread(name = "vpn-worker") { runTunnel() }
        DebugLog.log("VPN", "VPN 启动成功")
    }

    fun stopVpn() {
        if (!running.getAndSet(false)) return
        DebugLog.log("VPN", "停止 VPN")
        isRunning.set(false)
        sessions.values.forEach { it.close() }
        sessions.clear()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        workerThread?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { stopVpn() }

    // ==================== TUN 转发 ====================

    private fun runTunnel() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buf = ByteBuffer.allocate(VPN_MTU)

        // 回包线程：从 SOCKS5 读取响应写回 TUN
        thread(name = "vpn-resp", isDaemon = true) {
            while (running.get()) {
                try {
                    val iter = sessions.entries.iterator()
                    while (iter.hasNext()) {
                        val (port, session) = iter.next()
                        try {
                            val data = session.tryRead()
                            if (data != null && data.isNotEmpty()) {
                                val pkt = buildTcpDataPacket(session, data)
                                if (pkt != null) synchronized(output) { output.write(pkt); output.flush() }
                            }
                        } catch (_: Exception) {
                            iter.remove()
                            session.close()
                        }
                    }
                    Thread.sleep(1)
                } catch (_: InterruptedException) { break }
                catch (e: Exception) { if (running.get()) DebugLog.log("VPN", "回包: ${e.message}") }
            }
        }

        try {
            while (running.get()) {
                buf.clear()
                val n = input.read(buf.array())
                if (n <= 0) continue
                buf.limit(n)
                handlePacket(buf, output)
            }
        } catch (e: Exception) {
            if (running.get()) DebugLog.log("VPN", "TUN: ${e.message}")
        }
    }

    private fun handlePacket(pkt: ByteBuffer, out: FileOutputStream) {
        if (pkt.limit() < 20) return
        val b0 = pkt.get(0).toInt() and 0xFF
        if (b0 shr 4 != 4) return // IPv4 only
        val ihl = (b0 and 0x0F) * 4
        val proto = pkt.get(9).toInt() and 0xFF
        val srcIp = ipStr(pkt, 12)
        val dstIp = ipStr(pkt, 16)
        when (proto) {
            6 -> handleTcp(pkt, ihl, srcIp, dstIp, out)
            17 -> handleUdp(pkt, ihl, srcIp, dstIp, out)
        }
    }

    private fun handleTcp(pkt: ByteBuffer, ihl: Int, srcIp: String, dstIp: String, out: FileOutputStream) {
        if (pkt.limit() < ihl + 20) return
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        val seq = i32(pkt, ihl + 4)
        val ack = i32(pkt, ihl + 8)
        val doff = ((pkt.get(ihl + 12).toInt() and 0xFF) shr 4) * 4
        val flags = pkt.get(ihl + 13).toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val ackF = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val hdrEnd = ihl + doff
        val payloadLen = pkt.limit() - hdrEnd

        if (rst) { sessions.remove(srcPort)?.close(); return }

        if (syn && !ackF) {
            // 新连接
            val s = TcpSession(srcIp, srcPort, dstIp, dstPort, seq, seqCounter)
            seqCounter += 10000
            sessions[srcPort] = s
            thread(name = "socks-$srcPort") {
                try {
                    val sock = Socket()
                    protect(sock)
                    sock.connect(InetSocketAddress("127.0.0.1", socksPort), 10000)
                    sock.tcpNoDelay = true
                    sock.soTimeout = 100
                    // SOCKS5 handshake
                    val o = sock.getOutputStream(); val i = sock.getInputStream()
                    o.write(byteArrayOf(5, 1, 0)); o.flush()
                    val a = ByteArray(2); i.read(a)
                    // CONNECT
                    val ip = dstIp.split(".").map { it.toInt().toByte() }.toByteArray()
                    o.write(byteArrayOf(5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3],
                        (dstPort shr 8).toByte(), (dstPort and 0xFF).toByte())); o.flush()
                    val r = ByteArray(10); i.read(r)
                    if (r[1] != 0.toByte()) throw Exception("SOCKS5 refused")
                    s.socket = sock
                    s.clientSeq = seq + 1
                    s.state = TcpSession.State.ESTABLISHED
                    // SYN-ACK
                    val sa = buildCtl(s, syn = true, ack = true)
                    if (sa != null) synchronized(out) { out.write(sa); out.flush() }
                    s.serverSeq++
                    DebugLog.log("VPN", "→ $dstIp:$dstPort OK")
                } catch (e: Exception) {
                    DebugLog.log("VPN", "→ $dstIp:$dstPort 失败: ${e.message}")
                    sessions.remove(srcPort)
                    val r2 = buildCtl(s, rst = true, ack = true)
                    if (r2 != null) try { synchronized(out) { out.write(r2); out.flush() } } catch (_: Exception) {}
                }
            }
            return
        }

        val s = sessions[srcPort] ?: return

        if (fin) {
            s.clientSeq = seq + 1
            val fa = buildCtl(s, fin = true, ack = true)
            if (fa != null) synchronized(out) { out.write(fa); out.flush() }
            s.serverSeq++
            sessions.remove(srcPort)?.close()
            return
        }

        if (ackF && payloadLen > 0 && s.state == TcpSession.State.ESTABLISHED) {
            val payload = ByteArray(payloadLen)
            System.arraycopy(pkt.array(), hdrEnd, payload, 0, payloadLen)
            s.clientSeq = seq + payloadLen
            s.writeToRemote(payload)
            val a2 = buildCtl(s, ack = true)
            if (a2 != null) synchronized(out) { out.write(a2); out.flush() }
        }
    }

    private fun handleUdp(pkt: ByteBuffer, ihl: Int, srcIp: String, dstIp: String, out: FileOutputStream) {
        if (pkt.limit() < ihl + 8) return
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        if (dstPort != 53) return
        val udpLen = u16(pkt, ihl + 4)
        val payStart = ihl + 8
        val payLen = udpLen - 8
        if (payLen <= 0) return
        val query = ByteArray(payLen)
        System.arraycopy(pkt.array(), payStart, query, 0, payLen)

        thread(name = "dns") {
            try {
                val ds = java.net.DatagramSocket()
                protect(ds)
                ds.send(java.net.DatagramPacket(query, query.size, java.net.InetAddress.getByName(dstIp), 53))
                ds.soTimeout = 5000
                val resp = ByteArray(1500)
                val rp = java.net.DatagramPacket(resp, resp.size)
                ds.receive(rp); ds.close()
                val udpResp = buildUdpPacket(dstIp, srcIp, 53, srcPort, resp.copyOf(rp.length))
                if (udpResp != null) synchronized(out) { out.write(udpResp); out.flush() }
            } catch (_: Exception) {}
        }
    }

    // ==================== 数据包构建 ====================

    private fun buildCtl(s: TcpSession, syn: Boolean = false, ack: Boolean = false, fin: Boolean = false, rst: Boolean = false): ByteArray? {
        return buildTcp(s, ByteArray(0), syn, ack, fin, rst)
    }

    private fun buildTcpDataPacket(s: TcpSession, data: ByteArray): ByteArray? {
        val p = buildTcp(s, data, ack = true)
        if (p != null) s.serverSeq += data.size
        return p
    }

    private fun buildTcp(s: TcpSession, payload: ByteArray, syn: Boolean = false, ack: Boolean = false, fin: Boolean = false, rst: Boolean = false): ByteArray? {
        try {
            val total = 40 + payload.size
            val p = ByteArray(total)
            val b = ByteBuffer.wrap(p)
            // IP
            b.put(0x45.toByte()); b.put(0); b.putShort(total.toShort()); b.putShort(0)
            b.putShort(0x4000.toShort()); b.put(64); b.put(6); b.putShort(0)
            putIp(b, s.dstIp); putIp(b, s.srcIp)
            val ipCs = checksum(p, 0, 20)
            p[10] = (ipCs shr 8).toByte(); p[11] = (ipCs and 0xFF).toByte()
            // TCP
            b.position(20)
            b.putShort(s.dstPort.toShort()); b.putShort(s.srcPort.toShort())
            b.putInt(s.serverSeq); b.putInt(s.clientSeq)
            b.put(0x50.toByte())
            var f = 0; if (syn) f = f or 2; if (ack) f = f or 16; if (fin) f = f or 1; if (rst) f = f or 4
            b.put(f.toByte())
            b.putShort(0xFFFF.toInt().toShort()); b.putShort(0); b.putShort(0)
            if (payload.isNotEmpty()) b.put(payload)
            val tcpCs = tcpChecksum(p, 20, total - 20, s.dstIp, s.srcIp)
            p[36] = (tcpCs shr 8).toByte(); p[37] = (tcpCs and 0xFF).toByte()
            return p
        } catch (_: Exception) { return null }
    }

    private fun buildUdpPacket(srcIp: String, dstIp: String, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray? {
        try {
            val total = 28 + payload.size
            val p = ByteArray(total)
            val b = ByteBuffer.wrap(p)
            b.put(0x45.toByte()); b.put(0); b.putShort(total.toShort()); b.putShort(0)
            b.putShort(0x4000.toShort()); b.put(64); b.put(17); b.putShort(0)
            putIp(b, srcIp); putIp(b, dstIp)
            val cs = checksum(p, 0, 20)
            p[10] = (cs shr 8).toByte(); p[11] = (cs and 0xFF).toByte()
            b.position(20)
            b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
            b.putShort((8 + payload.size).toShort()); b.putShort(0)
            b.put(payload)
            return p
        } catch (_: Exception) { return null }
    }

    // ==================== 工具 ====================

    private fun ipStr(b: ByteBuffer, off: Int) = "${b.get(off).toInt() and 0xFF}.${b.get(off+1).toInt() and 0xFF}.${b.get(off+2).toInt() and 0xFF}.${b.get(off+3).toInt() and 0xFF}"
    private fun u16(b: ByteBuffer, off: Int) = ((b.get(off).toInt() and 0xFF) shl 8) or (b.get(off+1).toInt() and 0xFF)
    private fun i32(b: ByteBuffer, off: Int) = ((b.get(off).toInt() and 0xFF) shl 24) or ((b.get(off+1).toInt() and 0xFF) shl 16) or ((b.get(off+2).toInt() and 0xFF) shl 8) or (b.get(off+3).toInt() and 0xFF)
    private fun putIp(b: ByteBuffer, ip: String) { ip.split(".").forEach { b.put(it.toInt().toByte()) } }

    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off; var r = len
        while (r > 1) { sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF); i += 2; r -= 2 }
        if (r == 1) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private fun tcpChecksum(pkt: ByteArray, off: Int, len: Int, srcIp: String, dstIp: String): Int {
        val pseudo = ByteArray(12 + len)
        srcIp.split(".").forEachIndexed { i, s -> pseudo[i] = s.toInt().toByte() }
        dstIp.split(".").forEachIndexed { i, s -> pseudo[4+i] = s.toInt().toByte() }
        pseudo[9] = 6; pseudo[10] = (len shr 8).toByte(); pseudo[11] = (len and 0xFF).toByte()
        System.arraycopy(pkt, off, pseudo, 12, len)
        pseudo[12+16] = 0; pseudo[12+17] = 0
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Zray VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        val si = PendingIntent.getService(this, 1,
            Intent(this, ZrayVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zray VPN").setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pi)
            .addAction(0, "停止", si).setOngoing(true).build()
    }

    class TcpSession(
        val srcIp: String, val srcPort: Int,
        val dstIp: String, val dstPort: Int,
        var clientSeq: Int, var serverSeq: Int
    ) {
        var socket: Socket? = null
        var state = State.SYN_RECEIVED
        enum class State { SYN_RECEIVED, ESTABLISHED, CLOSED }

        fun writeToRemote(data: ByteArray) {
            try { socket?.getOutputStream()?.apply { write(data); flush() } }
            catch (_: Exception) {}
        }

        fun tryRead(): ByteArray? {
            val s = socket ?: return null
            if (state != State.ESTABLISHED) return null
            return try {
                val avail = s.getInputStream().available()
                if (avail <= 0) return null
                val buf = ByteArray(minOf(avail, 4096))
                val n = s.getInputStream().read(buf)
                if (n > 0) buf.copyOf(n) else null
            } catch (_: Exception) { null }
        }

        fun close() {
            state = State.CLOSED
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
