package com.zrayandroid.zray.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.zrayandroid.zray.ui.screens.Profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zray_config")

private val json = Json { ignoreUnknownKeys = true }

object ProfileStore {
    private val PROFILES_KEY = stringPreferencesKey("profiles_json")
    private val ACTIVE_ID_KEY = stringPreferencesKey("active_profile_id")
    private val SOCKS_PORT_KEY = intPreferencesKey("socks_port")
    private val ALLOW_INSECURE_SSL_KEY = booleanPreferencesKey("allow_insecure_ssl")
    private val ENABLE_IPV6_KEY = booleanPreferencesKey("enable_ipv6")
    private val DNS_PROTOCOL_KEY = stringPreferencesKey("dns_protocol")
    private val DNS_SERVER_KEY = stringPreferencesKey("dns_server")

    suspend fun saveDnsConfig(context: Context, protocol: DnsProtocol, server: String) {
        context.dataStore.edit {
            it[DNS_PROTOCOL_KEY] = protocol.name
            it[DNS_SERVER_KEY] = server
        }
    }

    suspend fun loadDnsProtocol(context: Context): DnsProtocol {
        val name = context.dataStore.data.first()[DNS_PROTOCOL_KEY] ?: DnsProtocol.DOH.name
        return try { DnsProtocol.valueOf(name) } catch (_: Exception) { DnsProtocol.DOH }
    }

    suspend fun loadDnsServer(context: Context): String {
        return context.dataStore.data.first()[DNS_SERVER_KEY] ?: ZrayDnsResolver.DEFAULT_DOH_SERVER
    }

    suspend fun saveProfiles(context: Context, profiles: List<Profile>, activeId: String?) {
        val encoded = json.encodeToString(profiles)
        context.dataStore.edit { prefs ->
            prefs[PROFILES_KEY] = encoded
            if (activeId != null) prefs[ACTIVE_ID_KEY] = activeId
            else prefs.remove(ACTIVE_ID_KEY)
        }
        DebugLog.log("STORE", "保存 ${profiles.size} 个配置")
    }

    suspend fun loadProfiles(context: Context): Pair<List<Profile>, String?> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[PROFILES_KEY] ?: return Pair(emptyList(), null)
        val activeId = prefs[ACTIVE_ID_KEY]
        val profiles = try {
            json.decodeFromString<List<Profile>>(raw)
        } catch (e: Exception) {
            DebugLog.log("STORE", "反序列化失败，尝试兼容旧格式: ${e.message}")
            // 兼容旧 org.json 格式的迁移
            parseLegacyProfiles(raw)
        }
        DebugLog.log("STORE", "加载 ${profiles.size} 个配置")
        return Pair(profiles, activeId)
    }

    suspend fun saveSocksPort(context: Context, port: Int) {
        context.dataStore.edit { it[SOCKS_PORT_KEY] = port }
    }

    suspend fun loadSocksPort(context: Context): Int {
        return context.dataStore.data.first()[SOCKS_PORT_KEY] ?: 1081
    }

    suspend fun saveAllowInsecureSsl(context: Context, allow: Boolean) {
        context.dataStore.edit { it[ALLOW_INSECURE_SSL_KEY] = allow }
    }

    /** 默认 false 以强制安全 SSL 校验 */
    suspend fun loadAllowInsecureSsl(context: Context): Boolean {
        return context.dataStore.data.first()[ALLOW_INSECURE_SSL_KEY] ?: false
    }

    suspend fun saveEnableIpv6(context: Context, enable: Boolean) {
        context.dataStore.edit { it[ENABLE_IPV6_KEY] = enable }
    }

    suspend fun loadEnableIpv6(context: Context): Boolean {
        return context.dataStore.data.first()[ENABLE_IPV6_KEY] ?: false
    }

    /**
     * 兼容旧版 org.json 格式的 Profile 数据迁移。
     * 使用 org.json 解析后转换为 Profile 对象。
     */
    private fun parseLegacyProfiles(raw: String): List<Profile> {
        return try {
            val arr = org.json.JSONArray(raw)
            val profiles = mutableListOf<Profile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                profiles.add(Profile(
                    id = obj.getString("id"),
                    name = obj.optString("name", ""),
                    server = obj.optString("server", ""),
                    port = obj.optInt("port", 64433),
                    userHash = obj.optString("userHash", ""),
                    link = obj.optString("link", "")
                ))
            }
            profiles
        } catch (e: Exception) {
            DebugLog.log("STORE", "旧格式迁移也失败: ${e.message}")
            emptyList()
        }
    }
}
