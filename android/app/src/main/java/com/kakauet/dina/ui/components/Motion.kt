package com.kakauet.dina.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring as coreSpring
import androidx.compose.animation.core.tween as coreTween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kakauet.dina.ui.theme.Dina
import com.kakauet.dina.ui.theme.DinaMotion
import com.kakauet.dina.ui.theme.DinaSpring
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp

/*
 * Every animation in the app uses these springs and durations (tokens in Theme.kt), and all of
 * them jump to the end when the system asks for reduced motion.
 */

fun <T> DinaMotion.spring(token: DinaSpring, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    if (reduced) snap() else coreSpring(token.dampingRatio, token.stiffness, visibilityThreshold)

fun <T> DinaMotion.tween(duration: Int): FiniteAnimationSpec<T> = if (reduced) snap() else coreTween(duration)

/** Where appearing content comes from. */
enum class From { BELOW, START, END }

/**
 * Fades and lifts the content in the first time it is composed (new cards, new messages), with
 * the lively spring. Runs in the draw layer, so it never relayouts. No-op with reduced motion.
 */
@Composable
fun Modifier.appear(delayMs: Int = 0, from: From = From.BELOW): Modifier {
    val motion = Dina.motion
    if (motion.reduced) return this
    val lift = Dina.sizes.lift
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Waits on the frame clock rather than delay(), so it follows the clock tests drive too.
        if (delayMs > 0) {
            val start = withFrameNanos { it }
            while (withFrameNanos { it } - start < delayMs * 1_000_000L) Unit
        }
        progress.animateTo(1f, coreSpring(motion.lively.dampingRatio, motion.lively.stiffness))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val away = (1f - p) * lift.toPx()
        when (from) {
            From.BELOW -> translationY = away
            From.START -> translationX = -away
            From.END -> translationX = away
        }
        val scale = 0.96f + 0.04f * p
        scaleX = scale
        scaleY = scale
        transformOrigin = when (from) {
            From.BELOW -> TransformOrigin.Center
            From.START -> TransformOrigin(0f, 1f)
            From.END -> TransformOrigin(1f, 1f)
        }
    }
}

/** Content that comes and goes in place (bubbles, hints, buttons): fades, grows and lifts in. */
fun DinaMotion.enter(): EnterTransition =
    if (reduced) EnterTransition.None
    else fadeIn(spring(lively)) + expandVertically(spring(smooth, IntSize.VisibilityThreshold)) +
        scaleIn(spring(lively), initialScale = 0.94f)

fun DinaMotion.exit(): ExitTransition =
    if (reduced) ExitTransition.None
    else fadeOut(tween(quick)) + shrinkVertically(spring(smooth, IntSize.VisibilityThreshold)) +
        scaleOut(tween(quick), targetScale = 0.96f)

/** Tabs and screens: a short slide towards the direction of travel plus a crossfade. */
fun <S> AnimatedContentTransitionScope<S>.slideBetween(motion: DinaMotion, forward: Boolean): ContentTransform {
    if (motion.reduced) return EnterTransition.None togetherWith ExitTransition.None
    val direction = if (forward) 1 else -1
    val slide = motion.spring(motion.smooth, IntOffset.VisibilityThreshold)
    return (slideInHorizontally(slide) { direction * it / 6 } + fadeIn(motion.tween(motion.standard))) togetherWith
        (slideOutHorizontally(slide) { -direction * it / 6 } + fadeOut(motion.tween(motion.quick)))
}

/** A label that changes in place (Dina's state): the new one rises in as the old one fades up and out. */
fun <S> AnimatedContentTransitionScope<S>.riseBetween(motion: DinaMotion): ContentTransform {
    if (motion.reduced) return EnterTransition.None togetherWith ExitTransition.None
    val rise = motion.spring(motion.lively, IntOffset.VisibilityThreshold)
    return (fadeIn(motion.tween(motion.standard)) + slideInVertically(rise) { it / 3 }) togetherWith
        (fadeOut(motion.tween(motion.quick)) + slideOutVertically(motion.spring(motion.smooth, IntOffset.VisibilityThreshold)) { -it / 4 }) using
        SizeTransform(clip = false) { _, _ -> motion.spring(motion.smooth, IntSize.VisibilityThreshold) }
}

/** List items: fade in and out, and slide to their new place, with the shared springs. */
@Composable
fun LazyItemScope.animateDinaItem(): Modifier {
    val motion = Dina.motion
    if (motion.reduced) return Modifier
    return Modifier.animateItem(
        fadeInSpec = coreSpring(motion.lively.dampingRatio, motion.lively.stiffness),
        placementSpec = coreSpring(motion.smooth.dampingRatio, motion.smooth.stiffness, IntOffset.VisibilityThreshold),
        fadeOutSpec = coreTween(motion.quick),
    )
}

/** Press feedback for tappable surfaces that are not buttons: a slight sink while pressed. */
@Composable
fun Modifier.pressFeedback(interaction: InteractionSource): Modifier {
    val motion = Dina.motion
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(0f) }
    LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, motion.spring(motion.snappy)) }
    return graphicsLayer {
        val scale = 1f - 0.03f * press.value
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Follows a jumpy signal (the microphone level, ~50 updates per second): rises fast so speech
 * feels immediate, falls slowly so the meter does not flicker between syllables. Frame-rate
 * independent: [next] takes the time since the last frame.
 */
class LevelSmoother(private val attackS: Float = 0.045f, private val releaseS: Float = 0.22f) {
    var value = 0f
        private set

    fun next(target: Float, dtS: Float): Float {
        val goal = target.coerceIn(0f, 1f)
        val tau = if (goal > value) attackS else releaseS
        value += (goal - value) * (1f - exp(-dtS.coerceIn(0f, 0.1f) / tau))
        return value
    }

    fun reset(to: Float = 0f) { value = to }
}

/**
 * [level] smoothed every frame while composed. Read the result in the draw phase so only the
 * canvas that shows it redraws. With reduced motion it follows the raw level.
 */
@Composable
fun rememberSmoothedLevel(level: () -> Float, active: Boolean = true): State<Float> {
    val reduced = Dina.motion.reduced
    // Starts at the current level, so a still frame (screenshots, previews) shows it.
    val smoother = remember { LevelSmoother().apply { reset(if (active) level().coerceIn(0f, 1f) else 0f) } }
    val smoothed = remember { mutableFloatStateOf(smoother.value) }
    LaunchedEffect(active, reduced) {
        var last = -1L
        // Infinite on purpose: Compose tests pause these instead of waiting for them.
        while (true) withInfiniteAnimationFrameNanos { now ->
            val dt = if (last < 0) 0f else (now - last) / 1_000_000_000f
            last = now
            smoothed.floatValue = when {
                !active -> smoother.next(0f, dt)
                reduced -> level().coerceIn(0f, 1f).also { smoother.reset(it) }
                else -> smoother.next(level(), dt)
            }
        }
    }
    return smoothed
}

private class Shown<T>(val key: Any, var item: T, val visible: MutableTransitionState<Boolean>)

/**
 * A column whose rows animate in and out ([DinaMotion.enter]/[DinaMotion.exit]) when [items]
 * change, keyed by [key]; rows present when it first appears do not animate. For short lists
 * inside a card, where a lazy list cannot go.
 */
@Composable
fun <T> AnimatedColumn(items: List<T>, key: (T) -> Any, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    val motion = Dina.motion
    val shown = remember { ArrayList<Shown<T>>() }
    val first = remember { booleanArrayOf(true) }
    val keys = items.mapTo(HashSet(), key)
    shown.forEach { if (it.key !in keys) it.visible.targetState = false }
    var at = 0
    for (item in items) {
        val k = key(item)
        val i = shown.indexOfFirst { it.key == k }
        if (i >= 0) {
            shown[i].item = item
            shown[i].visible.targetState = true
            at = i + 1
        } else {
            shown.add(at++, Shown(k, item, MutableTransitionState(first[0]).apply { targetState = true }))
        }
    }
    first[0] = false
    // Rows that finished leaving; reading the state here recomposes once they have.
    shown.removeAll { !it.visible.targetState && !it.visible.currentState && it.visible.isIdle }
    Column(modifier) {
        for (entry in shown) {
            key(entry.key) {
                AnimatedVisibility(entry.visible, enter = motion.enter(), exit = motion.exit()) { row(entry.item) }
            }
        }
    }
}
