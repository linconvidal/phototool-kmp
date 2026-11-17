package br.com.lincon.phototool.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Versioned single-flight LRU. Only consumers keep work alive; failures are never cached. */
class BoundedPreviewCache<T : Any>(
    private val scope: CoroutineScope,
    private val maximumBytes: Long,
    concurrency: Int = 4,
    private val cost: (T) -> Long,
) {
    private class Flight<T>(val task: Deferred<T?>, var readers: Int = 0)
    private val lock = Mutex()
    private val slots = Semaphore(concurrency)
    private val cached = linkedMapOf<String, T>()
    private val flights = mutableMapOf<String, Flight<T>>()
    private var bytes = 0L

    init { require(maximumBytes > 0) }

    suspend fun load(key: String, read: suspend () -> T?): T? {
        val flight = lock.withLock {
            cached.remove(key)?.let { cached[key] = it; return it }
            flights.getOrPut(key) {
                Flight(scope.async(start = CoroutineStart.LAZY) {
                    slots.withPermit {
                        val value = try { read() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Throwable) { null }
                        currentCoroutineContext().ensureActive()
                        if (value != null) lock.withLock { remember(key, value) }
                        value
                    }
                })
            }.also { it.readers++ }
        }
        try { return flight.task.await() }
        finally {
            withContext(NonCancellable) {
                lock.withLock {
                    flight.readers--
                    if (flight.readers == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        flight.task.cancel()
                    }
                }
            }
        }
    }

    private fun remember(key: String, value: T) {
        val size = cost(value)
        if (size !in 1..maximumBytes) return
        cached.remove(key)?.let { bytes -= cost(it) }
        while (bytes + size > maximumBytes && cached.isNotEmpty()) {
            bytes -= cost(cached.remove(cached.keys.first())!!)
        }
        cached[key] = value
        bytes += size
    }
}
