package com.kakauet.dina.tools

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Persists off the caller's thread, so a turn never waits for the disk. Saves are coalesced:
 * only the latest world is written, always in order, by one background thread. [flush] waits
 * for the pending write (tests, shutdown). A failed write is reported and retried by the next save.
 */
class AsyncToolStore(
    private val inner: ToolStore,
    private val onError: (Throwable) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "dina-tool-store").apply { isDaemon = true } },
) : ToolStore {
    private val lock = Any()
    private var pending: ToolWorld? = null

    override fun load(): ToolWorld? = inner.load()

    override fun save(world: ToolWorld) {
        val schedule = synchronized(lock) {
            val idle = pending == null
            pending = world
            idle
        }
        if (schedule) executor.execute(::writeLatest)
    }

    /** Waits until everything saved so far is on disk (at most [timeoutMs]). */
    fun flush(timeoutMs: Long = 5_000) {
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun writeLatest() {
        val world = synchronized(lock) { pending.also { pending = null } } ?: return
        try {
            inner.save(world)
        } catch (error: Throwable) {
            onError(error)
        }
    }
}
