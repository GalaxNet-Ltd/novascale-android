/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.res.stringResource
import cc.galaxnet.novascale.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cc.galaxnet.novascale.NovaScaleTopBar
import cc.galaxnet.novascale.remote.GroupHostKey
import cc.galaxnet.novascale.remote.HostFilterMember
import cc.galaxnet.novascale.remote.HostFilterSheetContent
import cc.galaxnet.novascale.remote.HostFilterToolbar
import cc.galaxnet.novascale.remote.HostGroup
import cc.galaxnet.novascale.remote.HostGroupsState
import cc.galaxnet.novascale.remote.rememberHostGroupFilter

/** Exercises production toolbar, sheet content and selection state with synthetic hosts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostFilterHarness(
    state: HostGroupsState,
    members: List<HostFilterMember> = emptyList(),
    onCreate: () -> Unit = {},
    onOpenMember: (GroupHostKey) -> Unit = {},
    onRemoveMember: (HostGroup, HostFilterMember) -> Unit = { _, _ -> },
    onAdd: () -> Unit = {},
    content: @Composable (String) -> Unit,
) {
    var selected by rememberHostGroupFilter(state, storageError = false)
    var showing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        NovaScaleTopBar("Hosts", showBack = false, onBack = {},
            leading = { HostFilterToolbar(selected, state.groups) { showing = true } },
            actions = { IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, stringResource(R.string.traditional_host_add)) } })
        content(selected)
    }
    if (showing) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showing = false }, sheetState = sheetState) {
            HostFilterSheetContent(state, storageError = false, selected, members,
                onSelect = { selected = it; showing = false },
                onOpenMember = { showing = false; onOpenMember(it) },
                onCreate = { onCreate(); showing = false }, onRename = {}, onDelete = {},
                onRemoveMember = onRemoveMember, onDismiss = { showing = false })
        }
    }
}
