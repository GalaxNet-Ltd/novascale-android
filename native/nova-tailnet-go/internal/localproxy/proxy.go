// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0

// Package localproxy serves authenticated HTTP/CONNECT and TCP SOCKS5 on one
// loopback listener. It owns no tailnet: its caller supplies the shared dialer.
package localproxy

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Config struct {
	Port                      int
	Username, Password, Realm string
}
type DialFunc func(context.Context, string, string) (net.Conn, error)

type Server struct {
	listener     net.Listener
	httpListener *httpListener
	httpServer   *http.Server
	transport    *http.Transport
	forward      *httputil.ReverseProxy
	config       Config
	dial         DialFunc
	ctx          context.Context
	cancel       context.CancelFunc
	mu           sync.Mutex
	closed       bool
	connections  map[*trackedConn]struct{}
	slots        chan struct{}
	done         chan struct{}
	once         sync.Once
	Fallback     bool
}

// Start binds the preferred port, falling back to an OS-assigned port. A
// malformed configuration is an error, never a request to disable auth.
func Start(config Config, dial DialFunc) (*Server, error) {
	if config.Port < 0 || config.Port > 65535 || config.Username == "" || len(config.Username) > 255 || strings.Contains(config.Username, ":") || len(config.Password) < 32 || len(config.Password) > 255 || config.Realm == "" || strings.ContainsAny(config.Realm, "\"\r\n\\") || dial == nil {
		return nil, errors.New("invalid proxy configuration")
	}
	ln, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(config.Port)))
	fallback := err != nil && config.Port != 0
	if fallback {
		ln, err = net.Listen("tcp4", "127.0.0.1:0")
	}
	if err != nil {
		return nil, errors.New("unable to bind local proxy")
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{listener: ln, config: config, dial: dial, ctx: ctx, cancel: cancel, connections: make(map[*trackedConn]struct{}), slots: make(chan struct{}, 128), done: make(chan struct{}), Fallback: fallback}
	s.httpListener = &httpListener{address: ln.Addr(), incoming: make(chan net.Conn), done: s.done}
	s.transport = &http.Transport{Proxy: nil, DialContext: s.dialRemote, MaxIdleConns: 32, MaxIdleConnsPerHost: 8, IdleConnTimeout: 60 * time.Second, ResponseHeaderTimeout: 30 * time.Second, TLSHandshakeTimeout: 15 * time.Second}
	s.forward = &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.Out.Header.Del("Proxy-Authorization")
			r.Out.Header.Del("Proxy-Connection")
		},
		Transport: s.transport, FlushInterval: -1, ErrorLog: log.New(io.Discard, "", 0),
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, e error) {
			http.Error(w, "Proxy connection failed", http.StatusBadGateway)
		},
	}
	s.httpServer = &http.Server{Handler: s, ReadHeaderTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 64 * 1024, ErrorLog: log.New(io.Discard, "", 0), BaseContext: func(net.Listener) context.Context { return ctx }}
	go func() { _ = s.httpServer.Serve(s.httpListener) }()
	go s.accept()
	return s, nil
}
func (s *Server) Address() string { return s.listener.Addr().String() }
func (s *Server) Close() {
	s.once.Do(func() {
		s.mu.Lock()
		s.closed = true
		s.mu.Unlock()
		s.cancel()
		close(s.done)
		_ = s.listener.Close()
		_ = s.httpServer.Close()
		s.transport.CloseIdleConnections()
		s.mu.Lock()
		all := make([]*trackedConn, 0, len(s.connections))
		for c := range s.connections {
			all = append(all, c)
		}
		s.mu.Unlock()
		for _, c := range all {
			_ = c.Close()
		}
	})
}
func (s *Server) track(c net.Conn, release func()) (*trackedConn, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		c.Close()
		if release != nil {
			release()
		}
		return nil, net.ErrClosed
	}
	t := &trackedConn{Conn: c, owner: s, release: release}
	s.connections[t] = struct{}{}
	return t, nil
}

type trackedConn struct {
	net.Conn
	owner   *Server
	once    sync.Once
	release func()
}

func (c *trackedConn) Close() error {
	var err error
	c.once.Do(func() {
		err = c.Conn.Close()
		c.owner.mu.Lock()
		delete(c.owner.connections, c)
		c.owner.mu.Unlock()
		if c.release != nil {
			c.release()
		}
	})
	return err
}
func (c *trackedConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return c.Close()
}
func (s *Server) accept() {
	for {
		c, err := s.listener.Accept()
		if err != nil {
			return
		}
		select {
		case s.slots <- struct{}{}:
		default:
			c.Close()
			continue
		}
		t, err := s.track(c, func() { <-s.slots })
		if err != nil {
			continue
		}
		go func() {
			_ = t.SetDeadline(time.Now().Add(15 * time.Second))
			r := bufio.NewReader(t)
			first, err := r.Peek(1)
			if err != nil {
				t.Close()
				return
			}
			if first[0] == 5 {
				s.socks(t, r)
				return
			}
			_ = t.SetDeadline(time.Time{})
			buffered := &bufferedConn{Conn: t, reader: r}
			select {
			case s.httpListener.incoming <- buffered:
			case <-s.done:
				t.Close()
			}
		}()
	}
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(b []byte) (int, error) { return c.reader.Read(b) }

type httpListener struct {
	address  net.Addr
	incoming chan net.Conn
	done     <-chan struct{}
}

func (l *httpListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.incoming:
		return c, nil
	case <-l.done:
		return nil, net.ErrClosed
	}
}
func (l *httpListener) Close() error   { return nil }
func (l *httpListener) Addr() net.Addr { return l.address }

func secureEqual(a, b string) bool {
	x := sha256.Sum256([]byte(a))
	y := sha256.Sum256([]byte(b))
	return subtle.ConstantTimeCompare(x[:], y[:]) == 1
}
func (s *Server) authorized(r *http.Request) bool {
	value := r.Header.Get("Proxy-Authorization")
	scheme, encoded, ok := strings.Cut(value, " ")
	if !ok || !strings.EqualFold(scheme, "Basic") {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return false
	}
	u, p, ok := strings.Cut(string(decoded), ":")
	return ok && secureEqual(u, s.config.Username) && secureEqual(p, s.config.Password)
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !s.authorized(r) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="`+s.config.Realm+`"`)
		w.Header().Set("Connection", "close")
		http.Error(w, "Proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	r.Header.Del("Proxy-Authorization")
	if r.Method == http.MethodConnect {
		s.connect(w, r)
		return
	}
	if r.URL != nil && r.URL.Scheme == "ws" && strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		r.URL.Scheme = "http"
	}
	if r.URL == nil || r.URL.Scheme != "http" || r.URL.User != nil || r.URL.Host == "" {
		http.Error(w, "Unsupported proxy request", http.StatusBadRequest)
		return
	}
	host := r.URL.Hostname()
	port := r.URL.Port()
	if port == "" {
		port = "80"
	}
	if !AllowedAddress(net.JoinHostPort(host, port)) {
		http.Error(w, "Destination unavailable", http.StatusForbidden)
		return
	}
	// The absolute request URI is authoritative, not an independently supplied Host.
	r.Host = r.URL.Host
	s.forward.ServeHTTP(w, r)
}
func (s *Server) connect(w http.ResponseWriter, r *http.Request) {
	remote, err := s.dialRemote(r.Context(), "tcp", r.Host)
	if err != nil {
		http.Error(w, "Proxy connection failed", http.StatusBadGateway)
		return
	}
	defer remote.Close()
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Tunnel unavailable", 500)
		return
	}
	c, b, err := hj.Hijack()
	if err != nil {
		return
	}
	defer c.Close()
	if _, err = b.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	if b.Flush() != nil {
		return
	}
	relay(c, b.Reader, remote)
}
func (s *Server) dialRemote(ctx context.Context, network, address string) (net.Conn, error) {
	if network != "tcp" || !AllowedAddress(address) {
		return nil, errors.New("destination unavailable")
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	c, err := s.dial(ctx, network, address)
	if err != nil {
		return nil, errors.New("proxy dial failed")
	}
	// Check the resolved peer too, before sending any payload. This also blocks
	// DNS aliases/rebinding to loopback/control endpoints and mapped IPv4 forms.
	if !AllowedAddress(c.RemoteAddr().String()) {
		c.Close()
		return nil, errors.New("resolved destination unavailable")
	}
	return s.track(c, nil)
}

// AllowedAddress allows tailnet/subnet/public destinations but never this
// device's loopback, link-local, unspecified, multicast or reserved IPv4 space.
func AllowedAddress(address string) bool {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return false
	}
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	if host == "" || host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.ContainsAny(host, " /\\@%\r\n") {
		return false
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		ip = ip.Unmap()
		if !ip.IsGlobalUnicast() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			return false
		}
		if ip.Is4() {
			b := ip.As4()
			if b[0] == 0 || b[0] >= 224 {
				return false
			}
		}
	}
	return true
}
func relay(c net.Conn, reader io.Reader, remote net.Conn) {
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(remote, reader)
		if w, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = w.CloseWrite()
		} else {
			_ = remote.Close()
		}
		close(done)
	}()
	_, _ = io.Copy(c, remote)
	_ = c.Close()
	_ = remote.Close()
	<-done
}
func (s *Server) socks(c net.Conn, r *bufio.Reader) {
	defer c.Close()
	header := make([]byte, 2)
	if _, err := io.ReadFull(r, header); err != nil || header[0] != 5 || header[1] == 0 {
		return
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	found := false
	for _, m := range methods {
		found = found || m == 2
	}
	if !found {
		_, _ = c.Write([]byte{5, 255})
		return
	}
	if _, err := c.Write([]byte{5, 2}); err != nil {
		return
	}
	if _, err := io.ReadFull(r, header); err != nil || header[0] != 1 || header[1] == 0 {
		return
	}
	user := make([]byte, int(header[1]))
	if _, err := io.ReadFull(r, user); err != nil {
		return
	}
	n, err := r.ReadByte()
	if err != nil || n == 0 {
		return
	}
	pass := make([]byte, int(n))
	if _, err := io.ReadFull(r, pass); err != nil {
		return
	}
	if !secureEqual(string(user), s.config.Username) || !secureEqual(string(pass), s.config.Password) {
		_, _ = c.Write([]byte{1, 1})
		return
	}
	if _, err := c.Write([]byte{1, 0}); err != nil {
		return
	}
	request := make([]byte, 4)
	if _, err := io.ReadFull(r, request); err != nil || request[0] != 5 || request[2] != 0 {
		return
	}
	if request[1] != 1 {
		s.socksReply(c, 7)
		return
	}
	var host string
	switch request[3] {
	case 1, 4:
		size := 4
		if request[3] == 4 {
			size = 16
		}
		ip := make([]byte, size)
		if _, err := io.ReadFull(r, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	case 3:
		n, err := r.ReadByte()
		if err != nil || n == 0 {
			return
		}
		b := make([]byte, int(n))
		if _, err := io.ReadFull(r, b); err != nil {
			return
		}
		host = string(b)
	default:
		s.socksReply(c, 8)
		return
	}
	if _, err := io.ReadFull(r, header); err != nil {
		return
	}
	address := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(header))))
	remote, err := s.dialRemote(s.ctx, "tcp", address)
	if err != nil {
		s.socksReply(c, 2)
		return
	}
	defer remote.Close()
	if !s.socksReply(c, 0) {
		return
	}
	_ = c.SetDeadline(time.Time{})
	relay(c, r, remote)
}
func (s *Server) socksReply(c net.Conn, code byte) bool {
	_, err := c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err == nil
}

func (s *Server) String() string { return fmt.Sprintf("LocalProxy(%s)", s.Address()) }
