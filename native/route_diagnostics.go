// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"

	"dev.dsh/onetunnel/native/diagnostics"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var stopRouteTrace func()

// Opt-in debugging through a private, one-use marker. No listener or external
// control API is exposed. Collection automatically ends after five minutes.
func startRouteTrace(home string) {
	flag := filepath.Join(home, "route-trace-once")
	if _, err := os.Stat(flag); err != nil {
		return
	}
	_ = os.Remove(flag)
	if stopRouteTrace != nil {
		stopRouteTrace()
	}
	sub := log.Subscribe()
	var mu sync.Mutex
	events := make([]diagnostics.Route, 0, 256)
	collectorDone := make(chan struct{})
	go func() {
		defer close(collectorDone)
		for event := range sub {
			if route, ok := diagnostics.Parse(event.Payload); ok {
				mu.Lock()
				if len(events) == 256 {
					copy(events, events[1:])
					events = events[:255]
				}
				events = append(events, route)
				mu.Unlock()
			}
		}
	}()
	type connection struct {
		Network     string `json:"network"`
		Source      string `json:"source"`
		Destination string `json:"destination"`
		IP          string `json:"ip"`
		Rule        string `json:"rule"`
		Exit        string `json:"exit"`
		Upload      int64  `json:"upload"`
		Download    int64  `json:"download"`
	}
	write := func() {
		mu.Lock()
		copyEvents := append([]diagnostics.Route(nil), events...)
		mu.Unlock()
		active := make([]connection, 0, 32)
		statistic.DefaultManager.Range(func(tracker statistic.Tracker) bool {
			info := tracker.Info()
			m := info.Metadata
			active = append(active, connection{m.NetWork.String(), m.SourceAddress(), m.RemoteAddress(), m.DstIP.String(), info.Rule, info.Chain.String(), info.UploadTotal.Load(), info.DownloadTotal.Load()})
			return len(active) < 128
		})
		data, err := json.Marshal(struct {
			At     time.Time           `json:"at"`
			Routes []diagnostics.Route `json:"routes"`
			Active []connection        `json:"active"`
		}{time.Now().UTC(), copyEvents, active})
		if err != nil {
			return
		}
		path := filepath.Join(home, "route-trace.json")
		if os.WriteFile(path+".tmp", data, 0600) == nil {
			_ = os.Rename(path+".tmp", path)
		}
	}
	done, finished := make(chan struct{}), make(chan struct{})
	var once sync.Once
	stop := func() {
		once.Do(func() { log.UnSubscribe(sub); <-collectorDone; close(done) })
	}
	stopRouteTrace = func() { stop(); <-finished }
	go func() {
		defer close(finished)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		deadline := time.NewTimer(5 * time.Minute)
		defer deadline.Stop()
		for {
			select {
			case <-ticker.C:
				write()
			case <-deadline.C:
				stop()
				write()
				return
			case <-done:
				write()
				return
			}
		}
	}()
}
