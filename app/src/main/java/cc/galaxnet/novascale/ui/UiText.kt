/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Keeps long-lived ViewModel state locale-neutral. Resource-backed values are
 * resolved only when rendered, so a system locale change updates a live screen
 * without reconnecting SSH/SFTP or rebuilding its state.
 */
internal sealed interface UiText {
    data class Resource(
        @StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @PluralsRes val id: Int,
        val quantity: Int,
        val arguments: List<Any> = listOf(quantity),
    ) : UiText

    /** Text supplied by a remote system or lower-level library without a stable error code. */
    data class Dynamic(val value: String) : UiText
}

internal fun uiText(@StringRes id: Int, vararg arguments: Any): UiText =
    UiText.Resource(id, arguments.toList())

internal fun uiPlural(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): UiText =
    UiText.Plural(
        id = id,
        quantity = quantity,
        arguments = if (arguments.isEmpty()) listOf(quantity) else arguments.toList(),
    )

internal fun String.asUiText(): UiText = UiText.Dynamic(this)

@Composable
internal fun UiText.asString(): String = when (this) {
    is UiText.Dynamic -> value
    is UiText.Resource -> stringResource(id, *arguments.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *arguments.toTypedArray())
}

internal fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Dynamic -> value
    is UiText.Resource -> context.getString(id, *arguments.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(
        id,
        quantity,
        *arguments.toTypedArray(),
    )
}
