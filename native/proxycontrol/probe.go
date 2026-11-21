// SPDX-License-Identifier: GPL-3.0-or-later
package proxycontrol

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"net/url"
	"strconv"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"go.yaml.in/yaml/v3"
)

const MaxProbeTimeout = 30 * time.Second

type probeConfig struct {
	URL      string `yaml:"url"`
	Expected string `yaml:"expected-status"`
	Timeout  any    `yaml:"timeout"`
}

// Retain only probe settings. Do not serialize providers/adapters: they contain credentials.
type ProbePolicies struct {
	groups    map[string]probeConfig
	providers map[string]probeConfig
}

func ReadProbePolicies(text string) ProbePolicies {
	var profile struct {
		Groups []struct {
			Name        string `yaml:"name"`
			probeConfig `yaml:",inline"`
		} `yaml:"proxy-groups"`
		Providers map[string]struct {
			HealthCheck probeConfig `yaml:"health-check"`
		} `yaml:"proxy-providers"`
	}
	p := ProbePolicies{groups: make(map[string]probeConfig), providers: make(map[string]probeConfig)}
	if yaml.Unmarshal([]byte(text), &profile) != nil {
		return p
	}
	for _, g := range profile.Groups {
		p.groups[g.Name] = g.probeConfig
	}
	for name, provider := range profile.Providers {
		p.providers[name] = provider.HealthCheck
	}
	return p
}

type ProbeOptions struct {
	URL      string
	Expected utils.IntRanges[uint16]
	Timeout  time.Duration
}

func probeTimeout(value any) time.Duration {
	var ms int64
	switch n := value.(type) {
	case int:
		ms = int64(n)
	case int64:
		ms = n
	case string:
		ms, _ = strconv.ParseInt(n, 10, 64)
	}
	if ms <= 0 {
		return 5 * time.Second // Mihomo HealthCheck's default, also used by CMFA.
	}
	if ms > MaxProbeTimeout.Milliseconds() {
		return MaxProbeTimeout
	}
	return time.Duration(ms) * time.Millisecond
}

// CMFA invokes each provider's HealthCheck, not one group-wide default URL.
// Match those settings while keeping individual probes cancellable and bounded.
func (p ProbePolicies) Options(proxies map[string]C.Proxy, group, target string) ProbeOptions {
	return p.Resolver(proxies, group)(target)
}

// Build once per snapshot, avoiding repeated group JSON and provider scans per node.
func (p ProbePolicies) Resolver(proxies map[string]C.Proxy, group string) func(string) ProbeOptions {
	u, expected := TestOptions(proxies, group)
	options := ProbeOptions{u, expected, 5 * time.Second}
	g, ok := proxies[group]
	if !ok {
		return func(string) ProbeOptions { return options }
	}
	adapter, ok := g.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return func(string) ProbeOptions { return options }
	}
	groupConfig := p.groups[group]
	byTarget := make(map[string]probeConfig)
	for _, provider := range adapter.Providers() {
		chosen := groupConfig
		if provider.VehicleType() != P.Compatible {
			chosen = p.providers[provider.Name()]
			if chosen.URL == "" {
				chosen.URL = provider.HealthCheckURL()
			}
		}
		for _, member := range provider.Proxies() {
			if _, exists := byTarget[member.Name()]; !exists {
				byTarget[member.Name()] = chosen
			}
		}
	}
	return func(target string) ProbeOptions {
		chosen, ok := byTarget[target]
		if !ok {
			chosen = groupConfig
		}
		result := options
		result.Timeout = probeTimeout(chosen.Timeout)
		// An explicit group URL registers an extra task on its providers in Mihomo.
		if groupConfig.URL != "" {
			result.URL = groupConfig.URL
			result.Expected, _ = utils.NewUnsignedRanges[uint16](groupConfig.Expected)
			// RegisterHealthCheckTask keeps the provider's original status policy
			// when the group reuses that exact URL.
			if chosen.URL == groupConfig.URL {
				result.Expected, _ = utils.NewUnsignedRanges[uint16](chosen.Expected)
			}
		} else if chosen.URL != "" {
			result.URL = chosen.URL
			result.Expected, _ = utils.NewUnsignedRanges[uint16](chosen.Expected)
		}
		return result
	}
}

func (o ProbeOptions) Host() string {
	u, err := url.Parse(o.URL)
	if err != nil {
		return ""
	}
	return u.Hostname() // No URL credentials, path or query crosses JNI.
}

type ProbeResult struct {
	Status    string `json:"status"`
	Delay     uint16 `json:"delay"`
	Reason    string `json:"reason"`
	Host      string `json:"host"`
	TimeoutMS int64  `json:"timeoutMs"`
}

func Measure(parent context.Context, proxy C.Proxy, options ProbeOptions) ProbeResult {
	ctx, cancel := context.WithTimeout(parent, options.Timeout)
	defer cancel()
	delay, err := proxy.URLTest(ctx, options.URL, options.Expected)
	r := ProbeResult{Status: "ok", Delay: delay, Host: options.Host(), TimeoutMS: options.Timeout.Milliseconds()}
	if err == nil {
		// URLTest reports HTTP responses separately from its transport error.
		if options.Expected != nil && !proxy.AliveForTestUrl(options.URL) {
			r.Status, r.Reason = "failed", "status"
		}
		return r
	}
	r.Status, r.Reason = "failed", "network"
	var dns *net.DNSError
	var timeout net.Error
	var unknown x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	var record tls.RecordHeaderError
	switch {
	case errors.Is(ctx.Err(), context.Canceled):
		r.Status, r.Reason = "cancelled", "cancelled"
	case errors.Is(ctx.Err(), context.DeadlineExceeded), errors.Is(err, context.DeadlineExceeded):
		r.Status, r.Reason = "timeout", "timeout"
	case errors.As(err, &dns):
		r.Reason = "dns"
	case errors.As(err, &unknown), errors.As(err, &invalid), errors.As(err, &hostname), errors.As(err, &record):
		r.Reason = "tls"
	case errors.As(err, &timeout) && timeout.Timeout():
		r.Status, r.Reason = "timeout", "timeout"
	}
	return r
}
