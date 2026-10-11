package com.kakauet.dina.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    /** Whether she can wave hello; [handX], [handY] is where the waving hand (or leaf) is. */
    open val waves = false
    open val handX = 0f
    open val handY = 0f

    abstract fun prepare(f: Frame)
    abstract fun DrawScope.body(f: Frame, pose: Pose)

    // Color-dependent gradients, rebuilt when the frame's size or colors change.
    private var built = -1

    protected fun update(f: Frame) {
        if (built == f.version) return
        built = f.version
        shade(f)
    }

    protected abstract fun shade(f: Frame)
}

private fun boilPaths() = Array(BOIL_FRAMES) { Path() }

/** Waving swing in degrees, from the overlay clock. */
private fun swing(pose: Pose) = 20f * sin(pose.overlayTime * 13f)

/** Degrees the appendages droop (+) or flick up (-) as they trail behind a jump. */
private fun droop(pose: Pose) = (pose.flop * 650f).coerceIn(-35f, 35f)

/** Degrees they trail behind a turn. */
private fun trail(pose: Pose) = pose.sway.coerceIn(-30f, 30f)

/** Draws [block] in a frame rotated by [degrees] around ([x], [y]). */
private inline fun DrawScope.rotated(x: Float, y: Float, degrees: Float, block: DrawScope.() -> Unit) =
    withTransform({ translate(x, y); rotate(degrees, Offset.Zero) }, block)

/**
 * The light comes from above and a little from the left. In a frame rotated by [degrees], how much
 * of that direction falls across the frame's x axis (on its y): -1..1, for the form offset of leaves and arms.
 */
private fun lightAcross(degrees: Float): Float {
    val a = degrees * DEG
    return 0.45f * sin(a) - 0.9f * cos(a)
}

/** Draws [block] outside [body] when [cut], so what is behind a see-through body stays hidden. */
private inline fun DrawScope.behind(body: Path, cut: Boolean, block: DrawScope.() -> Unit) {
    if (cut) clipPath(body, ClipOp.Difference, block) else block()
}

/** Keeps the off-register fill offset pointing down-right on screen inside a frame rotated by [degrees]. */
private fun misAlong(f: Frame, degrees: Float, scale: Float, x: Boolean): Float {
    val a = degrees * DEG
    return if (x) (f.misX * cos(a) + f.misY * sin(a)) * scale else (-f.misX * sin(a) + f.misY * cos(a)) * scale
}

/**
 * A two-tone stem through the sketcher's current points: an ink stroke with a green core.
 * Small canvases get the ink alone.
 */
private fun DrawScope.stem(f: Frame, width: Float, core: Color, seed: Int) {
    val sk = f.sk
    val ink = f.path()
    sk.brush(ink, width, f.seed(seed), taper = 0.3f, weight = 0f)
    drawPath(ink, f.colors.ink)
    if (f.detail) {
        val green = f.path()
        sk.brush(green, width * 0.42f, f.seed(seed + 1), variation = 0.2f, taper = 0.5f, weight = 0f)
        drawPath(green, core)
    }
}

/**
 * A leaf in its own frame: base at the origin, pointing along +x, [length] and [width] in px.
 * Writes the fill, the ink outline with its midrib, and thin side veins.
 */
private fun leafShape(f: Frame, length: Float, width: Float, seed: Int, fill: Path, ink: Path, veins: Path) {
    val sk = f.sk
    fill.rewind(); ink.rewind(); veins.rewind()
    val l = length
    val w = width
    val j = f.jitter * 0.8f * (l / f.u(0.2f)).coerceAtMost(1f)
    val tx = l + hash(f.seed(seed), 0) * j
    val ty = hash(f.seed(seed), 1) * j
    val ux = 0.42f * l + hash(f.seed(seed), 2) * j
    val uy = -0.85f * w + hash(f.seed(seed), 3) * j
    val dx = 0.55f * l + hash(f.seed(seed), 4) * j
    val dy = 0.75f * w + hash(f.seed(seed), 5) * j
    sk.begin().quad(0f, 0f, ux, uy, tx, ty, 9).quad(tx, ty, dx, dy, 0f, 0f, 9).polyline(true)
    sk.fill(fill)
    sk.begin().quad(0f, 0f, ux, uy, tx, ty, 9).point(tx, ty).polyline(false)
    sk.brush(ink, f.ink * 0.9f * (l / f.u(0.2f)).coerceIn(0.6f, 1f), f.seed(seed + 20), taper = 0.85f)
    sk.begin().quad(tx, ty, dx, dy, 0f, 0f, 9).point(0f, 0f).polyline(false)
    sk.brush(ink, f.ink * 0.9f * (l / f.u(0.2f)).coerceIn(0.6f, 1f), f.seed(seed + 30), taper = 0.85f)
    if (f.detail) {
        sk.begin().point(0.06f * l, 0f).point(0.45f * l, -0.06f * w).point(0.82f * l, -0.02f * w).curve(false, 4)
        sk.brush(ink, f.ink * 0.5f, f.seed(seed + 40), taper = 0.9f, weight = 0f)
        // Side veins from the midrib towards the edges, pointing to the tip.
        for (v in 1..3) {
            val x = (0.14f + 0.19f * v) * l
            val y = -0.05f * w * sin(x / l * PI.toFloat())
            val reach = 0.62f - 0.1f * v
            sk.begin().point(x, y).point(x + 0.09f * l, y - 0.3f * w * reach).point(x + 0.16f * l, y - 0.55f * w * reach).curve(false, 3)
            sk.brush(veins, f.ink * 0.32f, f.seed(seed + 50 + v), variation = 0.2f, taper = 0.9f, weight = 0f)
            sk.begin().point(x, y).point(x + 0.09f * l, y + 0.27f * w * reach).point(x + 0.16f * l, y + 0.48f * w * reach).curve(false, 3)
            sk.brush(veins, f.ink * 0.32f, f.seed(seed + 60 + v), variation = 0.2f, taper = 0.9f, weight = 0f)
        }
    }
}

/** A leaf's lit side: darker at the base, light at the tip. */
private fun leafLight(f: Frame, length: Float) = Brush.horizontalGradient(
    0f to mix(f.colors.leaf, f.colors.leafShade, 0.18f),
    0.45f to f.colors.leaf,
    1f to mix(f.colors.leaf, f.colors.leafLight, 0.75f),
    startX = 0f,
    endX = length,
)

/** Brote: a pea-like seed with a two-leaf sprout. The leaves carry the mood, and one of them waves. */
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
    override val waves = true
    override val handX = 0.68f
    override val handY = 0.2f

    private val beanFill = boilPaths()
    private val beanInk = boilPaths()
    private val beanLight = boilPaths()
    private val socket = boilPaths()
    /** Leaves in their own frame: base at the origin, pointing along +x. */
    private val leafFill = Array(2) { boilPaths() }
    private val leafInk = Array(2) { boilPaths() }
    private val leafVeins = Array(2) { boilPaths() }
    private var beanSpan = emptyArray<HatchSpan>()
    private var leafSpan = emptyArray<Array<HatchSpan>>()

    private var beanCore = Color.Transparent
    private var beanLit: Brush = SolidColor(Color.Transparent)
    private var leafCore = Color.Transparent
    private var stemCore = Color.Transparent
    private val leafLit = arrayOf<Brush>(SolidColor(Color.Transparent), SolidColor(Color.Transparent))

    override fun prepare(f: Frame) {
        val sk = f.sk
        for (b in 0 until BOIL_FRAMES) {
            f.boil = b
            beanFill[b].rewind(); beanInk[b].rewind(); beanLight[b].rewind(); socket[b].rewind()
            bean(f).jitter(f.jitter, f.seed(7)).curve(true, 8)
            sk.fill(beanFill[b])
            bean(f).jitter(f.jitter, f.seed(5)).curve(true, 8)
            sk.brush(beanInk[b], f.ink, f.seed(6))
            sk.ellipse(f.u(0.355f), f.u(0.5f), f.u(0.042f), f.u(0.024f), 6, phase = -0.9f).jitter(f.jitter * 0.4f, f.seed(8)).curve(true, 5)
            sk.fill(beanLight[b])
            sk.ellipse(f.u(0.405f), f.u(0.468f), f.u(0.011f), f.u(0.008f), 5).jitter(f.jitter * 0.2f, f.seed(9)).curve(true, 3)
            sk.fill(beanLight[b])
            // Where the sprout comes out of the seed, a small shaded dip.
            sk.ellipse(f.u(0.502f), f.u(0.402f), f.u(0.038f), f.u(0.013f), 6).jitter(f.jitter * 0.3f, f.seed(10)).curve(true, 4)
            sk.fill(socket[b])
            for (i in 0..1) leafShape(f, f.u(LEAVES[i * 2]), f.u(LEAVES[i * 2 + 1]), 3 + i, leafFill[i][b], leafInk[i][b], leafVeins[i][b])
        }
        beanSpan = Array(BOIL_FRAMES) { HatchSpan(beanFill[it]) }
        leafSpan = Array(2) { i -> Array(BOIL_FRAMES) { HatchSpan(leafFill[i][it]) } }
    }

    override fun shade(f: Frame) {
        val c = f.colors
        beanCore = mix(c.body, c.bodyShade, 0.3f)
        // The lit part is drawn shifted by the form offset, so its gloss center is shifted back.
        beanLit = Brush.radialGradient(
            0f to mix(c.body, c.bodyLight, 0.6f),
            0.45f to mix(c.body, c.bodyLight, 0.2f),
            1f to c.body,
            center = Offset(f.u(0.37f + FORM_X), f.u(0.5f + FORM_Y)),
            radius = f.u(0.26f),
        )
        leafCore = mix(c.leaf, c.leafShade, 0.32f)
        stemCore = mix(c.leaf, c.leafShade, 0.35f)
        for (i in 0..1) leafLit[i] = leafLight(f, f.u(LEAVES[i * 2]))
    }

    private fun bean(f: Frame): Sketcher {
        f.sk.begin()
        for (i in BEAN.indices step 2) f.sk.point(f.u(BEAN[i]), f.u(BEAN[i + 1]))
        return f.sk
    }

    override fun DrawScope.body(f: Frame, pose: Pose) {
        update(f)
        val c = f.colors
        val b = f.boil
        val droop = droop(pose)
        val trail = trail(pose)
        // The stem bends with the leaves and trails behind her turns, so it is drawn fresh each frame.
        val ax = f.u(0.515f + 0.01f * pose.wiggle) - f.u(0.055f) * sin(trail * DEG)
        val ay = f.u(0.29f - 0.012f * pose.perk + 0.25f * pose.flop.coerceIn(-0.04f, 0.04f))
        f.sk.begin().point(f.u(0.5f), f.u(0.41f)).point(f.u(0.503f), f.u(0.35f)).point(ax, ay).jitter(f.jitter * 0.4f, f.seed(1)).curve(false, 5)
        stem(f, f.ink * 1.55f, stemCore, 2)
        for (i in 0..1) {
            // Raised leaves stop short of upright, so the two always open in a V.
            var angle = if (i == 0) {
                (-150f + 30f * pose.perk + 12f * pose.wiggle + 30f * pose.cheer + 9f * pose.beat).coerceAtMost(-120f) - droop
            } else {
                (-30f - 30f * pose.perk - 12f * pose.wiggle - 30f * pose.cheer - 9f * pose.beat).coerceAtLeast(-60f) + droop
            }
            angle -= trail
            if (i == 1) {
                // The small leaf waves hello, or goes up like a raised finger, tapping, while she thinks.
                angle += (-64f + swing(pose) - angle) * pose.wave.coerceIn(0f, 1f)
                angle += (-58f + 8f * sin(pose.overlayTime * 9f) - angle) * pose.scratch.coerceIn(0f, 1f)
            }
            val span = leafSpan[i][b]
            val w = f.u(LEAVES[i * 2 + 1])
            rotated(ax, ay, angle) {
                riso(
                    f, leafFill[i][b], c.leaf, c.leafShade,
                    dx = misAlong(f, angle, 0.6f, x = true), dy = misAlong(f, angle, 0.6f, x = false),
                    light = span.light, dark = span.dark,
                    formX = 0f, formY = w * 0.44f * lightAcross(angle), core = leafCore, lit = leafLit[i],
                )
                if (f.detail) drawPath(leafVeins[i][b], c.leafShade, alpha = 0.75f)
                drawPath(leafInk[i][b], c.ink)
            }
        }
        riso(
            f, beanFill[b], c.body, c.bodyShade, light = beanSpan[b].light, dark = beanSpan[b].dark,
            formX = f.u(FORM_X), formY = f.u(FORM_Y), core = beanCore, lit = beanLit,
        )
        translate(f.misX, f.misY) { drawPath(socket[b], c.bodyShade, alpha = 0.28f) }
        drawPath(beanLight[b], c.bodyLight, alpha = 0.9f)
        drawPath(beanInk[b], c.ink)
    }

    private companion object {
        val BEAN = floatArrayOf(
            0.5f, 0.385f, 0.6f, 0.4f, 0.69f, 0.46f, 0.755f, 0.57f, 0.77f, 0.7f, 0.715f, 0.818f, 0.6f, 0.868f,
            0.5f, 0.876f, 0.4f, 0.868f, 0.285f, 0.818f, 0.23f, 0.7f, 0.245f, 0.57f, 0.31f, 0.46f, 0.4f, 0.4f,
        )
        /** Length and width of the big and the small leaf. */
        val LEAVES = floatArrayOf(0.235f, 0.125f, 0.185f, 0.1f)
        /** Light comes from the top left: the lit part of the seed is its shape moved this way. */
        const val FORM_X = -0.032f
        const val FORM_Y = -0.028f
    }
}

/** Musgo: a round moss spirit with fluffy fur, tiny feet and arms, and a curly sprout with a leaf. */
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
    override val handX = 0.82f
    override val handY = 0.45f

    private val footFill = Array(2) { boilPaths() }
    private val footInk = Array(2) { boilPaths() }
    /** Arms from the shoulder at the origin along +x, so they can rotate and wave. */
    private val armFill = Array(2) { boilPaths() }
    private val armInk = Array(2) { boilPaths() }
    private val belly = boilPaths()
    private val light = boilPaths()
    private val tuftsDark = boilPaths()
    private val tuftsLight = boilPaths()
    private val sproutLeafFill = boilPaths()
    private val sproutLeafInk = boilPaths()
    private val sproutLeafVeins = boilPaths()
    private var armSpan = emptyArray<Array<HatchSpan>>()

    private var bodyCore = Color.Transparent
    private var bodyLit: Brush = SolidColor(Color.Transparent)
    private var limbCore = Color.Transparent
    private var footColor = Color.Transparent
    private var stemCore = Color.Transparent
    private var sproutLit: Brush = SolidColor(Color.Transparent)

    override fun prepare(f: Frame) {
        val sk = f.sk
        for (b in 0 until BOIL_FRAMES) {
            f.boil = b
            for (i in 0..1) {
                val s = SIDES[i]
                footFill[i][b].rewind(); footInk[i][b].rewind(); armFill[i][b].rewind(); armInk[i][b].rewind()
                // Feet around their own center (so they can lift), drawn at (0.5 ± 0.105, 0.855).
                sk.ellipse(0f, 0f, f.u(0.064f), f.u(0.036f), 8, phase = s.toFloat()).jitter(f.jitter * 0.5f, f.seed(10 + s)).curve(true, 5)
                sk.fill(footFill[i][b])
                sk.brush(footInk[i][b], f.ink * 0.85f, f.seed(12 + s))
                arm(f).jitter(f.jitter * 0.4f, f.seed(14 + s)).curve(true, 3)
                sk.fill(armFill[i][b])
                sk.brush(armInk[i][b], f.ink * 0.8f, f.seed(16 + s))
            }
            belly[b].rewind(); light[b].rewind(); tuftsDark[b].rewind(); tuftsLight[b].rewind()
            sk.ellipse(f.u(0.5f), f.u(0.7f), f.u(0.155f), f.u(0.1f), 8).jitter(f.jitter * 0.6f, f.seed(22)).curve(true, 5)
            sk.fill(belly[b])
            sk.ellipse(f.u(0.355f), f.u(0.445f), f.u(0.04f), f.u(0.023f), 6, phase = -0.8f).jitter(f.jitter * 0.4f, f.seed(23)).curve(true, 5)
            sk.fill(light[b])
            sk.ellipse(f.u(0.405f), f.u(0.41f), f.u(0.011f), f.u(0.008f), 5).jitter(f.jitter * 0.2f, f.seed(25)).curve(true, 3)
            sk.fill(light[b])
            // Fur clumps along the edge, clear of the belly: dark ones on the shadow side, light ones where the light falls.
            for (j in 0 until TUFTS) {
                val dark = j < TUFTS - 4
                val a = if (dark) (-62f + j * 92f / (TUFTS - 5) + hash(31, j) * 5f) * DEG else (203f + (j - TUFTS + 4) * 17f) * DEG
                // A small "v" pointing out of the body.
                val r = f.u(0.228f + 0.012f * hash(37, j))
                val reach = f.u(0.026f + 0.006f * hash(41, j))
                sk.begin()
                    .point(f.u(0.5f) + cos(a - 0.07f) * (r - reach * 0.3f), f.u(0.585f) + sin(a - 0.07f) * (r - reach * 0.3f))
                    .point(f.u(0.5f) + cos(a) * (r + reach * 0.5f), f.u(0.585f) + sin(a) * (r + reach * 0.5f))
                    .point(f.u(0.5f) + cos(a + 0.08f) * (r - reach * 0.15f), f.u(0.585f) + sin(a + 0.08f) * (r - reach * 0.15f))
                    .jitter(f.jitter * 0.25f, f.seed(43 + j))
                    .curve(false, 3)
                sk.brush(if (dark) tuftsDark[b] else tuftsLight[b], f.ink * 0.5f, f.seed(47 + j), variation = 0.3f, taper = 0.85f, weight = 0f)
            }
            leafShape(f, f.u(0.085f), f.u(0.05f), 26, sproutLeafFill[b], sproutLeafInk[b], sproutLeafVeins[b])
        }
        armSpan = Array(2) { i -> Array(BOIL_FRAMES) { HatchSpan(armFill[i][it]) } }
    }

    override fun shade(f: Frame) {
        val c = f.colors
        bodyCore = mix(c.leaf, c.leafShade, 0.3f)
        bodyLit = Brush.radialGradient(
            0f to mix(c.leaf, c.bodyLight, 0.5f),
            0.4f to mix(c.leaf, c.bodyLight, 0.16f),
            1f to c.leaf,
            center = Offset(f.u(0.38f + FORM_X), f.u(0.45f + FORM_Y)),
            radius = f.u(0.3f),
        )
        limbCore = mix(c.leaf, c.leafShade, 0.3f)
        footColor = mix(c.leaf, c.leafShade, 0.62f)
        stemCore = mix(c.leaf, c.leafShade, 0.35f)
        sproutLit = leafLight(f, f.u(0.085f))
    }

    /** A little arm: a capsule from the shoulder (origin) along +x, thicker at the hand. */
    private fun arm(f: Frame): Sketcher {
        val sk = f.sk
        val l = f.u(ARM)
        val r0 = f.u(0.026f)
        val r1 = f.u(0.034f)
        sk.begin()
        sk.point(0f, -r0).point(l * 0.5f, -(r0 + r1) / 2f * 1.02f)
        for (p in 0..6) {
            val a = -PI.toFloat() / 2f + p * PI.toFloat() / 6f
            sk.point(l + cos(a) * r1, sin(a) * r1)
        }
        sk.point(l * 0.5f, (r0 + r1) / 2f * 1.02f).point(0f, r0)
        for (p in 1..3) {
            val a = PI.toFloat() / 2f + p * PI.toFloat() / 4f
            sk.point(cos(a) * r0, sin(a) * r0)
        }
        return sk
    }

    override fun DrawScope.body(f: Frame, pose: Pose) {
        update(f)
        val c = f.colors
        val sk = f.sk
        val b = f.boil
        val droop = droop(pose)
        val trail = trail(pose)
        // The fluffy edge bristles with the mood, so it is built per frame (first: limbs hide behind it).
        val amp = 0.034f + 0.022f * pose.perk.coerceAtLeast(0f)
        fluff(f, amp, f.seed(20))
        val fill = f.path()
        sk.fill(fill)
        // With a see-through body (the themed icon's ink-only colors) the limbs are cut where it would cover them.
        behind(fill, c.leaf.alpha < 0.5f) { limbs(f, pose, droop, trail) }
        // Built every frame: its bounds come from the circle it bristles around, not from measuring it.
        val reach = f.u(0.272f) * (1f + amp * 2.9f)
        riso(
            f, fill, c.leaf, c.leafShade,
            light = Offset(f.u(0.5f), f.u(0.585f)), dark = Offset(f.u(0.5f) + reach, f.u(0.585f) + reach),
            formX = f.u(FORM_X), formY = f.u(FORM_Y), core = bodyCore, lit = bodyLit,
        )
        translate(f.misX, f.misY) {
            drawPath(belly[b], c.belly, alpha = 0.92f)
            if (f.detail) {
                drawPath(tuftsDark[b], c.leafShade, alpha = 0.55f)
                drawPath(tuftsLight[b], c.bodyLight, alpha = 0.6f)
            }
        }
        drawPath(light[b], c.bodyLight, alpha = 0.8f)
        fluff(f, amp, f.seed(21))
        val ink = f.path()
        sk.brush(ink, f.ink, f.seed(24), variation = 0.45f)
        drawPath(ink, c.ink)
    }

    /** Feet, arms and the sprout: everything that comes out from behind the body. */
    private fun DrawScope.limbs(f: Frame, pose: Pose, droop: Float, trail: Float) {
        val c = f.colors
        val sk = f.sk
        val b = f.boil
        val t = pose.overlayTime
        // Feet: they lift in turn when she taps along.
        for (i in 0..1) {
            val s = SIDES[i]
            val lift = (pose.step * s).coerceAtLeast(0f)
            withTransform({
                translate(f.u(0.5f + s * 0.105f), f.u(0.855f - 0.026f * lift))
                rotate(-s * 12f * lift, Offset.Zero)
            }) {
                riso(f, footFill[i][b], footColor, c.ink, textured = false)
                drawPath(footInk[i][b], c.ink)
            }
        }
        // Arms come out from behind the body: at rest a little out to the sides, up to wave, cheer or tap her head.
        for (i in 0..1) {
            val s = SIDES[i]
            val rest = if (s > 0) 26f else 154f
            val bob = s * pose.wiggle * 18f * (0.4f + 0.6f * pose.eyeHappy)
            val beat = -s * 26f * pose.beat
            var angle = rest + bob + beat + s * droop * 0.6f - trail * 0.7f
            val raise: Float
            if (s > 0) {
                val wave = pose.wave.coerceIn(0f, 1f)
                val cheer = pose.cheer.coerceIn(0f, 1f)
                raise = maxOf(wave, cheer)
                val target = if (cheer >= wave) -56f + 5f * sin(t * 7f) else -50f + swing(pose)
                angle += (target - angle) * raise
            } else {
                val scratch = pose.scratch.coerceIn(0f, 1f)
                val cheer = pose.cheer.coerceIn(0f, 1f)
                raise = maxOf(scratch, cheer)
                val target = if (cheer >= scratch) 236f - 5f * sin(t * 7f) else 243f + 9f * sin(t * 9f)
                angle += (target - angle) * raise
            }
            val x = f.u(0.5f + s * (0.225f + 0.005f * raise))
            val y = f.u(0.63f - 0.13f * raise - 0.015f * pose.perk.coerceAtLeast(0f))
            val span = armSpan[i][b]
            rotated(x, y, angle) {
                riso(
                    f, armFill[i][b], c.leaf, c.leafShade,
                    dx = misAlong(f, angle, 0.7f, x = true), dy = misAlong(f, angle, 0.7f, x = false),
                    light = span.light, dark = span.dark, formX = 0f, formY = f.u(0.014f) * lightAcross(angle), core = limbCore,
                )
                drawPath(armInk[i][b], c.ink)
            }
        }
        // Curly sprout on top, with a small leaf that bobs behind her moves.
        val bend = 0.03f * pose.wiggle + 0.035f * (pose.perk - 0.5f).coerceAtMost(0f) - 0.02f * sin(trail * DEG) * 2f
        val tipX = f.u(0.515f + bend)
        val tipY = f.u(0.225f + 0.3f * pose.flop.coerceIn(-0.04f, 0.04f))
        sk.begin().point(f.u(0.5f), f.u(0.34f)).point(f.u(0.492f + bend * 0.3f), f.u(0.27f)).point(tipX, tipY)
            .point(f.u(0.55f + bend), f.u(0.232f)).point(f.u(0.548f + bend), f.u(0.262f)).jitter(f.jitter * 0.3f, f.seed(18)).curve(false, 5)
        stem(f, f.ink * 1.35f, stemCore, 19)
        val leafAngle = -128f + 25f * pose.perk.coerceIn(-1f, 1f) + 14f * pose.wiggle - droop - trail
        rotated(tipX - f.u(0.004f), tipY + f.u(0.004f), leafAngle) {
            riso(
                f, sproutLeafFill[b], c.leaf, c.leafShade, textured = false,
                dx = misAlong(f, leafAngle, 0.4f, x = true), dy = misAlong(f, leafAngle, 0.4f, x = false),
                formX = 0f, formY = f.u(0.022f) * lightAcross(leafAngle), core = limbCore, lit = sproutLit,
            )
            if (f.detail) drawPath(sproutLeafVeins[b], c.leafShade, alpha = 0.6f)
            drawPath(sproutLeafInk[b], c.ink)
        }
    }

    /** Round lobes of fur of slightly different sizes around a circle: soft, cloud-like bumps. */
    private fun fluff(f: Frame, amp: Float, seed: Int) {
        val sk = f.sk
        val cx = f.u(0.5f)
        val cy = f.u(0.585f)
        val r = f.u(0.272f)
        val step = 2f * PI.toFloat() / BUMPS
        sk.begin()
        for (j in 0 until BUMPS) {
            val a0 = j * step - PI.toFloat() / 2f + 0.11f
            val size = r * amp * (2.1f + 0.9f * hash(97, j)) + hash(seed + 1, j) * f.jitter * 0.6f
            val base = r * (1f + 0.012f * hash(91, j)) + hash(seed, j) * f.jitter * 0.5f
            val lean = 0.12f * hash(101, j)
            for (q in 0 until LOBE_POINTS) {
                val u = q / LOBE_POINTS.toFloat()
                // A rounded lobe whose top leans a little to one side, like a tuft of moss.
                val bulge = sqrt(sin(u * PI.toFloat())) * (1f + lean * (u - 0.5f))
                val a = a0 + step * u
                val rr = base + size * bulge
                sk.point(cx + cos(a) * rr, cy + sin(a) * rr)
            }
        }
        sk.polyline(true)
    }

    private companion object {
        const val BUMPS = 17
        const val LOBE_POINTS = 6
        const val TUFTS = 12
        const val ARM = 0.088f
        const val FORM_X = -0.036f
        const val FORM_Y = -0.03f
        val SIDES = intArrayOf(-1, 1)
    }
}
