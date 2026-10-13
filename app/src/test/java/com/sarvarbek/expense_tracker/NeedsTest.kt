package com.sarvarbek.expense_tracker

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.sarvarbek.expense_tracker.data.Need
import com.sarvarbek.expense_tracker.data.NeedStatus
import com.sarvarbek.expense_tracker.data.withStatus
import com.sarvarbek.expense_tracker.features.needs.NeedActions
import com.sarvarbek.expense_tracker.features.needs.NeedsScreen
import com.sarvarbek.expense_tracker.features.needs.validLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Kerakli tab (#110): add sheet, status tabs, who may mark done / reject. */
@RunWith(RobolectricTestRunner::class)
class NeedsTest : ScreenTest() {
    private fun need(text: String, owner: String?, family: String? = "f1", status: String = NeedStatus.NEW) =
        Need(text = text, ownerId = owner, familyId = family).withStatus(status)

    private fun seed() = runBlocking {
        db.insertNeed(need("Olma", "me", family = null))
        db.insertNeed(need("Kitob", "me", status = NeedStatus.DONE))
        db.insertNeed(need("Velosiped", "u2"))
        db.insertNeed(need("Telefon", "u2", status = NeedStatus.REJECTED))
    }

    private suspend fun status(text: String) = db.watchNeeds().first().single { it.text == text }.status

    @Test fun linkRuleMatchesServer() {
        assertTrue(validLink("https://shop.uz/item?id=1"))
        assertTrue(validLink("HTTP://a.uz"))
        assertFalse(validLink("javascript:alert(1)"))
        assertFalse(validLink("https://a b"))
        assertFalse(validLink("https://" + "a".repeat(500)))
    }

    @Test fun rolesMirrorServerGuard() {
        val other = need("x", "u2")
        NeedActions(other, "me", isAdmin = false).run { assertEquals(listOf(false, false, false, false), listOf(done, reject, reopen, delete)) }
        NeedActions(other, "me", isAdmin = true).run { assertEquals(listOf(true, true, false, true), listOf(done, reject, reopen, delete)) }
        NeedActions(need("x", "me"), "me", isAdmin = false).run { assertEquals(listOf(true, false, true), listOf(done, reject, delete)) }
        NeedActions(need("x", null, family = null), "me", isAdmin = true).run { assertFalse("no reject on personal items", reject) }
        NeedActions(need("x", "me", status = NeedStatus.REJECTED), "me", isAdmin = false).run { assertFalse("only admin un-rejects", reopen) }
        NeedActions(need("x", "u2", status = NeedStatus.REJECTED), "me", isAdmin = true).run { assertTrue(reopen) }
    }

    @Test fun addSheetSavesFieldsAndValidatesLink() {
        show { NeedsScreen(db, familyId = "f1", myId = "me", isAdmin = false) }
        waitFor("Ro'yxat bo'sh")
        rule.onNodeWithContentDescription("Kerakli qo'shish").performClick()
        rule.onNodeWithText("Nima kerak?").performTextInput("Velosiped")
        rule.onNodeWithText("Pulim bor, fikr kerak").performClick()
        rule.onNodeWithText("Havola (ixtiyoriy)").performTextInput("ftp://x")
        rule.onNodeWithText("Havola http:// yoki https:// bilan boshlansin").assertExists()
        rule.onNodeWithText("Saqlash").assertIsNotEnabled()
        rule.onNodeWithText("ftp://x").performTextReplacement("https://shop.uz/b")
        rule.onNodeWithText("Saqlash").performClick()
        waitFor("Velosiped")
        val n = runBlocking { db.watchNeeds().first() }.single()
        assertEquals(listOf<Any?>("f1", false, "https://shop.uz/b", NeedStatus.NEW), listOf(n.familyId, n.needMoney, n.link, n.status))
        rule.onNodeWithText("shop.uz").assertExists() // link chip shows the host
        rule.onNodeWithText("Yangi 1").assertExists()
    }

    @Test fun statusTabsFilterAndMemberMarksOnlyOwnDone() {
        seed()
        show { NeedsScreen(db, familyId = "f1", myId = "me", isAdmin = false, names = mapOf("u2" to "Ali")) }
        waitFor("Olma")
        rule.onNodeWithText("Velosiped").assertExists()
        rule.onNodeWithText("Kitob").assertDoesNotExist()
        rule.onNodeWithText("Ali").assertExists() // other member's item names its owner
        // Member: done on own Olma only, never reject; can't delete Ali's item.
        assertEquals(1, rule.onAllNodesWithText("Bajarildi").fetchSemanticsNodes().size)
        rule.onNodeWithText("Rad etish").assertDoesNotExist()
        assertEquals(1, rule.onAllNodesWithContentDescriptionCount("O'chirish"))
        assertTapTargets()

        rule.onNodeWithText("Bajarildi").performClick()
        rule.waitUntil(5000) { runBlocking { status("Olma") } == NeedStatus.DONE }
        assertTrue(runBlocking { db.watchNeeds().first() }.single { it.text == "Olma" }.let { it.done && it.dirty })

        rule.onNodeWithText("Bajarildi 2").performClick()
        waitFor("Kitob")
        rule.onNodeWithText("Olma").assertExists()
        rule.onNodeWithText("Velosiped").assertDoesNotExist()
        rule.onNodeWithText("Rad etildi 1").performClick()
        waitFor("Telefon")
        rule.onNodeWithText("Qayta ochish").assertDoesNotExist() // only the admin un-rejects
        rule.onNodeWithText("Kutilmoqda").performClick()
        waitFor("Bu bo'limda hozircha hech narsa yo'q")
    }

    @Test fun adminRejectsAndMarksOthersDone() {
        seed()
        show { NeedsScreen(db, familyId = "f1", myId = "me", isAdmin = true) }
        waitFor("Velosiped")
        // Reject only on the family item; Olma is personal.
        assertEquals(1, rule.onAllNodesWithText("Rad etish").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("Bajarildi").fetchSemanticsNodes().size)
        rule.onNodeWithText("Rad etish").performClick()
        rule.waitUntil(5000) { runBlocking { status("Velosiped") } == NeedStatus.REJECTED }
        rule.onNodeWithText("Rad etildi 2").performClick()
        waitFor("Telefon")
        assertEquals(2, rule.onAllNodesWithText("Qayta ochish").fetchSemanticsNodes().size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(label: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().size
}
