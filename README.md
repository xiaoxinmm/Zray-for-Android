# Zray for Android

Zray 加密代理的 Android 客户端。前台保活 + Material Design 3 界面。

不接管 VPN，仅在本地开 SOCKS5 端口（默认 127.0.0.1:1081），配合 NekoBox 等软件使用。

## 功能

- 大圆形开关按钮，一键连接/断开
- ZA:// 加密链接导入，多配置管理
- 实时上传/下载速度显示
- 前台服务保活，后台不被杀
- Material Design 3，支持深色模式和 Dynamic Color
- SOCKS5 端口自定义

## 使用

1. 从 [Releases](https://github.com/xiaoxinmm/Zray-for-Android/releases) 下载 APK
2. 安装后打开，进「配置」页面
3. 点 + 号，粘贴 ZA:// 链接
4. 回到首页，点圆形按钮连接
5. 在 NekoBox 等软件中设置上游代理为 `socks5://127.0.0.1:1081`

## 编译

需要 JDK 17 + Android SDK。

```bash
./gradlew assembleDebug
```

或者推 tag 触发 GitHub Actions 自动编译。

## 核心替换

当前使用 `ZrayCoreMock` 模拟核心。编译真实 zraylib.aar 后：

1. 把 `zraylib.aar` 放到 `app/libs/`
2. 在 `app/build.gradle.kts` 添加 `implementation(files("libs/zraylib.aar"))`
3. 将 `ZrayCoreMock.start/stop` 替换为 `zraylib.Zraylib.start/stop`

## 关联项目

- [Zray](https://github.com/xiaoxinmm/Zray) — 主项目（服务端 + 桌面客户端 + 核心协议）

## 协议

MIT
# v1.1.0
