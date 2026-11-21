// SPDX-License-Identifier: GPL-3.0-or-later
package main

/*
#include <stdlib.h>
#include "bridge.h"
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"go.yaml.in/yaml/v3"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"dev.dsh/onetunnel/native/appsettings"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var coreMu sync.Mutex
var tun io.Closer
var configured bool
var errRuleDatabase = errors.New("rule database unavailable")

//export GoPrepareCore
func GoPrepareCore(homeText, configText *C.char) (result *C.char) {
	coreMu.Lock()
	defer coreMu.Unlock()
	defer func() {
		if recover() != nil {
			result = C.CString("PROFILE_PREPARATION_FAILED")
		}
	}()
	if configured || tun != nil {
		return C.CString("Stop the current session first")
	}
	log.SetLevel(log.SILENT)
	if err := prepareHome(C.GoString(homeText)); err != nil {
		return errorString("private state directory", err)
	}
	// Keep the existing Android VPN/network available while initial databases
	// and rule files are prepared. No TUN, listeners, or proxy service is started.
	dialer.DefaultSocketHook = nil
	cfg, err := parseCoreProfile(C.GoString(configText))
	if err != nil {
		return errorString("profile", err)
	}
	for _, provider := range cfg.Providers {
		if closer, ok := provider.(io.Closer); ok {
			_ = closer.Close()
		}
	}
	for _, proxy := range cfg.Proxies {
		_ = proxy.Close()
	}
	return C.CString("")
}

func prepareHome(home string) error {
	if err := os.MkdirAll(home, 0700); err != nil {
		return err
	}
	for env, value := range map[string]string{"HOME": home, "TMPDIR": filepath.Join(home, "tmp"), "XDG_CACHE_HOME": filepath.Join(home, "cache"), "XDG_CONFIG_HOME": filepath.Join(home, "config")} {
		if err := os.MkdirAll(value, 0700); err != nil {
			return err
		}
		if err := os.Setenv(env, value); err != nil {
			return err
		}
	}
	_ = os.Setenv("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")
	MC.SetHomeDir(home)
	MC.Version = "onetunnel-meta-0455d0d"
	return nil
}

//export GoPrepareTailscale
func GoPrepareTailscale(homeText, configText, nodeText *C.char) (result *C.char) {
	coreMu.Lock()
	defer coreMu.Unlock()
	defer func() {
		if recover() != nil {
			stopLocked()
			result = errorString("Tailscale preparation", errors.New("panic"))
		}
	}()
	if configured || tun != nil {
		return C.CString("Stop the current session first")
	}
	log.SetLevel(log.SILENT)
	text := C.GoString(configText)
	if len(text) == 0 || len(text) > 1024*1024 {
		return C.CString("Invalid profile size")
	}
	if err := prepareHome(C.GoString(homeText)); err != nil {
		return errorString("private state directory", err)
	}
	var profile struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if err := yaml.Unmarshal([]byte(text), &profile); err != nil {
		return errorString("profile", err)
	}
	var node map[string]any
	for _, candidate := range profile.Proxies {
		if candidate["name"] == C.GoString(nodeText) && candidate["type"] == "tailscale" {
			node = candidate
			break
		}
	}
	if node == nil {
		return C.CString("Tailscale node not found")
	}
	// Interactive login is not a forwarding session. Keep configured host
	// services closed until the user starts the unified VPN connection.
	delete(node, "listen-ports")
	delete(node, "listen-all-ports")
	// Auth-only session: no TUN, no listeners, no providers, and no VPN socket
	// protection. Control traffic follows the user's existing network/VPN.
	dialer.DefaultSocketHook = nil
	raw := config.DefaultRawConfig()
	raw.Proxy = []map[string]any{node}
	raw.Rule = []string{"MATCH,DIRECT"}
	raw.Mode, raw.FindProcessMode, raw.LogLevel = tunnel.Rule, process.FindProcessOff, log.SILENT
	raw.DNS.Enable = true
	raw.DNS.NameServer = []string{"https://dns.alidns.com/dns-query", "https://1.1.1.1/dns-query"}
	raw.DNS.DefaultNameserver = []string{"223.5.5.5", "1.1.1.1"}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		return errorString("Tailscale profile", err)
	}
	configured = true
	executor.ApplyConfig(cfg, true)
	return C.CString("")
}

//export GoTailscaleAction
func GoTailscaleAction(nodeText, actionText *C.char) (result *C.char) {
	coreMu.Lock()
	defer coreMu.Unlock()
	diagnostic := func(stage string, err error) *C.char {
		code := stage
		if err != nil {
			message := strings.ToLower(err.Error())
			for _, hint := range []string{"ts_start", "ts_local_client", "permission", "netlink", "bind", "android", "unsupported", "certificate", "timeout", "envknob", "gomobile", "no such", "operation not permitted"} {
				if strings.Contains(message, hint) {
					code += "_" + strings.ToUpper(strings.ReplaceAll(hint, " ", "_"))
				}
			}
		}
		bytes, _ := json.Marshal(map[string]string{"error": "Tailscale 操作失败，请检查网络或重试", "code": code})
		return C.CString(string(bytes))
	}
	defer func() {
		if value := recover(); value != nil {
			result = diagnostic("TS_PANIC", fmt.Errorf("%v", value))
		}
	}()
	if !configured {
		return diagnostic("TS_NOT_CONFIGURED", nil)
	}
	proxy := tunnel.Proxies()[C.GoString(nodeText)]
	if proxy == nil {
		return diagnostic("TS_NODE_MISSING", nil)
	}
	bridge, ok := proxy.Adapter().(interface {
		OneTunnelAction(context.Context, string) (map[string]any, error)
	})
	if !ok {
		return diagnostic("TS_ADAPTER_UNSUPPORTED", nil)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	response, err := bridge.OneTunnelAction(ctx, C.GoString(actionText))
	if err != nil {
		return diagnostic("TS_ACTION", err)
	}
	bytes, err := json.Marshal(response)
	if err != nil || len(bytes) > 128*1024 {
		return diagnostic("TS_RESPONSE_INVALID", nil)
	}
	return C.CString(string(bytes))
}

// No core API, local proxy listener, packet forwarding, or TUN is started at load time.
func main() {}

func errorString(stage string, err error) *C.char {
	// Never return raw parser errors: upstream errors may include node credentials.
	if errors.Is(err, errRuleDatabase) {
		return C.CString("RULE_DATABASE_UNAVAILABLE")
	}
	if stage == "tunnel inbound" {
		return C.CString("TUNNEL_INBOUND_FAILED")
	}
	if stage == "Android TUN attachment" {
		message := strings.ToLower(err.Error())
		switch {
		case strings.Contains(message, "packages"):
			return C.CString("ANDROID_PACKAGE_ACCESS")
		case errors.Is(err, syscall.EPERM), errors.Is(err, syscall.EACCES):
			return C.CString("TUN_PERMISSION_DENIED")
		case errors.Is(err, syscall.EBADF):
			return C.CString("TUN_INVALID_FD")
		default:
			return C.CString("TUN_ATTACH_FAILED")
		}
	}
	return C.CString(stage + ": failed; check the profile and the first-version limitations. Raw errors are suppressed to protect credentials.")
}

//export GoStartCore
func GoStartCore(homeText, configText *C.char, androidFD C.int) (result *C.char) {
	coreMu.Lock()
	defer coreMu.Unlock()
	stage := "initialization"
	defer func() {
		if recover() != nil {
			stopLocked()
			result = errorString(stage, errors.New("native panic"))
		}
	}()
	if configured || tun != nil {
		return C.CString("Core already started; stop before restarting")
	}
	log.SetLevel(log.SILENT)
	home := C.GoString(homeText)
	text := C.GoString(configText)
	if len(text) == 0 || len(text) > 1024*1024 {
		return C.CString("Profile must be 1 byte to 1 MiB")
	}
	if strings.Contains(text, "REPLACE_") {
		return C.CString("Replace all REPLACE_ placeholders before starting")
	}
	if err := prepareHome(home); err != nil {
		return errorString("private state directory", err)
	}
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		protected := false
		if err := conn.Control(func(fd uintptr) { protected = C.protect_socket(C.int(fd)) != 0 }); err != nil {
			return err
		}
		if !protected {
			return errors.New("Android VpnService.protect refused socket; refusing unprotected connection")
		}
		return nil
	}
	startRouteTrace(home)
	cfg, err := parseCoreProfile(text)
	if err != nil {
		return errorString("profile", err)
	}
	stage = "Mihomo configuration"
	configured = true // Mark before applying so failure cleanup can close partially started outbounds.
	executor.ApplyConfig(cfg, true)
	stage = "Android TUN attachment"
	rotateClashSession()
	fd, err := syscall.Dup(int(androidFD))
	if err != nil {
		stopLocked()
		return errorString(stage, err)
	}
	options := LC.Tun{
		Enable:              true,
		Device:              "onetunnel",
		Stack:               MC.TunGvisor,
		DNSHijack:           []string{"0.0.0.0:53", "[::]:53"},
		AutoRoute:           false,
		AutoDetectInterface: false,
		Inet4Address:        []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		Inet6Address:        []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
		MTU:                 1280,
		FileDescriptor:      fd,
	}
	attached, err := sing_tun.New(options, tunnel.Tunnel)
	if err != nil {
		// A failed constructor may already have closed fd. Do not close it again:
		// an FD number may have been reused by another goroutine.
		stopLocked()
		return errorString(stage, err)
	}
	tun = attached
	stage = "tunnel inbound"
	if err := startTunnelInbounds(); err != nil {
		stopLocked()
		return errorString(stage, err)
	}
	return C.CString("")
}

func startTunnelInbounds() error {
	for _, proxy := range tunnel.Proxies() {
		if bridge, ok := proxy.Adapter().(interface{ OneTunnelStartInbound() error }); ok {
			if err := bridge.OneTunnelStartInbound(); err != nil {
				return err
			}
		}
	}
	return nil
}

func parseCoreProfile(text string) (*config.Config, error) {
	if len(text) == 0 || len(text) > 1024*1024 || strings.Contains(text, "REPLACE_") {
		return nil, errors.New("invalid profile")
	}
	raw, err := config.UnmarshalRawConfig([]byte(text))
	if err != nil {
		return nil, errors.New("profile parsing failed")
	}
	if !raw.DNS.Enable {
		return nil, errors.New("DNS required")
	}
	if len(raw.Rule) == 0 {
		return nil, errors.New("rules required")
	}
	// Android, not the profile, exclusively owns the TUN and incoming ports.
	raw.Port, raw.SocksPort, raw.RedirPort, raw.TProxyPort, raw.MixedPort = 0, 0, 0, 0, 0
	raw.ShadowSocksConfig, raw.VmessConfig = "", ""
	raw.AllowLan, raw.BindAddress = false, "127.0.0.1"
	// One explicit app listener: loopback by default, authenticated private LAN
	// access only when sharing is enabled. Imported listeners remain ignored.
	proxy, err := appsettings.ParseProxy(text)
	if err != nil {
		return nil, err
	}
	raw.Authentication, raw.SkipAuthPrefixes = nil, nil
	raw.LanAllowedIPs, raw.LanDisAllowedIPs = nil, nil
	if proxy.Enabled {
		raw.MixedPort, raw.AllowLan, raw.BindAddress = proxy.Port, proxy.AllowLAN, proxy.BindAddress
		raw.Authentication, raw.LanAllowedIPs = proxy.Authentication, proxy.AllowedIPs
	}
	raw.ExternalController, raw.ExternalControllerTLS, raw.ExternalControllerUnix, raw.ExternalControllerPipe = "", "", "", ""
	raw.ExternalUI, raw.ExternalUIURL, raw.ExternalDohServer = "", "", ""
	raw.Listeners, raw.Tunnels = nil, nil
	raw.Tun.Enable, raw.IPTables.Enable, raw.TuicServer.Enable = false, false, false
	raw.NTP.Enable, raw.NTP.WriteToSystem, raw.GeoAutoUpdate = false, false, false
	raw.LogLevel = log.SILENT
	raw.FindProcessMode = process.FindProcessOff
	raw.Mode = tunnel.Rule
	// App selections are persisted in encrypted YAML as default-selected.
	// Ignore Mihomo's separate unencrypted selector cache on every reload.
	raw.Profile.StoreSelected = false
	raw.DNS.Listen = ""
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		// Only classify the error. The original text may contain subscription
		// credentials and must never cross JNI or enter the Android log.
		message := strings.ToLower(err.Error())
		if strings.Contains(message, "geosite") || strings.Contains(message, "geoip") || strings.Contains(message, "mmdb") || strings.Contains(message, "geodata") {
			return nil, errRuleDatabase
		}
		return nil, errors.New("profile parsing failed")
	}
	return cfg, nil
}

//export GoReloadCore
func GoReloadCore(configText *C.char) (result *C.char) {
	coreMu.Lock()
	defer coreMu.Unlock()
	defer func() {
		if recover() != nil {
			stopLocked()
			result = C.CString("Configuration reload failed")
		}
	}()
	if !configured || tun == nil {
		return C.CString("VPN is not running")
	}
	cfg, err := parseCoreProfile(C.GoString(configText))
	if err != nil {
		return errorString("profile", err)
	}
	tunnel.OnSuspend()
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool { _ = c.Close(); return true })
	// Stop old tunnel identities before new tsnet/WG instances can use the same
	// state directory. A disabled connection must not survive in an old adapter.
	for _, provider := range tunnel.Providers() {
		if closer, ok := provider.(io.Closer); ok {
			_ = closer.Close()
		}
	}
	for _, proxy := range tunnel.Proxies() {
		_ = proxy.Close()
	}
	executor.ApplyConfig(cfg, true)
	rotateClashSession()
	if err := startTunnelInbounds(); err != nil {
		stopLocked()
		return errorString("tunnel inbound", err)
	}
	return C.CString("")
}

func stopLocked() {
	rotateClashSession()
	cancelClash()
	if stopRouteTrace != nil {
		stopRouteTrace()
		stopRouteTrace = nil
	}
	if tun != nil {
		_ = tun.Close()
		tun = nil
	}
	if configured {
		tunnel.OnSuspend()
		executor.Shutdown()
		for _, proxy := range tunnel.Proxies() {
			_ = proxy.Close()
		}
		configured = false
	}
}

//export GoStopCore
func GoStopCore() {
	coreMu.Lock()
	defer coreMu.Unlock()
	defer func() {
		if r := recover(); r != nil {
			_ = fmt.Sprint(r)
		}
	}()
	stopLocked()
}
