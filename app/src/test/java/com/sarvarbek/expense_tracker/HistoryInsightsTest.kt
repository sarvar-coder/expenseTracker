package com.sarvarbek.expense_tracker

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.features.activity.ActivityFilterSaver
import com.sarvarbek.expense_tracker.features.activity.ActivityScreen
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import com.sarvarbek.expense_tracker.features.insights.InsightsScreen
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.mergeFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Toaster
import com.sarvarbek.expense_tracker.ui.common.monthName
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Ports activity_screen_test and insights_screen_test. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class HistoryInsightsTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private val db get() = database.dao()

    @Before fun setUp() {
        database = AppDatabase.open(context, name = null)
    }

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        AppTheme {
            CompositionLocalProvider(LocalToaster provides Toaster(remember { SnackbarHostState() }, rememberCoroutineScope())) { content() }
        }
    }

    private fun add(description: String, amount: Long, category: String) = runBlocking {
        val cat = db.getCategories().first { it.name == category }
        db.insertExpense(Expense(description = description, amount = amount, categoryId = cat.id, date = System.currentTimeMillis(), source = ExpenseSource.manual))
    }

    private fun waitFor(text: String) =
        rule.waitUntil(5000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()

    private fun chip(label: String) = rule.onNode(hasText(label) and hasContentDescription("Olib tashlash"))

    private fun removeChip() = rule.onNodeWithContentDescription("Olib tashlash").performClick()

    @Test fun filterPageAndSearchFilterRowsDayTotalEmptyState() {
        add("Coffee", 10000, "Food & dining")
        add("Taxi", 10000, "Transport")
        show { ActivityScreen(db, onEdit = {}) }
        waitFor("Coffee")
        rule.onNodeWithText("Taxi").assertExists()
        rule.onNodeWithText("Bugun").assertExists()
        rule.onNodeWithText("20 000").assertExists() // day total
        rule.onNodeWithContentDescription("Olib tashlash").assertDoesNotExist() // no active filters
        rule.onNodeWithContentDescription("Filtr").assertHeightIsAtLeast(48.dp)

        // Filter page: Turkum sheet -> Transport -> Tayyor -> Qo'llash.
        rule.onNodeWithContentDescription("Filtr").performClick()
        tap("Turkum")
        waitFor("Tayyor")
        rule.onNode(hasText("Transport") and isToggleable()).performClick()
        tap("Tayyor")
        rule.waitUntil(5000) { rule.onAllNodesWithText("Tayyor").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("Turkum") and hasText("Transport")).assertExists() // row subtitle
        tap("Qo'llash")
        rule.waitForIdle()
        rule.onNodeWithText("Coffee").assertDoesNotExist()
        rule.onNodeWithText("Taxi").assertExists()
        chip("Transport").assertExists()
        rule.onNodeWithText("1").assertExists() // badge

        // Removing the active chip restores the list.
        removeChip()
        rule.onNodeWithText("Coffee").assertExists()
        rule.onNodeWithContentDescription("Olib tashlash").assertDoesNotExist()

        // No private expenses, so no Ko'rinish filter.
        rule.onNodeWithContentDescription("Filtr").performClick()
        rule.onNodeWithText("Ko'rinish").assertDoesNotExist()
        rule.onNodeWithContentDescription("Orqaga").performClick()

        val search = rule.onNode(hasSetTextAction())
        search.performTextReplacement("cof")
        rule.onNodeWithText("Coffee").assertExists()
        rule.onNodeWithText("Taxi").assertDoesNotExist()
        search.performTextReplacement("zzz")
        rule.onNodeWithText("Mos keladigani yo'q").assertExists()
    }

    @Test fun amountFilterSwapsMinMaxAndShowsChip() {
        add("Coffee", 10000, "Food & dining")
        add("Laptop", 900000, "Shopping")
        show { ActivityScreen(db, onEdit = {}) }
        waitFor("Coffee")
        rule.onNodeWithContentDescription("Filtr").performClick()
        tap("Summa")
        rule.onNodeWithTag("min").performTextReplacement("50000")
        rule.onNodeWithTag("max").performTextReplacement("5000")
        rule.onNodeWithText("50 000 – 5 000").assertExists() // live label, unswapped
        tap("Qo'llash")
        rule.waitForIdle()
        rule.onNodeWithText("Laptop").assertDoesNotExist()
        rule.onNodeWithText("Coffee").assertExists()
        chip("5 000 – 50 000").assertExists()
    }

    @Test fun insightsTotalLegendMonthPillsYearSheetFilterSwapsPillsForChips() {
        add("Coffee", 45000, "Food & dining")
        show { InsightsScreen(db) }
        val now = LocalDate.now()
        rule.waitUntil(5000) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Jami 45 000 UZS")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Food & dining").assertExists()
        rule.onNodeWithText("100%").assertExists()

        // Current month pill selected; future months hidden.
        rule.onNodeWithText(monthName(now.monthValue)).assertIsSelected()
        if (now.monthValue < 12) rule.onNodeWithText(monthName(now.monthValue + 1)).assertDoesNotExist()

        // Year sheet lists years; picking last year shows all 12 months, no data.
        tap("${now.year}")
        waitFor("${now.year - 1}")
        tap("${now.year - 1}")
        rule.waitUntil(5000) { rule.onAllNodesWithText("${now.year}").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("${now.year - 1}").assertExists()
        rule.onNodeWithText("Bu davrda xarajat yo'q").assertExists()
        rule.onNodeWithText("Dekabr").assertExists()

        // Filter by Summa: month pills give way to chips (period + filter).
        rule.onNodeWithContentDescription("Filtr").performClick()
        rule.onNodeWithText("Turkum").assertExists()
        tap("Summa")
        rule.onNodeWithTag("min").performTextReplacement("1000")
        tap("Qo'llash")
        rule.waitForIdle()
        rule.onNodeWithText("Dekabr").assertDoesNotExist()
        rule.onNodeWithText("${monthName(now.monthValue)} ${now.year - 1}").assertExists()
        chip("1 000 dan").assertExists()

        // Removing the last chip brings the month pills back.
        removeChip()
        rule.onNodeWithText("Dekabr").assertExists()
    }

    @Test fun insightsCountsWholeFamilyAndFiltersByMember() {
        add("Coffee", 45000, "Food & dining") // mine, not yet synced: no ownerId
        val transport = runBlocking { db.getCategories().first { it.name == "Transport" }.id }
        fun other(id: String, amount: Long, owner: String) = Expense(
            id = id, description = "Taksi", amount = amount, categoryId = transport,
            date = System.currentTimeMillis(), source = ExpenseSource.manual, ownerId = owner,
        )
        val others = MutableStateFlow(listOf(FamilyExpense(other("o1", 30000, "u2"), "Vali"), FamilyExpense(other("o2", 5000, "u3"), "Gul")))
        val feed = combine(db.watchExpenses(), others) { own, o -> mergeFeed(own, o, "Siz", "u1") }
        show { InsightsScreen(db, feed) }
        rule.waitUntil(5000) { rule.onAllNodes(hasContentDescription("Jami 80 000 UZS")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Transport").assertExists()

        // A'zo: Vali only, total and legend follow the filtered rows.
        rule.onNodeWithContentDescription("Filtr").performClick()
        tap("A'zo")
        waitFor("Tayyor")
        rule.onNode(hasText("Vali") and isToggleable()).performClick()
        tap("Tayyor")
        rule.waitUntil(5000) { rule.onAllNodesWithText("Tayyor").fetchSemanticsNodes().isEmpty() }
        tap("Qo'llash")
        rule.waitUntil(5000) { rule.onAllNodes(hasContentDescription("Jami 30 000 UZS")).fetchSemanticsNodes().isNotEmpty() }
        chip("Vali").assertExists()
        rule.onNodeWithText("Food & dining").assertDoesNotExist()

        // Me ("Siz"): my unsynced row counts under my id.
        removeChip()
        rule.onNodeWithContentDescription("Filtr").performClick()
        tap("A'zo")
        waitFor("Tayyor")
        rule.onNode(hasText("Siz") and isToggleable()).performClick()
        tap("Tayyor")
        rule.waitUntil(5000) { rule.onAllNodesWithText("Tayyor").fetchSemanticsNodes().isEmpty() }
        tap("Qo'llash")
        rule.waitUntil(5000) { rule.onAllNodes(hasContentDescription("Jami 45 000 UZS")).fetchSemanticsNodes().isNotEmpty() }

        // Vali picked, then Vali leaves the feed: the stale pick stops filtering.
        removeChip()
        rule.onNodeWithContentDescription("Filtr").performClick()
        tap("A'zo")
        waitFor("Tayyor")
        rule.onNode(hasText("Vali") and isToggleable()).performClick()
        tap("Tayyor")
        rule.waitUntil(5000) { rule.onAllNodesWithText("Tayyor").fetchSemanticsNodes().isEmpty() }
        tap("Qo'llash")
        rule.waitUntil(5000) { rule.onAllNodes(hasContentDescription("Jami 30 000 UZS")).fetchSemanticsNodes().isNotEmpty() }
        others.value = others.value.filter { it.ownerName != "Vali" }
        rule.waitUntil(5000) { rule.onAllNodes(hasContentDescription("Jami 50 000 UZS")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Olib tashlash").assertDoesNotExist()
    }

    @Test fun insightsAloneHasNoMemberRow() {
        add("Coffee", 45000, "Food & dining")
        val feed = db.watchExpenses().map { mergeFeed(it, emptyList(), "Siz", "u1") }
        show { InsightsScreen(db, feed) }
        rule.waitUntil(5000) { rule.onAllNodes(hasContentDescription("Jami 45 000 UZS")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Filtr").performClick()
        rule.onNodeWithText("Turkum").assertExists()
        rule.onNodeWithText("A'zo").assertDoesNotExist()
    }

    @Test fun filterSurvivesSaveRestore() {
        val f = ActivityFilter(setOf("a", "b"), LocalDate.of(2026, 7, 5)..LocalDate.of(2026, 7, 9), 1000, null, setOf("u2"))
        val saved = with(ActivityFilterSaver) { SaverScope { true }.save(f) }!!
        assertEquals(f, ActivityFilterSaver.restore(saved))
        assertEquals(ActivityFilter(), ActivityFilterSaver.restore(with(ActivityFilterSaver) { SaverScope { true }.save(ActivityFilter()) }!!))
    }

    @Test fun emptyPeriodShowsEmptyState() {
        show { InsightsScreen(db) }
        waitFor("Bu davrda xarajat yo'q")
    }
}
