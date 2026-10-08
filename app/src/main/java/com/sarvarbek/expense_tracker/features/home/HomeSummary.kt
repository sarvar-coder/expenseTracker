package com.sarvarbek.expense_tracker.features.home

import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.ui.common.startMillis
import java.time.LocalDate

/** This month's spend against the budget, for Home's month line. */
data class HomeSummary(val spent: Long, val budget: Long) {
    val progress: Float get() = if (budget <= 0) 0f else (spent.toFloat() / budget).coerceIn(0f, 1f)
    val remaining: Long get() = budget - spent
}

private fun Expense.within(start: LocalDate, end: LocalDate) = date >= start.startMillis() && date < end.startMillis()

/** Sums the calendar month containing [today] into a [HomeSummary]; transfers aren't spending. */
fun summarize(expenses: List<Expense>, budget: Long, today: LocalDate): HomeSummary {
    val start = today.withDayOfMonth(1)
    return HomeSummary(expenses.filter { it.transferTo == null && it.within(start, start.plusMonths(1)) }.sumOf { it.amount }, budget)
}

/** Feed rows (mine and others') on the local calendar day [today], newest first. */
fun todayExpenses(rows: List<FamilyExpense>, today: LocalDate): List<FamilyExpense> =
    rows.filter { it.expense.within(today, today.plusDays(1)) }.sortedByDescending { it.expense.date }
