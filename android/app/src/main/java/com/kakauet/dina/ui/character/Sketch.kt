package com.kakauet.dina.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Hand-drawn primitives. Shapes are control points that go through a Catmull-Rom spline,
 * get a per-drawing jitter ("line boil") and are inked with a variable-width brush ribbon.
 * Everything writes into reusable buffers: drawing a frame allocates nothing.
 */

/** Deterministic hash noise in [-1, 1). */
internal fun hash(seed: Int, i: Int): Float {
    var h = seed * -0x61c88647 + i * 0x27d4eb2d
    h = h xor (h ushr 15)
    h *= 0x2c1b3c6d
    h = h xor (h ushr 12)
    h *= 0x297a2d39
    h = h xor (h ushr 15)
    return (h ushr 8) / 8_388_608f - 1f
}

/** Smooth value noise in [-1, 1], continuous in [x]. */
internal fun smoothNoise(seed: Int, x: Float): Float {
    val i = floor(x).toInt()
    val f = x - i
    val u = f * f * (3f - 2f * f)
    return hash(seed, i) * (1f - u) + hash(seed, i + 1) * u
}

/** A sampled curve in pixels. */
internal class Line(capacity: Int = 256) {
    var xs = FloatArray(capacity); private set
    var ys = FloatArray(capacity); private set
    var size = 0; private set
    var closed = false; private set

    fun reset(closed: Boolean) { size = 0; this.closed = closed }

    fun add(x: Float, y: Float) {
        if (size == xs.size) { xs = xs.copyOf(size * 2); ys = ys.copyOf(size * 2) }
        xs[size] = x; ys[size] = y; size++
    }
}

/**
 * Builds sketchy paths. One instance per character: its buffers are reused every frame.
 * Typical use: `begin(); point(..)...; jitter(..); curve(closed); brush(path, ..)`.
 */
internal class Sketcher {
    private var px = FloatArray(64)
    private var py = FloatArray(64)
    private var n = 0
    val line = Line()
    private var lx = FloatArray(256)
    private var ly = FloatArray(256)
    private var rx = FloatArray(256)
    private var ry = FloatArray(256)

    fun begin(): Sketcher { n = 0; return this }

    fun point(x: Float, y: Float): Sketcher {
        if (n == px.size) { px = px.copyOf(n * 2); py = py.copyOf(n * 2) }
        px[n] = x; py[n] = y; n++
        return this
    }

    /** Moves every control point by up to [amount] px; the same [seed] gives the same drawing. */
    fun jitter(amount: Float, seed: Int): Sketcher {
        if (amount == 0f) return this
        for (i in 0 until n) {
            px[i] += hash(seed, i * 2) * amount
            py[i] += hash(seed, i * 2 + 1) * amount
        }
        return this
    }

    /**
     * Pushes every point of a closed outline along its normal by smooth noise of up to [amount] px,
     * one bump every [wavelength] points: a gentle hand wobble without kinks.
     */
    fun wobble(amount: Float, seed: Int, wavelength: Float): Sketcher {
        if (n < 3 || amount == 0f) return this
        val ox = px.copyOf(n)
        val oy = py.copyOf(n)
        for (i in 0 until n) {
            val a = if (i == 0) n - 1 else i - 1
            val b = if (i == n - 1) 0 else i + 1
            val tx = ox[b] - ox[a]
            val ty = oy[b] - oy[a]
            val length = sqrt(tx * tx + ty * ty).coerceAtLeast(0.0001f)
            val offset = smoothNoise(seed, i / wavelength) * amount
            px[i] = ox[i] - ty / length * offset
            py[i] = oy[i] + tx / length * offset
        }
        return this
    }

    /** Catmull-Rom spline through the control points into [line]. */
    fun curve(closed: Boolean, steps: Int = 8): Line {
        line.reset(closed)
        if (n < 2) return line
        val segments = if (closed) n else n - 1
        for (s in 0 until segments) {
            val i0 = index(s - 1, closed); val i1 = index(s, closed)
            val i2 = index(s + 1, closed); val i3 = index(s + 2, closed)
            for (k in 0 until steps) {
                val t = k / steps.toFloat()
                line.add(catmull(px[i0], px[i1], px[i2], px[i3], t), catmull(py[i0], py[i1], py[i2], py[i3], t))
            }
        }
        if (!closed) line.add(px[n - 1], py[n - 1])
        return line
    }

    /** Straight polyline through the control points (for shapes with sharp corners). */
    fun polyline(closed: Boolean): Line {
        line.reset(closed)
        for (i in 0 until n) line.add(px[i], py[i])
        return line
    }

    private fun index(i: Int, closed: Boolean) = if (closed) ((i % n) + n) % n else i.coerceIn(0, n - 1)

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        return 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (3f * p1 - p0 - 3f * p2 + p3) * t2 * t)
    }

    /** Appends [line] as a filled polygon. */
    fun fill(path: Path, line: Line = this.line, dx: Float = 0f, dy: Float = 0f) {
        if (line.size < 2) return
        path.moveTo(line.xs[0] + dx, line.ys[0] + dy)
        for (i in 1 until line.size) path.lineTo(line.xs[i] + dx, line.ys[i] + dy)
        path.close()
    }

    /**
     * Appends an ink stroke along [line] as a filled ribbon whose width wanders with noise,
     * thickens on the shadow side (down-right, like a pressed brush) and tapers at open ends.
     */
    fun brush(
        path: Path,
        width: Float,
        seed: Int,
        line: Line = this.line,
        variation: Float = 0.45f,
        taper: Float = 0.75f,
        weight: Float = 0.45f,
    ) {
        val count = line.size
        if (count < 2) return
        ensureRibbon(count)
        val xs = line.xs; val ys = line.ys
        val closed = line.closed
        for (i in 0 until count) {
            val a = if (i > 0) i - 1 else if (closed) count - 1 else 0
            val b = if (i < count - 1) i + 1 else if (closed) 0 else count - 1
            var tx = xs[b] - xs[a]; var ty = ys[b] - ys[a]
            val length = sqrt(tx * tx + ty * ty).coerceAtLeast(0.0001f)
            tx /= length; ty /= length
            val nx = -ty; val ny = tx
            val s = i / (count - 1f)
            var w = width * (1f + variation * smoothNoise(seed, s * 5.5f))
            // Shadow side: normals pointing down-right get a heavier line.
            w *= 1f + weight * ((nx * 0.45f + ny * 0.9f).coerceAtLeast(0f))
            if (!closed) {
                val edge = (minOf(s, 1f - s) * 2f).coerceIn(0f, 1f)
                w *= 1f - taper + taper * sqrt(edge)
            }
            val h = w / 2f
            lx[i] = xs[i] + nx * h; ly[i] = ys[i] + ny * h
            rx[i] = xs[i] - nx * h; ry[i] = ys[i] - ny * h
        }
        if (closed) {
            // Outer loop forward, inner loop backwards: the hole has winding 0 with the default
            // non-zero rule, so several strokes can share one path.
            path.moveTo(lx[0], ly[0])
            for (i in 1 until count) path.lineTo(lx[i], ly[i])
            path.close()
            path.moveTo(rx[count - 1], ry[count - 1])
            for (i in count - 2 downTo 0) path.lineTo(rx[i], ry[i])
            path.close()
        } else {
            path.moveTo(lx[0], ly[0])
            for (i in 1 until count) path.lineTo(lx[i], ly[i])
            cap(path, count - 1, xs[count - 1] - xs[count - 2], ys[count - 1] - ys[count - 2], rx[count - 1], ry[count - 1])
            for (i in count - 2 downTo 0) path.lineTo(rx[i], ry[i])
            cap(path, 0, xs[0] - xs[1], ys[0] - ys[1], lx[0], ly[0])
            path.close()
        }
    }

    /** Rounded end: a quadratic bulge from the current side to the other side of the ribbon. */
    private fun cap(path: Path, i: Int, dirX: Float, dirY: Float, toX: Float, toY: Float) {
        val length = sqrt(dirX * dirX + dirY * dirY).coerceAtLeast(0.0001f)
        val half = sqrt((lx[i] - rx[i]) * (lx[i] - rx[i]) + (ly[i] - ry[i]) * (ly[i] - ry[i])) / 2f
        val cx = (lx[i] + rx[i]) / 2f + dirX / length * half * 1.3f
        val cy = (ly[i] + ry[i]) / 2f + dirY / length * half * 1.3f
        path.quadraticTo(cx, cy, toX, toY)
    }

    private fun ensureRibbon(count: Int) {
        if (lx.size >= count) return
        lx = FloatArray(count * 2); ly = FloatArray(count * 2); rx = FloatArray(count * 2); ry = FloatArray(count * 2)
    }

    /** Adds quadratic Bézier samples (without the end point). */
    fun quad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, steps: Int): Sketcher {
        for (k in 0 until steps) {
            val t = k / steps.toFloat()
            val a = (1 - t) * (1 - t); val b = 2 * (1 - t) * t; val c = t * t
            point(a * x0 + b * cx + c * x1, a * y0 + b * cy + c * y1)
        }
        return this
    }

    /** Ellipse as sketchy control points (8 points + jitter keeps it hand-made). */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, points: Int = 8, phase: Float = 0f): Sketcher {
        begin()
        for (i in 0 until points) {
            val a = phase + i * (2f * PI.toFloat() / points)
            point(cx + rx * cos(a), cy + ry * sin(a))
        }
        return this
    }
}

/** Paper texture for a canvas of [side] px: scattered grain and diagonal pencil hatching (one per boil frame). */
internal class Texture(side: Float, boilFrames: Int, sketcher: Sketcher) {
    val grain = Path()
    val hatching = Array(boilFrames) { Path() }

    init {
        val dots = 420
        for (i in 0 until dots) {
            val x = (hash(71, i) * 0.5f + 0.5f) * side
            val y = (hash(73, i) * 0.5f + 0.5f) * side
            val r = side * (0.0028f + 0.0035f * (hash(79, i) * 0.5f + 0.5f))
            grain.addOval(Rect(Offset(x, y), r))
        }
        val spacing = side * 0.034f
        val lines = (2f * side / spacing).toInt()
        hatching.forEachIndexed { frame, path ->
            for (i in 0 until lines) {
                // 45° strokes from the bottom-left to the top-right, broken into short dashes.
                // Only the lower-right half: riso() fades hatching out towards the light anyway.
                val start = i * spacing - side
                if (start < -0.3f * side) continue
                var t = (hash(frame * 97 + 5, i) * 0.5f + 0.5f) * side * 0.12f
                while (t < side - maxOf(start, 0f)) {
                    val length = side * (0.07f + 0.08f * (hash(frame * 31 + i, t.toInt()) * 0.5f + 0.5f))
                    val x0 = start + t; val y0 = side - t
                    sketcher.begin()
                        .point(x0, y0)
                        .point(x0 + length * 0.5f, y0 - length * 0.5f + side * 0.003f * hash(frame, i))
                        .point(x0 + length, y0 - length)
                        .curve(closed = false, steps = 3)
                    sketcher.brush(path, side * 0.0065f, seed = frame * 13 + i, variation = 0.5f, taper = 0.9f, weight = 0f)
                    t += length + side * 0.035f
                }
            }
        }
    }
}
