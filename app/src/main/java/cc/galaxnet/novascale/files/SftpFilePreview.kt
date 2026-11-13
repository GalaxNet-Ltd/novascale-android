/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.files

import android.content.Context
import android.webkit.MimeTypeMap
import cc.galaxnet.novascale.ui.UiText
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID

// Keep this aligned with MAX_EDIT_BYTES in native/nova-core-rust/src/sftp.rs.
internal const val SFTP_QUICK_EDIT_MAX_BYTES = 2 * 1_024 * 1_024

internal enum class SftpPreviewKind {
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    EXTERNAL,
}

internal enum class FilePreviewSizeLimit(
    val id: String,
    val label: String,
    val maximumBytes: Long?,
) {
    FIVE_MIB("5_mib", "5 MiB", 5L * 1_024 * 1_024),
    TEN_MIB("10_mib", "10 MiB", 10L * 1_024 * 1_024),
    TWENTY_MIB("20_mib", "20 MiB", 20L * 1_024 * 1_024),
    FIFTY_MIB("50_mib", "50 MiB", 50L * 1_024 * 1_024),
    ONE_HUNDRED_MIB("100_mib", "100 MiB", 100L * 1_024 * 1_024),
    UNLIMITED("unlimited", "Unlimited", null);

    companion object {
        val default = TEN_MIB

        fun fromId(id: String?): FilePreviewSizeLimit =
            entries.firstOrNull { it.id == id } ?: default
    }
}

internal data class FilePreviewSettings(
    val tapToPreview: Boolean = false,
    val sizeLimit: FilePreviewSizeLimit = FilePreviewSizeLimit.default,
)

internal data class SftpFilePreview(
    val entry: SftpEntry,
    val kind: SftpPreviewKind = previewKindForFileName(entry.name),
    val localPath: String? = null,
    val error: UiText? = null,
)

internal fun previewKindForFileName(fileName: String): SftpPreviewKind {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    return when (extension) {
        "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp" ->
            SftpPreviewKind.IMAGE
        "3gp", "3gpp", "avi", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ts", "webm" ->
            SftpPreviewKind.VIDEO
        "aac", "amr", "flac", "m4a", "mid", "midi", "mp3", "oga", "ogg", "opus", "wav" ->
            SftpPreviewKind.AUDIO
        "pdf" -> SftpPreviewKind.PDF
        else -> SftpPreviewKind.EXTERNAL
    }
}

internal fun previewMimeType(fileName: String, kind: SftpPreviewKind): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: when (kind) {
            SftpPreviewKind.IMAGE -> "image/*"
            SftpPreviewKind.VIDEO -> "video/*"
            SftpPreviewKind.AUDIO -> "audio/*"
            SftpPreviewKind.PDF -> "application/pdf"
            SftpPreviewKind.EXTERNAL -> "application/octet-stream"
    }
}

/**
 * Returns the complete UTF-8 document when an unsupported preview can safely
 * fall back to the quick editor. A strict decoder rejects malformed payloads,
 * while the control-character check avoids treating UTF-8-compatible binary
 * formats as text.
 */
internal fun decodePreviewText(bytes: ByteArray): String? {
    if (bytes.size > SFTP_QUICK_EDIT_MAX_BYTES) return null
    val text = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull() ?: return null
    if (!looksLikeText(text)) return null
    return text
}

internal fun readPreviewText(file: File): String? {
    if (!file.isFile || file.length() > SFTP_QUICK_EDIT_MAX_BYTES) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return decodePreviewText(bytes)
}

private fun looksLikeText(text: String): Boolean {
    if ('\u0000' in text) return false
    val suspiciousControls = text.count { character ->
        val code = character.code
        (code in 0x00..0x1f &&
            character != '\t' &&
            character != '\n' &&
            character != '\r' &&
            character != '\u000c' &&
            character != '\u001b') ||
            code == 0x7f
    }
    return suspiciousControls == 0 ||
        (text.length >= 100 && suspiciousControls * 100 <= text.length)
}

internal object SftpPreviewCache {
    private const val DIRECTORY_NAME = "sftp-previews"

    fun createFile(context: Context, originalName: String): File {
        val root = File(context.cacheDir, DIRECTORY_NAME).apply {
            check(isDirectory || mkdirs()) { "Unable to create preview cache" }
        }
        val previewDirectory = File(root, UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "Unable to create preview directory" }
        }
        return File(previewDirectory, safeFileName(originalName))
    }

    fun deletePreview(localPath: String?) {
        if (localPath.isNullOrBlank()) return
        val file = File(localPath)
        runCatching { file.delete() }
        runCatching { file.parentFile?.delete() }
    }

    fun clear(context: Context) {
        val root = File(context.cacheDir, DIRECTORY_NAME)
        if (root.exists()) runCatching { root.deleteRecursively() }
    }

    internal fun safeFileName(originalName: String): String {
        val lastComponent = originalName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
            .take(180)
        return lastComponent
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "preview"
    }
}
