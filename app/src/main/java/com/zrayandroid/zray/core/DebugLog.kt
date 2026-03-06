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
 *
 * 当 debugMode 开启时，所有日志写入文件，供调试使用。
 * 当 debugMode 关闭时，仅保留内存日志，不写入文件。
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

    /** 文件操作锁，保护 fileWriter 的并发访问 */
    private val fileLock = Any()

    /** 调试模式开关：开启后日志写入文件，关闭后仅内存日志 */
    @Volatile
    var debugMode: Boolean = false
        set(value) {
            field = value
            synchronized(fileLock) {
                if (value) {
                    // 开启时确保文件已打开
                    ensureFileOpen()
                } else {
                    // 关闭时释放文件写入器
                    closeFileWriter()
                }
            }
        }

    /**
     * 初始化文件日志。在 Application 或 Activity onCreate 中调用。
     */
    fun init(context: Context) {
        try {
            logDir = File(context.filesDir, "logs").also { it.mkdirs() }
            if (debugMode) {
                ensureFileOpen()
            }
            log("LOG", "日志系统初始化完成: ${logDir?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("DebugLog", "日志系统初始化失败", e)
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

        // 调试模式时写入文件
        if (debugMode) {
            writeToFile(line)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllText(): String = _logs.value.joinToString("\n")

    /**
     * 获取所有日志文件内容（用于查看器显示）
     */
    fun getFullFileLog(): String {
        return try {
            val dir = logDir ?: return getAllText()
            val files = dir.listFiles()?.sortedBy { it.name } ?: return getAllText()
            if (files.isEmpty()) return "暂无日志文件"
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

    /**
     * 获取日志目录
     */
    fun getLogDir(): File? = logDir

    /**
     * 获取所有日志文件列表（按日期排序）
     */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles()?.sortedByDescending { it.name }?.toList() ?: emptyList()
    }

    /**
     * 清理所有日志文件
     */
    fun clearLogFiles() {
        synchronized(fileLock) {
            try {
                closeFileWriter()
                logDir?.listFiles()?.forEach { it.delete() }
                _logs.value = emptyList()
                if (debugMode) {
                    ensureFileOpen()
                    log("LOG", "日志文件已清理")
                }
                Unit
            } catch (e: Exception) {
                android.util.Log.e("DebugLog", "清理日志文件失败", e)
            }
        }
    }

    private fun writeToFile(line: String) {
        synchronized(fileLock) {
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
    }

    private fun ensureFileOpen() {
        if (fileWriter == null || dateFmt.format(Date()) != currentDate) {
            rotateFile()
        }
    }

    private fun closeFileWriter() {
        try {
            fileWriter?.close()
        } catch (_: Exception) {}
        fileWriter = null
    }

    private fun rotateFile() {
        closeFileWriter()

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
        } catch (_: Exception) {}
    }
}
