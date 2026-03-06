package com.zrayandroid.zray.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局日志收集器 — 内存 + 文件双写。
 */
object DebugLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private const val MAX_LINES = 500

    private var logDir: File? = null
    private var fileWriter: FileWriter? = null
    private var currentDate: String = ""

    /**
     * 初始化文件日志。在 Application 或 Activity onCreate 中调用。
     */
    fun init(context: Context) {
        try {
            logDir = File(context.filesDir, "logs").also { it.mkdirs() }
            rotateFile()
            log("LOG", "文件日志初始化完成: ${logDir?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("DebugLog", "文件日志初始化失败", e)
        }
    }

    fun log(tag: String, msg: String) {
        val ts = sdf.format(Date())
        val line = "$ts [$tag] $msg"
        android.util.Log.d("ZrayDebug", line)

        // 内存
        val current = _logs.value.toMutableList()
        current.add(line)
        if (current.size > MAX_LINES) {
            _logs.value = current.takeLast(MAX_LINES)
        } else {
            _logs.value = current
        }

        // 文件
        writeToFile(line)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllText(): String = _logs.value.joinToString("\n")

    /**
     * 获取所有日志文件内容（用于一键导出/复制）
     */
    fun getFullFileLog(): String {
        return try {
            val dir = logDir ?: return getAllText()
            val files = dir.listFiles()?.sortedBy { it.name } ?: return getAllText()
            files.joinToString("\n\n") { f ->
                "=== ${f.name} ===\n${f.readText()}"
            }
        } catch (e: Exception) {
            "读取日志文件失败: ${e.message}"
        }
    }

    /**
     * 获取当天日志文件路径
     */
    fun getTodayLogFile(): File? {
        val dir = logDir ?: return null
        val today = dateFmt.format(Date())
        return File(dir, "zray-$today.log").takeIf { it.exists() }
    }

    private fun writeToFile(line: String) {
        try {
            val today = dateFmt.format(Date())
            if (today != currentDate) {
                rotateFile()
            }
            fileWriter?.apply {
                write(line)
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            // 文件写入失败不影响主流程
        }
    }

    private fun rotateFile() {
        try {
            fileWriter?.close()
        } catch (e: Exception) {}

        val dir = logDir ?: return
        currentDate = dateFmt.format(Date())
        val file = File(dir, "zray-$currentDate.log")
        fileWriter = FileWriter(file, true) // append

        // 清理7天前的日志
        try {
            val cutoff = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (e: Exception) {}
    }
}
