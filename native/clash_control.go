// SPDX-License-Identifier: GPL-3.0-or-later
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"strconv"

	"dev.dsh/onetunnel/native/proxycontrol"
	"github.com/metacubex/mihomo/tunnel"
)

var clashGeneration uint64
var clashContext, cancelClash = context.WithCancel(context.Background())
var clashProbes = make(chan struct{}, 4)
var clashProbePolicies proxycontrol.ProbePolicies

func rotateClashSession() {
	cancelClash()
	clashGeneration++
	clashContext, cancelClash = context.WithCancel(context.Background())
}
func clashJSON(value any) *C.char {
	data, err := json.Marshal(value)
	if err != nil || len(data) > 256*1024 {
		return C.CString(`{"error":"代理列表过大，请缩小代理组"}`)
	}
	return C.CString(string(data))
}
func clashError(message string) *C.char { return clashJSON(map[string]string{"error": message}) }

//export GoClashAction
func GoClashAction(actionText, groupText, targetText, generationText *C.char) (result *C.char) {
	// Delay tests hold no core lock during I/O. Reload/stop cancels their session.
	action, group, target, expected := C.GoString(actionText), C.GoString(groupText), C.GoString(targetText), C.GoString(generationText)
	var locked bool
	defer func() {
		if locked {
			coreMu.Unlock()
		}
		if recover() != nil {
			result = clashError("代理操作失败")
		}
	}()
	coreMu.Lock()
	locked = true
	unlock := func() { locked = false; coreMu.Unlock() }
	if !configured || tun == nil {
		unlock()
		return clashError("请先开启 OneTunnel")
	}
	generation := strconv.FormatUint(clashGeneration, 10)
	if action != "state" && expected != generation {
		unlock()
		return clashError("配置已变化，请刷新代理页")
	}
	proxies := tunnel.Proxies()
	switch action {
	case "cancel":
		cancelClash()
		clashContext, cancelClash = context.WithCancel(context.Background())
		unlock()
		return clashJSON(map[string]bool{"cancelled": true})
	case "state":
		state := proxycontrol.SnapshotWithPolicies(proxies, group, clashProbePolicies)
		unlock()
		return clashJSON(map[string]any{"generation": generation, "groups": state.Groups, "group": state.Group, "nodes": state.Nodes})
	case "validate", "select":
		err := proxycontrol.ValidateSelection(proxies, group, target)
		if err == nil && action == "select" {
			err = proxycontrol.Select(proxies, group, target)
		}
		unlock()
		if err != nil {
			return clashError("只能选择手动代理组中的有效节点")
		}
		return clashJSON(map[string]any{"generation": generation, "selected": target, "group": group})
	case "delay":
		proxy, err := proxycontrol.Member(proxies, group, target)
		if err != nil {
			unlock()
			return clashError("节点已变化，请刷新代理页")
		}
		select {
		case clashProbes <- struct{}{}:
		default:
			unlock()
			return clashError("测速繁忙，请稍后重试")
		}
		ctx := clashContext
		options := clashProbePolicies.Options(proxies, group, target)
		unlock()
		defer func() { <-clashProbes }()
		measurement := proxycontrol.Measure(ctx, proxy, options)
		coreMu.Lock()
		locked = true
		stale := generation != strconv.FormatUint(clashGeneration, 10) || tun == nil
		unlock()
		if stale {
			return clashError("配置已变化，请重新测速")
		}
		return clashJSON(map[string]any{"generation": generation, "name": target, "group": group,
			"delay": measurement.Delay, "status": measurement.Status, "reason": measurement.Reason,
			"host": measurement.Host, "timeoutMs": measurement.TimeoutMS})
	default:
		unlock()
		return clashError("不支持的代理操作")
	}
}
