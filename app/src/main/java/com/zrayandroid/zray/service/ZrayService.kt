package com.zrayandroid.zray.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zrayandroid.zray.MainActivity
import com.zrayandroid.zray.R
import com.zrayandroid.zray.core.ZrayCoreMock
import com.zrayandroid.zray.core.DebugLog
import kotlinx.coroutines.*

class ZrayService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var config: String = ""
    private var socksPort: Int = 1081

    companion object {
        const val CHANNEL_ID = "zray_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.zrayandroid.zray.START"
        const val ACTION_STOP = "com.zrayandroid.zray.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_PORT = "socks_port"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DebugLog.log("SERVICE", "ZrayService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ZrayCoreMock.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                config = intent.getStringExtra(EXTRA_CONFIG) ?: ""
                socksPort = intent.getIntExtra(EXTRA_PORT, 1081)
            }
        }

        val notification = buildNotification("Zray 运行中 · SOCKS5 :$socksPort")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch {
            DebugLog.log("SERVICE", "前台服务启动, 端口: $socksPort")
            // 核心已在 MainActivity 中启动，这里只做保活
            // 不再重复调用 startAsync
            if (ZrayCoreMock.isRunning) {
                DebugLog.log("SERVICE", "核心已在运行中")
            } else {
                DebugLog.log("SERVICE", "核心未运行，启动中...")
                ZrayCoreMock.startAsync(config, socksPort) { success, error ->
                    if (success) DebugLog.log("SERVICE", "核心启动成功")
                    else DebugLog.log("ERROR", "核心启动失败: $error")
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        DebugLog.log("SERVICE", "ZrayService onDestroy")
        ZrayCoreMock.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ZrayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zray")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
