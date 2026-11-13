// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.billing.*
import cc.galaxnet.novascale.remote.*
import cc.galaxnet.novascale.tailnet.*
import cc.galaxnet.novascale.ui.NovaScaleTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Isolated Room metadata and the actual debug controls. No real network or checkout. */
internal object ProEnforcementChecks {
    fun run(test: Instrumentation, activity: ProxyTestActivity) = runBlocking {
        val context = test.targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val suffix = UUID.randomUUID().toString()
        val names = listOf("pro-profiles-$suffix.db", "pro-groups-$suffix.db", "pro-usage-$suffix.db")
        val overrides = createDebugProStore(context)
        val previousOverride = overrides.read()
        val cache = object : EntitlementCache {
            override suspend fun read(): CachedEntitlement? = null
            override suspend fun write(value: CachedEntitlement) = Unit
        }
        val manager = PaywallManager(UnavailableBillingStore(), PurchaseVerifier { error("No checkout") }, cache,
            scope, configured = false, debugStore = overrides)
        manager.awaitReady(); manager.setDebugPro(false)
        val legacyA = "fixture-a-$suffix"; val legacyB = "fixture-b-$suffix"
        // Exercise the actual v1 -> v2 upgrade and one-time legacy snapshot.
        context.openOrCreateDatabase(names[0], Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE tailnet_profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, controlUrl TEXT NOT NULL, createdAt INTEGER NOT NULL, pendingDelete INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE tailnet_selection (singleton INTEGER NOT NULL PRIMARY KEY, profileId TEXT NOT NULL)")
            listOf(legacyA, legacyB).forEach { db.execSQL("INSERT INTO tailnet_profiles VALUES (?, ?, ?, 0, 0)", arrayOf(it, "Legacy", DEFAULT_CONTROL_SERVER)) }
            db.execSQL("INSERT INTO tailnet_selection VALUES (1, ?)", arrayOf(legacyA)); db.version = 1
        }
        val profilesDb = Room.databaseBuilder(context, TailnetProfileDatabase::class.java, names[0])
            .addMigrations(TailnetProfileRepository.MIGRATION_1_2).build()
        val groupsDb = Room.databaseBuilder(context, TraditionalHostDatabase::class.java, names[1]).build()
        val profiles = TailnetProfileRepository(context, {}, {}, profilesDb, paywall = manager)
        val groups = HostGroupRepository(groupsDb, scope, manager)
        try {
            val migrated = profiles.load()
            check(migrated.freeIds == setOf(legacyA, legacyB))
            check(runCatching { profiles.add("Blocked", DEFAULT_CONTROL_SERVER) }.exceptionOrNull() is ProLimitReached)
            check(profiles.load().profiles.size == 2)
            manager.setDebugPro(true)
            val paid = profiles.add("Added with Pro", DEFAULT_CONTROL_SERVER).profiles.single { it.id !in migrated.freeIds }
            profiles.select(paid.id)
            manager.setDebugPro(false)
            profiles.select(legacyB)
            check(runCatching { profiles.select(paid.id) }.exceptionOrNull() is ProLimitReached)
            check(profiles.load().activeId == legacyB)
            profiles.selectFree(paid.id)
            check(profiles.load().activeId == paid.id)
            val cancelled = profiles.stageReplacement("Cancelled", DEFAULT_CONTROL_SERVER)
            profiles.discardReplacement()
            check(profiles.load().profiles.none { it.id == cancelled.id })
            val replacement = profiles.stageReplacement("Replacement", "https://headscale.fixture.invalid")
            val replaced = profiles.commitReplacement(paid.id, replacement)
            check(replaced.activeId == replacement.id && replaced.profiles.none { it.id == paid.id })
            check(replacement.id in replaced.freeIds && replaced.profiles.size == 3)

            val creations = coroutineScope { (1..8).map { async { runCatching { groups.create("Group $it") } } }.awaitAll() }
            check(creations.count { it.isSuccess } == 3)
            check(creations.filter { it.isFailure }.all { it.exceptionOrNull() is ProLimitReached })
            manager.setDebugPro(true)
            val fourth = groups.create("Fourth")
            manager.setDebugPro(false)
            groups.rename(fourth.id, "Still editable")
            val host = checkNotNull(GroupHostKey.tailnet("fixture", "node"))
            groups.setGroups(host, setOf(fourth.id))
            check(runCatching { groups.create("Fifth") }.exceptionOrNull() is ProLimitReached)
            check(groups.state.value.memberships[host] == setOf(fourth.id))

            val clock = AtomicLong(0)
            val usageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val store = RoomHerdrUsageStore(context, names[2])
            val usage = HerdrUsageManager(store, manager, usageScope, { 100 }, clock::get)
            val visit = try {
                val id = usage.reserve("hashed-fixture-identity")
                usage.setActive(id, true); clock.set(12_500); usage.tick()
                test.runOnMainSync {
                    activity.setContent { NovaScaleTheme(darkTheme = false) {
                        val entitlement by manager.state.collectAsStateWithLifecycle()
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            DebugHerdrUsageStats(usage, entitlement.hasPro)
                        }
                    } }
                }
                awaitText(test, context.getString(R.string.herdr_debug_counts, 0, 3, 1, 2))
                awaitText(test, context.getString(R.string.herdr_debug_progress, 12.5, 30L))
                awaitText(test, context.getString(R.string.herdr_debug_attachments, 1, 1, 0))
                usage.setActive(id, false); clock.set(75_000); usage.tick()
                awaitText(test, context.getString(R.string.herdr_debug_attachments, 1, 0, 0))
                awaitText(test, context.getString(R.string.herdr_debug_progress, 12.5, 30L))
                usage.setActive(id, true); clock.set(92_500); usage.setActive(id, false)
                awaitText(test, context.getString(R.string.herdr_debug_counts, 1, 3, 0, 2))
                awaitText(test, context.getString(R.string.herdr_debug_progress, 0.0, 30L))
                awaitText(test, context.getString(R.string.herdr_debug_attachments, 1, 0, 1))
                manager.setDebugPro(true)
                awaitText(test, context.getString(R.string.herdr_debug_unlimited))
                manager.setDebugPro(false)
                awaitText(test, context.getString(R.string.herdr_debug_free))
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "pro-usage.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                id
            } finally { usageScope.cancel(); store.close() }
            val reopened = RoomHerdrUsageStore(context, names[2])
            try {
                val saved = checkNotNull(reopened.read())
                check(saved.used == 1 && saved.visits[visit]?.charged == true)
            } finally { reopened.close() }

            manager.dismiss()
            test.runOnMainSync {
                activity.setContent { NovaScaleTheme(darkTheme = false) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DebugProControls(manager, herdrUsage = { usage })
                    }
                    PaywallHost(BillingComponents(manager) { error("No checkout") }, herdrUsage = { usage })
                } }
            }
            check(runCatching { groups.create("Blocked in UI") }.exceptionOrNull() is ProLimitReached)
            click(test, context.getString(R.string.pro_debug_pro))
            await("Pro: ${manager.state.value}") { manager.state.value.hasPro }
            click(test, context.getString(R.string.action_done))
            groups.create("Unlocked from sheet")
            click(test, context.getString(R.string.pro_debug_free))
            await("Free: ${manager.state.value}") { !manager.state.value.hasPro }
            val restored = PaywallManager(UnavailableBillingStore(), PurchaseVerifier { error("No checkout") }, cache,
                scope, configured = false, debugStore = overrides)
            restored.awaitReady()
            check(restored.state.value.debugPro == false)
            check(runCatching { groups.create("Still blocked") }.exceptionOrNull() is ProLimitReached)
            manager.dismiss()
            click(test, context.getString(R.string.pro_debug_default))
            await("Default: ${manager.state.value}") { manager.state.value.debugPro == null }
            check(!manager.state.value.pro)
        } catch (failure: Exception) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "pro-enforcement-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw failure
        } finally {
            withContext(NonCancellable) { overrides.write(previousOverride) }
            scope.cancel(); profilesDb.close(); groupsDb.close()
            names.forEach(context::deleteDatabase)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        repeat(80) { if (condition()) return; Thread.sleep(100) }
        error("Pro state did not update: $label")
    }
    private fun awaitText(test: Instrumentation, text: String) {
        fun find(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            return (node.isVisibleToUser && node.text?.toString() == text) || (0 until node.childCount).any { find(node.getChild(it)) }
        }
        await(text) { find(test.uiAutomation.rootInActiveWindow) }
    }
    private fun click(test: Instrumentation, text: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        repeat(100) { attempt ->
            var node = find(test.uiAutomation.rootInActiveWindow)
            if (node != null) {
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync(); return
                }
            }
            if (attempt % 10 == 9) {
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    node ?: return false
                    if (node.isScrollable && node.performAction(if (attempt % 20 == 9) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                    return (0 until node.childCount).any { scroll(node.getChild(it)) }
                }
                scroll(test.uiAutomation.rootInActiveWindow)
            }
            Thread.sleep(100)
        }
        error("Missing Pro control: $text")
    }
}
