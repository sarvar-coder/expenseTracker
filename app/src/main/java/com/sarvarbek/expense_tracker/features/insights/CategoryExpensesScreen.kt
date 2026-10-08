package com.sarvarbek.expense_tracker.features.insights

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.features.activity.DaySection
import com.sarvarbek.expense_tracker.features.activity.groupExpenses
import com.sarvarbek.expense_tracker.features.settings.AppBarScaffold
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.ui.common.CategoryBadge
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.ExpenseTile
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.monthName
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * A category tap on Tahlil or Oila: the category, the period's label, and
 * [select], which narrows the feed to what that screen showed (period +
 * filters), so the list stays live and its total matches the legend.
 */
class CategoryPick(val categoryId: String, val period: String, val select: (List<FamilyExpense>) -> List<FamilyExpense>)

/** "Oktyabr 2026" for [month]'s month, or the filter's Sana range. */
fun periodLabel(range: ClosedRange<LocalDate>?, month: LocalDate): String = when {
    range == null -> "${monthName(month.monthValue)} ${month.year}"
    range.start == range.endInclusive -> uzDayMonth(range.start)
    else -> "${uzDayMonth(range.start)} – ${uzDayMonth(range.endInclusive)}"
}

/**
 * One category's expenses (transfers left out, like the legend), newest
 * first in day groups. [offline]: others' rows are as last fetched.
 */
@Composable
fun CategoryExpensesScreen(db: ExpenseDao, feed: Flow<List<FamilyExpense>>, pick: CategoryPick, offline: Boolean = false, onBack: () -> Unit, onEdit: (Expense) -> Unit) {
    val ty = MaterialTheme.typography
    val all by feed.collectAsStateWithLifecycle(null) // null = loading
    val cats by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(emptyList())
    val catById = cats.associateBy { it.id }
    val category = catById[pick.categoryId]
    val rows = all?.let { list -> pick.select(list).filter { it.expense.categoryId == pick.categoryId && it.expense.transferTo == null } }
    val total = rows.orEmpty().sumOf { it.expense.amount }

    AppBarScaffold(category?.name.orEmpty(), onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section)) {
            item {
                Row(
                    Modifier.clearAndSetSemantics { contentDescription = "${pick.period}, ${t("insights.total_a11y", formatMoney(total))}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryBadge(category)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(pick.period, color = AppTheme.colors.muted, style = ty.labelMedium)
                        Money(total, style = ty.headlineMedium, autoSize = true)
                    }
                }
                if (offline) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WifiOff, null, tint = AppTheme.colors.muted)
                        Spacer(Modifier.width(8.dp))
                        Text(t("insights.offline"), color = AppTheme.colors.muted, style = ty.bodySmall)
                    }
                }
            }
            when {
                rows == null -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.accent) }
                }
                rows.isEmpty() -> item { EmptyState(Icons.Outlined.ReceiptLong, t("insights.category_empty")) }
                else -> daySections(groupExpenses(rows.map { it.expense }, today = LocalDate.now()), rows.associateBy { it.expense.id }, catById, db, onEdit)
            }
        }
    }
}

/** Day headers with the day's total (transfers left out), then the rows in a card. Tahlil list, Tarix and this screen. */
internal fun LazyListScope.daySections(
    sections: List<DaySection>,
    rows: Map<String, FamilyExpense>,
    catById: Map<String, Category>,
    db: ExpenseDao,
    onEdit: (Expense) -> Unit,
) = items(sections, key = { it.label }) { section ->
    val ty = MaterialTheme.typography
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp)) {
        Text(section.label, style = ty.titleMedium, modifier = Modifier.weight(1f))
        Text(formatMoney(section.items.filter { it.transferTo == null }.sumOf { it.amount }), color = AppTheme.colors.muted, style = ty.labelMedium)
    }
    AppCard(Modifier.fillMaxWidth()) {
        for (e in section.items) rows[e.id]?.let { ExpenseTile(it, catById[e.categoryId], db, onEdit) }
    }
}
