package com.sarvarbek.expense_tracker.features.activity

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.ExpenseTile
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import java.time.LocalDate

/** Tarix: search + filter page over every expense, grouped by day with each day's total. */
@Composable
fun ActivityScreen(db: ExpenseDao, onEdit: (Expense) -> Unit) {
    val t = MaterialTheme.typography
    val expenses by remember(db) { db.watchExpenses() }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(db) { db.watchCategories() }.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable(stateSaver = ActivityFilterSaver) { mutableStateOf(ActivityFilter()) }
    var filterOpen by remember { mutableStateOf(false) }
    val catById = categories.associateBy { it.id }
    val sections = groupExpenses(expenses, query, filter, LocalDate.now())

    // Bottom padding clears the floating add button.
    LazyColumn(contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tarix", style = t.headlineMedium, modifier = Modifier.weight(1f))
                FilterButton(filter.count) { filterOpen = true }
            }
            Spacer(Modifier.height(AppSpace.gap))
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Xarajatlarni qidirish") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = AppTheme.colors.muted) },
                singleLine = true, shape = fieldShape, colors = fieldColors(),
            )
            if (!filter.isEmpty) {
                Spacer(Modifier.height(12.dp))
                ActiveFilters(filter, catById, onChange = { filter = it })
            }
        }
        if (sections.isEmpty()) {
            item {
                if (expenses.isEmpty()) {
                    EmptyState(Icons.Outlined.ReceiptLong, "Hali xarajat yo'q", "Pastdagi tugma bilan birinchisini qo'shing")
                } else {
                    EmptyState(Icons.Outlined.SearchOff, "Mos keladigani yo'q", "Boshqa so'z yoki filtrni sinab ko'ring")
                }
            }
        }
        items(sections, key = { it.label }) { section ->
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp)) {
                Text(section.label, style = t.titleMedium, modifier = Modifier.weight(1f))
                Text(formatMoney(section.items.filter { it.transferTo == null }.sumOf { it.amount }), color = AppTheme.colors.muted, style = t.labelMedium)
            }
            AppCard(Modifier.fillMaxWidth()) {
                for (e in section.items) ExpenseTile(e, catById[e.categoryId], db, onEdit)
            }
        }
    }

    if (filterOpen) {
        ActivityFilterPage(filter, categories, onDismiss = { filterOpen = false }) { filter = it; filterOpen = false }
    }
}
