package com.kakauet.dina.ui.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.ui.character.CharacterDesign
import com.kakauet.dina.ui.character.DinaCharacter
import com.kakauet.dina.ui.components.Halo
import com.kakauet.dina.ui.components.SpeechBubble
import com.kakauet.dina.ui.components.Tail
import com.kakauet.dina.ui.theme.Dina
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/** A representative moment of each state: clock (s), audio level and caption. */
private class Moment(val state: VoiceState, val time: Float, val level: Float, val label: String)

private val moments = listOf(
    Moment(VoiceState.IDLE, 0.5f, 0f, "Esperando"),
    Moment(VoiceState.LOADING, 3.6f, 0f, "Despertando"),
    Moment(VoiceState.ACTIVATED, 0.14f, 0f, "Activada"),
    Moment(VoiceState.LISTENING, 1.1f, 0.65f, "Escuchando"),
    Moment(VoiceState.TRANSCRIBING, 0.6f, 0f, "Transcribiendo"),
    Moment(VoiceState.THINKING, 1f, 0f, "Pensando"),
    Moment(VoiceState.EXECUTING, 0.2f, 0f, "Hecho"),
    Moment(VoiceState.SPEAKING, 0.5f, 0.7f, "Hablando"),
    Moment(VoiceState.FOLLOW_UP, 0.4f, 0f, "¿Algo más?"),
    Moment(VoiceState.ERROR, 1f, 0f, "Error"),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xhdpi")
class CharacterScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun broteSheet() = compose.snap("character", "brote-sheet") { Sheet(CharacterDesign.BROTE) }

    @Test fun musgoSheet() = compose.snap("character", "musgo-sheet") { Sheet(CharacterDesign.MUSGO) }

    @Test fun broteDark() = compose.snap("character", "brote-sheet-dark") { Sheet(CharacterDesign.BROTE, dark = true) }

    /** One large PNG per state, as on the home screen. */
    @Test fun broteStates() = states(CharacterDesign.BROTE, "brote")

    @Test fun musgoStates() = states(CharacterDesign.MUSGO, "musgo")

    @Test fun broteStory() = story(CharacterDesign.BROTE, "brote-story")

    @Test fun musgoStory() = story(CharacterDesign.MUSGO, "musgo-story")

    private fun states(design: CharacterDesign, name: String) {
        val index = mutableIntStateOf(0)
        compose.setContent {
            val moment = moments[index.intValue]
            Themed(design = design) {
                Box(Modifier.background(Dina.colors.background).padding(12.dp), contentAlignment = Alignment.Center) {
                    Halo(Dina.colors.halo, Dina.colors.shadow, null, Modifier.size(236.dp))
                    // A fresh character per moment: no blend from the previous state.
                    key(moment) { DinaCharacter(moment.state, Modifier.size(244.dp), audioLevel = { moment.level }, time = moment.time) }
                }
            }
        }
        moments.forEachIndexed { i, moment ->
            index.intValue = i
            compose.capture("character/$name", "%02d-%s".format(i, moment.state.name.lowercase()))
        }
    }

    /** A short turn: idle, listening to a voice, thinking, speaking, done, follow-up. */
    private fun story(design: CharacterDesign, name: String) {
        val fps = 15
        val time = mutableFloatStateOf(0f)
        compose.setContent {
            val t by time
            val beat = scene(t)
            Themed(design = design) {
                Column(Modifier.background(Dina.colors.background).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        Halo(Dina.colors.halo, Dina.colors.shadow, null, Modifier.size(176.dp))
                        DinaCharacter(beat.state, Modifier.size(184.dp), audioLevel = { beat.level(t) }, time = t)
                    }
                    Text(beat.label, style = Dina.type.state, color = Dina.colors.ink)
                }
            }
        }
        compose.gif("character", name, frames = (STORY_S * fps).toInt(), fps = fps) { time.floatValue = it / fps.toFloat() }
    }
}

@Composable
private fun Sheet(design: CharacterDesign, dark: Boolean = false) = Themed(dark, design) {
    val c = Dina.colors
    Column(Modifier.width(420.dp).background(c.background).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(design.label, style = Dina.type.title, color = c.ink)
        Text(design.pitch, style = Dina.type.caption, color = c.inkMuted)
        moments.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { moment ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Halo(c.halo, c.shadow, null, Modifier.size(84.dp))
                            DinaCharacter(moment.state, Modifier.size(92.dp), audioLevel = { moment.level }, time = moment.time)
                        }
                        Text(moment.label, style = Dina.type.caption, color = c.ink)
                    }
                }
            }
        }
        // Avatar size, as in the chat.
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.padding(bottom = 11.dp).size(38.dp).background(c.halo, Dina.shapes.pill), contentAlignment = Alignment.Center) {
                DinaCharacter(VoiceState.IDLE, Modifier.size(38.dp), animated = false)
            }
            SpeechBubble(mine = false, tail = Tail.BOTTOM_START, modifier = Modifier.padding(start = 6.dp)) {
                Text("Listo, temporizador de 10 minutos.", style = Dina.type.body, color = c.ink)
            }
        }
    }
}

private const val STORY_S = 8f

private class Beat(val until: Float, val state: VoiceState, val label: String, val level: (Float) -> Float = { 0f })

private val story = listOf(
    Beat(1.8f, VoiceState.IDLE, "Esperando"),
    Beat(3.4f, VoiceState.LISTENING, "Escuchando") { t -> 0.35f + 0.45f * abs(sin(t * 7f)) * abs(sin(t * 2.3f)) },
    Beat(4.6f, VoiceState.THINKING, "Pensando"),
    Beat(6.2f, VoiceState.SPEAKING, "Hablando") { t -> abs(sin(t * 11f)) * (0.5f + 0.5f * abs(sin(t * 3.1f))) },
    Beat(7.1f, VoiceState.EXECUTING, "Hecho"),
    Beat(STORY_S, VoiceState.FOLLOW_UP, "¿Algo más?"),
)

private fun scene(t: Float) = story.first { t < it.until || it === story.last() }
