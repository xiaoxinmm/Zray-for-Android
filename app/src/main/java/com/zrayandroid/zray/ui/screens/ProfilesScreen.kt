package com.zrayandroid.zray.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val server: String = "",
    val port: Int = 64433,
    val userHash: String = "",
    val link: String = "",
    @Transient val isActive: Boolean = false
) {
    fun toConfigJson(): String = """
        {"remote_host":"$server","remote_port":$port,"user_hash":"$userHash"}
    """.trimIndent()

    val displayInfo: String
        get() = if (server.isNotEmpty()) "$server:$port" else "ZA://...${link.takeLast(12)}"
}

@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    onAddProfile: (Profile) -> Unit,
    onUpdateProfile: (Profile) -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Profile?>(null) }
    var addMode by remember { mutableStateOf("manual") } // "manual" or "link"

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加配置")
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击 + 添加服务器或导入 ZA 链接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileItem(
                        profile = profile,
                        onSelect = { onSelectProfile(profile.id) },
                        onEdit = { showEditDialog = profile },
                        onDelete = { onDeleteProfile(profile.id) }
                    )
                }
            }
        }
    }

    // 添加配置对话框
    if (showAddDialog) {
        AddProfileDialog(
            mode = addMode,
            onModeChange = { addMode = it },
            onConfirm = { profile ->
                onAddProfile(profile)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // 编辑配置对话框
    showEditDialog?.let { profile ->
        EditProfileDialog(
            profile = profile,
            onConfirm = { updated ->
                onUpdateProfile(updated)
                showEditDialog = null
            },
            onDismiss = { showEditDialog = null }
        )
    }
}

@Composable
private fun AddProfileDialog(
    mode: String,
    onModeChange: (String) -> Unit,
    onConfirm: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("64433") }
    var userHash by remember { mutableStateOf("") }
    var linkInput by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加配置") },
        text = {
            Column {
                // 模式切换
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = mode == "manual",
                        onClick = { onModeChange("manual") },
                        label = { Text("手动配置") },
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                    FilterChip(
                        selected = mode == "link",
                        onClick = { onModeChange("link") },
                        label = { Text("ZA 链接") },
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (mode == "manual") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        placeholder = { Text("我的节点") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server,
                        onValueChange = {
                            server = it
                            // 校验：IP 地址或域名
                            val trimmed = it.trim()
                            serverError = when {
                                trimmed.isEmpty() -> null
                                trimmed.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) -> {
                                    // IPv4 — 验证每个八位组 0-255
                                    val valid = trimmed.split(".").all { o -> o.toIntOrNull()?.let { v -> v in 0..255 } == true }
                                    if (valid) null else "IP 地址八位组范围: 0-255"
                                }
                                trimmed.contains(":") && trimmed.matches(Regex("^[0-9a-fA-F:]+$")) -> {
                                    // IPv6 — 基本格式检查
                                    try {
                                        java.net.InetAddress.getByName(trimmed)
                                        null
                                    } catch (_: Exception) { "无效的 IPv6 地址" }
                                }
                                trimmed.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)*$")) -> null
                                else -> "请输入有效的 IP 地址或域名"
                            }
                        },
                        label = { Text("服务器地址") },
                        placeholder = { Text("1.2.3.4 或 domain.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = serverError != null,
                        supportingText = serverError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            val p = it.toIntOrNull()
                            portError = if (it.isBlank() || (p != null && p in 1..65535)) null
                            else "端口范围: 1-65535"
                        },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = portError != null,
                        supportingText = portError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userHash,
                        onValueChange = { userHash = it },
                        label = { Text("密钥 (user_hash)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    // 预先计算链接解析结果，避免在 composable 参数中使用 try/catch
                    val linkPreview = remember(linkInput, linkError) {
                        if (linkError == null && linkInput.trim().startsWith("ZA://", ignoreCase = true) && linkInput.trim().length >= 10) {
                            try {
                                val cfg = com.zrayandroid.zray.core.ZALinkParser.parse(linkInput.trim())
                                "✓ ${cfg.host}:${cfg.port}"
                            } catch (_: Exception) { null }
                        } else null
                    }

                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = {
                            linkInput = it
                            val trimmed = it.trim()
                            linkError = when {
                                trimmed.isEmpty() -> null
                                !trimmed.startsWith("ZA://", ignoreCase = true) -> "链接必须以 ZA:// 开头"
                                trimmed.length < 10 -> "链接内容太短，请检查是否完整"
                                else -> {
                                    // 尝试即时解析校验
                                    try {
                                        com.zrayandroid.zray.core.ZALinkParser.parse(trimmed)
                                        null // 解析成功
                                    } catch (e: Exception) {
                                        "链接格式错误: ${e.message?.take(100) ?: "解析失败"}"
                                    }
                                }
                            }
                        },
                        label = { Text("粘贴 ZA:// 链接") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                        isError = linkError != null,
                        supportingText = if (linkError != null) {
                            { Text(linkError!!, color = MaterialTheme.colorScheme.error) }
                        } else if (linkPreview != null) {
                            { Text(linkPreview, color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "导入后可在配置列表中编辑详细信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == "manual" && server.isNotBlank() && serverError == null && portError == null) {
                        val p = Profile(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name.ifBlank { server },
                            server = server.trim(),
                            port = port.toIntOrNull() ?: 64433,
                            userHash = userHash.trim()
                        )
                        onConfirm(p)
                    } else if (mode == "link" && linkInput.trim().startsWith("ZA://", ignoreCase = true) && linkError == null) {
                        try {
                            val cfg = com.zrayandroid.zray.core.ZALinkParser.parse(linkInput.trim())
                            val p = Profile(
                                id = java.util.UUID.randomUUID().toString(),
                                name = "${cfg.host}:${cfg.port}",
                                link = linkInput.trim(),
                                server = cfg.host,
                                port = cfg.port,
                                userHash = cfg.userHash
                            )
                            com.zrayandroid.zray.core.DebugLog.log("PROFILE", "ZA 链接解析成功: ${cfg.host}:${cfg.port}")
                            onConfirm(p)
                        } catch (e: Exception) {
                            linkError = "解析失败: ${e.message?.take(100) ?: "未知错误"}"
                            com.zrayandroid.zray.core.DebugLog.log("ERROR", "ZA 链接解析失败: ${e.message}")
                        }
                    }
                },
                enabled = if (mode == "manual") server.isNotBlank() && serverError == null && portError == null
                         else linkInput.trim().startsWith("ZA://", ignoreCase = true) && linkError == null
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun EditProfileDialog(
    profile: Profile,
    onConfirm: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var server by remember { mutableStateOf(profile.server) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var userHash by remember { mutableStateOf(profile.userHash) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑配置") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = userHash,
                    onValueChange = { userHash = it },
                    label = { Text("密钥 (user_hash)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                if (profile.link.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "原始链接: ZA://...${profile.link.takeLast(16)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(profile.copy(
                        name = name.ifBlank { server },
                        server = server.trim(),
                        port = port.toIntOrNull() ?: 64433,
                        userHash = userHash.trim()
                    ))
                },
                enabled = server.isNotBlank() || profile.link.isNotEmpty()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ProfileItem(
    profile: Profile,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示
            if (profile.isActive) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "当前使用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = profile.displayInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // 编辑按钮
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // 删除按钮
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
