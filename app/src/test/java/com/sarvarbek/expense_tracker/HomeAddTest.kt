package com.sarvarbek.expense_tracker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sarvarbek.expense_tracker.features.insights.InsightsScreen
import com.sarvarbek.expense_tracker.ui.Shell
import com.sarvarbek.expense_tracker.ui.back
import com.sarvarbek.expense_tracker.ui.go
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Contribution
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.features.add.AddScreen
import com.sarvarbek.expense_tracker.features.add.shortfallFor
import com.sarvarbek.expense_tracker.features.home.HomeScreen
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.ParsedExpense
import com.sarvarbek.expense_tracker.services.mergeFeed
import com.sarvarbek.expense_tracker.services.SpeechService
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Toaster
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/** Fake STT: `listen` emits a canned transcript at once, no microphone. */
private class FakeSpeech(context: Context, val transcript: String) : SpeechService(context) {
    override fun available() = true
    override fun listen(locale: String, onResult: (String) -> Unit, onEnd: () -> Unit) = onResult(transcript)
    override fun stop() {}
    override fun release() {}
}

/** Ports home_screen_test, add_screen_test, add_edit_test, type_mode_test, speak_mode_test. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp") // Manual form (with Maxfiy) is tall.
class HomeAddTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private val db get() = database.dao()
    private lateinit var settings: SettingsStore
    private val aiCoffee = ParsedExpense("Coffee", 45000, "Food & dining", LocalDate.of(2026, 9, 26))

    @Before fun setUp() {
        database = AppDatabase.open(context, name = null)
        val prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        settings = SettingsStore(prefs)
    }

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        AppTheme {
            CompositionLocalProvider(LocalToaster provides Toaster(remember { SnackbarHostState() }, rememberCoroutineScope())) { content() }
        }
    }

    @Composable
    private fun Add(
        editing: Expense? = null,
        parsed: ParsedExpense? = null,
        speech: SpeechService = FakeSpeech(context, ""),
        members: List<Pair<String, String>> = emptyList(),
        aiModes: Boolean = true,
    ) = AddScreen(db, settings, { _, _ -> parsed }, speech, canCreate = { true }, onClose = {}, editing = editing, members = members, aiModes = aiModes)

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    private fun rows() = runBlocking { db.getExpenses() }

    private fun waitRows(n: Int) = rule.waitUntil(5000) { rows().size == n }

    /** Enough contributions this month and aiCoffee's month that saves don't hit "Pul qayerdan?". */
    private fun fund() = runBlocking {
        db.insertContribution(Contribution(amount = 10_000_000, note = "Oylik"))
        db.insertContribution(Contribution(amount = 10_000_000, note = "Oylik", date = aiCoffee.date.startMillis()))
    }

    private fun SemanticsNodeInteraction.tap() = performScrollTo().performClick()

    private fun pickCategory(current: String, name: String) {
        rule.onNodeWithText(current).tap()
        waitFor(name)
        rule.onAllNodesWithText(name).onLast().performClick()
        rule.waitForIdle()
    }

    private fun maxfiy() = rule.onNode(hasText("Maxfiy") and isToggleable())

    // --- home_screen_test ---

    @Test fun emptyDayShowsEmptyStateAndBudgetHint() {
        show { HomeScreen(db, settings, onSettings = {}, onEdit = {}) }
        waitFor("Bugun hali xarajat yo'q")
        rule.onNodeWithText("hali hissa yo'q", substring = true).assertExists()
        rule.onNodeWithContentDescription("Sozlamalar").assertHeightIsAtLeast(48.dp)
    }

    @Test fun listsOnlyTodayAndShowsMonthLine() {
        val food = runBlocking { db.getCategories() }.first()
        val now = System.currentTimeMillis()
        runBlocking {
            db.insertExpense(Expense(description = "Coffee", amount = 45000, categoryId = food.id, date = now, source = ExpenseSource.manual))
            db.insertExpense(Expense(description = "Old taxi", amount = 30000, categoryId = food.id, date = now - 40L * 86_400_000, source = ExpenseSource.manual))
        }
        // Another member's shared rows, as FamilyService.feed delivers them.
        val others = listOf(
            FamilyExpense(Expense(description = "Bread", amount = 10000, categoryId = food.id, date = now - 1000, source = ExpenseSource.manual, ownerId = "ali"), "Ali"),
            FamilyExpense(Expense(description = "Old bread", amount = 5000, categoryId = food.id, date = now - 40L * 86_400_000, source = ExpenseSource.manual, ownerId = "ali"), "Ali"),
        )
        runBlocking { db.insertContribution(Contribution(amount = 1_000_000, note = "Oylik")) }
        settings.setReceived(YearMonth.now().toString(), 200_000) // a transfer from Ali
        val feed = db.watchExpenses().map { mergeFeed(it, others, "Siz") }
        show { HomeScreen(db, settings, feed, offline = true, inFamily = true, onSettings = {}, onEdit = {}) }
        waitFor("Coffee")
        rule.onNodeWithText("Bread").assertExists()
        rule.onNodeWithText("Ali ·", substring = true).assertExists()
        rule.onNodeWithText("Old taxi").assertDoesNotExist()
        rule.onNodeWithText("Old bread").assertDoesNotExist()
        rule.onNodeWithText("Bugun hali xarajat yo'q").assertDoesNotExist()
        // Hero: mine big, family's line; budget line stays personal.
        rule.onNodeWithText("Men · bugun · 1 ta").assertExists()
        rule.onNodeWithText("Oila bugun: 55 000 UZS").assertExists()
        rule.onNodeWithText("Oy: 45 000 / 1 200 000", substring = true).assertExists()
        rule.onNodeWithText("Internet yo'q", substring = true).assertExists()
        // Others' row opens the read-only sheet, not edit.
        rule.onNodeWithText("Bread").performClick()
        waitFor("Faqat o'qish uchun")
    }

    @Test fun addsContributionWithRequiredNoteThenDeletes() {
        show { HomeScreen(db, settings, onSettings = {}, onEdit = {}) }
        waitFor("+ Hissa qo'shish")
        rule.onNodeWithText("+ Hissa qo'shish").performClick()
        waitFor("Bu oy hali hissa qo'shilmagan")
        rule.onNodeWithTag("contrib_amount").performTextInput("400000")
        rule.onNodeWithText("Qo'shish").performClick()
        rule.waitForIdle()
        assertTrue("note is required", runBlocking { db.getContributions() }.isEmpty())
        rule.onNodeWithTag("contrib_note").performTextInput("Oylik")
        rule.onNodeWithText("Qo'shish").performClick()
        rule.waitUntil(5000) { runBlocking { db.getContributions() }.isNotEmpty() }
        assertEquals(listOf(400_000L to "Oylik"), runBlocking { db.getContributions() }.map { it.amount to it.note })
        waitFor("Bu oy: 400 000 UZS")
        waitFor("Oy: 0 / 400 000", substring = true) // Home's month line counts it
        rule.onNodeWithContentDescription("O'chirish").performClick()
        waitFor("Hissani o'chirasizmi?")
        rule.onNodeWithText("O'chirish").performClick()
        rule.waitUntil(5000) { runBlocking { db.getContributions() }.isEmpty() }
    }

    @Test fun notInFamilyHasNoFamilyLine() {
        val food = runBlocking { db.getCategories() }.first()
        runBlocking { db.insertExpense(Expense(description = "Coffee", amount = 45000, categoryId = food.id, date = System.currentTimeMillis(), source = ExpenseSource.manual)) }
        show { HomeScreen(db, settings, onSettings = {}, onEdit = {}) }
        waitFor("Coffee")
        rule.onNodeWithText("Bugun sarflangan · 1 ta").assertExists()
        rule.onNodeWithText("Oila bugun", substring = true).assertDoesNotExist()
    }

    @Test fun tabsKeepScrollAfterPageAndBack() {
        val food = runBlocking { db.getCategories() }.first()
        val now = System.currentTimeMillis()
        runBlocking {
            repeat(40) { db.insertExpense(Expense(description = "Coffee $it", amount = 1000, categoryId = food.id, date = now - it, source = ExpenseSource.manual)) }
        }
        lateinit var nav: NavHostController
        show {
            nav = rememberNavController()
            NavHost(nav, "shell") {
                composable("shell") {
                    Shell(onAdd = {}) { i, _ -> if (i == 0) HomeScreen(db, settings, onSettings = {}, onEdit = {}) else InsightsScreen(db) }
                }
                composable("page") {}
            }
        }
        fun shown(text: String) = runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        for (tab in listOf("Asosiy", "Tahlil")) {
            rule.onNodeWithText(tab).performClick()
            waitFor("Coffee 0")
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Coffee 39"))
            assertFalse(shown("Coffee 0"))
            rule.runOnIdle { nav.go("page") }
            rule.runOnIdle { nav.back() }
            rule.waitForIdle()
            assertTrue("$tab: Coffee 39 still on screen", shown("Coffee 39"))
            assertFalse("$tab: not back at the top", shown("Coffee 0"))
        }
    }

    // --- add_screen_test ---

    @Test fun aiModesOffShowsOnlyManual() {
        settings.lastAddMode = "speak"
        show { Add(aiModes = false) }
        rule.onNodeWithText("Yozish").assertDoesNotExist()
        rule.onNodeWithText("Aytish").assertDoesNotExist()
        rule.onNodeWithTag("amount").assertExists()
    }

    @Test fun opensInYozishThenReopensInLastPickedMode() {
        var visible by mutableStateOf(true)
        show { if (visible) Add() }
        rule.onNodeWithText("Yozish").assertIsSelected()

        rule.onNodeWithText("Aytish").performClick()
        assertEquals("one mic only", 1, rule.onAllNodesWithContentDescription("Gapirish").fetchSemanticsNodes().size)
        assertEquals("speak", settings.lastAddMode)

        visible = false
        rule.waitForIdle()
        visible = true
        rule.onNodeWithText("Aytish").assertIsSelected()
    }

    @Test fun editScreenDeletesAfterConfirm() {
        val food = runBlocking { db.getCategories() }.first()
        runBlocking { db.insertExpense(Expense(description = "Coffee", amount = 45000, categoryId = food.id, date = System.currentTimeMillis(), source = ExpenseSource.manual)) }
        val expense = rows().single()

        show { Add(editing = expense) }
        rule.onNodeWithText("Qo'lda").assertIsSelected()
        rule.onNodeWithText("O'chirish").tap()
        rule.onNodeWithText("Xarajat o'chirilsinmi?").assertExists()
        rule.onAllNodesWithText("O'chirish").onLast().performClick()
        waitRows(0)
    }

    @Test fun manualValidatesThenSavesAmountDescCategoryKecha() {
        fund()
        settings.lastAddMode = "manual"
        show { Add() }

        rule.onNodeWithText("Saqlash").tap()
        waitFor("To'g'ri summa kiriting")

        rule.onNodeWithTag("amount").performTextInput("45000")
        rule.onNodeWithTag("desc").performTextInput("Coffee")
        pickCategory("Turkum tanlang", "Groceries")
        rule.onNodeWithText("Kecha").tap()
        rule.onNodeWithText("Kecha").assertIsSelected().assertHeightIsAtLeast(48.dp)

        rule.onNodeWithText("Saqlash").tap()
        waitRows(1)
        val row = rows().single()
        val groceries = runBlocking { db.getCategories() }.first { it.name == "Groceries" }
        assertEquals(45000L, row.amount)
        assertEquals("Coffee", row.description)
        assertEquals(groceries.id, row.categoryId)
        assertEquals(ExpenseSource.manual, row.source)
        assertEquals(LocalDate.now().minusDays(1), row.date.toLocalDate())
    }

    @Test fun transferPicksMemberSkipsCategoryAndSaves() {
        fund()
        settings.lastAddMode = "manual"
        show { Add(members = listOf("u2" to "Malika · Singil")) }

        rule.onNodeWithTag("transfer").tap()
        rule.onNodeWithText("Turkum tanlang").assertDoesNotExist()
        maxfiy().assertDoesNotExist()
        rule.onNodeWithTag("amount").performTextInput("200000")
        rule.onNodeWithText("Saqlash").tap()
        waitFor("Kimga berilganini tanlang")

        rule.onNodeWithText("Malika · Singil").tap()
        rule.onNodeWithText("Saqlash").tap()
        waitRows(1)
        val row = rows().single()
        val boshqa = runBlocking { db.getCategories() }.first { it.name == "Boshqa" }
        assertEquals("u2", row.transferTo)
        assertEquals("→ Malika · Singil", row.description)
        assertEquals(boshqa.id, row.categoryId)
    }

    @Test fun noTransferToggleOutsideFamily() {
        settings.lastAddMode = "manual"
        show { Add() }
        rule.onNodeWithTag("transfer").assertDoesNotExist()
    }

    @Test fun noMaxfiyAndNewRowsShared() {
        fund()
        settings.lastAddMode = "manual"
        show { Add() }

        maxfiy().assertDoesNotExist()
        rule.onNodeWithTag("amount").performTextInput("1000")
        rule.onNodeWithTag("desc").performTextInput("Secret")
        pickCategory("Turkum tanlang", "Food & dining")
        rule.onNodeWithText("Saqlash").tap()
        waitRows(1)
        assertFalse(rows().single().isPrivate)
    }

    // --- overspend ("Pul qayerdan?") ---

    private fun fillCoffee(amount: String) {
        settings.lastAddMode = "manual"
        show { Add() }
        rule.onNodeWithTag("amount").performTextInput(amount)
        rule.onNodeWithTag("desc").performTextInput("Coffee")
        pickCategory("Turkum tanlang", "Food & dining")
        rule.onNodeWithText("Saqlash").tap()
    }

    @Test fun overspendAsksSourceNeedsNoteThenWritesBoth() {
        runBlocking { db.insertContribution(Contribution(amount = 30000, note = "Oylik")) }
        fillCoffee("45000")
        waitFor("Pul qayerdan?")
        rule.onNodeWithText("15 000 UZS", substring = true).assertExists()
        rule.onNodeWithTag("source_confirm").assertIsNotEnabled()
        assertEquals(0, rows().size)
        rule.onNodeWithText("Qarz").performClick()
        rule.onNodeWithTag("source_confirm").assertIsEnabled().performClick()
        waitRows(1)
        val source = runBlocking { db.getContributions() }.first { it.note == "Qarz" }
        assertEquals(15000L, source.amount)
        assertTrue(source.dirty && rows().single().dirty)
    }

    @Test fun overspendCancelSavesNothing() {
        fillCoffee("45000")
        waitFor("Pul qayerdan?")
        rule.onNodeWithText("Bekor qilish").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Pul qayerdan?").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, rows().size)
        assertEquals(0, runBlocking { db.getContributions() }.size)
        rule.onNodeWithText("Saqlash").assertIsEnabled()
    }

    @Test fun withinAvailableSavesDirectly() {
        runBlocking { db.insertContribution(Contribution(amount = 45000, note = "Oylik")) }
        fillCoffee("45000")
        waitRows(1)
        rule.onNodeWithText("Pul qayerdan?").assertDoesNotExist()
        assertEquals(1, runBlocking { db.getContributions() }.size)
    }

    @Test fun shortfallForEditsAndPastMonths() = runBlocking {
        val food = db.getCategories().first()
        val today = LocalDate.now()
        val past = today.minusMonths(2)
        db.insertContribution(Contribution(amount = 100_000, note = "Oylik"))
        db.insertContribution(Contribution(amount = 10_000, note = "Oylik", date = past.startMillis()))
        val old = Expense(description = "Taxi", amount = 80_000, categoryId = food.id, date = today.startMillis(), source = ExpenseSource.manual)
        db.insertExpense(old) // 20 000 left this month
        assertEquals("new row: over by 10 000", 10_000L, shortfallFor(db, settings, old.copy(id = "n", amount = 30_000), null))
        assertEquals("edit same month: only the 25 000 increase counts", 5_000L, shortfallFor(db, settings, old.copy(amount = 105_000), old))
        assertEquals("edit down", 0L, shortfallFor(db, settings, old.copy(amount = 50_000), old))
        assertEquals("moved to a past month: that month's 10 000", 70_000L, shortfallFor(db, settings, old.copy(date = past.startMillis()), old))
    }

    // --- add_edit_test ---

    @Test fun editingUpdatesInPlaceKeepsIdSourceCreatedAt() = runBlocking {
        val (food, other) = db.getCategories().take(2)
        val id = db.insertExpense(Expense(description = "Coffee", amount = 45000, categoryId = food.id, date = LocalDate.of(2026, 7, 1).startMillis(), source = ExpenseSource.manual))
        val original = db.getExpenses().single()
        db.updateExpense(original.copy(description = "Lunch", amount = 90000, categoryId = other.id, date = LocalDate.of(2026, 7, 5).startMillis()))
        val edited = db.getExpenses().single()
        assertEquals("no new row created", id, edited.id)
        assertEquals("Lunch", edited.description)
        assertEquals(90000L, edited.amount)
        assertEquals(other.id, edited.categoryId)
        assertEquals(LocalDate.of(2026, 7, 5), edited.date.toLocalDate())
        assertEquals(original.source, edited.source)
        assertEquals(original.createdAt, edited.createdAt)
        assertTrue(edited.dirty)
    }

    // --- type_mode_test / speak_mode_test ---

    private fun assertSavedCoffee(source: ExpenseSource) {
        waitFor("Qo'shildi: Coffee — 45 000 UZS")
        val row = rows().single()
        val food = runBlocking { db.getCategories() }.first { it.name == "Food & dining" }
        assertEquals(source, row.source)
        assertEquals("Coffee", row.description)
        assertEquals(45000L, row.amount)
        assertEquals("matcher reused, no duplicate", food.id, row.categoryId)
        assertEquals("coffee 45000", row.rawInput)
        assertEquals("AI date, not now", LocalDate.of(2026, 9, 26).startMillis(), row.date)
        assertEquals("no new category", 5, runBlocking { db.getCategories() }.size)
    }

    @Test fun typeModeParsesAndSavesWithSourceTyped() {
        fund()
        show { Add(parsed = aiCoffee) }
        rule.onNodeWithText("Yozish").performClick()
        rule.onNodeWithTag("input").performTextInput("coffee 45000")
        rule.onNodeWithText("AI bilan qo'shish").tap()
        assertSavedCoffee(ExpenseSource.typed)
    }

    @Test fun speakModeTranscribesParsesAndSavesWithSourceVoice() {
        fund()
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show { Add(parsed = aiCoffee, speech = FakeSpeech(context, "coffee 45000")) }
        rule.onNodeWithText("Aytish").performClick()
        rule.onNodeWithContentDescription("Gapirish").performClick()
        rule.onNodeWithText("AI bilan qo'shish").tap()
        assertSavedCoffee(ExpenseSource.voice)
    }

    @Test fun parseFailureDropsIntoManualWithRawText() {
        show { Add(parsed = null) }
        rule.onNodeWithTag("input").performTextInput("nimadir")
        rule.onNodeWithText("AI bilan qo'shish").tap()
        waitFor("Tahlil qilib bo'lmadi — qo'lda to'ldiring")
        rule.onNodeWithText("Qo'lda").assertIsSelected()
        rule.onNodeWithText("nimadir").assertExists()
    }
}
