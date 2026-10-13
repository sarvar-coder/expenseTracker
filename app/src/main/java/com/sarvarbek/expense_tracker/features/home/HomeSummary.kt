package com.sarvarbek.expense_tracker.features.home

import com.sarvarbek.expense_tracker.data.Contribution
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.ui.common.startMillis
import java.time.LocalDate

/** Spent against money in (budget), for the month lines on Home and Oila. */
data class HomeSummary(val spent: Long, val budget: Long) {
    val progress: Float get() = if (budget <= 0) 0f else (spent.toFloat() / budget).coerceIn(0f, 1f)
    val remaining: Long get() = budget - spent
}

/**
 * My calendar month: own contributions + transfers received − own spending −
 * transfers given. Mirrors family_summary's `available`.
 */
data class Balance(val contributed: Long, val received: Long, val spent: Long, val given: Long) {
    val available: Long get() = contributed + received - spent - given
    /** Month line: what I had to spend (in − given) vs spent; remaining = [available]. */
    val summary: HomeSummary get() = HomeSummary(spent, contributed + received - given)
}

private fun Long.within(start: LocalDate, end: LocalDate) = this >= start.startMillis() && this < end.startMillis()
private fun Expense.within(start: LocalDate, end: LocalDate) = date.within(start, end)

/**
 * [expenses]/[contributions]: own rows (Room). [received]: from the server
 * ([com.sarvarbek.expense_tracker.data.Settings.receivedIn]), others' transfers aren't local.
 */
fun balance(expenses: List<Expense>, contributions: List<Contribution>, received: Long, today: LocalDate): Balance {
    val start = today.withDayOfMonth(1)
    val end = start.plusMonths(1)
    val month = expenses.filter { it.within(start, end) }
    return Balance(
        contributed = contributions.filter { it.date.within(start, end) }.sumOf { it.amount },
        received = received,
        spent = month.filter { it.transferTo == null }.sumOf { it.amount },
        given = month.filter { it.transferTo != null }.sumOf { it.amount },
    )
}

/** Step 4's overspend check: what I can still spend this month (may be negative). Room rows are all own. */
suspend fun myAvailable(db: ExpenseDao, settings: SettingsStore, today: LocalDate = LocalDate.now()): Long =
    balance(db.getExpenses(), db.getContributions(), settings.settings.value.receivedIn(today), today).available

/** Feed rows (mine and others') on the local calendar day [today], newest first. */
fun todayExpenses(rows: List<FamilyExpense>, today: LocalDate): List<FamilyExpense> =
    rows.filter { it.expense.within(today, today.plusDays(1)) }.sortedByDescending { it.expense.date }
