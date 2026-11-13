/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegalDocumentsTest {
    @Test
    fun packagedDocumentsUseUniqueLegalAssetPaths() {
        val paths = PackagedLegalDocument.entries.map { it.assetPath }

        assertEquals(paths.size, paths.toSet().size)
        assertEquals(
            setOf(
                "legal/Apache-2.0.txt",
                "legal/THIRD_PARTY_NOTICES.md",
            ),
            paths.toSet(),
        )
    }

    @Test
    fun releaseLinksRequireCredentialFreeHttpsUrls() {
        assertEquals(
            "https://galaxnet.dev/nova/legal/privacy/",
            validReleaseWebUrl(" https://galaxnet.dev/nova/legal/privacy/ "),
        )
        assertNull(validReleaseWebUrl(""))
        assertNull(validReleaseWebUrl("http://galaxnet.dev/nova/legal/privacy/"))
        assertNull(validReleaseWebUrl("https://user:password@example.com/source"))
        assertNull(validReleaseWebUrl("/relative/source"))
        assertNull(validReleaseWebUrl("not a URL"))
    }

    @Test
    fun bundledLegalWebLinksAreSecureAndValid() {
        assertEquals(BuildConfig.PRIVACY_POLICY_URL, validReleaseWebUrl(BuildConfig.PRIVACY_POLICY_URL))
        assertEquals(BuildConfig.TERMS_OF_SERVICE_URL, validReleaseWebUrl(BuildConfig.TERMS_OF_SERVICE_URL))
    }
}
