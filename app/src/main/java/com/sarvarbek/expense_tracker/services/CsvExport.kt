package com.sarvarbek.expense_tracker.services

import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.ui.common.toLocalDate

/** RFC 4180 field: quoted (inner quotes doubled) only when it needs to be. */
private fun field(v: Any): String {
    val s = v.toString()
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
}

/** Expenses CSV (CRLF rows, ISO dates). Pure — directly unit-testable. */
fun expensesCsv(rows: List<Expense>, catNames: Map<String, String>): String =
    (listOf(listOf("date", "description", "category", "amount", "source")) +
        rows.map { e -> listOf(e.date.toLocalDate(), e.description, catNames[e.categoryId] ?: "", e.amount, e.source.name) })
        .joinToString("\r\n") { row -> row.joinToString(",", transform = ::field) }
