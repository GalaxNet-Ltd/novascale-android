// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package novatailnet

import (
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"testing"
)

func TestWatcherOptionsAndPeerRemoval(t *testing.T) {
	if err := ipn.ValidateNotifyWatchOpt(nodeWatchOptions); err != nil {
		t.Fatal(err)
	}
	if !peerSnapshotChanged(ipn.Notify{PeersRemoved: []tailcfg.NodeID{42}}) {
		t.Fatal("peer removal would leave stale discovery")
	}
	if !peerSnapshotChanged(ipn.Notify{PeersChanged: []*tailcfg.Node{{}}}) {
		t.Fatal("peer add/replacement ignored")
	}
	if !peerSnapshotChanged(ipn.Notify{PeerChangedPatch: []*tailcfg.PeerChange{{}}}) {
		t.Fatal("online change ignored")
	}
	if peerSnapshotChanged(ipn.Notify{}) {
		t.Fatal("empty notification refreshes peers")
	}
}
