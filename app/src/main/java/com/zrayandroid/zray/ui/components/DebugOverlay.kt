package com.zrayandroid.zray.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zrayandroid.zray.core.DebugLog

@Composable
fun DebugOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val logs by DebugLog.logs.collectAsState()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    // 自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.4f)
                .background(
                    Color(0xEE1A1A1A),
                    RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Debug Log",
                        color = Color(0xFF00FF41),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row {
                        // 复制按钮（包含完整文件日志）
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(DebugLog.getFullFileLog()))
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制日志",
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 清空按钮
                        IconButton(
                            onClick = { DebugLog.clear() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "清空",
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 关闭按钮
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 日志内容
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无日志",
                            color = Color(0xFF666666),
                            fontSize = 12.sp,
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
                            val textColor = when {
                                "[ERROR]" in line || "[FATAL]" in line -> Color(0xFFFF4444)
                                "[WARN]" in line -> Color(0xFFFFAA00)
                                "[AUTH]" in line || "[SEC]" in line -> Color(0xFF44AAFF)
                                "[PROXY]" in line -> Color(0xFF00CC88)
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
                    }
                }
            }
        }
    }
}
