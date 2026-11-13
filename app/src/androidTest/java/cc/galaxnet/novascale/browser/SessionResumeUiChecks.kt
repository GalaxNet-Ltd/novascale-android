// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import cc.galaxnet.novascale.*
import cc.galaxnet.novascale.core.TailnetPeer
import cc.galaxnet.novascale.remote.RemoteConnectionConfig
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real launcher/task path without credentials or a remote connection. */
internal object SessionResumeUiChecks {
    fun backgroundAndResume(test: Instrumentation, activity: androidx.activity.ComponentActivity, backgroundMs: Long = 0) {
        fun awaitState(message: String, check: () -> Boolean) {
            repeat(150) { if (check()) return; Thread.sleep(100) }
            error("Session lifecycle timed out: $message")
        }
        check(test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        awaitState("background") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        if (backgroundMs > 0) Thread.sleep(backgroundMs)
        activity.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == activity.taskId }.moveToFront()
        awaitState("foreground") { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus() }
        test.waitForIdleSync()
    }

    fun run(test: Instrumentation) {
        val context = test.targetContext
        check(context.packageName.endsWith(".debug")) { "Use a disposable debug package" }
        val app = context.applicationContext as NovaScaleApplication
        runBlocking { app.preferences.completeOnboarding() }
        val activities = CopyOnWriteArrayList<MainActivity>()
        val resumed = AtomicReference<MainActivity?>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) {
                    activities += activity
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) resumed.set(activity) }
            override fun onActivityPaused(activity: Activity) { if (activity is MainActivity) resumed.compareAndSet(activity, null) }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        fun until(message: String, condition: () -> Boolean) {
            repeat(150) { if (condition()) return; Thread.sleep(100) }
            error("Session resume timed out: $message")
        }
        fun background(activity: MainActivity) {
            check(test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            until("activity stopped") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        }
        fun launchFromIcon() {
            // Use another app's Activity, as a launcher does; a shell-only start can
            // merely bring the task forward and miss duplicate-Activity regressions.
            val command = "am start --user 0 -f 0x10000000 " +
                "-n ${test.context.packageName}/${SessionLauncherActivity::class.java.name} " +
                "--es targetPackage ${context.packageName}"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            until("launcher resumed") { resumed.get() != null }
            test.waitForIdleSync()
        }
        val key = "resume-ui-fixture"
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            // Reproduce an explicit launch followed by the Home screen's launcher intent.
            var main = test.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            until("profile restoration") { app.tailnetProfiles.state.value.snapshot != null }
            val peer = TailnetPeer("resume-ui-fixture", "Resume fixture", "resume-fixture.invalid", emptyList(), false, true)
            val destination = AppDestination.Terminal(peer, key, returnToHost = true)
            val config = RemoteConnectionConfig(host = "resume-fixture.invalid")
            lateinit var navigation: NovaScaleNavigationModel
            test.runOnMainSync {
                val group = app.terminalSessions.getOrCreate(key, peer.stableNodeId, peer, config)
                group.addTab(connectImmediately = false)
                navigation = ViewModelProvider(main)[NovaScaleNavigationModel::class.java]
                navigation.open(AppSection.HOME, destination)
            }
            until("terminal view attached") { app.terminalSessions.sessions.value.single { it.key == key }.attachedViewCount == 1 }
            val group = app.terminalSessions.getOrCreate(key, peer.stableNodeId, peer, config)
            val runtimes = group.tabs.value.map { it.runtime }
            repeat(3) {
                background(main)
                launchFromIcon()
                check(resumed.get() === main) { "Launcher created a new main screen and lost the active session" }
                check(navigation.uiState.value.destination == destination && group.selectedTabId.value == 2)
                check(group.tabs.value.map { it.runtime } == runtimes)
            }
            // The active-session notification also sends a plain explicit reopen intent.
            background(main)
            val notification = android.app.PendingIntent.getActivity(context, 49,
                Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
            try { notification.send() } finally { notification.cancel() }
            until("notification resumed") { resumed.get() != null }
            check(resumed.get() === main && navigation.uiState.value.destination == destination) {
                "Session notification created a new main screen"
            }
            background(main)
            context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == main.taskId }.moveToFront()
            until("Recents resumed the same screen") { resumed.get() === main }
            check(navigation.uiState.value.destination == destination)

            // Recreation retains navigation; an explicitly requested document is a separate window.
            val oldMain = main
            test.runOnMainSync { main.recreate() }
            until("recreated activity resumed") { resumed.get()?.let { it !== oldMain } == true }
            main = checkNotNull(resumed.get())
            test.runOnMainSync { check(ViewModelProvider(main)[NovaScaleNavigationModel::class.java] === navigation) }
            until("recreated terminal attached") { app.terminalSessions.sessions.value.single { it.key == key }.attachedViewCount == 1 }
            check(navigation.uiState.value.destination == destination && group.selectedTabId.value == 2)
            val document = test.startActivitySync(TerminalWindowIntent.create(context, peer, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            check(document !== main && document.taskId != main.taskId) { "New terminal window reused the launcher window" }
            until("both windows share the session") { app.terminalSessions.sessions.value.single { it.key == key }.attachedViewCount == 2 }
            test.runOnMainSync { document.finishAndRemoveTask() }
            until("main window resumed") { resumed.get() === main }
            check(navigation.uiState.value.destination == destination && group.tabs.value.map { it.runtime } == runtimes)

            // An explicit detach stays detached; a foreground return must not reopen it.
            test.runOnMainSync { main.onBackPressedDispatcher.onBackPressed() }
            until("explicit detach") { navigation.uiState.value.destination == AppDestination.Host(peer) }
            until("view detached") { app.terminalSessions.sessions.value.single { it.key == key }.detached }
            background(main)
            launchFromIcon()
            check(resumed.get() === main && navigation.uiState.value.destination == AppDestination.Host(peer))
            check(!group.isClosed.value && group.tabs.value.map { it.runtime } == runtimes)
        } finally {
            test.runOnMainSync {
                app.terminalSessions.closeGroup(key)
                activities.reversed().forEach { if (!it.isDestroyed) it.finish() }
            }
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}

