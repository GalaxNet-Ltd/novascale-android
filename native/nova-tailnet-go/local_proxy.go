// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package novatailnet

import (
	"cc.galaxnet.novascale/native/novatailnet/internal/localproxy"
	"encoding/json"
	"errors"
)

// StartLocalProxy replaces the application's authenticated shared proxy. The
// returned document contains no credentials and reports the actual bound port.
func (n *Node) StartLocalProxy(port int, username, password, realm string) (string, error) {
	if err := n.Start(); err != nil {
		return "", err
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		return "", errors.New("node closed")
	}
	if n.localProxy != nil {
		n.localProxy.Close()
		n.localProxy = nil
	}
	id, err := randomConnectionID()
	if err != nil {
		return "", err
	}
	proxy, err := localproxy.Start(localproxy.Config{Port: port, Username: username, Password: password, Realm: realm}, n.server.Dial)
	if err != nil {
		return "", err
	}
	n.localProxy = proxy
	n.localProxyID = id
	out, _ := json.Marshal(struct {
		ID       string `json:"id"`
		Address  string `json:"address"`
		Fallback bool   `json:"fallback"`
	}{id, proxy.Address(), proxy.Fallback})
	return string(out), nil
}
func (n *Node) StopLocalProxy(id string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.localProxy != nil && (id == "" || n.localProxyID == id) {
		n.localProxy.Close()
		n.localProxy = nil
		n.localProxyID = ""
	}
}
