// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.core.*
import cc.galaxnet.novascale.files.*
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.ui.NovaScaleTheme
import cc.galaxnet.novascale.ui.uiText
import java.io.File
import java.net.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Actual Kotlin/JNI/Rust SFTP and Compose UI against a disposable OS-link fixture. */
internal object SftpSymlinkChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity, port: Int, fingerprint: String) {
        check(port > 0 && fingerprint.startsWith("SHA256:"))
        val backend = object : TailnetBackend {
            override val currentState = TailnetState.Running(TailnetIdentity("fixture", "fixture.invalid", emptyList()), 1)
            override val currentPeers = emptyList<TailnetPeer>()
            override fun addStateListener(listener: (TailnetState) -> Unit) = AutoCloseable {}
            override fun addPeerListener(listener: (List<TailnetPeer>) -> Unit) = AutoCloseable {}
            override suspend fun start(configuration: TailnetConfiguration) = Unit
            override suspend fun beginInteractiveLogin(): LoginRequest = error("Unused")
            override suspend fun peers() = currentPeers
            override suspend fun ping(address: String): TailnetPingResult = error("Unused")
            override suspend fun dial(destination: TailnetDestination) = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    DialedStream("sftp-fixture", ParcelFileDescriptor.fromSocket(socket).detachFd())
                }
            }
            override fun cancelDial(connectionId: String) = Unit
            override suspend fun startBrowserProxy(origins: List<TailnetOrigin>): BrowserProxyEndpoint = error("Unused")
            override fun stopBrowserProxy(proxyId: String) = Unit
            override suspend fun logout() = Unit
            override fun close() = Unit
        }
        val config = RemoteConnectionConfig("sftp-link-fixture.invalid", username = "fixture",
            authenticationKind = AuthenticationKind.PASSWORD, password = "fixture-correct")
        val known = KnownHostStore(test.targetContext)
        val previous = known.fingerprint(config)
        val dark = mutableStateOf(false)
        lateinit var model: SftpViewModel
        var modelCreated = false
        var stage = "connecting"
        fun step(name: String) {
            stage = name
            test.sendStatus(0, android.os.Bundle().apply { putString("stream", "SFTP: $name\n") })
        }
        val download = File(test.targetContext.cacheDir, "sftp-link-download.txt")
        fun await(predicate: (SftpConnectionState) -> Boolean): SftpConnectionState = runBlocking {
            try { withTimeout(20_000) { model.state.first(predicate) } }
            catch (error: TimeoutCancellationException) {
                val state = model.state.value
                error("SFTP $stage: timed out; path=${state.currentPath}, busy=${state.busy}, status=${state.status}, editor=${state.editingPath}, preview=${state.preview}, transfer=${state.transfer}")
            }
        }
        fun entry(name: String) = model.state.value.entries.single { it.name == name }
        fun list(path: String) {
            test.runOnMainSync { model.list(path) }
            await { !it.busy && it.currentPath == path }
        }
        fun read(name: String): String {
            test.runOnMainSync { model.open(entry(name)) }
            val state = await { !it.busy }
            check(state.editingPath != null) { "Could not edit $name: ${state.status}" }
            return state.editingText.also { test.runOnMainSync { model.closeEditor() } }
        }
        fun node(text: String, root: AccessibilityNodeInfo? = test.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
            root ?: return null
            root.refresh()
            if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
            for (index in 0 until root.childCount) node(text, root.getChild(index))?.let { return it }
            return null
        }
        fun click(text: String) {
            repeat(60) {
                test.waitForIdleSync()
                var target = node(text)
                while (target != null && !target.isClickable) target = target.parent
                if (target?.isEnabled == true && target.isVisibleToUser && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync()
                    return
                }
                Thread.sleep(100)
            }
            test.uiAutomation.takeScreenshot()?.let { image ->
                File(test.targetContext.cacheDir, "sftp-link-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            fun labels(root: AccessibilityNodeInfo?): List<String> = if (root == null) emptyList() else
                listOfNotNull(root.text?.toString()) + (0 until root.childCount).flatMap { labels(root.getChild(it)) }
            error("Missing SFTP action: $text; labels=${labels(test.uiAutomation.rootInActiveWindow).take(20)}; path=${model.state.value.currentPath}, busy=${model.state.value.busy}")
        }
        fun screenshot(name: String) {
            test.waitForIdleSync(); Thread.sleep(300)
            test.uiAutomation.takeScreenshot()?.let { image ->
                File(test.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        }
        try {
            known.trust(config, fingerprint)
            test.runOnMainSync {
                model = ViewModelProvider(activity, SftpViewModel.factory(activity, backend))[
                    "sftp:${config.transport}:${config.tailnetProfileId}:${config.host}:${config.port}", SftpViewModel::class.java]
                modelCreated = true
                activity.setContent { NovaScaleTheme(darkTheme = dark.value) {
                    Surface(Modifier.safeDrawingPadding()) {
                        SftpScreen(backend, backend.currentState, config, FilePreviewSettings(tapToPreview = false), {}, {}, {})
                    }
                } }
            }
            await { it.connected && !it.busy && it.entries.isNotEmpty() }
            check(entry("A-folder").isDirectory && entry("A-folder").isSymbolicLink)
            check(entry("B-folder-chain").isDirectory)
            listOf("C-relative-file.txt", "D-file-chain.txt", "E-absolute-file.txt").forEach {
                check(entry(it).isSymbolicLink && entry(it).targetKind == "file")
                check(entry(it).size == "original target\n".toByteArray().size.toLong())
            }
            listOf("F-dangling", "G-loop", "H-denied").forEach { check(entry(it).isUnavailableLink) }
            screenshot("sftp-links-light.png")
            check(node(activity.getString(R.string.sftp_symbolic_link)) != null) { "Link icon has no accessible label" }
            test.runOnMainSync { dark.value = true }
            screenshot("sftp-links-dark.png")
            test.runOnMainSync { dark.value = false }
            step("folder navigation")
            click("A-folder")
            await { !it.busy && it.currentPath == "/A-folder" }
            check(read("child.txt") == "nested target\n")
            test.runOnMainSync { model.parentDirectory() }
            await { !it.busy && it.currentPath == "/" }
            click("B-folder-chain")
            await { !it.busy && it.currentPath == "/B-folder-chain" }
            check(entry("child.txt").path == "/B-folder-chain/child.txt")
            list("/")

            for (name in listOf("C-relative-file.txt", "D-file-chain.txt", "E-absolute-file.txt")) {
                step("save $name")
                val content = "Saved through $name\n"
                test.runOnMainSync { model.open(entry(name)) }
                await { !it.busy && it.editingPath != null }
                test.runOnMainSync { model.updateEditor(content); model.saveEditor(closeAfterSave = true) }
                val saved = await { !it.busy }
                check(saved.editingPath == null && !saved.editorDirty) { "Link save failed: ${saved.status}" }
                list("/")
                check(entry(name).isSymbolicLink) { "Save replaced the link itself" }
                check(read("real-file.txt") == content) { "Save did not update the target" }
            }

            step("unresolved REALPATH")
            // A REALPATH implementation that leaves the link unresolved must fail
            // safely, leaving the link and the dirty editor contents intact.
            test.runOnMainSync { model.open(entry("I-unresolved-realpath.txt")) }
            await { !it.busy && it.editingPath != null }
            test.runOnMainSync { model.updateEditor("must not replace link"); model.saveEditor() }
            check(await { !it.busy }.editorDirty)
            test.runOnMainSync { model.closeEditor() }
            list("/")
            check(entry("I-unresolved-realpath.txt").isSymbolicLink)
            check(read("real-file.txt") == "Saved through E-absolute-file.txt\n")

            step("preview link")
            test.runOnMainSync { model.preview(entry("C-relative-file.txt"), 1024L * 1024) }
            // Text previews intentionally fall back to the quick editor after
            // downloading, retaining the link pathname for subsequent saves.
            val preview = await { !it.busy && it.editingPath == "/C-relative-file.txt" }
            check(preview.editingText == "Saved through E-absolute-file.txt\n")
            test.runOnMainSync { model.closeEditor() }
            step("download link")
            test.runOnMainSync { model.download(entry("D-file-chain.txt"), Uri.fromFile(download)) }
            await { it.transfer != null || download.exists() }
            await { !it.busy && it.transfer == null }
            check(download.readText() == "Saved through E-absolute-file.txt\n")

            step("unavailable link")
            test.runOnMainSync { model.open(entry("F-dangling")) }
            check(model.state.value.status == uiText(R.string.sftp_link_unavailable))
            check(!model.state.value.busy && model.state.value.preview == null && model.state.value.editingPath == null)
            test.runOnMainSync { check(model.create("F-dangling", false) == null) }
            await { !it.busy }
            list("/")
            check(entry("F-dangling").isSymbolicLink && entry("F-dangling").isUnavailableLink)
            check(model.state.value.entries.none { it.name == "missing-target.txt" })

            step("rename/unlink")
            test.runOnMainSync { check(model.rename(entry("E-absolute-file.txt"), "renamed-link.txt") == null) }
            await { !it.busy && it.entries.any { e -> e.name == "renamed-link.txt" } }
            check(entry("renamed-link.txt").isSymbolicLink)
            test.runOnMainSync { model.delete(entry("renamed-link.txt")) }
            await { !it.busy && it.entries.none { e -> e.name == "renamed-link.txt" } }
            check(read("real-file.txt") == "Saved through E-absolute-file.txt\n")
            test.runOnMainSync { model.delete(entry("A-folder")) }
            await { !it.busy && it.entries.none { e -> e.name == "A-folder" } }
            check(entry("real-dir").isDirectory)
            list("/real-dir")
            check(read("child.txt") == "nested target\n")
            step("many links")
            list("/many")
            check(model.state.value.entries.size == 24 && model.state.value.entries.all { it.targetKind == "file" })
            list("/")
            step("editor bound")
            test.runOnMainSync { model.open(entry("large.bin")) }
            check(await { !it.busy }.editingPath == null) { "Quick editor accepted an oversized file" }
        } finally {
            test.runOnMainSync { activity.setContent {}; if (modelCreated) model.leaveScreen() }
            download.delete()
            if (previous == null) known.forget(config) else known.trust(config, previous)
        }
    }
}
