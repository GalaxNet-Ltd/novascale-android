// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

internal enum class BrowserAccess { READY, STARTING, DISABLED, TAILNET_REQUIRED, FAILED, UNSUPPORTED }

internal fun browserAccess(state: ProxyUiState, activeProfileId: String?): BrowserAccess = when (state.status) {
    ProxyStatus.RUNNING -> if (activeProfileId != null && state.session?.profileId == activeProfileId)
        BrowserAccess.READY else BrowserAccess.STARTING
    ProxyStatus.STARTING -> BrowserAccess.STARTING
    ProxyStatus.FAILED -> BrowserAccess.FAILED
    ProxyStatus.STOPPED -> when (state.stopReason) {
        ProxyStopReason.DISABLED -> BrowserAccess.DISABLED
        ProxyStopReason.TAILNET_REQUIRED -> BrowserAccess.TAILNET_REQUIRED
        ProxyStopReason.INACTIVE -> BrowserAccess.STARTING
    }
}
