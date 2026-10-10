package com.kakauet.dina.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kakauet.dina.ui.character.Sketcher
import com.kakauet.dina.ui.theme.Dina

/** Card look: plain (mint), accent (selected) or warm (needs attention, e.g. ringing). */
enum class CardTone { PLAIN, ACCENT, WARM }

@Composable
fun DinaCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.PLAIN,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Dina.colors
    val fill by animateColorAsState(
        when (tone) { CardTone.PLAIN -> c.surface; CardTone.ACCENT -> c.accentSoft; CardTone.WARM -> c.warmSoft },
        tween(Dina.motion.ms(Dina.motion.standard)), label = "card",
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
    Text(text, modifier, style = Dina.type.caption, color = color, textAlign = textAlign, maxLines = maxLines)
}

enum class ButtonStyle { PRIMARY, SECONDARY, WARM, DANGER, GHOST }

private class ButtonColors(val fill: Color, val content: Color, val shadow: Color?, val outline: Color?)

@Composable
private fun buttonColors(style: ButtonStyle, enabled: Boolean): ButtonColors {
    val c = Dina.colors
    if (!enabled) return ButtonColors(c.surfaceAlt, c.inkMuted, null, c.line)
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
    PressableSurface(onClick, colors, modifier.heightIn(min = Dina.spacing.touch), enabled, radius = 24.dp, role = Role.Button) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                SketchIcon(icon, null, tint = colors.content, size = 20.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Dina.type.label, color = colors.content, textAlign = TextAlign.Center)
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
    size: Dp = 48.dp,
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(Dina.motion.ms(90)), label = "press")
    val shadow = colors.shadow
    Box(
        modifier
            .then(
                if (shadow == null) Modifier else remember(shadow, radius) {
                    Modifier.drawWithCache {
                        val sk = Sketcher()
                        val path = Path().also { sk.fill(it, sk.roundRect(size.width, size.height, radius.toPx(), density, seed = 21)) }
                        val offset = 3.dp.toPx()
                        onDrawBehind { translate(offset, offset) { drawPath(path, shadow) } }
                    }
                },
            )
            .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            .graphicsLayer {
                val offset = 3.dp.toPx() * press
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

/** Two or three options in a pill; a sliding marker shows the selected one. */
@Composable
fun PillSwitch(options: List<Pair<String, DinaIcon?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Dina.colors
    val position by animateFloatAsState(selected.toFloat(), tween(Dina.motion.ms(Dina.motion.standard)), label = "pill")
    val marker = c.accent
    Row(
        modifier
            .sketchBackground(c.surfaceAlt, c.line, shadow = null, radius = 30.dp, seed = 31)
            .padding(4.dp)
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
            val tint by animateColorAsState(if (active) c.onAccent else c.ink, tween(Dina.motion.ms(Dina.motion.standard)), label = "pillText")
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    SketchIcon(icon, null, tint = tint, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = Dina.type.label, color = tint)
            }
        }
    }
}

/** Speech bubble; Dina's are outlined cream, yours are sage. */
@Composable
fun SpeechBubble(mine: Boolean, tail: Tail, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Dina.colors
    Column(
        modifier
            .padding(top = if (tail == Tail.TOP_START) 11.dp else 0.dp, bottom = if (tail == Tail.BOTTOM_START || tail == Tail.BOTTOM_END) 11.dp else 0.dp)
            .sketchBackground(
                fill = if (mine) c.userBubble else c.dinaBubble,
                outline = c.line,
                shadow = if (mine) null else c.shadow,
                radius = 22.dp,
                tail = tail,
                seed = if (mine) 41 else 42,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        modifier = modifier.heightIn(min = 52.dp).semantics { contentDescription = label },
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = Dina.type.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { field ->
            Box(
                Modifier
                    .sketchBackground(c.dinaBubble, c.line, shadow = null, radius = 24.dp, seed = 51)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = Dina.type.body, color = c.inkMuted)
                field()
            }
        },
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, icon: DinaIcon? = null, count: Int? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = Dina.spacing.m, bottom = Dina.spacing.xs).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SketchIcon(icon, null, tint = Dina.colors.accent, size = 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = Dina.type.heading, color = Dina.colors.ink)
        if (count != null && count > 0) Badge(count.toString())
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = 30.dp).sketchBackground(Dina.colors.accentSoft, null, null, radius = 14.dp, seed = 61)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Dina.type.label, color = Dina.colors.onAccentSoft) }
}

/** Small rounded label, e.g. "Cada día". */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, icon: DinaIcon? = null, tone: CardTone = CardTone.ACCENT) {
    val c = Dina.colors
    val fill = when (tone) { CardTone.PLAIN -> c.surfaceAlt; CardTone.ACCENT -> c.accentSoft; CardTone.WARM -> c.warmSoft }
    val ink = if (tone == CardTone.WARM) c.onWarm else c.onAccentSoft
    Row(
        modifier.sketchBackground(fill, null, null, radius = 14.dp, seed = 62).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SketchIcon(icon, null, tint = ink, size = 15.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = Dina.type.caption, color = ink)
    }
}

@Composable
fun DinaSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val c = Dina.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = c.accent,
            uncheckedThumbColor = c.inkMuted,
            uncheckedTrackColor = c.surfaceAlt,
            uncheckedBorderColor = c.inkMuted,
        ),
    )
}

@Composable
fun DinaSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null) {
    val c = Dina.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.surfaceSunken,
            activeTickColor = c.onAccent,
            inactiveTickColor = c.inkMuted,
        ),
    )
}

/** Title + description + switch; the whole row toggles. */
@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DinaCard(
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        padding = PaddingValues(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Dina.type.bodyStrong, color = Dina.colors.ink)
                Caption(detail, Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(Dina.spacing.m))
            DinaSwitch(checked, onCheckedChange = null)
        }
    }
}

@Composable
fun InfoRow(title: String, detail: String? = null) {
    DinaCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text(title, style = Dina.type.bodyStrong, color = Dina.colors.ink)
        detail?.let { Caption(it, Modifier.padding(top = 3.dp)) }

    }
}

/** Icon in a soft disk + text: for empty lists. */
@Composable
fun EmptyState(icon: DinaIcon, title: String, detail: String, modifier: Modifier = Modifier, action: (@Composable ColumnScope.() -> Unit)? = null) {
    DinaCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(Dina.colors.halo, Dina.shapes.pill), contentAlignment = Alignment.Center) {
                SketchIcon(icon, null, tint = Dina.colors.accent, size = 26.dp)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, style = Dina.type.bodyStrong, color = Dina.colors.ink)
                Caption(detail, Modifier.padding(top = 2.dp))
            }
        }
        action?.invoke(this)
    }
}

/** Secondary screen: back button, title and a scrolling column that respects system bars. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = Dina.colors
    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = Dina.spacing.gutter, top = 6.dp, bottom = 4.dp).heightIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(DinaIcon.BACK, "Volver", onBack, style = ButtonStyle.GHOST)
            Text(title, Modifier.padding(start = 6.dp).weight(1f).semantics { heading() }, style = Dina.type.title, color = c.ink)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dina.spacing.gutter, end = Dina.spacing.gutter, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (subtitle != null) item { Caption(subtitle, Modifier.padding(start = 4.dp, bottom = Dina.spacing.xs)) }
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
