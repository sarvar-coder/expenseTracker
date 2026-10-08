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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.mergeFeed
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.ExpenseTile
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Today: my total (plus the family's, in a family), slim personal month-budget
 * line, and today's rows from the family [feed]: mine plus other members'
 * shared ones, newest first. Not in a family the feed is just mine.
 * [offline]: others' rows are as last fetched. [inFamily]: show the family line.
 */
@Composable
fun HomeScreen(
    db: ExpenseDao,
    settings: SettingsStore,
    feed: Flow<List<FamilyExpense>> = remember(db) { db.watchExpenses().map { mergeFeed(it, emptyList(), t("family.you")) } },
    offline: Boolean = false,
    inFamily: Boolean = false,
    onSettings: () -> Unit,
    onEdit: (Expense) -> Unit,
) {
    val rows by feed.collectAsStateWithLifecycle(null) // null = loading
    val categories by remember(db) { db.watchCategories() }.collectAsStateWithLifecycle(emptyList())
    val budget by settings.settings.collectAsStateWithLifecycle()
    val now = LocalDate.now()
    val today = remember(rows, now) { todayExpenses(rows.orEmpty(), now) }
    // The budget line stays personal.
    val month = remember(rows, budget.monthlyBudget, now) { summarize(rows.orEmpty().filter { it.mine }.map { it.expense }, budget.monthlyBudget, now) }
    // Others' rows also count: the family pref can lag a join until the next recomposition.
    val family = inFamily || today.any { !it.mine }
    val catById = categories.associateBy { it.id }

    // Bottom padding clears the floating add button.
    LazyColumn(contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp)) {
        item { Header(now, onSettings) }
        item { Spacer(Modifier.height(AppSpace.gap)) }
        // Transfers stay in the list but aren't "spent".
        item {
            val spent = today.filter { it.expense.transferTo == null }
            val mine = spent.filter { it.mine }
            TodayHero(mine.sumOf { it.expense.amount }, mine.size, month, spent.takeIf { family }?.sumOf { it.expense.amount })
        }
        item {
            Spacer(Modifier.height(AppSpace.section))
            Text(t("home.today_expenses"), style = MaterialTheme.typography.titleMedium)
            if (offline) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WifiOff, null, tint = AppTheme.colors.muted)
                    Spacer(Modifier.width(8.dp))
                    Text(t("insights.offline"), color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        item {
            when {
                rows == null -> Unit // loading: Room answers in a frame, no spinner flash
                today.isEmpty() -> EmptyState(Icons.Outlined.ReceiptLong, t("home.empty_title"), t("home.empty_body"))
                else -> AppCard {
                    for (fe in today) key(fe.expense.id) { ExpenseTile(fe, catById[fe.expense.categoryId], db, onEdit) }
                }
            }
        }
    }
}

@Composable
private fun Header(date: LocalDate, onSettings: () -> Unit) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    Row {
        Column(Modifier.weight(1f)) {
            Text(uzDayMonth(date), color = AppTheme.colors.muted, style = ty.labelMedium)
            Text(t("home.today"), style = ty.headlineMedium)
        }
        OutlinedIconButton(
            onSettings,
            Modifier.size(48.dp),
            shape = RoundedCornerShape(AppRadii.md),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = c.card, contentColor = c.text),
            border = BorderStroke(1.dp, c.border),
        ) { Icon(Icons.Outlined.Settings, t("home.settings")) }
    }
}

@Composable
/** [familyTotal]: today's family spend (mine included), null when not in a family. */
private fun TodayHero(total: Long, count: Int, month: HomeSummary, familyTotal: Long?) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val soft = c.onHero.copy(alpha = 0.72f)
    val over = month.budget > 0 && month.spent > month.budget
    Column(
        Modifier.fillMaxWidth().background(c.hero, RoundedCornerShape(AppRadii.hero)).padding(22.dp, 22.dp, 22.dp, 18.dp),
    ) {
        val label = when {
            familyTotal != null -> t("home.mine_today", count)
            count == 0 -> t("home.spent_today")
            else -> t("home.spent_today_count", count)
        }
        Text(label, style = ty.labelMedium.copy(color = soft))
        Spacer(Modifier.height(6.dp))
        Money(total, style = ty.displayLarge, color = c.onHero, autoSize = true)
        if (familyTotal != null) {
            Spacer(Modifier.height(4.dp))
            Text(t("home.family_today", formatMoney(familyTotal)), style = ty.labelMedium.copy(color = c.onHero))
        }
        Spacer(Modifier.height(20.dp))
        if (month.budget <= 0) {
            Text(t("home.month_no_budget", formatMoney(month.spent)), style = ty.labelSmall.copy(color = soft))
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
                Text(t("home.month_of_budget", formatMoney(month.spent), formatMoney(month.budget)), style = ty.labelSmall.copy(color = soft), modifier = Modifier.weight(1f))
                Text(
                    if (over) t("home.over", formatMoney(-month.remaining)) else t("home.left", formatMoney(month.remaining)),
                    style = ty.labelSmall.copy(color = c.onHero, fontWeight = if (over) FontWeight.ExtraBold else null),
                )
            }
        }
    }
}
