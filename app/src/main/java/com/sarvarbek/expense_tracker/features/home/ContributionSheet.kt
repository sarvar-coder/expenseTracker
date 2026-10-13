package com.sarvarbek.expense_tracker.features.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Contribution
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.SectionLabel
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppSheet
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Hissa qo'shish: amount + required note, dated now. Below, this month's own
 * contributions (delete own). Local first; sync pushes them. Opened from
 * Home's month line and the Oila budget card.
 */
@Composable
fun ContributionSheet(db: ExpenseDao, onDismiss: () -> Unit) = AppSheet(onDismiss) { _ ->
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Contribution?>(null) }
    val all by remember(db) { db.watchContributions() }.collectAsStateWithLifecycle(null)
    val start = LocalDate.now().withDayOfMonth(1)
    val month = all?.filter { it.date >= start.startMillis() && it.date < start.plusMonths(1).startMillis() }

    deleting?.let { d ->
        ConfirmDialog(t("contrib.delete_title"), "${d.note} · ${formatMoney(d.amount)} UZS", t("common.delete"), { deleting = null }) {
            deleting = null
            scope.launch { db.deleteContribution(d.id); toaster.show(t("contrib.deleted")) }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = AppSpace.section)) {
        Text(t("contrib.add"), style = ty.titleLarge, modifier = Modifier.semantics { heading() })
        Text(t("contrib.hint"), color = c.muted, style = ty.bodySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            amount, { v -> amount = v.filter { it.isDigit() } }, Modifier.fillMaxWidth().testTag("contrib_amount"),
            label = { Text(t("add.amount")) }, placeholder = { Text("400000") }, suffix = { Text("UZS") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = fieldShape, colors = fieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            note, { note = it.take(200) }, Modifier.fillMaxWidth().testTag("contrib_note"),
            label = { Text(t("contrib.note")) }, placeholder = { Text(t("contrib.note_hint")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = fieldShape, colors = fieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton({
            val a = parseAmount(amount)
            when {
                a == null || a <= 0 -> toaster.show(t("add.err_amount"))
                note.isBlank() -> toaster.show(t("contrib.err_note"))
                else -> scope.launch {
                    db.insertContribution(Contribution(amount = a, note = note.trim()))
                    toaster.show(t("contrib.added", formatMoney(a)))
                    amount = ""; note = ""
                }
            }
        }, Modifier.fillMaxWidth()) { Text(t("contrib.save")) }

        if (month != null) {
            SectionLabel(t("contrib.month_total", formatMoney(month.sumOf { it.amount })))
            if (month.isEmpty()) {
                Text(t("contrib.empty"), color = c.muted, style = ty.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                DividedCard(month, indent = 16.dp) { row ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(row.note, style = ty.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(uzDayMonth(row.date.toLocalDate()), color = c.muted, style = ty.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Money(row.amount)
                        IconButton({ deleting = row }) { Icon(Icons.Outlined.Delete, t("common.delete"), tint = c.muted) }
                    }
                }
            }
        }
    }
}
