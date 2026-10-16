package com.sarvarbek.expense_tracker

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.features.auth.authErrorText
import com.sarvarbek.expense_tracker.services.ParsedExpense
import com.sarvarbek.expense_tracker.services.adoptLocal
import com.sarvarbek.expense_tracker.services.applyCategories
import com.sarvarbek.expense_tracker.services.applyExpenses
import com.sarvarbek.expense_tracker.services.familyErrorText
import com.sarvarbek.expense_tracker.services.mergeFeed
import com.sarvarbek.expense_tracker.services.parseOthers
import com.sarvarbek.expense_tracker.services.parseGeminiJson
import com.sarvarbek.expense_tracker.services.parseMember
import com.sarvarbek.expense_tracker.services.FamilyOverview
import com.sarvarbek.expense_tracker.ui.AuthGate
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Ports ai_parser_test, auth_test, sync_test, family_test (logic part). */
@RunWith(RobolectricTestRunner::class)
class BackendTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private val db get() = database.dao()

    @Before fun setUp() {
        database = AppDatabase.open(context, name = null)
    }

    // --- ai_parser_test ---
    private val today = LocalDate.of(2026, 9, 27)
    private fun pj(text: String) = parseGeminiJson(text, today)

    @Test fun parsesCleanJson() {
        assertEquals(ParsedExpense("Coffee", 45000, "Food & dining", today), pj("""{"item":"Coffee","amount":45000,"category":"Food & dining"}"""))
    }

    @Test fun parsesFencedJsonAndStringAmounts() {
        assertEquals(2000L, pj("```json\n{\"item\":\"Bus\",\"amount\":2000,\"category\":\"Transport\"}\n```")?.amount)
        assertEquals(128000L, pj("""{"item":"Lunch","amount":"128,000","category":"Food"}""")?.amount)
        assertEquals(10000L, pj("""{"item":"qurt","amount":"10 000","category":"Oziq-ovqat"}""")?.amount)
    }

    @Test fun rejectsMissingOrBadFields() {
        assertNull(pj("""{"item":"x","category":"Food"}"""))
        assertNull(pj("""{"item":"x","amount":"free","category":"Food"}"""))
        assertNull(pj("""{"item":"","amount":100,"category":"Food"}"""))
        assertNull(pj("not json at all"))
    }

    @Test fun dateMissingGarbageOrFutureBecomesToday() {
        val base = """"item":"qurt","amount":10000,"category":"Oziq-ovqat""""
        assertEquals(LocalDate.of(2026, 9, 26), pj("""{$base,"date":"2026-09-26"}""")?.date)
        assertEquals(today, pj("{$base}")?.date)
        assertEquals(today, pj("""{$base,"date":"kecha"}""")?.date)
        assertEquals(today, pj("""{$base,"date":"2026-10-01"}""")?.date)
    }

    // --- auth_test ---
    @Test fun authErrorTextMapsCodesAndNetwork() {
        assertEquals("Email yoki parol noto'g'ri", authErrorText("invalid_credentials", "x"))
        assertEquals("Kod noto'g'ri yoki eskirgan", authErrorText("otp_expired", "x"))
        assertEquals(
            "Juda tez. 42 soniyadan so'ng qayta urining",
            authErrorText("over_email_send_rate_limit", "For security purposes, you can only request this after 42 seconds."),
        )
        assertEquals(
            "Soatlik email limiti tugadi. 1 soatgacha kuting, so'ng qayta urining",
            authErrorText("over_email_send_rate_limit", "email rate limit exceeded"),
        )
        assertEquals("Server said no", authErrorText("unexpected", "Server said no"))
        assertEquals("Internet aloqasini tekshiring", authErrorText(IOException("offline")))
        assertEquals("Xatolik yuz berdi. Qayta urining", authErrorText(IllegalStateException("x")))
    }

    @get:Rule val rule = createComposeRule()

    @Test fun signedOutGateShowsSignInAndValidatesBeforeAnyCall() {
        val auth = (context as App).container.supabase.auth
        rule.setContent { AppTheme { AuthGate(SessionStatus.NotAuthenticated(), auth) { Text("Asosiy") } } }

        rule.onNodeWithText("Hisobingizga kiring").assertExists()
        rule.onNodeWithText("Asosiy").assertDoesNotExist()

        // Invalid input never reaches Supabase.
        rule.onNode(hasText("Kirish") and hasClickAction()).performClick()
        rule.onNodeWithText("To'g'ri email kiriting").assertExists()
        rule.onNodeWithText("Kamida 6 belgi").assertExists()

        rule.onNodeWithText("Parolni unutdingizmi?").performScrollTo().performClick()
        rule.onNodeWithText("Emailingizga 6 xonali kod yuboramiz").assertExists()
        rule.onNodeWithText("Parol").assertDoesNotExist()

        rule.onNodeWithText("Kirish sahifasiga qaytish").performScrollTo().performClick()
        rule.onNodeWithText("Hisobingiz yo'qmi? Ro'yxatdan o'ting").performScrollTo().performClick()
        rule.onNodeWithText("Yangi hisob yarating").assertExists()
    }

    // --- sync_test ---
    private suspend fun addExpense(categoryId: String) = db.insertExpense(
        Expense(description = "Non", amount = 5000, categoryId = categoryId, date = 0, source = ExpenseSource.manual),
    )

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun serverCategory(id: String, name: String, owner: String? = "u1", family: String? = null): JsonObject = obj(
        """{"id":"$id","owner_id":${owner?.let { "\"$it\"" }},"family_id":${family?.let { "\"$it\"" }},"name":"$name",
        "icon_key":"category","color_hex":"E08A5B","is_archived":false,"updated_at":"2026-09-01T00:00:00Z","deleted_at":null}""",
    )

    @Test fun adoptLocalMergesSeededTwinsAndClaimsTheRest() = runBlocking {
        val food = db.getCategories().first { it.name == "Food & dining" }
        val expenseId = addExpense(food.id)
        applyCategories(database, listOf(serverCategory("srv-food", "food & DINING ")))

        adoptLocal(database, "u1", null)

        val cats = db.getAllCategories()
        assertEquals("local twin merged away", listOf("srv-food"), cats.filter { "food" in it.name.lowercase() }.map { it.id })
        assertTrue(cats.all { it.ownerId == "u1" })
        val e = db.getExpenses().single()
        assertEquals(expenseId, e.id)
        assertEquals(Triple("srv-food", "u1", true), Triple(e.categoryId, e.ownerId, e.dirty))
    }

    @Test fun adoptLocalMergesUnpushedOwnedCategoryIntoPulledTwin() = runBlocking {
        val local = db.insertCategory(Category(name = "Kino", colorHex = "AAAAAA", ownerId = "u1")) // claimed, push refused
        val expenseId = addExpense(local)
        applyCategories(database, listOf(serverCategory("srv-kino", "kino ")))

        adoptLocal(database, "u1", "fam")

        assertEquals(listOf("srv-kino"), db.getAllCategories().filter { "kino" in it.name.lowercase() }.map { it.id })
        assertTrue("tombstone pushed", db.dirtyCategories().single { it.id == local }.deletedAt != null)
        assertEquals("srv-kino", db.getExpenses().single { it.id == expenseId }.categoryId)
    }

    @Test fun adoptLocalInFamilyKeepsCategoriesPersonal() = runBlocking {
        val gym = db.insertCategory(Category(name = "Gym", colorHex = "AAAAAA"))
        val expenseId = addExpense(gym)

        adoptLocal(database, "u1", "fam")

        assertTrue(db.getAllCategories().all { it.ownerId == "u1" && it.familyId == null })
        val e = db.getExpenses().single { it.id == expenseId }
        assertEquals(Triple(gym, "u1", "fam"), Triple(e.categoryId, e.ownerId, e.familyId))
        assertNull(e.pendingCategory)
    }

    @Test fun applyExpensesNewerServerWinsUnpushedNewerLocalKept() = runBlocking {
        val cat = db.getCategories().first()
        val id = addExpense(cat.id)
        val local = db.getExpenses().single()
        fun row(desc: String, updated: Long) = obj(
            """{"id":"$id","owner_id":"u1","family_id":null,"category_id":"${cat.id}","description":"$desc","amount":7000,
            "date":"2026-09-01T00:00:00+00:00","source":"manual","raw_input":null,"is_private":false,"pending_category":null,
            "frozen":false,"created_at":"2026-09-01T00:00:00Z","updated_at":"${Instant.ofEpochMilli(updated)}","deleted_at":null}""",
        )

        applyExpenses(database, listOf(row("stale", local.updatedAt - 3_600_000)))
        assertEquals("Non", db.getExpenses().single().description)

        applyExpenses(database, listOf(row("server", local.updatedAt + 3_600_000)))
        val e = db.getExpenses().single()
        assertEquals(Triple("server", 7000L, false), Triple(e.description, e.amount, e.dirty))
    }

    @Test fun parsesFamilySummaryOldAndNewServers() {
        fun row(extra: String) = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(
            """{"user_id":"u1","display_name":"Ali","role":"admin","shared_total":300,"contribution":1000,"title":null$extra}""",
        )
        // 20261013120000: B got 200 from a transfer, gave 100 away.
        val m = parseMember(row(""",
            "own_contribution":800,"received":200,"given":100,"available":600"""))
        assertEquals(listOf(800L, 200L, 100L, 600L), listOf(m.ownContribution, m.received, m.given, m.available))
        // Older server: no Hissa columns, derived from contribution/shared.
        val old = parseMember(row(""))
        assertEquals(listOf(1000L, 0L, 0L, 700L), listOf(old.ownContribution, old.received, old.given, old.available))
        val f = FamilyOverview("f", "Uy", "u1", true, listOf(m, m.copy(userId = "u2", ownContribution = 0, contribution = 100)))
        assertEquals("family budget counts own contributions only", 800L, f.budget)
    }

    // --- family_test (logic) ---
    @Test fun hideFamilyCategoriesHidesEveryFamilyRow() = runBlocking {
        db.insertCategory(Category(name = "Old", colorHex = "AAAAAA", familyId = "old"))
        db.insertCategory(Category(name = "Cur", colorHex = "AAAAAA", familyId = "cur"))
        db.insertCategory(Category(name = "Mine", colorHex = "AAAAAA", ownerId = "u1"))

        db.hideFamilyCategories()

        val names = db.getCategories().map { it.name }.toSet()
        assertFalse("Old" in names || "Cur" in names)
        assertTrue("Mine" in names)
        assertTrue("never pushed", db.allCategoryRows().filter { it.deletedAt != null }.none { it.dirty })
    }

    @Test fun familyErrorTextMapsServerCodes() {
        assertTrue("admin" in familyErrorText("transfer_admin_first"))
        assertTrue("admin" in familyErrorText("last_admin"))
        assertTrue("taklif qilingan" in familyErrorText("duplicate key", "23505"))
        assertTrue("Qayta" in familyErrorText("boom"))
    }

    @Test fun familyFeedMergesMineAndOthersSkipsHiddenDedupesById() {
        fun row(id: String, date: String?, deletedAt: String? = null) = Json.parseToJsonElement(
            if (date == null) """{"id":"$id","owner_id":"u2","owner_name":"Vali","category_id":null,"description":null,"amount":null,"date":null,"is_private":true,"frozen":false,"deleted_at":null}"""
            else """{"id":"$id","owner_id":"u2","owner_name":"Vali","category_id":"c1","description":"d","amount":5000,"date":"$date","source":"voice","raw_input":"d 5000","is_private":false,"frozen":false,"deleted_at":${deletedAt?.let { "\"$it\"" }},"category_name":"Ovqat","category_color":"E08A5B","category_icon":"category"}""",
        ).jsonObject
        val others = parseOthers(listOf(
            row("o1", "2026-10-01T10:00:00Z"),
            row("t1", null), // transfer tombstone
            row("o2", "2026-10-02T10:00:00Z", deletedAt = "2026-10-03T10:00:00Z"),
            row("dup", "2026-10-01T09:00:00Z"),
        ), "f1")
        assertEquals(listOf("o1", "dup"), others.map { it.expense.id })
        assertTrue(others.all { !it.mine && it.ownerName == "Vali" && it.expense.familyId == "f1" })
        assertTrue(others.all { it.expense.source == ExpenseSource.voice && it.expense.rawInput == "d 5000" && !it.editable })
        assertTrue("owner's category from the feed", others.all { it.categoryName == "Ovqat" && it.categoryColor == "E08A5B" && it.categoryIcon == "category" })

        val own = listOf(
            Expense(id = "m1", description = "Non", amount = 1, categoryId = "c1", date = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli(), source = ExpenseSource.manual),
            Expense(id = "dup", description = "Mine", amount = 2, categoryId = "c1", date = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), source = ExpenseSource.manual),
        )
        val feed = mergeFeed(own, others, "Siz")
        assertEquals(listOf("m1", "o1", "dup"), feed.map { it.expense.id }) // newest first
        assertEquals(listOf(true, false, true), feed.map { it.mine }) // own copy wins the duplicate
        assertEquals("Mine", feed.last().expense.description)
        assertEquals(own.map { it.id }, mergeFeed(own, emptyList(), "Siz").map { it.expense.id }) // not in a family
        assertEquals(listOf("u1", "u2", "u1"), mergeFeed(own, others, "Siz", "u1").map { it.expense.ownerId }) // unsynced own rows get my id
    }
}
