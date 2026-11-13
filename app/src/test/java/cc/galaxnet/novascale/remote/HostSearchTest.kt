/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import org.junit.Assert.*
import org.junit.Test

class HostSearchTest {
    @Test fun searchCombinesNameAndAddressTerms() {
        assertTrue(hostMatchesSearch("  PROD  2222 ", "Production", "deploy.example:2222"))
        assertTrue(hostMatchesSearch("", "Production", "deploy.example"))
        assertTrue(hostMatchesSearch("2001:db8", "IPv6 host", "2001:db8::1"))
        assertFalse(hostMatchesSearch("prod 192.168", "Production", "100.64.0.2"))
        assertFalse(hostMatchesSearch("staging", "Production", "deploy.example"))
    }
}
