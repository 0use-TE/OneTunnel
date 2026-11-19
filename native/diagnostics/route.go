// SPDX-License-Identifier: GPL-3.0-or-later
package diagnostics

import (
	"net"
	"strings"
	"time"
)

// Route excludes raw error messages, URL paths, payloads, and configuration.
type Route struct {
	At          time.Time `json:"at"`
	Network     string    `json:"network"`
	Source      string    `json:"source"`
	Destination string    `json:"destination"`
	Rule        string    `json:"rule,omitempty"`
	Exit        string    `json:"exit"`
	Error       string    `json:"error,omitempty"`
}

func endpoint(s string) bool {
	if len(s) > 280 || strings.ContainsAny(s, " /\\@?\r\n\t") {
		return false
	}
	_, _, err := net.SplitHostPort(s)
	return err == nil
}

// Parse only accepts the core's connection routing messages. Other core logs
// can contain authentication URLs and must never be written to this trace.
func Parse(message string) (r Route, ok bool) {
	if strings.HasPrefix(message, "[TCP] ") {
		r.Network = "tcp"
	} else if strings.HasPrefix(message, "[UDP] ") {
		r.Network = "udp"
	} else {
		return r, false
	}
	left, right, found := strings.Cut(message[6:], " --> ")
	if !found {
		return r, false
	}
	if strings.HasPrefix(left, "dial ") {
		route, source, found := strings.Cut(left[5:], ") ")
		if found {
			r.Exit, r.Rule, found = strings.Cut(route, " (match ")
			if !found {
				return r, false
			}
			r.Source = source
		} else {
			cut := strings.LastIndexByte(left[5:], ' ')
			if cut < 0 {
				return r, false
			}
			r.Exit, r.Source = left[5:5+cut], left[6+cut:]
		}
		var rawError string
		r.Destination, rawError, found = strings.Cut(right, " error: ")
		if !found {
			return r, false
		}
		r.Error = "connect_failed"
		lower := strings.ToLower(rawError)
		for _, category := range []string{"timeout", "refused", "unreachable", "certificate", "resolve", "protect", "reset", "eof"} {
			if strings.Contains(lower, category) {
				r.Error = category
				break
			}
		}
	} else {
		r.Source = left
		before, exit, found := strings.Cut(right, " using ")
		if !found {
			return r, false
		}
		r.Exit = exit
		r.Destination, r.Rule, _ = strings.Cut(before, " match ")
	}
	// With process finding disabled the source is an endpoint. Strip optional
	// process labels if a future core version supplies them.
	r.Source, _, _ = strings.Cut(r.Source, "(")
	if !endpoint(r.Source) || !endpoint(r.Destination) || len(r.Exit) > 512 || len(r.Rule) > 512 {
		return Route{}, false
	}
	r.At = time.Now().UTC()
	return r, true
}
