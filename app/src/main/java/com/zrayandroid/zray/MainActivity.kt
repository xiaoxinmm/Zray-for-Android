package com.zrayandroid.zray

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zrayandroid.zray.core.DebugLog
import com.zrayandroid.zray.core.ZrayCoreMock
import com.zrayandroid.zray.navigation.Screen
import com.zrayandroid.zray.navigation.screens
import com.zrayandroid.zray.service.ZrayService
import com.zrayandroid.zray.ui.components.DebugOverlay
import com.zrayandroid.zray.ui.screens.*
import com.zrayandroid.zray.ui.theme.ZrayTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        DebugLog.log("APP", "Zray for Android v1.0.2 启动")
        DebugLog.log("APP", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        DebugLog.log("APP", "设备: ${Build.MANUFACTURER} ${Build.MODEL}")

        setContent {
            ZrayTheme {
                ZrayApp(
                    onStartService = { config -> startZrayService(config) },
                    onStopService = { stopZrayService() }
                )
            }
        }
    }

    private fun startZrayService(config: String) {
        DebugLog.log("SERVICE", "请求启动前台服务")
        val intent = Intent(this, ZrayService::class.java).apply {
            action = ZrayService.ACTION_START
            putExtra(ZrayService.EXTRA_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopZrayService() {
        DebugLog.log("SERVICE", "请求停止前台服务")
        val intent = Intent(this, ZrayService::class.java).apply {
            action = ZrayService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
fun ZrayApp(
    onStartService: (String) -> Unit,
    onStopService: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var isConnected by remember { mutableStateOf(false) }
    var socksPort by remember { mutableIntStateOf(1081) }
    var profiles by remember { mutableStateOf(listOf<Profile>()) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var debugEnabled by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        isConnected = isConnected,
                        onToggle = {
                            if (isConnected) {
                                onStopService()
                                isConnected = false
                                DebugLog.log("UI", "用户点击断开")
                            } else {
                                val activeProfile = profiles.find { it.id == activeProfileId }
                                val config = activeProfile?.link ?: ""
                                if (config.isEmpty()) {
                                    DebugLog.log("UI", "无活跃配置，使用空配置启动")
                                } else {
                                    DebugLog.log("UI", "使用配置启动: ${activeProfile?.name}")
                                }
                                onStartService(config)
                                isConnected = true
                                DebugLog.log("UI", "用户点击连接")
                            }
                        },
                        socksPort = socksPort
                    )
                }

                composable(Screen.Profiles.route) {
                    ProfilesScreen(
                        profiles = profiles.map { it.copy(isActive = it.id == activeProfileId) },
                        onAddProfile = { link ->
                            val id = UUID.randomUUID().toString()
                            val name = "配置 ${profiles.size + 1}"
                            profiles = profiles + Profile(id, name, link)
                            if (activeProfileId == null) activeProfileId = id
                            DebugLog.log("PROFILE", "添加配置: $name")
                            DebugLog.log("PROFILE", "链接: ${link.take(30)}...")
                        },
                        onSelectProfile = { id ->
                            activeProfileId = id
                            val p = profiles.find { it.id == id }
                            DebugLog.log("PROFILE", "切换到: ${p?.name}")
                        },
                        onDeleteProfile = { id ->
                            val p = profiles.find { it.id == id }
                            profiles = profiles.filter { it.id != id }
                            if (activeProfileId == id) activeProfileId = profiles.firstOrNull()?.id
                            DebugLog.log("PROFILE", "删除配置: ${p?.name}")
                        }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        socksPort = socksPort,
                        onPortChange = {
                            socksPort = it
                            DebugLog.log("SETTINGS", "端口改为: $it")
                        },
                        debugEnabled = debugEnabled,
                        onDebugToggle = {
                            debugEnabled = it
                            DebugLog.log("SETTINGS", "Debug ${if (it) "开启" else "关闭"}")
                        }
                    )
                }
            }

            // Debug 浮窗覆盖在内容上方
            DebugOverlay(
                visible = debugEnabled,
                onDismiss = { debugEnabled = false }
            )
        }
    }
}
