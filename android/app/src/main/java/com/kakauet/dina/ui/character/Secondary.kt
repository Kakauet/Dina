package com.kakauet.dina.ui.character

/**
 * Follow-through for what hangs on her body (leaves, sprout, arms): each one is a soft, slightly
 * bouncy spring that chases the body, so it trails behind a jump or a turn and overshoots a little
 * when she stops. Pure math, stepped by the renderer with the real frame time.
 */
internal class Secondary {
    private var hop = 0f
    private var tilt = 0f
    private var shift = 0f
    private var y = 0f
    private var vy = 0f
    private var a = 0f
    private var va = 0f
    private var x = 0f
    private var vx = 0f
    private var started = false

    /** How far the appendages hang below where the body carries them, as a fraction of the canvas (+ = drooping). */
    val flop get() = hop - y

    /** How many degrees they trail behind the body's turns and sideways shakes. */
    val sway get() = (tilt - a) + (shift - x) * SHIFT_DEGREES

    /** Back to rest at the body's current place: no lag. */
    fun reset(hop: Float, tilt: Float, shift: Float) {
        this.hop = hop; this.tilt = tilt; this.shift = shift
        y = hop; a = tilt; x = shift
        vy = 0f; va = 0f; vx = 0f
        started = true
    }

    /** Advances [dt] seconds towards the body's new [hop], [tilt] and [shift], in small stable substeps. */
    fun step(dt: Float, hop: Float, tilt: Float, shift: Float) {
        if (!started) return reset(hop, tilt, shift)
        this.hop = hop; this.tilt = tilt; this.shift = shift
        var left = dt.coerceIn(0f, MAX_STEP)
        while (left > 0f) {
            val h = minOf(left, SUBSTEP)
            vy += (LIFT_K * (hop - y) - LIFT_C * vy) * h
            y += vy * h
            va += (TURN_K * (tilt - a) - TURN_C * va) * h
            a += va * h
            vx += (LIFT_K * (shift - x) - LIFT_C * vx) * h
            x += vx * h
            left -= h
        }
    }

    private companion object {
        // ~2.2 Hz with little damping for the bounce of a jump; ~1.6 Hz for turns.
        const val LIFT_K = 190f
        const val LIFT_C = 7.5f
        const val TURN_K = 100f
        const val TURN_C = 6.5f
        const val SHIFT_DEGREES = 900f
        const val SUBSTEP = 1f / 240f
        const val MAX_STEP = 0.1f
    }
}
