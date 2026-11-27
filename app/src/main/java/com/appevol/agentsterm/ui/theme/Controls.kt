package com.appevol.agentsterm.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The app's controls, drawn the way the current theme wants them. Each one takes the same
 * arguments as the Material component it stands in for, so screens are written once: use these
 * instead of `Button`, `Switch` and friends, and a theme with its own `ControlStyle` restyles them.
 */

private val LunaButtonShape = RoundedCornerShape(3.dp)

/** How faded a disabled Start or caption button is. */
private const val DISABLED_ALPHA = 0.5f
private val LunaFieldShape = RoundedCornerShape(1.dp)

/** True when the current theme draws Windows XP controls. */
val isLunaControls: Boolean
    @Composable @ReadOnlyComposable
    get() = AppTheme.chrome.controls == ControlStyle.LUNA

/** A filled button. Set [isDefault] on the one a screen expects you to press, as XP dialogs do. */
@Composable
fun ThemedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDefault: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (isLunaControls) {
        LunaPushButton(onClick, modifier, enabled, isDefault, 40.dp, contentPadding, content)
    } else {
        Button(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding, content = content)
    }
}

@Composable
fun ThemedOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (isLunaControls) {
        LunaPushButton(onClick, modifier, enabled, isDefault = false, 40.dp, contentPadding, content)
    } else {
        OutlinedButton(
            onClick,
            modifier,
            enabled,
            shape,
            colors,
            border = border,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
fun ThemedTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDefault: Boolean = LocalIsDefaultButton.current,
    content: @Composable RowScope.() -> Unit,
) {
    if (isLunaControls) {
        // XP has no borderless buttons: the buttons of a dialog are ordinary push buttons.
        LunaPushButton(
            onClick = onClick,
            modifier = modifier.padding(horizontal = 3.dp, vertical = 4.dp),
            enabled = enabled,
            isDefault = isDefault,
            minHeight = 34.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            content = content,
        )
    } else {
        TextButton(onClick, modifier, enabled, content = content)
    }
}

/** A chip that is one of a set of choices. In the XP look it is a small button that stays down. */
@Composable
fun ThemedFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    if (!isLunaControls) {
        FilterChip(selected, onClick, label, modifier, leadingIcon = leadingIcon)
        return
    }
    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .defaultMinSize(minHeight = 34.dp)
            .clip(LunaButtonShape)
            .then(if (selected) Modifier.background(LunaSelectionSoft) else Modifier.background(LunaButtonFaceBands))
            .border(1.dp, if (selected) LunaSelection else LunaButtonBorder, LunaButtonShape)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) LunaNavy else LunaInk) {
            val weight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = weight)) {
                leadingIcon?.invoke()
                label()
            }
        }
    }
}

/** The app's main call to action, "New tab". In the XP look it is the green Start button. */
@Composable
fun PrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isLunaControls) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = themedCorner(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppTheme.chrome.primaryAction,
                contentColor = AppTheme.chrome.onPrimaryAction,
            ),
            content = content,
        )
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Square on the left, rounded on the right, like the button at the end of the taskbar.
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 12.dp, bottomEnd = 12.dp)
    val style = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        shadow = Shadow(color = LunaStartTextShadow, offset = Offset(2f, 2f), blurRadius = 2f),
    )
    Row(
        modifier = modifier
            .clip(shape)
            .background(LunaStartBands)
            .drawBehind { if (pressed) drawRect(Color.Black.copy(alpha = 0.18f)) }
            .border(1.dp, LunaStartBorder, shape)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            ProvideTextStyle(style) { content() }
        }
    }
}

@Composable
private fun LunaPushButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    isDefault: Boolean,
    minHeight: Dp,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = minHeight)
            .clip(LunaButtonShape)
            .drawBehind {
                when {
                    !enabled -> drawRect(LunaButtonFaceDisabled)
                    pressed -> LunaButtonPressedBands.draw(this)
                    else -> LunaButtonFaceBands.draw(this)
                }
                if (enabled && isDefault && !pressed) drawDefaultRing()
            }
            .border(1.dp, if (enabled) LunaButtonBorder else LunaButtonBorderDisabled, LunaButtonShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) LunaInk else LunaInkDisabled) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)) { content() }
        }
    }
}

/** The pale blue ring just inside the border of a dialog's default button. */
private fun DrawScope.drawDefaultRing() {
    val width = 2.dp.toPx()
    val inset = 1.dp.toPx() + width / 2
    drawRoundRect(
        brush = Brush.verticalGradient(LunaDefaultRingColors),
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset),
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(width),
    )
}

/** An on/off setting. XP has no switches, so there it is a check box. */
@Composable
fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!isLunaControls) {
        Switch(checked, onCheckedChange, modifier, enabled = enabled)
        return
    }
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(modifier = modifier.then(toggle).size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 1.dp.toPx()
            if (enabled) {
                drawRect(Brush.linearGradient(LunaCheckFaceColors))
            } else {
                drawRect(Color.White)
            }
            drawRect(
                color = if (enabled) LunaCheckBorder else LunaCheckBorderDisabled,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke),
            )
            if (checked) {
                val mark = Path().apply {
                    moveTo(size.width * 0.24f, size.height * 0.50f)
                    lineTo(size.width * 0.43f, size.height * 0.70f)
                    lineTo(size.width * 0.78f, size.height * 0.28f)
                }
                drawPath(
                    path = mark,
                    color = if (enabled) LunaCheckMark else LunaCheckBorderDisabled,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter),
                )
            }
        }
    }
}

/** Shows whether an option is the chosen one. The row around it is expected to take the tap. */
@Composable
fun ThemedRadioIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    if (!isLunaControls) {
        RadioButton(selected = selected, onClick = null, modifier = modifier)
        return
    }
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.dp.toPx()
        drawCircle(Brush.linearGradient(LunaCheckFaceColors))
        drawCircle(LunaCheckBorder, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (selected) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7BE47D), LunaCheckMark),
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    radius = size.minDimension * 0.3f,
                ),
                radius = size.minDimension * 0.24f,
            )
        }
    }
}

/** An icon button on a title bar. In the XP look it is a caption button, like minimize and maximize. */
@Composable
fun TitleBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!isLunaControls) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(shape)
                .background(Brush.linearGradient(LunaCaptionButtonColors))
                .drawBehind { if (pressed) drawRect(Color.Black.copy(alpha = 0.25f)) }
                .border(1.dp, Color.White.copy(alpha = 0.9f), shape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** [base] as the title on a title bar: XP sets it in bold with a dark drop shadow. */
@Composable
@ReadOnlyComposable
fun titleBarTitleStyle(base: TextStyle): TextStyle {
    val shadow = AppTheme.chrome.titleBar.titleShadow ?: return base.copy(fontWeight = FontWeight.SemiBold)
    return base.copy(fontWeight = FontWeight.Bold, shadow = shadow)
}

/** The color of a section or group heading. XP writes the caption of a group box in blue. */
val sectionTitleColor: Color
    @Composable @ReadOnlyComposable
    get() = if (isLunaControls) LunaGroupTitle else MaterialTheme.colorScheme.onSurface

/**
 * A text box. In the XP look it is a white well with a thin steel-blue edge, and its label stays
 * inside the box instead of sitting in a notch on the border.
 */
@Composable
fun ThemedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
) {
    if (!isLunaControls) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            shape = shape,
        )
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = when {
        isError -> MaterialTheme.colorScheme.error
        !enabled -> LunaButtonBorderDisabled
        focused -> LunaBlue
        else -> LunaInputBorder
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.border(1.dp, edge, LunaFieldShape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interaction,
        shape = LunaFieldShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            errorContainerColor = Color.White,
            disabledContainerColor = LunaButtonFaceDisabled,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            unfocusedLabelColor = LunaInkMuted,
            disabledTextColor = LunaInkDisabled,
        ),
    )
}

/** Overload of [ThemedTextField] supporting [TextFieldValue] for explicit cursor/selection control. */
@Composable
fun ThemedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
) {
    if (!isLunaControls) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            shape = shape,
        )
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = when {
        isError -> MaterialTheme.colorScheme.error
        !enabled -> LunaButtonBorderDisabled
        focused -> LunaBlue
        else -> LunaInputBorder
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.border(1.dp, edge, LunaFieldShape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interaction,
        shape = LunaFieldShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            errorContainerColor = Color.White,
            disabledContainerColor = LunaButtonFaceDisabled,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            unfocusedLabelColor = LunaInkMuted,
            disabledTextColor = LunaInkDisabled,
        ),
    )
}

private val LunaTabShape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)

/** Draws an XP tab behind the content. The selected tab is paler and wears an orange cap. */
fun Modifier.lunaTab(selected: Boolean): Modifier = this
    .clip(LunaTabShape)
    .then(if (selected) Modifier.background(LunaTabPage) else Modifier.background(LunaTabFaceBands))
    .border(1.dp, if (selected) LunaTabPageBorder else LunaTabBorder, LunaTabShape)
    .drawWithContent {
        drawContent()
        if (selected) {
            drawRect(LunaTabCap, size = Size(size.width, 3.dp.toPx()))
            drawRect(LunaTabCapEdge, size = Size(size.width, 1.dp.toPx()))
        }
    }

/** The line a row of XP tabs stands on. */
fun Modifier.lunaTabBaseline(): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRect(LunaTabPageBorder, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
}

/**
 * XP's "busy" progress bar: a short train of green blocks crossing a white track.
 * [phase] runs from 0 to 1 for one crossing.
 */
@Composable
fun LunaMarquee(phase: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Canvas(
        modifier
            .size(width = 46.dp, height = 11.dp)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, LunaProgressBorder, shape),
    ) {
        val inset = 2.dp.toPx()
        val block = 5.dp.toPx()
        val gap = 1.5.dp.toPx()
        val train = 3 * block + 2 * gap
        // Offset so phase 0 (used when system animations are disabled) centers the blocks inside
        // the track instead of hiding them off the left edge.
        val shiftedPhase = (phase + 0.45f) % 1f
        val start = -train + (size.width + train) * shiftedPhase
        for (i in 0..2) {
            val left = start + i * (block + gap)
            val from = left.coerceAtLeast(inset)
            val to = (left + block).coerceAtMost(size.width - inset)
            if (to > from) LunaProgressBands.draw(this, Offset(from, inset), Size(to - from, size.height - 2 * inset))
        }
    }
}

/**
 * A backdrop in the spirit of XP's default wallpaper: a deep blue sky with a few clouds over a
 * rolling green hill. Drawn rather than bundled, so it scales to any size.
 */
@Composable
fun LunaHillBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(LunaSkyColors))
        for ((cx, cy, r) in LunaClouds) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)),
                    center = Offset(w * cx, h * cy),
                    radius = h * r,
                ),
                radius = h * r,
                center = Offset(w * cx, h * cy),
            )
        }
        val farHill = Path().apply {
            moveTo(0f, h * 0.78f)
            cubicTo(w * 0.25f, h * 0.60f, w * 0.55f, h * 0.66f, w, h * 0.86f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(farHill, Brush.verticalGradient(LunaFarHillColors, startY = h * 0.6f, endY = h))
        val nearHill = Path().apply {
            moveTo(0f, h * 0.96f)
            cubicTo(w * 0.35f, h * 0.86f, w * 0.65f, h * 0.62f, w, h * 0.58f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(nearHill, Brush.verticalGradient(LunaNearHillColors, startY = h * 0.58f, endY = h))
    }
}
