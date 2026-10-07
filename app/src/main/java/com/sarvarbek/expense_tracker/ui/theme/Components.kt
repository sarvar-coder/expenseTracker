package com.sarvarbek.expense_tracker.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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

/** Primary action: accent fill, 56dp tall. */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = AppTheme.colors
    Button(
        onClick, modifier.defaultMinSize(minHeight = 56.dp), enabled, shape = md,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        content = { ProvideLabel { content() } },
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
        content = { ProvideLabel { content() } },
    )
}

/** Text action in accent, 48dp touch target. */
@Composable
fun LinkButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(
        onClick, modifier.defaultMinSize(48.dp, 48.dp), enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accent),
        content = { ProvideLabel { content() } },
    )
}

@Composable
private fun ProvideLabel(content: @Composable () -> Unit) =
    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = androidx.compose.ui.graphics.Color.Unspecified), content)

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
