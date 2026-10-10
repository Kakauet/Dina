package com.kakauet.dina.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DEG = (PI / 180.0).toFloat()

/**
 * One character design. Coordinates are fractions of the square canvas (ground at [GROUND]).
 * [prepare] builds the paths that never change for a canvas size, one set per boil drawing;
 * [body] draws everything behind the face for the current pose.
 */
internal abstract class Rig {
    abstract val faceX: Float
    abstract val faceY: Float
    open val faceScale = 1f
    /** Where thought dots, sparkles and z's grow from. */
    abstract val crownX: Float
    abstract val crownY: Float
    abstract val sweatX: Float
    abstract val sweatY: Float
    /** Sound ripples travel between these distances from the face. */
    open val auraMin = 0.31f
    open val auraMax = 0.45f
    /** Whether she can wave hello (only with arms); [handX], [handY] is the waving hand. */
    open val waves = false
    open val handX = 0f
    open val handY = 0f

    abstract fun prepare(f: Frame)
    abstract fun DrawScope.body(f: Frame, pose: Pose)
}

private fun boilPaths() = Array(BOIL_FRAMES) { Path() }

/** Waving swing in degrees, from the overlay clock. */
private fun swing(pose: Pose) = 20f * sin(pose.overlayTime * 13f)

/** Draws [block] in a frame rotated by [degrees] around ([x], [y]). */
private inline fun DrawScope.rotated(x: Float, y: Float, degrees: Float, block: DrawScope.() -> Unit) =
    withTransform({ translate(x, y); rotate(degrees, Offset.Zero) }, block)

/** Brote: a pea-like seed with a two-leaf sprout. The leaves carry the mood. */
internal class BroteRig : Rig() {
    override val faceX = 0.5f
    override val faceY = 0.655f
    override val faceScale = 0.95f
    override val crownX = 0.6f
    override val crownY = 0.3f
    override val sweatX = 0.75f
    override val sweatY = 0.5f
    override val auraMin = 0.33f
    override val auraMax = 0.46f

    private val beanFill = boilPaths()
    private val beanInk = boilPaths()
    private val beanLight = boilPaths()
    /** Leaves in their own frame: base at the origin, pointing along +x. */
    private val leafFill = Array(2) { boilPaths() }
    private val leafInk = Array(2) { boilPaths() }

    override fun prepare(f: Frame) {
        val sk = f.sk
        for (b in 0 until BOIL_FRAMES) {
            f.boil = b
            beanFill[b].rewind(); beanInk[b].rewind(); beanLight[b].rewind()
            bean(f).jitter(f.jitter, f.seed(7)).curve(true, 8)
            sk.fill(beanFill[b])
            bean(f).jitter(f.jitter, f.seed(5)).curve(true, 8)
            sk.brush(beanInk[b], f.ink, f.seed(6))
            sk.ellipse(f.u(0.36f), f.u(0.5f), f.u(0.048f), f.u(0.028f), 6, phase = -0.9f).jitter(f.jitter * 0.5f, f.seed(8)).curve(true, 5)
            sk.fill(beanLight[b])
            for (i in 0..1) leaf(f, LEAVES[i * 2], LEAVES[i * 2 + 1], 3 + i, leafFill[i][b], leafInk[i][b])
        }
    }

    private fun bean(f: Frame): Sketcher {
        f.sk.begin()
        for (i in BEAN.indices step 2) f.sk.point(f.u(BEAN[i]), f.u(BEAN[i + 1]))
        return f.sk
    }

    private fun leaf(f: Frame, length: Float, width: Float, seed: Int, fill: Path, ink: Path) {
        val sk = f.sk
        fill.rewind(); ink.rewind()
        val l = f.u(length)
        val w = f.u(width)
        val j = f.jitter * 0.8f
        val tx = l + hash(f.seed(seed), 0) * j
        val ty = hash(f.seed(seed), 1) * j
        val ux = 0.42f * l + hash(f.seed(seed), 2) * j
        val uy = -0.85f * w + hash(f.seed(seed), 3) * j
        val dx = 0.55f * l + hash(f.seed(seed), 4) * j
        val dy = 0.75f * w + hash(f.seed(seed), 5) * j
        sk.begin().quad(0f, 0f, ux, uy, tx, ty, 9).quad(tx, ty, dx, dy, 0f, 0f, 9).polyline(true)
        sk.fill(fill)
        sk.begin().quad(0f, 0f, ux, uy, tx, ty, 9).point(tx, ty).polyline(false)
        sk.brush(ink, f.ink * 0.9f, f.seed(seed + 20), taper = 0.85f)
        sk.begin().quad(tx, ty, dx, dy, 0f, 0f, 9).point(0f, 0f).polyline(false)
        sk.brush(ink, f.ink * 0.9f, f.seed(seed + 30), taper = 0.85f)
        if (f.detail) {
            sk.begin().point(0.08f * l, 0f).point(0.45f * l, -0.06f * w).point(0.78f * l, -0.02f * w).curve(false, 4)
            sk.brush(ink, f.ink * 0.5f, f.seed(seed + 40), taper = 0.9f, weight = 0f)
        }
    }

    override fun DrawScope.body(f: Frame, pose: Pose) {
        val c = f.colors
        val b = f.boil
        // The stem bends with the leaves, so it is the one part drawn fresh each frame.
        val ax = f.u(0.515f + 0.01f * pose.wiggle)
        val ay = f.u(0.29f - 0.012f * pose.perk)
        f.sk.begin().point(f.u(0.5f), f.u(0.4f)).point(f.u(0.503f), f.u(0.345f)).point(ax, ay).jitter(f.jitter * 0.4f, f.seed(1)).curve(false, 5)
        val stem = f.path()
        f.sk.brush(stem, f.ink * 1.05f, f.seed(2), taper = 0.25f, weight = 0f)
        drawPath(stem, c.ink)
        val angles = floatArrayOf(-150f + 30f * pose.perk + 12f * pose.wiggle, -30f - 30f * pose.perk - 12f * pose.wiggle)
        for (i in 0..1) {
            val a = angles[i] * DEG
            // Keep the off-register fill offset pointing down-right on screen.
            val mx = f.misX * 0.6f * cos(a) + f.misY * 0.6f * sin(a)
            val my = -f.misX * 0.6f * sin(a) + f.misY * 0.6f * cos(a)
            rotated(ax, ay, angles[i]) {
                riso(f, leafFill[i][b], c.leaf, c.leafShade, dx = mx, dy = my)
                drawPath(leafInk[i][b], c.ink)
            }
        }
        riso(f, beanFill[b], c.body, c.bodyShade)
        drawPath(beanLight[b], c.bodyLight, alpha = 0.85f)
        drawPath(beanInk[b], c.ink)
    }

    private companion object {
        val BEAN = floatArrayOf(
            0.5f, 0.385f, 0.6f, 0.4f, 0.69f, 0.46f, 0.755f, 0.57f, 0.77f, 0.7f, 0.715f, 0.818f, 0.6f, 0.868f,
            0.5f, 0.876f, 0.4f, 0.868f, 0.285f, 0.818f, 0.23f, 0.7f, 0.245f, 0.57f, 0.31f, 0.46f, 0.4f, 0.4f,
        )
        /** Length and width of the big and the small leaf. */
        val LEAVES = floatArrayOf(0.235f, 0.125f, 0.185f, 0.1f)
    }
}

/** Musgo: a round moss spirit with fluffy edges, tiny feet and arms, and a curly sprout. */
internal class MusgoRig : Rig() {
    override val faceX = 0.5f
    override val faceY = 0.555f
    override val crownX = 0.58f
    override val crownY = 0.26f
    override val sweatX = 0.76f
    override val sweatY = 0.43f
    override val auraMin = 0.34f
    override val auraMax = 0.47f
    override val waves = true
    override val handX = 0.8f
    override val handY = 0.45f

    private val footFill = Array(2) { boilPaths() }
    private val footInk = Array(2) { boilPaths() }
    /** Arms around their own center, so they can wave. */
    private val armFill = Array(2) { boilPaths() }
    private val armInk = Array(2) { boilPaths() }
    private val belly = boilPaths()
    private val light = boilPaths()

    override fun prepare(f: Frame) {
        val sk = f.sk
        for (b in 0 until BOIL_FRAMES) {
            f.boil = b
            for ((i, s) in SIDES.withIndex()) {
                footFill[i][b].rewind(); footInk[i][b].rewind(); armFill[i][b].rewind(); armInk[i][b].rewind()
                sk.ellipse(f.u(0.5f + s * 0.105f), f.u(0.855f), f.u(0.062f), f.u(0.034f), 7, phase = s.toFloat()).jitter(f.jitter * 0.5f, f.seed(10 + s)).curve(true, 5)
                sk.fill(footFill[i][b])
                sk.brush(footInk[i][b], f.ink * 0.85f, f.seed(12 + s))
                sk.ellipse(0f, 0f, f.u(0.048f), f.u(0.032f), 7, phase = s * 0.5f).jitter(f.jitter * 0.5f, f.seed(14 + s)).curve(true, 5)
                sk.fill(armFill[i][b])
                sk.brush(armInk[i][b], f.ink * 0.8f, f.seed(16 + s))
            }
            belly[b].rewind(); light[b].rewind()
            sk.ellipse(f.u(0.5f), f.u(0.7f), f.u(0.155f), f.u(0.1f), 8).jitter(f.jitter * 0.6f, f.seed(22)).curve(true, 5)
            sk.fill(belly[b])
            sk.ellipse(f.u(0.36f), f.u(0.44f), f.u(0.045f), f.u(0.026f), 6, phase = -0.8f).jitter(f.jitter * 0.5f, f.seed(23)).curve(true, 5)
            sk.fill(light[b])
        }
    }

    override fun DrawScope.body(f: Frame, pose: Pose) {
        val c = f.colors
        val sk = f.sk
        val b = f.boil
        for ((i, s) in SIDES.withIndex()) {
            riso(f, footFill[i][b], c.leafShade.copy(alpha = 0.9f * c.leafShade.alpha), c.ink, textured = false)
            drawPath(footInk[i][b], c.ink)
            // The right arm bobs when she is happy and rises to wave hello.
            val bob = if (s > 0) pose.wiggle * 0.03f * (0.4f + 0.6f * pose.eyeHappy) else 0f
            // Waving hello: the same short arm goes up beside her head and swings.
            val raise = if (s > 0) pose.wave.coerceIn(0f, 1f) else 0f
            val x = f.u(0.5f + s * (0.285f - 0.01f * raise))
            val y = f.u(0.63f - bob - 0.02f * pose.perk.coerceAtLeast(0f) - 0.15f * raise)
            rotated(x, y, s * 20f * bob / 0.03f + (-50f + swing(pose)) * raise) {
                riso(f, armFill[i][b], c.leaf, c.leafShade, textured = false)
                drawPath(armInk[i][b], c.ink)
            }
        }
        // Curly sprout on top.
        val bend = 0.03f * pose.wiggle + 0.035f * (pose.perk - 0.5f).coerceAtMost(0f)
        val sprout = f.path()
        sk.begin().point(f.u(0.5f), f.u(0.33f)).point(f.u(0.492f + bend * 0.3f), f.u(0.27f)).point(f.u(0.515f + bend), f.u(0.225f))
            .point(f.u(0.55f + bend), f.u(0.232f)).point(f.u(0.548f + bend), f.u(0.262f)).jitter(f.jitter * 0.3f, f.seed(18)).curve(false, 5)
        sk.brush(sprout, f.ink * 0.95f, f.seed(19), taper = 0.6f)
        drawPath(sprout, c.ink)

        // The fluffy edge bristles with the mood (and with your voice while listening), so it is built per frame.
        val amp = 0.03f + 0.025f * pose.perk.coerceAtLeast(0f)
        fluff(f, amp, f.seed(20))
        val fill = f.path()
        sk.fill(fill)
        riso(f, fill, c.leaf, c.leafShade)
        drawPath(belly[b], c.belly, alpha = 0.9f)
        drawPath(light[b], c.bodyLight, alpha = 0.7f)
        fluff(f, amp, f.seed(21))
        val ink = f.path()
        sk.brush(ink, f.ink, f.seed(24), variation = 0.5f)
        drawPath(ink, c.ink)
    }

    /** Round bumps between points on a circle. */
    private fun fluff(f: Frame, amp: Float, seed: Int) {
        val sk = f.sk
        val cx = f.u(0.5f)
        val cy = f.u(0.585f)
        val r = f.u(0.272f)
        val step = 2f * PI.toFloat() / BUMPS
        sk.begin()
        for (j in 0 until BUMPS) {
            val a0 = j * step - PI.toFloat() / 2f
            val a1 = a0 + step
            val am = a0 + step / 2f
            val r0 = r * (1f + 0.018f * hash(91, j)) + hash(seed, j) * f.jitter
            val r1 = r * (1f + 0.018f * hash(91, (j + 1) % BUMPS)) + hash(seed, (j + 1) % BUMPS) * f.jitter
            val rm = r * (1f + amp * (2.2f + 0.5f * hash(97, j))) + hash(seed + 1, j) * f.jitter
            sk.quad(cx + cos(a0) * r0, cy + sin(a0) * r0, cx + cos(am) * rm, cy + sin(am) * rm, cx + cos(a1) * r1, cy + sin(a1) * r1, 5)
        }
        sk.polyline(true)
    }

    private companion object {
        const val BUMPS = 15
        val SIDES = intArrayOf(-1, 1)
    }
}
