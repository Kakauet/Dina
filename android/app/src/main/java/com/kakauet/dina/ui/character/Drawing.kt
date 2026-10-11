package com.kakauet.dina.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.kakauet.dina.ui.theme.CharacterColors
import com.kakauet.dina.ui.theme.CharacterInk
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Drawings that loop for the line boil, and how many of them play per second. */
internal const val BOIL_FRAMES = 3
internal const val BOIL_FPS = 10f

/** Ground line, as a fraction of the canvas. */
internal const val GROUND = 0.885f

private const val TAU = (2 * PI).toFloat()
private const val DEG = (PI / 180.0).toFloat()

/** Eye half-height and half-width at eyeScale 1, as fractions of the face scale. */
private const val EYE_H = 0.05f
private const val EYE_W = 0.035f

/** Drawing context shared by rigs, the face and overlays. */
internal class Frame(val sk: Sketcher) {
    var side = 0f
    var density = 1f
    var boil = 0
    /** Line boil amplitude in px. */
    var jitter = 0f
    /** Base ink width in px. */
    var ink = 0f
    /** Textures and small overlays; off for avatar sizes. */
    var detail = true
    var texture: Texture? = null
    var colors: CharacterColors = CharacterInk
        set(value) {
            if (value !== field) { field = value; version++ }
        }
    /** Changes whenever the size or the colors do, so cached gradients know when to rebuild. */
    var version = 0
        private set
    /** The watercolor edge inside riso fills; rebuilt only when the size changes. */
    var edge = Stroke(1f)
        private set
    private val pool = ArrayList<Path>()
    private var next = 0

    // Hatching gradients by fill bounds and shade: a few slots, reused every frame.
    private val gradients = arrayOfNulls<Brush>(GRADIENT_SLOTS)
    private val gradientKeys = LongArray(GRADIENT_SLOTS)
    private val gradientShades = LongArray(GRADIENT_SLOTS)
    private var nextGradient = 0

    /** Sets the size-dependent values; call before building or drawing. */
    fun size(side: Float, density: Float) {
        this.side = side
        this.density = density
        jitter = side * 0.0055f
        ink = maxOf(side * 0.0145f, 1.3f * density)
        detail = side / density >= 80f
        edge = Stroke(side * 0.05f)
        gradients.fill(null)
        version++
    }

    /** A gradient from [from] (clear) to [to] (shade); the same bounds within 2 px reuse the last one. */
    fun hatchBrush(from: Offset, to: Offset, shade: Color): Brush {
        fun q(v: Float) = (v / 2f).toInt().toLong() and 0xFFFF
        val key = (q(from.x) shl 48) or (q(from.y) shl 32) or (q(to.x) shl 16) or q(to.y)
        val tone = shade.value.toLong()
        for (i in 0 until GRADIENT_SLOTS) {
            val brush = gradients[i]
            if (brush != null && gradientKeys[i] == key && gradientShades[i] == tone) return brush
        }
        val slot = nextGradient
        nextGradient = (nextGradient + 1) % GRADIENT_SLOTS
        return Brush.linearGradient(listOf(Color.Transparent, shade.copy(alpha = 0.42f * shade.alpha)), start = from, end = to).also {
            gradients[slot] = it
            gradientKeys[slot] = key
            gradientShades[slot] = tone
        }
    }

    /*
     * Gradients drawn around the origin: translate (and scale) there before drawing with them.
     * Built once per size, colors and face scale, so a frame creates no shaders.
     */
    var eyeFill: Brush = SolidColor(Color.Black)
        private set
    var blushFill: Brush = SolidColor(Color.Transparent)
        private set
    var shadowFill: Brush = SolidColor(Color.Transparent)
        private set
    private var builtVersion = -1
    private var builtK = 0f

    fun gradients(k: Float) {
        if (builtVersion == version && builtK == k) return
        builtVersion = version
        builtK = k
        val c = colors
        val eh = EYE_H * k
        eyeFill = Brush.verticalGradient(0f to c.ink, 0.4f to c.ink, 1f to c.iris, startY = -eh, endY = eh)
        blushFill = Brush.radialGradient(
            0f to c.cheek,
            0.55f to c.cheek.copy(alpha = 0.8f * c.cheek.alpha),
            1f to c.cheek.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 1f,
        )
        shadowFill = Brush.radialGradient(
            0f to c.ink.copy(alpha = 0.2f * c.ink.alpha),
            0.55f to c.ink.copy(alpha = 0.12f * c.ink.alpha),
            1f to c.ink.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 1f,
        )
    }

    // Eye glints of the current frame (x, y, r), drawn after the lids.
    private val glints = FloatArray(12)
    private var glintCount = 0

    fun glint(x: Float, y: Float, r: Float) {
        if (glintCount * 3 + 2 >= glints.size) return
        glints[glintCount * 3] = x; glints[glintCount * 3 + 1] = y; glints[glintCount * 3 + 2] = r
        glintCount++
    }

    fun DrawScope.drawGlints(color: Color) {
        for (i in 0 until glintCount) drawCircle(color, glints[i * 3 + 2], Offset(glints[i * 3], glints[i * 3 + 1]))
        glintCount = 0
    }

    private companion object { const val GRADIENT_SLOTS = 12 }

    fun start() { next = 0 }

    /** A cleared path from a per-frame pool (no allocation once warm). */
    fun path(): Path {
        if (next == pool.size) pool.add(Path())
        val path = pool[next++]
        path.rewind()
        path.fillType = PathFillType.NonZero
        return path
    }

    fun u(x: Float) = x * side
    fun seed(part: Int) = part * 7919 + boil * 104_729
    /** Off-register offset of riso fills: enough to feel printed, not enough to look misprinted. */
    val misX get() = side * 0.009f
    val misY get() = side * 0.0065f
}

/** Straight sRGB mix of two colors (no color space conversion, so nothing is allocated). */
internal fun mix(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * Fills [fill] slightly off-register (by [dx], [dy]), with a watercolor edge, pencil hatching on
 * the shadow side and paper grain. The hatching fades in from [light] to [dark] (by default the
 * center and bottom-right corner of the fill); pass them for paths drawn every frame, since
 * measuring the bounds allocates.
 *
 * With a form offset ([formX], [formY]) the shape gets volume: it is filled with [core] and the lit
 * part is the same shape shifted by that offset, leaving a crescent of shadow on the far side.
 * [lit] paints the lit part instead of [color] (a gradient in the fill's own coordinates).
 */
internal fun DrawScope.riso(
    f: Frame,
    fill: Path,
    color: Color,
    shade: Color,
    textured: Boolean = true,
    dx: Float = f.misX,
    dy: Float = f.misY,
    light: Offset = Offset.Unspecified,
    dark: Offset = Offset.Unspecified,
    formX: Float = 0f,
    formY: Float = 0f,
    core: Color = color,
    lit: Brush? = null,
) {
    translate(dx, dy) {
        val form = formX != 0f || formY != 0f
        when {
            form -> drawPath(fill, core)
            lit != null -> drawPath(fill, lit)
            else -> drawPath(fill, color)
        }
        val texture = f.texture
        val detailed = f.detail && textured && texture != null
        if (!form && !detailed) return@translate
        clipPath(fill) {
            if (form) translate(formX, formY) { if (lit != null) drawPath(fill, lit) else drawPath(fill, color) }
            if (detailed && texture != null) {
                var from = light
                var to = dark
                if (!from.isSpecified || !to.isSpecified) {
                    val bounds = fill.getBounds()
                    from = bounds.center
                    to = bounds.bottomRight
                }
                drawPath(fill, shade, alpha = 0.16f, style = f.edge)
                drawPath(texture.hatching[f.boil], f.hatchBrush(from, to, shade))
                drawPath(texture.grain, shade, alpha = 0.08f)
            }
        }
    }
}

/** Center and bottom-right corner of a path, measured once, for [riso]'s hatching. */
internal class HatchSpan(path: Path) {
    private val bounds = path.getBounds()
    val light get() = Offset(bounds.center.x, bounds.center.y)
    val dark get() = Offset(bounds.right, bounds.bottom)
}

internal fun smooth01(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun ellipseHeight(t: Float) = sqrt(1f - (2f * t - 1f) * (2f * t - 1f))

/** Draws [block] scaled by ([sx], [sy]) around ([x], [y]), with that point as the origin: for the unit gradients of [Frame]. */
internal inline fun DrawScope.around(x: Float, y: Float, sx: Float, sy: Float, block: DrawScope.() -> Unit) =
    withTransform({ translate(x, y); scale(sx, sy, Offset.Zero) }, block)

/** A four-pointed star with curved sides around ([x], [y]), turned by [turn] radians. */
internal fun Sketcher.star(x: Float, y: Float, r: Float, turn: Float): Sketcher {
    begin()
    for (p in 0 until 4) {
        val a = turn + p * PI.toFloat() / 2f - PI.toFloat() / 2f
        val b = a + PI.toFloat() / 2f
        val m = a + PI.toFloat() / 4f
        quad(x + cos(a) * r, y + sin(a) * r, x + cos(m) * r * 0.14f, y + sin(m) * r * 0.14f, x + cos(b) * r, y + sin(b) * r, 4)
    }
    return this
}

/** Eyes, brows, cheeks and mouth around ([fx], [fy]). [k] is the face scale in px (≈ canvas side). */
internal fun DrawScope.face(f: Frame, pose: Pose, fx: Float, fy: Float, k: Float) {
    val c = f.colors
    val sk = f.sk
    f.gradients(k)
    val cx = fx + pose.lookX * 0.035f * k
    val cy = fy + pose.lookY * 0.026f * k
    val ex = 0.094f * k
    val open = pose.eyeOpen.coerceIn(0f, 1f)
    val happy = pose.eyeHappy.coerceIn(0f, 1f)
    val wink = pose.wink.coerceIn(0f, 1f)
    val ew = EYE_W * k * pose.eyeScale
    val eh = EYE_H * k * pose.eyeScale

    // Cheeks first, so eyes and mouth sit on top: a soft blush with three pencil strokes.
    val blush = pose.blush.coerceIn(0f, 1f)
    if (blush > 0.02f) {
        val hatch = f.path()
        for (s in SIDES) {
            val bx = cx + s * (ex + 0.052f * k)
            val by = cy + 0.046f * k
            around(bx, by, 0.05f * k, 0.03f * k) { drawCircle(f.blushFill, 1f, Offset.Zero, alpha = 0.35f + 0.65f * blush) }
            if (f.detail) for (i in 0..2) {
                val hx = bx - 0.017f * k + i * 0.015f * k
                sk.begin().point(hx - 0.005f * k, by + 0.01f * k).point(hx + 0.006f * k, by - 0.01f * k).curve(false, 2)
                sk.brush(hatch, 0.0055f * k, f.seed(50 + i + s * 3), variation = 0.3f, taper = 0.8f, weight = 0f)
            }
        }
        drawPath(hatch, c.tongue, alpha = 0.45f * blush)
    }

    // Eyes: a lens that darkens from the lid down to a green iris, whose lower lid can rise into a
    // smiling crescent; inked along the upper lid with two lashes at the outer corner.
    val eyeY = cy - 0.012f * k + pose.lookY * 0.016f * k
    val fills = f.path()
    val lids = f.path()
    val lashes = f.path()
    for (s in SIDES) {
        val ecx = cx + s * ex + pose.lookX * 0.012f * k
        val hap = if (s > 0) maxOf(happy, wink) else happy
        val top = eh * open
        val bottom = eh * open * (1f - 1.8f * hap)
        val bow = eh * 0.22f * (1f - open) * (1f - hap)
        val jx = hash(f.seed(60 + s), 0) * f.jitter * 0.3f
        val jy = hash(f.seed(60 + s), 1) * f.jitter * 0.3f
        // The fill is built around eyeY, where the iris gradient is anchored.
        sk.begin()
        for (i in 0..8) {
            val t = i / 8f
            sk.point(ecx - ew + 2f * ew * t + jx, (bow - top) * ellipseHeight(t) + jy)
        }
        for (i in 7 downTo 1) {
            val t = i / 8f
            sk.point(ecx - ew + 2f * ew * t + jx, (bottom + bow) * ellipseHeight(t) + jy)
        }
        sk.polyline(closed = true)
        sk.fill(fills)
        sk.begin()
        for (i in 0..8) {
            val t = i / 8f
            sk.point(ecx - ew * 1.08f + 2.16f * ew * t + jx, eyeY + (bow - top) * ellipseHeight(t) + jy)
        }
        sk.polyline(closed = false)
        sk.brush(lids, maxOf(f.ink * 0.8f, k * (0.011f + 0.008f * (1f - open))), f.seed(70 + s), variation = 0.25f, taper = 0.55f, weight = 0f)
        if (f.detail) {
            // Lashes flick up on an open eye and follow the lid down when it closes or smiles.
            val up = open * (1f - hap)
            val a1 = (30f - 62f * up) * DEG
            val a2 = a1 - 30f * DEG
            val x1 = ecx + s * ew * 1.0f + jx
            val y1 = eyeY + (bow - top) * 0.2f + jy
            val x2 = ecx + s * ew * 0.7f + jx
            val y2 = eyeY + (bow - top) * 0.71f + jy
            for (lash in 0..1) {
                val x = if (lash == 0) x1 else x2
                val y = if (lash == 0) y1 else y2
                val a = if (lash == 0) a1 else a2
                val l = (if (lash == 0) 0.022f else 0.016f) * k * pose.eyeScale
                sk.begin().point(x, y).point(x + s * cos(a) * l * 0.55f, y + sin(a) * l * 0.55f - 0.002f * k).point(x + s * cos(a) * l, y + sin(a) * l)
                    .curve(false, 3)
                sk.brush(lashes, 0.0095f * k, f.seed(75 + s + lash * 2), variation = 0.2f, taper = 0.85f, weight = 0f)
            }
        }
        val glint = smooth01(0.45f, 0.85f, open) * (1f - smooth01(0.3f, 0.6f, hap))
        if (glint > 0f) {
            // The glint follows the gaze, which sells where she is looking.
            f.glint(ecx + ew * (0.3f + 0.25f * pose.lookX), eyeY - top * (0.38f - 0.3f * pose.lookY), ew * 0.38f * glint)
            if (f.detail) f.glint(ecx - ew * 0.3f, eyeY + top * 0.42f, ew * 0.16f * glint)
        }
    }
    translate(0f, eyeY) {
        drawPath(fills, f.eyeFill)
        // A soft reflection low in the iris, which makes the eye read as wet and round.
        val shine = smooth01(0.45f, 0.85f, open) * (1f - smooth01(0.1f, 0.45f, happy))
        if (f.detail && shine > 0.02f) clipPath(fills) {
            for (s in SIDES) {
                val ecx = cx + s * ex + pose.lookX * 0.012f * k
                drawOval(c.irisLight, Offset(ecx - ew * 0.78f, eh * open * 0.22f), Size(ew * 1.56f, eh * open * 0.95f), alpha = 0.5f * shine)
            }
        }
    }
    drawPath(lids, c.ink)
    drawPath(lashes, c.ink)
    with(f) { drawGlints(c.shine) }

    // Brows: only when the expression needs them.
    if (pose.brow > 0.02f) {
        val brows = f.path()
        for (s in SIDES) {
            val bx = cx + s * ex
            val by = cy - 0.012f * k - eh * 1.7f - (pose.browLift + s * pose.browAsym) * 0.016f * k
            val inner = by - pose.browTilt * 0.014f * k
            val outer = by + pose.browTilt * 0.007f * k
            sk.begin()
                .point(bx + s * 0.03f * k, outer)
                .point(bx, (inner + outer) / 2f - 0.006f * k)
                .point(bx - s * 0.03f * k, inner)
                .jitter(f.jitter * 0.3f, f.seed(80 + s))
                .curve(false, 4)
            sk.brush(brows, maxOf(f.ink * 0.75f, 0.011f * k), f.seed(85 + s), variation = 0.3f, taper = 0.7f, weight = 0f)
        }
        drawPath(brows, c.ink, alpha = pose.brow.coerceIn(0f, 1f))
    }

    // Mouth: a smile line that opens into a filled shape with a tongue, round for "o", slid aside for "hmm".
    val mouthOpen = pose.mouthOpen.coerceIn(0f, 1f)
    val round = pose.mouthRound.coerceIn(0f, 1f) * smooth01(0f, 0.2f, mouthOpen)
    val shift = pose.mouthShift.coerceIn(-1f, 1f)
    val base = 0.041f * k * pose.mouthWidth
    val mx = cx + pose.lookX * 0.012f * k + shift * base * 0.6f
    val my = cy + 0.07f * k
    val mw = base * (1f - 0.22f * mouthOpen) * (1f - 0.42f * round)
    val curve = pose.smile * 0.026f * k
    val skew = shift * 0.012f * k
    val mj = f.jitter * 0.3f
    if (mouthOpen < 0.06f) {
        sk.begin()
        for (i in 0..4) {
            val x = -1f + i * 0.5f
            sk.point(mx + x * mw, my + curve * (1f - x * x) - curve * 0.35f - x * skew)
        }
        sk.jitter(mj, f.seed(90)).curve(false, 4)
        val line = f.path()
        sk.brush(line, maxOf(f.ink * 0.85f, 0.013f * k), f.seed(91), variation = 0.35f, taper = 0.6f, weight = 0f)
        // Little dimples at the corners of a big smile.
        val dimple = smooth01(0.55f, 0.9f, pose.smile)
        if (f.detail && dimple > 0.02f) for (s in SIDES) {
            val ox = mx + s * mw * 1.04f
            val oy = my - curve * 0.35f - s * skew
            sk.begin().point(ox + s * 0.002f * k, oy - 0.009f * k).point(ox + s * 0.0075f * k, oy - 0.001f * k).point(ox + s * 0.004f * k, oy + 0.007f * k)
                .curve(false, 3)
            sk.brush(line, 0.0075f * k * dimple, f.seed(95 + s), variation = 0.2f, taper = 0.9f, weight = 0f)
        }
        drawPath(line, c.ink)
    } else {
        val depth = mouthOpen * 0.08f * k * (1f + 0.25f * round)
        sk.begin()
        for (i in 0..6) {
            val x = -1f + i / 3f
            val belly = 1f - x * x
            val wide = curve * 0.45f * belly - curve * 0.25f
            val oval = -depth * 0.32f * sqrt(belly)
            sk.point(mx + x * mw, my + wide + (oval - wide) * round - x * skew)
        }
        for (i in 5 downTo 1) {
            val x = -1f + i / 3f
            val belly = 1f - x * x
            val wide = curve * 0.45f * belly - curve * 0.25f
            sk.point(mx + x * mw * 0.97f, my + wide * (1f - round) - x * skew + depth * sqrt(belly) * (1f - 0.3f * round))
        }
        sk.jitter(mj, f.seed(92)).curve(true, 3)
        val shape = f.path()
        sk.fill(shape)
        val outline = f.path()
        sk.brush(outline, maxOf(f.ink * 0.7f, 0.011f * k), f.seed(93), variation = 0.3f, weight = 0f)
        drawPath(shape, c.mouth)
        if (mouthOpen > 0.25f) clipPath(shape) {
            drawOval(c.tongue, Offset(mx - mw * 0.55f, my + depth * (0.5f - 0.2f * round)), Size(mw * 1.1f, depth * 0.9f))
        }
        drawPath(outline, c.ink)
    }
}

/** Thought bubbles, sparkles, confetti, a music note, a sweat drop, sleepy z's and sound ripples around the character. */
internal fun DrawScope.overlays(f: Frame, pose: Pose, rig: Rig) {
    if (!f.detail) return
    val c = f.colors
    val sk = f.sk
    val t = pose.overlayTime
    ripples(f, pose, rig, pose.listen, inward = true)
    ripples(f, pose, rig, pose.voice, inward = false)
    if (rig.waves && pose.wave > 0.3f) {
        // Two motion marks next to the waving hand.
        val path = f.path()
        for (i in 0..1) {
            val r = f.u(0.06f + 0.03f * i)
            sk.begin()
            for (p in 0..4) {
                val a = (-100f + 60f * p / 4f) * DEG
                sk.point(f.u(rig.handX) + cos(a) * r, f.u(rig.handY) + sin(a) * r)
            }
            sk.jitter(f.jitter * 0.3f, f.seed(170 + i)).curve(false, 3)
            sk.brush(path, f.ink * 0.7f, f.seed(175 + i), variation = 0.3f, taper = 0.9f, weight = 0f)
        }
        drawPath(path, c.aura, alpha = smooth01(0.3f, 0.8f, pose.wave) * (0.8f + 0.2f * sin(t * 13f)))
    }
    if (pose.thinking > 0.02f) thoughts(f, pose, rig)
    if (pose.sparkle > 0.02f) {
        val fills = f.path()
        val inks = f.path()
        for (i in 0..2) {
            val twinkle = 0.72f + 0.28f * sin(t * 6f + i * 2.1f)
            val r = f.u(STARS[i * 3 + 2]) * pose.sparkle * twinkle
            val x = f.u(rig.crownX + STARS[i * 3])
            val y = f.u(rig.crownY + STARS[i * 3 + 1]) + f.u(0.008f) * sin(t * 2f + i)
            sk.star(x, y, r, 0.18f * sin(t * 1.7f + i)).jitter(f.jitter * 0.2f, f.seed(120 + i)).polyline(true)
            sk.fill(fills)
            sk.brush(inks, f.ink * 0.55f, f.seed(125 + i), variation = 0.2f, weight = 0f)
        }
        val alpha = pose.sparkle.coerceIn(0f, 1f)
        drawPath(fills, c.spark, alpha = alpha)
        drawPath(inks, c.ink, alpha = alpha)
    }
    if (pose.burst > 0f && pose.burst < 1f) confetti(f, pose.burst, rig)
    if (pose.note > 0.02f) note(f, pose, rig)
    if (pose.sweat > 0.02f) {
        val slide = (t % 2.6f) / 2.6f
        val x = f.u(rig.sweatX)
        val y = f.u(rig.sweatY + 0.03f * slide)
        val r = f.u(0.026f)
        sk.begin().point(x, y - r * 2.1f).quad(x, y - r * 2.1f, x + r * 1.1f, y - r * 0.4f, x + r, y + r * 0.2f, 3)
        for (p in 0..6) {
            val a = p * PI.toFloat() / 6f
            sk.point(x + cos(a) * r, y + r * 0.2f + sin(a) * r)
        }
        sk.quad(x - r, y + r * 0.2f, x - r * 1.1f, y - r * 0.4f, x, y - r * 2.1f, 3)
        sk.jitter(f.jitter * 0.2f, f.seed(130)).polyline(true)
        val fill = f.path()
        sk.fill(fill)
        val ink = f.path()
        sk.brush(ink, f.ink * 0.7f, f.seed(131), variation = 0.2f, weight = 0f)
        val alpha = pose.sweat.coerceIn(0f, 1f) * (1f - smooth01(0.8f, 1f, slide))
        drawPath(fill, c.sweat, alpha = alpha)
        drawOval(c.shine, Offset(x - r * 0.55f, y - r * 0.3f), Size(r * 0.45f, r * 0.6f), alpha = 0.8f * alpha)
        drawPath(ink, c.ink, alpha = alpha)
    }
    if (pose.sleepy > 0.02f) {
        val inks = f.path()
        for (i in 0..1) {
            val phase = (t * 0.35f + i * 0.5f) % 1f
            val s = f.u(0.035f + 0.015f * i) * (0.6f + 0.4f * phase)
            val x = f.u(rig.crownX + 0.1f + 0.06f * phase + i * 0.02f) + f.u(0.012f) * sin(phase * TAU)
            val y = f.u(rig.crownY + 0.02f - 0.16f * phase)
            sk.begin().point(x - s / 2, y - s / 2).point(x + s / 2, y - s / 2).point(x - s / 2, y + s / 2).point(x + s / 2, y + s / 2)
                .jitter(f.jitter * 0.2f, f.seed(140 + i)).polyline(false)
            sk.brush(inks, f.ink * 0.7f, f.seed(145 + i), variation = 0.2f, taper = 0.3f, weight = 0f)
            drawPath(inks, c.aura, alpha = pose.sleepy * smooth01(0f, 0.15f, phase) * (1f - phase) * 0.9f)
            inks.rewind()
        }
    }
}

/**
 * Three thought bubbles that pop in one after the other; the big one holds three typing dots
 * (thinking) or a little sound wave (going over what you said).
 */
private fun DrawScope.thoughts(f: Frame, pose: Pose, rig: Rig) {
    val c = f.colors
    val sk = f.sk
    val t = pose.overlayTime
    val fills = f.path()
    val inks = f.path()
    var bigX = 0f
    var bigY = 0f
    var bigGrow = 0f
    for (i in 0..2) {
        val grow = settle((pose.thinking - i * 0.2f) / 0.6f)
        if (grow <= 0.01f) continue
        val big = i == 2
        val pulse = if (big) 1f + 0.025f * sin(t * 3f) else 0.85f + 0.15f * sin((t * 2.4f - i * 0.7f) * TAU)
        val r = f.u(THOUGHTS[i * 3 + 2]) * grow * pulse
        val x = f.u(rig.crownX + THOUGHTS[i * 3])
        val y = f.u(rig.crownY + THOUGHTS[i * 3 + 1]) + f.u(0.006f) * sin(t * 3f + i)
        sk.ellipse(x, y, if (big) r * 1.5f else r, r * 0.92f, points = if (big) 9 else 6, phase = i.toFloat())
            .jitter(f.jitter * 0.4f, f.seed(110 + i)).curve(true, 5)
        sk.fill(fills)
        sk.brush(inks, f.ink * 0.75f, f.seed(115 + i), variation = 0.3f)
        if (big) { bigX = x; bigY = y; bigGrow = grow }
    }
    val alpha = pose.thinking.coerceIn(0f, 1f)
    drawPath(fills, c.bubble, alpha = alpha)
    drawPath(inks, c.ink, alpha = alpha)
    val inside = alpha * smooth01(0.55f, 0.9f, bigGrow)
    if (inside <= 0.02f) return
    if (pose.dots > 0.02f) {
        for (j in 0..2) {
            val phase = ((t * 1.5f - j * 0.17f) % 1f + 1f) % 1f
            val lift = if (phase < 0.4f) sin(phase / 0.4f * PI.toFloat()) else 0f
            drawCircle(c.ink, f.u(0.0078f), Offset(bigX + (j - 1) * f.u(0.025f), bigY - lift * f.u(0.012f)), alpha = inside * pose.dots.coerceIn(0f, 1f))
        }
    }
    if (pose.squiggle > 0.02f) {
        sk.begin()
        for (p in 0..12) {
            val u = p / 12f
            val envelope = sin(u * PI.toFloat())
            sk.point(bigX + f.u(0.048f) * (2f * u - 1f), bigY + f.u(0.016f) * envelope * sin(u * 13f - t * 9f))
        }
        sk.curve(false, 3)
        val wave = f.path()
        sk.brush(wave, f.ink * 0.55f, f.seed(118), variation = 0.2f, taper = 0.8f, weight = 0f)
        drawPath(wave, c.ink, alpha = inside * pose.squiggle.coerceIn(0f, 1f))
    }
}

/** A finished task: stars, dots and petals fly up from her head, turn and fall, then fade. */
private fun DrawScope.confetti(f: Frame, progress: Float, rig: Rig) {
    val c = f.colors
    val sk = f.sk
    val ox = f.u(0.5f)
    val oy = f.u(rig.crownY + 0.12f)
    val out = 1f - (1f - progress) * (1f - progress)
    val fall = f.u(0.2f) * progress * progress
    for (group in 0..3) {
        val path = f.path()
        for (i in group until CONFETTI step 4) {
            val a = (-90f + (i - (CONFETTI - 1) / 2f) * 15f + hash(201, i) * 6f) * DEG
            val reach = f.u(0.3f + 0.12f * (hash(203, i) * 0.5f + 0.5f))
            val x = ox + cos(a) * reach * out
            val y = oy + sin(a) * reach * out + fall
            val r = f.u(0.017f + 0.008f * abs(hash(207, i))) * (1f - 0.3f * progress)
            val spin = progress * (5f + 4f * hash(211, i))
            when (i % 3) {
                0 -> sk.star(x, y, r * 1.3f, spin).polyline(true)
                1 -> sk.ellipse(x, y, r * 0.7f, r * 0.7f, points = 6, phase = spin).curve(true, 3)
                else -> {
                    sk.begin()
                    for (p in 0 until 6) {
                        val q = p * TAU / 6f
                        val lx = cos(q) * r * 1.2f
                        val ly = sin(q) * r * 0.5f
                        sk.point(x + lx * cos(spin) - ly * sin(spin), y + lx * sin(spin) + ly * cos(spin))
                    }
                    sk.curve(true, 3)
                }
            }
            sk.fill(path)
        }
        val color = when (group) { 0 -> c.spark; 1 -> c.cheek; 2 -> c.leaf; else -> c.sweat }
        drawPath(path, color, alpha = 1f - smooth01(0.6f, 1f, progress))
    }
}

/** An eighth note that floats up and sways while she hums. */
private fun DrawScope.note(f: Frame, pose: Pose, rig: Rig) {
    val sk = f.sk
    val p = pose.notePhase
    val x = f.u(rig.crownX + 0.13f) + f.u(0.03f) * sin(p * 6f)
    val y = f.u(rig.crownY + 0.07f - 0.17f * p)
    val r = f.u(0.016f)
    val turn = -0.35f + 0.25f * sin(p * 5f)
    sk.begin()
    for (q in 0 until 7) {
        val a = q * TAU / 7f
        val lx = cos(a) * r * 1.25f
        val ly = sin(a) * r * 0.85f
        sk.point(x + lx * cos(turn) - ly * sin(turn), y + lx * sin(turn) + ly * cos(turn))
    }
    sk.jitter(f.jitter * 0.15f, f.seed(180)).curve(true, 4)
    val head = f.path()
    sk.fill(head)
    val stem = f.path()
    val sx = x + r * 1.05f
    sk.begin().point(sx, y - r * 0.2f).point(sx + r * 0.05f, y - r * 1.9f).point(sx, y - r * 3.6f).curve(false, 3)
    sk.brush(stem, f.ink * 0.6f, f.seed(181), variation = 0.15f, taper = 0.2f, weight = 0f)
    sk.begin().point(sx, y - r * 3.6f).point(sx + r * 1.2f, y - r * 3f).point(sx + r * 1.5f, y - r * 1.9f).curve(false, 3)
    sk.brush(stem, f.ink * 0.55f, f.seed(182), variation = 0.15f, taper = 0.7f, weight = 0f)
    val alpha = pose.note.coerceIn(0f, 1f) * smooth01(0f, 0.12f, p) * (1f - smooth01(0.75f, 1f, p))
    drawPath(head, f.colors.aura, alpha = alpha)
    drawPath(stem, f.colors.aura, alpha = alpha)
}

/**
 * Cartoon sound lines on both sides of the head, as strong as the real audio [level].
 * Incoming lines (her listening) travel towards her; outgoing ones (her voice) travel away.
 */
private fun DrawScope.ripples(f: Frame, pose: Pose, rig: Rig, level: Float, inward: Boolean) {
    if (level < 0.03f) return
    val sk = f.sk
    val cx = f.u(rig.faceX)
    val cy = f.u(rig.faceY + 0.02f)
    for (i in 0..2) {
        val phase = (pose.overlayTime * 0.9f + i / 3f) % 1f
        val travel = if (inward) 1f - phase else phase
        val radius = f.u(rig.auraMin + (rig.auraMax - rig.auraMin) * travel)
        val alpha = (level * 1.6f).coerceAtMost(1f) * sin(phase * PI.toFloat())
        val span = (13f + 6f * level) * DEG
        val path = f.path()
        for (s in SIDES) {
            val base = if (s > 0) 0f else PI.toFloat()
            sk.begin()
            for (p in 0..4) {
                val a = base - span + 2f * span * p / 4f
                sk.point(cx + cos(a) * radius, cy + sin(a) * radius)
            }
            sk.jitter(f.jitter * 0.3f, f.seed(150 + i * 2 + s)).curve(false, 3)
            sk.brush(path, f.ink * (0.55f + 0.35f * level), f.seed(160 + i + s), variation = 0.3f, taper = 0.9f, weight = 0f)
        }
        drawPath(path, f.colors.aura, alpha = alpha)
    }
}

private val SIDES = intArrayOf(-1, 1)

private const val CONFETTI = 11

/** Offsets from the crown and radii of the three thought bubbles. */
private val THOUGHTS = floatArrayOf(0.05f, 0.01f, 0.019f, 0.11f, -0.05f, 0.028f, 0.2f, -0.135f, 0.046f)

/** Offsets from the crown and radii of the three sparkles. */
private val STARS = floatArrayOf(-0.24f, 0.04f, 0.05f, 0.22f, -0.03f, 0.036f, 0.27f, 0.12f, 0.022f)
