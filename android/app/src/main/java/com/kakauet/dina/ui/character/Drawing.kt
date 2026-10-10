package com.kakauet.dina.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import com.kakauet.dina.ui.theme.CharacterColors
import com.kakauet.dina.ui.theme.CharacterInk
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Drawings that loop for the line boil, and how many of them play per second. */
internal const val BOIL_FRAMES = 3
internal const val BOIL_FPS = 10f

/** Ground line, as a fraction of the canvas. */
internal const val GROUND = 0.885f

private const val TAU = (2 * PI).toFloat()

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
    private val pool = ArrayList<Path>()
    private var next = 0

    /** Sets the size-dependent values; call before building or drawing. */
    fun size(side: Float, density: Float) {
        this.side = side
        this.density = density
        jitter = side * 0.0055f
        ink = maxOf(side * 0.0145f, 1.3f * density)
        detail = side / density >= 80f
    }

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
    val misX get() = side * 0.017f
    val misY get() = side * 0.012f
}

/**
 * Fills [fill] slightly off-register (by [dx], [dy]), with a watercolor edge, pencil hatching on
 * the shadow side and paper grain.
 */
internal fun DrawScope.riso(
    f: Frame,
    fill: Path,
    color: Color,
    shade: Color,
    textured: Boolean = true,
    dx: Float = f.misX,
    dy: Float = f.misY,
) {
    translate(dx, dy) {
        drawPath(fill, color)
        val texture = f.texture
        if (f.detail && textured && texture != null) {
            val bounds = fill.getBounds()
            clipPath(fill) {
                drawPath(fill, shade, alpha = 0.22f, style = Stroke(f.side * 0.05f))
                drawPath(
                    texture.hatching[f.boil],
                    Brush.linearGradient(listOf(Color.Transparent, shade.copy(alpha = 0.5f * shade.alpha)), start = bounds.center, end = bounds.bottomRight),
                )
                drawPath(texture.grain, shade, alpha = 0.14f)
            }
        }
    }
}

internal fun smooth01(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun ellipseHeight(t: Float) = sqrt(1f - (2f * t - 1f) * (2f * t - 1f))

/** Eyes, brows, cheeks and mouth around ([fx], [fy]). [k] is the face scale in px (≈ canvas side). */
internal fun DrawScope.face(f: Frame, pose: Pose, fx: Float, fy: Float, k: Float) {
    val c = f.colors
    val sk = f.sk
    val cx = fx + pose.lookX * 0.035f * k
    val cy = fy + pose.lookY * 0.026f * k
    val ex = 0.094f * k
    val open = pose.eyeOpen.coerceIn(0f, 1f)
    val happy = pose.eyeHappy.coerceIn(0f, 1f)
    val ew = 0.033f * k * pose.eyeScale
    val eh = 0.047f * k * pose.eyeScale

    // Cheeks first, so eyes and mouth sit on top.
    val blush = pose.blush.coerceIn(0f, 1f)
    if (blush > 0.02f) {
        val cheeks = f.path()
        val hatch = f.path()
        for (s in SIDES) {
            val bx = cx + s * (ex + 0.05f * k)
            val by = cy + 0.044f * k
            sk.ellipse(bx, by, 0.037f * k, 0.021f * k, points = 7, phase = s * 0.4f).jitter(f.jitter * 0.5f, f.seed(40 + s)).curve(true, 5)
            sk.fill(cheeks)
            if (f.detail) for (i in 0..2) {
                val hx = bx - 0.018f * k + i * 0.016f * k
                sk.begin().point(hx - 0.006f * k, by + 0.011f * k).point(hx + 0.007f * k, by - 0.011f * k).curve(false, 2)
                sk.brush(hatch, 0.006f * k, f.seed(50 + i + s * 3), variation = 0.3f, taper = 0.8f, weight = 0f)
            }
        }
        drawPath(cheeks, c.cheek, alpha = 0.72f * blush)
        drawPath(hatch, c.tongue, alpha = 0.55f * blush)
    }

    // Eyes: a filled lens whose lower lid can rise into a smiling crescent, inked along the upper lid.
    val fills = f.path()
    val lids = f.path()
    val shine = f.path()
    for (s in SIDES) {
        val ecx = cx + s * ex + pose.lookX * 0.012f * k
        val ecy = cy - 0.012f * k + pose.lookY * 0.016f * k
        val top = eh * open
        val bottom = eh * open * (1f - 1.8f * happy)
        val bow = eh * 0.22f * (1f - open) * (1f - happy)
        val jx = hash(f.seed(60 + s), 0) * f.jitter * 0.3f
        val jy = hash(f.seed(60 + s), 1) * f.jitter * 0.3f
        sk.begin()
        for (i in 0..8) {
            val t = i / 8f
            sk.point(ecx - ew + 2f * ew * t + jx, ecy + (bow - top) * ellipseHeight(t) + jy)
        }
        for (i in 7 downTo 1) {
            val t = i / 8f
            sk.point(ecx - ew + 2f * ew * t + jx, ecy + (bottom + bow) * ellipseHeight(t) + jy)
        }
        sk.polyline(closed = true)
        sk.fill(fills)
        sk.begin()
        for (i in 0..8) {
            val t = i / 8f
            sk.point(ecx - ew * 1.08f + 2.16f * ew * t + jx, ecy + (bow - top) * ellipseHeight(t) + jy)
        }
        sk.polyline(closed = false)
        sk.brush(lids, maxOf(f.ink * 0.8f, k * (0.011f + 0.008f * (1f - open))), f.seed(70 + s), variation = 0.25f, taper = 0.55f, weight = 0f)
        val glint = smooth01(0.45f, 0.85f, open) * (1f - smooth01(0.3f, 0.6f, happy))
        if (glint > 0f) {
            // The glint follows the gaze, which sells where she is looking.
            shine.addOval(Rect(Offset(ecx + ew * (0.3f + 0.25f * pose.lookX), ecy - top * (0.36f - 0.3f * pose.lookY)), ew * 0.36f * glint))
            if (f.detail) shine.addOval(Rect(Offset(ecx - ew * 0.28f, ecy + top * 0.4f), ew * 0.15f * glint))
        }
    }
    drawPath(fills, c.ink)
    drawPath(lids, c.ink)
    drawPath(shine, c.shine)

    // Brows: only when the expression needs them.
    if (pose.brow > 0.02f) {
        val brows = f.path()
        for (s in SIDES) {
            val bx = cx + s * ex
            val by = cy - 0.012f * k - eh * 1.75f - pose.browLift * 0.016f * k
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

    // Mouth: a smile line that opens into a filled shape with a tongue.
    val mouthOpen = pose.mouthOpen.coerceIn(0f, 1f)
    val mx = cx + pose.lookX * 0.012f * k
    val my = cy + 0.07f * k
    val mw = 0.041f * k * pose.mouthWidth * (1f - 0.22f * mouthOpen)
    val curve = pose.smile * 0.026f * k
    val mj = f.jitter * 0.3f
    if (mouthOpen < 0.06f) {
        sk.begin()
        for (i in 0..4) {
            val x = -1f + i * 0.5f
            sk.point(mx + x * mw, my + curve * (1f - x * x) - curve * 0.35f)
        }
        sk.jitter(mj, f.seed(90)).curve(false, 4)
        val line = f.path()
        sk.brush(line, maxOf(f.ink * 0.85f, 0.013f * k), f.seed(91), variation = 0.35f, taper = 0.6f, weight = 0f)
        drawPath(line, c.ink)
    } else {
        val depth = mouthOpen * 0.08f * k
        sk.begin()
        for (i in 0..6) {
            val x = -1f + i / 3f
            sk.point(mx + x * mw, my + curve * 0.45f * (1f - x * x) - curve * 0.25f)
        }
        for (i in 5 downTo 1) {
            val x = -1f + i / 3f
            val belly = 1f - x * x
            sk.point(mx + x * mw * 0.97f, my + curve * 0.45f * belly - curve * 0.25f + depth * sqrt(belly))
        }
        sk.jitter(mj, f.seed(92)).curve(true, 3)
        val shape = f.path()
        sk.fill(shape)
        val outline = f.path()
        sk.brush(outline, maxOf(f.ink * 0.7f, 0.011f * k), f.seed(93), variation = 0.3f, weight = 0f)
        drawPath(shape, c.mouth)
        if (mouthOpen > 0.25f) clipPath(shape) {
            drawOval(c.tongue, Offset(mx - mw * 0.55f, my + depth * 0.55f), Size(mw * 1.1f, depth * 0.9f))
        }
        drawPath(outline, c.ink)
    }
}

/** Thought dots, sparkles, a sweat drop, sleepy z's and sound ripples around the character. */
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
                val a = (-100f + 60f * p / 4f) * PI.toFloat() / 180f
                sk.point(f.u(rig.handX) + cos(a) * r, f.u(rig.handY) + sin(a) * r)
            }
            sk.jitter(f.jitter * 0.3f, f.seed(170 + i)).curve(false, 3)
            sk.brush(path, f.ink * 0.7f, f.seed(175 + i), variation = 0.3f, taper = 0.9f, weight = 0f)
        }
        drawPath(path, c.aura, alpha = smooth01(0.3f, 0.8f, pose.wave) * (0.8f + 0.2f * sin(t * 13f)))
    }
    if (pose.thinking > 0.02f) {
        val fills = f.path()
        val inks = f.path()
        for (i in 0..2) {
            val pulse = 0.75f + 0.25f * sin((t * 2.4f - i * 0.7f) * TAU)
            val r = f.u(THOUGHTS[i * 3 + 2]) * pose.thinking * pulse
            val x = f.u(rig.crownX + THOUGHTS[i * 3])
            val y = f.u(rig.crownY + THOUGHTS[i * 3 + 1]) + f.u(0.006f) * sin(t * 3f + i)
            sk.ellipse(x, y, r, r * 0.92f, points = 6, phase = i.toFloat()).jitter(f.jitter * 0.4f, f.seed(110 + i)).curve(true, 5)
            sk.fill(fills)
            sk.brush(inks, f.ink * 0.75f, f.seed(115 + i), variation = 0.3f)
        }
        drawPath(fills, c.bubble, alpha = pose.thinking)
        drawPath(inks, c.ink, alpha = pose.thinking)
    }
    if (pose.sparkle > 0.02f) {
        val fills = f.path()
        val inks = f.path()
        for (i in 0..1) {
            val twinkle = 0.8f + 0.2f * sin(t * 6f + i * 2f)
            val r = f.u(STARS[i * 3 + 2]) * pose.sparkle * twinkle
            val x = f.u(rig.crownX + STARS[i * 3])
            val y = f.u(rig.crownY + STARS[i * 3 + 1])
            sk.begin()
            for (p in 0 until 8) {
                val a = p * PI.toFloat() / 4f - PI.toFloat() / 2f
                val rr = if (p % 2 == 0) r else r * 0.32f
                sk.point(x + cos(a) * rr, y + sin(a) * rr)
            }
            sk.jitter(f.jitter * 0.3f, f.seed(120 + i)).polyline(true)
            sk.fill(fills)
            sk.brush(inks, f.ink * 0.6f, f.seed(125 + i), variation = 0.2f, weight = 0f)
        }
        val alpha = pose.sparkle.coerceIn(0f, 1f)
        drawPath(fills, c.spark, alpha = alpha)
        drawPath(inks, c.ink, alpha = alpha)
    }
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
        drawPath(ink, c.ink, alpha = alpha)
    }
    if (pose.sleepy > 0.02f) {
        val inks = f.path()
        for (i in 0..1) {
            val phase = (t * 0.35f + i * 0.5f) % 1f
            val s = f.u(0.035f + 0.015f * i) * (0.6f + 0.4f * phase)
            val x = f.u(rig.crownX + 0.1f + 0.06f * phase + i * 0.02f)
            val y = f.u(rig.crownY + 0.02f - 0.16f * phase)
            sk.begin().point(x - s / 2, y - s / 2).point(x + s / 2, y - s / 2).point(x - s / 2, y + s / 2).point(x + s / 2, y + s / 2)
                .jitter(f.jitter * 0.2f, f.seed(140 + i)).polyline(false)
            sk.brush(inks, f.ink * 0.7f, f.seed(145 + i), variation = 0.2f, taper = 0.3f, weight = 0f)
            drawPath(inks, c.aura, alpha = pose.sleepy * (1f - phase) * 0.8f)
            inks.rewind()
        }
    }
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
        val span = (13f + 6f * level) * PI.toFloat() / 180f
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

/** Offsets from the crown and radii of the three thought dots. */
private val THOUGHTS = floatArrayOf(0.05f, 0.01f, 0.021f, 0.115f, -0.055f, 0.03f, 0.2f, -0.135f, 0.044f)

/** Offsets from the crown and radii of the two sparkles. */
private val STARS = floatArrayOf(-0.24f, 0.04f, 0.05f, 0.22f, -0.03f, 0.034f)
