# Zray for Android

<p align="center">
  <img src="https://img.shields.io/badge/Android-26%2B-green" />
  <img src="https://img.shields.io/badge/Kotlin-1.9-blue" />
  <img src="https://img.shields.io/badge/Compose-Material3-purple" />
  <img src="https://img.shields.io/github/v/release/xiaoxinmm/Zray-for-Android" />
</p>

Zray 加密代理 Android 客户端。完整实现 Zray 协议（TLS + HTTP 伪装 + 自定义认证），配合 [Zray 服务端](https://github.com/xiaoxinmm/Zray) 使用。

本地开启 SOCKS5 端口，不接管系统 VPN，配合 NekoBox / Clash 等工具使用。

---

## 功能

- 🔐 **Zray 协议** — TLS 加密 + HTTP 伪装 + 时间戳防重放认证，与 Go 服务端完全兼容
- 🔗 **ZA:// 链接导入** — 粘贴链接一键添加节点
- 📡 **实时延迟** — TCP ping 远程服务器，首页实时显示延迟（ms）
- 🎛️ **多配置管理** — 支持多个节点，自由切换
- 🔧 **端口自定义** — SOCKS5 监听端口可在设置中修改
- 🛡️ **前台服务保活** — 后台持续运行不被系统杀掉
- 🎨 **Material Design 3** — 支持深色模式 + Dynamic Color
- 📝 **完整日志** — 实时文件日志，自动清理 7 天前记录，一键复制导出
- 🐛 **Debug 浮窗** — 开发调试用，实时查看连接日志

## 截图

| 首页 | 配置 | 设置 |
|:---:|:---:|:---:|
| 连接状态 + 延迟 | 节点列表 | 端口 + Debug |

## 下载

从 [Releases](https://github.com/xiaoxinmm/Zray-for-Android/releases) 下载最新 APK。

## 使用

### 1. 添加节点

打开「配置」页面，点 **+** 号，输入：

- **名称**：自定义（如「香港节点」）
- **配置**：支持以下格式

**JSON 格式：**
```json
{
  "remote_host": "your-server-ip",
  "remote_port": 64433,
  "user_hash": "your-16byte-key"
}
```

**ZA:// 加密链接：**
```
ZA://KQ48z5c-8YxDJfqH6fM1T4uapzUV...
```

**简单格式：**
```
host:port:userhash
```

### 2. 连接

回到首页，点击圆形按钮即可连接。连接成功后显示延迟。

### 3. 配合代理工具

在 NekoBox / Clash / SagerNet 中添加上游代理：

```
socks5://127.0.0.1:1081
```

默认端口 1081，可在「设置」中修改。

## 协议原理

```
App → SOCKS5 请求 → TCP 连接远程 → TLS 握手
  → HTTP 伪装头（模拟 Chrome 浏览器）
  → Zray 协议头（版本 + 时间戳 + Nonce + UserHash）
  → 随机 Padding
  → CONNECT 命令 + 目标地址
  → 双向 Relay
```

- TLS 加密传输，自签证书
- HTTP 伪装对抗 DPI 检测
- 时间戳 + Nonce 防重放攻击
- 16 字节 UserHash 认证

## 编译

需要 JDK 17 + Android SDK 35。

```bash
git clone https://github.com/xiaoxinmm/Zray-for-Android.git
cd Zray-for-Android
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

推送代码到 `main` 分支会触发 GitHub Actions 自动编译发布。

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material Design 3
- **持久化**：DataStore (Preferences)
- **网络**：Java NIO / SSLSocket
- **最低版本**：Android 8.0 (API 26)
- **目标版本**：Android 15 (API 35)

## 项目结构

```
app/src/main/java/com/zrayandroid/zray/
├── MainActivity.kt          # 主界面 + 导航
├── core/
│   ├── ZrayCoreMock.kt      # Zray 协议核心（TLS + 伪装 + SOCKS5）
│   ├── DebugLog.kt          # 日志系统（内存 + 文件双写）
│   └── ProfileStore.kt      # 配置持久化
├── service/
│   └── ZrayService.kt       # 前台保活服务
├── navigation/
│   └── Screen.kt            # 导航定义
└── ui/
    ├── screens/
    │   ├── HomeScreen.kt     # 首页（连接 + 状态）
    │   ├── ProfilesScreen.kt # 配置管理
    │   └── SettingsScreen.kt # 设置
    └── components/
        └── DebugOverlay.kt   # Debug 浮窗
```

## 关联项目

- [Zray](https://github.com/xiaoxinmm/Zray) — 服务端 + 桌面客户端 + 协议定义

## 许可

MIT License
