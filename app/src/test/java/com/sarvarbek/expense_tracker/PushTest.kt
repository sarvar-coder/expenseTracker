package com.sarvarbek.expense_tracker

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.SettingsStore
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

    @Test fun dropsPushForAnotherAccountOrUnknownType() {
        assertNull("signed out", pushContent(expense, null))
        assertNull("other account", pushContent(expense, "u2"))
        assertNull("newer server type", pushContent(expense + ("type" to "something_new"), "u1"))
        // category requests are gone (2.3.0): an old queued push is dropped
        assertNull(pushContent(mapOf("type" to "category_request", "uid" to "u1", "name" to "Ali", "category" to "Taksi"), "u1"))
        assertNull(pushContent(mapOf("type" to "category_resolved", "uid" to "u1", "category" to "Taksi", "status" to "approved"), "u1"))
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
        assertNull("requests page is gone", Push.route(Intent().putExtra(Push.EXTRA_ROUTE, "family-settings")))
        assertNull(Push.route(Intent().putExtra(Push.EXTRA_ROUTE, "settings")))
        assertNull(Push.route(Intent().putExtra(Push.EXTRA_ROUTE, "expense/../settings")))
        assertNull("Kerakli is gone", Push.route(Intent().putExtra(Push.EXTRA_ROUTE, "need/1c9a7c1e-3f4d-4e5a-9b8c-7d6e5f4a3b2c")))
        assertNull(pushContent(mapOf("type" to "need_comment", "uid" to "u1", "need_id" to "x"), "u1"))
        assertNull(Push.route(null))
    }

    @Test fun permissionAskedUntilSystemDialogAnswers() {
        assertTrue(shouldAskNotifications(33, granted = false, answered = false))
        assertFalse("pre-13 needs no runtime permission", shouldAskNotifications(32, granted = false, answered = false))
        assertFalse("already granted", shouldAskNotifications(34, granted = true, answered = false))
        assertFalse("system dialog answered: Settings row only", shouldAskNotifications(34, granted = false, answered = true))
    }

    @Test fun pushAnsweredIsOnlySetExplicitly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = SettingsStore(app.getSharedPreferences("t", 0).apply { edit().putBoolean("push.asked", true).commit() })
        assertFalse("old dismiss-counts key is ignored", store.pushAnswered)
        store.pushAnswered = true
        assertTrue(store.pushAnswered)
    }

    @Test fun blockedChannelCantNotify() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(Push.CH_EXPENSES, "x", NotificationManager.IMPORTANCE_NONE))
        assertTrue(canNotify(app))
        assertFalse(canNotify(app, Push.CH_EXPENSES))
        assertTrue("unknown channel isn't blocked", canNotify(app, "category_requests"))
    }

    @Test fun canNotifyFollowsRuntimePermission() { // robolectric sdk 34
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(canNotify(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(canNotify(app))
    }
}
