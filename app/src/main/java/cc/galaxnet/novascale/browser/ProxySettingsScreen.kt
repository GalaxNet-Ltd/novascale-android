// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.galaxnet.novascale.AppPreferences
import cc.galaxnet.novascale.R
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal class ProxySettingsViewModel(private val preferences: AppPreferences, val controller: LocalProxyController) : ViewModel() {
    val settings = preferences.proxySettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySettings())
    val error = MutableStateFlow(false)
    fun enable(value: Boolean) = save { preferences.setProxyEnabled(value) }
    fun background(value: Boolean) = save { preferences.setProxyBackground(value) }
    fun port(value: String) {
        val port = if (value.isBlank()) 0 else value.toIntOrNull()
        if (port == null || port !in 0..65535) { error.value = true; return }
        save { preferences.setProxyPort(port) }
    }
    private fun save(action: suspend () -> Unit) { viewModelScope.launch { try { action(); error.value = false } catch (_: Exception) { error.value = true } } }
    class Factory(private val preferences: AppPreferences, private val controller: LocalProxyController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ProxySettingsViewModel(preferences, controller) as T
    }
}

@Composable
internal fun ProxySettingsScreen(preferences: AppPreferences, controller: LocalProxyController) {
    val model: ProxySettingsViewModel = viewModel(factory = remember { ProxySettingsViewModel.Factory(preferences, controller) })
    val settings by model.settings.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var port by remember(settings.port) { mutableStateOf(if (settings.port == 0) "" else settings.port.toString()) }
    var revealed by remember(state.session) { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) revealed = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val passwordLabel = stringResource(R.string.proxy_password)
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.proxy_description))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.proxy_enabled)); Switch(settings.enabled, onCheckedChange = model::enable)
        }
        Text(stringResource(when (state.status) {
            ProxyStatus.RUNNING -> R.string.proxy_running
            ProxyStatus.STARTING -> R.string.browser_proxy_starting
            ProxyStatus.STOPPED -> if (state.stopReason == ProxyStopReason.DISABLED) R.string.browser_proxy_disabled_title else R.string.proxy_stopped
            ProxyStatus.FAILED -> R.string.proxy_failed
        }))
        if (state.status == ProxyStatus.FAILED) TextButton(onClick = controller::retry) { Text(stringResource(R.string.proxy_retry)) }
        state.session?.let { session ->
            Text(session.endpoint.address, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.proxy_protocols))
            if (session.endpoint.usedFallback) Text(stringResource(R.string.proxy_port_fallback, settings.port, session.endpoint.address), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.proxy_username, session.username))
            Text(if (revealed) session.password else "••••••••••••", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { revealed = !revealed }) { Text(stringResource(if (revealed) R.string.proxy_hide_password else R.string.proxy_show_password)) }
                TextButton(onClick = {
                    val clip = ClipData.newPlainText(passwordLabel, session.password)
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }) { Text(stringResource(R.string.proxy_copy_password)) }
            }
        }
        OutlinedTextField(port, onValueChange = { port = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.proxy_preferred_port)) }, supportingText = { Text(stringResource(R.string.proxy_port_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = error)
        if (error) Text(stringResource(R.string.proxy_settings_error), color = MaterialTheme.colorScheme.error)
        NovaPrimaryButton(onClick = { requestNotificationPermission(); model.port(port) }) { Text(stringResource(R.string.proxy_apply)) }
        Text(stringResource(R.string.proxy_apply_description), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.proxy_background), modifier = Modifier.weight(1f))
            Switch(settings.background, onCheckedChange = { if (it) requestNotificationPermission(); model.background(it) })
        }
        Text(stringResource(R.string.proxy_background_description), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirmRotate = true }) { Text(stringResource(R.string.proxy_regenerate)) }
    }
    if (confirmRotate) AlertDialog(onDismissRequest = { confirmRotate = false },
        title = { Text(stringResource(R.string.proxy_regenerate)) }, text = { Text(stringResource(R.string.proxy_regenerate_description)) },
        confirmButton = { TextButton(onClick = { confirmRotate = false; controller.regeneratePassword() }) { Text(stringResource(R.string.proxy_regenerate)) } },
        dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.proxy_cancel)) } })
}
