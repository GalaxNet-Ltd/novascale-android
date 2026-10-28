/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cc.galaxnet.novascale.nativecore

import android.util.Base64
import cc.galaxnet.novascale.core.TerminalSnapshotDecoder
import cc.galaxnet.novascale.core.GhosttySnapshotParser
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import java.util.UUID
import org.json.JSONObject

/** The only Kotlin object allowed to invoke the Rust JNI exports directly. */
internal object NativeCore {
    init {
        System.loadLibrary("nova_core")
    }

    @JvmStatic external fun nativeBuildInfo(): String
    @JvmStatic external fun nativeStartSsh(fd: Int, request: String): String
    @JvmStatic external fun nativeStartSftp(fd: Int, request: String): String
    @JvmStatic external fun nativePollEvent(sessionId: Long, timeoutMs: Int): String
    @JvmStatic external fun nativeSendCommand(sessionId: Long, command: String): Boolean
    @JvmStatic external fun nativeSnapshot(sessionId: Long): ByteArray
    @JvmStatic external fun nativeSnapshotWithImages(sessionId: Long, imageRevision: Long): ByteArray
    @JvmStatic external fun nativeTerminalProbe(): ByteArray
    @JvmStatic external fun nativeTerminalGraphicsProbe(commands: String, scroll: Int): ByteArray
    @JvmStatic external fun nativeRelease(sessionId: Long)
}

internal sealed interface SshAuthentication {
    fun toJson(): JSONObject

    data object None : SshAuthentication {
        override fun toJson() = JSONObject().put("type", "none")
    }

    data class Password(val password: String) : SshAuthentication {
        override fun toJson() = JSONObject()
            .put("type", "password")
            .put("password", password)
    }

    data class PrivateKey(
        val privateKey: String,
        val passphrase: String? = null,
    ) : SshAuthentication {
        override fun toJson() = JSONObject()
            .put("type", "private_key")
            .put("private_key", privateKey)
            .apply { passphrase?.let { put("passphrase", it) } }
    }
}

internal data class NativeSessionRequest(
    val host: String,
    val username: String,
    val authentication: SshAuthentication,
    val expectedHostKey: String? = null,
    val terminal: String = "xterm-256color",
    val columns: Int = 80,
    val rows: Int = 24,
    val terminalTheme: NativeTerminalTheme? = null,
) {
    fun toJson(): String = JSONObject()
        .put("host", host)
        .put("username", username)
        .put("authentication", authentication.toJson())
        .put("terminal", terminal)
        .put("columns", columns)
        .put("rows", rows)
        .apply { terminalTheme?.let { put("terminalTheme", it.toJson()) } }
        .apply { expectedHostKey?.let { put("expectedHostKey", it) } }
        .toString()
}

internal data class NativeTerminalTheme(
    val foregroundRgb: Int,
    val backgroundRgb: Int,
    val cursorRgb: Int,
    val ansiRgb: List<Int>,
) {
    init {
        require(ansiRgb.size == 16)
        require(listOf(foregroundRgb, backgroundRgb, cursorRgb).plus(ansiRgb).all { it in 0..0x00ffffff })
    }

    fun toJson(): JSONObject = JSONObject()
        .put("foreground", foregroundRgb)
        .put("background", backgroundRgb)
        .put("cursor", cursorRgb)
        .put("ansi", org.json.JSONArray(ansiRgb))
}

internal class NativeSession private constructor(val id: Long) : AutoCloseable {
    private val snapshotDecoder = TerminalSnapshotDecoder()
    fun pollEvent(timeoutMs: Int = 1_000): NativeEvent? {
        val raw = NativeCore.nativePollEvent(id, timeoutMs)
        return raw.takeIf(String::isNotEmpty)?.let(::NativeEvent)
    }

    fun sendInput(value: ByteArray): Boolean = send(
        JSONObject()
            .put("type", "input")
            .put("base64", Base64.encodeToString(value, Base64.NO_WRAP)),
    )

    fun resize(columns: Int, rows: Int, cellWidth: Int = 8, cellHeight: Int = 16): Boolean = send(
        JSONObject()
            .put("type", "resize")
            .put("columns", columns)
            .put("rows", rows)
            .put("cell_width", cellWidth)
            .put("cell_height", cellHeight),
    )

    fun scrollTerminal(rows: Int): Boolean = send(
        JSONObject()
            .put("type", "terminal_scroll")
            .put("rows", rows),
    )

    fun setTerminalTheme(theme: NativeTerminalTheme): Boolean = send(
        JSONObject()
            .put("type", "terminal_theme")
            .put("foreground", theme.foregroundRgb)
            .put("background", theme.backgroundRgb)
            .put("cursor", theme.cursorRgb)
            .put("ansi", org.json.JSONArray(theme.ansiRgb)),
    )

    fun terminalSnapshot(): TerminalRenderSnapshot =
        snapshotDecoder.parse(NativeCore.nativeSnapshotWithImages(id, snapshotDecoder.imageRevision))

    fun list(path: String, requestId: String = UUID.randomUUID().toString()): String {
        check(send(
            JSONObject()
                .put("type", "sftp_list")
                .put("request_id", requestId)
                .put("path", path),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun read(path: String, requestId: String = UUID.randomUUID().toString()): String {
        check(send(
            JSONObject()
                .put("type", "sftp_read")
                .put("request_id", requestId)
                .put("path", path),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun write(
        path: String,
        content: ByteArray,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        check(send(
            JSONObject()
                .put("type", "sftp_write")
                .put("request_id", requestId)
                .put("path", path)
                .put("base64", Base64.encodeToString(content, Base64.NO_WRAP)),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun createDirectory(path: String, requestId: String = UUID.randomUUID().toString()): String {
        check(send(
            JSONObject()
                .put("type", "sftp_create_directory")
                .put("request_id", requestId)
                .put("path", path),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun createFile(path: String, requestId: String = UUID.randomUUID().toString()): String {
        check(send(
            JSONObject()
                .put("type", "sftp_create_file")
                .put("request_id", requestId)
                .put("path", path),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun rename(
        sourcePath: String,
        destinationPath: String,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        check(send(
            JSONObject()
                .put("type", "sftp_rename")
                .put("request_id", requestId)
                .put("source_path", sourcePath)
                .put("destination_path", destinationPath),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun delete(
        path: String,
        directory: Boolean,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        check(send(
            JSONObject()
                .put("type", "sftp_delete")
                .put("request_id", requestId)
                .put("path", path)
                .put("directory", directory),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    /**
     * Transfers ownership of [localFd] to Rust, including when enqueueing the command fails.
     * The caller must detach the descriptor from its ParcelFileDescriptor and never close it.
     */
    fun upload(
        path: String,
        localFd: Int,
        totalBytes: Long?,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        check(send(
            JSONObject()
                .put("type", "sftp_upload")
                .put("request_id", requestId)
                .put("path", path)
                .put("local_fd", localFd)
                .apply { totalBytes?.let { put("total_bytes", it) } },
        )) { "Native SFTP session is closed" }
        return requestId
    }

    /**
     * Transfers ownership of [localFd] to Rust, including when enqueueing the command fails.
     * The caller must detach the descriptor from its ParcelFileDescriptor and never close it.
     */
    fun download(
        path: String,
        localFd: Int,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        check(send(
            JSONObject()
                .put("type", "sftp_download")
                .put("request_id", requestId)
                .put("path", path)
                .put("local_fd", localFd),
        )) { "Native SFTP session is closed" }
        return requestId
    }

    fun cancelSftpTransfer(requestId: String): Boolean = send(
        JSONObject()
            .put("type", "sftp_cancel_transfer")
            .put("request_id", requestId),
    )

    override fun close() {
        send(JSONObject().put("type", "close"))
        NativeCore.nativeRelease(id)
    }

    private fun send(command: JSONObject): Boolean =
        NativeCore.nativeSendCommand(id, command.toString())

    companion object {
        fun startSsh(fd: Int, request: NativeSessionRequest): NativeSession =
            parseStart(NativeCore.nativeStartSsh(fd, request.toJson()))

        fun startSftp(fd: Int, request: NativeSessionRequest): NativeSession =
            parseStart(NativeCore.nativeStartSftp(fd, request.toJson()))

        private fun parseStart(raw: String): NativeSession {
            val response = JSONObject(raw)
            check(response.optBoolean("ok")) {
                response.optString("error", "Unable to start the native session")
            }
            return NativeSession(response.getLong("sessionId"))
        }
    }
}

internal class NativeEvent(raw: String) {
    private val document = JSONObject(raw)

    val type: String = document.getString("type")
    val category: String? = document.optString("category").ifBlank { null }
    val message: String? = document.optString("message").ifBlank { null }
    val fingerprint: String? = document.optString("fingerprint").ifBlank { null }
    val algorithm: String? = document.optString("algorithm").ifBlank { null }
    val expectedFingerprint: String? =
        document.optString("expectedFingerprint").ifBlank { null }
    val exitStatus: Int? = if (document.has("exitStatus")) document.getInt("exitStatus") else null
    val revision: Long? = if (document.has("revision")) document.getLong("revision") else null
    val requestId: String? = document.optString("requestId").ifBlank { null }
    val payload: JSONObject? = document.optJSONObject("payload")
}

internal fun rustCoreBuildInfo(): JSONObject = JSONObject(NativeCore.nativeBuildInfo())

internal fun ghosttyTerminalProbe(): String =
    GhosttySnapshotParser.parse(NativeCore.nativeTerminalProbe()).accessibleText().trim()
