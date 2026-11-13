/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cc.galaxnet.novascale.AppPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class FilePreviewSettingsViewModel(
    private val preferences: AppPreferences,
) : ViewModel() {
    val settings: StateFlow<FilePreviewSettings> = preferences.filePreviewSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = FilePreviewSettings(),
    )

    fun setTapToPreview(enabled: Boolean) {
        viewModelScope.launch { preferences.setFileTapToPreview(enabled) }
    }

    fun setSizeLimit(limit: FilePreviewSizeLimit) {
        viewModelScope.launch { preferences.setFilePreviewSizeLimit(limit) }
    }

    class Factory(
        private val preferences: AppPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FilePreviewSettingsViewModel(preferences) as T
    }
}
