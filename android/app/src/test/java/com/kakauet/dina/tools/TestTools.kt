package com.kakauet.dina.tools

import com.kakauet.dina.brain.EngineExecutor
import java.time.LocalDateTime
import java.time.ZoneId

/** Deterministic engine for tests: fixed zone, controllable clock, in-memory ports. */
class TestTools(start: LocalDateTime = LocalDateTime.of(2026, 10, 5, 10, 0)) {
    val zone: ZoneId = ZoneId.of("Europe/Madrid")
    var now: Long = start.atZone(zone).toInstant().toEpochMilli()
    val store = MemoryStore()
    val alerts = RecordingAlerts()
    val volume = FakeVolume()
    val engine by lazy { ToolEngine(store, alerts, volume, clock = { now }, zone = { zone }) }
    val executor by lazy { EngineExecutor(engine) }

    fun advance(ms: Long) { now += ms }

    fun ok(command: ToolCommand): ToolData {
        val outcome = engine.execute(command)
        check(outcome is ToolOutcome.Success) { "Expected success for $command, got $outcome" }
        return outcome.data
    }

    fun failure(command: ToolCommand): String {
        val outcome = engine.execute(command)
        check(outcome is ToolOutcome.Failure) { "Expected failure for $command, got $outcome" }
        return outcome.code
    }
}

class MemoryStore(var world: ToolWorld? = null) : ToolStore {
    var saves = 0
    override fun load() = world
    override fun save(world: ToolWorld) { this.world = world; saves++ }
}

class RecordingAlerts : AlertScheduler {
    val scheduled = mutableMapOf<String, Long>()
    override fun schedule(kind: AlertKind, id: String, atMs: Long, label: String?) { scheduled["${kind.code}/$id"] = atMs }
    override fun cancel(kind: AlertKind, id: String) { scheduled.remove("${kind.code}/$id") }
}

class FakeVolume(var value: Int = 50, var mutedFlag: Boolean = false) : VolumeControl {
    override fun percent() = value
    override fun isMuted() = mutedFlag
    override fun setPercent(percent: Int) { value = percent; mutedFlag = false }
}

const val MINUTE = 60_000L
