package com.aurora.tvplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurora.tvplayer.ui.theme.Aurora

val ButtonShape = RoundedCornerShape(10.dp)
val CardShape = RoundedCornerShape(14.dp)

/** The 2.x focus language: the focused element fills with cyan and glows; text turns navy. */
@Composable
private fun auroraColors(container: Color = Aurora.panel, content: Color = Aurora.white) = ClickableSurfaceDefaults.colors(
    containerColor = container,
    contentColor = content,
    focusedContainerColor = Aurora.cyan,
    focusedContentColor = Aurora.navy,
    pressedContainerColor = Aurora.mint,
    pressedContentColor = Aurora.navy,
    disabledContainerColor = Aurora.panel.copy(alpha = 0.5f),
    disabledContentColor = Aurora.faint,
)

@Composable
private fun auroraBorder(shape: Shape, color: Color = Aurora.line) = ClickableSurfaceDefaults.border(
    border = Border(BorderStroke(1.dp, color), shape = shape),
    focusedBorder = Border(BorderStroke(2.dp, Aurora.white), shape = shape),
)

private val focusGlow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Aurora.cyan.copy(alpha = 0.55f), 14.dp))

@Composable
fun AuroraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 46.dp),
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = auroraColors(container = if (accent) Aurora.panelHigh else Aurora.panel, content = if (accent) Aurora.cyan else Aurora.white),
        border = auroraBorder(ButtonShape, if (accent) Aurora.cyan.copy(alpha = 0.6f) else Aurora.line),
        glow = focusGlow,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            icon?.let { Icon(it, null, Modifier.size(20.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round icon button with an optional caption under it (player and toolbars). */
@Composable
fun RoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = auroraColors(container = if (emphasized) Aurora.white.copy(alpha = 0.16f) else Aurora.white.copy(alpha = 0.08f)),
        border = auroraBorder(CircleShape, Aurora.white.copy(alpha = 0.14f)),
        glow = focusGlow,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(size * 0.5f))
        }
    }
}

/**
 * Source card of the home screen, the most recognizable element of Aurora TV: icon badge on top,
 * full title and hint below. [compact] is the smaller tile of the "Ещё" row in the same style.
 */
@Composable
fun SourceCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (compact) 72.dp else 120.dp),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = auroraColors(container = Aurora.panel.copy(alpha = 0.92f)),
        border = auroraBorder(CardShape, Aurora.line),
        glow = focusGlow,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, null, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(44.dp).background(LocalContentColor.current.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Toolbar chip; [selected] marks a toggled filter. */
@Composable
fun AuroraChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = auroraColors(container = if (selected) Aurora.cyan.copy(alpha = 0.18f) else Aurora.panel, content = if (selected) Aurora.cyan else Aurora.white),
        border = auroraBorder(shape, if (selected) Aurora.cyan else Aurora.line),
        glow = focusGlow,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, null, Modifier.size(18.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** List row used by the file browsers, menus and settings. */
@Composable
fun AuroraRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = auroraColors(container = Aurora.panel.copy(alpha = if (dimmed) 0.45f else 0.85f), content = if (dimmed) Aurora.muted else Aurora.white),
        border = auroraBorder(ButtonShape, Aurora.line.copy(alpha = 0.6f)),
        glow = focusGlow,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.012f),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Aurora.cyan, track: Color = Aurora.white.copy(alpha = 0.18f)) {
    Box(modifier.height(4.dp).background(track, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(color, RoundedCornerShape(2.dp)))
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 44.dp, color: Color = Aurora.cyan) {
    val rotation by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rotation",
    )
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() / 10
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(stroke / 2, stroke / 2)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(color, rotation, 100f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * Text input that behaves on a remote: it is a button until OK is pressed, so moving through
 * the screen never pops up the keyboard by accident.
 */
@Composable
fun AuroraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester = remember { FocusRequester() },
    startEditing: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(startEditing) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        onClick = { editing = true },
        modifier = modifier.focusRequester(focusRequester),
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Aurora.navyDeep.copy(alpha = 0.7f), contentColor = Aurora.white,
            focusedContainerColor = Aurora.panelHigh, focusedContentColor = Aurora.white,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (editing) 2.dp else 1.dp, if (editing) Aurora.cyan else Aurora.line), shape = ButtonShape),
            focusedBorder = Border(BorderStroke(2.dp, Aurora.cyan), shape = ButtonShape),
        ),
        glow = focusGlow,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon?.let { Icon(it, null, tint = Aurora.muted, modifier = Modifier.size(22.dp)) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Aurora.white),
                cursorBrush = SolidColor(Aurora.cyan),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = if (secret) KeyboardType.Password else keyboardType),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    keyboard?.hide()
                    runCatching { focusRequester.requestFocus() }
                    onSubmit()
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Aurora.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }
}

/** "AURORA TV  /  ФАЙЛЫ" breadcrumb and a big title, as on the 2.x screens. */
@Composable
fun ScreenHeader(section: String, title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Aurora.cyan, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("AURORA TV  /  ${section.uppercase()}", style = MaterialTheme.typography.labelSmall, color = Aurora.cyan)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Aurora.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Aurora.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Aurora.cyan.copy(alpha = 0.16f), content: Color = Aurora.cyan) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
