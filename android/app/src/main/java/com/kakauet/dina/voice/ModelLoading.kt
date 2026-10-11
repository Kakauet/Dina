package com.kakauet.dina.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs model loads one at a time. A new load cancels the one in progress and starts only after it
 * has really ended, cleanup included, so two loads never create native objects (ONNX Runtime
 * sessions, llama.cpp's process-wide model) or write the service's fields at the same time.
 */
class SerialLoads(private val scope: CoroutineScope) {
    private val turn = Mutex()
    private val lock = Any()
    private var current: Job? = null

    /** A load is queued or running. */
    val isActive get() = synchronized(lock) { current?.isActive == true }

    fun launch(context: CoroutineContext = EmptyCoroutineContext, load: suspend CoroutineScope.() -> Unit): Job = synchronized(lock) {
        current?.cancel()
        // The mutex is FIFO and a cancelled waiter leaves the queue: only the newest load runs, after the last one ends.
        scope.launch(context) { turn.withLock { load() } }.also { current = it }
    }

    fun cancel() = synchronized(lock) { current?.cancel() }
}

/**
 * What a load has created and not handed over yet. If the load fails or is cancelled, [close]
 * releases it all (newest first); once the service owns the objects, [handOff] forgets them.
 * Register each object inside the same block that creates it: a `withContext` that is cancelled
 * while its block runs discards the block's result, so registering afterwards would leak it.
 */
class LoadedResources : AutoCloseable {
    private val owned = ArrayList<AutoCloseable>()

    fun <T : AutoCloseable> own(resource: T): T = synchronized(owned) { owned += resource; resource }

    /** For an object now owned by another one that closes it (a voice wrapped by [PolishedVoice]). */
    fun disown(resource: AutoCloseable) = synchronized(owned) { owned.removeAll { it === resource } }

    fun handOff() = synchronized(owned) { owned.clear() }

    override fun close() {
        val all = synchronized(owned) { owned.toList().also { owned.clear() } }
        all.asReversed().forEach { runCatching { it.close() } }
    }
}
