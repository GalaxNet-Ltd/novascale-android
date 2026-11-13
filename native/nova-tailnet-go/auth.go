// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package novatailnet

import (
	"context"
	"errors"
	"strings"
	"tailscale.com/ipn"
	"time"
)

// BeginAuthKeyLogin submits a key to the existing local backend. The key is
// never copied into node configuration, persistent preferences, or events.
// IPN state events report connection/approval; this call does not mean connected.
func (n *Node) BeginAuthKeyLogin(key string) error {
	key = strings.TrimSpace(key)
	if key == "" {
		return errors.New("authentication key is required")
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed || !n.started || n.localClient == nil {
		return errors.New("tailnet not started")
	}
	if n.authCancel != nil {
		n.authCancel()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	n.authCancel = cancel
	n.authGeneration++
	generation := n.authGeneration
	lc := n.localClient
	go func() {
		defer cancel()
		err := lc.Start(ctx, ipn.Options{AuthKey: key})
		if err == nil {
			// Start installs the key on the control client, but a new/logged-out
			// node does not automatically log in. Match tsnet/CLI's follow-up.
			// Despite its name, this consumes the auth key without opening UI.
			err = lc.StartLoginInteractive(ctx)
		}
		n.mu.Lock()
		defer n.mu.Unlock()
		if generation != n.authGeneration || n.closed {
			return
		}
		n.authCancel = nil
		if err != nil {
			// LocalAPI/control server errors may echo submitted credentials.
			n.emit(event{Type: "state", State: "failed", Message: "Authentication failed. Check the key and connection."})
		}
	}()
	return nil
}

// CancelAuthKeyLogin stops the pending local request, not an already-enrolled node.
func (n *Node) CancelAuthKeyLogin() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.authGeneration++
	if n.authCancel != nil {
		n.authCancel()
		n.authCancel = nil
	}
}
