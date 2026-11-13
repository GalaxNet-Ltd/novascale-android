// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"testing"

	"tailscale.com/ipn/ipnstate"
)

func TestPingDocumentClassifiesConnectionPaths(t *testing.T) {
	tests := []struct {
		name       string
		result     *ipnstate.PingResult
		wantPath   string
		wantDetail string
	}{
		{
			name:       "direct",
			result:     &ipnstate.PingResult{IP: "100.64.0.2", LatencySeconds: 0.0125, Endpoint: "192.0.2.4:41641"},
			wantPath:   "direct",
			wantDetail: "192.0.2.4:41641",
		},
		{
			name:       "peer relay",
			result:     &ipnstate.PingResult{LatencySeconds: 0.021, PeerRelay: "100.64.0.9:40000:vni:7"},
			wantPath:   "peer-relay",
			wantDetail: "100.64.0.9:40000:vni:7",
		},
		{
			name:       "DERP relay",
			result:     &ipnstate.PingResult{LatencySeconds: 0.074, DERPRegionID: 4, DERPRegionCode: "fra"},
			wantPath:   "derp",
			wantDetail: "FRA",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := pingDocumentFromResult("100.64.0.2", test.result)
			if got.Path != test.wantPath {
				t.Fatalf("path = %q, want %q", got.Path, test.wantPath)
			}
			detail := got.DirectEndpoint + got.PeerRelayEndpoint + got.RelayRegion
			if detail != test.wantDetail {
				t.Fatalf("detail = %q, want %q", detail, test.wantDetail)
			}
			if got.LatencyMillis == nil {
				t.Fatal("successful ping omitted latency")
			}
		})
	}
}

func TestPingDocumentRedactsFailures(t *testing.T) {
	got := pingDocumentFromResult(
		"100.64.0.2",
		&ipnstate.PingResult{Err: "request timeout while contacting 100.64.0.2"},
	)
	if got.Failure != "timeout" {
		t.Fatalf("failure = %q", got.Failure)
	}
	if got.LatencyMillis != nil {
		t.Fatal("failed ping included latency")
	}
}
