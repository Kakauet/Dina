package com.kakauet.dina.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncToolStoreTest {
    /** Blocks every write until [gate] opens, like a slow disk. */
    private class SlowStore(private val gate: CountDownLatch) : ToolStore {
        val written = mutableListOf<ToolWorld>()
        override fun load(): ToolWorld? = written.lastOrNull()
        override fun save(world: ToolWorld) { gate.await(5, TimeUnit.SECONDS); synchronized(written) { written += world } }
    }

    private fun world(n: Int) = ToolWorld(counters = mapOf("timer" to n))

    @Test
    fun theTurnDoesNotWaitForTheDiskAndTheLatestStateWins() {
        val gate = CountDownLatch(1)
        val slow = SlowStore(gate)
        val store = AsyncToolStore(slow)
        val started = System.nanoTime()
        (1..5).forEach { store.save(world(it)) }
        assertTrue("save must not block", (System.nanoTime() - started) / 1_000_000 < 1_000)
        gate.countDown()
        store.flush()
        // The first write may have started before the others; the last one written is always the latest.
        assertEquals(world(5), slow.written.last())
        assertTrue(slow.written.size <= 2)
    }

    @Test
    fun theEnginePersistsThroughItAndAFailedWriteIsReported() {
        val memory = MemoryStore()
        val async = AsyncToolStore(memory)
        ToolEngine(async, RecordingAlerts(), FakeVolume()).execute(TimerCommand.Create(MINUTE))
        async.flush()
        // A new engine (next app start) reads what the first one wrote.
        assertEquals(1, ToolEngine(AsyncToolStore(memory), RecordingAlerts(), FakeVolume()).state.value.timers.size)

        val errors = mutableListOf<Throwable>()
        val failing = AsyncToolStore(object : ToolStore {
            override fun load(): ToolWorld? = null
            override fun save(world: ToolWorld) = error("disco lleno")
        }, onError = { synchronized(errors) { errors += it } })
        failing.save(ToolWorld())
        failing.flush()
        assertEquals("disco lleno", errors.single().message)
    }

    @Test
    fun aFailedWriteDoesNotStopTheNextOnes() {
        val memory = MemoryStore()
        var full = true
        val errors = mutableListOf<Throwable>()
        val store = AsyncToolStore(object : ToolStore {
            override fun load() = memory.load()
            override fun save(world: ToolWorld) { if (full) { full = false; error("disco lleno") }; memory.save(world) }
        }, onError = { synchronized(errors) { errors += it } })
        store.save(world(1))
        store.flush()
        store.save(world(2))
        store.flush()
        assertEquals(world(2), memory.world)
        assertEquals(1, errors.size)
    }
}
