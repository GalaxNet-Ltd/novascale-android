/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.c.TSLanguageC
import com.itsaky.androidide.treesitter.cpp.TSLanguageCpp
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.properties.TSLanguageProperties
import com.itsaky.androidide.treesitter.python.TSLanguagePython
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.editor.ts.TsThemeBuilder
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.styling.TextStyle.makeStyle
import io.github.rosemoe.sora.lang.styling.textStyle
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019
import java.util.Locale

internal class SftpCodeEditor(context: Context) : CodeEditor(context) {
    private var applyingRemoteText = false
    private var boundPath: String? = null
    var onTextChanged: ((String) -> Unit)? = null

    init {
        typefaceText = Typeface.MONOSPACE
        setTextSize(15f)
        setLineNumberEnabled(true)
        setPinLineNumber(true)
        setHighlightCurrentLine(true)
        setWordwrap(false)
        colorScheme = if (isDarkMode(context)) SchemeVS2019() else SchemeGitHub()
        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            if (
                !applyingRemoteText &&
                (event.action == ContentChangeEvent.ACTION_INSERT || event.action == ContentChangeEvent.ACTION_DELETE)
            ) {
                onTextChanged?.invoke(text.toString())
            }
        }
    }

    fun bindDocument(path: String, value: String) {
        if (boundPath != path) {
            boundPath = path
            setEditorLanguage(runCatching { codeLanguageForPath(path).createLanguage(context) }.getOrElse { EmptyLanguage() })
        }
        if (text.toString() != value) {
            applyingRemoteText = true
            setText(value)
            applyingRemoteText = false
        }
    }

    fun searchFor(value: String) {
        if (value.isBlank()) {
            searcher.stopSearch()
        } else {
            searcher.search(value, EditorSearcher.SearchOptions(false, false))
        }
    }

    fun findNext() {
        if (searcher.hasQuery()) searcher.gotoNext()
    }

    fun findPrevious() {
        if (searcher.hasQuery()) searcher.gotoPrevious()
    }

    fun toggleWordWrap(): Boolean {
        val enabled = !isWordwrap()
        setWordwrap(enabled)
        return enabled
    }

    fun releaseEditorInput() {
        val inputToken = windowToken
        clearFocus()
        if (inputToken != null) {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(inputToken, 0)
        }
    }
}

internal enum class SftpCodeLanguage(
    val label: String,
    private val assetNames: List<String> = emptyList(),
    private val grammar: (() -> TSLanguage)? = null,
) {
    PLAIN_TEXT("Plain text"),
    C("C", listOf("c"), { TSLanguageC.getInstance() }),
    CPP("C++", listOf("c", "cpp"), { TSLanguageCpp.getInstance() }),
    JAVA("Java", listOf("java"), { TSLanguageJava.getInstance() }),
    JSON("JSON", listOf("json"), { TSLanguageJson.getInstance() }),
    KOTLIN("Kotlin", listOf("kotlin"), { TSLanguageKotlin.getInstance() }),
    PROPERTIES("Properties", listOf("properties"), { TSLanguageProperties.getInstance() }),
    PYTHON("Python", listOf("python"), { TSLanguagePython.getInstance() }),
    XML("XML", listOf("xml"), { TSLanguageXml.getInstance() });

    fun createLanguage(context: Context): Language {
        val language = grammar?.invoke() ?: return EmptyLanguage()
        val query = assetNames.joinToString("\n") { name ->
            context.assets.open("tree-sitter-queries/$name/highlights.scm").bufferedReader().use { it.readText() }
        }
        return TsLanguage(TsLanguageSpec(language, query)) { novaScaleTheme() }
    }
}

internal fun codeLanguageForPath(path: String): SftpCodeLanguage {
    val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        name == "makefile" || extension in setOf("c", "h") -> SftpCodeLanguage.C
        extension in setOf("cc", "cpp", "cxx", "hh", "hpp", "hxx") -> SftpCodeLanguage.CPP
        extension == "java" -> SftpCodeLanguage.JAVA
        extension in setOf("json", "jsonc") -> SftpCodeLanguage.JSON
        extension in setOf("kt", "kts") -> SftpCodeLanguage.KOTLIN
        extension in setOf("properties", "conf") -> SftpCodeLanguage.PROPERTIES
        extension in setOf("py", "pyw") -> SftpCodeLanguage.PYTHON
        extension in setOf("xml", "svg", "xhtml") -> SftpCodeLanguage.XML
        else -> SftpCodeLanguage.PLAIN_TEXT
    }
}

private fun TsThemeBuilder.novaScaleTheme() {
    textStyle(EditorColorScheme.COMMENT, italic = true) applyTo "comment"
    textStyle(EditorColorScheme.KEYWORD, bold = true) applyTo "keyword"
    makeStyle(EditorColorScheme.LITERAL) applyTo arrayOf(
        "string",
        "number",
        "constant",
        "escape",
        "attr.value",
        "prop.value",
    )
    makeStyle(EditorColorScheme.IDENTIFIER_VAR) applyTo arrayOf(
        "variable",
        "property",
        "prop.key",
        "parameter",
    )
    makeStyle(EditorColorScheme.IDENTIFIER_NAME) applyTo arrayOf(
        "type",
        "constructor",
        "element.tag",
        "attr.name",
    )
    makeStyle(EditorColorScheme.FUNCTION_NAME) applyTo "function"
    makeStyle(EditorColorScheme.ANNOTATION) applyTo arrayOf("attribute", "annotation")
    makeStyle(EditorColorScheme.OPERATOR) applyTo arrayOf("operator", "punctuation")
}

private fun isDarkMode(context: Context): Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
