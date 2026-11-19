// SPDX-License-Identifier: GPL-3.0-or-later
package appsettings

import (
	"errors"
	"net/netip"

	"go.yaml.in/yaml/v3"
)

type Proxy struct {
	Enabled        bool
	Port           int
	AllowLAN       bool
	BindAddress    string
	Authentication []string
	AllowedIPs     []netip.Prefix
}

// Only explicit app settings may open a listener. Imported Clash ports never do.
func ParseProxy(text string) (Proxy, error) {
	var app struct {
		Settings struct {
			HTTPEnabled *bool  `yaml:"http-enabled"`
			LANEnabled  bool   `yaml:"lan-enabled"`
			AuthEnabled *bool  `yaml:"lan-auth-enabled"`
			Port        *int   `yaml:"lan-port"`
			User        string `yaml:"lan-user"`
			Password    string `yaml:"lan-password"`
		} `yaml:"x-onetunnel"`
	}
	invalid := errors.New("proxy settings invalid")
	if err := yaml.Unmarshal([]byte(text), &app); err != nil {
		return Proxy{}, invalid
	}
	settings := app.Settings
	proxy := Proxy{Enabled: settings.LANEnabled, Port: 7890, BindAddress: "127.0.0.1"}
	if settings.HTTPEnabled != nil {
		proxy.Enabled = *settings.HTTPEnabled
	}
	if settings.Port != nil {
		proxy.Port = *settings.Port
	}
	if proxy.Port < 1024 || proxy.Port > 65535 {
		return Proxy{}, invalid
	}
	if !proxy.Enabled || !settings.LANEnabled {
		return proxy, nil
	}
	if settings.AuthEnabled == nil || *settings.AuthEnabled {
		validUser := len(settings.User) >= 1 && len(settings.User) <= 32
		for _, c := range settings.User {
			if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
				validUser = false
			}
		}
		validPassword := len(settings.Password) >= 12 && len(settings.Password) <= 128
		for _, c := range settings.Password {
			if c < '!' || c > '~' || c == ':' {
				validPassword = false
			}
		}
		if !validUser || !validPassword {
			return Proxy{}, invalid
		}
		proxy.Authentication = []string{settings.User + ":" + settings.Password}
	}
	proxy.AllowLAN, proxy.BindAddress = true, "*"
	for _, prefix := range []string{"127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7", "fe80::/10"} {
		proxy.AllowedIPs = append(proxy.AllowedIPs, netip.MustParsePrefix(prefix))
	}
	return proxy, nil
}
