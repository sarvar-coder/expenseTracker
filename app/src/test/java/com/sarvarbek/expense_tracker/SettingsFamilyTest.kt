package com.sarvarbek.expense_tracker

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.features.family.FamilyScreen
import com.sarvarbek.expense_tracker.features.family.FamilyState
import com.sarvarbek.expense_tracker.features.settings.CategoriesScreen
import com.sarvarbek.expense_tracker.features.settings.SettingsScreen
import com.sarvarbek.expense_tracker.services.CategoryRequest
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.FamilyInvite
import com.sarvarbek.expense_tracker.services.FamilyMember
import com.sarvarbek.expense_tracker.services.FamilyOverview
import com.sarvarbek.expense_tracker.ui.common.ExpenseTile
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Toaster
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
import org.robolectric.annotation.Config

/** Ports settings_screen_test, family_screen_test, the frozen tile of family_test, and a11y_test sizes. */
/** Shared Robolectric setup: in-memory DB, cleared prefs, themed host with a toaster. */
abstract class ScreenTest {
    @get:Rule val rule = createComposeRule()
    protected val context = ApplicationProvider.getApplicationContext<Context>()
    protected val prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE)
    protected lateinit var database: AppDatabase
    protected val db get() = database.dao()

    @Before fun setUp() {
        prefs.edit().clear().commit()
        database = AppDatabase.open(context, name = null)
    }

    protected fun show(content: @Composable () -> Unit) = rule.setContent {
        AppTheme {
            CompositionLocalProvider(LocalToaster provides Toaster(remember { SnackbarHostState() }, rememberCoroutineScope())) { content() }
        }
    }

    protected fun waitFor(text: String) =
        rule.waitUntil(5000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** a11y_test port: every tappable node is ≥48dp and has a label. */
    protected fun assertTapTargets() {
        val density = rule.density.density
        for (node in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val b = node.touchBoundsInRoot
            if (b.isEmpty) continue // scrolled out of view
            val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() +
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            assertTrue("too small: $label ${b.width / density}x${b.height / density}", b.width / density >= 47.5f && b.height / density >= 47.5f)
            assertTrue("unlabelled tap target at $b", label.replace("null", "").isNotBlank() || node.config.contains(SemanticsActions.SetText))
        }
    }

}

// A text-field dialog never goes idle on a custom-qualifier screen here, so default size.
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest : ScreenTest() {
    @Test fun settingsShowsUzbekRowsAndTogglesPrivateDefault() {
        val store = SettingsStore(prefs)
        show { SettingsScreen(store, db, "me@oila.uz", {}, {}, {}) }

        rule.onNodeWithText("Sozlamalar").assertExists()
        rule.onNodeWithText("Ovoz tili").assertExists()
        rule.onNodeWithContentDescription("Orqaga").assertExists()
        assertTrue(rule.onAllNodesWithText("Valyuta").fetchSemanticsNodes().isEmpty())
        assertTapTargets()

        rule.onNodeWithText("Yangi xarajatlar maxfiy").performClick()
        rule.waitForIdle()
        assertTrue(prefs.getBoolean("defaultPrivate", false))

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Bepul tarif", substring = true))
        rule.onNodeWithText("O'zbekcha").performClick()
        rule.onNodeWithText("Ruscha").performClick()
        waitFor("Ruscha")
        assertEquals("ru_RU", store.settings.value.sttLocale)

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Belgilanmagan"))
        rule.onNodeWithText("Belgilanmagan").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("4000000")
        rule.onNodeWithText("Saqlash").performClick()
        waitFor("4 000 000 UZS")
        assertEquals(4_000_000L, store.settings.value.monthlyBudget)

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("me@oila.uz"))
        rule.onNodeWithText("Ma'lumotni eksport (CSV)").assertExists()
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class SettingsFamilyTest : ScreenTest() {
    @Test fun categoriesSplitActiveAndArchived() {
        runBlocking { db.insertCategory(Category(name = "Kitob", colorHex = "5B8DB8", isArchived = true)) }
        show { CategoriesScreen(db, canEdit = true) {} }

        waitFor("Kitob")
        rule.onNodeWithText("Faol").assertExists()
        rule.onNodeWithText("Arxivlangan").assertExists()
        rule.onNodeWithContentDescription("Arxivdan chiqarish").assertExists()
        assertTrue(rule.onAllNodesWithContentDescription("Arxivlash").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Turkum qo'shish").assertExists()
        assertTapTargets()

        rule.onNodeWithContentDescription("Arxivdan chiqarish").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Arxivlangan").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun memberCannotEditCategories() {
        show { CategoriesScreen(db, canEdit = false) {} }
        waitFor("Transport")
        assertTrue(rule.onAllNodesWithContentDescription("Turkum qo'shish").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("Arxivlash").fetchSemanticsNodes().isEmpty())
    }

    @Test fun familyBudgetIsSumOfContributions() {
        val f = FamilyOverview(
            "f1", "Uy", "u1", false,
            members = listOf(FamilyMember("u1", "Ali", true, 300_000, 1_000_000), FamilyMember("u2", "Vali", false, 200_000, -50_000)),
            others = emptyList(),
        )
        assertEquals(950_000L, f.budget)
        assertEquals(500_000L, f.spent)
    }

    @Test fun notInFamilyShowsInvitesAndCreate() {
        val state = FamilyState(null, load = { null }, loadInvites = { listOf(FamilyInvite("i1", "Uy", "Ali")) })
        show { FamilyScreen(state, db) }

        waitFor("“Uy” oilasiga taklif")
        rule.onNodeWithText("Siz hali oilada emassiz").assertExists()
        rule.onNodeWithText("Qabul qilish").assertExists()
        rule.onNodeWithText("Oila yaratish").assertExists()
        assertTapTargets()
    }

    @Test fun offlineFamilyShowsError() {
        show { FamilyScreen(FamilyState(null, load = { error("offline") }, loadInvites = { emptyList() }), db) }
        waitFor("Oila ma'lumotini yuklab bo'lmadi")
    }

    @Test fun inFamilyShowsBudgetMembersSharedListAndRequests() {
        runBlocking {
            val cat = db.getCategories().first { it.name == "Groceries" }.id
            fun e(desc: String, family: String?, private: Boolean = false) =
                Expense(description = desc, amount = 10_000, categoryId = cat, date = System.currentTimeMillis(), source = ExpenseSource.manual, familyId = family, isPrivate = private)
            db.insertExpense(e("Non", "f1"))
            db.insertExpense(e("Sovg'a", "f1", private = true))
            db.insertExpense(e("Shaxsiy", null))
        }
        val overview = FamilyOverview(
            "f1", "Uy", "u1", isAdmin = true,
            members = listOf(FamilyMember("u1", "Ali", true, 10_000, 1_000_000), FamilyMember("u2", "Vali", false, 20_000, 500_000)),
            others = listOf(FamilyExpense("o1", "Vali", "c1", "Taksi", 20_000, System.currentTimeMillis())),
            requests = listOf(CategoryRequest("r1", "Dorilar")),
        )
        show { FamilyScreen(FamilyState(null, load = { overview }, loadInvites = { emptyList() }), db) }

        waitFor("Non")
        rule.onNodeWithText("Uy").assertExists() // title = family name
        rule.onNodeWithText("Byudjet: 1 500 000").assertExists()
        rule.onNodeWithText("Ali (siz) · admin").assertExists()
        rule.onNodeWithText("Taksi").assertExists()
        assertTrue("private stays hidden", rule.onAllNodesWithText("Sovg'a").fetchSemanticsNodes().isEmpty())
        assertTrue("personal, not in the family", rule.onAllNodesWithText("Shaxsiy").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Dorilar").assertExists()
        rule.onNodeWithText("Oilani o'chirish").assertExists()
        rule.onNodeWithContentDescription("Amallar").assertExists() // only on Vali, not self
        assertTapTargets()
    }

    @Test fun frozenTileCannotBeOpened() {
        val frozen = Expense(description = "Non", amount = 5_000, categoryId = "c", date = System.currentTimeMillis(), source = ExpenseSource.manual, frozen = true)
        var opened = false
        show { ExpenseTile(frozen, null, db) { opened = true } }

        rule.onNodeWithContentDescription("Faqat o'qish uchun").assertExists()
        rule.onNode(hasClickAction() and hasText("Non", substring = true)).assertIsNotEnabled()
        rule.onNodeWithText("Non").performClick()
        assertFalse(opened)
    }
}
