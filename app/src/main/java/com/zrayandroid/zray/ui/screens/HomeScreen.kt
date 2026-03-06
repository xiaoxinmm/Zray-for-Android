package com.zrayandroid.zray.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zrayandroid.zray.core.CoreType

@Composable
fun HomeScreen(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    hasProfile: Boolean,
    activeProfileName: String?,
    onToggle: () -> Unit,
    socksPort: Int,
    latencyMs: Long = -1,
    uploadSpeed: Long = 0,
    downloadSpeed: Long = 0,
    totalUpload: Long = 0,
    totalDownload: Long = 0,
    selectedCoreType: CoreType = CoreType.KOTLIN_CORE
) {
    val isGoCore = selectedCoreType == CoreType.GO_CORE

    // ==================== 颜色渐变过渡 ====================
    val connectedColor = Color(0xFF00C853)          // 绿色：已连接
    val connectingColor = Color(0xFFFFC107)          // 黄色：连接中
    val disconnectedColor = Color(0xFF78909C)        // 灰色：断开
    val primaryColor = MaterialTheme.colorScheme.primary

    val targetButtonColor = when {
        !hasProfile && !isConnected -> MaterialTheme.colorScheme.surfaceVariant
        isConnecting -> connectingColor
        isConnected -> connectedColor
        else -> primaryColor
    }
    val buttonColor by animateColorAsState(
        targetValue = targetButtonColor,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "buttonColor"
    )

    // ==================== 涟漪扩散动画 (多圈) ====================
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    // 三圈涟漪，依次延迟
    val ring1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring1"
    )
    val ring2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring2"
    )
    val ring3 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring3"
    )

    // 呼吸光效
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "glow"
    )

    // ==================== 网速历史数据 ====================
    val maxHistory = 30
    val uploadHistory = remember { mutableStateListOf<Long>() }
    val downloadHistory = remember { mutableStateListOf<Long>() }

    LaunchedEffect(uploadSpeed, downloadSpeed) {
        uploadHistory.add(uploadSpeed)
        downloadHistory.add(downloadSpeed)
        if (uploadHistory.size > maxHistory) uploadHistory.removeAt(0)
        if (downloadHistory.size > maxHistory) downloadHistory.removeAt(0)
    }

    // 断开时清空历史
    LaunchedEffect(isConnected) {
        if (!isConnected) {
            uploadHistory.clear()
            downloadHistory.clear()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // 当前节点信息
        if (activeProfileName != null) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = if (isConnected) connectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activeProfileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isConnected) {
                        Text(
                            "● 在线",
                            color = connectedColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // ==================== 大圆形开关按钮（含涟漪） ====================
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(220.dp)
        ) {
            // 涟漪扩散环 (仅已连接状态显示)
            if (isConnected) {
                val ringColor = connectedColor
                Canvas(modifier = Modifier.size(220.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension / 2f

                    listOf(ring1, ring2, ring3).forEach { progress ->
                        val radius = 75f + (maxRadius - 75f) * progress
                        val alpha = (1f - progress) * 0.4f
                        drawCircle(
                            color = ringColor.copy(alpha = alpha),
                            radius = radius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // 外层光晕
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .shadow(
                            elevation = (glowAlpha * 28).dp,
                            shape = CircleShape,
                            ambientColor = connectedColor.copy(alpha = glowAlpha * 0.5f),
                            spotColor = connectedColor.copy(alpha = glowAlpha * 0.5f)
                        )
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    connectedColor.copy(alpha = glowAlpha * 0.12f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
            }

            // 连接中黄色脉冲
            if (isConnecting) {
                val pulseAnim by infiniteTransition.animateFloat(
                    initialValue = 0.85f, targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ), label = "connectingPulse"
                )
                Box(
                    modifier = Modifier
                        .size((160 * pulseAnim).dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    connectingColor.copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
            }

            // 主按钮
            Surface(
                modifier = Modifier
                    .size(150.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (hasProfile || isConnected) onToggle()
                        }
                    ),
                shape = CircleShape,
                color = buttonColor,
                shadowElevation = if (isConnected) 16.dp else 8.dp,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                if (isConnected) Icons.Default.PowerSettingsNew else Icons.Default.Power,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                isConnecting -> "连接中"
                                !hasProfile -> "无配置"
                                isConnected -> "断开"
                                else -> "连接"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (!hasProfile && !isConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "请先在「配置」页添加节点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.weight(0.15f))

        // ==================== 实时网速折线图 ====================
        if (isConnected && !isGoCore) {
            SpeedChart(
                uploadHistory = uploadHistory,
                downloadHistory = downloadHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ==================== 状态卡片 ====================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ArrowUpward,
                label = "上传",
                value = when {
                    isGoCore && isConnected -> "不适用"
                    isConnected -> com.zrayandroid.zray.core.TrafficStats.formatSpeed(uploadSpeed)
                    else -> "--"
                },
                color = Color(0xFF42A5F5)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ArrowDownward,
                label = "下载",
                value = when {
                    isGoCore && isConnected -> "不适用"
                    isConnected -> com.zrayandroid.zray.core.TrafficStats.formatSpeed(downloadSpeed)
                    else -> "--"
                },
                color = Color(0xFF66BB6A)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Speed,
                label = "延迟",
                value = when {
                    isGoCore && isConnected -> "不适用"
                    isConnected -> {
                        if (latencyMs > 0) "${latencyMs} ms"
                        else if (latencyMs == 0L) "< 1 ms"
                        else "测量中..."
                    }
                    else -> "--"
                },
                color = Color(0xFFFFA726)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Hub,
                label = "连接数",
                value = when {
                    isGoCore && isConnected -> "不适用"
                    isConnected -> "${com.zrayandroid.zray.core.TrafficStats.activeConns.get()}"
                    else -> "--"
                },
                color = Color(0xFFAB47BC)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SOCKS5 状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lan,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "SOCKS5",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "127.0.0.1:$socksPort",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ==================== 实时网速折线图 ====================

@Composable
private fun SpeedChart(
    uploadHistory: List<Long>,
    downloadHistory: List<Long>,
    modifier: Modifier = Modifier
) {
    val uploadColor = Color(0xFF42A5F5)
    val downloadColor = Color(0xFF66BB6A)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val w = size.width
            val h = size.height

            // 绘制网格线
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5.dp.toPx())
            }

            val allValues = uploadHistory + downloadHistory
            val maxVal = (allValues.maxOrNull() ?: 1L).coerceAtLeast(1024)

            fun drawSpeedLine(data: List<Long>, color: Color) {
                if (data.size < 2) return
                val path = Path()
                val maxPoints = 30
                val points = if (data.size > maxPoints) data.takeLast(maxPoints) else data
                val stepX = w / (maxPoints - 1).coerceAtLeast(1)

                points.forEachIndexed { i, value ->
                    val x = i * stepX
                    val y = h - (value.toFloat() / maxVal * h * 0.9f).coerceIn(0f, h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.8f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            drawSpeedLine(uploadHistory, uploadColor)
            drawSpeedLine(downloadHistory, downloadColor)
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
