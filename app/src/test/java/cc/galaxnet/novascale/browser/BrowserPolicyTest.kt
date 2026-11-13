// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser
import org.junit.Assert.*
import org.junit.Test
class BrowserPolicyTest {
    @Test fun proxyUsesTheSelectedTailnetIdWhileCredentialsRemainNodeScoped() {
        val identity = ProxyIdentity("work-profile", "device-node")
        val session = identity.session(cc.galaxnet.novascale.core.LocalProxyEndpoint("proxy-1", "127.0.0.1:9000", false), "user", "test-secret", "realm")
        val running = ProxyUiState(ProxyStatus.RUNNING, session)
        assertEquals(BrowserAccess.READY, browserAccess(running, "work-profile"))
        assertEquals(BrowserAccess.STARTING, browserAccess(running, "personal-profile"))
        assertEquals(BrowserAccess.STARTING, browserAccess(running, null))
        assertEquals("work-profile:device-node", identity.credentialKey)
        assertNotEquals(identity.credentialKey, identity.copy(nodeId = "replacement-node").credentialKey)
        assertFalse(session.toString().contains("test-secret"))
    }

    @Test fun disabledDisconnectedAndFailedProxyStatesHaveDistinctRecovery() {
        assertEquals(BrowserAccess.DISABLED, browserAccess(ProxyUiState(stopReason = ProxyStopReason.DISABLED), "work"))
        assertEquals(BrowserAccess.TAILNET_REQUIRED, browserAccess(ProxyUiState(), "work"))
        assertEquals(BrowserAccess.STARTING, browserAccess(ProxyUiState(stopReason = ProxyStopReason.INACTIVE), "work"))
        assertEquals(BrowserAccess.STARTING, browserAccess(ProxyUiState(ProxyStatus.STARTING), "work"))
        assertEquals(BrowserAccess.FAILED, browserAccess(ProxyUiState(ProxyStatus.FAILED), "work"))
    }

    @Test fun hostSheetPortsChooseSchemeAndPreserveEncodedComponents() {
        assertEquals("https://grafana/dashboard?q=a%26b#x", hostWebAddressWithPort("http://grafana:3000/dashboard?q=a%26b#x", 443))
        assertEquals("http://[fd7a:115c:a1e0::1]:8096/web", hostWebAddressWithPort("http://[fd7a:115c:a1e0::1]/web", 8096))
        assertEquals("http://grafana/", hostWebAddressWithPort("https://grafana:3000/", 80))
    }

    @Test fun hostLinksUseShortNamesAndBracketIpv6Fallbacks() {
        assertEquals("http://grafana", browserHostAddress("grafana.tail123.ts.net.", listOf("100.64.0.1")))
        assertEquals("http://jellyfin", browserHostAddress("jellyfin.internal.example", emptyList()))
        assertEquals("http://[fd7a:115c:a1e0::1]", browserHostAddress(null, listOf("fd7a:115c:a1e0::1")))
    }
    @Test fun tabsKeepIndependentAddressAndNavigationState() {
        val model = BrowserViewModel()
        assertTrue(model.tabs.isEmpty())
        assertNull(model.open())
        assertTrue(model.tabs.isEmpty())
        val first = model.active
        model.address("grafana:3000")
        first.requestedUrl = model.open()
        model.newTab("http://jellyfin:8096")
        val second = model.active
        assertNull(second.requestedUrl)
        model.select(first)
        assertEquals("http://grafana:3000", model.state.value.url)
        assertEquals("grafana:3000", model.state.value.address)
        model.close(first)
        assertSame(second, model.active)
        model.close(second)
        assertEquals(0, model.tabs.size)
        assertEquals("http://", model.state.value.address)
        assertNull(model.active.requestedUrl)
    }
    @Test fun portPresetsPreserveHostAndPath() {
        assertEquals("http://grafana:3000/", browserPortUrl("http://grafana:8080", 3000))
        assertEquals("https://[fd7a:115c:a1e0::1]:8096/web/", browserPortUrl("https://[fd7a:115c:a1e0::1]/web/", 8096))
        assertEquals("https://example.com:9090/a%20b", browserPortUrl("https://example.com/a%20b", 9090))
        assertNull(browserPortUrl("https://", 8080))
    }

    @Test fun changingPortOrSchemePreservesEncodedUrlComponents() {
        assertEquals("https://host:8443/a%2Fb?q=a%26b#x%20y", browserPortUrl("https://host/a%2Fb?q=a%26b#x%20y", 8443))
        assertEquals("https://host/a%2Fb?q=a%26b#x%20y", browserChangeScheme("http://host/a%2Fb?q=a%26b#x%20y", "https"))
    }
    @Test fun profileChangeDiscardsPagesAndDrafts() {
        val model = BrowserViewModel()
        model.bindProfile("first")
        model.address("http://private/dashboard")
        model.active.requestedUrl = model.open()
        model.bindProfile("first")
        assertEquals(1, model.tabs.size)
        model.bindProfile("second")
        assertTrue(model.tabs.isEmpty())
        assertEquals("http://", model.state.value.address)
        assertNull(model.active.requestedUrl)
    }
    @Test fun suggestionsAreLocalBoundedAndMatchTitleOrAddress() {
        val sites = (1..20).map { BrowserSite("$it", "http://site$it/", "Dashboard $it", it.toLong(), it == 2) }
        assertEquals(8, browserSuggestions(sites, "http://").size)
        assertEquals("2", browserSuggestions(sites, "dashboard").first().id)
        assertEquals("http://site19/", browserSuggestions(sites, "site19").single().url)
        assertTrue(browserSuggestions(sites, "missing").isEmpty())
    }
    @Test fun acceptsTailnetAndInternetAddresses() {
        assertEquals("http://grafana:3000", browserUrl("grafana:3000"))
        assertEquals("http://100.64.0.42:8096/web/", browserUrl("http://100.64.0.42:8096/web/"))
        assertEquals("https://example.com/", browserUrl(" https://example.com/ "))
    }
    @Test fun rejectsCredentialsLocalhostAndNonWebSchemes() {
        for (value in listOf("https://user:password@host", "http://127.0.0.2:8080", "http://[::1]", "http://localhost", "file:///data/x", "https://host:65536", "https://host:0")) {
            assertThrows(value, IllegalArgumentException::class.java) { browserUrl(value) }
        }
    }
}
