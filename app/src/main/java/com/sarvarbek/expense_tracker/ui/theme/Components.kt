package com.sarvarbek.expense_tracker.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Component styles ported from buildTheme() in lib/app/theme.dart. Compose has no
// global component themes, so screens use these instead of the raw M3 widgets.

private val md = RoundedCornerShape(AppRadii.md)

/**
 * Page motion (M3 emphasized easing, Android 14 feel). Push: new page slides in
 * from the right, old one parallaxes a quarter left and dims. Pop is the reverse,
 * a bit faster. Compose scales these with the system animator setting.
 */
object AppMotion {
    private val decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    private const val PUSH = 350
    private const val POP = 300

    val pushEnter: EnterTransition = slideInHorizontally(tween(PUSH, easing = decelerate)) { it }
    val pushExit: ExitTransition = slideOutHorizontally(tween(PUSH, easing = decelerate)) { -it / 4 } +
        fadeOut(tween(PUSH, easing = decelerate), targetAlpha = 0.6f)
    val popEnter: EnterTransition = slideInHorizontally(tween(POP, easing = decelerate)) { -it / 4 } +
        fadeIn(tween(POP, easing = decelerate), initialAlpha = 0.6f)
    val popExit: ExitTransition = slideOutHorizontally(tween(POP, easing = accelerate)) { it }
}

/**
 * Bottom sheet that opens straight to its content height: no half-height
 * anchor, so dragging up never snaps/jumps. [content] gets `hide(then)`, which
 * slides the sheet down before running `then` (use it for picks).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(hide: (() -> Unit) -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hide: (() -> Unit) -> Unit = { then -> scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) then() } }
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = AppTheme.colors.card) { content(hide) }
}

/** Primary action: accent fill, 56dp tall. */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = AppTheme.colors
    Button(
        onClick, modifier.defaultMinSize(minHeight = 56.dp), enabled, shape = md,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        content = content,
    )
}

/** Secondary action: 1.5dp border, 56dp tall. */
@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = AppTheme.colors
    OutlinedButton(
        onClick, modifier.defaultMinSize(minHeight = 56.dp), enabled, shape = md,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
        border = BorderStroke(1.5.dp, c.border),
        content = content,
    )
}

/** Text action in accent, 48dp touch target. */
@Composable
fun LinkButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(
        onClick, modifier.defaultMinSize(48.dp, 48.dp), enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accent),
        content = content,
    )
}

/** Content card: flat; dark mode adds a hairline so cards don't melt into bg. */
@Composable
fun AppCard(modifier: Modifier = Modifier, dark: Boolean = isSystemInDarkTheme(), content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    Card(
        modifier,
        shape = RoundedCornerShape(AppRadii.card),
        colors = CardDefaults.cardColors(containerColor = c.card, contentColor = c.text),
        elevation = CardDefaults.cardElevation(0.dp),
        border = if (dark) BorderStroke(1.dp, c.border) else null,
        content = content,
    )
}

/** Input fields: card fill, border, 2dp accent when focused. Use with OutlinedTextField + [fieldShape]. */
@Composable
fun fieldColors(): TextFieldColors {
    val c = AppTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.card,
        unfocusedContainerColor = c.card,
        disabledContainerColor = c.card,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.border,
        focusedLabelColor = c.muted,
        unfocusedLabelColor = c.muted,
        focusedPlaceholderColor = c.muted,
        unfocusedPlaceholderColor = c.muted,
        cursorColor = c.accent,
        errorBorderColor = c.danger,
    )
}

val fieldShape = md

/** Pill filter chip: accent fill when selected, no checkmark. */
@Composable
fun AppChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    FilterChip(
        selected, onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)) },
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(AppRadii.chip),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.card, labelColor = c.text,
            selectedContainerColor = c.accent, selectedLabelColor = c.onAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = c.border, selectedBorderColor = c.accent),
    )
}

/** Floating snackbar on hero color. Pass to SnackbarHost(snackbar = { AppSnackbar(it) }). */
@Composable
fun AppSnackbar(data: SnackbarData) {
    val c = AppTheme.colors
    Snackbar(data, shape = md, containerColor = c.hero, contentColor = c.onHero, actionColor = c.onHero)
}
