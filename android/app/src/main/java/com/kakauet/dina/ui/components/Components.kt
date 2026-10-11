package com.kakauet.dina.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakauet.dina.core.VoiceState
import com.kakauet.dina.ui.character.DinaCharacter
import com.kakauet.dina.ui.character.Sketcher
import com.kakauet.dina.ui.theme.Dina

/** Card look: plain (mint), accent (selected) or warm (needs attention, e.g. ringing). */
enum class CardTone { PLAIN, ACCENT, WARM }

@Composable
fun DinaCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.PLAIN,
    padding: PaddingValues = PaddingValues(horizontal = Dina.spacing.cardX, vertical = Dina.spacing.cardY),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Dina.colors
    val motion = Dina.motion
    val fill by animateColorAsState(
        when (tone) { CardTone.PLAIN -> c.surface; CardTone.ACCENT -> c.accentSoft; CardTone.WARM -> c.warmSoft },
        motion.tween(motion.standard), label = "card",
    )
    Column(
        modifier
            .fillMaxWidth()
            .sketchBackground(fill, outline = if (tone == CardTone.WARM) c.warm else c.line, shadow = c.shadow)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Dina.colors.accent) {
    Text(text.uppercase(), modifier, style = Dina.type.overline, color = color)
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Dina.colors.inkMuted, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, style = Dina.type.caption, color = color, textAlign = textAlign, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A note under a card or control, lined up with the text inside the cards. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Caption(text, modifier.padding(horizontal = Dina.spacing.cardX))
}

/**
 * One line that shrinks its font (down to [minSize]) instead of wrapping or clipping: pills,
 * big numbers. [color] is read when drawing, so it can animate without recomposing.
 */
@Composable
fun FitText(text: String, style: TextStyle, color: () -> Color, modifier: Modifier = Modifier, minSize: TextUnit = Dina.type.fitMin) {
    BasicText(
        text,
        modifier,
        style = style,
        maxLines = 1,
        softWrap = false,
        color = color,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

enum class ButtonStyle { PRIMARY, SECONDARY, WARM, DANGER, GHOST }

private class ButtonColors(val fill: Color, val content: Color, val shadow: Color?, val outline: Color?)

@Composable
private fun buttonColors(style: ButtonStyle, enabled: Boolean): ButtonColors {
    val c = Dina.colors
    if (!enabled) return ButtonColors(c.surfaceAlt, c.inkMuted, null, c.lineStrong)
    return when (style) {
        ButtonStyle.PRIMARY -> ButtonColors(c.accent, c.onAccent, c.ink.copy(alpha = if (c.isDark) 0.5f else 0.85f), null)
        ButtonStyle.SECONDARY -> ButtonColors(c.surface, c.ink, c.shadow, c.line)
        ButtonStyle.WARM -> ButtonColors(c.warm, c.onWarmFill, c.onWarm.copy(alpha = 0.5f), null)
        ButtonStyle.DANGER -> ButtonColors(c.dangerSoft, c.danger, c.shadow, c.danger.copy(alpha = 0.6f))
        ButtonStyle.GHOST -> ButtonColors(Color.Transparent, c.accent, null, null)
    }
}

/**
 * Sticker-like button: it sits on an off-register shadow and sinks onto it while pressed.
 * At least 48 dp tall.
 */
@Composable
fun DinaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: DinaIcon? = null,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    enabled: Boolean = true,
) {
    val colors = buttonColors(style, enabled)
    PressableSurface(onClick, colors, modifier.heightIn(min = Dina.spacing.touch), enabled, radius = Dina.shapes.buttonRadius, role = Role.Button) {
        Row(
            Modifier.padding(horizontal = Dina.spacing.l, vertical = Dina.spacing.s),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                SketchIcon(icon, null, tint = colors.content, size = Dina.sizes.icon)
                Spacer(Modifier.width(Dina.spacing.iconGap))
            }
            // One line that shrinks with large fonts, rather than breaking a word in two.
            FitText(text, Dina.type.label, { colors.content })
        }
    }
}

/** Round icon-only button (48 dp touch target). */
@Composable
fun RoundIconButton(
    icon: DinaIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    enabled: Boolean = true,
    size: Dp = Dina.spacing.touch,
) {
    val colors = buttonColors(style, enabled)
    PressableSurface(onClick, colors, modifier.size(size).semantics { this.contentDescription = contentDescription }, enabled, radius = size / 2, role = Role.Button) {
        SketchIcon(icon, null, Modifier.align(Alignment.Center), tint = colors.content, size = size * 0.46f)
    }
}

@Composable
private fun PressableSurface(
    onClick: () -> Unit,
    colors: ButtonColors,
    modifier: Modifier,
    enabled: Boolean,
    radius: Dp,
    role: Role,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = Dina.motion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Read in the layer, so pressing never recomposes the button.
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, motion.spring(motion.snappy), label = "press")
    val shadow = colors.shadow
    val depth = Dina.sizes.shadowOffset
    Box(
        modifier
            .then(
                if (shadow == null) Modifier else remember(shadow, radius, depth) {
                    Modifier.drawWithCache {
                        val sk = Sketcher()
                        val path = Path().also { sk.fill(it, sk.roundRect(size.width, size.height, radius.toPx(), density, seed = 21)) }
                        val offset = depth.toPx()
                        onDrawBehind { translate(offset, offset) { drawPath(path, shadow) } }
                    }
                },
            )
            .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            .graphicsLayer {
                val offset = depth.toPx() * press
                translationX = offset
                translationY = offset
            }
            .sketchBackground(colors.fill, colors.outline, shadow = null, radius = radius, seed = 21),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Equal-width actions; the first one is the main action unless styles say otherwise. */
class Action(val label: String, val onClick: () -> Unit, val icon: DinaIcon? = null, val style: ButtonStyle = ButtonStyle.SECONDARY)

/** [compact] drops the icons, for narrow cards. */
@Composable
fun ActionRow(vararg actions: Action, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(top = Dina.spacing.m), horizontalArrangement = Arrangement.spacedBy(Dina.spacing.s)) {
        actions.forEach { DinaButton(it.label, it.onClick, Modifier.weight(1f), icon = it.icon.takeUnless { compact }, style = it.style) }
    }
}

/** Two or three options in a pill; a sliding marker shows the selected one. Labels shrink rather than wrap. */
@Composable
fun PillSwitch(options: List<Pair<String, DinaIcon?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Dina.colors
    val motion = Dina.motion
    val position by animateFloatAsState(selected.toFloat(), motion.spring(motion.snappy), label = "pill")
    val marker = c.accent
    val inset = Dina.spacing.xs
    Row(
        modifier
            .sketchBackground(c.surfaceAlt, c.line, shadow = null, radius = Dina.shapes.buttonRadius + inset, seed = 31)
            .padding(inset)
            .then(
                remember(marker, options.size) {
                    Modifier.drawWithCache {
                        val sk = Sketcher()
                        val w = size.width / options.size
                        val pill = Path().also { sk.fill(it, sk.roundRect(w, size.height, size.height / 2f, density, seed = 32)) }
                        onDrawBehind { translate(w * position, 0f) { drawPath(pill, marker) } }
                    }
                },
            )
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, (label, icon) ->
            val active = index == selected
            val tint by animateColorAsState(if (active) c.onAccent else c.ink, motion.tween(motion.standard), label = "pillText")
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = Dina.spacing.touch - inset * 2)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = Dina.spacing.s),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    SketchIcon(icon, null, tint = tint, size = Dina.sizes.icon)
                    Spacer(Modifier.width(Dina.spacing.iconGap - Dina.spacing.xxs))
                }
                FitText(label, Dina.type.label, { tint })
            }
        }
    }
}

/** Speech bubble; Dina's are outlined cream, yours are sage. Room is left for the tail. */
@Composable
fun SpeechBubble(mine: Boolean, tail: Tail, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Dina.colors
    val tailRoom = Dina.spacing.m
    Column(
        modifier
            .padding(top = if (tail == Tail.TOP_START) tailRoom else 0.dp, bottom = if (tail == Tail.BOTTOM_START || tail == Tail.BOTTOM_END) tailRoom else 0.dp)
            .sketchBackground(
                fill = if (mine) c.userBubble else c.dinaBubble,
                outline = c.line,
                shadow = if (mine) null else c.shadow,
                radius = Dina.shapes.bubbleRadius,
                tail = tail,
                seed = if (mine) 41 else 42,
            )
            .padding(horizontal = Dina.spacing.l - Dina.spacing.xxs, vertical = Dina.spacing.m),
        content = content,
    )
}

/** Text field in a hand-drawn pill. */
@Composable
fun DinaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    label: String = placeholder,
) {
    val c = Dina.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = Dina.sizes.field).semantics { contentDescription = label },
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = Dina.type.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { field ->
            Box(
                Modifier
                    .heightIn(min = Dina.sizes.field)
                    .sketchBackground(c.dinaBubble, c.lineStrong, shadow = null, radius = Dina.shapes.buttonRadius, seed = 51)
                    .padding(horizontal = Dina.spacing.l, vertical = Dina.spacing.m),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = Dina.type.body, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
        },
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, icon: DinaIcon? = null, count: Int? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = Dina.spacing.m, bottom = Dina.spacing.xs).heightIn(min = Dina.spacing.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SketchIcon(icon, null, tint = Dina.colors.accent, size = Dina.sizes.iconLarge)
            Spacer(Modifier.width(Dina.spacing.iconGap + Dina.spacing.xxs))
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = Dina.type.heading, color = Dina.colors.ink)
        AnimatedVisibility(count != null && count > 0, enter = Dina.motion.enter(), exit = Dina.motion.exit()) {
            Badge((count ?: 0).toString())
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = Dina.sizes.iconLarge + Dina.spacing.xs)
            .sketchBackground(Dina.colors.accentSoft, null, null, radius = Dina.shapes.chipRadius, seed = 61)
            .padding(horizontal = Dina.spacing.s, vertical = Dina.spacing.xxs),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Dina.type.label, color = Dina.colors.onAccentSoft) }
}

/** Small rounded label, e.g. "Cada día". */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, icon: DinaIcon? = null, tone: CardTone = CardTone.ACCENT) {
    val c = Dina.colors
    val fill = when (tone) { CardTone.PLAIN -> c.surfaceAlt; CardTone.ACCENT -> c.accentSoft; CardTone.WARM -> c.warmSoft }
    val ink = when (tone) { CardTone.PLAIN -> c.inkMuted; CardTone.ACCENT -> c.onAccentSoft; CardTone.WARM -> c.onWarm }
    Row(
        modifier.sketchBackground(fill, null, null, radius = Dina.shapes.chipRadius, seed = 62)
            .padding(horizontal = Dina.spacing.s + Dina.spacing.xxs, vertical = Dina.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SketchIcon(icon, null, tint = ink, size = Dina.sizes.iconSmall)
            Spacer(Modifier.width(Dina.spacing.xs))
        }
        Text(text, style = Dina.type.caption, color = ink)
    }
}

/**
 * Hand-drawn switch: a wobbly track and a thumb that springs across. With [onCheckedChange] =
 * null it only shows the state (the whole row toggles, see [ToggleRow]).
 */
@Composable
fun DinaSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val c = Dina.colors
    val motion = Dina.motion
    val position by animateFloatAsState(if (checked) 1f else 0f, motion.spring(motion.snappy), label = "switch")
    val track by animateColorAsState(if (checked) c.accent else c.surfaceAlt, motion.tween(motion.standard), label = "switchTrack")
    val thumb by animateColorAsState(if (checked) c.onAccent else c.lineStrong, motion.tween(motion.standard), label = "switchThumb")
    val outline by animateColorAsState(if (checked) c.accent else c.lineStrong, motion.tween(motion.standard), label = "switchLine")
    val interactive = if (onCheckedChange == null) Modifier else
        Modifier.minimumInteractiveComponentSize().toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
    Spacer(
        modifier.then(interactive).size(Dina.sizes.switchWidth, Dina.sizes.switchHeight).drawWithCache {
            val sk = Sketcher()
            val h = size.height
            val line = sk.roundRect(size.width, h, h / 2f, density, seed = 71)
            val body = Path().also { sk.fill(it, line) }
            val ink = Path().also { sk.brush(it, 1.5f * density, 72, line, variation = 0.3f, weight = 0.5f) }
            val r = h * 0.34f
            val dot = Path().also { sk.fill(it, sk.circle(0f, 0f, r, r * 0.05f, 73, points = 9)) }
            val travel = size.width - h
            onDrawBehind {
                drawPath(body, track)
                drawPath(ink, outline)
                val p = position
                translate(h / 2f + travel * p, h / 2f) {
                    // The thumb grows a little when on, like a pressed sticker.
                    scale(0.82f + 0.18f * p.coerceIn(0f, 1f), pivot = Offset.Zero) { drawPath(dot, thumb) }
                }
            }
        },
    )
}

/** Slider with a hand-drawn track (inked up to the value) and a sticker thumb; Material handles touch and accessibility. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DinaSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null) {
    val c = Dina.colors
    val motion = Dina.motion
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsPressedAsState()
    val grab by animateFloatAsState(if (dragging) 1f else 0f, motion.spring(motion.snappy), label = "grab")
    // One state object for the whole life of the slider, read while drawing the track.
    val fraction by rememberUpdatedState(((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f))
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = {
            Spacer(
                Modifier.size(Dina.sizes.sliderThumb).drawWithCache {
                    val sk = Sketcher()
                    val r = size.minDimension / 2f * 0.86f
                    val line = sk.circle(size.width / 2f, size.height / 2f, r, r * 0.04f, 76, points = 10)
                    val disk = Path().also { sk.fill(it, line) }
                    val ink = Path().also { sk.brush(it, 1.5f * density, 77, line, variation = 0.3f, weight = 0.6f) }
                    val depth = 2f * density
                    onDrawBehind {
                        val sink = depth * grab
                        translate(depth, depth) { drawPath(disk, c.shadow) }
                        translate(sink, sink) {
                            drawPath(disk, c.accent)
                            drawPath(ink, c.ink.copy(alpha = 0.5f))
                        }
                    }
                },
            )
        },
        track = {
            Spacer(
                Modifier.fillMaxWidth().height(Dina.sizes.bar).drawWithCache {
                    val sk = Sketcher()
                    val line = sk.roundRect(size.width, size.height, size.height / 2f, density, seed = 78)
                    val body = Path().also { sk.fill(it, line) }
                    val ink = Path().also { sk.brush(it, 1.3f * density, 79, line, variation = 0.3f, weight = 0.5f) }
                    val tick = size.height * 0.14f
                    onDrawBehind {
                        drawPath(body, c.surfaceSunken)
                        clipRect(right = size.width * fraction) { drawPath(body, c.accent) }
                        for (i in 1..steps) {
                            val x = size.width * i / (steps + 1)
                            drawCircle(if (x <= size.width * fraction) c.onAccent else c.inkMuted, tick, Offset(x, size.height / 2f), alpha = 0.7f)
                        }
                        drawPath(ink, c.lineStrong)
                    }
                },
            )
        },
    )
}

/** Title + description + switch; the whole row toggles. */
@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    DinaCard(
        Modifier
            .pressFeedback(interaction)
            .toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onChange),
        padding = PaddingValues(start = Dina.spacing.cardX, end = Dina.spacing.m, top = Dina.spacing.m + Dina.spacing.xxs, bottom = Dina.spacing.m + Dina.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Dina.type.bodyStrong, color = Dina.colors.ink)
                Caption(detail, Modifier.padding(top = Dina.spacing.xxs))
            }
            Spacer(Modifier.width(Dina.spacing.m))
            DinaSwitch(checked, onCheckedChange = null)
        }
    }
}

@Composable
fun InfoRow(title: String, detail: String? = null) {
    DinaCard(padding = PaddingValues(horizontal = Dina.spacing.cardX, vertical = Dina.spacing.m + Dina.spacing.xxs)) {
        Text(title, style = Dina.type.bodyStrong, color = Dina.colors.ink)
        detail?.let { Caption(it, Modifier.padding(top = Dina.spacing.xxs)) }
    }
}

/** Several facts in one card, title over detail, with drawn rules between them. */
@Composable
fun InfoGroup(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    DinaCard(modifier, padding = PaddingValues(horizontal = Dina.spacing.cardX, vertical = Dina.spacing.xs)) {
        rows.forEachIndexed { index, (title, detail) ->
            if (index > 0) SketchDivider(seed = 95 + index)
            Column(Modifier.fillMaxWidth().padding(vertical = Dina.spacing.m).semantics(mergeDescendants = true) {}) {
                Text(title, style = Dina.type.bodyStrong, color = Dina.colors.ink)
                Caption(detail, Modifier.padding(top = Dina.spacing.xxs))
            }
        }
    }
}

/** One line of a [MetricTable]; [strong] lines stand out (totals). */
class Metric(val label: String, val value: String, val strong: Boolean = false, val indent: Boolean = false)

/** Label on the left, value on the right; long values wrap under themselves, not under the label. */
@Composable
fun MetricTable(rows: List<Metric>, modifier: Modifier = Modifier) {
    val c = Dina.colors
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) SketchDivider(color = c.line.copy(alpha = 0.6f), seed = 120 + index)
            Row(
                Modifier.fillMaxWidth().padding(vertical = Dina.spacing.s - Dina.spacing.xxs).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Dina.spacing.m),
            ) {
                Text(
                    row.label,
                    Modifier.weight(0.48f).padding(start = if (row.indent) Dina.spacing.m else 0.dp),
                    style = if (row.strong) Dina.type.label else Dina.type.caption,
                    color = if (row.strong) c.ink else c.inkMuted,
                )
                Text(
                    row.value,
                    Modifier.weight(0.52f),
                    style = if (row.strong) Dina.type.label else Dina.type.caption,
                    color = if (row.strong) c.accent else c.ink,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** Dina (still, in a mood that fits) next to a short text: for empty lists. */
@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, mood: VoiceState = VoiceState.IDLE, action: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = Dina.colors
    DinaCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Dina.sizes.characterTiny), contentAlignment = Alignment.Center) {
                Halo({ c.halo }, c.shadow, outline = null, modifier = Modifier.matchParentSize())
                DinaCharacter(mood, Modifier.matchParentSize(), animated = false, describe = false)
            }
            Column(Modifier.padding(start = Dina.spacing.l - Dina.spacing.xxs).weight(1f)) {
                Text(title, style = Dina.type.bodyStrong, color = c.ink)
                Caption(detail, Modifier.padding(top = Dina.spacing.xxs))
            }
        }
        action?.invoke(this)
    }
}

/**
 * Secondary screen: a back button, then the title as the first item of a scrolling column that
 * respects system bars. Once the title scrolls away, a small copy of it fades into the top bar.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = Dina.colors
    val motion = Dina.motion
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val barTitle by animateFloatAsState(if (scrolled) 1f else 0f, motion.spring(motion.smooth), label = "barTitle")
    val rise = Dina.spacing.s
    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        Row(
            // The back arrow's ink lines up with the screen margin.
            Modifier.fillMaxWidth().padding(start = Dina.spacing.xs, end = Dina.spacing.gutter, top = Dina.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(DinaIcon.BACK, "Volver", onBack, style = ButtonStyle.GHOST)
            Text(
                title,
                Modifier.padding(start = Dina.spacing.xs).weight(1f).graphicsLayer {
                    alpha = barTitle
                    translationY = (1f - barTitle) * rise.toPx()
                },
                style = Dina.type.heading,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = Dina.spacing.gutter, end = Dina.spacing.gutter, bottom = Dina.spacing.xxl + Dina.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Dina.spacing.stack),
        ) {
            item(key = "title") {
                Column(Modifier.padding(bottom = Dina.spacing.xs)) {
                    Text(title, Modifier.semantics { heading() }, style = Dina.type.title, color = c.ink)
                    if (subtitle != null) Caption(subtitle, Modifier.padding(top = Dina.spacing.xs))
                }
            }
            content()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Dina.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Dina.type.heading, color = c.ink) },
        text = { Text(message, style = Dina.type.body, color = c.inkMuted) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel, style = Dina.type.label, color = c.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", style = Dina.type.label, color = c.accent) } },
        containerColor = c.dinaBubble,
        shape = Dina.shapes.card,
    )
}
