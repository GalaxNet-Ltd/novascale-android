// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/** Ordinary preedit stays local until committed; armed Ctrl/Alt letters are immediate keystrokes. */
internal class TerminalInputConnection(private val terminal: NovaTerminalView) : BaseInputConnection(terminal, true) {
    private val buffer = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
    private var active = true
    override fun getEditable(): Editable = buffer
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!active) return false
        terminal.inputText(text?.toString().orEmpty())
        reset()
        return true
    }
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!active) return false
        val value = text?.toString().orEmpty()
        if (terminal.hasShortcutModifiers && value.length == 1 && value[0] in ' '..'~') {
            // Samsung and other IMEs can compose even with suggestions disabled.
            // Shortcuts are keystrokes; retire this editor after sending so a
            // later commit/finish cannot send the same key a second time.
            terminal.inputText(value)
            terminal.resetImeEditor()
            return true
        }
        return super.setComposingText(text, newCursorPosition)
    }
    override fun finishComposingText(): Boolean {
        if (!active) return false
        val value = buffer.toString()
        reset()
        terminal.inputText(value)
        return true
    }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        delete(beforeLength, afterLength, false)
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        delete(beforeLength, afterLength, true)
    private fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (!active || before < 0 || after < 0) return false
        if (buffer.isNotEmpty()) return if (codePoints) super.deleteSurroundingTextInCodePoints(before, after)
            else super.deleteSurroundingText(before, after)
        repeat(before.coerceAtMost(1024)) { terminal.inputText("\u007f") }
        repeat(after.coerceAtMost(1024)) { terminal.inputKey(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL)) }
        return true
    }
    override fun performEditorAction(editorAction: Int): Boolean {
        if (!active) return false
        finishComposingText()
        terminal.inputText("\r")
        return true
    }
    override fun sendKeyEvent(event: KeyEvent): Boolean = active &&
        (event.action != KeyEvent.ACTION_DOWN || terminal.inputKey(event.keyCode, event))
    override fun performContextMenuAction(id: Int): Boolean = active && terminal.editAction(id)
    override fun closeConnection() { cancel(); super.closeConnection() }
    fun commitBeforeShortcut(): Boolean {
        if (!active || buffer.isEmpty()) return false
        val value = buffer.toString()
        reset()
        // The caller is changing the modifier state; it owns the new selection.
        terminal.inputText(value, consumeOneShot = false)
        return true
    }
    fun cancel() { active = false; reset() }
    private fun reset() { removeComposingSpans(buffer); buffer.clear(); Selection.setSelection(buffer, 0) }
}
