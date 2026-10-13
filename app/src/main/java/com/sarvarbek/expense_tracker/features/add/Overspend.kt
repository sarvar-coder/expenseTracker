@file:OptIn(ExperimentalLayoutApi::class)

package com.sarvarbek.expense_tracker.features.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.data.Contribution
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.features.home.myAvailable
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppSheet
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.SecondaryButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.CompletableDeferred
import java.time.YearMonth

/** Money still to explain when spending [extra] with [available] left: the part not covered (overspent months count from 0). */
fun shortfall(extra: Long, available: Long): Long = (extra - available.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Shortfall for saving [e] (replacing [old] when editing), against the
 * available of [e]'s own month. An edit in the same month only adds its increase;
 * moved to another month, the whole amount is new there.
 */
suspend fun shortfallFor(db: ExpenseDao, settings: SettingsStore, e: Expense, old: Expense?): Long {
    val day = e.date.toLocalDate()
    // ponytail: received transfers are cached for the current month only, so other months count them as 0
    // (may ask needlessly); keep received per month if that bites.
    val kept = old?.takeIf { YearMonth.from(it.date.toLocalDate()) == YearMonth.from(day) }?.amount ?: 0
    return shortfall(e.amount - kept, myAvailable(db, settings, day))
}

private class Ask(val shortfall: Long, val note: CompletableDeferred<String?> = CompletableDeferred())

/**
 * The one expense write path. Within available: saved at once. Past it: asks
 * "Pul qayerdan?" and saves the shortfall as a contribution with the note,
 * together with the expense. Returns an undo (for new rows), or null when cancelled.
 */
@Composable
fun rememberExpenseSaver(db: ExpenseDao, settings: SettingsStore): suspend (e: Expense, old: Expense?) -> (suspend () -> Unit)? {
    var ask by remember { mutableStateOf<Ask?>(null) }
    ask?.let { a -> SourceSheet(a.shortfall, onDone = { a.note.complete(it) }) }
    return remember(db, settings) {
        save@{ e, old ->
            val s = shortfallFor(db, settings, e, old)
            val source = if (s == 0L) null else {
                val a = Ask(s).also { ask = it }
                val note = try { a.note.await() } finally { ask = null }
                Contribution(amount = s, note = note ?: return@save null, date = e.date)
            }
            db.saveExpense(e, update = old != null, source)
            ({ db.deleteExpenseWithSource(e.id, source?.id) })
        }
    }
}

/** "Pul qayerdan?": shortfall + required note (quick chips or free text). [onDone] null = cancel. */
@Composable
private fun SourceSheet(shortfall: Long, onDone: (String?) -> Unit) = AppSheet({ onDone(null) }) { hide ->
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    var note by rememberSaveable { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = AppSpace.section)) {
        Text(t("overspend.title"), style = ty.titleLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Text(t("overspend.body", formatMoney(shortfall)), color = c.muted, style = ty.bodyMedium)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in listOf("overspend.own", "overspend.loan", "overspend.salary")) {
                val label = t(k)
                AppChip(label, note == label, { note = label })
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            note, { note = it.take(200) }, Modifier.fillMaxWidth().testTag("source_note"),
            label = { Text(t("contrib.note")) }, supportingText = { Text(t("contrib.err_note")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = fieldShape, colors = fieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        // Disabled until a note: a toast would sit behind the sheet.
        PrimaryButton({ hide { onDone(note.trim()) } }, Modifier.fillMaxWidth().testTag("source_confirm"), enabled = note.isNotBlank()) {
            Text(t("overspend.confirm"))
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton({ hide { onDone(null) } }, Modifier.fillMaxWidth()) { Text(t("common.cancel")) }
    }
}
