package com.zrayandroid.zray

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zrayandroid.zray.core.*
import com.zrayandroid.zray.ui.screens.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主 ViewModel — 管理所有 UI 状态，解耦 Activity 与业务逻辑。
 *
 * 将原 ZrayApp Composable 中的 remember { mutableStateOf(...) } 迁移至此，
 * 使用 StateFlow 统一管理，确保 Configuration Change 后状态不丢失。
 */
class ZrayViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    // ==================== 核心管理器 ====================
    val coreManager = ZrayCoreManager(context)

    // ==================== 连接状态 ====================
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // ==================== 配置 ====================
    private val _socksPort = MutableStateFlow(1081)
    val socksPort: StateFlow<Int> = _socksPort.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _debugEnabled = MutableStateFlow(false)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    private val _selectedCoreType = MutableStateFlow(CoreType.KOTLIN_CORE)
    val selectedCoreType: StateFlow<CoreType> = _selectedCoreType.asStateFlow()

    private val _allowInsecureSsl = MutableStateFlow(true)
    val allowInsecureSsl: StateFlow<Boolean> = _allowInsecureSsl.asStateFlow()

    // ==================== 路由 ====================
    private val _routingConfig = MutableStateFlow(RoutingConfig())
    val routingConfig: StateFlow<RoutingConfig> = _routingConfig.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isVpnRunning = MutableStateFlow(false)
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

    // ==================== 流量统计 ====================
    private val _latencyMs = MutableStateFlow(-1L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _uploadSpeed = MutableStateFlow(0L)
    val uploadSpeed: StateFlow<Long> = _uploadSpeed.asStateFlow()

    private val _downloadSpeed = MutableStateFlow(0L)
    val downloadSpeed: StateFlow<Long> = _downloadSpeed.asStateFlow()

    private val _totalUpload = MutableStateFlow(0L)
    val totalUpload: StateFlow<Long> = _totalUpload.asStateFlow()

    private val _totalDownload = MutableStateFlow(0L)
    val totalDownload: StateFlow<Long> = _totalDownload.asStateFlow()

    // ==================== UI 状态 ====================
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateChecker.UpdateInfo?> = _updateInfo.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    // ==================== 初始化 ====================

    init {
        loadData()
        checkForUpdate()
        startTrafficTicker()
    }

    private fun loadData() {
        viewModelScope.launch {
            val (savedProfiles, savedActiveId) = ProfileStore.loadProfiles(context)
            val savedPort = ProfileStore.loadSocksPort(context)
            val savedInsecureSsl = ProfileStore.loadAllowInsecureSsl(context)
            _profiles.value = savedProfiles
            _activeProfileId.value = savedActiveId
            _socksPort.value = savedPort
            _allowInsecureSsl.value = savedInsecureSsl
            coreManager.allowInsecureSsl = savedInsecureSsl
            _loaded.value = true
            DebugLog.log("APP", "本地配置加载完成: ${savedProfiles.size} 个节点")
        }
        viewModelScope.launch {
            _routingConfig.value = RoutingStore.load(context)
            _installedApps.value = RoutingStore.getInstalledApps(context)
        }
    }

    private fun checkForUpdate() {
        UpdateChecker.checkAsync(APP_VERSION) { info ->
            if (info != null) {
                _updateInfo.value = info
                _showUpdateDialog.value = true
            }
        }
    }

    /** 每秒刷新流量统计和延迟 */
    private fun startTrafficTicker() {
        viewModelScope.launch {
            while (isActive) {
                if (_isConnected.value) {
                    _latencyMs.value = coreManager.getLatency()
                    TrafficStats.tick()
                    _uploadSpeed.value = TrafficStats.uploadSpeed
                    _downloadSpeed.value = TrafficStats.downloadSpeed
                    _totalUpload.value = TrafficStats.uploadBytes.get()
                    _totalDownload.value = TrafficStats.downloadBytes.get()
                } else {
                    _latencyMs.value = -1L
                    _uploadSpeed.value = 0L
                    _downloadSpeed.value = 0L
                }
                delay(1000)
            }
        }
    }

    // ==================== 操作方法 ====================

    fun getActiveProfile(): Profile? = _profiles.value.find { it.id == _activeProfileId.value }

    fun setConnected(connected: Boolean) { _isConnected.value = connected }
    fun setConnecting(connecting: Boolean) { _isConnecting.value = connecting }
    fun setVpnRunning(running: Boolean) { _isVpnRunning.value = running }
    fun setError(msg: String?) { _errorMessage.value = msg }
    fun dismissUpdateDialog() { _showUpdateDialog.value = false }
    fun setDebugEnabled(enabled: Boolean) {
        _debugEnabled.value = enabled
        DebugLog.log("SETTINGS", "Debug ${if (enabled) "开启" else "关闭"}")
    }

    fun setSocksPort(port: Int) {
        _socksPort.value = port
        DebugLog.log("SETTINGS", "端口改为: $port")
        saveAll()
    }

    fun setAllowInsecureSsl(allow: Boolean) {
        _allowInsecureSsl.value = allow
        coreManager.allowInsecureSsl = allow
        viewModelScope.launch {
            ProfileStore.saveAllowInsecureSsl(context, allow)
        }
        DebugLog.log("SETTINGS", "SSL 不安全证书: ${if (allow) "允许" else "禁止"}")
    }

    fun switchCoreType(type: CoreType) {
        if (_isConnected.value) {
            _errorMessage.value = "请先断开连接再切换核心"
            return
        }
        _selectedCoreType.value = type
        coreManager.switchCore(type) { error ->
            if (error != null) _errorMessage.value = error
        }
        DebugLog.log("SETTINGS", "核心切换为: ${type.displayName}")
    }

    // ===== Profile 操作 =====

    fun addProfile(profile: Profile) {
        _profiles.value = _profiles.value + profile
        if (_activeProfileId.value == null) _activeProfileId.value = profile.id
        DebugLog.log("PROFILE", "添加: ${profile.name}")
        saveAll()
    }

    fun updateProfile(updated: Profile) {
        _profiles.value = _profiles.value.map { if (it.id == updated.id) updated else it }
        DebugLog.log("PROFILE", "更新: ${updated.name}")
        saveAll()
    }

    fun selectProfile(id: String) {
        _activeProfileId.value = id
        val p = _profiles.value.find { it.id == id }
        DebugLog.log("PROFILE", "切换到: ${p?.name}")
        saveAll()
    }

    fun deleteProfile(id: String) {
        val p = _profiles.value.find { it.id == id }
        _profiles.value = _profiles.value.filter { it.id != id }
        if (_activeProfileId.value == id) _activeProfileId.value = _profiles.value.firstOrNull()?.id
        DebugLog.log("PROFILE", "删除: ${p?.name}")
        saveAll()
    }

    // ===== 路由操作 =====

    fun updateRoutingConfig(config: RoutingConfig) {
        _routingConfig.value = config
        viewModelScope.launch {
            RoutingStore.save(context, config)
        }
    }

    fun updateSelectedApps(apps: Set<String>) {
        val newConfig = _routingConfig.value.copy(selectedApps = apps)
        _routingConfig.value = newConfig
        viewModelScope.launch {
            RoutingStore.save(context, newConfig)
        }
    }

    // ===== 连接操作 =====

    fun startConnection(onStartService: (String, Int) -> Unit, onPrepareVpn: (() -> Unit) -> Unit) {
        val activeProfile = getActiveProfile() ?: return
        val config = if (activeProfile.server.isNotEmpty()) {
            activeProfile.toConfigJson(_socksPort.value)
        } else {
            activeProfile.link
        }
        if (config.isBlank()) {
            DebugLog.log("UI", "配置为空，无法启动")
            return
        }

        _isConnecting.value = true
        DebugLog.log("UI", "尝试连接: ${activeProfile.name} (${coreManager.selectedCoreType.displayName})")

        coreManager.start(config, _socksPort.value) { success, error ->
            _isConnecting.value = false
            if (success) {
                _isConnected.value = true
                onStartService(config, _socksPort.value)
                // 根据路由模式启动 VPN
                if (_routingConfig.value.mode != ProxyMode.SOCKS5_ONLY) {
                    onPrepareVpn {
                        _isVpnRunning.value = true
                    }
                }
                DebugLog.log("UI", "连接成功 (${_routingConfig.value.mode.displayName})")
            } else {
                _isConnected.value = false
                _errorMessage.value = error ?: "连接失败"
                DebugLog.log("ERROR", "连接失败: $error")
            }
        }
    }

    fun stopConnection(onStopService: () -> Unit, onStopVpn: () -> Unit) {
        coreManager.stop()
        onStopService()
        if (_isVpnRunning.value) {
            onStopVpn()
            _isVpnRunning.value = false
        }
        _isConnected.value = false
        DebugLog.log("UI", "用户点击断开")
    }

    // ===== 持久化 =====

    private fun saveAll() {
        viewModelScope.launch {
            ProfileStore.saveProfiles(context, _profiles.value, _activeProfileId.value)
            ProfileStore.saveSocksPort(context, _socksPort.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        coreManager.destroy()
    }
}
