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
import com.sarvarbek.expense_tracker.services.applyNeeds
import com.sarvarbek.expense_tracker.data.Need
import com.sarvarbek.expense_tracker.services.familyErrorText
import com.sarvarbek.expense_tracker.services.mergeFeed
import com.sarvarbek.expense_tracker.services.parseOthers
import com.sarvarbek.expense_tracker.services.parseGeminiJson
import com.sarvarbek.expense_tracker.services.shouldPushProfile
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

    @Test fun adoptLocalInFamilyGivesCategoriesToFamily() = runBlocking {
        adoptLocal(database, "u1", "fam")
        assertTrue(db.getAllCategories().all { it.familyId == "fam" && it.ownerId == null })
    }

    @Test fun adoptLocalAsMemberTurnsUnknownCategoriesIntoRequests() = runBlocking {
        val gym = db.insertCategory(Category(name = "Gym", colorHex = "AAAAAA"))
        val expenseId = addExpense(gym)
        applyCategories(database, listOf(serverCategory("srv-boshqa", "Boshqa", owner = null, family = "fam")))

        adoptLocal(database, "u1", "fam", canCreate = false)

        val e = db.getExpenses().first { it.id == expenseId }
        assertEquals(Triple("srv-boshqa", "Gym", true), Triple(e.categoryId, e.pendingCategory, e.dirty))
        assertEquals("member creates no categories", listOf("srv-boshqa"), db.getAllCategories().map { it.id })
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

    @Test fun applyNeedsNewerWinsAndForeignFamilyItemsDrop() = runBlocking {
        val local = Need(text = "Non", familyId = "f1").also { db.insertNeed(it) }
        fun row(id: String, text: String, updated: Long, family: String?) = obj(
            """{"id":"$id","owner_id":"u2","family_id":${family?.let { "\"$it\"" }},"text":"$text","done":true,
            "created_at":"2026-09-01T00:00:00Z","updated_at":"${Instant.ofEpochMilli(updated)}","deleted_at":null}""",
        )
        applyNeeds(database, listOf(row(local.id, "stale", local.updatedAt - 1000, "f1"), row("n2", "Sut", local.updatedAt, "f1")))
        assertEquals(setOf("Non", "Sut"), db.watchNeeds().first().map { it.text }.toSet())

        applyNeeds(database, listOf(row(local.id, "Non 2", local.updatedAt + 1000, "f1")))
        val n = db.watchNeeds().first().single { it.id == local.id }
        assertEquals(Triple("Non 2", true, false), Triple(n.text, n.done, n.dirty))

        db.insertNeed(Need(text = "Mine"))
        db.dropForeignNeeds(null) // left the family
        assertEquals(listOf("Mine"), db.watchNeeds().first().map { it.text })
    }

    @Test fun shouldPushProfileLocalEditsAndUnsyncedBudgetsWin() {
        assertTrue(shouldPushProfile(dirty = true, serverBudget = null, localBudget = 0))
        assertTrue(shouldPushProfile(dirty = false, serverBudget = 0, localBudget = 900000))
        assertFalse(shouldPushProfile(dirty = false, serverBudget = 500000, localBudget = 900000))
        assertFalse(shouldPushProfile(dirty = false, serverBudget = 0, localBudget = 0))
    }

    // --- family_test (logic) ---
    @Test fun hideForeignCategoriesHidesOnlyOtherFamilies() = runBlocking {
        db.insertCategory(Category(name = "Old", colorHex = "AAAAAA", familyId = "old"))
        db.insertCategory(Category(name = "Cur", colorHex = "AAAAAA", familyId = "cur"))
        db.insertCategory(Category(name = "Mine", colorHex = "AAAAAA", ownerId = "u1"))

        db.hideForeignCategories("cur")
        var names = db.getCategories().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Cur", "Mine")))
        assertFalse("Old" in names)

        db.hideForeignCategories(null) // left every family
        names = db.getCategories().map { it.name }.toSet()
        assertFalse("Cur" in names)
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
            else """{"id":"$id","owner_id":"u2","owner_name":"Vali","category_id":"c1","description":"d","amount":5000,"date":"$date","source":"voice","raw_input":"d 5000","is_private":false,"frozen":false,"deleted_at":${deletedAt?.let { "\"$it\"" }}}""",
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
