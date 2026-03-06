package com.zrayandroid.zray.core

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 代理模式
 */
enum class ProxyMode(val displayName: String, val desc: String) {
    SOCKS5_ONLY("仅 SOCKS5", "不启用 VPN，手动配置代理"),
    VPN_GLOBAL("VPN 全局", "所有应用流量走代理"),
    VPN_PER_APP("VPN 分应用", "仅选中的应用走代理")
}

/**
 * 路由配置
 */
data class RoutingConfig(
    val mode: ProxyMode = ProxyMode.SOCKS5_ONLY,
    val perAppMode: PerAppMode = PerAppMode.WHITELIST,
    val selectedApps: Set<String> = emptySet()
)

/**
 * 分应用模式
 */
enum class PerAppMode(val displayName: String) {
    WHITELIST("白名单（仅选中走代理）"),
    BLACKLIST("黑名单（选中不走代理）")
}

/**
 * 已安装应用信息
 */
data class AppInfo(
    val packageName: String,
    val name: String,
    val isSystem: Boolean
)

/**
 * 路由配置持久化
 */
object RoutingStore {
    private val Context.routingDataStore by preferencesDataStore("routing")

    private val KEY_MODE = stringPreferencesKey("proxy_mode")
    private val KEY_PER_APP_MODE = stringPreferencesKey("per_app_mode")
    private val KEY_SELECTED_APPS = stringSetPreferencesKey("selected_apps")

    suspend fun load(context: Context): RoutingConfig {
        val prefs = context.routingDataStore.data.first()
        return RoutingConfig(
            mode = try {
                ProxyMode.valueOf(prefs[KEY_MODE] ?: ProxyMode.SOCKS5_ONLY.name)
            } catch (_: Exception) { ProxyMode.SOCKS5_ONLY },
            perAppMode = try {
                PerAppMode.valueOf(prefs[KEY_PER_APP_MODE] ?: PerAppMode.WHITELIST.name)
            } catch (_: Exception) { PerAppMode.WHITELIST },
            selectedApps = prefs[KEY_SELECTED_APPS] ?: emptySet()
        )
    }

    suspend fun save(context: Context, config: RoutingConfig) {
        context.routingDataStore.edit { prefs ->
            prefs[KEY_MODE] = config.mode.name
            prefs[KEY_PER_APP_MODE] = config.perAppMode.name
            prefs[KEY_SELECTED_APPS] = config.selectedApps
        }
    }

    /**
     * 获取已安装应用列表（排除系统核心应用）
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName } // 排除自己
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    name = appInfo.loadLabel(pm).toString(),
                    isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.name }))
    }
}
