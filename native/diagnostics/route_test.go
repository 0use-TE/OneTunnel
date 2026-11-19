// SPDX-License-Identifier: GPL-3.0-or-later
package diagnostics

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestRouteParsing(t *testing.T) {
	for _, tc := range []struct{ message, rule, exit, failure string }{
		{"[TCP] 172.19.0.1:1234 --> 1.2.3.4:443 match GeoIP(CN) using DIRECT", "GeoIP(CN)", "DIRECT", ""},
		{"[UDP] [fdfe::1]:1234 --> example.com:443 match Match using group[node]", "Match", "group[node]", ""},
		{"[TCP] dial DIRECT (match GeoIP/CN) 172.19.0.1:1234 --> 1.2.3.4:443 error: i/o timeout https://secret.example/token", "GeoIP/CN", "DIRECT", "timeout"},
		{"[TCP] dial My proxy 172.19.0.1:1234 --> example.com:443 error: connection refused", "", "My proxy", "refused"},
	} {
		r, ok := Parse(tc.message)
		if !ok || r.Rule != tc.rule || r.Exit != tc.exit || r.Error != tc.failure {
			t.Fatalf("unexpected route: %+v accepted=%v", r, ok)
		}
		data, _ := json.Marshal(r)
		if strings.Contains(string(data), "secret") || strings.Contains(string(data), "token") {
			t.Fatal("raw error leaked")
		}
	}
}

func TestRejectNonRouteAndSensitiveTargets(t *testing.T) {
	for _, message := range []string{
		"Auth URL: https://login.tailscale.com/a/secret",
		"[TCP] request https://user:password@example.com/file",
		"[TCP] 172.19.0.1:12 --> https://example.com/token:443 match Match using DIRECT",
		"[DNS] example.com query failed: secret",
	} {
		if _, ok := Parse(message); ok {
			t.Fatal("accepted non-route or URL")
		}
	}
}
