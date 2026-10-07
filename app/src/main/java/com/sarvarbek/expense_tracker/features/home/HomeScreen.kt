package com.sarvarbek.expense_tracker.features.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.ExpenseTile
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import java.time.LocalDate

/** Today: big total, slim month-budget line, today's expenses. */
@Composable
fun HomeScreen(db: ExpenseDao, settings: SettingsStore, onSettings: () -> Unit, onEdit: (Expense) -> Unit) {
    val expenses by remember(db) { db.watchExpenses() }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(db) { db.watchCategories() }.collectAsStateWithLifecycle(emptyList())
    val budget by settings.settings.collectAsStateWithLifecycle()
    val now = LocalDate.now()
    val today = todayExpenses(expenses, now)
    val month = summarize(expenses, budget.monthlyBudget, now)
    val catById = categories.associateBy { it.id }

    // Bottom padding clears the floating add button.
    LazyColumn(contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp)) {
        item { Header(now, onSettings) }
        item { Spacer(Modifier.height(AppSpace.gap)) }
        item { TodayHero(today.sumOf { it.amount }, today.size, month) }
        item {
            Spacer(Modifier.height(AppSpace.section))
            Text("Bugungi xarajatlar", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
        }
        item {
            if (today.isEmpty()) {
                EmptyState(Icons.Outlined.ReceiptLong, "Bugun hali xarajat yo'q", "Pastdagi tugma bilan birinchisini qo'shing")
            } else {
                AppCard {
                    for (e in today) ExpenseTile(e, catById[e.categoryId], db, onEdit)
                }
            }
        }
    }
}

@Composable
private fun Header(date: LocalDate, onSettings: () -> Unit) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    Row {
        Column(Modifier.weight(1f)) {
            Text(uzDayMonth(date), style = t.labelMedium)
            Text("Bugun", style = t.headlineMedium)
        }
        OutlinedIconButton(
            onSettings,
            Modifier.size(48.dp),
            shape = RoundedCornerShape(AppRadii.md),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = c.card, contentColor = c.text),
            border = BorderStroke(1.dp, c.border),
        ) { Icon(Icons.Outlined.Settings, "Sozlamalar") }
    }
}

@Composable
private fun TodayHero(total: Long, count: Int, month: HomeSummary) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    val soft = c.onHero.copy(alpha = 0.72f)
    val over = month.budget > 0 && month.spent > month.budget
    Column(
        Modifier.fillMaxWidth().background(c.hero, RoundedCornerShape(AppRadii.hero)).padding(22.dp, 22.dp, 22.dp, 18.dp),
    ) {
        Text(if (count == 0) "Bugun sarflangan" else "Bugun sarflangan · $count ta", style = t.labelMedium.copy(color = soft))
        Spacer(Modifier.height(6.dp))
        Money(total, style = t.displayLarge, color = c.onHero, autoSize = true)
        Spacer(Modifier.height(20.dp))
        if (month.budget <= 0) {
            Text("Oy: ${formatMoney(month.spent)} UZS · Sozlamalarda byudjet belgilang", style = t.labelSmall.copy(color = soft))
        } else {
            LinearProgressIndicator(
                progress = { month.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(AppRadii.chip)),
                color = if (over) c.danger else c.accent,
                trackColor = c.onHero.copy(alpha = 0.15f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text("Oy: ${formatMoney(month.spent)} / ${formatMoney(month.budget)}", style = t.labelSmall.copy(color = soft), modifier = Modifier.weight(1f))
                Text(
                    if (over) "${formatMoney(-month.remaining)} oshdi" else "${formatMoney(month.remaining)} qoldi",
                    style = t.labelSmall.copy(color = c.onHero, fontWeight = if (over) FontWeight.ExtraBold else null),
                )
            }
        }
    }
}
