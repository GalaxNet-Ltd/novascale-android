// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminalfeature

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.TerminalKeyModifiers
import cc.galaxnet.novascale.core.terminalFunctionInput
import cc.galaxnet.novascale.core.terminalNavigationInput
import cc.galaxnet.novascale.core.terminalTextInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun TerminalAccessoryKeyboard(
    colors: TerminalChromeColors,
    controlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    bracketedPasteEnabled: Boolean,
    keyboardFlags: Int,
    onDetach: () -> Unit,
    onCloseSession: () -> Unit,
    onModifiersChanged: (control: Boolean, alt: Boolean, shift: Boolean) -> Unit,
    onSend: (ByteArray) -> Unit,
    onPaste: () -> Unit,
    onKeepTerminalFocus: () -> Unit,
    onCollapseInput: () -> Unit,
) {
    var arrowChoicesVisible by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resumed) {
        if (!resumed) {
            arrowChoicesVisible = false
            onModifiersChanged(false, false, false)
        }
    }

    fun clearModifiers() = onModifiersChanged(false, false, false)
    fun send(value: String, navigation: Boolean = false) {
        arrowChoicesVisible = false
        onKeepTerminalFocus()
        onSend(terminalAccessorySequence(value, controlActive, altActive, navigation, shiftActive, keyboardFlags))
        clearModifiers()
    }
    fun changeModifiers(control: Boolean, alt: Boolean, shift: Boolean) {
        arrowChoicesVisible = false
        onKeepTerminalFocus()
        onModifiersChanged(control, alt, shift)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = colors.background,
        border = BorderStroke(1.dp, colors.border),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)) {
            val compact = maxWidth < 600.dp
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScrollableKeys(colors, Modifier.fillMaxWidth()) {
                    IconKey(colors, Icons.AutoMirrored.Outlined.OpenInNew,
                        stringResource(R.string.cd_detach_terminal), colors.accent, onClick = onDetach)
                    IconKey(colors, Icons.Outlined.Cancel,
                        stringResource(R.string.cd_close_terminal), colors.destructive, onClick = onCloseSession)
                    AccessorySeparator(colors)
                    TextKey(colors, "Esc") { send("\u001b") }
                    TextKey(colors, "Ctl", selected = controlActive) {
                        changeModifiers(!controlActive, altActive, shiftActive)
                    }
                    TextKey(colors, "Alt", selected = altActive) {
                        changeModifiers(controlActive, !altActive, shiftActive)
                    }
                    TextKey(colors, "Tab") { send("\t") }
                    AccessorySeparator(colors)
                    if (compact) {
                        ArrowPad(colors, resumed, arrowChoicesVisible,
                            onToggleChoices = { arrowChoicesVisible = !arrowChoicesVisible },
                            onDismissChoices = { arrowChoicesVisible = false },
                            onArrow = { send(it.suffix, navigation = true) })
                    } else {
                        ArrowDirection.entries.forEach { direction ->
                            TextKey(colors, direction.glyph, description = stringResource(direction.description)) {
                                send(direction.suffix, navigation = true)
                            }
                        }
                    }
                    IconKey(colors, Icons.Outlined.ContentPaste, stringResource(R.string.cd_paste)) {
                        arrowChoicesVisible = false
                        onKeepTerminalFocus()
                        onPaste()
                        clearModifiers()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconKey(colors, ImageVector.vectorResource(R.drawable.ic_terminal_shift), stringResource(R.string.terminal_shift),
                        selected = shiftActive) {
                        changeModifiers(controlActive, altActive, !shiftActive)
                    }
                    IconKey(colors, Icons.AutoMirrored.Outlined.KeyboardReturn,
                        stringResource(R.string.terminal_insert_newline), enabled = bracketedPasteEnabled) {
                        arrowChoicesVisible = false
                        onKeepTerminalFocus()
                        terminalNewlineSequence(bracketedPasteEnabled)?.let(onSend)
                        clearModifiers()
                    }
                    AccessorySeparator(colors)
                    ScrollableKeys(colors, Modifier.weight(1f)) {
                        TerminalSymbols.forEach { symbol -> TextKey(colors, symbol) { send(symbol) } }
                        (1..12).forEach { number -> TextKey(colors, "F$number") { send("F$number") } }
                    }
                    AccessorySeparator(colors)
                    IconKey(colors, Icons.Outlined.KeyboardHide, stringResource(R.string.cd_hide_keyboard)) {
                        arrowChoicesVisible = false
                        clearModifiers()
                        onCollapseInput()
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollableKeys(colors: TerminalChromeColors, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Row(Modifier.fillMaxWidth().padding(end = if (scroll.canScrollForward) 10.dp else 0.dp)
            .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically, content = content)
        if (scroll.canScrollForward) {
            Text("›", color = colors.secondary, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun ArrowPad(
    colors: TerminalChromeColors,
    enabled: Boolean,
    choicesVisible: Boolean,
    onToggleChoices: () -> Unit,
    onDismissChoices: () -> Unit,
    onArrow: (ArrowDirection) -> Unit,
) {
    var direction by remember { mutableStateOf<ArrowDirection?>(null) }
    val currentArrow by rememberUpdatedState(onArrow)
    val currentToggle by rememberUpdatedState(onToggleChoices)
    val currentDismiss by rememberUpdatedState(onDismissChoices)
    val threshold = with(LocalDensity.current) { 8.dp.toPx() }
    val popupOffset = with(LocalDensity.current) { (-54).dp.roundToPx() }
    val navigationLabel = stringResource(R.string.terminal_navigation)
    val hint = stringResource(R.string.terminal_navigation_hint)
    val actions = ArrowDirection.entries.map { arrow ->
        CustomAccessibilityAction(stringResource(arrow.description)) { currentArrow(arrow); true }
    }
    LaunchedEffect(direction, enabled) {
        val repeating = direction ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        delay(600)
        // Direction state changes before Compose cancels/restarts this effect.
        // A delayed frame must not emit an old direction after the new one.
        while (isActive && direction == repeating) {
            currentArrow(repeating)
            delay(100)
        }
    }
    Box {
        Surface(
            modifier = Modifier.size(34.dp, 36.dp)
                .semantics {
                    contentDescription = navigationLabel
                    stateDescription = direction?.glyph ?: hint
                    customActions = actions
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        var dragged = false
                        var released = false
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.count { it.pressed } > 1) break
                                pointer.consume()
                                if (!pointer.pressed) { released = true; break }
                                val next = terminalArrowDirection(pointer.position - down.position, threshold)
                                if (next != null) dragged = true
                                if (next != direction) {
                                    direction = next
                                    if (next != null) {
                                        currentDismiss()
                                        currentArrow(next)
                                    }
                                }
                            } while (true)
                            if (released && !dragged) currentToggle()
                        } finally {
                            direction = null
                        }
                    }
                }
                // Retain keyboard activation and TalkBack's standard click action.
                .clickable(enabled = enabled, role = Role.Button, onClick = onToggleChoices),
            shape = MaterialTheme.shapes.small,
            color = if (direction != null) colors.foreground.copy(alpha = 0.14f) else Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val active = direction
                if (active == null) Icon(Icons.Outlined.OpenWith, null, Modifier.size(16.dp), tint = colors.foreground)
                else Text(active.glyph, color = colors.foreground, fontSize = 16.sp)
            }
        }
        if (choicesVisible && enabled) {
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, popupOffset),
                onDismissRequest = onDismissChoices, properties = PopupProperties(focusable = false)) {
                Surface(color = colors.background, shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, colors.border), shadowElevation = 4.dp) {
                    Row {
                        ArrowDirection.entries.forEach { arrow ->
                            TextKey(colors, arrow.glyph, description = stringResource(arrow.description), height = 48.dp) { currentArrow(arrow) }
                        }
                    }
                }
            }
        }
    }
}

internal enum class ArrowDirection(val suffix: String, val glyph: String, val description: Int) {
    Left("D", "←", R.string.terminal_arrow_left),
    Down("B", "↓", R.string.terminal_arrow_down),
    Up("A", "↑", R.string.terminal_arrow_up),
    Right("C", "→", R.string.terminal_arrow_right),
}

internal fun terminalArrowDirection(translation: Offset, threshold: Float): ArrowDirection? {
    if (max(abs(translation.x), abs(translation.y)) < threshold) return null
    return if (abs(translation.x) > abs(translation.y)) {
        if (translation.x < 0) ArrowDirection.Left else ArrowDirection.Right
    } else if (translation.y < 0) ArrowDirection.Up else ArrowDirection.Down
}

@Composable
private fun IconKey(
    colors: TerminalChromeColors,
    icon: ImageVector,
    description: String,
    tint: Color = colors.foreground,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AccessoryKey(colors, selected, enabled, Modifier.size(32.dp, 36.dp), description, onClick) {
        Icon(icon, null, Modifier.size(16.dp),
            tint = (if (selected) colors.terminalBackground else tint).copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun TextKey(colors: TerminalChromeColors, label: String, selected: Boolean = false,
    description: String = label, height: Dp = 36.dp, onClick: () -> Unit) {
    AccessoryKey(colors, selected, true, Modifier.height(height), description, onClick) {
        Text(label, modifier = Modifier.padding(horizontal = if (height > 36.dp) 16.dp else 5.dp),
            color = if (selected) colors.terminalBackground else colors.foreground,
            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif, maxLines = 1)
    }
}

@Composable
private fun AccessoryKey(colors: TerminalChromeColors, selected: Boolean, enabled: Boolean,
    modifier: Modifier, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier = modifier.semantics(mergeDescendants = true) {
        contentDescription = description
        this.selected = selected
        if (!enabled) disabled()
    }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.foreground else Color.Transparent) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun AccessorySeparator(colors: TerminalChromeColors) {
    Surface(Modifier.size(1.dp, 18.dp), color = colors.border) {}
}

internal fun terminalAccessorySequence(value: String, control: Boolean, alt: Boolean, navigation: Boolean,
    shift: Boolean = false, keyboardFlags: Int = 0): ByteArray {
    val modifiers = TerminalKeyModifiers(control, alt, shift)
    if (navigation) return terminalNavigationInput(value, modifiers)
    val function = value.takeIf { it.startsWith("F") }?.drop(1)?.toIntOrNull()
    return if (function != null && function in 1..12) terminalFunctionInput(function, modifiers)
    else terminalTextInput(value, modifiers, keyboardFlags)
}

/** A pasted newline edits the remote prompt without submitting it. Fail closed in plain shells. */
internal fun terminalNewlineSequence(bracketedPasteEnabled: Boolean): ByteArray? =
    if (bracketedPasteEnabled) "\u001b[200~\n\u001b[201~".toByteArray() else null

private val TerminalSymbols = listOf(
    "/", "-", "_", "|", "~", ".", ":", ";", "$", "@", "#", "&", "*",
    "(", ")", "{", "}", "[", "]", "'", "\"", "`",
)
