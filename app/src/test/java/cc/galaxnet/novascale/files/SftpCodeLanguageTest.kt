/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SftpCodeLanguageTest {
    @Test
    fun detectsSupportedTreeSitterLanguagesFromRemotePath() {
        assertEquals(SftpCodeLanguage.JSON, codeLanguageForPath("/etc/service/config.json"))
        assertEquals(SftpCodeLanguage.PYTHON, codeLanguageForPath("/opt/tool/worker.py"))
        assertEquals(SftpCodeLanguage.KOTLIN, codeLanguageForPath("build.gradle.kts"))
        assertEquals(SftpCodeLanguage.CPP, codeLanguageForPath("/src/worker.hpp"))
        assertEquals(SftpCodeLanguage.XML, codeLanguageForPath("vector.svg"))
        assertEquals(SftpCodeLanguage.PROPERTIES, codeLanguageForPath("app.properties"))
    }

    @Test
    fun unknownExtensionRemainsSafePlainText() {
        assertEquals(SftpCodeLanguage.PLAIN_TEXT, codeLanguageForPath("/etc/hosts"))
        assertEquals(SftpCodeLanguage.PLAIN_TEXT, codeLanguageForPath("README.md"))
    }
}
