// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"context"
	"encoding/json"
	"errors"
	"net/netip"
	"strings"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

const defaultPingTimeout = 2 * time.Second

type pingDocument struct {
	Version           int      `json:"version"`
	IP                string   `json:"ip"`
	LatencyMillis     *float64 `json:"latencyMillis,omitempty"`
	Failure           string   `json:"failure,omitempty"`
	Path              string   `json:"path,omitempty"`
	DirectEndpoint    string   `json:"directEndpoint,omitempty"`
	RelayRegion       string   `json:"relayRegion,omitempty"`
	PeerRelayEndpoint string   `json:"peerRelayEndpoint,omitempty"`
}

// PingJSON performs one Tailscale disco ping using the shared tsnet runtime.
// requestID gives Kotlin a narrow cancellation handle while the generated
// gomobile call is blocked.
func (n *Node) PingJSON(requestID, address string, timeoutMillis int64) (string, error) {
	requestID = strings.TrimSpace(requestID)
	if requestID == "" {
		return "", errors.New("ping request ID must not be empty")
	}
	ip, err := netip.ParseAddr(strings.TrimSpace(address))
	if err != nil {
		return "", errors.New("ping destination must be an IP address")
	}

	timeout := boundedTimeout(timeoutMillis, 30*time.Second)
	if timeout == 0 {
		timeout = defaultPingTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)

	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		cancel()
		return "", errors.New("node is closed")
	}
	if !n.started || n.localClient == nil {
		n.mu.Unlock()
		cancel()
		return "", errors.New("tailnet is not started")
	}
	if _, exists := n.pings[requestID]; exists {
		n.mu.Unlock()
		cancel()
		return "", errors.New("ping request ID is already active")
	}
	lc := n.localClient
	n.pings[requestID] = cancel
	n.mu.Unlock()

	defer func() {
		cancel()
		n.mu.Lock()
		delete(n.pings, requestID)
		n.mu.Unlock()
	}()

	result, err := lc.Ping(ctx, ip, tailcfg.PingDisco)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
			return marshalPingDocument(pingDocument{
				Version: apiVersion,
				IP:      ip.String(),
				Failure: "timeout",
			})
		}
		return "", errors.New("tailnet ping unavailable")
	}
	return marshalPingDocument(pingDocumentFromResult(ip.String(), result))
}

// CancelPing interrupts an active PingJSON call. It is safe to call more than
// once or after the request has already completed.
func (n *Node) CancelPing(requestID string) {
	n.mu.Lock()
	cancel := n.pings[strings.TrimSpace(requestID)]
	delete(n.pings, strings.TrimSpace(requestID))
	n.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func pingDocumentFromResult(fallbackIP string, result *ipnstate.PingResult) pingDocument {
	document := pingDocument{
		Version: apiVersion,
		IP:      fallbackIP,
	}
	if result == nil {
		document.Failure = "unreachable"
		return document
	}
	if result.IP != "" {
		document.IP = result.IP
	}
	switch {
	case result.Endpoint != "":
		document.Path = "direct"
		document.DirectEndpoint = result.Endpoint
	case result.PeerRelay != "":
		document.Path = "peer-relay"
		document.PeerRelayEndpoint = result.PeerRelay
	case result.DERPRegionID != 0 || result.DERPRegionCode != "":
		document.Path = "derp"
		document.RelayRegion = strings.ToUpper(strings.TrimSpace(result.DERPRegionCode))
	}
	if strings.TrimSpace(result.Err) != "" {
		document.Failure = classifyPingFailure(result.Err)
		return document
	}
	latencyMillis := result.LatencySeconds * 1000
	document.LatencyMillis = &latencyMillis
	return document
}

func classifyPingFailure(message string) string {
	normalized := strings.ToLower(message)
	if strings.Contains(normalized, "timeout") ||
		strings.Contains(normalized, "timed out") ||
		strings.Contains(normalized, "deadline") {
		return "timeout"
	}
	return "unreachable"
}

func marshalPingDocument(document pingDocument) (string, error) {
	data, err := json.Marshal(document)
	if err != nil {
		return "", errors.New("encode ping result")
	}
	return string(data), nil
}
