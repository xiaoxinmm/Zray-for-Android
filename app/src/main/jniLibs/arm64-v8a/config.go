package main

import (
	"encoding/json"
	"fmt"
	"os"
)

// Config 安卓专用极简配置结构体。
// 仅包含 Android 客户端所需字段；保留 smart_port 等冗余字段防止反序列化报错。
type Config struct {
	GlobalPort string `json:"global_port"`  // 本地 SOCKS5 监听地址，例如 "127.0.0.1:1081"
	RemoteHost string `json:"remote_host"`  // Zray 节点服务器 IP 或域名
	RemotePort int    `json:"remote_port"`  // Zray 节点服务器端口
	UserHash   string `json:"user_hash"`    // 16 字节认证密钥

	// 以下为兼容字段，读取但不使用，避免 JSON 解析失败
	SmartPort   string `json:"smart_port,omitempty"`
	EnableTFO   bool   `json:"enable_tfo,omitempty"`
	GeositePath string `json:"geosite_path,omitempty"`
}

// loadConfig 从当前工作目录读取 config.json
func loadConfig() (Config, error) {
	var cfg Config

	f, err := os.Open("config.json")
	if err != nil {
		return cfg, fmt.Errorf("打开配置文件失败: %w", err)
	}
	defer f.Close()

	if err := json.NewDecoder(f).Decode(&cfg); err != nil {
		return cfg, fmt.Errorf("解析配置文件失败: %w", err)
	}

	// 校验必要字段
	if cfg.GlobalPort == "" {
		return cfg, fmt.Errorf("配置缺少 global_port")
	}
	if cfg.RemoteHost == "" {
		return cfg, fmt.Errorf("配置缺少 remote_host")
	}
	if cfg.RemotePort <= 0 {
		return cfg, fmt.Errorf("配置缺少有效的 remote_port")
	}
	if cfg.UserHash == "" {
		return cfg, fmt.Errorf("配置缺少 user_hash")
	}

	return cfg, nil
}
