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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
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
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.features.add.AddScreen
import com.sarvarbek.expense_tracker.features.home.HomeScreen
import com.sarvarbek.expense_tracker.services.ParsedExpense
import com.sarvarbek.expense_tracker.services.SpeechService
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Toaster
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
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
    ) = AddScreen(db, settings, { _, _ -> parsed }, speech, canCreate = { true }, onClose = {}, editing = editing, members = members)

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    private fun rows() = runBlocking { db.getExpenses() }

    private fun waitRows(n: Int) = rule.waitUntil(5000) { rows().size == n }

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
        rule.onNodeWithText("byudjet belgilang", substring = true).assertExists()
        rule.onNodeWithContentDescription("Sozlamalar").assertHeightIsAtLeast(48.dp)
    }

    @Test fun listsOnlyTodayAndShowsMonthLine() {
        val food = runBlocking { db.getCategories() }.first()
        val now = System.currentTimeMillis()
        runBlocking {
            db.insertExpense(Expense(description = "Coffee", amount = 45000, categoryId = food.id, date = now, source = ExpenseSource.manual))
            db.insertExpense(Expense(description = "Old taxi", amount = 30000, categoryId = food.id, date = now - 40L * 86_400_000, source = ExpenseSource.manual))
        }
        settings.setBudget(1_000_000)
        show { HomeScreen(db, settings, onSettings = {}, onEdit = {}) }
        waitFor("Coffee")
        rule.onNodeWithText("Old taxi").assertDoesNotExist()
        rule.onNodeWithText("Bugun hali xarajat yo'q").assertDoesNotExist()
        rule.onNodeWithText("Oy: 45 000 / 1 000 000", substring = true).assertExists()
    }

    // --- add_screen_test ---

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

    @Test fun maxfiyStartsFromSettingsDefaultAndIsSaved() {
        settings.lastAddMode = "manual"
        settings.setDefaultPrivate(true)
        var editing by mutableStateOf<Expense?>(null)
        var visible by mutableStateOf(true)
        show { if (visible) Add(editing = editing) }

        maxfiy().assertIsOn()
        rule.onNodeWithTag("amount").performTextInput("1000")
        rule.onNodeWithTag("desc").performTextInput("Secret")
        pickCategory("Turkum tanlang", "Food & dining")
        rule.onNodeWithText("Saqlash").tap()
        waitRows(1)
        assertTrue(rows().single().isPrivate)

        // Editing keeps the row's own flag; flip it off and save.
        visible = false
        rule.waitForIdle()
        editing = rows().single()
        visible = true
        maxfiy().assertIsOn().performScrollTo().performClick()
        rule.onNodeWithText("Saqlash").tap()
        rule.waitUntil(5000) { !rows().single().isPrivate }
        assertFalse(rows().single().isPrivate)
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
        show { Add(parsed = aiCoffee) }
        rule.onNodeWithText("Yozish").performClick()
        rule.onNodeWithTag("input").performTextInput("coffee 45000")
        rule.onNodeWithText("AI bilan qo'shish").tap()
        assertSavedCoffee(ExpenseSource.typed)
    }

    @Test fun speakModeTranscribesParsesAndSavesWithSourceVoice() {
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
