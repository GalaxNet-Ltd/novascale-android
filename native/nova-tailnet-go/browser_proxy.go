// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const browserProxyConfigVersion = 1

type browserProxyConfig struct {
	Version int                    `json:"version"`
	Origins []browserAllowedOrigin `json:"origins"`
}

type browserAllowedOrigin struct {
	Host string `json:"host"`
	Port int    `json:"port"`
}

type browserProxyResult struct {
	Version  int    `json:"version"`
	ID       string `json:"id"`
	ProxyURL string `json:"proxyUrl"`
}

type browserProxy struct {
	node      *Node
	id        string
	listener  net.Listener
	server    *http.Server
	transport *http.Transport
	allowed   map[string]map[int]struct{}
	closeOnce sync.Once
}

// StartBrowserProxy starts a capability-scoped HTTP CONNECT proxy. The JSON
// policy must enumerate every exact host and port the WebView may reach. The
// listener uses a random address in 127/8 plus an ephemeral port so its address
// acts as an app-private, short-lived capability; no wildcard or direct fallback
// is provided.
func (n *Node) StartBrowserProxy(policyJSON string) (string, error) {
	allowed, err := parseBrowserProxyPolicy(policyJSON)
	if err != nil {
		return "", err
	}
	if err := n.Start(); err != nil {
		return "", err
	}
	listener, err := randomLoopbackListener()
	if err != nil {
		return "", errors.New("unable to create private browser proxy")
	}
	id, err := randomConnectionID()
	if err != nil {
		listener.Close()
		return "", err
	}
	proxy := &browserProxy{
		node:     n,
		id:       id,
		listener: listener,
		allowed:  allowed,
	}
	proxy.transport = &http.Transport{
		Proxy:                 nil,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          16,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" && network != "tcp4" && network != "tcp6" {
				return nil, errors.New("unsupported browser proxy network")
			}
			if !proxy.allowsAddress(address) {
				return nil, errors.New("browser destination is outside the approved origin set")
			}
			return n.server.Dial(ctx, "tcp", address)
		},
	}
	proxy.server = &http.Server{
		Handler:           proxy,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    64 * 1024,
	}

	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		proxy.close()
		return "", errors.New("node is closed")
	}
	n.proxies[id] = proxy
	n.mu.Unlock()
	go func() {
		_ = proxy.server.Serve(listener)
		proxy.close()
	}()
	result, _ := json.Marshal(browserProxyResult{
		Version:  browserProxyConfigVersion,
		ID:       id,
		ProxyURL: "http://" + listener.Addr().String(),
	})
	return string(result), nil
}

// StopBrowserProxy revokes a browser capability. Repeated calls are harmless.
func (n *Node) StopBrowserProxy(id string) {
	n.mu.Lock()
	proxy := n.proxies[id]
	n.mu.Unlock()
	if proxy != nil {
		proxy.close()
	}
}

func (p *browserProxy) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodConnect {
		p.serveConnect(writer, request)
		return
	}
	if request.URL == nil || request.URL.Host == "" {
		request.URL = &url.URL{Scheme: "http", Host: request.Host, Path: request.RequestURI}
	}
	if !p.allowsURL(request.URL) {
		http.Error(writer, "destination not approved", http.StatusForbidden)
		return
	}
	request.RequestURI = ""
	removeHopByHopHeaders(request.Header)
	request.Header.Del("Proxy-Authorization")
	response, err := p.transport.RoundTrip(request)
	if err != nil {
		http.Error(writer, "tailnet request failed", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	removeHopByHopHeaders(response.Header)
	for name, values := range response.Header {
		for _, value := range values {
			writer.Header().Add(name, value)
		}
	}
	writer.WriteHeader(response.StatusCode)
	_, _ = io.Copy(writer, response.Body)
}

func (p *browserProxy) serveConnect(writer http.ResponseWriter, request *http.Request) {
	if !p.allowsAddress(request.Host) {
		http.Error(writer, "destination not approved", http.StatusForbidden)
		return
	}
	ctx, cancel := context.WithTimeout(request.Context(), 30*time.Second)
	defer cancel()
	remote, err := p.node.server.Dial(ctx, "tcp", request.Host)
	if err != nil {
		http.Error(writer, "tailnet connection failed", http.StatusBadGateway)
		return
	}
	hijacker, ok := writer.(http.Hijacker)
	if !ok {
		remote.Close()
		http.Error(writer, "proxy tunnel unavailable", http.StatusInternalServerError)
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		remote.Close()
		return
	}
	if _, err := buffered.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		client.Close()
		remote.Close()
		return
	}
	if err := buffered.Flush(); err != nil {
		client.Close()
		remote.Close()
		return
	}
	go tunnelConnections(client, buffered.Reader, remote)
}

func tunnelConnections(client net.Conn, buffered *bufio.Reader, remote net.Conn) {
	defer client.Close()
	defer remote.Close()
	var wait sync.WaitGroup
	wait.Add(2)
	go func() {
		defer wait.Done()
		_, _ = io.Copy(remote, buffered)
		closeWrite(remote)
	}()
	go func() {
		defer wait.Done()
		_, _ = io.Copy(client, remote)
		closeWrite(client)
	}()
	wait.Wait()
}

func (p *browserProxy) allowsAddress(address string) bool {
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return false
	}
	host = normalizeBrowserHost(host)
	ports := p.allowed[host]
	_, ok := ports[port]
	return ok
}

func (p *browserProxy) allowsURL(value *url.URL) bool {
	if value == nil || value.Hostname() == "" {
		return false
	}
	port := value.Port()
	if port == "" {
		if strings.EqualFold(value.Scheme, "https") {
			port = "443"
		} else {
			port = "80"
		}
	}
	return p.allowsAddress(net.JoinHostPort(value.Hostname(), port))
}

func (p *browserProxy) close() {
	p.closeOnce.Do(func() {
		p.transport.CloseIdleConnections()
		_ = p.server.Close()
		_ = p.listener.Close()
		p.node.mu.Lock()
		delete(p.node.proxies, p.id)
		p.node.mu.Unlock()
	})
}

func parseBrowserProxyPolicy(raw string) (map[string]map[int]struct{}, error) {
	var policy browserProxyConfig
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&policy); err != nil || policy.Version != browserProxyConfigVersion {
		return nil, errors.New("invalid browser proxy policy")
	}
	if len(policy.Origins) == 0 || len(policy.Origins) > 16 {
		return nil, errors.New("browser proxy requires 1 to 16 exact origins")
	}
	allowed := make(map[string]map[int]struct{})
	for _, origin := range policy.Origins {
		host := normalizeBrowserHost(origin.Host)
		if !validBrowserHost(host) || origin.Port < 1 || origin.Port > 65535 {
			return nil, errors.New("invalid browser proxy origin")
		}
		ports := allowed[host]
		if ports == nil {
			ports = make(map[int]struct{})
			allowed[host] = ports
		}
		ports[origin.Port] = struct{}{}
	}
	return allowed, nil
}

func normalizeBrowserHost(host string) string {
	return strings.ToLower(strings.TrimSuffix(strings.TrimSpace(host), "."))
}

func validBrowserHost(host string) bool {
	if host == "" || strings.ContainsAny(host, " /\\@%*") {
		return false
	}
	if ip := net.ParseIP(strings.Trim(host, "[]")); ip != nil {
		return !ip.IsLoopback() && !ip.IsUnspecified()
	}
	return len(host) <= 253
}

func randomLoopbackListener() (net.Listener, error) {
	var random [3]byte
	for range 32 {
		if _, err := rand.Read(random[:]); err != nil {
			return nil, err
		}
		// Avoid common localhost aliases and keep every octet non-zero.
		address := fmt.Sprintf("127.%d.%d.%d:0", int(random[0])%254+1, int(random[1])%254+1, int(random[2])%254+1)
		listener, err := net.Listen("tcp4", address)
		if err == nil {
			return listener, nil
		}
	}
	return nil, errors.New("unable to allocate random loopback capability")
}

func removeHopByHopHeaders(header http.Header) {
	for _, name := range strings.Split(header.Get("Connection"), ",") {
		header.Del(strings.TrimSpace(name))
	}
	for _, name := range []string{
		"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
		"Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade",
	} {
		header.Del(name)
	}
}
