// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"encoding/json"
	"net"
	"os"
	"testing"
	"time"
)

func TestAndroidInterfaceSnapshot(t *testing.T) {
	payload := `[{"name":"wlan0","index":7,"mtu":1500,"up":true,"broadcast":true,"loopback":false,"pointToPoint":false,"multicast":true,"addrs":[{"ip":"192.0.2.3","prefixLen":24},{"ip":"fe80::1%wlan0","prefixLen":64}]}]`
	if err := UpdateInterfacesJSON(payload); err != nil {
		t.Fatalf("UpdateInterfacesJSON: %v", err)
	}
	interfaces, err := androidInterfaces.get()
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if len(interfaces) != 1 || interfaces[0].Name != "wlan0" || len(interfaces[0].AltAddrs) != 2 {
		t.Fatalf("interfaces = %#v", interfaces)
	}
}

func TestNewNodeValidatesConfiguration(t *testing.T) {
	if _, err := NewNode("relative", "novascale", ""); err == nil {
		t.Fatal("expected relative state directory to fail")
	}
	if _, err := NewNode(t.TempDir(), "novascale", "http://control.example"); err == nil {
		t.Fatal("expected insecure control URL to fail")
	}
	if _, err := NewNode(t.TempDir(), "novascale", "https://user:secret@control.example"); err == nil {
		t.Fatal("expected a control URL containing credentials to fail")
	}
	stateDirectory := t.TempDir()
	node, err := NewNode(stateDirectory, "novascale", "https://control.example")
	if err != nil {
		t.Fatalf("NewNode: %v", err)
	}
	if node == nil {
		t.Fatal("expected node")
	}
	if got := os.Getenv("TS_LOGS_DIR"); got != stateDirectory {
		t.Fatalf("TS_LOGS_DIR = %q, want %q", got, stateDirectory)
	}
}

func TestEventQueueKeepsNewestEvent(t *testing.T) {
	node, err := NewNode(t.TempDir(), "novascale", "")
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 80; index++ {
		node.emit(event{Type: "state", State: "starting"})
	}
	raw := node.NextEvent(1)
	var got event
	if err := json.Unmarshal([]byte(raw), &got); err != nil {
		t.Fatalf("event JSON: %v", err)
	}
	if got.Version != apiVersion {
		t.Fatalf("version = %d", got.Version)
	}
}

func TestSocketBridgeTransfersBothDirections(t *testing.T) {
	node, err := NewNode(t.TempDir(), "novascale", "")
	if err != nil {
		t.Fatal(err)
	}
	remoteBridge, remotePeer := net.Pipe()
	handle, err := node.bridgeConnection(remoteBridge)
	if err != nil {
		t.Fatalf("bridgeConnection: %v", err)
	}
	fd, err := handle.TakeFD()
	if err != nil {
		t.Fatalf("TakeFD: %v", err)
	}
	file := os.NewFile(uintptr(fd), "novascale-bridge-test")
	localPeer, err := net.FileConn(file)
	_ = file.Close()
	if err != nil {
		t.Fatalf("FileConn: %v", err)
	}
	defer localPeer.Close()
	defer remotePeer.Close()

	deadline := time.Now().Add(2 * time.Second)
	_ = localPeer.SetDeadline(deadline)
	_ = remotePeer.SetDeadline(deadline)

	go func() { _, _ = localPeer.Write([]byte("request")) }()
	request := make([]byte, len("request"))
	if _, err := remotePeer.Read(request); err != nil {
		t.Fatalf("remote read: %v", err)
	}
	if string(request) != "request" {
		t.Fatalf("request = %q", request)
	}

	go func() { _, _ = remotePeer.Write([]byte("response")) }()
	response := make([]byte, len("response"))
	if _, err := localPeer.Read(response); err != nil {
		t.Fatalf("local read: %v", err)
	}
	if string(response) != "response" {
		t.Fatalf("response = %q", response)
	}

	handle.Cancel()
	select {
	case <-handle.bridge.done:
	case <-time.After(2 * time.Second):
		t.Fatal("bridge did not stop")
	}
}
