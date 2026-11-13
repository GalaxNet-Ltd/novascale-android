// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

type dialBridge struct {
	node   *Node
	id     string
	remote net.Conn
	local  net.Conn
	handle *DialHandle

	closeOnce sync.Once
	done      chan struct{}
}

// DialHandle represents a Go pump and one descriptor awaiting one-way
// ownership transfer to Rust.
type DialHandle struct {
	mu     sync.Mutex
	id     string
	fd     int
	taken  bool
	closed bool
	bridge *dialBridge
}

// ConnectionID identifies the Go-side pump for cancellation and diagnostics.
func (h *DialHandle) ConnectionID() string {
	return h.id
}

// TakeFD transfers the descriptor exactly once. The caller owns it on return.
func (h *DialHandle) TakeFD() (int, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return -1, errors.New("dial handle is closed")
	}
	if h.taken || h.fd < 0 {
		return -1, errors.New("descriptor already transferred")
	}
	fd := h.fd
	h.fd = -1
	h.taken = true
	return fd, nil
}

// Cancel stops the Go pump. It also closes the descriptor if TakeFD has not
// transferred ownership yet.
func (h *DialHandle) Cancel() {
	if h.bridge != nil {
		h.bridge.cancel()
	}
}

func (h *DialHandle) closeUntakenFD() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closed = true
	if !h.taken && h.fd >= 0 {
		_ = unix.Close(h.fd)
		h.fd = -1
	}
}

// DialTCP dials through the shared tsnet node and creates a full-duplex local
// socket bridge. TakeFD must be called exactly once on success.
func (n *Node) DialTCP(address string, timeoutMillis int64) (*DialHandle, error) {
	if _, _, err := net.SplitHostPort(address); err != nil {
		return nil, errors.New("destination must be host:port")
	}
	if err := n.Start(); err != nil {
		return nil, err
	}
	timeout := boundedTimeout(timeoutMillis, 2*time.Minute)
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	remote, err := n.server.Dial(ctx, "tcp", address)
	if err != nil {
		return nil, errors.New("tailnet dial failed")
	}
	handle, err := n.bridgeConnection(remote)
	if err != nil {
		remote.Close()
		return nil, err
	}
	return handle, nil
}

// CancelDial cancels a pump by ID. Repeated calls are harmless.
func (n *Node) CancelDial(connectionID string) {
	n.mu.Lock()
	bridge := n.bridges[connectionID]
	n.mu.Unlock()
	if bridge != nil {
		bridge.cancel()
	}
}

func (n *Node) bridgeConnection(remote net.Conn) (*DialHandle, error) {
	descriptors, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("create socket pair: %w", err)
	}
	localFile := os.NewFile(uintptr(descriptors[0]), "novascale-tailnet-bridge")
	local, err := net.FileConn(localFile)
	_ = localFile.Close()
	if err != nil {
		_ = unix.Close(descriptors[1])
		return nil, fmt.Errorf("adopt socket pair: %w", err)
	}
	id, err := randomConnectionID()
	if err != nil {
		local.Close()
		_ = unix.Close(descriptors[1])
		return nil, err
	}
	handle := &DialHandle{id: id, fd: descriptors[1]}
	bridge := &dialBridge{
		node:   n,
		id:     id,
		remote: remote,
		local:  local,
		handle: handle,
		done:   make(chan struct{}),
	}
	handle.bridge = bridge

	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		bridge.cancel()
		return nil, errors.New("node is closed")
	}
	n.bridges[id] = bridge
	n.mu.Unlock()

	go bridge.run()
	return handle, nil
}

func (b *dialBridge) run() {
	var wait sync.WaitGroup
	wait.Add(2)
	go func() {
		defer wait.Done()
		_, _ = io.Copy(b.remote, b.local)
		closeWrite(b.remote)
	}()
	go func() {
		defer wait.Done()
		_, _ = io.Copy(b.local, b.remote)
		closeWrite(b.local)
	}()
	wait.Wait()
	b.cancel()
}

func (b *dialBridge) cancel() {
	b.closeOnce.Do(func() {
		_ = b.remote.Close()
		_ = b.local.Close()
		b.handle.closeUntakenFD()
		if b.node != nil {
			b.node.mu.Lock()
			delete(b.node.bridges, b.id)
			b.node.mu.Unlock()
		}
		close(b.done)
	})
}

func closeWrite(connection net.Conn) {
	type closeWriter interface {
		CloseWrite() error
	}
	if writer, ok := connection.(closeWriter); ok {
		_ = writer.CloseWrite()
	}
}

func randomConnectionID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", fmt.Errorf("create connection ID: %w", err)
	}
	return hex.EncodeToString(bytes), nil
}
