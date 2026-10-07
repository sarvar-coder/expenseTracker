package com.sarvarbek.expense_tracker.features.insights

import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.ui.common.startMillis
import java.time.LocalDate

data class Slice(val category: Category, val amount: Long)

data class InsightsData(val total: Long, val slices: List<Slice>) { // slices sorted desc by amount
    fun fraction(amount: Long): Double = if (total <= 0) 0.0 else amount.toDouble() / total
}

/**
 * Spend by category over the half-open day window [start, end), keeping only
 * expenses that pass [filter].
 */
fun insightsFor(
    expenses: List<Expense>,
    categories: List<Category>,
    start: LocalDate,
    end: LocalDate,
    filter: ActivityFilter = ActivityFilter(),
): InsightsData {
    val from = start.startMillis()
    val to = end.startMillis()
    val kept = expenses.filter { it.date in from until to && filter.matches(it) }
    val catById = categories.associateBy { it.id }
    val slices = kept.groupBy { it.categoryId }
        .mapNotNull { (id, items) -> catById[id]?.let { Slice(it, items.sumOf { e -> e.amount }) } }
        .sortedByDescending { it.amount }
    return InsightsData(kept.sumOf { it.amount }, slices)
}
