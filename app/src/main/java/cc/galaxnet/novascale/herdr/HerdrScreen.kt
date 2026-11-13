/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import cc.galaxnet.novascale.ui.NovaPrimaryButton
import cc.galaxnet.novascale.ui.NovaDestructiveButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.galaxnet.novascale.NovaScaleTopBar
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.remote.HostKeyConfirmationDialog
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import cc.galaxnet.novascale.terminal.NovaTerminalView
import cc.galaxnet.novascale.terminalfeature.TerminalAccessoryKeyboard
import cc.galaxnet.novascale.terminalfeature.TerminalSettings
import cc.galaxnet.novascale.terminalfeature.TerminalSystemBars
import cc.galaxnet.novascale.terminalfeature.TerminalThemePalette
import cc.galaxnet.novascale.terminalfeature.chromeColors
import cc.galaxnet.novascale.ui.NovaTheme
import cc.galaxnet.novascale.ui.asString
import cc.galaxnet.novascale.ui.resolve

/** The owner retains an attachment on detach and releases it on explicit close. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HerdrScreen(
    controller: HerdrController,
    config: RemoteConnectionConfig,
    settings: TerminalSettings,
    theme: TerminalThemePalette,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    hostName: String = config.host,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            controller.setPresented(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.setPresented(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.setPresented(false) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val chrome = remember(theme) { theme.chromeColors() }
    var terminal by remember(controller) { mutableStateOf<NovaTerminalView?>(null) }
    var initialInputView by remember(controller) { mutableStateOf<NovaTerminalView?>(null) }
    var terminalFocused by remember(controller) { mutableStateOf(false) }
    val accessoryVisible = imeVisible && terminalFocused
    var displayedTarget by remember(controller) { mutableStateOf<HerdrInputTarget?>(null) }
    var closeRequested by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var restoreTerminalFocus by remember { mutableStateOf(false) }
    var selectedSession by rememberSaveable(controller) { mutableStateOf("default") }
    val detach = {
        terminal?.releaseTerminalInput()
        keyboard?.hide()
        onDetach()
    }
    val leave = { if (state.sessionName != null) detach() else onClose() }
    BackHandler {
        if (imeVisible) { terminal?.releaseTerminalInput(); keyboard?.hide() }
        else leave()
    }

    LaunchedEffect(controller) { controller.connectIfNeeded(config) }
    LaunchedEffect(controller, theme) { controller.applyTheme(theme.toNativeTheme()) }
    LaunchedEffect(controller, settings.herdrImproveTextContrast) { controller.setImproveTextContrast(settings.herdrImproveTextContrast) }
    LaunchedEffect(terminal, windowFocused, state.connected, state.recovering, state.snapshotTarget != null) {
        val view = terminal ?: return@LaunchedEffect
        if (windowFocused && state.connected && !state.recovering && state.snapshotTarget != null && initialInputView !== view) {
            // Like iOS's first-responder request, activate once per presentation.
            // Later IME dismissals and foreground returns must not reopen it.
            initialInputView = view
            view.requestTerminalInput()
        }
    }
    LaunchedEffect(accessoryVisible) {
        if (!accessoryVisible) {
            control = false; alt = false; shift = false
            terminal?.setOneShotModifiers(false, false, false)
        }
    }
    LaunchedEffect(restoreTerminalFocus, state.actionPending, state.navigation.ready, state.snapshotTarget, displayedTarget, state.actionError) {
        if (restoreTerminalFocus && !state.recovering && state.actionPending == null && state.navigation.ready && state.actionError == null &&
            state.snapshotTarget != null && displayedTarget == state.snapshotTarget) {
            terminal?.requestTerminalInput(showSoftwareKeyboard = false)
            restoreTerminalFocus = false
        }
    }
    LaunchedEffect(controller) {
        controller.clipboardWrites.collect { text ->
            terminal?.takeIf { it.isShown && it.hasWindowFocus() && it.isFocused }?.copyRemoteText(text)
        }
    }
    LaunchedEffect(controller, context) {
        controller.notices.collect { notice ->
            android.widget.Toast.makeText(context, notice.resolve(context), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(controller) {
        onDispose {
            terminal?.setTerminalCallbacks(null, null)
            terminal?.releaseTerminalInput()
            terminal = null
            displayedTarget = null
        }
    }
    if (closeRequested) AlertDialog(onDismissRequest = { closeRequested = false },
        title = { Text(stringResource(R.string.herdr_close_title)) },
        text = { Text(stringResource(R.string.herdr_close_description)) },
        confirmButton = { NovaDestructiveButton(onClick = { closeRequested = false; terminal?.releaseTerminalInput(); keyboard?.hide(); onClose() }) {
            Text(stringResource(R.string.herdr_close))
        } },
        dismissButton = { TextButton(onClick = { closeRequested = false }) { Text(stringResource(R.string.action_cancel)) } })

    state.actionError?.let { message ->
        AlertDialog(onDismissRequest = controller::dismissActionError,
            title = { Text(stringResource(R.string.herdr_action_error)) }, text = { Text(message.asString()) },
            confirmButton = { TextButton(onClick = controller::dismissActionError) { Text(stringResource(R.string.action_done)) } },
            dismissButton = { TextButton(onClick = { controller.dismissActionError(); controller.reconnect(config) }) { Text(stringResource(R.string.herdr_reconnect)) } })
    }

    if ((!state.connected && !state.recovering) || state.snapshot == null) {
        Surface(Modifier.fillMaxSize(), color = NovaTheme.colors.backgroundPrimary) {
            Column(Modifier.fillMaxSize().imePadding()) {
                NovaScaleTopBar(stringResource(R.string.herdr_title), showBack = true, onBack = leave)
                if (state.sessionName == null) {
                    Text(hostName, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                } else {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(hostName, style = MaterialTheme.typography.titleMedium)
                        Text(state.status.asString(), style = MaterialTheme.typography.bodyMedium)
                        if (state.busy) CircularProgressIndicator()
                        else if (state.hostKeyPrompt == null) {
                            NovaPrimaryButton(onClick = { controller.reconnect(config) }) { Text(stringResource(R.string.herdr_reconnect)) }
                            TextButton(onClick = { controller.discover(config) }) { Text(stringResource(R.string.herdr_choose_another)) }
                        }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
        if (state.sessionName == null) HerdrSessionPicker(hostName, state.sessions, controller.executable,
            selected = selectedSession, onSelect = { selectedSession = it }, onConnect = controller::selectSession,
            onRefresh = { controller.discover(config) }, onExecutable = { controller.discover(config, it) },
            onDismiss = onClose, ready = state.choosingSession, busy = state.busy,
            status = state.status.asString().takeIf { !state.choosingSession },
            allowanceContent = {
                controller.usageState?.let { usage ->
                    val allowance by usage.collectAsStateWithLifecycle()
                    val pro by (context.applicationContext as cc.galaxnet.novascale.NovaScaleApplication).billing.manager.state.collectAsStateWithLifecycle()
                    if (!pro.hasPro && allowance.loaded) {
                        Text(stringResource(R.string.pro_herdr_remaining, allowance.remaining, allowance.reserved),
                            style = MaterialTheme.typography.bodySmall)
                        if (allowance.remaining == 0) Text(stringResource(R.string.pro_herdr_reset,
                            java.time.LocalDate.ofEpochDay(allowance.day + 1).toString()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            })
        // Keep explicit SSH trust above the connection sheet, including discovery failures.
        state.hostKeyPrompt?.let { prompt ->
            HostKeyConfirmationDialog(prompt, config.endpoint, controller::acceptHostKey, controller::rejectHostKey)
        }
        return
    }
    TerminalSystemBars(chrome)
    CompositionLocalProvider(LocalContentColor provides chrome.foreground) {
        Surface(Modifier.fillMaxSize(), color = chrome.background, contentColor = chrome.foreground) {
            Column(Modifier.fillMaxSize().imePadding()) {
                HerdrNativeControls(
                    state.navigation, state.recovering || state.actionPending != null, hostName,
                    state.sessionName ?: stringResource(R.string.herdr_default_session), chrome,
                    connectionStatus = if (state.recovering) stringResource(R.string.herdr_restoring) else null,
                    onAction = { action, boot ->
                        control = false; alt = false; shift = false
                        terminal?.setOneShotModifiers(false, false, false)
                        restoreTerminalFocus = true
                        controller.performAction(action, boot)
                    },
                    onDetach = detach,
                    onKeyboard = { terminal?.requestTerminalInput(showSoftwareKeyboard = true) },
                    onClose = { closeRequested = true }, onReconnect = { controller.reconnect(config) },
                )
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { NovaTerminalView(it).also { view -> terminal = view } },
                    update = { view ->
                        terminal = view
                        view.setOnFocusChangeListener { _, focused -> terminalFocused = focused }
                        terminalFocused = view.isFocused
                        view.applyAppearance(
                            token = "${theme.id}:${settings.fontId}:${settings.fontSizeSp}:${settings.cursorShapeId}",
                            typeface = settings.font.typeface(view.context), textSizeSp = settings.fontSizeSp,
                            cursorArgb = theme.cursor, cursorShape = settings.cursorShape.shapeOverride,
                            selectionArgb = theme.selection, selectedTextArgb = theme.selectedText,
                        )
                        state.snapshot?.let(view::submitSnapshot)
                        val target = state.snapshotTarget.takeUnless { state.recovering }
                        displayedTarget = target
                        view.setOneShotModifiers(control, alt, shift)
                        view.setTerminalCallbacks(onInput = { bytes -> controller.send(bytes, target) },
                            onRemoteScroll = { bytes -> controller.send(bytes, target) },
                            onResize = null, onResizePixels = controller::resize, resizeToken = controller,
                            inputToken = target,
                            onModifiersConsumed = { control = false; alt = false; shift = false })
                    },
                    onRelease = { view ->
                        view.releaseTerminalInput()
                        view.setOnFocusChangeListener(null)
                        view.setTerminalCallbacks(null, null)
                        if (initialInputView === view) initialInputView = null
                        if (terminal === view) { terminal = null; displayedTarget = null; terminalFocused = false }
                    },
                )
                if (accessoryVisible) TerminalAccessoryKeyboard(
                    colors = chrome, controlActive = control, altActive = alt, shiftActive = shift,
                    bracketedPasteEnabled = state.snapshot?.bracketedPasteEnabled == true,
                    keyboardFlags = state.snapshot?.keyboardEnhancementFlags ?: 0,
                    onDetach = detach, onCloseSession = { closeRequested = true },
                    onModifiersChanged = { ctrl, nextAlt, nextShift ->
                        control = ctrl; alt = nextAlt; shift = nextShift
                        terminal?.setOneShotModifiers(ctrl, nextAlt, nextShift)
                    },
                    onSend = { controller.send(it, displayedTarget) }, onPaste = { terminal?.pasteFromClipboard() },
                    onKeepTerminalFocus = { terminal?.requestTerminalInput(showSoftwareKeyboard = false) },
                    onCollapseInput = { terminal?.releaseTerminalInput(); keyboard?.hide() },
                )
            }
        }
    }
}
