// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package novatailnet

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"testing"
	"time"
)

func TestAuthKeyUsesLocalAPIAndRedactsErrors(t *testing.T) {
	const secret = "test-key-never-return-in-events"
	received := make(chan ipn.Options, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/localapi/v0/start" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		var options ipn.Options
		if err := json.NewDecoder(r.Body).Decode(&options); err != nil {
			t.Error(err)
		}
		received <- options
		http.Error(w, secret, http.StatusForbidden)
	}))
	defer server.Close()
	node := &Node{started: true, events: make(chan string, 8), localClient: &local.Client{
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
		},
	}}
	if err := node.BeginAuthKeyLogin(secret); err != nil {
		t.Fatal(err)
	}
	select {
	case options := <-received:
		if options.AuthKey != secret || options.UpdatePrefs != nil {
			t.Fatal("auth key or retained preferences changed")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no auth request")
	}
	select {
	case event := <-node.events:
		if strings.Contains(event, secret) || !strings.Contains(event, "failed") {
			t.Fatal("unredacted or missing failure")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no failure event")
	}
	node.CancelAuthKeyLogin()
}

func TestAuthKeyRejectsEmptyAndUnstartedNode(t *testing.T) {
	node := &Node{}
	if node.BeginAuthKeyLogin("  ") == nil {
		t.Fatal("accepted empty key")
	}
	if node.BeginAuthKeyLogin("secret") == nil {
		t.Fatal("accepted unstarted node")
	}
}

func TestAuthKeyCancellationSuppressesLateFailure(t *testing.T) {
	entered := make(chan struct{})
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-release
		http.Error(w, "do not expose a cancelled response", http.StatusForbidden)
	}))
	defer server.Close()
	node := &Node{started: true, events: make(chan string, 8), localClient: &local.Client{
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
		},
	}}
	if err := node.BeginAuthKeyLogin("fixture-key"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		close(release)
		t.Fatal("no auth request")
	}
	node.CancelAuthKeyLogin()
	close(release)
	select {
	case <-node.events:
		t.Fatal("cancelled request emitted failure")
	case <-time.After(100 * time.Millisecond):
	}
}

// A successful /start only installs the key. New nodes must receive the
// subsequent login trigger; without it they remain NeedsLogin indefinitely.
func TestAuthKeyTriggersLoginAfterInstallingKey(t *testing.T) {
	for _, failLogin := range []bool{false, true} {
		t.Run(fmt.Sprint("loginFailure=", failLogin), func(t *testing.T) {
			const secret = "fixture-secret-never-log"
			requests := make(chan string, 3)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/localapi/v0/start":
					var options ipn.Options
					if err := json.NewDecoder(r.Body).Decode(&options); err != nil {
						t.Error(err)
					}
					if options.AuthKey != secret || options.UpdatePrefs != nil {
						t.Error("incorrect start options")
					}
				case "/localapi/v0/login-interactive":
					if failLogin {
						requests <- r.URL.Path
						http.Error(w, secret, http.StatusForbidden)
						return
					}
				default:
					t.Errorf("unexpected endpoint %s", r.URL.Path)
				}
				requests <- r.URL.Path
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			node := &Node{started: true, events: make(chan string, 8), localClient: &local.Client{
				Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
					return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
				},
			}}
			defer node.CancelAuthKeyLogin()
			if err := node.BeginAuthKeyLogin(secret); err != nil {
				t.Fatal(err)
			}
			for _, want := range []string{"/localapi/v0/start", "/localapi/v0/login-interactive"} {
				select {
				case got := <-requests:
					if got != want {
						t.Fatalf("got %s, want %s", got, want)
					}
				case <-time.After(5 * time.Second):
					t.Fatalf("missing %s", want)
				}
			}
			if failLogin {
				select {
				case event := <-node.events:
					if strings.Contains(event, secret) || !strings.Contains(event, "failed") {
						t.Fatal("missing redacted failure")
					}
				case <-time.After(5 * time.Second):
					t.Fatal("missing failure event")
				}
			}
		})
	}
}
