// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"cc.galaxnet.novascale/native/novatailnet/internal/localproxy"

	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// Node owns NovaScale's one embedded tailnet runtime.
type Node struct {
	lifecycleMu sync.Mutex
	mu          sync.Mutex
	eventMu     sync.Mutex

	localProxy     *localproxy.Server
	localProxyID   string
	server         *tsnet.Server
	localClient    *local.Client
	authCancel     context.CancelFunc
	authGeneration uint64
	watchCancel    context.CancelFunc
	started        bool
	closed         bool

	events  chan string
	bridges map[string]*dialBridge
	proxies map[string]*browserProxy
	pings   map[string]context.CancelFunc
}

// NewNode creates an unstarted node. stateDirectory must be app-private and
// unique to the selected tailnet profile.
func NewNode(stateDirectory, hostname, controlURL string) (*Node, error) {
	stateDirectory = strings.TrimSpace(stateDirectory)
	hostname = strings.TrimSpace(hostname)
	controlURL = strings.TrimSpace(controlURL)
	if stateDirectory == "" || !filepath.IsAbs(stateDirectory) {
		return nil, errors.New("state directory must be an absolute path")
	}
	if hostname == "" {
		return nil, errors.New("hostname must not be empty")
	}
	if controlURL != "" {
		parsed, err := url.Parse(controlURL)
		if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Host == "" ||
			parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
			return nil, errors.New("custom control URL must be an absolute HTTPS URL without credentials, query, or fragment")
		}
	}
	if err := os.MkdirAll(stateDirectory, 0700); err != nil {
		return nil, fmt.Errorf("create state directory: %w", err)
	}

	// NovaScale does not upload diagnostic logs implicitly. This process-wide
	// Tailscale setting is applied before the tsnet logger is initialized.
	envknob.SetNoLogsNoSupport()
	// Android's Go runtime has neither a Unix service-state directory nor a
	// usable user cache directory. Point Tailscale's auxiliary log-policy state
	// at the same app-private directory as tsnet. Upload remains disabled above.
	if err := os.Setenv("TS_LOGS_DIR", stateDirectory); err != nil {
		return nil, fmt.Errorf("configure log state directory: %w", err)
	}

	server := &tsnet.Server{
		Dir:        stateDirectory,
		Hostname:   hostname,
		ControlURL: controlURL,
		Logf:       func(string, ...any) {},
		UserLogf:   func(string, ...any) {},
	}
	return &Node{
		server:  server,
		events:  make(chan string, 64),
		bridges: make(map[string]*dialBridge),
		proxies: make(map[string]*browserProxy),
		pings:   make(map[string]context.CancelFunc),
	}, nil
}

// Start initializes tsnet and begins publishing coarse state events.
func (n *Node) Start() error {
	n.lifecycleMu.Lock()
	defer n.lifecycleMu.Unlock()

	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return errors.New("node is closed")
	}
	if n.started {
		n.mu.Unlock()
		return nil
	}
	n.mu.Unlock()

	lc, err := n.server.LocalClient()
	if err != nil {
		n.emit(event{Type: "state", State: "failed", Message: "Tailnet initialization failed"})
		return fmt.Errorf("start tailnet: %w", err)
	}

	watchContext, cancel := context.WithCancel(context.Background())
	watcher, err := lc.WatchIPNBus(
		watchContext,
		nodeWatchOptions,
	)
	if err != nil {
		cancel()
		return fmt.Errorf("watch tailnet state: %w", err)
	}

	n.mu.Lock()
	n.localClient = lc
	n.watchCancel = cancel
	n.started = true
	n.mu.Unlock()

	n.emit(event{Type: "state", State: "starting"})
	go n.watchIPNBus(watcher)
	return nil
}

func (n *Node) watchIPNBus(watcher *local.IPNBusWatcher) {
	defer watcher.Close()
	for {
		notification, err := watcher.Next()
		if err != nil {
			n.mu.Lock()
			closed := n.closed
			n.mu.Unlock()
			if !closed && !errors.Is(err, context.Canceled) {
				n.emit(event{Type: "state", State: "failed", Message: "Tailnet event stream stopped"})
			}
			return
		}
		if notification.ErrMessage != nil {
			n.emit(event{Type: "state", State: "failed", Message: "Tailnet backend reported an error"})
		}
		if notification.State != nil {
			n.emit(event{Type: "state", State: strings.ToLower(notification.State.String())})
		}
		if notification.BrowseToURL != nil {
			n.emit(event{Type: "login", LoginURL: *notification.BrowseToURL})
		}
		if notification.LoginFinished != nil {
			n.emit(event{Type: "login_finished"})
		}
		if peerSnapshotChanged(notification) {
			n.emit(event{Type: "peers_changed"})
		}
	}
}

// BeginInteractiveLogin asks Tailscale to issue a browser login URL. The URL
// is delivered as a login event from NextEvent.
func (n *Node) BeginInteractiveLogin() error {
	if err := n.Start(); err != nil {
		return err
	}
	n.mu.Lock()
	lc := n.localClient
	n.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := lc.StartLoginInteractive(ctx); err != nil {
		return fmt.Errorf("begin interactive login: %w", err)
	}
	return nil
}

// Logout removes the current node identity while retaining this Node object.
func (n *Node) Logout() error {
	n.CancelAuthKeyLogin()
	n.StopLocalProxy("")
	n.mu.Lock()
	lc := n.localClient
	started := n.started
	n.mu.Unlock()
	if !started || lc == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := lc.Logout(ctx); err != nil {
		return fmt.Errorf("logout: %w", err)
	}
	return nil
}

// Close idempotently stops all dial bridges, event watching, and tsnet.
func (n *Node) Close() error {
	n.CancelAuthKeyLogin()
	n.lifecycleMu.Lock()
	defer n.lifecycleMu.Unlock()

	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil
	}
	n.closed = true
	if n.localProxy != nil {
		n.localProxy.Close()
		n.localProxy = nil
	}
	started := n.started
	cancel := n.watchCancel
	bridges := make([]*dialBridge, 0, len(n.bridges))
	for _, bridge := range n.bridges {
		bridges = append(bridges, bridge)
	}
	proxies := make([]*browserProxy, 0, len(n.proxies))
	for _, proxy := range n.proxies {
		proxies = append(proxies, proxy)
	}
	pingCancels := make([]context.CancelFunc, 0, len(n.pings))
	for _, cancelPing := range n.pings {
		pingCancels = append(pingCancels, cancelPing)
	}
	clear(n.pings)
	n.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	for _, bridge := range bridges {
		bridge.cancel()
	}
	for _, proxy := range proxies {
		proxy.close()
	}
	for _, cancelPing := range pingCancels {
		cancelPing()
	}
	if started {
		if err := n.server.Close(); err != nil && !errors.Is(err, context.Canceled) {
			return fmt.Errorf("close tailnet: %w", err)
		}
	}
	n.emit(event{Type: "state", State: "stopped"})
	return nil
}

// Peer delta streams cannot be combined with NotifyRateLimit as of v1.102.
const nodeWatchOptions = ipn.NotifyInitialState | ipn.NotifyInitialNetMap | ipn.NotifyNoPrivateKeys | ipn.NotifyPeerChanges

func peerSnapshotChanged(n ipn.Notify) bool {
	return n.NetMap != nil || n.SelfChange != nil || n.InitialStatus != nil || len(n.PeersChanged) != 0 || len(n.PeersRemoved) != 0 || len(n.PeerChangedPatch) != 0
}
