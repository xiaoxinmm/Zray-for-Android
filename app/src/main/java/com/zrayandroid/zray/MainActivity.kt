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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zrayandroid.zray.core.DebugLog
import com.zrayandroid.zray.navigation.Screen
import com.zrayandroid.zray.navigation.screens
import com.zrayandroid.zray.navigation.screenTitle
import com.zrayandroid.zray.service.ZrayService
import com.zrayandroid.zray.ui.screens.*
import com.zrayandroid.zray.ui.theme.ZrayTheme

const val APP_VERSION = BuildConfig.VERSION_NAME

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not */ }

    // VPN 授权
    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            DebugLog.log("VPN", "VPN 授权通过")
            pendingVpnStart?.invoke()
        } else {
            DebugLog.log("VPN", "VPN 授权被拒绝")
        }
        pendingVpnStart = null
    }

    var pendingVpnStart: (() -> Unit)? = null

    fun prepareVpn(onReady: () -> Unit) {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingVpnStart = onReady
            vpnPermission.launch(intent)
        } else {
            onReady()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 全局异常捕获
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.log("FATAL", "未捕获异常 [${thread.name}]: ${throwable.message}")
            DebugLog.log("FATAL", throwable.stackTraceToString().take(500))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        DebugLog.init(this) // 初始化文件日志
        DebugLog.log("APP", "Zray for Android v$APP_VERSION 启动")
        DebugLog.log("APP", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        DebugLog.log("APP", "设备: ${Build.MANUFACTURER} ${Build.MODEL}")

        setContent {
            ZrayTheme {
                ZrayApp(
                    activity = this,
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

/**
 * 主界面 Composable — 所有 UI 状态由 ZrayViewModel 管理。
 * Activity 仅负责 VPN 授权和前台服务的 Intent 启停。
 */
@Composable
fun ZrayApp(
    activity: MainActivity,
    onStartService: (String, Int) -> Unit,
    onStopService: () -> Unit,
    vm: ZrayViewModel = viewModel()
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 从 ViewModel 收集所有状态
    val isConnected by vm.isConnected.collectAsState()
    val isConnecting by vm.isConnecting.collectAsState()
    val socksPort by vm.socksPort.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val debugEnabled by vm.debugEnabled.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val updateInfo by vm.updateInfo.collectAsState()
    val showUpdateDialog by vm.showUpdateDialog.collectAsState()
    val selectedCoreType by vm.selectedCoreType.collectAsState()
    val allowInsecureSsl by vm.allowInsecureSsl.collectAsState()
    val dnsProtocol by vm.dnsProtocol.collectAsState()
    val dnsServer by vm.dnsServer.collectAsState()
    val routingConfig by vm.routingConfig.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    val isVpnRunning by vm.isVpnRunning.collectAsState()
    val latencyMs by vm.latencyMs.collectAsState()
    val uploadSpeed by vm.uploadSpeed.collectAsState()
    val downloadSpeed by vm.downloadSpeed.collectAsState()
    val totalUpload by vm.totalUpload.collectAsState()
    val totalDownload by vm.totalDownload.collectAsState()

    val activeProfile = profiles.find { it.id == activeProfileId }

    if (!loaded) return // 等加载完成

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            val title = screenTitle(currentRoute)
            val isAppList = currentRoute == Screen.AppList.route
            @OptIn(ExperimentalMaterial3Api::class)
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (isAppList) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            // AppList 页面不显示底部导航
            if (currentRoute != Screen.AppList.route) {
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
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                                vm.stopConnection(
                                    onStopService = onStopService,
                                    onStopVpn = { stopVpn(context) }
                                )
                            } else if (activeProfile != null) {
                                vm.startConnection(
                                    onStartService = onStartService,
                                    onPrepareVpn = { onVpnReady ->
                                        activity.prepareVpn {
                                            startVpn(context, socksPort, routingConfig)
                                            onVpnReady()
                                        }
                                    }
                                )
                            }
                        },
                        socksPort = socksPort,
                        latencyMs = latencyMs,
                        uploadSpeed = uploadSpeed,
                        downloadSpeed = downloadSpeed,
                        totalUpload = totalUpload,
                        totalDownload = totalDownload,
                        selectedCoreType = selectedCoreType
                    )
                }

                composable(Screen.Profiles.route) {
                    ProfilesScreen(
                        profiles = profiles.map { it.copy(isActive = it.id == activeProfileId) },
                        onAddProfile = { vm.addProfile(it) },
                        onUpdateProfile = { vm.updateProfile(it) },
                        onSelectProfile = { vm.selectProfile(it) },
                        onDeleteProfile = { vm.deleteProfile(it) }
                    )
                }

                composable(Screen.Routing.route) {
                    RoutingScreen(
                        config = routingConfig,
                        onConfigChange = { vm.updateRoutingConfig(it) },
                        isVpnRunning = isVpnRunning,
                        onNavigateToAppList = {
                            navController.navigate(Screen.AppList.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Screen.AppList.route) {
                    AppListScreen(
                        installedApps = installedApps,
                        selectedApps = routingConfig.selectedApps,
                        isVpnRunning = isVpnRunning,
                        onSelectionChange = { vm.updateSelectedApps(it) }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        socksPort = socksPort,
                        onPortChange = { vm.setSocksPort(it) },
                        debugEnabled = debugEnabled,
                        onDebugToggle = { vm.setDebugEnabled(it) },
                        selectedCoreType = selectedCoreType,
                        onCoreTypeChange = { vm.switchCoreType(it) },
                        isGoCoreAvailable = vm.coreManager.isGoCoreAvailable(),
                        goBinaryPath = vm.coreManager.getGoBinaryPath(),
                        allowInsecureSsl = allowInsecureSsl,
                        onInsecureSslToggle = { vm.setAllowInsecureSsl(it) },
                        dnsProtocol = dnsProtocol,
                        onDnsProtocolChange = { vm.setDnsProtocol(it) },
                        dnsServer = dnsServer,
                        onDnsServerChange = { vm.setDnsServer(it) },
                        onOpenLogViewer = {
                            DebugLog.log("UI", "用户点击查看日志")
                            context.startActivity(
                                android.content.Intent(context, LogViewerActivity::class.java)
                            )
                        }
                    )
                }
            }
        }
    }

    // 错误弹窗
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.setError(null) },
            title = { Text("连接失败") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.setError(null) }) { Text("确定") }
            }
        )
    }

    // 更新弹窗
    if (showUpdateDialog) {
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { vm.dismissUpdateDialog() },
                title = { Text("发现新版本 v${info.version}") },
                text = {
                    Column {
                        Text("当前版本: v$APP_VERSION")
                        Spacer(modifier = Modifier.height(8.dp))
                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                info.releaseNotes.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.dismissUpdateDialog()
                        // 打开下载页面
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(info.htmlUrl)
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }) { Text("去更新") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissUpdateDialog() }) { Text("以后再说") }
                },
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

private fun startVpn(context: android.content.Context, socksPort: Int, config: com.zrayandroid.zray.core.RoutingConfig) {
    val intent = android.content.Intent(context, com.zrayandroid.zray.service.ZrayVpnService::class.java).apply {
        action = com.zrayandroid.zray.service.ZrayVpnService.ACTION_START
        putExtra(com.zrayandroid.zray.service.ZrayVpnService.EXTRA_SOCKS_PORT, socksPort)
        putExtra(com.zrayandroid.zray.service.ZrayVpnService.EXTRA_MODE, config.mode.name)
        putStringArrayListExtra(com.zrayandroid.zray.service.ZrayVpnService.EXTRA_SELECTED_APPS,
            ArrayList(config.selectedApps))
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopVpn(context: android.content.Context) {
    val intent = android.content.Intent(context, com.zrayandroid.zray.service.ZrayVpnService::class.java).apply {
        action = com.zrayandroid.zray.service.ZrayVpnService.ACTION_STOP
    }
    context.startService(intent)
}
