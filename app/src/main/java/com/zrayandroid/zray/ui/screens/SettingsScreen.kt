package com.zrayandroid.zray.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zrayandroid.zray.core.DnsProtocol
import com.zrayandroid.zray.core.ZrayDnsResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    socksPort: Int,
    onPortChange: (Int) -> Unit,
    debugEnabled: Boolean,
    onDebugToggle: (Boolean) -> Unit,
    enableIpv6: Boolean = false,
    onIpv6Toggle: (Boolean) -> Unit = {},
    dnsProtocol: DnsProtocol = DnsProtocol.DOH,
    onDnsProtocolChange: (DnsProtocol) -> Unit = {},
    dnsServer: String = ZrayDnsResolver.DEFAULT_DOH_SERVER,
    onDnsServerChange: (String) -> Unit = {},
    onOpenLogViewer: () -> Unit = {}
) {
    var portText by remember { mutableStateOf(socksPort.toString()) }
    var showAbout by remember { mutableStateOf(false) }
    var dnsServerText by remember { mutableStateOf(dnsServer) }
    var dnsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ===== SOCKS5 端口 =====
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("SOCKS5 端口", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it
                        it.toIntOrNull()?.let { port ->
                            if (port in 1024..65535) onPortChange(port)
                        }
                    },
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "监听地址: 127.0.0.1:$portText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== DNS 设置 =====
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("DNS 设置", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "选择 DNS 解析协议和服务器，防止 DNS 污染",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // DNS 协议选择
                ExposedDropdownMenuBox(
                    expanded = dnsExpanded,
                    onExpandedChange = { dnsExpanded = !dnsExpanded }
                ) {
                    OutlinedTextField(
                        value = when (dnsProtocol) {
                            DnsProtocol.UDP -> "UDP (传统 DNS)"
                            DnsProtocol.DOH -> "DoH (DNS over HTTPS)"
                            DnsProtocol.DOT -> "DoT (DNS over TLS)"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("协议") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dnsExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = dnsExpanded,
                        onDismissRequest = { dnsExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("UDP (传统 DNS)") },
                            onClick = {
                                onDnsProtocolChange(DnsProtocol.UDP)
                                dnsServerText = ZrayDnsResolver.DEFAULT_UDP_SERVER
                                onDnsServerChange(ZrayDnsResolver.DEFAULT_UDP_SERVER)
                                dnsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("DoH (DNS over HTTPS)") },
                            onClick = {
                                onDnsProtocolChange(DnsProtocol.DOH)
                                dnsServerText = ZrayDnsResolver.DEFAULT_DOH_SERVER
                                onDnsServerChange(ZrayDnsResolver.DEFAULT_DOH_SERVER)
                                dnsExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("DoT (DNS over TLS)") },
                            onClick = {
                                onDnsProtocolChange(DnsProtocol.DOT)
                                dnsServerText = ZrayDnsResolver.DEFAULT_DOT_SERVER
                                onDnsServerChange(ZrayDnsResolver.DEFAULT_DOT_SERVER)
                                dnsExpanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // DNS 服务器地址
                OutlinedTextField(
                    value = dnsServerText,
                    onValueChange = {
                        dnsServerText = it
                        onDnsServerChange(it)
                    },
                    label = {
                        Text(
                            when (dnsProtocol) {
                                DnsProtocol.UDP -> "DNS 服务器 IP"
                                DnsProtocol.DOH -> "DoH URL"
                                DnsProtocol.DOT -> "DoT 服务器 (IP:853)"
                            }
                        )
                    },
                    placeholder = {
                        Text(
                            when (dnsProtocol) {
                                DnsProtocol.UDP -> ZrayDnsResolver.DEFAULT_UDP_SERVER
                                DnsProtocol.DOH -> ZrayDnsResolver.DEFAULT_DOH_SERVER
                                DnsProtocol.DOT -> ZrayDnsResolver.DEFAULT_DOT_SERVER
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    when (dnsProtocol) {
                        DnsProtocol.UDP -> "传统 UDP DNS，可能受到 DNS 污染"
                        DnsProtocol.DOH -> "通过 HTTPS 加密 DNS 查询（推荐）"
                        DnsProtocol.DOT -> "通过 TLS 加密 DNS 查询（端口 853）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 调试模式开关 =====
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("调试模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (debugEnabled) "已开启 — 全量日志将保存至文件"
                            else "关闭状态 — 仅保留内存日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Switch(checked = debugEnabled, onCheckedChange = onDebugToggle)
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 查看日志按钮
                OutlinedButton(
                    onClick = onOpenLogViewer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("查看日志")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== IPv6 代理支持 =====
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("IPv6 代理", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (enableIpv6) "已启用 — 代理 IPv4 + IPv6 流量"
                        else "已关闭 — 仅代理 IPv4 流量",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = enableIpv6, onCheckedChange = onIpv6Toggle)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 关于 =====
        Card(
            onClick = { showAbout = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("关于", style = MaterialTheme.typography.titleSmall)
                Text(
                    "v${com.zrayandroid.zray.APP_VERSION}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Zray for Android") },
            text = {
                Column {
                    Text("v${com.zrayandroid.zray.APP_VERSION}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("轻量加密代理客户端")
                    Text("核心: Go uTLS 高性能引擎")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "https://github.com/xiaoxinmm/Zray-for-Android",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("确定") }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}
