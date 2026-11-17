package br.com.lincon.phototool

import br.com.lincon.phototool.remote.BoundedPreviewCache
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedPreviewCacheTest {
    @Test fun sameVersionSharesOneReadAndOneCacheCharge() = runTest {
        val cache = BoundedPreviewCache<String>(backgroundScope, 2) { 1 }
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val a = async { cache.load("a?v=1") { reads++; gate.await(); "a" } }
        val b = async { cache.load("a?v=1") { reads++; "duplicate" } }
        runCurrent(); assertEquals(1, reads)
        gate.complete(Unit); runCurrent()
        assertEquals("a", a.await()); assertEquals("a", b.await())
        cache.load("b") { "b" }
        assertEquals("a", cache.load("a?v=1") { error("double cache charge evicted a") })
    }

    @Test fun evictionIsByteBoundedAndUsesRecencyNotCollectionSize() = runTest {
        val cache = BoundedPreviewCache<String>(backgroundScope, 4) { it.length.toLong() }
        cache.load("a") { "aa" }; cache.load("b") { "bb" }
        cache.load("a") { error("miss") }; cache.load("c") { "cc" }
        assertEquals("aa", cache.load("a") { error("recent item was evicted") })
        assertEquals("new-b", cache.load("b") { "new-b" })
        assertEquals("again-b", cache.load("b") { "again-b" }, "Oversized values must not enter the cache")
    }

    @Test fun cancelOneReaderKeepsTheOtherButLastCancellationStopsTheRead() = runTest {
        val cache = BoundedPreviewCache<String>(backgroundScope, 4) { 1 }
        val gate = CompletableDeferred<Unit>()
        var cancelled = 0
        val a = launch { cache.load("a") { try { gate.await(); "a" } finally { cancelled++ } } }
        val b = async { cache.load("a") { error("duplicate") } }
        runCurrent(); a.cancel(); runCurrent(); assertEquals(0, cancelled)
        gate.complete(Unit); runCurrent(); assertEquals("a", b.await())
        val c = launch { cache.load("c") { try { awaitCancellation() } finally { cancelled++ } } }
        runCurrent(); c.cancel(); runCurrent(); assertEquals(2, cancelled)
        assertEquals("retry", cache.load("c") { "retry" })
    }

    @Test fun failuresAreRetryableAndCannotCancelTheHostScope() = runTest {
        val cache = BoundedPreviewCache<String>(this, 4) { 1 }
        assertNull(cache.load("a") { error("decode failed") })
        assertNull(cache.load("a") { throw Error("Browser fetch failed") })
        assertTrue(currentCoroutineContext().isActive)
        assertEquals("ok", cache.load("a") { "ok" })
        assertEquals("new-version", cache.load("a?v=2") { "new-version" })
    }

    @Test fun activeReadsNeverExceedFourAndCancelledStaleReadCannotPopulateTheCache() = runTest {
        val cache = BoundedPreviewCache<String>(backgroundScope, 4) { 1 }
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val jobs = (1..10).map { id -> launch {
            cache.load("$id") {
                active++; peak = maxOf(peak, active)
                try { release.await(); "$id" } finally { active-- }
            }
        } }
        runCurrent(); assertEquals(4, peak)
        jobs.forEach { it.cancel() }; runCurrent(); assertEquals(0, active)
        val late = launch { cache.load("late") { withContext(NonCancellable) { release.await() }; "stale" } }
        runCurrent(); late.cancel(); runCurrent(); release.complete(Unit); runCurrent()
        assertEquals("fresh", cache.load("late") { "fresh" })
    }
}
