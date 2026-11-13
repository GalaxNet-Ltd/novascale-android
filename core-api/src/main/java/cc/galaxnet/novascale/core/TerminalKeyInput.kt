// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.core

import java.util.Locale

/** Shared by the accessory, Android IME and hardware keyboard paths. */
data class TerminalKeyModifiers(
    val control: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val parameter: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (control) 4 else 0)
    val active: Boolean get() = control || alt || shift
}

fun terminalNavigationInput(suffix: String, modifiers: TerminalKeyModifiers): ByteArray =
    (if (modifiers.active) "\u001b[1;${modifiers.parameter}$suffix" else "\u001b[$suffix").toByteArray()

fun terminalFunctionInput(number: Int, modifiers: TerminalKeyModifiers): ByteArray {
    require(number in 1..12)
    val parameter = if (modifiers.active) ";${modifiers.parameter}" else ""
    return if (number <= 4) {
        val suffix = "PQRS"[number - 1]
        (if (modifiers.active) "\u001b[1$parameter$suffix" else "\u001bO$suffix").toByteArray()
    } else {
        val code = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)[number - 5]
        "\u001b[$code$parameter~".toByteArray()
    }
}

fun terminalTextInput(
    value: String,
    modifiers: TerminalKeyModifiers,
    keyboardFlags: Int = 0,
): ByteArray {
    if (value.isEmpty()) return byteArrayOf()
    val normalized = value.replace("\r\n", "\n").replace('\n', '\r')
    val first = normalized.codePointAt(0)
    val remainder = normalized.substring(Character.charCount(first)).toByteArray()
    // Ghostty owns negotiation. Only use CSI-u when the remote program requested it.
    val enhanced = keyboardFlags and 1 != 0
    val reportAll = keyboardFlags and 8 != 0
    if (normalized.codePointCount(0, normalized.length) == 1 &&
        (reportAll || enhanced && (first == 27 || modifiers.active && first in listOf(9, 13, 127)))) {
        val key = if (modifiers.shift && first in 'A'.code..'Z'.code) first + 32 else first
        return "\u001b[$key;${modifiers.parameter}u".toByteArray()
    }
    if (first == 9 && modifiers.shift) {
        return "\u001b[Z".toByteArray() + remainder
    }
    var text = String(Character.toChars(first))
    if (modifiers.shift && first >= 32 && first != 127) text = text.uppercase(Locale.ROOT)
    if (modifiers.control) {
        val code = when (val cp = text.codePointAt(0)) {
            32, 64 -> 0
            in 97..122 -> cp - 96
            in 65..90 -> cp - 64
            in 91..95 -> cp - 64
            63 -> 127
            else -> cp
        }
        text = String(Character.toChars(code))
    }
    val payload = text.toByteArray() + remainder
    return if (modifiers.alt) byteArrayOf(27) + payload else payload
}
