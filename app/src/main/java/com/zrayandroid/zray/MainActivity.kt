package com.zrayandroid.zray

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zrayandroid.zray.core.DebugLog
import com.zrayandroid.zray.core.ProfileStore
import com.zrayandroid.zray.navigation.Screen
import com.zrayandroid.zray.navigation.screens
import com.zrayandroid.zray.service.ZrayService
import com.zrayandroid.zray.ui.components.DebugOverlay
import com.zrayandroid.zray.ui.screens.*
import com.zrayandroid.zray.ui.theme.ZrayTheme
import kotlinx.coroutines.launch

const val APP_VERSION = "1.1.0"

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        DebugLog.log("APP", "Zray for Android v$APP_VERSION 启动")
        DebugLog.log("APP", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        DebugLog.log("APP", "设备: ${Build.MANUFACTURER} ${Build.MODEL}")

        setContent {
            ZrayTheme {
                ZrayApp(
                    onStartService = { config, port -> startZrayService(config, port) },
                    onStopService = { stopZrayService() }
                )
            }
        }
    }

    private fun startZrayService(config: String, port: Int) {
        DebugLog.log("SERVICE", "请求启动前台服务")
        val intent = Intent(this, ZrayService::class.java).apply {
            action = ZrayService.ACTION_START
            putExtra(ZrayService.EXTRA_CONFIG, config)
            putExtra(ZrayService.EXTRA_PORT, port)
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
    onStartService: (String, Int) -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var socksPort by remember { mutableIntStateOf(1081) }
    var profiles by remember { mutableStateOf(listOf<Profile>()) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var debugEnabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val activeProfile = profiles.find { it.id == activeProfileId }

    // 启动时加载本地配置
    LaunchedEffect(Unit) {
        val (savedProfiles, savedActiveId) = ProfileStore.loadProfiles(context)
        val savedPort = ProfileStore.loadSocksPort(context)
        profiles = savedProfiles
        activeProfileId = savedActiveId
        socksPort = savedPort
        loaded = true
        DebugLog.log("APP", "本地配置加载完成: ${savedProfiles.size} 个节点")
    }

    // 配置变更时自动保存
    fun saveAll() {
        scope.launch {
            ProfileStore.saveProfiles(context, profiles, activeProfileId)
            ProfileStore.saveSocksPort(context, socksPort)
        }
    }

    if (!loaded) return // 等加载完成

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                )
            ) {
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
        },
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        hasProfile = profiles.isNotEmpty() && activeProfileId != null,
                        activeProfileName = activeProfile?.name,
                        onToggle = {
                            if (isConnecting) return@HomeScreen
                            if (isConnected) {
                                onStopService()
                                isConnected = false
                                DebugLog.log("UI", "用户点击断开")
                            } else if (activeProfile != null) {
                                val config = if (activeProfile.server.isNotEmpty()) {
                                    activeProfile.toConfigJson(socksPort)
                                } else {
                                    activeProfile.link
                                }
                                if (config.isBlank()) {
                                    DebugLog.log("UI", "配置为空，无法启动")
                                    return@HomeScreen
                                }
                                // 尝试连接
                                isConnecting = true
                                DebugLog.log("UI", "尝试连接: ${activeProfile.name}")
                                
                                // 先停止旧的
                                onStopService()
                                kotlinx.coroutines.delay(300)
                                
                                // 异步启动核心
                                com.zrayandroid.zray.core.ZrayCoreMock.startAsync(config, socksPort) { success, error ->
                                    scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        isConnecting = false
                                        if (success) {
                                            isConnected = true
                                            // 也启动前台服务保活
                                            onStartService(config, socksPort)
                                            DebugLog.log("UI", "连接成功")
                                        } else {
                                            isConnected = false
                                            errorMessage = error ?: "连接失败"
                                            DebugLog.log("ERROR", "连接失败: $error")
                                        }
                                    }
                                }
                            }
                        },
                        socksPort = socksPort
                    )
                }

                composable(Screen.Profiles.route) {
                    ProfilesScreen(
                        profiles = profiles.map { it.copy(isActive = it.id == activeProfileId) },
                        onAddProfile = { profile ->
                            profiles = profiles + profile
                            if (activeProfileId == null) activeProfileId = profile.id
                            DebugLog.log("PROFILE", "添加: ${profile.name}")
                            saveAll()
                        },
                        onUpdateProfile = { updated ->
                            profiles = profiles.map { if (it.id == updated.id) updated else it }
                            DebugLog.log("PROFILE", "更新: ${updated.name}")
                            saveAll()
                        },
                        onSelectProfile = { id ->
                            activeProfileId = id
                            val p = profiles.find { it.id == id }
                            DebugLog.log("PROFILE", "切换到: ${p?.name}")
                            saveAll()
                        },
                        onDeleteProfile = { id ->
                            val p = profiles.find { it.id == id }
                            profiles = profiles.filter { it.id != id }
                            if (activeProfileId == id) activeProfileId = profiles.firstOrNull()?.id
                            DebugLog.log("PROFILE", "删除: ${p?.name}")
                            saveAll()
                        }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        socksPort = socksPort,
                        onPortChange = {
                            socksPort = it
                            DebugLog.log("SETTINGS", "端口改为: $it")
                            saveAll()
                        },
                        debugEnabled = debugEnabled,
                        onDebugToggle = {
                            debugEnabled = it
                            DebugLog.log("SETTINGS", "Debug ${if (it) "开启" else "关闭"}")
                        }
                    )
                }
            }

            // Debug 浮窗
            DebugOverlay(
                visible = debugEnabled,
                onDismiss = { debugEnabled = false }
            )
        }
    }

    // 错误弹窗
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("连接失败") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("确定") }
            }
        )
    }
}
