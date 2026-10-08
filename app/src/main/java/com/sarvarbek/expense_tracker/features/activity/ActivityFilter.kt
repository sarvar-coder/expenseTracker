package com.sarvarbek.expense_tracker.features.activity

import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import androidx.compose.runtime.saveable.listSaver
import java.time.LocalDate

/** A day's worth of expenses under a human label (Bugun / Kecha / "8 Iyul"). */
data class DaySection(val day: LocalDate, val label: String, val items: List<Expense>)

/** Tarix sheet filters. Empty/null fields mean "no constraint". */
data class ActivityFilter(
    val categoryIds: Set<String> = emptySet(),
    val range: ClosedRange<LocalDate>? = null, // whole days, end inclusive
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val memberIds: Set<String> = emptySet(), // Oila only: who added it
) {
    /** Active filter groups — shown as the badge on the filter icon. */
    val count: Int
        get() = listOf(
            categoryIds.isNotEmpty(), range != null, minAmount != null || maxAmount != null, memberIds.isNotEmpty(),
        ).count { it }

    val isEmpty get() = count == 0

    fun matches(e: Expense): Boolean =
        (categoryIds.isEmpty() || e.categoryId in categoryIds) &&
            (range == null || e.date.toLocalDate() in range) &&
            (minAmount == null || e.amount >= minAmount) &&
            (maxAmount == null || e.amount <= maxAmount) &&
            (memberIds.isEmpty() || e.ownerId in memberIds)
}

/**
 * Filters [expenses] by description substring + [filter], then groups the
 * (already date-desc) list into day sections.
 */
fun groupExpenses(
    expenses: List<Expense>,
    query: String = "",
    filter: ActivityFilter = ActivityFilter(),
    today: LocalDate,
): List<DaySection> {
    val q = query.trim().lowercase()
    fun labelFor(d: LocalDate) = when (d) {
        today -> t("activity.today")
        today.minusDays(1) -> t("activity.yesterday")
        else -> uzDayMonth(d)
    }
    // groupBy keeps first-seen order; input is already date-desc. By date, not label: "8 Iyul" recurs every year.
    return expenses
        .filter { filter.matches(it) && (q.isEmpty() || it.description.lowercase().contains(q)) }
        .groupBy { it.date.toLocalDate() }
        .map { (day, items) -> DaySection(day, labelFor(day), items) }
}

/** Keeps the filter across tab switches (the shell saves per-tab state). */
val ActivityFilterSaver = listSaver<ActivityFilter, Any?>(
    save = { f -> listOf(ArrayList(f.categoryIds), f.range?.start?.toEpochDay(), f.range?.endInclusive?.toEpochDay(), f.minAmount, f.maxAmount, ArrayList(f.memberIds)) },
    restore = { l ->
        @Suppress("UNCHECKED_CAST")
        ActivityFilter(
            (l[0] as List<String>).toSet(),
            (l[1] as Long?)?.let { LocalDate.ofEpochDay(it)..LocalDate.ofEpochDay(l[2] as Long) },
            l[3] as Long?, l[4] as Long?, (l.last() as List<String>).toSet(), // last: a 2.0.x bundle had isPrivate before it
        )
    },
)
