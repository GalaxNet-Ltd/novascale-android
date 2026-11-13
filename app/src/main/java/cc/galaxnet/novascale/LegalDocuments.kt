/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale

import androidx.annotation.StringRes
import java.net.URI

internal enum class PackagedLegalDocument(
    @StringRes val titleRes: Int,
    val assetPath: String,
) {
    APPLICATION_LICENSE(
        titleRes = R.string.application_license,
        assetPath = "legal/Apache-2.0.txt",
    ),
    THIRD_PARTY_NOTICES(
        titleRes = R.string.third_party_notices,
        assetPath = "legal/THIRD_PARTY_NOTICES.md",
    ),
}

internal fun validReleaseWebUrl(rawUrl: String): String? {
    val value = rawUrl.trim()
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return value.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }
}
