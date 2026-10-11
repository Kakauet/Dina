package com.kakauet.dina.ui.character

import com.kakauet.dina.core.VoiceState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Everything that makes up an expression, as plain floats so two poses can be blended.
 * Faces and bodies only read this; [choreograph] decides it from the state and the clock,
 * [Accents] add the one-shot moves and the renderer adds the secondary motion ([flop], [sway]).
 */
internal class Pose {
    /** 0 closed, 1 open. */
    var eyeOpen = 1f
    /** 0 normal, 1 smiling crescent eyes. */
    var eyeHappy = 0f
    var eyeScale = 1f
    /** 0..1: her left eye (on the right of the screen) closes into a happy arc. */
    var wink = 0f
    /** -1 left/up … 1 right/down. Moves the face across the body like a head turn. */
    var lookX = 0f
    var lookY = 0f
    /** Brow visibility and tilt (+ worried, inner ends up; - frown). */
    var brow = 0f
    var browTilt = 0f
    var browLift = 0f
    /** -1..1: one brow higher than the other (+ lifts the one on the right of the screen). */
    var browAsym = 0f
    var mouthOpen = 0f
    /** -1 sad … 1 big smile. */
    var smile = 0.4f
    var mouthWidth = 1f
    /** 0..1: an open mouth rounds into an "o". */
    var mouthRound = 0f
    /** -1..1: the mouth slides to one side and one corner rises ("hmm"). */
    var mouthShift = 0f
    var blush = 0.6f
    /** Degrees, clockwise. */
    var tilt = 0f
    /** 0..1 towards the viewer. */
    var lean = 0f
    /** + squashed, - stretched. */
    var squash = 0f
    /** Jump height as a fraction of the canvas. */
    var hop = 0f
    /** Sideways shift as a fraction of the canvas (a shiver). */
    var shiftX = 0f
    /** Character-specific appendage: leaves, fur, tip. -1 droopy … 1 perky. */
    var perk = 0f
    /** Extra appendage motion (wiggle) in [-1, 1]. */
    var wiggle = 0f
    /** 0..1: both arms (or leaves) up, cheering or stretching. */
    var cheer = 0f
    /** 0..1: one hand up by her head, tapping while she thinks. */
    var scratch = 0f
    /** 0..1: a little emphasis gesture of the arm (or leaves) while she talks. */
    var beat = 0f
    /** -1..1: which foot is lifted (Musgo taps along when she hums). */
    var step = 0f
    var sweat = 0f
    var thinking = 0f
    /** 0..1 what the biggest thought bubble holds: three typing dots or a little sound wave. */
    var dots = 0f
    var squiggle = 0f
    var sparkle = 0f
    var sleepy = 0f
    /** Sound ripples: [listen] flow towards her (your voice), [voice] flow out (hers). 0..1 = audio level. */
    var listen = 0f
    var voice = 0f
    /** 0..1: a hand raised and waving hello (Brote waves a leaf). */
    var wave = 0f
    /** 0..1: a music note floats up while she hums; [notePhase] is how far it went. */
    var note = 0f
    var notePhase = 0f
    /** 0..1 progress of the confetti of a finished task; 0 when there is none. */
    var burst = 0f
    /** Secondary motion: how far the appendages lag behind the body, down (+, fraction of the canvas) and around (degrees). */
    var flop = 0f
    var sway = 0f
    /** Free-running phase for overlays (thought dots, sparkles, z's). */
    var overlayTime = 0f

    fun set(from: Pose) = blend(from, from, 0f)

    fun blend(a: Pose, b: Pose, t: Float) {
        fun mix(x: Float, y: Float) = x + (y - x) * t
        eyeOpen = mix(a.eyeOpen, b.eyeOpen); eyeHappy = mix(a.eyeHappy, b.eyeHappy); eyeScale = mix(a.eyeScale, b.eyeScale)
        wink = mix(a.wink, b.wink); lookX = mix(a.lookX, b.lookX); lookY = mix(a.lookY, b.lookY)
        brow = mix(a.brow, b.brow); browTilt = mix(a.browTilt, b.browTilt); browLift = mix(a.browLift, b.browLift)
        browAsym = mix(a.browAsym, b.browAsym)
        mouthOpen = mix(a.mouthOpen, b.mouthOpen); smile = mix(a.smile, b.smile); mouthWidth = mix(a.mouthWidth, b.mouthWidth)
        mouthRound = mix(a.mouthRound, b.mouthRound); mouthShift = mix(a.mouthShift, b.mouthShift)
        blush = mix(a.blush, b.blush); tilt = mix(a.tilt, b.tilt); lean = mix(a.lean, b.lean)
        squash = mix(a.squash, b.squash); hop = mix(a.hop, b.hop); shiftX = mix(a.shiftX, b.shiftX)
        perk = mix(a.perk, b.perk); wiggle = mix(a.wiggle, b.wiggle)
        cheer = mix(a.cheer, b.cheer); scratch = mix(a.scratch, b.scratch); beat = mix(a.beat, b.beat); step = mix(a.step, b.step)
        sweat = mix(a.sweat, b.sweat); thinking = mix(a.thinking, b.thinking); dots = mix(a.dots, b.dots); squiggle = mix(a.squiggle, b.squiggle)
        sparkle = mix(a.sparkle, b.sparkle); sleepy = mix(a.sleepy, b.sleepy); listen = mix(a.listen, b.listen); voice = mix(a.voice, b.voice)
        wave = mix(a.wave, b.wave); note = mix(a.note, b.note)
        notePhase = b.notePhase
        burst = b.burst
        flop = b.flop
        sway = b.sway
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

/** Rises over [rise] s from [x] = 0 and falls back over [fall] s before [length]: an action's envelope. */
private fun envelope(x: Float, length: Float, rise: Float, fall: Float) =
    smoothstep(0f, rise, x) * (1f - smoothstep(length - fall, length, x))

/** Eye openness [x] s into a blink: lids drop fast, rest a moment and lift slower, as real blinks do. */
internal fun blinkShape(x: Float): Float = when {
    x <= 0f -> 1f
    x < 0.065f -> 1f - smoothstep(0f, 0.065f, x)
    x < 0.1f -> 0f
    else -> smoothstep(0.1f, 0.24f, x)
}

/** Blinks at irregular times, sometimes doubled. */
private fun blink(t: Float): Float {
    val cell = 4.2f
    val k = floor(t / cell).toInt()
    val at = k * cell + (hash(401, k) * 0.5f + 0.5f) * (cell - 0.6f)
    var open = blinkShape(t - at)
    if (hash(409, k) > 0.55f) open = minOf(open, blinkShape(t - at - 0.3f))
    return open
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
    // A breeze: the leaves (and Musgo's sprout) never stand quite still.
    out.wiggle = if (motion) 0.16f * wave(t, 2.8f) + 0.14f * smoothNoise(601, t * 0.8f) else 0f
    when (state) {
        VoiceState.IDLE -> idle(t, motion && !greeting, out)
        VoiceState.LOADING -> {
            // Waking up: heavy eyelids, and every few seconds a yawn with a big stretch.
            val cycle = if (motion) (t % 5.5f) / 5.5f else 0f
            val yawn = smoothstep(0.45f, 0.6f, cycle) * (1f - smoothstep(0.78f, 0.92f, cycle))
            out.sleepy = 1f - yawn
            out.eyeOpen = 0.3f + 0.1f * (if (motion) wave(t, 2.2f) else 0f) - 0.28f * yawn
            out.mouthOpen = 0.8f * yawn
            out.mouthRound = 0.7f
            out.mouthWidth = 0.75f
            out.smile = 0.15f
            out.squash = -0.1f * yawn + 0.03f * (1f - yawn) + 0.012f * breath
            out.tilt = -4f * (1f - yawn) + 3f * yawn + (if (motion) 1.2f * wave(t, 4.4f) else 0f)
            out.perk = -0.6f + 1.3f * yawn
            out.cheer = 0.8f * yawn
            out.blush = 0.35f
            out.brow = 0.4f * yawn
            out.browTilt = -0.4f
            out.lookY = 0.25f
        }
        VoiceState.ACTIVATED -> {
            // "¿Sí?": wide eyes, a little "oh", a wave. The hop itself is an accent.
            val pop = if (motion) exp(-since * 5f) else 0f
            out.eyeScale = 1.14f + 0.08f * pop
            out.eyeOpen = maxOf(out.eyeOpen, 0.9f)
            out.mouthOpen = 0.32f
            out.mouthRound = 0.75f
            out.mouthWidth = 0.6f
            out.smile = 0.5f
            out.perk = 1f
            out.brow = 0.7f
            out.browLift = 1f
            out.lean = 0.6f
            out.sparkle = if (motion) pop else 0.6f
            out.wave = 1f
        }
        VoiceState.LISTENING -> {
            out.lean = 1f
            out.tilt = 6f + (if (motion) wave(t, 4f) * 1.5f else 0f)
            out.eyeScale = 1.08f + level * 0.1f
            out.brow = 0.75f
            out.browLift = 0.5f + level * 0.6f
            out.smile = 0.35f
            out.mouthWidth = 0.8f
            out.squash = 0.012f * breath - level * 0.06f
            out.perk = 0.6f + level * 0.5f
            out.wiggle += level * (if (motion) wave(t, 0.35f) * 0.6f else 0f)
            out.listen = level
            // "Ajá": small nods while you talk.
            if (motion) out.lookY = -0.12f + 0.22f * level * (0.5f + 0.5f * wave(t, 0.9f))
        }
        VoiceState.TRANSCRIBING -> {
            // Going over what you said: eyes aside, a focused little "mm" and a sound wave in the bubble.
            val nod = if (motion) wave(t, 0.7f) else 0f
            out.lookX = -0.45f + (if (motion) 0.12f * smoothNoise(701, t * 0.9f) else 0f)
            out.lookY = -0.35f + 0.08f * nod
            out.tilt = -3f + nod
            out.eyeOpen *= 0.85f
            out.smile = 0.1f
            out.mouthWidth = 0.5f
            out.brow = 0.75f
            out.browTilt = -0.3f
            out.browLift = 0.4f
            out.thinking = 1f
            out.squiggle = 1f
            out.perk = 0.3f
        }
        VoiceState.THINKING -> {
            // Eyes wander up, the mouth slides to one side, one brow up, a hand tapping her head.
            val sway = if (motion) wave(t, 3.2f) else 0f
            out.lookX = 0.45f + 0.15f * sway + (if (motion) 0.18f * smoothNoise(703, t * 0.7f) else 0f)
            out.lookY = -0.85f + (if (motion) 0.12f * smoothNoise(709, t * 0.9f) else 0f)
            out.tilt = -5f + 2f * sway
            out.smile = 0.05f
            out.mouthWidth = 0.6f
            out.mouthShift = 0.75f
            out.brow = 0.85f
            out.browTilt = -0.15f
            out.browLift = 0.7f
            out.browAsym = 0.7f
            out.thinking = 1f
            out.dots = 1f
            out.scratch = 1f
            out.perk = 0.2f
        }
        VoiceState.EXECUTING -> {
            // "¡Hecho!": crescent eyes and arms up. The jump and the confetti are accents.
            out.eyeHappy = 1f
            out.eyeOpen = 1f
            out.smile = 1f
            out.mouthOpen = 0.38f
            out.mouthWidth = 1.1f
            out.squash += 0.04f * (if (motion) exp(-since * 2f) else 0f)
            out.perk = 1f
            out.cheer = 1f
            out.sparkle = 1f
            out.blush = 1f
        }
        VoiceState.SPEAKING -> {
            val talk = if (motion) 0.5f + 0.5f * smoothNoise(907, t * 9f) else 0.5f
            // Vowels: the open mouth goes from a wide "a"/"e" to a round "o"/"u".
            val vowel = if (motion) (0.5f + 0.5f * smoothNoise(911, t * 4.3f)) else 0.3f
            out.mouthOpen = (level * 1.25f * (0.7f + 0.6f * talk)).coerceIn(0f, 1f)
            out.mouthRound = smoothstep(0.45f, 0.85f, vowel) * 0.85f
            out.mouthWidth = 0.85f + 0.3f * talk * level
            out.smile = 0.55f
            out.tilt = if (motion) 2.5f * wave(t, 2.4f) + 1.5f * smoothNoise(913, t * 0.8f) else 0f
            out.lookY = if (motion) 0.14f * level * smoothNoise(919, t * 4f) else 0f
            out.squash = 0.012f * breath + level * 0.03f
            out.perk = 0.4f + level * 0.4f
            out.brow = 0.35f + 0.25f * level
            out.browLift = level * 1.2f
            out.blush = 0.75f
            out.voice = level
            out.beat = (level * talk * 1.4f).coerceAtMost(1f)
            // Now and then she smiles with her eyes as she talks.
            if (motion) out.eyeHappy = 0.4f * smoothstep(0.35f, 0.7f, smoothNoise(929, t * 0.45f))
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
            // "¿Mm?": every few seconds one brow goes up.
            if (motion) {
                val ask = envelope(t % 4.2f - 2.4f, 1.1f, 0.2f, 0.35f)
                out.browAsym = 0.8f * ask
                out.browLift += 0.3f * ask
                out.tilt += 3f * ask
            }
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
            if (motion) {
                // A sigh every few seconds: breathe in (taller, eyes up), breathe out (sag, eyes half shut).
                val x = t % 4.6f - 2f
                val inhale = envelope(x, 0.9f, 0.4f, 0.3f)
                val exhale = envelope(x - 0.75f, 1.3f, 0.25f, 0.8f)
                out.squash += -0.04f * inhale + 0.05f * exhale
                out.lookY -= 0.4f * inhale
                out.eyeOpen *= 1f - 0.45f * exhale
                out.perk -= 0.4f * exhale
                out.mouthOpen = 0.18f * exhale
                out.mouthRound = 1f
            }
        }
    }
    if (greeting) {
        out.wave = 1f
        out.smile = maxOf(out.smile, 0.85f)
        out.mouthOpen = maxOf(out.mouthOpen, 0.3f)
        out.mouthWidth = 1f
        out.mouthRound = 0f
        out.eyeHappy = maxOf(out.eyeHappy, 0.45f)
        out.blush = 1f
        out.tilt += 5f
        out.perk = maxOf(out.perk, 0.6f)
        // A wink now and then (never at the start, which is what the launcher icon shows).
        if (motion) out.wink = envelope(t % 5f - 2.6f, 0.55f, 0.1f, 0.18f)
    }
}

private const val QUIET = 0
private const val GLANCE = 1
private const val HUM = 2
private const val BOUNCE = 3
private const val STRETCH = 4

/** What she does in each seven-second slot while she waits; the list loops (70 s). */
private val ROUTINE = intArrayOf(GLANCE, HUM, QUIET, BOUNCE, GLANCE, STRETCH, HUM, GLANCE, BOUNCE, QUIET)

/**
 * Waiting for "Dina": breathing, blinking and, every seven seconds or so, one small thing to do
 * from [ROUTINE] at a random moment of its slot: glance aside, hum a tune, bounce, stretch, or
 * nothing at all. Never in her first two seconds.
 */
private fun idle(t: Float, motion: Boolean, out: Pose) {
    out.smile = 0.45f
    if (!motion) return
    val cell = 7f
    val k = floor(t / cell).toInt()
    val x = t - (k * cell + 2.2f + (hash(611, k) * 0.5f + 0.5f) * 2.2f)
    when (ROUTINE[Math.floorMod(k, ROUTINE.size)]) {
        GLANCE -> {
            val side = if (hash(521, k) > 0f) 1f else -1f
            val g = side * envelope(x, 1.9f, 0.3f, 0.4f)
            out.lookX = g * 0.85f
            out.tilt = g * 3f
            out.brow = 0.3f * abs(g)
            out.browAsym = 0.4f * g
        }
        HUM -> {
            // Humming to herself: rocking, happy eyes, a note floating up, Musgo tapping a foot.
            val length = 2.8f
            val e = envelope(x, length, 0.35f, 0.45f)
            val beat = sin(x * TAU / 1.1f)
            out.tilt = 4f * e * beat
            out.eyeHappy = 0.85f * e
            out.smile = 0.45f + 0.3f * e
            out.blush = 0.6f + 0.3f * e
            out.squash += 0.02f * e * abs(beat)
            out.wiggle += 0.45f * e * beat
            out.step = e * beat
            out.note = e
            out.notePhase = (x / length).coerceIn(0f, 1f)
        }
        BOUNCE -> {
            // A little bounce on the spot.
            if (x > 0f && x < 1.3f) jump(x, 0.028f, out)
            out.eyeHappy = 0.6f * envelope(x - 0.3f, 1f, 0.15f, 0.4f)
        }
        STRETCH -> {
            // A big stretch: arms (or leaves) up, eyes shut, a quiet "mmm".
            val e = envelope(x, 1.9f, 0.45f, 0.55f)
            out.cheer = e
            out.squash -= 0.06f * e
            out.eyeOpen = minOf(out.eyeOpen, 1f - 0.9f * e)
            out.mouthOpen = 0.3f * e
            out.mouthRound = 1f
            out.mouthWidth = 0.7f
            out.perk += 0.8f * e
            out.tilt = -2f * e
        }
    }
}

/**
 * A hop with anticipation [x] s after it starts: crouch, stretch on the way up, round at the top,
 * stretch on the way down and squash on landing, wobbling out.
 */
internal fun jump(x: Float, height: Float, out: Pose) {
    if (x < 0f) return
    val crouch = 0.08f
    val air = 0.2f + 6f * height
    val depth = 0.06f + height
    when {
        x < crouch -> out.squash += depth * sin(x / crouch * PI.toFloat() / 2f)
        x < crouch + air -> {
            val u = (x - crouch) / air
            out.hop += height * 4f * u * (1f - u)
            val flying = -0.07f * (2f * u - 1f) * (2f * u - 1f)
            out.squash += depth + (flying - depth) * smoothstep(0f, 0.15f, u)
        }
        else -> {
            val v = x - crouch - air
            out.squash += if (v < 0.04f) -0.07f + (depth + 0.07f) * (v / 0.04f) else depth * exp(-(v - 0.04f) * 7f) * cos((v - 0.04f) * 19f)
        }
    }
}

/**
 * One-shot moves started by a change of state, added after blending so they play at full size,
 * and to the end even if the state changes again: hop when called, jump and confetti when a task
 * is done, a shiver on errors, and a blink on every other change of expression.
 */
internal class Accents {
    private var jumpAt = NEVER
    private var jumpHeight = 0f
    private var shakeAt = NEVER
    private var burstAt = NEVER
    private var blinkAt = NEVER

    fun start(state: VoiceState, now: Float, transition: Boolean) {
        when (state) {
            VoiceState.ACTIVATED -> { jumpAt = now; jumpHeight = 0.045f }
            VoiceState.EXECUTING -> { jumpAt = now; jumpHeight = 0.06f; burstAt = now }
            VoiceState.ERROR -> shakeAt = now
            else -> {}
        }
        // Changing expression mid-blink looks natural; not when surprised (eyes pop open) or at first sight.
        if (transition && state != VoiceState.ACTIVATED && state != VoiceState.EXECUTING) blinkAt = now
    }

    fun apply(now: Float, motion: Boolean, out: Pose) {
        if (!motion) return
        val j = now - jumpAt
        if (j >= 0f && j < 1.2f) jump(j, jumpHeight, out)
        val s = now - shakeAt
        if (s >= 0f && s < 0.8f) out.shiftX += 0.012f * exp(-s * 5f) * sin(s * 52f)
        val b = now - burstAt
        out.burst = if (b >= 0f && b < BURST_S) b / BURST_S else 0f
        out.eyeOpen *= blinkShape(now - blinkAt)
    }

    private companion object {
        const val NEVER = -1000f
        const val BURST_S = 1.5f
    }
}
