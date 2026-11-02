package com.sarvarbek.expense_tracker.features.home

import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.ui.common.startMillis
import java.time.LocalDate

private fun Expense.within(start: LocalDate, end: LocalDate) = date >= start.startMillis() && date < end.startMillis()

/** What [expenses] (own rows) spent in [today]'s calendar month; old transfers don't count. */
fun monthSpent(expenses: List<Expense>, today: LocalDate): Long {
    val start = today.withDayOfMonth(1)
    return expenses.filter { it.transferTo == null && it.within(start, start.plusMonths(1)) }.sumOf { it.amount }
}

/** Feed rows (mine and others') on the local calendar day [today], newest first. */
fun todayExpenses(rows: List<FamilyExpense>, today: LocalDate): List<FamilyExpense> =
    rows.filter { it.expense.within(today, today.plusDays(1)) }.sortedByDescending { it.expense.date }
