package com.kakauet.dina.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kakauet.dina.ui.theme.Dina
import com.kakauet.dina.ui.theme.DinaMotion
import kotlinx.coroutines.delay

/**
 * Fades and lifts the content in the first time it is composed (new cards, new messages).
 * The animation runs in the draw layer, so it never relayouts. No-op with reduced motion.
 */
@Composable
fun Modifier.appear(delayMs: Int = 0): Modifier {
    val motion = Dina.motion
    if (motion.reduced) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, tween(motion.gentle, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 14.dp.toPx()
        val scale = 0.97f + 0.03f * p
        scaleX = scale
        scaleY = scale
    }
}

/** Tabs and screens: a short slide towards the direction of travel plus a crossfade. */
fun <S> AnimatedContentTransitionScope<S>.slideBetween(motion: DinaMotion, forward: Boolean): ContentTransform {
    if (motion.reduced) return EnterTransition.None togetherWith ExitTransition.None
    val duration = motion.standard
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { direction * it / 6 } + fadeIn(tween(duration))) togetherWith
        (slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { -direction * it / 6 } + fadeOut(tween(duration / 2)))
}
