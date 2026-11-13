// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TailnetPeer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostWebAccessSheet(peer: TailnetPeer, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var address by rememberSaveable(peer) { mutableStateOf(browserHostAddress(peer.dnsName, peer.addresses)) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    fun open() {
        val url = runCatching { browserUrl(address) }.getOrNull()
        if (url == null) invalid = true else onOpen(url)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Language, null, Modifier.align(Alignment.CenterHorizontally).size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.host_web_access), Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.titleLarge)
            Text(peer.displayName, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(address, { address = it; invalid = false }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.browser_address)) }, isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { open() }))
            if (invalid) Text(stringResource(R.string.browser_invalid_url), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.web_quick_ports), style = MaterialTheme.typography.labelMedium)
            listOf(80, 443, 8080, 3000, 8123, 9090, 8096, 8888).chunked(4).forEach { ports ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ports.forEach { port ->
                        OutlinedButton(onClick = {
                            address = hostWebAddressWithPort(address, port)
                            invalid = false
                        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text(port.toString()) }
                    }
                }
            }
            NovaPrimaryButton(onClick = ::open, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.web_open_browser)) }
        }
    }
}

internal fun hostWebAddressWithPort(address: String, port: Int): String {
    val uri = runCatching { java.net.URI(browserUrl(address)) }.getOrNull() ?: return address
    val scheme = when (port) { 443 -> "https"; 80 -> "http"; else -> uri.scheme }
    val origin = java.net.URI(scheme, null, uri.host, if (port == 80 || port == 443) -1 else port,
        null, null, null).toASCIIString()
    return origin + uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "") +
        (uri.rawFragment?.let { "#$it" } ?: "")
}
