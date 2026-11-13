// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

package novatailnet

import (
	"encoding/json"
	"runtime"
)

const (
	apiVersion        = 1
	tailscaleVersion  = "v1.102.4"
	gomobileVersion   = "v0.0.0-20260709172247-6129f5bee9d5"
	nativeBuildTarget = "android/arm64,android/amd64"
)

// BuildInfoJSON returns non-sensitive dependency and runtime provenance.
func BuildInfoJSON() string {
	value := struct {
		APIVersion       int    `json:"apiVersion"`
		TailscaleVersion string `json:"tailscaleVersion"`
		GomobileVersion  string `json:"gomobileVersion"`
		GoVersion        string `json:"goVersion"`
		BuildTarget      string `json:"buildTarget"`
		RemoteLogging    bool   `json:"remoteLogging"`
	}{
		APIVersion:       apiVersion,
		TailscaleVersion: tailscaleVersion,
		GomobileVersion:  gomobileVersion,
		GoVersion:        runtime.Version(),
		BuildTarget:      nativeBuildTarget,
		RemoteLogging:    false,
	}
	data, _ := json.Marshal(value)
	return string(data)
}
