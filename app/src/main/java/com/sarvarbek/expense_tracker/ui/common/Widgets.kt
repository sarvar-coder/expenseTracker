package com.sarvarbek.expense_tracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** 6-digit hex (no '#') -> opaque color, e.g. "E08A5B". */
fun colorFromHex(hex: String) = Color(0xFF000000 or hex.toLong(16))

/**
 * App-wide snackbar. Lives above the nav graph so a message (with Undo)
 * outlives the screen that showed it.
 */
class Toaster(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(msg: String, action: String? = null, onAction: suspend () -> Unit = {}) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            if (host.showSnackbar(msg, action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("LocalToaster not provided") }

/**
 * Amount with a smaller "UZS" after it. [style] sets the number; the unit
 * takes 45% of its size (min 11sp). [autoSize] shrinks to fit one line.
 */
@Composable
fun Money(amount: Long, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleSmall, color: Color = Color.Unspecified, autoSize: Boolean = false) {
    val base = style.merge(TextStyle(color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }))
    val text = buildAnnotatedString {
        append(formatMoney(amount))
        // em = relative to the (possibly auto-sized) number.
        val unit = if (autoSize) 0.45.em else maxOf(11f, base.fontSize.value * 0.45f).sp
        withStyle(SpanStyle(fontSize = unit, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)) { append(" UZS") }
    }
    BasicText(
        text, modifier, base, maxLines = 1, softWrap = false,
        autoSize = if (autoSize) TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = base.fontSize) else null,
    )
}

/** Rounded square tinted with the category color, holding its initial. Decorative: the name sits next to it. */
@Composable
fun CategoryBadge(category: Category?, size: Dp = 44.dp) {
    val c = AppTheme.colors
    val color = category?.let { colorFromHex(it.colorHex) } ?: c.muted
    val name = category?.name?.trim().orEmpty()
    Box(
        Modifier.size(size).background(color.copy(alpha = 0.22f), RoundedCornerShape(size * 0.32f)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (name.isEmpty()) "?" else name.take(1).uppercase(),
            fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.ExtraBold, color = c.text,
        )
    }
}

/** [CategoryBadge]'s shape for an O'tkazma row. Decorative: the row says "O'tkazma". */
@Composable
private fun TransferBadge(size: Dp = 44.dp) {
    val c = AppTheme.colors
    Box(
        Modifier.size(size).background(c.muted.copy(alpha = 0.22f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.SwapHoriz, null, tint = c.text, modifier = Modifier.size(size * 0.5f)) }
}

/** Empty screen/section: says what's missing and what to do next. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(c.accent.copy(alpha = 0.12f), RoundedCornerShape(AppRadii.card)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = t.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = AppTheme.colors.muted, style = t.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

/** "Delete this expense?" confirm. Shared by swipe-delete and the edit screen. */
@Composable
fun ConfirmDeleteDialog(description: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Xarajat o'chirilsinmi?") },
    text = { Text("“$description” o'chiriladi.") },
    dismissButton = { LinkButton(onDismiss) { Text("Bekor qilish") } },
    confirmButton = { LinkButton(onConfirm) { Text("O'chirish") } },
)

/** Source glyph + label shown under the description. */
fun sourceMeta(s: ExpenseSource): Pair<String, ImageVector> = when (s) {
    ExpenseSource.typed -> "Yozilgan" to Icons.Outlined.Edit
    ExpenseSource.voice -> "Ovozli" to Icons.Outlined.MicNone
    ExpenseSource.manual -> "Qo'lda" to Icons.AutoMirrored.Outlined.ListAlt
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One expense row. Tap opens edit; swipe left deletes after confirm.
 * Frozen rows (shared history of a family you left) are read-only.
 * Used by Home (today) and Tarix.
 */
@Composable
fun ExpenseTile(expense: Expense, category: Category?, db: ExpenseDao, onEdit: (Expense) -> Unit) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    if (confirm) {
        ConfirmDeleteDialog(
            expense.description,
            onConfirm = {
                confirm = false
                scope.launch {
                    db.deleteExpense(expense.id)
                    toaster.show("Xarajat o'chirildi")
                }
            },
            onDismiss = { confirm = false },
        )
    }
    SwipeToDismissBox(
        swipe,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(c.danger).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onError)
            }
        },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !expense.frozen,
        // Row snaps back; it disappears from the list once the delete lands.
        onDismiss = { confirm = true; scope.launch { swipe.reset() } },
    ) {
        val (srcLabel, srcIcon) = sourceMeta(expense.source)
        Row(
            Modifier
                .background(c.card)
                .clickable(enabled = !expense.frozen) { onEdit(expense) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val transfer = expense.transferTo != null
            if (transfer) TransferBadge() else CategoryBadge(category)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(expense.description, style = t.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (transfer) "O'tkazma" else category?.name ?: "Turkumsiz", color = AppTheme.colors.muted, style = t.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    Icon(srcIcon, srcLabel, tint = c.muted, modifier = Modifier.size(13.dp))
                    if (expense.isPrivate) Icon(Icons.Outlined.VisibilityOff, "Maxfiy", tint = c.muted, modifier = Modifier.size(13.dp))
                    if (expense.frozen) Icon(Icons.Outlined.Lock, "Faqat o'qish uchun", tint = c.muted, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatMoney(expense.amount), style = t.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(expense.date.toLocalDateTime().format(hhmm), color = AppTheme.colors.muted, style = t.bodySmall)
            }
        }
    }
}

/** Yes/no confirm with a named action ("Chiqish", "O'chirish"…). */
@Composable
fun ConfirmDialog(title: String, body: String?, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = body?.let { { Text(it) } },
    dismissButton = { LinkButton(onDismiss) { Text("Bekor qilish") } },
    confirmButton = { LinkButton(onConfirm) { Text(action) } },
)

/** One-field dialog. [accept] rejects edits (e.g. digits only). [onDone] gets the trimmed text (may be empty). */
@Composable
fun TextDialog(
    title: String,
    action: String,
    onDismiss: () -> Unit,
    initial: String = "",
    hint: String? = null,
    suffix: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    accept: (String) -> Boolean = { true },
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { if (accept(it.text)) text = it }, Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = hint?.let { { Text(it) } }, suffix = suffix?.let { { Text(it) } }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None, keyboardType = keyboard),
                shape = fieldShape, colors = fieldColors(),
            )
        },
        dismissButton = { LinkButton(onDismiss) { Text("Bekor qilish") } },
        confirmButton = { LinkButton({ onDone(text.text.trim()) }) { Text(action) } },
    )
    DisposableEffect(Unit) { runCatching { focus.requestFocus() }; onDispose {} }
}

/** Muted label above a card group; optional count badge. */
@Composable
fun SectionLabel(text: String, top: Dp = AppSpace.section, badge: Int = 0) = Row(
    Modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(text, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
    if (badge > 0) {
        Spacer(Modifier.width(8.dp))
        Badge(containerColor = AppTheme.colors.danger) { Text("$badge") }
    }
}

/** Card of [row]s split by hairlines starting at [indent]. */
@Composable
fun <T> DividedCard(items: List<T>, indent: Dp = 72.dp, row: @Composable (T) -> Unit) = AppCard(Modifier.fillMaxWidth()) {
    items.forEachIndexed { i, item ->
        if (i > 0) HorizontalDivider(Modifier.padding(start = indent), color = AppTheme.colors.border)
        row(item)
    }
}
