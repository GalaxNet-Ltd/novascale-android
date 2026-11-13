// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaPrimaryButton
import cc.galaxnet.novascale.ui.NovaSecondaryButton

/** Editing the first profile is a replacement, not an additional tailnet slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoginServerSheet(server: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(server, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(server, TextRange(0, server.length)))
    }
    val normalized = value.text.takeIf { it.isNotBlank() }?.let {
        runCatching { validatedControlServer(it) }.getOrNull()
    }
    val invalid = value.text.isNotEmpty() && normalized == null
    val changed = normalized != null && normalized != validatedControlServer(server)
    val scroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = !scroll.canScrollBackward) {
        Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.login_server_edit_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value, { value = it.copy(text = it.text.take(2048)) },
                    Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.label_control_server)) },
                    placeholder = { Text("https://headscale.example") }, isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    trailingIcon = {
                        if (value.text.isNotEmpty()) IconButton(onClick = { value = TextFieldValue() }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.login_server_clear))
                        }
                    },
                    supportingText = { Text(stringResource(if (invalid) R.string.login_server_invalid else R.string.control_server_description)) })
                TextButton(onClick = { value = TextFieldValue(DEFAULT_CONTROL_SERVER, TextRange(0, DEFAULT_CONTROL_SERVER.length)) },
                    enabled = value.text != DEFAULT_CONTROL_SERVER) { Text(stringResource(R.string.auth_reset_server)) }
                Text(stringResource(R.string.pro_replace_tailnet_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovaSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                NovaPrimaryButton(onClick = { normalized?.let(onSave) }, enabled = changed,
                    modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
