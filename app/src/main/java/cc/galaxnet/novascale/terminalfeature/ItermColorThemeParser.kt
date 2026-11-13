/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node

internal object ItermColorThemeParser {
    const val MAX_THEME_BYTES = 512 * 1024

    fun parse(fileName: String, bytes: ByteArray): TerminalThemePalette {
        require(bytes.isNotEmpty() && bytes.size <= MAX_THEME_BYTES) {
            "The iTerm2 theme must be smaller than 512 KiB."
        }
        val raw = bytes.toString(StandardCharsets.UTF_8)
        require(!raw.contains("<!ENTITY", ignoreCase = true)) { "Theme entities are not supported." }
        require(
            !raw.contains("<xi:include", ignoreCase = true) &&
                !raw.contains("<xinclude:include", ignoreCase = true),
        ) { "Theme includes are not supported." }
        val xml = raw.replace(DOCTYPE, "")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { isXIncludeAware = false }
            setExpandEntityReferences(false)
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            disableFeature("http://xml.org/sax/features/external-general-entities")
            disableFeature("http://xml.org/sax/features/external-parameter-entities")
            disableFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd")
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val document = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)),
        )
        val root = document.documentElement
        require(root.tagName == "plist") { "This is not an iTerm2 color preset." }
        val entries = root.directElements().firstOrNull { it.tagName == "dict" }
            ?.keyedElements()
            ?: error("The iTerm2 color dictionary is missing.")
        val ansi = List(16) { index ->
            entries["Ansi $index Color"]?.readColor()
                ?: error("The preset is missing ANSI color $index.")
        }
        val background = entries["Background Color"]?.readColor()
            ?: error("The preset is missing its background color.")
        val foreground = entries["Foreground Color"]?.readColor()
            ?: error("The preset is missing its foreground color.")
        val name = fileName
            .substringBeforeLast('.')
            .replace(Regex("[\\p{Cntrl}/\\\\]+"), " ")
            .trim()
            .take(64)
            .ifBlank { "Imported iTerm2 theme" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return TerminalThemePalette(
            id = "iterm_$digest",
            displayName = name,
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = entries["Cursor Color"]?.readColor() ?: foreground,
            cursorText = entries["Cursor Text Color"]?.readColor() ?: background,
            selection = entries["Selection Color"]?.readColor() ?: ansi[4],
            selectedText = entries["Selected Text Color"]?.readColor() ?: foreground,
            imported = true,
        )
    }

    private fun Element.keyedElements(): Map<String, Element> {
        val children = directElements()
        return buildMap {
            var index = 0
            while (index + 1 < children.size) {
                val key = children[index]
                val value = children[index + 1]
                if (key.tagName == "key") put(key.textContent.trim(), value)
                index += 2
            }
        }
    }

    private fun Element.readColor(): Int {
        require(tagName == "dict") { "An iTerm2 color entry is malformed." }
        val values = keyedElements()
        fun component(name: String): Double = values[name]
            ?.textContent
            ?.trim()
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: error("The $name is missing from an iTerm2 color.")
        val red = (component("Red Component") * 255.0).roundToInt()
        val green = (component("Green Component") * 255.0).roundToInt()
        val blue = (component("Blue Component") * 255.0).roundToInt()
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun Node.directElements(): List<Element> = buildList {
        var child = firstChild
        while (child != null) {
            if (child is Element) add(child)
            child = child.nextSibling
        }
    }

    private fun DocumentBuilderFactory.disableFeature(feature: String) {
        // Android's XML provider does not expose every Xerces feature. The raw
        // input is independently bounded, strips its doctype, and rejects
        // entity declarations; these flags add defense when the provider has them.
        runCatching { setFeature(feature, false) }
    }

    private val DOCTYPE = Regex("<!DOCTYPE[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
