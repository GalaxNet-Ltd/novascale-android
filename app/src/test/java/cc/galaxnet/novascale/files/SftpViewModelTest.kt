/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpViewModelTest {
    private val entries = listOf(
        entry("zeta.txt", size = 12, modifiedAt = 20),
        entry(".secret", size = 2, modifiedAt = 30),
        entry("alpha", directory = true, modifiedAt = 10),
        entry("beta.txt", size = 50, modifiedAt = 40),
    )

    @Test
    fun linkedFoldersNavigateAndSortAsFoldersButDeleteAsLinks() {
        val folderLink = entry("linked-folder").copy(kind = "symlink", targetKind = "directory")
        val fileLink = entry("linked-file").copy(kind = "symlink", targetKind = "file")
        assertTrue(folderLink.isSymbolicLink)
        assertTrue(folderLink.isDirectory)
        assertFalse(folderLink.deleteAsDirectory)
        assertFalse(fileLink.isDirectory)
        assertFalse(fileLink.deleteAsDirectory)
        assertTrue(entry("folder", directory = true).deleteAsDirectory)
        val result = filterAndSortSftpEntries(listOf(fileLink, entry("aaa.txt"), folderLink), "", false, SftpSortOrder.NAME_ASCENDING)
        assertEquals(listOf("linked-folder", "aaa.txt", "linked-file"), result.map { it.name })
    }

    @Test
    fun unresolvedLinksRemainIdentifiableWithoutPretendingToBeFiles() {
        val missing = entry("missing").copy(kind = "symlink")
        assertTrue(missing.isSymbolicLink)
        assertTrue(missing.isUnavailableLink)
        assertFalse(missing.isDirectory)
        assertFalse(missing.deleteAsDirectory)
        assertTrue(missing.copy(targetKind = "other").isUnavailableLink)
        assertFalse(missing.copy(targetKind = "file").isUnavailableLink)
        assertFalse(missing.copy(targetKind = "directory").isUnavailableLink)
        assertFalse(entry("normal").isUnavailableLink)
    }

    @Test
    fun hiddenFilesAreExcludedByDefaultAndDirectoriesRemainFirst() {
        val result = filterAndSortSftpEntries(
            entries = entries,
            searchText = "",
            showHiddenFiles = false,
            sortOrder = SftpSortOrder.NAME_ASCENDING,
        )

        assertEquals(listOf("alpha", "beta.txt", "zeta.txt"), result.map { it.name })
    }

    @Test
    fun searchIsCaseInsensitiveAndSortAppliesWithinItemKinds() {
        val result = filterAndSortSftpEntries(
            entries = entries,
            searchText = "T",
            showHiddenFiles = true,
            sortOrder = SftpSortOrder.SIZE_DESCENDING,
        )

        assertEquals(listOf("beta.txt", "zeta.txt", ".secret"), result.map { it.name })
    }

    @Test
    fun remoteNamesAndPathsRejectTraversal() {
        assertNull(validateSftpName("notes.md"))
        assertEquals(uiText(R.string.validation_enter_name), validateSftpName("  "))
        assertEquals(uiText(R.string.validation_different_name), validateSftpName(".."))
        assertEquals(uiText(R.string.validation_name_slash), validateSftpName("../notes"))
        assertEquals("/tmp/notes.md", joinRemotePath("/tmp/", "notes.md"))
        assertEquals("/", parentRemotePath("/tmp"))
        assertEquals("/tmp", parentRemotePath("/tmp/nova"))
    }

    @Test
    fun previewKindsCoverNativeMediaAndPdfViewers() {
        assertEquals(SftpPreviewKind.IMAGE, previewKindForFileName("photo.HEIC"))
        assertEquals(SftpPreviewKind.VIDEO, previewKindForFileName("demo.webm"))
        assertEquals(SftpPreviewKind.AUDIO, previewKindForFileName("voice.opus"))
        assertEquals(SftpPreviewKind.PDF, previewKindForFileName("runbook.pdf"))
        assertEquals(SftpPreviewKind.EXTERNAL, previewKindForFileName("inventory.xlsx"))
    }

    @Test
    fun previewCacheNamesCannotEscapeTheirPrivateDirectory() {
        assertEquals("passwd", SftpPreviewCache.safeFileName("../../etc/passwd"))
        assertEquals("notes.pdf", SftpPreviewCache.safeFileName("folder\\notes.pdf"))
        assertEquals("preview", SftpPreviewCache.safeFileName(".."))
        assertTrue(SftpPreviewCache.safeFileName("bad\u0000name.png").contains('_'))
    }

    @Test
    fun previewLimitDefaultsMatchIosAndUnlimitedIsExplicit() {
        assertEquals(FilePreviewSizeLimit.TEN_MIB, FilePreviewSizeLimit.fromId(null))
        assertEquals(10L * 1_024 * 1_024, FilePreviewSizeLimit.default.maximumBytes)
        assertNull(FilePreviewSizeLimit.UNLIMITED.maximumBytes)
        assertEquals(FilePreviewSizeLimit.FIFTY_MIB, FilePreviewSizeLimit.fromId("50_mib"))
    }

    @Test
    fun unsupportedPreviewCanFallBackToUtf8EditorContent() {
        val content = "# Runbook\n\n服务状态：正常\n"

        assertEquals(content, decodePreviewText(content.toByteArray(Charsets.UTF_8)))
        assertEquals("", decodePreviewText(byteArrayOf()))
    }

    @Test
    fun previewTextFallbackRejectsBinaryMalformedAndOversizedPayloads() {
        assertNull(decodePreviewText(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertNull(decodePreviewText(byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(decodePreviewText(ByteArray(SFTP_QUICK_EDIT_MAX_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test
    fun breadcrumbsExposeEveryNavigableAncestor() {
        assertEquals(
            listOf(
                SftpBreadcrumb("/", "/"),
                SftpBreadcrumb("home", "/home"),
                SftpBreadcrumb("fortitude", "/home/fortitude"),
                SftpBreadcrumb("projects", "/home/fortitude/projects"),
            ),
            sftpBreadcrumbs("/home/fortitude/projects/"),
        )
        assertEquals(listOf(SftpBreadcrumb("/", "/")), sftpBreadcrumbs("/"))
    }

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long? = null,
        modifiedAt: Long? = null,
    ) = SftpEntry(
        name = name,
        path = "/$name",
        kind = if (directory) "directory" else "file",
        size = size,
        modifiedAt = modifiedAt,
        permissions = null,
    )
}
