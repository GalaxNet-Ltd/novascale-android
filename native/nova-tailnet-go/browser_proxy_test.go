// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"net"
	"net/url"
	"testing"
)

func TestBrowserProxyPolicyIsExactHostAndPort(t *testing.T) {
	allowed, err := parseBrowserProxyPolicy(`{"version":1,"origins":[{"host":"Server.Tailnet.TS.NET.","port":443}]}`)
	if err != nil {
		t.Fatal(err)
	}
	proxy := &browserProxy{allowed: allowed}
	if !proxy.allowsAddress("server.tailnet.ts.net:443") {
		t.Fatal("expected normalized exact origin to be allowed")
	}
	if !proxy.allowsURL(&url.URL{Scheme: "https", Host: "server.tailnet.ts.net"}) {
		t.Fatal("expected default HTTPS port to be allowed")
	}
	for _, address := range []string{
		"server.tailnet.ts.net:80",
		"other.tailnet.ts.net:443",
		"server.tailnet.ts.net.evil.example:443",
		"127.0.0.1:443",
	} {
		if proxy.allowsAddress(address) {
			t.Fatalf("unexpected allowed address %q", address)
		}
	}
}

func TestBrowserProxyRejectsWildcardsAndLoopback(t *testing.T) {
	for _, policy := range []string{
		`{"version":1,"origins":[]}`,
		`{"version":1,"origins":[{"host":"*.ts.net","port":443}]}`,
		`{"version":1,"origins":[{"host":"127.0.0.1","port":443}]}`,
		`{"version":1,"origins":[{"host":"server","port":0}]}`,
	} {
		if _, err := parseBrowserProxyPolicy(policy); err == nil {
			t.Fatalf("expected policy to fail: %s", policy)
		}
	}
}

func TestRandomBrowserProxyListenerUsesNonDefaultLoopback(t *testing.T) {
	listener, err := randomLoopbackListener()
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	if host, _, err := netSplitHostPort(listener.Addr().String()); err != nil || host == "127.0.0.1" {
		t.Fatalf("listener = %q, host = %q, err = %v", listener.Addr(), host, err)
	}
}

// Kept tiny so the test calls the same standard parser without adding a test-only dependency.
func netSplitHostPort(value string) (string, string, error) {
	return net.SplitHostPort(value)
}
