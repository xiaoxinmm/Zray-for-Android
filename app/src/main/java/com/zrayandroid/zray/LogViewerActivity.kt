package com.zrayandroid.zray

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zrayandroid.zray.core.DebugLog
import com.zrayandroid.zray.ui.theme.ZrayTheme

/**
 * 日志查看器 Activity — 专门用于查看和管理调试日志。
 *
 * 功能：
 * - 实时显示内存日志（自动滚动到最新）
 * - 查看历史日志文件
 * - 一键清理所有日志文件
 * - 复制/分享日志内容
 */
class LogViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DebugLog.log("UI", "打开日志查看器")

        setContent {
            ZrayTheme {
                LogViewerScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by DebugLog.logs.collectAsState()
    val listState = rememberLazyListState()

    // 选项卡状态: 0=实时日志, 1=文件日志
    var selectedTab by remember { mutableIntStateOf(0) }
    var fileLogContent by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    // 自动滚动到底部（仅实时日志 tab）
    LaunchedEffect(logs.size, selectedTab) {
        if (selectedTab == 0 && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // 加载文件日志
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            fileLogContent = DebugLog.getFullFileLog()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 复制日志
                    IconButton(onClick = {
                        val text = if (selectedTab == 0) {
                            DebugLog.getAllText()
                        } else {
                            DebugLog.getFullFileLog()
                        }
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志")
                    }
                    // 分享日志
                    IconButton(onClick = {
                        val text = if (selectedTab == 0) {
                            DebugLog.getAllText()
                        } else {
                            DebugLog.getFullFileLog()
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Zray 调试日志")
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "分享日志"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "分享日志")
                    }
                    // 清理日志
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "清理日志",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab 切换
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("实时日志") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("文件日志") }
                )
            }

            // 日志文件信息
            val logFiles = remember { DebugLog.getLogFiles() }
            if (logFiles.isNotEmpty()) {
                val totalSize = logFiles.sumOf { it.length() }
                Text(
                    text = "日志文件: ${logFiles.size} 个, 共 ${formatFileSize(totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 日志内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0E0E16))
            ) {
                when (selectedTab) {
                    0 -> {
                        // 实时日志
                        if (logs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无日志\n开启调试模式后操作即可看到日志",
                                    color = Color(0xFF666666),
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                items(logs) { line ->
                                    LogLine(line)
                                }
                            }
                        }
                    }
                    1 -> {
                        // 文件日志
                        if (fileLogContent.isBlank()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无日志文件\n开启调试模式后日志将保存到文件",
                                    color = Color(0xFF666666),
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            val fileLines = fileLogContent.lines()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                items(fileLines) { line ->
                                    LogLine(line)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 清理确认弹窗
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清理日志") },
            text = { Text("确定要清理所有日志文件吗？\n此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        DebugLog.clearLogFiles()
                        fileLogContent = ""
                        showClearDialog = false
                        Toast.makeText(context, "日志已清理", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("确定清理", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun LogLine(line: String) {
    val textColor = when {
        "[ERROR]" in line || "[FATAL]" in line -> Color(0xFFFF4444)
        "[WARN]" in line -> Color(0xFFFFAA00)
        "[AUTH]" in line || "[SEC]" in line -> Color(0xFF44AAFF)
        "[PROXY]" in line -> Color(0xFF00CC88)
        "[VPN]" in line -> Color(0xFF44DDFF)
        "[UI]" in line || "[SETTINGS]" in line -> Color(0xFFBB86FC)
        line.startsWith("===") -> Color(0xFFFFD700) // 文件分隔线
        else -> Color(0xFF00FF41)
    }
    Text(
        text = line,
        color = textColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 15.sp,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
