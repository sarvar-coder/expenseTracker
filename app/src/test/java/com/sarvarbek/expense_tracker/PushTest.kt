package com.sarvarbek.expense_tracker

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.services.Push
import com.sarvarbek.expense_tracker.services.canNotify
import com.sarvarbek.expense_tracker.services.post
import com.sarvarbek.expense_tracker.services.pushContent
import com.sarvarbek.expense_tracker.services.shouldAskNotifications
import com.sarvarbek.expense_tracker.ui.common.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PushTest {
    @Before fun strings() {
        I18n.strings = I18n.parse(File("src/main/assets/i18n/uz.json").readText())
    }

    private val request = mapOf("type" to "category_request", "uid" to "u1", "name" to "Ali", "category" to "Taksi")

    @Test fun requestPushTextAndRoute() {
        val p = pushContent(request, "u1")!!
        assertEquals(Push.CH_REQUESTS, p.channel)
        assertEquals("Ali yangi turkum so'radi: Taksi", p.text)
        assertEquals(Push.ROUTE_REQUESTS, p.route)
    }

    @Test fun resolvedPushText() {
        val base = mapOf("type" to "category_resolved", "uid" to "u1", "category" to "Taksi")
        assertEquals("\"Taksi\" turkumi tasdiqlandi", pushContent(base + ("status" to "approved"), "u1")!!.text)
        assertEquals("\"Taksi\" turkumi rad etildi", pushContent(base + ("status" to "rejected"), "u1")!!.text)
    }

    @Test fun dropsPushForAnotherAccountOrUnknownType() {
        assertNull("signed out", pushContent(request, null))
        assertNull("other account", pushContent(request, "u2"))
        assertNull("newer server type", pushContent(request + ("type" to "something_new"), "u1"))
    }

    private val eid = "0b9a7c1e-3f4d-4e5a-9b8c-7d6e5f4a3b2c"
    private val expense = mapOf(
        "type" to "expense", "uid" to "u1", "id" to eid, "owner" to "o1", "name" to "Ali", "description" to "Non", "amount" to "45000",
    )

    @Test fun expensePushTextRouteAndGroup() {
        val p = pushContent(expense, "u1")!!
        assertEquals(Push.CH_EXPENSES, p.channel)
        assertEquals("Ali: Non — 45 000 UZS", p.text)
        assertEquals("expense/$eid", p.route)
        assertEquals("expenses:o1", p.group)
        assertEquals(p.route, Push.route(Intent().putExtra(Push.EXTRA_ROUTE, p.route)))
        assertNull("no id, nothing to open", pushContent(expense - "id", "u1"))
    }

    @Test fun expensePushesStackUnderOneSummaryPerOwner() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        post(app, pushContent(expense, "u1")!!)
        post(app, pushContent(expense + ("id" to eid.replace('0', '1')), "u1")!!)
        post(app, pushContent(expense + ("id" to eid.replace('0', '2')) + ("owner" to "o2"), "u1")!!)
        val all = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals("3 expenses + 2 summaries", 5, all.size)
        val summaries = all.filter { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(setOf("expenses:o1", "expenses:o2"), summaries.map { it.group }.toSet())
    }

    @Test fun tapRouteIsWhitelisted() {
        assertEquals(Push.ROUTE_REQUESTS, Push.route(Intent().putExtra(Push.EXTRA_ROUTE, Push.ROUTE_REQUESTS)))
        assertNull(Push.route(Intent().putExtra(Push.EXTRA_ROUTE, "settings")))
        assertNull(Push.route(Intent().putExtra(Push.EXTRA_ROUTE, "expense/../settings")))
        assertNull(Push.route(null))
    }

    @Test fun permissionAskedOnceOnAndroid13Plus() {
        assertTrue(shouldAskNotifications(33, granted = false, askedBefore = false))
        assertFalse("pre-13 needs no runtime permission", shouldAskNotifications(32, granted = false, askedBefore = false))
        assertFalse("already granted", shouldAskNotifications(34, granted = true, askedBefore = false))
        assertFalse("never ask again", shouldAskNotifications(34, granted = false, askedBefore = true))
    }

    @Test fun canNotifyFollowsRuntimePermission() { // robolectric sdk 34
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(canNotify(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(canNotify(app))
    }
}
