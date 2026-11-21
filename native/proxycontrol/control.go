// SPDX-License-Identifier: GPL-3.0-or-later
package proxycontrol

import (
	"encoding/json"
	"errors"
	"sort"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
)

// Explicit DTOs: never serialize adapters, which may contain credentials.
type Group struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	Now   string `json:"now"`
	Count int    `json:"count"`
}
type Node struct {
	Name      string  `json:"name"`
	Type      string  `json:"type"`
	Delay     *uint16 `json:"delay"`
	TimeoutMS int64   `json:"timeoutMs"`
	ProbeHost string  `json:"probeHost"`
}
type State struct {
	Groups []Group `json:"groups"`
	Group  string  `json:"group"`
	Nodes  []Node  `json:"nodes"`
}

func Snapshot(proxies map[string]C.Proxy, name string) State {
	return SnapshotWithPolicies(proxies, name, ProbePolicies{})
}

func SnapshotWithPolicies(proxies map[string]C.Proxy, name string, policies ProbePolicies) State {
	s := State{Groups: []Group{}, Group: name, Nodes: []Node{}}
	for key, proxy := range proxies {
		if key == "GLOBAL" {
			continue
		}
		if group, ok := proxy.Adapter().(outboundgroup.ProxyGroup); ok {
			var members []C.Proxy
			seen := make(map[string]bool)
			for _, member := range group.Proxies() {
				if !seen[member.Name()] {
					seen[member.Name()] = true
					members = append(members, member)
				}
			}
			s.Groups = append(s.Groups, Group{key, proxy.Type().String(), group.Now(), len(members)})
			if key == name {
				optionsFor := policies.Resolver(proxies, key)
				for _, member := range members {
					options := optionsFor(member.Name())
					n := Node{Name: member.Name(), Type: member.Type().String(), TimeoutMS: options.Timeout.Milliseconds(), ProbeHost: options.Host()}
					if history := member.ExtraDelayHistories()[options.URL].History; len(history) > 0 {
						delay := history[len(history)-1].Delay
						n.Delay = &delay
					}
					s.Nodes = append(s.Nodes, n)
				}
			}
		}
	}
	sort.Slice(s.Groups, func(i, j int) bool { return s.Groups[i].Name < s.Groups[j].Name })
	return s
}

// Match the group's own health-check URL so automatic strategies see these
// measurements. Marshal only group metadata internally; never export its URL.
func TestOptions(proxies map[string]C.Proxy, name string) (string, utils.IntRanges[uint16]) {
	url := C.DefaultTestURL
	var expected utils.IntRanges[uint16]
	if p := proxies[name]; p != nil {
		if _, ok := p.Adapter().(outboundgroup.ProxyGroup); ok {
			var info struct {
				URL      string `json:"testUrl"`
				Expected string `json:"expectedStatus"`
			}
			if data, err := p.Adapter().MarshalJSON(); err == nil && json.Unmarshal(data, &info) == nil {
				if info.URL != "" {
					url = info.URL
				}
				expected, _ = utils.NewUnsignedRanges[uint16](info.Expected)
			}
		}
	}
	return url, expected
}

func Member(proxies map[string]C.Proxy, name, target string) (C.Proxy, error) {
	p := proxies[name]
	if p == nil {
		return nil, errors.New("missing group")
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return nil, errors.New("missing group")
	}
	for _, member := range g.Proxies() {
		if member.Name() == target {
			return member, nil
		}
	}
	return nil, errors.New("missing member")
}

func ValidateSelection(proxies map[string]C.Proxy, name, target string) error {
	p := proxies[name]
	if p == nil || p.Type() != C.Selector {
		return errors.New("manual groups only")
	}
	_, err := Member(proxies, name, target)
	return err
}
func Select(proxies map[string]C.Proxy, name, target string) error {
	if err := ValidateSelection(proxies, name, target); err != nil {
		return err
	}
	selector, ok := proxies[name].Adapter().(outboundgroup.SelectAble)
	if !ok {
		return errors.New("manual groups only")
	}
	return selector.Set(target)
}
