package com.sarvarbek.expense_tracker.features.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.features.activity.ActiveFilters
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.features.activity.groupExpenses
import com.sarvarbek.expense_tracker.features.insights.OfflineNote
import com.sarvarbek.expense_tracker.features.insights.daySections
import com.sarvarbek.expense_tracker.features.insights.periodLabel
import com.sarvarbek.expense_tracker.features.settings.AppBarScaffold
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import java.time.LocalDate

/**
 * Oila's "Ro'yxat": the tab's shared expenses (same rows and [filterState]
 * as the tab), newest first in day groups. [offline]: others' rows are as last fetched.
 */
@Composable
fun FamilyListScreen(
    state: FamilyState,
    db: ExpenseDao,
    filterState: MutableState<ActivityFilter>,
    offline: Boolean = false,
    onBack: () -> Unit,
    onEdit: (Expense) -> Unit,
) {
    val ty = MaterialTheme.typography
    var filter by filterState
    val f = state.overview
    // Rotation drops the overview (FamilyState isn't saved): fetch it again; no family any more: back.
    LaunchedEffect(f == null) { if (f == null) state.refresh() }
    if (state.loaded && f == null) LaunchedEffect(Unit) { onBack() }
    val all by state.feed.collectAsStateWithLifecycle(null) // null = loading
    val cats by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(emptyList())
    val catById = cats.associateBy { it.id }
    val rows = if (f == null) null else all?.let(familySelect(f, filter))
    // Transfers left out of the total, like the tab's donut.
    val total = rows.orEmpty().filter { it.expense.transferTo == null }.sumOf { it.expense.amount }

    AppBarScaffold(t("family.list"), onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section)) {
            item {
                val period = periodLabel(filter.range, monthStart())
                Column(Modifier.clearAndSetSemantics { contentDescription = "$period, ${t("insights.total_a11y", formatMoney(total))}" }) {
                    Text(period, color = AppTheme.colors.muted, style = ty.labelMedium)
                    Money(total, style = ty.headlineMedium, autoSize = true)
                }
                if (offline) OfflineNote()
                if (f != null && !filter.isEmpty) {
                    Spacer(Modifier.height(12.dp))
                    ActiveFilters(filter, catById, { filter = it }, f.memberNames)
                }
            }
            when {
                rows == null && state.failed -> item { EmptyState(Icons.Outlined.WifiOff, t("family.load_failed"), t("family.load_failed_hint")) }
                rows == null -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.accent) }
                }
                rows.isEmpty() -> item {
                    if (filter.isEmpty) EmptyState(Icons.Outlined.ReceiptLong, t("family.empty_month"))
                    else EmptyState(Icons.Outlined.SearchOff, t("family.no_match"), t("family.no_match_hint"))
                }
                else -> daySections(groupExpenses(rows.map { it.expense }, today = LocalDate.now()), rows.associateBy { it.expense.id }, catById, db, onEdit) { fe, cat ->
                    SharedRow(fe, cat, onEdit)
                }
            }
        }
    }
}
