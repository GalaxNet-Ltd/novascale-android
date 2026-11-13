// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package localproxy

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const password = "test-only-password-0123456789abcdef"

func config() Config { return Config{Username: "nova", Password: password, Realm: "test-realm"} }
func start(t *testing.T, d DialFunc) *Server {
	t.Helper()
	s, e := Start(config(), d)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(s.Close)
	return s
}
func auth() string { return "Basic " + base64.StdEncoding.EncodeToString([]byte("nova:"+password)) }
func socket(t *testing.T, s *Server) net.Conn {
	t.Helper()
	c, e := net.Dial("tcp", s.Address())
	if e != nil {
		t.Fatal(e)
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	t.Cleanup(func() { c.Close() })
	return c
}
func TestAuthenticationBeforeDial(t *testing.T) {
	var calls atomic.Int32
	s := start(t, func(context.Context, string, string) (net.Conn, error) {
		calls.Add(1)
		return nil, fmt.Errorf("unexpected dial")
	})
	for _, a := range []string{"", "Basic !!!", "Basic " + base64.StdEncoding.EncodeToString([]byte("nova:wrong"))} {
		c := socket(t, s)
		fmt.Fprintf(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: %s\r\n\r\n", a)
		r, e := http.ReadResponse(bufio.NewReader(c), nil)
		if e != nil {
			t.Fatal(e)
		}
		if r.StatusCode != 407 {
			t.Fatalf("status %d", r.StatusCode)
		}
		r.Body.Close()
	}
	c := socket(t, s)
	c.Write([]byte{5, 1, 0})
	b := make([]byte, 2)
	io.ReadFull(c, b)
	if b[1] != 255 {
		t.Fatal("SOCKS allowed no authentication")
	}
	c = socket(t, s)
	c.Write([]byte{5, 1, 2})
	io.ReadFull(c, b)
	c.Write(append([]byte{1, 4}, append([]byte("nova"), append([]byte{5}, []byte("wrong")...)...)...))
	io.ReadFull(c, b)
	if b[1] != 1 {
		t.Fatal("SOCKS allowed wrong password")
	}
	if calls.Load() != 0 {
		t.Fatal("unauthenticated request reached dialer")
	}
}

type remoteConn struct{ net.Conn }

func (c remoteConn) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.ParseIP("100.64.0.42"), Port: 443}
}
func echoDial(context.Context, string, string) (net.Conn, error) {
	a, b := net.Pipe()
	go func() { defer b.Close(); io.Copy(b, b) }()
	return remoteConn{a}, nil
}
func TestConnectAndShutdown(t *testing.T) {
	s := start(t, echoDial)
	c := socket(t, s)
	fmt.Fprintf(c, "CONNECT service.ts.net:443 HTTP/1.1\r\nHost: service.ts.net:443\r\nProxy-Authorization: %s\r\n\r\n", auth())
	r := bufio.NewReader(c)
	resp, e := http.ReadResponse(r, nil)
	if e != nil || resp.StatusCode != 200 {
		t.Fatalf("connect: %v %v", resp, e)
	}
	c.Write([]byte("ping"))
	b := make([]byte, 4)
	if _, e = io.ReadFull(r, b); e != nil || string(b) != "ping" {
		t.Fatal("tunnel failed", e)
	}
	slow := socket(t, s)
	slow.Write([]byte{5})
	s.Close()
	if _, e = c.Read(b); e == nil {
		t.Fatal("tunnel survived close")
	}
	if _, e = slow.Read(b); e == nil {
		t.Fatal("handshake survived close")
	}
	if _, e = net.DialTimeout("tcp", s.Address(), time.Second); e == nil {
		t.Fatal("listener survived close")
	}
}
func TestSocksTCP(t *testing.T) {
	s := start(t, echoDial)
	c := socket(t, s)
	b := make([]byte, 2)
	c.Write([]byte{5, 1, 2})
	io.ReadFull(c, b)
	if b[1] != 2 {
		t.Fatal("auth not selected")
	}
	payload := append([]byte{1, 4}, []byte("nova")...)
	payload = append(payload, byte(len(password)))
	payload = append(payload, []byte(password)...)
	c.Write(payload)
	io.ReadFull(c, b)
	if b[1] != 0 {
		t.Fatal("auth rejected")
	}
	host := "service.ts.net"
	req := append([]byte{5, 1, 0, 3, byte(len(host))}, []byte(host)...)
	req = append(req, 1, 187)
	c.Write(req)
	reply := make([]byte, 10)
	if _, e := io.ReadFull(c, reply); e != nil || reply[1] != 0 {
		t.Fatal("connect failed", e)
	}
	c.Write([]byte("ping"))
	reply = make([]byte, 4)
	if _, e := io.ReadFull(c, reply); e != nil || string(reply) != "ping" {
		t.Fatal("SOCKS tunnel failed", e)
	}
}
func TestPreferredPortFallback(t *testing.T) {
	occupied, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer occupied.Close()
	cfg := config()
	cfg.Port = occupied.Addr().(*net.TCPAddr).Port
	s, e := Start(cfg, echoDial)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if !s.Fallback || s.Address() == occupied.Addr().String() {
		t.Fatal("no fallback")
	}
	occupied.Close()
	s2, e := Start(cfg, echoDial)
	if e != nil {
		t.Fatal(e)
	}
	defer s2.Close()
	if s2.Fallback || s2.Address() != occupied.Addr().String() {
		t.Fatal("preferred port not used")
	}
}
func TestDestinationPolicyAndDNSAlias(t *testing.T) {
	for _, a := range []string{"localhost:80", "x.localhost:80", "127.0.0.2:80", "[::ffff:127.0.0.1]:80", "[::1]:80", "0.0.0.0:80", "169.254.169.254:80", "[fe80::1]:80", "224.0.0.1:80", "example.com:0", "example.com:65536"} {
		if AllowedAddress(a) {
			t.Fatal("allowed", a)
		}
	}
	for _, a := range []string{"100.64.0.1:80", "[fd7a:115c:a1e0::1]:443", "192.168.1.2:8080", "grafana:3000", "example.com:443"} {
		if !AllowedAddress(a) {
			t.Fatal("rejected", a)
		}
	}
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer ln.Close()
	s := start(t, func(ctx context.Context, n, a string) (net.Conn, error) { return net.Dial(n, ln.Addr().String()) })
	if c, e := s.dialRemote(context.Background(), "tcp", "alias.example:80"); e == nil {
		c.Close()
		t.Fatal("DNS alias reached loopback")
	}
}
func TestHTTPRangeAndWebSocket(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Proxy-Authorization") != "" {
			t.Error("proxy credential leaked")
		}
		if r.Host != "service.ts.net" {
			t.Error("wrong Host", r.Host)
		}
		if r.URL.Path == "/ws" {
			if r.Header.Get("Upgrade") != "websocket" {
				t.Error("lost Upgrade")
				http.Error(w, "bad", 400)
				return
			}
			c, b, _ := w.(http.Hijacker).Hijack()
			defer c.Close()
			fmt.Fprint(b, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
			b.Flush()
			io.Copy(c, b)
			return
		}
		if r.Header.Get("Range") != "bytes=0-3" {
			t.Error("range lost")
		}
		w.Header().Set("Content-Range", "bytes 0-3/10")
		w.WriteHeader(206)
		fmt.Fprint(w, "data")
	}))
	defer origin.Close()
	s := start(t, func(ctx context.Context, n, a string) (net.Conn, error) {
		c, e := net.Dial(n, strings.TrimPrefix(origin.URL, "http://"))
		if e != nil {
			return nil, e
		}
		return remoteConn{c}, nil
	})
	u, _ := url.Parse("http://" + s.Address())
	u.User = url.UserPassword("nova", password)
	tr := &http.Transport{Proxy: http.ProxyURL(u)}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: 3 * time.Second}
	req, _ := http.NewRequest("GET", "http://service.ts.net/file", nil)
	req.Header.Set("Range", "bytes=0-3")
	res, e := client.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 206 || string(b) != "data" {
		t.Fatal("range failed")
	}
	c := socket(t, s)
	fmt.Fprintf(c, "GET ws://service.ts.net/ws HTTP/1.1\r\nHost: service.ts.net\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nProxy-Authorization: %s\r\n\r\n", auth())
	r := bufio.NewReader(c)
	res, e = http.ReadResponse(r, nil)
	if e != nil || res.StatusCode != 101 {
		t.Fatal("upgrade failed", e)
	}
	c.Write([]byte("echo"))
	b = make([]byte, 4)
	if _, e = io.ReadFull(r, b); e != nil || string(b) != "echo" {
		t.Fatal("duplex upgrade failed", e)
	}
	s.Close()
	if _, e = c.Read(b); e == nil {
		t.Fatal("upgrade survived shutdown")
	}
}
