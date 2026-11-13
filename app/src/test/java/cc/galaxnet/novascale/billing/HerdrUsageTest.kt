// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdrUsageTest {
    private class Store : HerdrUsageStore {
        var value: HerdrUsageRecord? = null
        var broken = false
        override suspend fun read() = value
        override suspend fun write(value: HerdrUsageRecord) { check(!broken); this.value = value }
    }
    private class Fixture(val scope: CoroutineScope) {
        var time = 0L
        var day = 100L
        var overrideValue: Boolean? = false
        val store = Store()
        val pro = PaywallManager(UnavailableBillingStore(), PurchaseVerifier { error("No store") },
            object : EntitlementCache {
                override suspend fun read(): CachedEntitlement? = null
                override suspend fun write(value: CachedEntitlement) = Unit
            }, scope, false, debugStore = object : DebugProStore {
                override suspend fun read() = overrideValue
                override suspend fun write(value: Boolean?) { overrideValue = value }
            })
        fun manager() = HerdrUsageManager(store, pro, scope, { day }, { time })
    }

    @Test fun `failed and short visits accumulate only connected foreground time`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        val failed = meter.reserve("failed"); f.time += 120_000; meter.end(failed)
        assertEquals(0, meter.state.value.used)
        val first = meter.reserve("first"); meter.setActive(first, true)
        f.time += 10_000; meter.setActive(first, false)
        f.time += 60_000; meter.end(first)
        assertEquals(10_000, f.store.value!!.partialMs)
        val second = meter.reserve("second"); meter.setActive(second, true)
        f.time += 19_999; meter.tick(); assertEquals(0, meter.state.value.used)
        f.time++; meter.tick(); assertEquals(1, meter.state.value.used)
        f.time += 100_000; meter.tick(); assertEquals(1, meter.state.value.used)
    }

    @Test fun `atomic reservations reject fourth admission and release unused capacity`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        val attempts = (0..6).map { async { runCatching { meter.reserve("host-$it") } } }.awaitAll()
        assertEquals(3, attempts.count { it.isSuccess })
        assertTrue(attempts.filter { it.isFailure }.all { it.exceptionOrNull() is ProLimitReached })
        assertEquals(0, meter.state.value.remaining)
        meter.end(attempts.first { it.isSuccess }.getOrThrow())
        assertNotNull(meter.reserve("replacement"))
        assertEquals(3, meter.state.value.reserved)
        assertFalse(meter.state.value.storageError)
    }

    @Test fun `three meaningful visits block new sessions but never interrupt an ongoing visit`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        var last = ""
        repeat(3) {
            last = meter.reserve("host-$it"); meter.setActive(last, true)
            f.time += 30_000; meter.tick()
            if (it < 2) meter.end(last)
        }
        assertEquals(3, meter.state.value.used)
        assertTrue(runCatching { meter.reserve("fourth") }.exceptionOrNull() is ProLimitReached)
        assertEquals(last, meter.reserve("host-2"))
        meter.setActive(last, false); f.time += 60_000; meter.setActive(last, true)
        f.time += 60_000; meter.tick(); assertEquals(3, meter.state.value.used)
    }

    @Test fun `restart adopts unfinished charged visit while explicit close starts a new visit`() = runTest {
        val f = Fixture(backgroundScope); val first = f.manager(); runCurrent()
        val id = first.reserve("identity"); first.setActive(id, true)
        f.time += 30_000; first.setActive(id, false)
        val restored = f.manager()
        assertEquals(id, restored.reserve("identity"))
        restored.setActive(id, true); f.time += 60_000; restored.tick()
        assertEquals(1, restored.state.value.used)
        restored.end(id)
        assertNotEquals(id, restored.reserve("identity"))
    }

    @Test fun `partial usage survives restart and abandoned reservations do not exhaust next process`() = runTest {
        val f = Fixture(backgroundScope); val first = f.manager(); runCurrent()
        val id = first.reserve("first"); first.setActive(id, true)
        f.time += 15_000; first.setActive(id, false)
        first.reserve("abandoned-two"); first.reserve("abandoned-three")
        val restarted = f.manager()
        val recovered = restarted.reserve("first")
        assertEquals(id, recovered)
        assertEquals(2, restarted.state.value.remaining)
        assertEquals(15_000, restarted.state.value.partialMs)
        assertEquals(1, restarted.state.value.retainedVisits)
        assertEquals(0, restarted.state.value.activeVisits)
        restarted.setActive(id, true); f.time += 15_000; restarted.tick()
        assertEquals(1, restarted.state.value.used)
    }

    @Test fun `midnight resets admissions once and charged visits stay exempt across clock rollback`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        val id = meter.reserve("ongoing"); meter.setActive(id, true)
        f.time += 30_000; meter.tick()
        f.day++; meter.tick(); assertEquals(0, meter.state.value.used)
        f.time += 60_000; meter.tick(); assertEquals(0, meter.state.value.used)
        val new = meter.reserve("new"); meter.setActive(new, true)
        f.time += 30_000; meter.tick(); assertEquals(1, meter.state.value.used)
        f.day--; meter.tick(); assertEquals(1, meter.state.value.used)
        f.day++; meter.tick(); assertEquals(1, meter.state.value.used)
    }

    @Test fun `Pro toggle bypasses limits but downgrade preserves admitted work and existing usage`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        repeat(3) { val id = meter.reserve("free-$it"); meter.setActive(id, true); f.time += 30_000; meter.tick(); meter.end(id) }
        f.pro.setDebugPro(true)
        val proVisit = meter.reserve("pro"); meter.setActive(proVisit, true)
        f.pro.setDebugPro(false); f.time += 60_000; meter.tick()
        assertEquals(3, meter.state.value.used)
        assertEquals(proVisit, meter.reserve("pro"))
        assertTrue(runCatching { meter.reserve("blocked") }.exceptionOrNull() is ProLimitReached)
        assertFalse(f.pro.state.value.pro)
    }

    @Test fun `failed durable reservation cannot start an attachment or leave a phantom slot`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        f.store.broken = true
        assertTrue(runCatching { meter.reserve("host") }.isFailure)
        assertTrue(meter.state.value.storageError)
        assertEquals(0, meter.state.value.retainedVisits)
        f.store.broken = false
        meter.tick(); assertEquals(0, meter.state.value.reserved)
        assertFalse(meter.state.value.storageError)
        assertNotNull(meter.reserve("host"))
    }

    @Test fun `usage diagnostics follow partial time reservations coverage and daily rollover`() = runTest {
        val f = Fixture(backgroundScope); val meter = f.manager(); runCurrent()
        assertTrue(meter.state.value.loaded)
        val first = meter.reserve("first")
        val second = meter.reserve("second")
        f.time += 90_000; meter.tick()
        assertEquals(0, meter.state.value.partialMs)
        assertEquals(2, meter.state.value.reserved)
        assertEquals(2, meter.state.value.retainedVisits)
        assertEquals(1, meter.state.value.remaining)
        meter.setActive(first, true); f.time += 10_123; meter.tick()
        assertEquals(10_123, meter.state.value.partialMs)
        assertEquals(1, meter.state.value.activeVisits)
        meter.end(first)
        assertEquals(1, meter.state.value.retainedVisits)
        assertEquals(0, meter.state.value.activeVisits)
        assertEquals(10_123, meter.state.value.partialMs)
        meter.setActive(second, true); f.time += 19_877; meter.tick()
        assertEquals(1, meter.state.value.used)
        assertEquals(0, meter.state.value.partialMs)
        assertEquals(0, meter.state.value.reserved)
        assertEquals(1, meter.state.value.coveredVisits)
        meter.setActive(second, false)
        f.pro.setDebugPro(true)
        val third = meter.reserve("pro"); meter.setActive(third, true)
        f.time += 60_000; meter.tick()
        assertEquals(2, meter.state.value.coveredVisits)
        assertEquals(1, meter.state.value.activeVisits)
        assertEquals(1, meter.state.value.used)
        meter.end(third); f.day++; meter.tick()
        assertEquals(f.day, meter.state.value.day)
        assertEquals(0, meter.state.value.used)
        assertEquals(3, meter.state.value.remaining)
        assertEquals(1, meter.state.value.retainedVisits)
        assertEquals(1, meter.state.value.coveredVisits)
    }
}
