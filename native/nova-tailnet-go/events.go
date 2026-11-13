// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"encoding/json"
	"time"
)

type event struct {
	Version  int    `json:"version"`
	Type     string `json:"type"`
	State    string `json:"state,omitempty"`
	LoginURL string `json:"loginUrl,omitempty"`
	Message  string `json:"message,omitempty"`
}

func (n *Node) emit(value event) {
	value.Version = apiVersion
	data, err := json.Marshal(value)
	if err != nil {
		return
	}

	n.eventMu.Lock()
	defer n.eventMu.Unlock()
	select {
	case n.events <- string(data):
		return
	default:
	}
	select {
	case <-n.events:
	default:
	}
	select {
	case n.events <- string(data):
	default:
	}
}

// NextEvent blocks for at most timeoutMillis and returns a versioned JSON
// event. An empty string means that no event arrived before the timeout.
func (n *Node) NextEvent(timeoutMillis int64) string {
	timeout := boundedTimeout(timeoutMillis, 60*time.Second)
	if timeout == 0 {
		select {
		case value := <-n.events:
			return value
		default:
			return ""
		}
	}

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case value := <-n.events:
		return value
	case <-timer.C:
		return ""
	}
}

func boundedTimeout(timeoutMillis int64, maximum time.Duration) time.Duration {
	if timeoutMillis <= 0 {
		return 0
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout > maximum {
		return maximum
	}
	return timeout
}
