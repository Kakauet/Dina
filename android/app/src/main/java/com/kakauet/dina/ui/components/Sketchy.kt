package com.kakauet.dina.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kakauet.dina.ui.character.Line
import com.kakauet.dina.ui.character.Sketcher
import com.kakauet.dina.ui.theme.Dina
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where a speech bubble points. */
enum class Tail { NONE, TOP_START, BOTTOM_START, BOTTOM_END }

/**
 * A hand-drawn rounded rectangle of [w] × [h] px as a dense closed line, optionally with a
 * speech-bubble [tail] that sticks out of the box. The same [seed] always draws the same wobble.
 */
internal fun Sketcher.roundRect(w: Float, h: Float, r: Float, density: Float, tail: Tail = Tail.NONE, seed: Int = 1): Line {
    val rr = r.coerceAtMost(minOf(w, h) / 2f)
    val step = 5f * density
    val t = 12f * density
    val base = 18f * density
    fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
        val n = maxOf(1, (hypot(x1 - x0, y1 - y0) / step).toInt())
        for (i in 0 until n) point(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n)
    }
    fun arc(cx: Float, cy: Float, from: Float) {
        val n = maxOf(3, (rr * PI.toFloat() / 2f / step).toInt())
        for (i in 0 until n) {
            val a = (from + 90f * i / n) * PI.toFloat() / 180f
            point(cx + cos(a) * rr, cy + sin(a) * rr)
        }
    }
    begin()
    arc(rr, rr, 180f)
    if (tail == Tail.TOP_START) {
        val a = rr + 10f * density
        edge(rr, 0f, a, 0f); edge(a, 0f, a - 3f * density, -t); edge(a - 3f * density, -t, a + base, 0f)
        edge(a + base, 0f, w - rr, 0f)
    } else edge(rr, 0f, w - rr, 0f)
    arc(w - rr, rr, 270f)
    edge(w, rr, w, h - rr)
    arc(w - rr, h - rr, 0f)
    when (tail) {
        Tail.BOTTOM_END -> {
            val a = w - rr - 2f * density
            edge(w - rr, h, a, h); edge(a, h, a + 6f * density, h + t); edge(a + 6f * density, h + t, a - base, h)
            edge(a - base, h, rr, h)
        }
        Tail.BOTTOM_START -> {
            val a = rr + 2f * density
            edge(w - rr, h, a + base, h); edge(a + base, h, a - 6f * density, h + t); edge(a - 6f * density, h + t, a, h)
            edge(a, h, rr, h)
        }
        else -> edge(w - rr, h, rr, h)
    }
    arc(rr, h - rr, 90f)
    edge(0f, h - rr, 0f, rr)
    wobble(0.9f * density, seed, wavelength = 14f)
    return polyline(closed = true)
}

/**
 * Hand-drawn background: an off-register [shadow] block, the [fill] and an ink [outline].
 * Paths are cached per size and colors, so recomposing the content does not rebuild them.
 */
@Composable
fun Modifier.sketchBackground(
    fill: Color,
    outline: Color? = null,
    shadow: Color? = null,
    radius: Dp = Dina.shapes.cardRadius,
    tail: Tail = Tail.NONE,
    stroke: Dp = 1.6.dp,
    shadowOffset: Dp = 3.dp,
    seed: Int = 1,
): Modifier = this.then(
    remember(fill, outline, shadow, radius, tail, stroke, shadowOffset, seed) {
        Modifier.drawWithCache {
            val sk = Sketcher()
            val line = sk.roundRect(size.width, size.height, radius.toPx(), density, tail, seed)
            val fillPath = Path().also { sk.fill(it, line) }
            val ink = Path()
            if (outline != null) sk.brush(ink, stroke.toPx(), seed, line, variation = 0.35f, weight = 0.6f)
            val offset = shadowOffset.toPx()
            onDrawBehind {
                if (shadow != null) translate(offset, offset * 1.15f) { drawPath(fillPath, shadow) }
                drawPath(fillPath, fill)
                if (outline != null) drawPath(ink, outline)
            }
        }
    },
)

/** A wobbly circle as a closed line (for halos and rings). */
internal fun Sketcher.circle(cx: Float, cy: Float, r: Float, jitter: Float, seed: Int, points: Int = 12): Line =
    ellipse(cx, cy, r, r, points, phase = seed * 0.37f).jitter(jitter, seed).curve(closed = true, steps = 6)

/** Hand-drawn disk with an off-register shadow, used behind the character. */
@Composable
fun Halo(color: Color, shadow: Color, outline: Color?, modifier: Modifier = Modifier) {
    Spacer(
        modifier.then(
            remember(color, shadow, outline) {
                Modifier.drawWithCache {
                    val sk = Sketcher()
                    val r = size.minDimension / 2f * 0.94f
                    val line = sk.circle(size.width / 2f, size.height / 2f, r, r * 0.012f, 7, points = 14)
                    val disk = Path().also { sk.fill(it, line) }
                    val ink = Path()
                    if (outline != null) sk.brush(ink, 1.6f * density, 8, line, variation = 0.5f)
                    val offset = r * 0.035f
                    onDrawBehind {
                        translate(offset, offset) { drawPath(disk, shadow) }
                        drawPath(disk, color)
                        if (outline != null) drawPath(ink, outline)
                    }
                }
            },
        ),
    )
}

/**
 * Progress ring drawn with a brush: a soft track and an inked arc from 12 o'clock, clockwise.
 * [progress] is read in the draw phase.
 */
@Composable
fun SketchRing(
    progress: () -> Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = 9.dp,
) {
    Spacer(
        modifier.drawWithCache {
            val sk = Sketcher()
            val width = thickness.toPx()
            val r = size.minDimension / 2f - width
            val cx = size.width / 2f
            val cy = size.height / 2f
            val trackPath = Path()
            sk.circle(cx, cy, r, width * 0.06f, 3, points = 16)
            sk.brush(trackPath, width, 4, variation = 0.25f, weight = 0f)
            val arc = Path()
            var drawn = -1f
            onDrawBehind {
                drawPath(trackPath, track)
                val p = progress().coerceIn(0f, 1f)
                if (p != drawn) {
                    drawn = p
                    arc.rewind()
                    if (p > 0.004f) {
                        val points = (4 + 44 * p).toInt()
                        sk.begin()
                        for (i in 0..points) {
                            val a = -PI.toFloat() / 2f + 2f * PI.toFloat() * p * i / points
                            sk.point(cx + cos(a) * r, cy + sin(a) * r)
                        }
                        sk.polyline(closed = false)
                        sk.brush(arc, width * 1.05f, 5, variation = 0.3f, taper = 0.15f, weight = 0f)
                    }
                }
                drawPath(arc, color)
                if (p > 0.004f) {
                    val a = -PI.toFloat() / 2f + 2f * PI.toFloat() * p
                    drawCircle(color, width * 0.85f, Offset(cx + cos(a) * r, cy + sin(a) * r))
                }
            }
        },
    )
}

/** Hand-drawn check box: wobbly rounded square, filled and ticked with a stroke that draws itself in. */
@Composable
fun SketchCheck(checked: Boolean, color: Color, outline: Color, mark: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(Dina.motion.ms(Dina.motion.standard)), label = "check")
    Spacer(
        modifier.size(size).drawWithCache {
            val sk = Sketcher()
            val s = this.size.minDimension
            val line = sk.roundRect(s, s, s * 0.3f, density, seed = 11)
            val box = Path().also { sk.fill(it, line) }
            val ink = Path().also { sk.brush(it, 1.8f * density, 11, line, variation = 0.3f, weight = 0.4f) }
            val tick = Path()
            onDrawBehind {
                drawPath(box, color, alpha = progress)
                drawPath(ink, if (progress > 0.5f) color else outline)
                if (progress > 0.01f) {
                    tick.rewind()
                    // Two strokes of the tick, revealed in order.
                    val xs = floatArrayOf(0.24f, 0.43f, 0.78f)
                    val ys = floatArrayOf(0.52f, 0.71f, 0.3f)
                    val reach = progress * 2f
                    sk.begin().point(xs[0] * s, ys[0] * s)
                    val first = reach.coerceAtMost(1f)
                    sk.point((xs[0] + (xs[1] - xs[0]) * first) * s, (ys[0] + (ys[1] - ys[0]) * first) * s)
                    if (reach > 1f) {
                        val second = (reach - 1f).coerceAtMost(1f)
                        sk.point((xs[1] + (xs[2] - xs[1]) * second) * s, (ys[1] + (ys[2] - ys[1]) * second) * s)
                    }
                    sk.polyline(closed = false)
                    sk.brush(tick, 2.4f * density, 12, variation = 0.2f, taper = 0.2f, weight = 0f)
                    drawPath(tick, mark)
                }
            }
        },
    )
}
