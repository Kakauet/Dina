package com.kakauet.dina.ui.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kakauet.dina.ui.character.CharacterDesign
import com.kakauet.dina.ui.character.LocalCharacterDesign
import com.kakauet.dina.ui.theme.DinaTheme
import com.kakauet.dina.ui.theme.LightMeadow
import com.kakauet.dina.ui.theme.NightMeadow
import java.io.ByteArrayOutputStream
import java.io.File

/** Where `.\dev.ps1 screenshots` collects the PNGs (git-ignored). */
private val outputDir: File = File(System.getProperty("dina.screenshots.dir") ?: "build/screenshots")

/** Renders [content] and writes `<group>/<name>.png`. */
fun ComposeContentTestRule.snap(group: String, name: String, content: @Composable () -> Unit) {
    setContent(content)
    capture(group, name)
}

/** Writes the current content to `<group>/<name>.png`. */
fun ComposeContentTestRule.capture(group: String, name: String) {
    waitForIdle()
    onRoot().captureRoboImage(File(outputDir, "$group/$name.png").path)
}

/** App theme and character design, as MainActivity provides them. */
@Composable
fun Themed(dark: Boolean = false, design: CharacterDesign = CharacterDesign.BROTE, content: @Composable () -> Unit) {
    DinaTheme(if (dark) NightMeadow else LightMeadow) {
        CompositionLocalProvider(LocalCharacterDesign provides design, content = content)
    }
}

/**
 * Writes `<group>/<name>.gif` from [frames] captures of the content already set, calling
 * [frame] before each capture so the content can move its clock.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.gif(group: String, name: String, frames: Int, fps: Int, frame: (Int) -> Unit) {
    val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    var width = 0
    var height = 0
    val captures = (0 until frames).map { index ->
        frame(index)
        waitForIdle()
        val bounds = onRoot().fetchSemanticsNode().boundsInRoot
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        width = bounds.width.toInt()
        height = bounds.height.toInt()
        IntArray(width * height).also { full.getPixels(it, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, height) }
    }
    val file = File(outputDir, "$group/$name.gif").also { it.parentFile?.mkdirs() }
    file.writeBytes(GifEncoder.encode(captures, width, height, delayCs = 100 / fps))
}

/** Minimal animated GIF89a writer: one shared 256-color palette, LZW, infinite loop. */
private object GifEncoder {
    fun encode(frames: List<IntArray>, width: Int, height: Int, delayCs: Int): ByteArray {
        val palette = palette(frames)
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.short(width); out.short(height)
        out.write(0xF7); out.write(0); out.write(0)
        for (color in palette) { out.write(color shr 16 and 0xFF); out.write(color shr 8 and 0xFF); out.write(color and 0xFF) }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
        val lookup = IntArray(1 shl 15) { -1 }
        for (pixels in frames) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 4)); out.short(delayCs); out.write(0); out.write(0)
            out.write(0x2C); out.short(0); out.short(0); out.short(width); out.short(height); out.write(0)
            val indices = ByteArray(pixels.size) { i ->
                val key = key15(pixels[i])
                if (lookup[key] < 0) lookup[key] = nearest(palette, pixels[i])
                lookup[key].toByte()
            }
            Lzw(out).encode(indices)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun key15(c: Int) = (c shr 9 and 0x7C00) or (c shr 6 and 0x3E0) or (c shr 3 and 0x1F)

    /** The 256 most used 15-bit colors, each averaged over the pixels that fell in it. */
    private fun palette(frames: List<IntArray>): IntArray {
        val count = IntArray(1 shl 15)
        val r = LongArray(1 shl 15); val g = LongArray(1 shl 15); val b = LongArray(1 shl 15)
        for (f in frames.indices step 3) for (c in frames[f]) {
            val k = key15(c)
            count[k]++; r[k] += (c shr 16 and 0xFF).toLong(); g[k] += (c shr 8 and 0xFF).toLong(); b[k] += (c and 0xFF).toLong()
        }
        val top = count.indices.filter { count[it] > 0 }.sortedByDescending { count[it] }.take(256)
        return IntArray(256) { i ->
            val k = top.getOrNull(i) ?: return@IntArray 0
            ((r[k] / count[k]).toInt() shl 16) or ((g[k] / count[k]).toInt() shl 8) or (b[k] / count[k]).toInt()
        }
    }

    private fun nearest(palette: IntArray, c: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = (p shr 16 and 0xFF) - (c shr 16 and 0xFF)
            val dg = (p shr 8 and 0xFF) - (c shr 8 and 0xFF)
            val db = (p and 0xFF) - (c and 0xFF)
            val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }

    private fun ByteArrayOutputStream.short(value: Int) { write(value and 0xFF); write(value shr 8 and 0xFF) }

    /** Variable-width LZW with 8-bit roots, written as GIF sub-blocks. */
    private class Lzw(private val out: ByteArrayOutputStream) {
        private val clearCode = 256
        private val endCode = 257
        private var bits = 9
        private var maxCode = (1 shl bits) - 1
        private var next = 258
        private var clearing = false
        private val table = HashMap<Int, Int>()
        private var accumulator = 0
        private var accumulated = 0
        private val block = ByteArrayOutputStream()

        fun encode(indices: ByteArray) {
            out.write(8)
            emit(clearCode)
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val code = table[key]
                if (code != null) { prefix = code; continue }
                emit(prefix)
                prefix = k
                if (next < 4096) {
                    table[key] = next++
                } else {
                    table.clear()
                    next = 258
                    clearing = true
                    emit(clearCode)
                }
            }
            emit(prefix)
            emit(endCode)
            while (accumulated > 0) { byte(accumulator and 0xFF); accumulator = accumulator ushr 8; accumulated = maxOf(0, accumulated - 8) }
            flush()
            out.write(0)
        }

        private fun emit(code: Int) {
            accumulator = accumulator or (code shl accumulated)
            accumulated += bits
            while (accumulated >= 8) { byte(accumulator and 0xFF); accumulator = accumulator ushr 8; accumulated -= 8 }
            if (clearing) {
                bits = 9; maxCode = (1 shl bits) - 1; clearing = false
            } else if (next > maxCode && bits < 12) {
                bits++; maxCode = if (bits == 12) 4096 else (1 shl bits) - 1
            }
        }

        private fun byte(value: Int) { block.write(value); if (block.size() == 255) flush() }

        private fun flush() {
            if (block.size() == 0) return
            out.write(block.size()); block.writeTo(out); block.reset()
        }
    }
}
