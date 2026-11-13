// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package novatailnet

import (
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"testing"
)

func TestSelfIdentityUsesLocalNodeUser(t *testing.T) {
	status := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{HostName: "dev-phone", UserID: 7},
		User: map[tailcfg.UserID]tailcfg.UserProfile{
			7: {LoginName: "owner@example.invalid"},
			8: {LoginName: "other@example.invalid"},
		},
	}
	self := selfFromStatus(status)
	if self.DisplayName != "dev-phone" || self.LoginName != "owner@example.invalid" {
		t.Fatal("local identity did not use the self node user")
	}
	status.Self.UserID = 0
	if selfFromStatus(status).LoginName != "" {
		t.Fatal("node without profile inherited another user")
	}
}
