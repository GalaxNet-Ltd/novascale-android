package cc.galaxnet.novascale.tailnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetProfilesTest {
    @Test
    fun officialControlAliasesUseTheDefaultProfile() {
        assertNull(normalizeControlUrl("https://login.tailscale.com/"))
        assertNull(normalizeControlUrl("https://controlplane.tailscale.com"))
    }

    @Test
    fun customControlServerIsPreserved() {
        assertEquals("https://headscale.example/base", normalizeControlUrl(" https://headscale.example/base/ "))
    }
    @Test fun serverValidationRejectsAmbiguousOrCredentialBearingUrls() {
        for (raw in listOf("http://example.test", "https://user:password@example.test", "https://example.test/?token=abc",
            "https://example.test/#fragment", "https://example.test:0", "https://example.test:65536", "example.test")) {
            assertTrue(raw, runCatching { validatedControlServer(raw) }.isFailure)
        }
        assertEquals(DEFAULT_CONTROL_SERVER, validatedControlServer("https://controlplane.tailscale.com/"))
        assertEquals("https://example.test/Headscale", validatedControlServer("https://example.test/Headscale/"))
    }

    @Test fun namesSupportUnicodeButNotEmptyOrControlCharacters() {
        assertEquals("工作网络", validatedTailnetName(" 工作网络 "))
        for (raw in listOf("  ", "x\ny", "a".repeat(81))) {
            assertTrue(runCatching { validatedTailnetName(raw) }.isFailure)
        }
    }

}
