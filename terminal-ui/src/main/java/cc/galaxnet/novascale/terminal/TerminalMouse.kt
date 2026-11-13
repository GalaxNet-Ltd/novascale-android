// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

/** xterm mouse protocol; mode bits are provided by Ghostty, never parsed a second time. */
internal object TerminalMouse {
    fun enabled(flags: Int) = flags and 15 != 0
    fun encode(flags: Int, button: Int, x: Int, y: Int, release: Boolean = false,
        motion: Boolean = false, shift: Boolean = false, alt: Boolean = false, control: Boolean = false,
        pixelX: Int = x, pixelY: Int = y): ByteArray? {
        if (!enabled(flags)) return null
        val x10 = flags and 14 == 0
        if (x10 && (release || motion || button >= 64)) return null
        if (motion && (flags and 12 == 0 || (button == 3 && flags and 8 == 0))) return null
        val modifiers = if (x10) 0 else (if (shift) 4 else 0) or (if (alt) 8 else 0) or (if (control) 16 else 0)
        var code = button or modifiers or (if (motion) 32 else 0)
        val cx = x.coerceAtLeast(1); val cy = y.coerceAtLeast(1)
        if (flags and (16 or 128) != 0) {
            val px = if (flags and 128 != 0) pixelX.coerceAtLeast(1) else cx
            val py = if (flags and 128 != 0) pixelY.coerceAtLeast(1) else cy
            return "\u001b[<$code;$px;$py${if (release) 'm' else 'M'}".toByteArray()
        }
        if (release) code = 3 or modifiers
        if (flags and 64 != 0) return "\u001b[${code + 32};$cx;${cy}M".toByteArray()
        if (flags and 32 != 0) {
            if (cx > 2015 || cy > 2015) return null
            return ("\u001b[M" + (code + 32).toChar() + (cx + 32).toChar() + (cy + 32).toChar()).toByteArray()
        }
        // Legacy mouse coordinates are bytes, NOT UTF-8 characters. Do not wrap at column 224.
        if (cx > 223 || cy > 223) return null
        return byteArrayOf(27, 91, 77, (code + 32).toByte(), (cx + 32).toByte(), (cy + 32).toByte())
    }
}
