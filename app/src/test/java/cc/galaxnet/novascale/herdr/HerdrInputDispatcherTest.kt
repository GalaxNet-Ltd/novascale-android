/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.herdr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdrInputDispatcherTest {
    private val target = HerdrInputTarget(1, 1)

    @Test fun displayChangesCoalesceAndStopWithTheirAttachment() = runTest {
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<HerdrDisplaySettings>()
        val dispatcher = HerdrInputDispatcher(backgroundScope, { _, _ -> true }, { error("Delivery failed") },
            StandardTestDispatcher(testScheduler), deliverDisplay = {
                delivered += it
                if (delivered.size == 1) release.await()
                true
            })
        val first = HerdrDisplaySettings(true, true)
        val last = HerdrDisplaySettings(false, false)
        dispatcher.offerDisplay(first)
        runCurrent()
        repeat(1000) { assertTrue(dispatcher.offerDisplay(HerdrDisplaySettings(it % 2 == 0, true))) }
        dispatcher.offerDisplay(last)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(first, last), delivered)
        dispatcher.stop()
        assertFalse(dispatcher.offerDisplay(first))
    }

    @Test fun inputIsCopiedAndDeliveredInOrder() = runTest {
        val sent = mutableListOf<Pair<ByteArray, HerdrInputTarget>>()
        var failures = 0
        val dispatcher = HerdrInputDispatcher(backgroundScope, { bytes, captured ->
            sent += bytes to captured
            true
        }, { failures++ }, StandardTestDispatcher(testScheduler))
        val first = byteArrayOf(1, 2)
        assertTrue(dispatcher.offer(first, target))
        first[0] = 9
        assertTrue(dispatcher.offer(byteArrayOf(3), target.copy(epoch = 2)))
        runCurrent()
        assertEquals(2, sent.size)
        assertArrayEquals(byteArrayOf(1, 2), sent[0].first)
        assertArrayEquals(byteArrayOf(3), sent[1].first)
        assertEquals(2L, sent[1].second.epoch)
        assertEquals(0, failures)
        dispatcher.stop()
    }

    @Test fun fullQueueFailsClosedOnceAndDiscardsPendingInput() = runTest {
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<ByteArray>()
        var failures = 0
        val dispatcher = HerdrInputDispatcher(backgroundScope, { bytes, _ ->
            sent += bytes
            release.await()
            true
        }, { failures++ }, StandardTestDispatcher(testScheduler))
        assertTrue(dispatcher.offer(byteArrayOf(0), target))
        runCurrent() // One in-flight delivery remains blocked.
        repeat(16) { assertTrue(dispatcher.offer(byteArrayOf((it + 1).toByte()), target)) }
        assertFalse(dispatcher.offer(byteArrayOf(99), target))
        assertFalse(dispatcher.offer(byteArrayOf(100), target))
        assertEquals(1, failures)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, sent.size)
        dispatcher.stop()
    }

    @Test fun oversizeAndNativeRejectionFailTheDispatcher() = runTest {
        var oversizeFailures = 0
        val tooLarge = HerdrInputDispatcher(backgroundScope, { _, _ -> true }, { oversizeFailures++ }, StandardTestDispatcher(testScheduler))
        assertFalse(tooLarge.offer(ByteArray(256 * 1024 + 1), target))
        assertEquals(1, oversizeFailures)
        tooLarge.stop()

        var rejectionFailures = 0
        val rejected = HerdrInputDispatcher(backgroundScope, { _, _ -> false }, { rejectionFailures++ }, StandardTestDispatcher(testScheduler))
        assertTrue(rejected.offer(byteArrayOf(1), target))
        runCurrent()
        assertEquals(1, rejectionFailures)
        assertFalse(rejected.offer(byteArrayOf(2), target))
        rejected.stop()
    }

    @Test fun stopCancelsInflightAndPreventsFutureInput() = runTest {
        val never = CompletableDeferred<Unit>()
        var sent = 0
        var failures = 0
        val dispatcher = HerdrInputDispatcher(backgroundScope, { _, _ ->
            sent++
            never.await()
            true
        }, { failures++ }, StandardTestDispatcher(testScheduler))
        assertTrue(dispatcher.offer(byteArrayOf(1), target))
        runCurrent()
        assertTrue(dispatcher.offer(byteArrayOf(2), target))
        dispatcher.stop()
        assertFalse(dispatcher.offer(byteArrayOf(3), target))
        assertEquals(1, sent)
        assertEquals(0, failures)
    }

    @Test fun resizeCoalescesToTheLatestWhileTheWorkerIsBusy() = runTest {
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<HerdrResize>()
        var failures = 0
        val dispatcher = HerdrInputDispatcher(
            backgroundScope,
            { _, _ -> true },
            { failures++ },
            StandardTestDispatcher(testScheduler),
            deliverResize = { size ->
                sent += size
                if (sent.size == 1) release.await()
                true
            },
        )
        val first = HerdrResize(80, 24, 8, 16)
        val last = HerdrResize(120, 40, 10, 20)
        assertTrue(dispatcher.offerResize(first))
        runCurrent()
        repeat(1000) { assertTrue(dispatcher.offerResize(HerdrResize(81 + it % 20, 25, 8, 16))) }
        assertTrue(dispatcher.offerResize(last))
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(first, last), sent)
        assertEquals(0, failures)
        dispatcher.stop()
    }

    @Test fun replacementCancelsOldResizeBeforeNewAttachmentUsesTheWorker() = runTest {
        val never = CompletableDeferred<Unit>()
        val oldSent = mutableListOf<HerdrResize>()
        val newSent = mutableListOf<HerdrResize>()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val old = HerdrInputDispatcher(backgroundScope, { _, _ -> true }, {}, testDispatcher,
            deliverResize = { size -> oldSent += size; never.await(); true })
        val first = HerdrResize(80, 24, 8, 16)
        val obsolete = HerdrResize(90, 25, 8, 16)
        val current = HerdrResize(100, 30, 8, 16)
        assertTrue(old.offerResize(first))
        runCurrent()
        assertTrue(old.offerResize(obsolete))
        old.stop()
        assertFalse(old.offerResize(current))
        val replacement = HerdrInputDispatcher(backgroundScope, { _, _ -> true }, {}, testDispatcher,
            deliverResize = { size -> newSent += size; true })
        assertTrue(replacement.offerResize(current))
        runCurrent()
        assertEquals(listOf(first), oldSent)
        assertEquals(listOf(current), newSent)
        replacement.stop()
    }

    @Test fun reopenIsOrderedBeforeNewInputAndSurvivesResizeCoalescing() = runTest {
        val sent = mutableListOf<String>()
        val dispatcher = HerdrInputDispatcher(backgroundScope,
            { _, _ -> sent += "input"; true }, { error("Unexpected rejection") }, StandardTestDispatcher(testScheduler),
            deliverResize = { sent += "resize:${it.columns}"; true },
            deliverResume = { sent += "resume"; true })
        assertTrue(dispatcher.offerResume())
        repeat(10) { dispatcher.offerResize(HerdrResize(80 + it, 24, 8, 16)) }
        assertTrue(dispatcher.offer(byteArrayOf(1), target))
        runCurrent()
        assertEquals(listOf("resize:89", "resume", "input"), sent)
        dispatcher.stop()
        assertFalse(dispatcher.offerResume())
    }

    @Test fun healthProbeIsOrderedAndCannotEscapeAStoppedAttachment() = runTest {
        val sent = mutableListOf<String>()
        val dispatcher = HerdrInputDispatcher(backgroundScope,
            { _, _ -> sent += "input"; true }, { error("Unexpected rejection") }, StandardTestDispatcher(testScheduler),
            deliverProbe = { sent += it; true })
        assertTrue(dispatcher.offerProbe("current-attachment"))
        assertTrue(dispatcher.offer(byteArrayOf(1), target))
        runCurrent()
        assertEquals(listOf("current-attachment", "input"), sent)
        assertTrue(dispatcher.offerProbe("cancelled-probe"))
        dispatcher.stop()
        runCurrent()
        assertEquals(listOf("current-attachment", "input"), sent)
        assertFalse(dispatcher.offerProbe("retired-attachment"))
    }
}
