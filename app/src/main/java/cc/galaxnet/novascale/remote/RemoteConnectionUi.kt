/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.remote

import cc.galaxnet.novascale.ui.NovaSecondaryButton

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetPeer

@Composable
internal fun RemoteConnectionForm(
    config: RemoteConnectionConfig,
    onConfigChange: (RemoteConnectionConfig) -> Unit,
    peers: List<TailnetPeer>,
    enabled: Boolean,
    connectLabel: String,
    onConnect: () -> Unit,
    showHostField: Boolean = true,
    showSubmit: Boolean = true,
) {
    var peerMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (peers.isNotEmpty()) {
            Box {
                TextButton(onClick = { peerMenu = true }, enabled = enabled) {
                    Text(stringResource(R.string.choose_tailnet_peers, peers.size))
                }
                DropdownMenu(expanded = peerMenu, onDismissRequest = { peerMenu = false }) {
                    peers.forEach { peer ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(peer.displayName)
                                    Text(
                                        peer.dnsName ?: peer.addresses.firstOrNull().orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = {
                                val host = peer.dnsName ?: peer.addresses.firstOrNull().orEmpty()
                                if (host.isNotEmpty()) onConfigChange(config.copy(host = host.trimEnd('.')))
                                peerMenu = false
                            },
                        )
                    }
                }
            }
        }
        if (showHostField) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = config.host,
                    onValueChange = { onConfigChange(config.copy(host = it)) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_host)) },
                )
                OutlinedTextField(
                    value = config.port,
                    onValueChange = { onConfigChange(config.copy(port = it.filter(Char::isDigit).take(5))) },
                    modifier = Modifier.weight(0.35f),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        } else {
            OutlinedTextField(
                value = config.port,
                onValueChange = { onConfigChange(config.copy(port = it.filter(Char::isDigit).take(5))) },
                modifier = Modifier.widthIn(max = 160.dp),
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.label_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        OutlinedTextField(
            value = config.username,
            onValueChange = { onConfigChange(config.copy(username = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.label_ssh_username)) },
        )
        Text(stringResource(R.string.label_authentication), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthenticationKind.entries.forEach { kind ->
                FilterChip(
                    selected = config.authenticationKind == kind,
                    onClick = { onConfigChange(config.copy(authenticationKind = kind)) },
                    enabled = enabled,
                    label = {
                        Text(stringResource(
                            when (kind) {
                                AuthenticationKind.NONE -> R.string.auth_none
                                AuthenticationKind.PASSWORD -> R.string.auth_password
                                AuthenticationKind.PRIVATE_KEY -> R.string.auth_key_short
                            },
                        ))
                    },
                )
            }
        }
        if (config.authenticationKind == AuthenticationKind.NONE) {
            Text(
                stringResource(R.string.auth_none_description),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (config.authenticationKind == AuthenticationKind.PASSWORD) {
            OutlinedTextField(
                value = config.password,
                onValueChange = { onConfigChange(config.copy(password = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.label_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        if (config.authenticationKind == AuthenticationKind.PRIVATE_KEY) {
            OutlinedTextField(
                value = config.privateKey,
                onValueChange = { onConfigChange(config.copy(privateKey = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 4,
                maxLines = 8,
                label = { Text(stringResource(R.string.label_openssh_private_key)) },
                supportingText = { Text(stringResource(R.string.private_key_storage_description)) },
            )
            OutlinedTextField(
                value = config.passphrase,
                onValueChange = { onConfigChange(config.copy(passphrase = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.label_key_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        if (showSubmit) NovaPrimaryButton(onClick = onConnect, enabled = enabled) {
            Text(connectLabel)
        }
    }
}

@Composable
internal fun HostKeyConfirmationDialog(
    prompt: HostKeyPrompt,
    endpoint: String,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(stringResource(
                if (prompt.changed) R.string.host_key_changed_title else R.string.trust_host_key_title,
            ))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (prompt.changed) {
                        stringResource(R.string.host_key_changed_description, endpoint)
                    } else {
                        stringResource(R.string.host_key_verify_description, endpoint)
                    },
                )
                prompt.algorithm?.let { Text(stringResource(R.string.host_key_algorithm, it)) }
                Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall)
                prompt.expectedFingerprint?.let {
                    Text(
                        stringResource(R.string.host_key_previously_trusted, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            NovaPrimaryButton(onClick = onTrust) {
                Text(stringResource(
                    if (prompt.changed) R.string.trust_new_key else R.string.trust_and_reconnect,
                ))
            }
        },
        dismissButton = {
            NovaSecondaryButton(onClick = onReject) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
