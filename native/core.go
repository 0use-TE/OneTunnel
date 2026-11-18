// SPDX-License-Identifier: GPL-3.0-or-later
package main

/*
#include <stdlib.h>
#include "bridge.h"
*/
import "C"

import (
    "errors"
    "fmt"
    "io"
    "net/netip"
    "os"
    "path/filepath"
    "strings"
    "sync"
    "syscall"

    "github.com/metacubex/mihomo/component/dialer"
    "github.com/metacubex/mihomo/component/process"
    MC "github.com/metacubex/mihomo/constant"
    "github.com/metacubex/mihomo/config"
    "github.com/metacubex/mihomo/hub/executor"
    LC "github.com/metacubex/mihomo/listener/config"
    "github.com/metacubex/mihomo/listener/sing_tun"
    "github.com/metacubex/mihomo/log"
    "github.com/metacubex/mihomo/tunnel"
)

var coreMu sync.Mutex
var tun io.Closer
var configured bool

// No core API, local proxy listener, packet forwarding, or TUN is started at load time.
func main() {}

func errorString(stage string, err error) *C.char {
    // Never return raw parser errors: upstream errors may include node credentials.
    _ = err
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
    if configured || tun != nil { return C.CString("Core already started; stop before restarting") }
    log.SetLevel(log.SILENT)
    home := C.GoString(homeText)
    text := C.GoString(configText)
    if len(text) == 0 || len(text) > 1024*1024 { return C.CString("Profile must be 1 byte to 1 MiB") }
    if strings.Contains(text, "REPLACE_") { return C.CString("Replace all REPLACE_ placeholders before starting") }
    if err := os.MkdirAll(home, 0700); err != nil { return errorString("private state directory", err) }
    // Termux Go has host-private default temp/certificate paths. Override them only
    // inside this dedicated VPN process so the APK is independent of Termux.
    for env, value := range map[string]string{
        "HOME": home,
        "TMPDIR": filepath.Join(home, "tmp"),
        "XDG_CACHE_HOME": filepath.Join(home, "cache"),
        "XDG_CONFIG_HOME": filepath.Join(home, "config"),
    } {
        if err := os.MkdirAll(value, 0700); err != nil { return errorString("private runtime directory", err) }
        if err := os.Setenv(env, value); err != nil { return errorString("private runtime environment", err) }
    }
    _ = os.Setenv("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")
    MC.SetHomeDir(home)
    MC.Version = "onetunnel-meta-0455d0d"
    dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
        protected := false
        if err := conn.Control(func(fd uintptr) { protected = C.protect_socket(C.int(fd)) != 0 }); err != nil { return err }
        if !protected { return errors.New("Android VpnService.protect refused socket; refusing unprotected connection") }
        return nil
    }
    stage = "YAML decoding"
    raw, err := config.UnmarshalRawConfig([]byte(text))
    if err != nil { return errorString(stage, err) }
    if !raw.DNS.Enable { return C.CString("This version requires dns.enable: true") }
    if len(raw.Rule) == 0 { return C.CString("Add explicit rules and a final MATCH rule; no automatic direct fallback is added") }
    // Android, not the profile, exclusively owns the TUN and incoming ports.
    raw.Port, raw.SocksPort, raw.RedirPort, raw.TProxyPort, raw.MixedPort = 0, 0, 0, 0, 0
    raw.ShadowSocksConfig, raw.VmessConfig = "", ""
    raw.AllowLan, raw.BindAddress = false, "127.0.0.1"
    raw.ExternalController, raw.ExternalControllerTLS, raw.ExternalControllerUnix, raw.ExternalControllerPipe = "", "", "", ""
    raw.ExternalUI, raw.ExternalUIURL, raw.ExternalDohServer = "", "", ""
    raw.Listeners, raw.Tunnels = nil, nil
    raw.Tun.Enable, raw.IPTables.Enable, raw.TuicServer.Enable = false, false, false
    raw.NTP.Enable, raw.NTP.WriteToSystem, raw.GeoAutoUpdate = false, false, false
    raw.LogLevel = log.SILENT
    raw.FindProcessMode = process.FindProcessOff
    raw.Mode = tunnel.Rule
    raw.DNS.Listen = ""
    stage = "Mihomo profile parsing"
    cfg, err := config.ParseRawConfig(raw)
    if err != nil { return errorString(stage, err) }
    stage = "Mihomo configuration"
    configured = true // Mark before applying so failure cleanup can close partially started outbounds.
    executor.ApplyConfig(cfg, true)
    stage = "Android TUN attachment"
    fd, err := syscall.Dup(int(androidFD))
    if err != nil { stopLocked(); return errorString(stage, err) }
    options := LC.Tun{
        Enable: true,
        Device: "onetunnel",
        Stack: MC.TunGvisor,
        DNSHijack: []string{"0.0.0.0:53", "[::]:53"},
        AutoRoute: false,
        AutoDetectInterface: false,
        Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
        Inet6Address: []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
        MTU: 1280,
        FileDescriptor: fd,
    }
    attached, err := sing_tun.New(options, tunnel.Tunnel)
    if err != nil {
        // A failed constructor may already have closed fd. Do not close it again:
        // an FD number may have been reused by another goroutine.
        stopLocked()
        return errorString(stage, err)
    }
    tun = attached
    return C.CString("")
}

func stopLocked() {
    if tun != nil { _ = tun.Close(); tun = nil }
    if configured {
        tunnel.OnSuspend()
        executor.Shutdown()
        for _, proxy := range tunnel.Proxies() { _ = proxy.Close() }
        configured = false
    }
}

//export GoStopCore
func GoStopCore() {
    coreMu.Lock()
    defer coreMu.Unlock()
    defer func() { if r := recover(); r != nil { _ = fmt.Sprint(r) } }()
    stopLocked()
}
