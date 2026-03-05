package com.zrayandroid.zray.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局日志收集器，供 Debug 浮窗显示。
 */
object DebugLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_LINES = 500

    fun log(tag: String, msg: String) {
        val ts = sdf.format(Date())
        val line = "$ts [$tag] $msg"
        android.util.Log.d("ZrayDebug", line)
        val current = _logs.value.toMutableList()
        current.add(line)
        if (current.size > MAX_LINES) {
            _logs.value = current.takeLast(MAX_LINES)
        } else {
            _logs.value = current
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllText(): String = _logs.value.joinToString("\n")
}
