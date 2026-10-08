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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.features.family.FamilyScreen
import com.sarvarbek.expense_tracker.features.insights.CategoryPick
import com.sarvarbek.expense_tracker.features.family.FamilySettingsScreen
import com.sarvarbek.expense_tracker.features.family.FamilyState
import com.sarvarbek.expense_tracker.features.settings.CategoriesScreen
import com.sarvarbek.expense_tracker.features.settings.SettingsScreen
import com.sarvarbek.expense_tracker.services.CategoryRequest
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.FamilyInvite
import com.sarvarbek.expense_tracker.services.FamilyMember
import com.sarvarbek.expense_tracker.services.FamilyOverview
import com.sarvarbek.expense_tracker.services.mergeFeed
import com.sarvarbek.expense_tracker.ui.common.ExpenseTile
import com.sarvarbek.expense_tracker.ui.common.I18n
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
    @Test fun settingsShowsUzbekRows() {
        val store = SettingsStore(prefs).apply { setDisplayName("Ali") }
        show { SettingsScreen(store, db, "me@oila.uz", {}, {}, {}) }

        rule.onNodeWithText("Sozlamalar").assertExists()
        rule.onNodeWithText("Ovoz tili").assertExists()
        rule.onNodeWithContentDescription("Orqaga").assertExists()
        assertTrue(rule.onAllNodesWithText("Valyuta").fetchSemanticsNodes().isEmpty())
        assertTapTargets()

        assertTrue(rule.onAllNodesWithText("Yangi xarajatlar maxfiy").fetchSemanticsNodes().isEmpty())

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
        rule.onNodeWithText("Ism").assertExists()
        rule.onNodeWithText("Ali").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Ma'lumotni eksport (CSV)"))
        rule.onNodeWithText("Ma'lumotni eksport (CSV)").assertExists()
    }

    @Test fun languageRowSwitchesUiToCyrillic() {
        val store = SettingsStore(prefs)
        show { SettingsScreen(store, db, "me@oila.uz", {}, {}, {}) }
        try {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("O'zbekcha (lotin)"))
            rule.onNodeWithText("O'zbekcha (lotin)").performClick()
            rule.onNodeWithText("Ўзбекча (кирилл)").performClick()
            waitFor("Созламалар")
            assertEquals("uz_cyrl", store.settings.value.uiLanguage)
        } finally {
            I18n.load(context, I18n.LATIN)
        }
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
        rule.onNodeWithText("Kerakli").assertExists() // local list still reachable offline
    }

    @Test fun needsAddTickAndFilter() {
        show { com.sarvarbek.expense_tracker.features.family.NeedsScreen(db, familyId = "f1", onSync = {}) {} }
        waitFor("Ro'yxat bo'sh")
        rule.onNodeWithText("Nima kerak?").performTextInput("Non")
        rule.onNodeWithContentDescription("Qo'shish").performClick()
        waitFor("Non")
        rule.onNodeWithText("Oila uchun").performClick() // next one personal
        rule.onNodeWithText("Nima kerak?").performTextInput("Paypoq")
        rule.onNodeWithContentDescription("Qo'shish").performClick()
        waitFor("Paypoq")
        val rows = runBlocking { db.watchNeeds().first() }.associateBy { it.text }
        assertEquals("f1", rows.getValue("Non").familyId)
        assertEquals(null, rows.getValue("Paypoq").familyId)

        rule.onNodeWithText("Non").performClick() // tick
        rule.waitUntil(5000) { runBlocking { db.watchNeeds().first() }.single { it.text == "Non" }.done }

        rule.onNodeWithText("Mening").performClick()
        rule.onNodeWithText("Non").assertDoesNotExist()
        rule.onNodeWithText("Paypoq").assertExists()
        assertTapTargets()
    }

    @Test fun inFamilyShowsBudgetMembersSharedListAndRequests() {
        val cat = runBlocking { db.getCategories().first { it.name == "Groceries" }.id }
        runBlocking {
            fun e(desc: String, family: String?, legacyPrivate: Boolean = false) =
                Expense(description = desc, amount = 10_000, categoryId = cat, date = System.currentTimeMillis(), source = ExpenseSource.manual, familyId = family, isPrivate = legacyPrivate)
            db.insertExpense(e("Non", "f1"))
            db.insertExpense(e("Sovg'a", "f1", legacyPrivate = true))
            db.insertExpense(e("Shaxsiy", null))
        }
        val overview = FamilyOverview(
            "f1", "Uy", "u1", isAdmin = true,
            members = listOf(FamilyMember("u1", "Ali", true, 10_000, 1_000_000, title = "Ota"), FamilyMember("u2", "Vali", false, 20_000, 500_000)),
            requests = listOf(CategoryRequest("r1", "Dorilar")),
        )
        val others = listOf(FamilyExpense(Expense(id = "o1", description = "Taksi", amount = 20_000, categoryId = cat, date = System.currentTimeMillis(), source = ExpenseSource.manual, ownerId = "u2"), "Vali"))
        val feed = db.watchExpenses().map { mergeFeed(it, others, "Siz") }
        var edited: Expense? = null
        var pick: CategoryPick? = null
        show { FamilyScreen(FamilyState(null, load = { overview }, loadInvites = { emptyList() }, feed = feed), db, onEdit = { edited = it }, onCategory = { pick = it }) }

        waitFor("Non")
        rule.onNodeWithText("Uy").assertExists() // title = family name
        rule.onNodeWithText("Byudjet: 1 500 000").assertExists()
        rule.onNodeWithText("Ali (siz) · Ota · admin").assertExists()
        rule.onAllNodesWithText("Ali · Ota", substring = true).onFirst().assertExists() // own shared row's owner label
        rule.onNodeWithText("Taksi").assertExists()
        rule.onNodeWithText("Sovg'a").assertExists() // old private row: no private any more, shared
        assertTrue("personal, not in the family", rule.onAllNodesWithText("Shaxsiy").fetchSemanticsNodes().isEmpty())
        // management lives behind the gear now
        assertTrue(rule.onAllNodesWithText("Oilani o'chirish").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Oila sozlamalari").assertExists()
        rule.onNodeWithContentDescription("Jami 40 000 UZS").assertExists() // donut over the shared list

        // Category tap: the pick narrows the feed to exactly the Oila list (family rows, member labels).
        rule.onNodeWithContentDescription("Groceries, 40 000 UZS, 100%").performSemanticsAction(SemanticsActions.OnClick)
        val picked = runBlocking { pick!!.select(feed.first()) }
        assertEquals(cat, pick!!.categoryId)
        assertEquals(setOf("Non", "Sovg'a", "Taksi"), picked.map { it.expense.description }.toSet())
        assertEquals(40_000L, picked.sumOf { it.expense.amount })
        assertTapTargets()

        // Tap routing: own row opens edit, Vali's opens the read-only sheet.
        // Rows sit below the fold in the lazy column: click via semantics.
        rule.onNodeWithText("Non").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("Non", edited?.description)
        edited = null
        rule.onNodeWithText("Taksi").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Kim qo'shgan").assertExists()
        assertEquals(null, edited)
        rule.onNodeWithText("Yopish").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Kim qo'shgan").fetchSemanticsNodes().isEmpty() }

        // Filter by member: only Vali's row stays; Ko'rinish is hidden (all shared).
        rule.onNodeWithContentDescription("Filtr").performClick()
        assertTrue(rule.onAllNodesWithText("Ko'rinish").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("A'zo").performClick()
        rule.onNode(hasText("Vali") and hasClickAction()).performClick() // the sheet row, not the member row
        rule.onNodeWithText("Tayyor").performClick()
        rule.onNodeWithText("Qo'llash").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Non").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Taksi").assertExists()
        rule.onNodeWithContentDescription("Jami 20 000 UZS").assertExists()
    }

    private fun overview(admin: Boolean) = FamilyOverview(
        "f1", "Uy", "u1", isAdmin = admin,
        members = listOf(FamilyMember("u1", "Ali", admin, 0, 0), FamilyMember("u2", "Vali", !admin, 0, 0)),
        requests = if (admin) listOf(CategoryRequest("r1", "Dorilar")) else emptyList(),
    )

    @Test fun adminFamilySettingsManageMembersAndDelete() {
        val state = FamilyState(null, load = { overview(admin = true) }, loadInvites = { emptyList() })
        show {
            LaunchedEffect(Unit) { state.refresh() }
            if (state.loaded) FamilySettingsScreen(state) {}
        }
        waitFor("Dorilar")
        rule.onNodeWithText("Oilani o'chirish").assertExists()
        rule.onNodeWithText("Oiladan chiqish").assertExists()
        rule.onNodeWithText("A'zo taklif qilish").assertExists()
        rule.onNodeWithContentDescription("Amallar: Vali").performClick()
        rule.onNodeWithText("Admin qilish").assertExists()
        rule.onNodeWithText("Oiladan chiqarish").assertExists()
        rule.onNodeWithText("Oiladagi o'rni").assertExists()
    }

    @Test fun memberFamilySettingsCanOnlyLeave() {
        val state = FamilyState(null, load = { overview(admin = false) }, loadInvites = { emptyList() })
        show {
            LaunchedEffect(Unit) { state.refresh() }
            if (state.loaded) FamilySettingsScreen(state) {}
        }
        waitFor("Oiladan chiqish")
        assertTrue(rule.onAllNodesWithText("Oilani o'chirish").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("A'zo taklif qilish").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("Amallar: Vali").fetchSemanticsNodes().isEmpty())
        // own row too: only the admin sets titles (#72)
        assertTrue(rule.onAllNodesWithContentDescription("Amallar: Ali").fetchSemanticsNodes().isEmpty())
    }

    @Test fun frozenTileOpensReadOnlySheet() {
        val frozen = Expense(description = "Non", amount = 5_000, categoryId = "c", date = System.currentTimeMillis(), source = ExpenseSource.voice, rawInput = "non besh ming", frozen = true)
        var opened = false
        show { ExpenseTile(FamilyExpense(frozen, "Siz", mine = true), null, db) { opened = true } }

        rule.onNodeWithContentDescription("Faqat o'qish uchun").assertExists()
        rule.onNodeWithText("Non").performClick()
        assertFalse("frozen never opens edit", opened)
        rule.onNodeWithText("Kim qo'shgan").assertExists()
        rule.onNodeWithText("non besh ming").assertExists() // raw input
        rule.onNodeWithText("Ovozli").assertExists() // source
        assertTrue("no delete in the sheet", rule.onAllNodesWithText("O'chirish").fetchSemanticsNodes().isEmpty())
        assertTapTargets()
        rule.onNodeWithText("Yopish").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Kim qo'shgan").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun ownTileOpensEditOthersOpenReadOnlyWithName() {
        val e = Expense(description = "Non", amount = 5_000, categoryId = "c", date = System.currentTimeMillis(), source = ExpenseSource.manual)
        var edited: Expense? = null
        show {
            androidx.compose.foundation.layout.Column {
                ExpenseTile(FamilyExpense(e, "Siz", mine = true), null, db) { edited = it }
                ExpenseTile(FamilyExpense(e.copy(id = "o1", description = "Taksi", ownerId = "u2"), "Vali"), null, db) { edited = it }
            }
        }
        rule.onNodeWithText("Non").performClick()
        assertEquals(e.id, edited?.id)
        edited = null
        rule.onNodeWithText("Taksi").performClick()
        rule.onNodeWithText("Vali").assertExists()
        assertEquals("others never open edit", null, edited)
    }
}
