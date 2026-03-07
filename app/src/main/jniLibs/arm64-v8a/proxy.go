package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync/atomic"
	"time"

	utls "github.com/refraction-networking/utls"
	"github.com/xiaoxinmm/Zray/pkg/camo"
	"github.com/xiaoxinmm/Zray/pkg/protocol"
	"github.com/xiaoxinmm/Zray/pkg/proxy"
)

// handleSocks5 处理单个 SOCKS5 连接请求（全局代理模式，所有流量走远端）
func handleSocks5(c net.Conn) {
	defer c.Close()
	atomic.AddInt64(&activeConns, 1)
	defer atomic.AddInt64(&activeConns, -1)

	c.SetDeadline(time.Now().Add(10 * time.Second))

	buf := make([]byte, 512)

	// SOCKS5 握手：版本 + 方法协商
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return
	}
	nMethods := int(buf[1])
	if _, err := io.ReadFull(c, buf[:nMethods]); err != nil {
		return
	}
	c.Write([]byte{5, 0}) // 无认证

	// SOCKS5 请求
	if _, err := io.ReadFull(c, buf[:4]); err != nil {
		return
	}
	cmd, atyp := buf[1], buf[3]
	if cmd != 1 { // 仅支持 CONNECT
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}

	var destBytes []byte
	var hostStr string
	switch atyp {
	case 1: // IPv4
		if _, err := io.ReadFull(c, buf[4:10]); err != nil {
			return
		}
		destBytes = buf[4:10]
		hostStr = fmt.Sprintf("%d.%d.%d.%d", buf[4], buf[5], buf[6], buf[7])
	case 3: // 域名
		if _, err := io.ReadFull(c, buf[4:5]); err != nil {
			return
		}
		l := int(buf[4])
		if _, err := io.ReadFull(c, buf[5:5+l+2]); err != nil {
			return
		}
		destBytes = buf[4 : 5+l+2]
		hostStr = string(buf[5 : 5+l])
	case 4: // IPv6
		if _, err := io.ReadFull(c, buf[4:22]); err != nil {
			return
		}
		destBytes = buf[4:22]
		hostStr = "IPv6"
	default:
		return
	}

	port := binary.BigEndian.Uint16(destBytes[len(destBytes)-2:])
	targetAddr := fmt.Sprintf("%s:%d", hostStr, port)

	c.SetDeadline(time.Time{}) // 清除超时，进入数据转发阶段

	// Android 全局代理模式：所有流量直接转发到远端
	atomic.AddInt64(&proxiedConns, 1)
	handleProxy(c, targetAddr, atyp, destBytes)
}

// handleProxy 通过 Zray 协议转发流量到远端服务器
func handleProxy(c net.Conn, target string, atyp byte, destBytes []byte) {
	svr, err := dialServer()
	if err != nil {
		fmt.Printf("[ERROR] 连接远程失败: %v\n", err)
		return
	}
	defer svr.Close()

	// TLS 加密的 HTTP 伪装
	if err := camo.WriteHTTPCamo(svr, config.RemoteHost); err != nil {
		return
	}

	addr := &protocol.Address{
		Port: binary.BigEndian.Uint16(destBytes[len(destBytes)-2:]),
		Type: atyp,
		Raw:  destBytes[:len(destBytes)-2],
	}

	if err := protocol.WriteRequest(svr, config.UserHash, protocol.CmdConnect, addr); err != nil {
		return
	}

	// 回复 SOCKS5 成功
	c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	fmt.Printf("[PROXY] -> %s\n", target)

	// 双向数据转发，统计流量
	up, down := proxy.Relay(c, svr)
	atomic.AddInt64(&uploadBytes, up)
	atomic.AddInt64(&downloadBytes, down)
}

// dialServer 连接到 Zray 远端服务器，使用 uTLS Chrome 指纹伪装
func dialServer() (net.Conn, error) {
	addr := fmt.Sprintf("%s:%d", config.RemoteHost, config.RemotePort)
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
	}

	tcpConn, err := dialer.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := tcpConn.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}

	// uTLS 模拟 Chrome TLS Client Hello 指纹
	uConn := utls.UClient(tcpConn, &utls.Config{
		InsecureSkipVerify: true, // Zray 服务端使用自签证书
		ServerName:         config.RemoteHost,
	}, utls.HelloChrome_Auto)

	if err := uConn.Handshake(); err != nil {
		tcpConn.Close()
		return nil, fmt.Errorf("TLS 握手失败: %w", err)
	}
	return uConn, nil
}
