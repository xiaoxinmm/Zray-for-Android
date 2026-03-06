package com.zrayandroid.zray.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zrayandroid.zray.core.AppInfo
import com.zrayandroid.zray.core.PerAppMode
import com.zrayandroid.zray.core.ProxyMode
import com.zrayandroid.zray.core.RoutingConfig

@Composable
fun RoutingScreen(
    config: RoutingConfig,
    onConfigChange: (RoutingConfig) -> Unit,
    installedApps: List<AppInfo>,
    isVpnRunning: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "路由",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // ===== 代理模式选择 =====
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("代理模式", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (isVpnRunning) "VPN 运行中，断开后可切换" else "选择流量代理方式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                ProxyMode.values().forEach { mode ->
                    ProxyModeCard(
                        title = mode.displayName,
                        subtitle = mode.desc,
                        selected = config.mode == mode,
                        enabled = !isVpnRunning,
                        onClick = { onConfigChange(config.copy(mode = mode)) }
                    )
                    if (mode != ProxyMode.values().last()) Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // ===== 分应用设置（仅 VPN_PER_APP 模式显示） =====
        if (config.mode == ProxyMode.VPN_PER_APP) {
            Spacer(modifier = Modifier.height(16.dp))

            // 白名单/黑名单切换
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PerAppMode.values().forEach { mode ->
                        FilterChip(
                            selected = config.perAppMode == mode,
                            onClick = { onConfigChange(config.copy(perAppMode = mode)) },
                            label = { Text(mode.displayName, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.weight(1f),
                            enabled = !isVpnRunning
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索应用") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 显示系统应用开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已选 ${config.selectedApps.size} 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示系统应用", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 应用列表
            val filtered = installedApps.filter { app ->
                (showSystemApps || !app.isSystem) &&
                (searchQuery.isBlank() || app.name.contains(searchQuery, true) || app.packageName.contains(searchQuery, true))
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in config.selectedApps
                    AppItem(
                        app = app,
                        isSelected = isSelected,
                        enabled = !isVpnRunning,
                        onToggle = {
                            val newSet = if (isSelected) {
                                config.selectedApps - app.packageName
                            } else {
                                config.selectedApps + app.packageName
                            }
                            onConfigChange(config.copy(selectedApps = newSet))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyModeCard(
    title: String, subtitle: String,
    selected: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        onClick = onClick, enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (selected) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun AppItem(
    app: AppInfo, isSelected: Boolean, enabled: Boolean, onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (app.isSystem) {
            Text("系统", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}
