// Zray Android Core — 安卓专用精简版 Go 代理核心
//
// 功能: 本地 SOCKS5 服务 → Zray 协议转发（uTLS Chrome 指纹伪装）
// 特点: 无 GUI、无路由分流、无 HTTP API、纯静默运行
// 调用: Android 端通过 ProcessBuilder 启动，stdout 捕获日志
// 编译: CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o libzraycore.so
package main

import (
	"fmt"
	"net"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"
)

// 全局运行时状态（原子操作，线程安全）
var (
	config Config

	activeConns  int64
	proxiedConns int64
	uploadBytes  int64
	downloadBytes int64
)

// listener 全局引用，用于优雅关闭
var listener net.Listener

func main() {
	// 日志输出到 stdout，Android 通过 Process.inputStream 读取
	fmt.Println("[GO-CORE] Starting Zray Android Core...")

	// 1. 加载配置
	var err error
	config, err = loadConfig()
	if err != nil {
		fmt.Printf("[ERROR] 加载配置失败: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("[GO-CORE] Remote: %s:%d\n", config.RemoteHost, config.RemotePort)
	fmt.Printf("[GO-CORE] SOCKS5: %s\n", config.GlobalPort)
	fmt.Printf("[GO-CORE] TLS: uTLS Chrome fingerprint enabled\n")

	// 2. 注册信号处理（优雅关闭）
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	// 3. 启动 SOCKS5 监听
	listener, err = net.Listen("tcp", config.GlobalPort)
	if err != nil {
		fmt.Printf("[ERROR] SOCKS5 端口监听失败 %s: %v\n", config.GlobalPort, err)
		os.Exit(1)
	}
	fmt.Printf("[GO-CORE] SOCKS5 listening on %s\n", config.GlobalPort)

	// 4. 后台接受连接
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				// 监听器被关闭时退出循环
				return
			}
			go handleSocks5(c)
		}
	}()

	// 5. 后台输出流量统计（每秒一次，供 Android 解析）
	go outputStats()

	// 6. 等待终止信号
	sig := <-sigCh
	fmt.Printf("[GO-CORE] Received signal: %v, shutting down...\n", sig)
	shutdown()
	fmt.Println("[GO-CORE] Shutdown complete.")
	os.Exit(0)
}

// shutdown 优雅关闭：先关监听器（拒绝新连接），等待现有连接自然结束
func shutdown() {
	if listener != nil {
		listener.Close()
	}
	// 给现有连接 2 秒收尾时间
	time.Sleep(2 * time.Second)
}

// outputStats 定期输出流量统计到 stdout，供 Kotlin 端正则解析
// 格式: [STATS] up=<bytes> down=<bytes> conns=<n> proxied=<n>
func outputStats() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	var lastUp, lastDown int64

	for range ticker.C {
		up := atomic.LoadInt64(&uploadBytes)
		down := atomic.LoadInt64(&downloadBytes)
		conns := atomic.LoadInt64(&activeConns)
		proxied := atomic.LoadInt64(&proxiedConns)

		upSpeed := up - lastUp
		downSpeed := down - lastDown

		fmt.Printf("[STATS] up=%d down=%d up_speed=%d down_speed=%d conns=%d proxied=%d\n",
			up, down, upSpeed, downSpeed, conns, proxied)

		lastUp = up
		lastDown = down
	}
}
