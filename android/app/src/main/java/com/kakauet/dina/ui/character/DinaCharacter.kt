package com.kakauet.dina.ui.character

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.ui.theme.CharacterColors
import com.kakauet.dina.ui.theme.Dina

/** The two looks of Dina; chosen in Ajustes. */
enum class CharacterDesign(val label: String, val pitch: String, internal val rig: () -> Rig) {
    BROTE("Brote", "Una semillita con dos hojas", ::BroteRig),
    MUSGO("Musgo", "Un espíritu del bosque esponjoso", ::MusgoRig),
}

/** Design used by every [DinaCharacter] below it; the app provides the user's choice. */
val LocalCharacterDesign = staticCompositionLocalOf { CharacterDesign.BROTE }

fun characterDescription(state: VoiceState): String = when (state) {
    VoiceState.IDLE -> "Dina descansa y espera a que digas «Dina»"
    VoiceState.LOADING -> "Dina se está despertando"
    VoiceState.ACTIVATED -> "Dina te ha oído"
    VoiceState.LISTENING -> "Dina te está escuchando"
    VoiceState.TRANSCRIBING -> "Dina está entendiendo lo que has dicho"
    VoiceState.THINKING -> "Dina está pensando"
    VoiceState.EXECUTING -> "Dina lo está haciendo"
    VoiceState.SPEAKING -> "Dina está hablando"
    VoiceState.FOLLOW_UP -> "Dina espera por si quieres algo más"
    VoiceState.ERROR -> "Algo ha fallado"
}

private val Silent: () -> Float = { 0f }

/**
 * Dina as a hand-drawn character that acts out [state].
 *
 * The animation clock and [audioLevel] are read only in the draw phase, so animating at 60 fps
 * redraws this canvas without recomposing anything. [animated] = false draws one still pose
 * (chat history, previews); [time] drives the clock from outside (screenshots).
 * [describe] adds the state as content description and a polite live region; [greeting] makes
 * her wave hello whatever the state (app icon, empty chat).
 */
@Composable
fun DinaCharacter(
    state: VoiceState,
    modifier: Modifier = Modifier,
    audioLevel: () -> Float = Silent,
    design: CharacterDesign = LocalCharacterDesign.current,
    animated: Boolean = true,
    time: Float? = null,
    describe: Boolean = true,
    greeting: Boolean = false,
) {
    val motion = animated && !Dina.motion.reduced
    val clock = remember { mutableFloatStateOf(0f) }
    if (time != null) clock.floatValue = time
    if (motion && time == null) {
        LaunchedEffect(Unit) {
            // Infinite on purpose: Compose tests pause these instead of waiting for them.
            val base = clock.floatValue
            var start = -1L
            while (true) withInfiniteAnimationFrameNanos { now ->
                if (start < 0) start = now
                clock.floatValue = base + (now - start) / 1_000_000_000f
            }
        }
    }
    val renderer = remember(design) { CharacterRenderer(design.rig()) }
    SideEffect { renderer.switchTo(state, clock.floatValue, blend = motion) }
    val colors = Dina.colors.character
    val semantics = if (describe) Modifier.semantics {
        contentDescription = characterDescription(state)
        liveRegion = LiveRegionMode.Polite
    } else Modifier
    Spacer(
        modifier.then(semantics).drawWithCache {
            renderer.prepare(size.minDimension, density)
            onDrawBehind {
                with(renderer) { render(clock.floatValue, audioLevel(), motion, colors, greeting) }
            }
        },
    )
}

/** Keeps the poses between frames and blends from the last drawn pose when the state changes. */
internal class CharacterRenderer(private val rig: Rig) {
    private val frame = Frame(Sketcher())
    private val from = Pose()
    private val target = Pose()
    private val pose = Pose()
    private var state: VoiceState? = null
    private var enteredAt = 0f
    private var blending = false
    private var preparedSide = 0f

    /** The first state counts from time zero; later ones blend from the last drawn pose unless [blend] is off. */
    fun switchTo(next: VoiceState, now: Float, blend: Boolean) {
        if (next == state) return
        val first = state == null
        blending = blend && !first
        from.set(pose)
        state = next
        enteredAt = if (first) 0f else now
    }

    /** Builds textures and the rig's static paths once per canvas size. */
    fun prepare(side: Float, density: Float) {
        if (side == preparedSide || side <= 0f) return
        preparedSide = side
        frame.size(side, density)
        frame.texture = if (frame.detail) Texture(side, BOIL_FRAMES, frame.sk) else null
        rig.prepare(frame)
    }

    fun DrawScope.render(time: Float, level: Float, motion: Boolean, colors: CharacterColors, greeting: Boolean = false) {
        val current = state ?: return
        val f = frame
        f.start()
        f.colors = colors
        f.boil = if (motion) (time * BOIL_FPS).toInt() % BOIL_FRAMES else 0

        val since = time - enteredAt
        choreograph(current, time, since, level, motion, target, greeting)
        pose.blend(from, target, if (blending) smooth01(0f, TRANSITION_S, since) else 1f)

        val side = f.side
        // Soft ground shadow, outside the body transform so it stays on the floor.
        val shadow = 1f - pose.hop * 5f
        drawOval(
            colors.ink.copy(alpha = 0.1f),
            Offset(side * (0.5f - 0.2f * shadow), side * (GROUND - 0.022f)),
            Size(side * 0.4f * shadow, side * 0.044f),
        )
        val pivot = Offset(side * 0.5f, side * GROUND)
        val grow = 1f + 0.045f * pose.lean
        withTransform({
            translate(0f, -side * pose.hop)
            rotate(pose.tilt, pivot)
            scale((1f + pose.squash) * grow, (1f - pose.squash) * grow, pivot)
        }) {
            with(rig) { body(f, pose) }
            face(f, pose, f.u(rig.faceX), f.u(rig.faceY), side * rig.faceScale)
            overlays(f, pose, rig)
        }
    }

    private companion object {
        const val TRANSITION_S = 0.45f
    }
}
