package com.zrayandroid.zray.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.zrayandroid.zray.ui.screens.Profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zray_config")

object ProfileStore {
    private val PROFILES_KEY = stringPreferencesKey("profiles_json")
    private val ACTIVE_ID_KEY = stringPreferencesKey("active_profile_id")
    private val SOCKS_PORT_KEY = intPreferencesKey("socks_port")

    suspend fun saveProfiles(context: Context, profiles: List<Profile>, activeId: String?) {
        val arr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("server", p.server)
                put("port", p.port)
                put("userHash", p.userHash)
                put("link", p.link)
            }
            arr.put(obj)
        }
        context.dataStore.edit { prefs ->
            prefs[PROFILES_KEY] = arr.toString()
            if (activeId != null) prefs[ACTIVE_ID_KEY] = activeId
            else prefs.remove(ACTIVE_ID_KEY)
        }
        DebugLog.log("STORE", "保存 ${profiles.size} 个配置")
    }

    suspend fun loadProfiles(context: Context): Pair<List<Profile>, String?> {
        val prefs = context.dataStore.data.first()
        val json = prefs[PROFILES_KEY] ?: return Pair(emptyList(), null)
        val activeId = prefs[ACTIVE_ID_KEY]
        val arr = JSONArray(json)
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
        DebugLog.log("STORE", "加载 ${profiles.size} 个配置")
        return Pair(profiles, activeId)
    }

    suspend fun saveSocksPort(context: Context, port: Int) {
        context.dataStore.edit { it[SOCKS_PORT_KEY] = port }
    }

    suspend fun loadSocksPort(context: Context): Int {
        return context.dataStore.data.first()[SOCKS_PORT_KEY] ?: 1081
    }
}
