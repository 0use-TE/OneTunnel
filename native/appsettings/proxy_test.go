// SPDX-License-Identifier: GPL-3.0-or-later
package appsettings

import (
	"fmt"
	"net/netip"
	"testing"
)

func TestImportedListenerFieldsCannotEnableAppProxy(t *testing.T) {
	proxy, err := ParseProxy("port: 1234\nmixed-port: 2345\nallow-lan: true\nbind-address: '*'\nauthentication: [user:password]\n")
	if err != nil || proxy.Enabled || proxy.AllowLAN || len(proxy.Authentication) != 0 {
		t.Fatal("imported listeners must remain disabled")
	}
}

func TestLocalHTTPUsesConfiguredPortWithoutLANOrAuthentication(t *testing.T) {
	proxy, err := ParseProxy("x-onetunnel: {http-enabled: true, lan-port: 8888}")
	if err != nil || !proxy.Enabled || proxy.Port != 8888 || proxy.BindAddress != "127.0.0.1" || proxy.AllowLAN || len(proxy.Authentication) != 0 {
		t.Fatal("local HTTP listener must use the selected loopback port")
	}
}

func TestLegacyLANRemainsAuthenticatedAndPrivate(t *testing.T) {
	proxy, err := ParseProxy("x-onetunnel: {lan-enabled: true, lan-port: 7891, lan-user: user, lan-password: a-strong-password}")
	if err != nil || !proxy.Enabled || !proxy.AllowLAN || proxy.Port != 7891 || proxy.BindAddress != "*" || len(proxy.Authentication) != 1 {
		t.Fatal("existing LAN settings must survive migration")
	}
	allowed := func(address string) bool {
		for _, prefix := range proxy.AllowedIPs {
			if prefix.Contains(netip.MustParseAddr(address)) {
				return true
			}
		}
		return false
	}
	if !allowed("192.168.1.5") || !allowed("fd00::1") || allowed("8.8.8.8") || allowed("2001:4860:4860::8888") {
		t.Fatal("LAN sharing must remain limited to private or local addresses")
	}
}

func TestExplicitOffOverridesRememberedLANSharing(t *testing.T) {
	proxy, err := ParseProxy("x-onetunnel: {http-enabled: false, lan-enabled: true, lan-port: 7890}")
	if err != nil || proxy.Enabled || proxy.AllowLAN || len(proxy.Authentication) != 0 {
		t.Fatal("HTTP off must close sharing too")
	}
}

func TestTurningOffSharingKeepsLocalHTTP(t *testing.T) {
	proxy, err := ParseProxy("x-onetunnel: {http-enabled: true, lan-enabled: false, lan-port: 7892}")
	if err != nil || !proxy.Enabled || proxy.AllowLAN || proxy.BindAddress != "127.0.0.1" {
		t.Fatal("disabling sharing must retain only local HTTP")
	}
}

func TestPortBoundsAndDefault(t *testing.T) {
	for _, port := range []int{1024, 7890, 65535} {
		proxy, err := ParseProxy(fmt.Sprintf("x-onetunnel: {http-enabled: true, lan-port: %d}", port))
		if err != nil || proxy.Port != port {
			t.Fatalf("valid port %d rejected", port)
		}
	}
	for _, port := range []int{-1, 0, 80, 1023, 65536} {
		if _, err := ParseProxy(fmt.Sprintf("x-onetunnel: {http-enabled: true, lan-port: %d}", port)); err == nil {
			t.Fatalf("invalid port %d accepted", port)
		}
	}
	proxy, err := ParseProxy("x-onetunnel: {http-enabled: true}")
	if err != nil || proxy.Port != 7890 {
		t.Fatal("default port must be 7890")
	}
}

func TestSharingRequiresValidAuthentication(t *testing.T) {
	for _, auth := range []string{"", "lan-user: user, lan-password: short", "lan-user: 'bad:user', lan-password: a-strong-password", "lan-user: user, lan-password: 'password:invalid'"} {
		if _, err := ParseProxy("x-onetunnel: {http-enabled: true, lan-enabled: true, " + auth + "}"); err == nil {
			t.Fatal("invalid shared authentication accepted")
		}
	}
}

func TestAnonymousLANRequiresExplicitOptOutAndRetainsPrivateAddresses(t *testing.T) {
	proxy, err := ParseProxy("x-onetunnel: {http-enabled: true, lan-enabled: true, lan-auth-enabled: false, lan-port: 8888}")
	if err != nil || !proxy.Enabled || !proxy.AllowLAN || proxy.BindAddress != "*" || len(proxy.Authentication) != 0 || len(proxy.AllowedIPs) == 0 {
		t.Fatal("anonymous LAN must omit authentication and keep private source restrictions")
	}
	for _, prefix := range proxy.AllowedIPs {
		if prefix.Contains(netip.MustParseAddr("8.8.8.8")) {
			t.Fatal("anonymous mode must not allow public source addresses")
		}
	}
	if _, err := ParseProxy("x-onetunnel: {http-enabled: true, lan-enabled: true}"); err == nil {
		t.Fatal("omitting auth preference must still require credentials")
	}
}
