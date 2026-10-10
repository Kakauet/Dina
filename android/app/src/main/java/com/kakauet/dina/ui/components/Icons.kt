package com.kakauet.dina.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kakauet.dina.ui.character.Sketcher
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pen for icons drawn on a 24 × 24 grid with the same brush as the character. */
internal class IconPen(private val sk: Sketcher, private val path: Path, private val unit: Float, private val width: Float) {
    private var seed = 1

    private fun ink(closed: Boolean, smooth: Boolean, xy: FloatArray) {
        sk.begin()
        for (i in xy.indices step 2) sk.point(xy[i] * unit, xy[i + 1] * unit)
        sk.jitter(unit * 0.12f, seed)
        if (smooth) sk.curve(closed, steps = 6) else sk.polyline(closed)
        sk.brush(path, width, seed++, variation = 0.3f, taper = 0.35f, weight = 0f)
    }

    /** Straight segments through the points. */
    fun line(vararg xy: Float) = ink(closed = false, smooth = false, xy)
    /** A smooth open curve through the points. */
    fun curve(vararg xy: Float) = ink(closed = false, smooth = true, xy)
    /** A smooth closed loop through the points. */
    fun loop(vararg xy: Float) = ink(closed = true, smooth = true, xy)
    /** A closed polygon with sharp corners. */
    fun polygon(vararg xy: Float) = ink(closed = true, smooth = false, xy)

    fun arc(cx: Float, cy: Float, r: Float, fromDeg: Float, toDeg: Float, points: Int = 9) {
        val xy = FloatArray(points * 2)
        for (i in 0 until points) {
            val a = (fromDeg + (toDeg - fromDeg) * i / (points - 1)) * PI.toFloat() / 180f
            xy[i * 2] = cx + cos(a) * r
            xy[i * 2 + 1] = cy + sin(a) * r
        }
        ink(closed = false, smooth = true, xy)
    }

    fun circle(cx: Float, cy: Float, r: Float) {
        val xy = FloatArray(16)
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            xy[i * 2] = cx + cos(a) * r
            xy[i * 2 + 1] = cy + sin(a) * r
        }
        ink(closed = true, smooth = true, xy)
    }
}

/** Dina's hand-drawn icon set. */
enum class DinaIcon(internal val draw: IconPen.() -> Unit) {
    MIC({
        loop(12f, 3f, 15f, 5.5f, 15f, 10.5f, 12f, 13.5f, 9f, 10.5f, 9f, 5.5f)
        arc(12f, 10.5f, 6.2f, 0f, 180f)
        line(12f, 16.8f, 12f, 20.5f)
        line(8.5f, 20.5f, 15.5f, 20.5f)
    }),
    CHAT({
        loop(6f, 4.5f, 18f, 4.5f, 20.5f, 7.5f, 20.5f, 13.5f, 18f, 16f, 11.5f, 16f, 7f, 20f, 7.8f, 16f, 6f, 16f, 3.5f, 13.5f, 3.5f, 7.5f)
        line(8f, 9f, 16f, 9f)
        line(8f, 12.3f, 13.5f, 12.3f)
    }),
    GEAR({
        val xy = FloatArray(48)
        for (k in 0 until 8) {
            val c = k * 45f
            val pts = floatArrayOf(c - 22.5f, 7.2f, c - 11f, 9.8f, c + 11f, 9.8f)
            for (p in 0..2) {
                val a = pts[p * 2] * PI.toFloat() / 180f
                xy[(k * 3 + p) * 2] = 12f + cos(a) * pts[p * 2 + 1]
                xy[(k * 3 + p) * 2 + 1] = 12f + sin(a) * pts[p * 2 + 1]
            }
        }
        polygon(*xy)
        circle(12f, 12f, 3.2f)
    }),
    LIST({
        line(3.8f, 6.2f, 5.6f, 8f, 8.6f, 4.6f)
        line(11.5f, 6.5f, 20.5f, 6.5f)
        line(3.8f, 12.2f, 5.6f, 14f, 8.6f, 10.6f)
        line(11.5f, 12.5f, 20.5f, 12.5f)
        circle(6f, 18.5f, 1.6f)
        line(11.5f, 18.5f, 18f, 18.5f)
    }),
    SEND({
        line(12f, 20f, 12f, 5f)
        line(6f, 11f, 12f, 4.8f, 18f, 11f)
    }),
    PLUS({
        line(12f, 5f, 12f, 19f)
        line(5f, 12f, 19f, 12f)
    }),
    CLOSE({
        line(6.5f, 6.5f, 17.5f, 17.5f)
        line(17.5f, 6.5f, 6.5f, 17.5f)
    }),
    CHECK({ line(5f, 12.5f, 10f, 17.2f, 19f, 7f) }),
    TRASH({
        line(4f, 7f, 20f, 7f)
        line(9f, 7f, 10f, 4f, 14f, 4f, 15f, 7f)
        line(6.2f, 7f, 7.6f, 20f, 16.4f, 20f, 17.8f, 7f)
        line(10.2f, 11f, 10.4f, 16.5f)
        line(13.8f, 11f, 13.6f, 16.5f)
    }),
    PLAY({ polygon(8f, 5f, 19f, 12f, 8f, 19f) }),
    PAUSE({
        line(9f, 6f, 9f, 18f)
        line(15f, 6f, 15f, 18f)
    }),
    REFRESH({
        arc(12f, 12f, 7.5f, -40f, 250f, 12)
        line(16.5f, 3.6f, 17.9f, 7.4f, 14f, 8.6f)
    }),
    BELL({
        loop(12f, 4.5f, 17f, 7.5f, 17.5f, 13.5f, 19.5f, 17f, 4.5f, 17f, 6.5f, 13.5f, 7f, 7.5f)
        arc(12f, 18.5f, 2.2f, 10f, 170f, 5)
        line(12f, 2.5f, 12f, 4.4f)
    }),
    HOURGLASS({
        line(6.5f, 3.5f, 17.5f, 3.5f)
        line(6.5f, 20.5f, 17.5f, 20.5f)
        curve(8f, 3.8f, 8.6f, 8.5f, 12f, 12f, 15.4f, 15.5f, 16f, 20.2f)
        curve(16f, 3.8f, 15.4f, 8.5f, 12f, 12f, 8.6f, 15.5f, 8f, 20.2f)
    }),
    STOPWATCH({
        circle(12f, 13.5f, 7.5f)
        line(10f, 3f, 14f, 3f)
        line(12f, 3.2f, 12f, 5.8f)
        line(12f, 13.5f, 14.8f, 10.6f)
    }),
    CART({
        line(2.8f, 5f, 5.8f, 5f, 8f, 15f, 18f, 15f, 20.3f, 8f, 6.8f, 8f)
        circle(9f, 19f, 1.5f)
        circle(17f, 19f, 1.5f)
    }),
    BACK({
        line(19.5f, 12f, 5f, 12f)
        line(11f, 6f, 5f, 12f, 11f, 18f)
    }),
    SNOOZE({
        line(5f, 7f, 11f, 7f, 5f, 13f, 11f, 13f)
        line(13.5f, 12.5f, 18.5f, 12.5f, 13.5f, 17.5f, 18.5f, 17.5f)
    }),
    CLOCK({
        circle(12f, 12f, 8.5f)
        line(12f, 7f, 12f, 12f, 15.5f, 14f)
    }),
    SPEAKER({
        polygon(4f, 9.5f, 8f, 9.5f, 13f, 5f, 13f, 19f, 8f, 14.5f, 4f, 14.5f)
        arc(13f, 12f, 4.5f, -45f, 45f, 5)
        arc(13f, 12f, 8f, -45f, 45f, 6)
    }),
    SPARK({
        polygon(12f, 3f, 14f, 10f, 21f, 12f, 14f, 14f, 12f, 21f, 10f, 14f, 3f, 12f, 10f, 10f)
    }),
}

/** One hand-drawn icon. Decorative unless [contentDescription] is given. */
@Composable
fun SketchIcon(
    icon: DinaIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Spacer(
        modifier.size(size).then(described).drawWithCache {
            val path = Path()
            val unit = this.size.minDimension / 24f
            val width = maxOf(unit * 2f, 1.5f * density)
            IconPen(Sketcher(), path, unit, width).apply(icon.draw)
            onDrawBehind { drawPath(path, tint) }
        },
    )
}
