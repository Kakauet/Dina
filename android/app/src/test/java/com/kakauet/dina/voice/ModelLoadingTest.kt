package com.kakauet.dina.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ModelLoadingTest {
    private class Closeable(private val name: String, private val log: MutableList<String>, private val fail: Boolean = false) : AutoCloseable {
        override fun close() {
            log += "close $name"
            if (fail) error("close failed")
        }
    }

    @Test
    fun aNewLoadStartsOnlyAfterTheCancelledOneHasCleanedUp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loads = SerialLoads(scope)
        val events = CopyOnWriteArrayList<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val first = loads.launch {
            events += "first starts"
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                // Closing native objects takes a while: the next load must not overlap it.
                withContext(NonCancellable) { delay(100) }
                events += "first closed"
            }
        }
        firstStarted.await()
        val second = loads.launch { events += "second starts" }
        withTimeout(5_000) { joinAll(first, second) }
        assertEquals(listOf("first starts", "first closed", "second starts"), events.toList())
        scope.cancel()
    }

    @Test
    fun rapidReloadsNeverOverlapAndOnlyTheLastOneFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loads = SerialLoads(scope)
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val finished = CopyOnWriteArrayList<Int>()
        val jobs = (1..5).map { n ->
            loads.launch {
                val now = running.incrementAndGet()
                maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    delay(50)
                    finished += n
                } finally {
                    withContext(NonCancellable) { delay(20) }
                    running.decrementAndGet()
                }
            }.also { yield() }
        }
        withTimeout(5_000) { jobs.joinAll() }
        assertEquals(1, maxRunning.get())
        assertEquals(listOf(5), finished.toList())
        assertFalse(loads.isActive)
        scope.cancel()
    }

    @Test
    fun cancelStopsTheLoadInProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loads = SerialLoads(scope)
        val started = CompletableDeferred<Unit>()
        val job = loads.launch { started.complete(Unit); awaitCancellation() }
        started.await()
        assertTrue(loads.isActive)
        loads.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertFalse(loads.isActive)
        scope.cancel()
    }

    @Test
    fun aCancelledLoadClosesWhatItCreatedNewestFirst() = runBlocking {
        val log = CopyOnWriteArrayList<String>()
        val created = LoadedResources()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inVoice = CompletableDeferred<Unit>()
        val job = scope.launch {
            try {
                created.own(Closeable("llm", log))
                // Registered inside the block that creates it: survives the block's result being discarded.
                withContext(Dispatchers.IO) {
                    val voice = created.own(Closeable("voice", log))
                    inVoice.complete(Unit)
                    Thread.sleep(100) // a native constructor that ignores cancellation
                    voice
                }
                created.own(Closeable("wake", log))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                created.close()
                throw cancelled
            }
        }
        inVoice.await()
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals(listOf("close voice", "close llm"), log.toList())
        scope.cancel()
    }

    @Test
    fun handedOffResourcesAreNotClosedAndCloseFailuresDoNotStopTheRest() {
        val log = CopyOnWriteArrayList<String>()
        val published = LoadedResources()
        published.own(Closeable("published", log))
        published.handOff()
        published.close()
        assertEquals(emptyList<String>(), log.toList())

        val failing = LoadedResources()
        failing.own(Closeable("a", log))
        failing.own(Closeable("b", log, fail = true))
        val wrapped = failing.own(Closeable("inner voice", log))
        failing.disown(wrapped)
        failing.close()
        failing.close() // closing twice closes nothing more
        assertEquals(listOf("close b", "close a"), log.toList())
    }
}
