package com.kakauet.dina.ui.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.ui.character.CharacterDesign
import com.kakauet.dina.ui.character.DinaCharacter
import com.kakauet.dina.ui.components.Halo
import com.kakauet.dina.ui.theme.CharacterInk
import com.kakauet.dina.ui.theme.DinaTheme
import com.kakauet.dina.ui.theme.LightMeadow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Launcher icons drawn by the same code as the character. `.\dev.ps1 icons` writes the layers
 * into res/mipmap-*; `.\dev.ps1 screenshots` only writes previews of the masked icons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xxxhdpi")
class IconRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val res = System.getProperty("dina.icons.res")?.let(::File)

    /** Layer 0: background, then foreground and monochrome per design. */
    @Test
    fun layers() {
        if (res == null) return
        val layer = mutableIntStateOf(0)
        compose.setContent {
            Box(Modifier.size(108.dp)) {
                when (layer.intValue) {
                    0 -> Background()
                    1 -> Foreground(CharacterDesign.BROTE, mono = false)
                    2 -> Foreground(CharacterDesign.BROTE, mono = true)
                    3 -> Foreground(CharacterDesign.MUSGO, mono = false)
                    else -> Foreground(CharacterDesign.MUSGO, mono = true)
                }
            }
        }
        val names = listOf("ic_launcher_background", "ic_brote_foreground", "ic_brote_monochrome", "ic_musgo_foreground", "ic_musgo_monochrome")
        names.forEachIndexed { index, name ->
            layer.intValue = index
            compose.waitForIdle()
            writeMipmaps(render(), name)
        }
    }

    @Test
    fun preview() = compose.snap("icons", "launcher") {
        // The same layers under the round and squircle masks launchers use (72 of 108 dp visible),
        // and the themed (monochrome) version on a light tint.
        Column(Modifier.background(Color(0xFFE9E4DA)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (design in CharacterDesign.entries) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Masked(design, CircleShape)
                    Masked(design, RoundedCornerShape(22.dp))
                    Masked(design, CircleShape, mono = true)
                }
            }
        }
    }

    @Composable
    private fun Masked(design: CharacterDesign, shape: androidx.compose.ui.graphics.Shape, mono: Boolean = false) {
        Box(Modifier.size(72.dp).clip(shape).background(if (mono) Color(0xFFD9E7D6) else Color.Transparent), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(108.dp)) {
                if (!mono) Background()
                Foreground(design, mono)
            }
        }
    }

    /** Draws the composed icon layer into a 432 px bitmap (108 dp at xxxhdpi). */
    private fun render(): Bitmap {
        val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        return Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
    }

    /** Halves the image step by step (sharper than one big scale) for each density. */
    private fun writeMipmaps(xxxhdpi: Bitmap, name: String) {
        val sizes = listOf("xxxhdpi" to 432, "xxhdpi" to 324, "xhdpi" to 216, "hdpi" to 162, "mdpi" to 108)
        for ((density, size) in sizes) {
            var image = xxxhdpi
            while (image.width / 2 >= size) image = Bitmap.createScaledBitmap(image, image.width / 2, image.height / 2, true)
            if (image.width != size) image = Bitmap.createScaledBitmap(image, size, size, true)
            val dir = File(res, "mipmap-$density").also { it.mkdirs() }
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

/** Cream paper with Dina's mint halo, as on the home screen. */
@Composable
private fun Background() = DinaTheme(LightMeadow) {
    Box(Modifier.size(108.dp).background(LightMeadow.background), contentAlignment = Alignment.Center) {
        Halo(LightMeadow.halo, LightMeadow.shadow, null, Modifier.size(70.dp))
    }
}

/** Ink only, for Android 13+ themed icons (the launcher tints it). */
private val MonoInk = CharacterInk.copy(
    ink = Color.Black, aura = Color.Black, body = Color.Transparent, bodyShade = Color.Transparent,
    bodyLight = Color.Transparent, leaf = Color.Transparent, leafShade = Color.Transparent, belly = Color.Transparent,
    cheek = Color.Transparent, mouth = Color.Black, tongue = Color.Transparent, shine = Color.Transparent,
    spark = Color.Transparent, sweat = Color.Transparent, bubble = Color.Transparent,
)

@Composable
private fun Foreground(design: CharacterDesign, mono: Boolean) = DinaTheme(if (mono) LightMeadow.copy(character = MonoInk) else LightMeadow) {
    Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
        DinaCharacter(VoiceState.IDLE, Modifier.size(80.dp).offset(x = if (design == CharacterDesign.MUSGO) (-4).dp else 0.dp, y = (-1).dp), design = design, time = 0.967f, describe = false, greeting = true)
    }
}
