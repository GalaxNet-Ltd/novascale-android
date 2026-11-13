// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"time"

	"tailscale.com/ipn/ipnstate"
)

type statusDocument struct {
	Version        int            `json:"version"`
	BackendState   string         `json:"backendState"`
	AuthURL        string         `json:"authUrl,omitempty"`
	TailnetName    string         `json:"tailnetName,omitempty"`
	MagicDNSSuffix string         `json:"magicDnsSuffix,omitempty"`
	Self           *peerDocument  `json:"self,omitempty"`
	Peers          []peerDocument `json:"peers"`
}

type peerDocument struct {
	StableNodeID string   `json:"stableNodeId"`
	DisplayName  string   `json:"displayName"`
	LoginName    string   `json:"loginName,omitempty"`
	DNSName      string   `json:"dnsName,omitempty"`
	Addresses    []string `json:"addresses"`
	OS           string   `json:"os,omitempty"`
	Online       bool     `json:"online"`
	Active       bool     `json:"active"`
	SSHAvailable bool     `json:"sshAvailable"`
}

// StatusJSON returns a stable, redacted subset of Tailscale status. Generated
// Tailscale types never cross the gomobile boundary.
func (n *Node) StatusJSON(timeoutMillis int64) (string, error) {
	n.mu.Lock()
	lc := n.localClient
	started := n.started
	closed := n.closed
	n.mu.Unlock()
	if closed {
		return "", errors.New("node is closed")
	}
	if !started || lc == nil {
		data, _ := json.Marshal(statusDocument{Version: apiVersion, BackendState: "Stopped", Peers: []peerDocument{}})
		return string(data), nil
	}

	timeout := boundedTimeout(timeoutMillis, 30*time.Second)
	if timeout == 0 {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		return "", errors.New("tailnet status unavailable")
	}

	document := statusDocument{
		Version:      apiVersion,
		BackendState: status.BackendState,
		AuthURL:      status.AuthURL,
		Peers:        make([]peerDocument, 0, len(status.Peer)),
	}
	if status.CurrentTailnet != nil {
		document.TailnetName = status.CurrentTailnet.Name
		document.MagicDNSSuffix = status.CurrentTailnet.MagicDNSSuffix
	}
	if status.Self != nil {
		self := selfFromStatus(status)
		document.Self = &self
	}
	for _, key := range status.Peers() {
		document.Peers = append(document.Peers, peerFromStatus(status.Peer[key]))
	}
	sort.Slice(document.Peers, func(i, j int) bool {
		return strings.ToLower(document.Peers[i].DisplayName) < strings.ToLower(document.Peers[j].DisplayName)
	})
	data, err := json.Marshal(document)
	if err != nil {
		return "", errors.New("encode tailnet status")
	}
	return string(data), nil
}

func peerFromStatus(peer *ipnstate.PeerStatus) peerDocument {
	addresses := make([]string, 0, len(peer.TailscaleIPs))
	for _, address := range peer.TailscaleIPs {
		addresses = append(addresses, address.String())
	}
	displayName := peer.HostName
	if displayName == "" {
		displayName = strings.TrimSuffix(peer.DNSName, ".")
	}
	return peerDocument{
		StableNodeID: string(peer.ID),
		DisplayName:  displayName,
		DNSName:      strings.TrimSuffix(peer.DNSName, "."),
		Addresses:    addresses,
		OS:           peer.OS,
		Online:       peer.Online,
		Active:       peer.Active,
		SSHAvailable: len(peer.SSH_HostKeys) > 0,
	}
}

// Only expose the local node's account identity, not the tailnet user directory.
// Tagged/auth-key nodes may have no user profile; leave the field absent then.
func selfFromStatus(status *ipnstate.Status) peerDocument {
	self := peerFromStatus(status.Self)
	if user, ok := status.User[status.Self.UserID]; ok {
		self.LoginName = user.LoginName
	}
	return self
}
