package com.sarvarbek.expense_tracker

import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.features.activity.groupExpenses
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.features.home.summarize
import com.sarvarbek.expense_tracker.features.home.todayExpenses
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.features.insights.insightsFor
import com.sarvarbek.expense_tracker.services.expensesCsv
import com.sarvarbek.expense_tracker.ui.common.I18n
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.common.toMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Plain JVM: ports home_summary, insights_data, activity_filter, csv_export and parseAmount tests. */
class HelpersTest {
    // Plain JVM: no Application, so load the Latin strings straight from the source tree.
    init { I18n.strings = I18n.parse(java.io.File("src/main/assets/i18n/uz.json").readText()) }

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toMillis()

    private fun exp(id: Int, amount: Long, catId: Int, date: Long, desc: String = "x") =
        Expense(id = "$id", description = desc, amount = amount, categoryId = "$catId", date = date,
            source = ExpenseSource.manual, dirty = false)

    // --- home_summary ---
    @Test fun summarizeSumsCurrentMonthAgainstBudget() {
        val s = summarize(
            listOf(
                exp(1, 45000, 1, at(2026, 9, 10)),
                exp(2, 30000, 1, at(2026, 9, 1)), // first instant of month: in
                exp(3, 20000, 2, at(2026, 9, 30, 23, 59)),
                exp(4, 99000, 1, at(2026, 8, 15)), // prior month: out
                exp(5, 11000, 1, at(2026, 10, 1)), // next month: out
            ),
            4_000_000, LocalDate.of(2026, 9, 26),
        )
        assertEquals(95000L, s.spent)
        assertEquals(3_905_000L, s.remaining)
        assertEquals(95000f / 4_000_000, s.progress, 1e-6f)
        assertEquals(0f, summarize(emptyList(), 0, LocalDate.of(2026, 9, 1)).progress)
    }

    @Test fun transfersAreNotSpending() {
        val day = at(2026, 9, 10)
        val list = listOf(exp(1, 45000, 1, day), exp(2, 500000, 1, day).copy(transferTo = "sister"))
        assertEquals(45000L, summarize(list, 0, LocalDate.of(2026, 9, 26)).spent)
        val data = insightsFor(list, cats, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        assertEquals(45000L, data.total)
        assertEquals(listOf(45000L), data.slices.map { it.amount })
    }

    @Test fun todayExpensesKeepsLocalTodayNewestFirst() {
        val t = todayExpenses(
            listOf(
                exp(1, 1, 1, at(2026, 9, 26)), // midnight: in
                exp(2, 1, 1, at(2026, 9, 25, 23, 59)), // yesterday: out
                exp(3, 1, 1, at(2026, 9, 26, 12)),
                exp(4, 1, 1, at(2026, 9, 27)), // tomorrow: out
            ).map { FamilyExpense(it, "") },
            LocalDate.of(2026, 9, 26),
        )
        assertEquals(listOf("3", "1"), t.map { it.expense.id })
    }

    // --- insights_data ---
    private val cats = listOf(Category(id = "1", name = "Food", colorHex = "E08A5B"), Category(id = "2", name = "Transport", colorHex = "5B8DB8"))

    @Test fun insightsMonthWindowRanksSlices() {
        val data = insightsFor(
            listOf(exp(1, 60000, 1, at(2026, 7, 2)), exp(2, 20000, 2, at(2026, 7, 9)), exp(3, 99000, 1, at(2026, 6, 30))),
            cats, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1),
        )
        assertEquals(80000L, data.total)
        assertEquals("Food", data.slices.first().category.name)
        assertEquals(listOf(60000L, 20000L), data.slices.map { it.amount })
        assertEquals(0.75, data.fraction(60000), 1e-9)
    }

    @Test fun insightsCustomWindowEndExclusive() {
        val data = insightsFor(
            listOf(
                exp(1, 10000, 1, at(2026, 7, 13)), // first day, in
                exp(2, 5000, 1, at(2026, 7, 19, 23)), // last day late, in
                exp(3, 7000, 1, at(2026, 7, 12, 23)), // day before, out
                exp(4, 8000, 1, at(2026, 7, 20)), // day after, out
            ),
            cats, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 20),
        )
        assertEquals(15000L, data.total)
    }

    @Test fun insightsFilterNarrows() {
        val data = insightsFor(
            listOf(exp(1, 10000, 1, at(2026, 7, 2)), exp(2, 50000, 1, at(2026, 7, 3)), exp(3, 20000, 2, at(2026, 7, 4))),
            cats, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1),
            filter = ActivityFilter(categoryIds = setOf("1"), maxAmount = 40000),
        )
        assertEquals(10000L, data.total)
        assertEquals("Food", data.slices.single().category.name)
    }

    @Test fun insightsEmptyPeriod() {
        val data = insightsFor(emptyList(), cats, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))
        assertEquals(0L, data.total)
        assertTrue(data.slices.isEmpty())
        assertEquals(0.0, data.fraction(0), 0.0)
    }

    // --- activity_filter ---
    private val today = LocalDate.of(2026, 7, 8)
    private val expenses = listOf( // date-desc, as the DB delivers it
        exp(1, 1000, 10, at(2026, 7, 8, 9), "Bon Cafe"),
        exp(2, 2000, 20, at(2026, 7, 7, 18), "Yandex Go"),
        exp(3, 3000, 10, at(2026, 7, 5, 10), "Korzinka"),
    )

    private fun names(f: ActivityFilter, list: List<Expense> = expenses) =
        groupExpenses(list, filter = f, today = today).flatMap { s -> s.items.map { it.description } }

    @Test fun groupsTodayYesterdayDated() {
        val sections = groupExpenses(expenses, today = today)
        assertEquals(listOf("Bugun", "Kecha", "5 Iyul"), sections.map { it.label })
        assertEquals("Bon Cafe", sections.first().items.single().description)
    }

    @Test fun sameDayMonthInTwoYearsStaysTwoSections() {
        val last = expenses.last()
        val yearAgo = last.copy(id = "y", date = last.date.toLocalDate().minusYears(1).startMillis())
        val sections = groupExpenses(expenses + yearAgo, today = today)
        assertEquals(listOf("5 Iyul", "5 Iyul"), sections.takeLast(2).map { it.label })
    }

    @Test fun queryNarrowsCaseInsensitive() {
        val sections = groupExpenses(expenses, query = "kOrz", today = today)
        assertEquals("5 Iyul", sections.single().label)
        assertEquals("Korzinka", sections.single().items.single().description)
        assertTrue(groupExpenses(expenses, query = "nope", today = today).isEmpty())
    }

    @Test fun categoriesOneOrSeveral() {
        assertEquals(listOf("Bon Cafe", "Korzinka"), names(ActivityFilter(categoryIds = setOf("10"))))
        assertEquals(listOf("Bon Cafe", "Yandex Go", "Korzinka"), names(ActivityFilter(categoryIds = setOf("10", "20"))))
    }

    @Test fun dateRangeIncludesWholeEndDay() {
        val f = ActivityFilter(range = LocalDate.of(2026, 7, 5)..LocalDate.of(2026, 7, 7))
        assertEquals(listOf("Yandex Go", "Korzinka"), names(f))
        assertEquals(1, f.count)
    }

    @Test fun amountBoundsInclusive() {
        assertEquals(listOf("Yandex Go", "Korzinka"), names(ActivityFilter(minAmount = 2000)))
        assertEquals(listOf("Bon Cafe", "Yandex Go"), names(ActivityFilter(maxAmount = 2000)))
        assertEquals(listOf("Yandex Go"), names(ActivityFilter(minAmount = 2000, maxAmount = 2000)))
    }

    @Test fun countAndIsEmpty() {
        assertTrue(ActivityFilter().isEmpty)
        assertEquals(2, ActivityFilter(categoryIds = setOf("1", "2"), minAmount = 1, maxAmount = 2).count)
        assertEquals(1, ActivityFilter(memberIds = setOf("u1", "u2")).count)
    }

    @Test fun membersOneOrSeveral() {
        val t = at(2026, 7, 8)
        val list = listOf(exp(1, 1, 10, t, "Non").copy(ownerId = "u1"), exp(2, 1, 10, t, "Taksi").copy(ownerId = "u2"), exp(3, 1, 10, t, "Dori").copy(ownerId = "u3"))
        assertEquals(listOf("Taksi"), names(ActivityFilter(memberIds = setOf("u2")), list))
        assertEquals(listOf("Non", "Dori"), names(ActivityFilter(memberIds = setOf("u1", "u3")), list))
        assertEquals(3, names(ActivityFilter(), list).size)
    }

    // --- csv_export ---
    @Test fun csvHeaderRowsAndEscaping() {
        val lines = expensesCsv(
            listOf(exp(1, 45000, 1, at(2026, 7, 2), "Coffee"), exp(2, 90000, 2, at(2026, 7, 3), "Lunch, with \"tip\"")),
            mapOf("1" to "Food", "2" to "Transport"),
        ).split("\r\n")
        assertEquals("date,description,category,amount,source", lines[0])
        assertEquals("2026-07-02,Coffee,Food,45000,manual", lines[1])
        assertEquals("2026-07-03,\"Lunch, with \"\"tip\"\"\",Transport,90000,manual", lines[2])
    }

    @Test fun csvUnknownCategoryEmptyCell() {
        assertTrue(expensesCsv(listOf(exp(1, 1000, 99, at(2026, 1, 1), "X")), emptyMap()).contains("2026-01-01,X,,1000,manual"))
    }

    // --- formatting ---
    @Test fun parseAmountStripsSeparators() {
        assertEquals(45000L, parseAmount("45 000"))
        assertEquals(128000L, parseAmount("128,000 UZS"))
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
    }

    @Test fun formatMoneyGroupsWithSpaces() {
        assertEquals("2 450 000", formatMoney(2_450_000))
        assertEquals("0", formatMoney(0))
        assertEquals("-1 000", formatMoney(-1000))
    }
}
