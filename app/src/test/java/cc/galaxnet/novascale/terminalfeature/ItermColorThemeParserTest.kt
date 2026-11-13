/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItermColorThemeParserTest {
    @Test
    fun importsStandardItermColorsPlist() {
        val theme = ItermColorThemeParser.parse(
            "Ocean Night.itermcolors",
            validTheme().toByteArray(),
        )

        assertEquals("Ocean Night", theme.displayName)
        assertEquals(16, theme.ansi.size)
        assertEquals(0xFF1A334D.toInt(), theme.ansi[0])
        assertEquals(0xFF0D1A26.toInt(), theme.background)
        assertEquals(0xFFE6CCB3.toInt(), theme.foreground)
        assertTrue(theme.imported)
        assertTrue(theme.id.startsWith("iterm_"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsXmlEntitiesBeforeParsing() {
        ItermColorThemeParser.parse(
            "unsafe.itermcolors",
            """<!DOCTYPE plist [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><plist><dict/></plist>"""
                .toByteArray(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsXIncludeBeforeParsing() {
        ItermColorThemeParser.parse(
            "unsafe.itermcolors",
            """<plist xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="file:///etc/passwd"/></plist>"""
                .toByteArray(),
        )
    }

    @Test
    fun importedThemeJsonRoundTrips() {
        val theme = ItermColorThemeParser.parse("Round Trip.itermcolors", validTheme().toByteArray())

        val restored = decodeImportedTerminalThemes(encodeImportedTerminalThemes(listOf(theme)))

        assertEquals(listOf(theme), restored)
    }
}

private fun validTheme(): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""")
    append("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
    append("<plist version=\"1.0\"><dict>")
    repeat(16) { index ->
        append("<key>Ansi $index Color</key>")
        append(color(red = 0.1 + index * 0.01, green = 0.2, blue = 0.3))
    }
    append("<key>Background Color</key>")
    append(color(red = 0.05, green = 0.1, blue = 0.15))
    append("<key>Foreground Color</key>")
    append(color(red = 0.9, green = 0.8, blue = 0.7))
    append("<key>Cursor Color</key>")
    append(color(red = 1.0, green = 0.5, blue = 0.25))
    append("</dict></plist>")
}

private fun color(red: Double, green: Double, blue: Double): String = """
    <dict>
      <key>Red Component</key><real>$red</real>
      <key>Green Component</key><real>$green</real>
      <key>Blue Component</key><real>$blue</real>
      <key>Color Space</key><string>sRGB</string>
    </dict>
""".trimIndent()
