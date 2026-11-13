// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0
//
// The JSON schema and conversion approach are adapted from
// tailscale/tailscale-android libtailscale/ifaceparse (BSD-3-Clause).

package novatailnet

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"slices"
	"sync"

	"tailscale.com/net/netmon"
)

var androidInterfaces interfaceStore

func init() {
	netmon.RegisterInterfaceGetter(androidInterfaces.get)
}

type interfaceStore struct {
	mu         sync.RWMutex
	interfaces []netmon.Interface
	ready      bool
}

// UpdateInterfacesJSON supplies Android's public NetworkInterface view to
// Tailscale. Android denies the Go runtime's netlink route query to ordinary
// apps, so this is required before starting tsnet on Android 11 and newer.
func UpdateInterfacesJSON(payload string) error {
	interfaces, err := parseInterfacesJSON([]byte(payload))
	if err != nil {
		return errors.New("invalid Android interface snapshot")
	}
	androidInterfaces.mu.Lock()
	androidInterfaces.interfaces = interfaces
	androidInterfaces.ready = true
	androidInterfaces.mu.Unlock()
	return nil
}

func (s *interfaceStore) get() ([]netmon.Interface, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if !s.ready {
		return nil, errors.New("Android network interfaces are not initialized")
	}
	return slices.Clone(s.interfaces), nil
}

type interfaceJSON struct {
	Name         string        `json:"name"`
	Index        int           `json:"index"`
	MTU          int           `json:"mtu"`
	Up           bool          `json:"up"`
	Broadcast    bool          `json:"broadcast"`
	Loopback     bool          `json:"loopback"`
	PointToPoint bool          `json:"pointToPoint"`
	Multicast    bool          `json:"multicast"`
	Addresses    []addressJSON `json:"addrs"`
}

type addressJSON struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}

func parseInterfacesJSON(payload []byte) ([]netmon.Interface, error) {
	var input []interfaceJSON
	if err := json.Unmarshal(payload, &input); err != nil {
		return nil, err
	}
	output := make([]netmon.Interface, 0, len(input))
	for _, item := range input {
		if item.Name == "" {
			continue
		}
		value := netmon.Interface{
			Interface: &net.Interface{
				Index: item.Index,
				MTU:   item.MTU,
				Name:  item.Name,
			},
			AltAddrs: []net.Addr{},
		}
		if item.Up {
			value.Flags |= net.FlagUp
		}
		if item.Broadcast {
			value.Flags |= net.FlagBroadcast
		}
		if item.Loopback {
			value.Flags |= net.FlagLoopback
		}
		if item.PointToPoint {
			value.Flags |= net.FlagPointToPoint
		}
		if item.Multicast {
			value.Flags |= net.FlagMulticast
		}
		value.Interface.Flags = value.Flags
		for _, address := range item.Addresses {
			parsed, err := address.netAddress()
			if err == nil {
				value.AltAddrs = append(value.AltAddrs, parsed)
			}
		}
		output = append(output, value)
	}
	return output, nil
}

func (a addressJSON) netAddress() (net.Addr, error) {
	address, err := netip.ParseAddr(a.IP)
	if err != nil {
		return nil, err
	}
	ip := net.IP(address.AsSlice())
	if zone := address.Zone(); zone != "" {
		return &net.IPAddr{IP: ip, Zone: zone}, nil
	}
	bits := 128
	if address.Is4() {
		bits = 32
	}
	if a.PrefixLen < 0 || a.PrefixLen > bits {
		return &net.IPAddr{IP: ip}, nil
	}
	return &net.IPNet{IP: ip, Mask: net.CIDRMask(a.PrefixLen, bits)}, nil
}
