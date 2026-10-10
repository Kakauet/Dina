package com.kakauet.dina.ui.character

import com.kakauet.dina.core.VoiceState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Everything that makes up an expression, as plain floats so two poses can be blended.
 * Faces and bodies only read this; [choreograph] decides it from the state and the clock.
 */
internal class Pose {
    /** 0 closed, 1 open. */
    var eyeOpen = 1f
    /** 0 normal, 1 smiling crescent eyes. */
    var eyeHappy = 0f
    var eyeScale = 1f
    /** -1 left/up … 1 right/down. Moves the face across the body like a head turn. */
    var lookX = 0f
    var lookY = 0f
    /** Brow visibility and tilt (+ worried, inner ends up; - frown). */
    var brow = 0f
    var browTilt = 0f
    var browLift = 0f
    var mouthOpen = 0f
    /** -1 sad … 1 big smile. */
    var smile = 0.4f
    var mouthWidth = 1f
    var blush = 0.6f
    /** Degrees, clockwise. */
    var tilt = 0f
    /** 0..1 towards the viewer. */
    var lean = 0f
    /** + squashed, - stretched. */
    var squash = 0f
    /** Jump height as a fraction of the canvas. */
    var hop = 0f
    /** Character-specific appendage: leaves, fur, tip. -1 droopy … 1 perky. */
    var perk = 0f
    /** Extra appendage motion (wiggle) in [-1, 1]. */
    var wiggle = 0f
    var sweat = 0f
    var thinking = 0f
    var sparkle = 0f
    var sleepy = 0f
    /** Sound ripples: [listen] flow towards her (your voice), [voice] flow out (hers). 0..1 = audio level. */
    var listen = 0f
    var voice = 0f
    /** 0..1: a hand raised and waving hello (designs without arms just look happy). */
    var wave = 0f
    /** Free-running phase for overlays (thought dots, sparkles, z's). */
    var overlayTime = 0f

    fun set(from: Pose) = blend(from, from, 0f)

    fun blend(a: Pose, b: Pose, t: Float) {
        fun mix(x: Float, y: Float) = x + (y - x) * t
        eyeOpen = mix(a.eyeOpen, b.eyeOpen); eyeHappy = mix(a.eyeHappy, b.eyeHappy); eyeScale = mix(a.eyeScale, b.eyeScale)
        lookX = mix(a.lookX, b.lookX); lookY = mix(a.lookY, b.lookY)
        brow = mix(a.brow, b.brow); browTilt = mix(a.browTilt, b.browTilt); browLift = mix(a.browLift, b.browLift)
        mouthOpen = mix(a.mouthOpen, b.mouthOpen); smile = mix(a.smile, b.smile); mouthWidth = mix(a.mouthWidth, b.mouthWidth)
        blush = mix(a.blush, b.blush); tilt = mix(a.tilt, b.tilt); lean = mix(a.lean, b.lean)
        squash = mix(a.squash, b.squash); hop = mix(a.hop, b.hop); perk = mix(a.perk, b.perk); wiggle = mix(a.wiggle, b.wiggle)
        sweat = mix(a.sweat, b.sweat); thinking = mix(a.thinking, b.thinking); sparkle = mix(a.sparkle, b.sparkle)
        sleepy = mix(a.sleepy, b.sleepy); listen = mix(a.listen, b.listen); voice = mix(a.voice, b.voice)
        wave = mix(a.wave, b.wave)
        overlayTime = b.overlayTime
    }

    fun reset() = set(Rest)

    private companion object { val Rest = Pose() }
}

private const val TAU = (2 * PI).toFloat()

private fun wave(t: Float, period: Float, phase: Float = 0f) = sin((t / period + phase) * TAU)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 1 → 0 → 1 dip while a blink happens. Blinks fall at irregular times (sometimes doubled). */
private fun blink(t: Float): Float {
    val cell = 4.2f
    val k = floor(t / cell).toInt()
    val at = k * cell + (hash(401, k) * 0.5f + 0.5f) * (cell - 0.6f)
    var closed = 1f - smoothstep(0f, 0.09f, abs(t - at))
    if (hash(409, k) > 0.55f) closed = maxOf(closed, 1f - smoothstep(0f, 0.08f, abs(t - at - 0.26f)))
    return 1f - closed
}

/** Occasional glance to one side and back: returns -1..1. */
private fun glance(t: Float): Float {
    val cell = 7f
    val k = floor(t / cell).toInt()
    if (hash(503, k) < -0.1f) return 0f
    val start = k * cell + 1f + (hash(509, k) * 0.5f + 0.5f) * 3f
    val side = if (hash(521, k) > 0f) 1f else -1f
    val x = t - start
    return side * smoothstep(0f, 0.35f, x) * (1f - smoothstep(1.5f, 1.9f, x))
}

/**
 * Writes the pose for [state] at clock [t] (seconds) into [out].
 * [since] is the time spent in this state; [level] the live audio level 0..1.
 */
internal fun choreograph(state: VoiceState, t: Float, since: Float, level: Float, motion: Boolean, out: Pose, greeting: Boolean = false) {
    out.reset()
    out.overlayTime = t
    val breath = if (motion) wave(t, 3.6f) else 0f
    out.squash = 0.018f * breath
    out.eyeOpen = if (motion) blink(t) else 1f
    out.wiggle = if (motion) wave(t, 2.8f) * 0.25f else 0f
    when (state) {
        VoiceState.IDLE -> {
            out.smile = 0.45f
            if (motion) {
                val g = glance(t)
                out.lookX = g * 0.85f
                out.tilt = g * 2.5f
            }
        }
        VoiceState.LOADING -> {
            // Waking up: heavy eyelids, a yawn-and-stretch every few seconds.
            val cycle = if (motion) (t % 5.5f) / 5.5f else 0f
            val yawn = smoothstep(0.45f, 0.6f, cycle) * (1f - smoothstep(0.78f, 0.92f, cycle))
            out.sleepy = 1f - yawn
            out.eyeOpen = 0.32f + 0.12f * (if (motion) wave(t, 2.2f) else 0f) - 0.3f * yawn
            out.mouthOpen = 0.75f * yawn
            out.mouthWidth = 0.75f
            out.smile = 0.15f
            out.squash = -0.1f * yawn + 0.03f * (1f - yawn) + 0.012f * breath
            out.tilt = -4f * (1f - yawn) + 3f * yawn
            out.perk = -0.6f + 1.2f * yawn
            out.blush = 0.35f
            out.brow = 0.4f * yawn
            out.browTilt = -0.4f
            out.lookY = 0.25f
        }
        VoiceState.ACTIVATED -> {
            val pop = exp(-since * 5f)
            out.eyeScale = 1.16f
            out.eyeOpen = maxOf(out.eyeOpen, 0.9f)
            out.mouthOpen = 0.28f
            out.mouthWidth = 0.55f
            out.smile = 0.5f
            out.hop = if (motion) 0.05f * pop * abs(sin(since * 11f)) else 0f
            out.squash = if (motion) -0.06f * pop * sin(since * 11f) else 0f
            out.perk = 1f
            out.brow = 0.7f
            out.browLift = 1f
            out.lean = 0.6f
            out.sparkle = pop
            // "¡Hola!": she waves when you call her.
            out.wave = 1f
        }
        VoiceState.LISTENING -> {
            out.lean = 1f
            out.tilt = 6f + (if (motion) wave(t, 4f) * 1.5f else 0f)
            out.eyeScale = 1.08f + level * 0.12f
            out.brow = 0.75f
            out.browLift = 0.6f + level * 0.6f
            out.smile = 0.35f
            out.mouthWidth = 0.8f
            out.squash = 0.012f * breath - level * 0.07f
            out.perk = 0.6f + level * 0.5f
            out.wiggle = level * (if (motion) wave(t, 0.35f) else 0f)
            out.listen = level
        }
        VoiceState.TRANSCRIBING, VoiceState.THINKING -> {
            val sway = if (motion) wave(t, 3.2f) else 0f
            out.lookX = 0.45f + 0.15f * sway
            out.lookY = -0.85f
            out.tilt = -5f + 2f * sway
            out.smile = 0.05f
            out.mouthWidth = 0.6f
            out.brow = 0.85f
            out.browTilt = -0.15f
            out.browLift = 0.7f
            out.thinking = 1f
            out.perk = 0.2f
        }
        VoiceState.EXECUTING -> {
            val bounce = if (motion) abs(sin(since * 7f)) * exp(-since * 1.8f) else 0f
            out.eyeHappy = 1f
            out.eyeOpen = 1f
            out.smile = 1f
            out.mouthOpen = 0.3f
            out.mouthWidth = 1.1f
            out.hop = 0.045f * bounce
            out.squash = 0.04f * (1f - bounce) * exp(-since * 2f) + 0.015f * breath
            out.perk = 1f
            out.sparkle = 1f
            out.blush = 1f
        }
        VoiceState.SPEAKING -> {
            val talk = if (motion) 0.5f + 0.5f * smoothNoise(907, t * 9f) else 0.5f
            out.mouthOpen = (level * 1.25f * (0.75f + 0.5f * talk)).coerceIn(0f, 1f)
            out.mouthWidth = 0.85f + 0.25f * talk * level
            out.smile = 0.55f
            out.tilt = if (motion) 2.5f * wave(t, 2.4f) else 0f
            out.squash = 0.012f * breath + level * 0.03f
            out.perk = 0.4f + level * 0.4f
            out.brow = 0.35f
            out.browLift = level
            out.blush = 0.75f
            out.voice = level
        }
        VoiceState.FOLLOW_UP -> {
            out.tilt = 9f + (if (motion) wave(t, 3f) * 2f else 0f)
            out.smile = 0.8f
            out.eyeHappy = 0.3f
            out.brow = 0.6f
            out.browLift = 0.9f
            out.lean = 0.5f
            out.perk = 0.7f
            out.blush = 0.85f
            out.hop = if (motion) 0.012f * (0.5f + 0.5f * wave(t, 1.6f)) else 0f
        }
        VoiceState.ERROR -> {
            out.brow = 1f
            out.browTilt = 1f
            out.smile = -0.55f
            out.mouthWidth = 0.7f
            out.eyeScale = 0.9f
            out.eyeOpen *= 0.85f
            out.lookY = 0.3f
            out.tilt = -4f
            out.squash = 0.035f + 0.012f * breath
            out.perk = -1f
            out.sweat = 1f
            out.blush = 0.3f
        }
    }
    if (greeting) {
        out.wave = 1f
        out.smile = maxOf(out.smile, 0.85f)
        out.mouthOpen = maxOf(out.mouthOpen, 0.3f)
        out.mouthWidth = 1f
        out.eyeHappy = maxOf(out.eyeHappy, 0.45f)
        out.blush = 1f
        out.tilt += 5f
        out.perk = maxOf(out.perk, 0.6f)
    }
}
