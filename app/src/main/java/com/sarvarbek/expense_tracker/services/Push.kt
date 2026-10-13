package com.sarvarbek.expense_tracker.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sarvarbek.expense_tracker.App
import com.sarvarbek.expense_tracker.BuildConfig
import com.sarvarbek.expense_tracker.MainActivity
import com.sarvarbek.expense_tracker.R
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.t
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * FCM push: this phone's token in `device_tokens` while signed in, and
 * notifications built from the data pushes the `notify` Edge Function sends
 * (data-only, so text is localized here and a push for another account is dropped).
 */
class Push(private val context: Context, private val client: SupabaseClient) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        createChannels()
        // Sign-in and every start with a stored session (re-registering is a cheap upsert).
        // Retried every 30s until it lands (offline start); a new account or sign-out cancels it.
        scope.launch {
            client.auth.sessionStatus
                .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
                .distinctUntilChanged()
                .collectLatest { uid -> if (uid != null) while (!register()) delay(RETRY_MS) }
        }
        // Back in the app (system settings, permission dialog): report a changed notification switch.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    if (sentCanNotify != null && sentCanNotify != reportable()) scope.launch { register() }
                }
            })
        }
    }

    /** App switch, permission and both channels: what the server sees as `can_notify`. */
    private fun reportable() = listOf(CH_REQUESTS, CH_EXPENSES).all { canNotify(context, it) }

    /** What the last successful [register] reported; null until one lands. */
    @Volatile private var sentCanNotify: Boolean? = null

    /** Saves [token] (default: this phone's) for the signed-in user. Never throws; false = failed, try again. */
    suspend fun register(token: String? = null): Boolean {
        if (client.auth.currentUserOrNull() == null) return true // signed out: nothing to do
        return attempt("register") {
            val t = token ?: Tasks.await(FirebaseMessaging.getInstance().token)
            val can = reportable()
            // can_notify + version: lets the server side tell a silent phone from a missing token.
            client.postgrest.rpc("register_push_token", buildJsonObject {
                put("p_token", t)
                put("p_can_notify", can)
                put("p_version", BuildConfig.VERSION_NAME)
            })
            sentCanNotify = can
            Log.i(TAG, "registered, canNotify=$can")
        }
    }

    /**
     * Before sign-out (needs the session): drop the server row, then the token
     * itself, so even if the row delete failed offline FCM rejects the old token.
     */
    suspend fun unregister() {
        attempt("unregister") {
            val t = Tasks.await(FirebaseMessaging.getInstance().token)
            client.from("device_tokens").delete { filter { eq("token", t) } }
        }
        attempt("deleteToken") { Tasks.await(FirebaseMessaging.getInstance().deleteToken()) }
    }

    /** Called on FCM's worker thread. */
    fun show(data: Map<String, String>) {
        val uid = runBlocking {
            withTimeoutOrNull(10_000) { client.auth.sessionStatus.first { it !is SessionStatus.Initializing } }
            // A push often wakes a killed app with an expired token: while that refresh fails
            // (RefreshFailure, no session in the status) the stored session still names the account.
            client.auth.currentUserOrNull()?.id
                ?: if (client.auth.sessionStatus.value is SessionStatus.RefreshFailure) client.auth.sessionManager.loadSessionOrNull()?.user?.id else null
        }
        if (uid == null) return Unit.also { Log.i(TAG, "drop ${data["type"]}: no signed-in user (${client.auth.sessionStatus.value})") }
        pushContent(data, uid)?.let { post(context, it) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CH_REQUESTS, t("push.channel_requests"), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_EXPENSES, t("push.channel_expenses"), NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private suspend fun attempt(what: String, f: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            f()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "push $what failed: $e")
            false
        }
    }

    companion object {
        private const val TAG = "push"
        private const val RETRY_MS = 30_000L
        const val CH_REQUESTS = "category_requests"
        const val CH_EXPENSES = "new_expenses"
        const val EXTRA_ROUTE = "route"
        const val ROUTE_REQUESTS = "family-settings"
        /** `expense/<id>`: that expense's read-only sheet. */
        const val ROUTE_EXPENSE = "expense/"
        private val expenseRoute = Regex("expense/[0-9a-fA-F-]{36}")

        /** Tap targets a notification may open; anything else in the intent is ignored. */
        fun route(intent: Intent?) = intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it == ROUTE_REQUESTS || expenseRoute.matches(it) }
    }
}

/**
 * What to show for a data push, or null: not for the signed-in account, or a type this version doesn't know.
 * [group]: pushes sharing it stack under one summary (new expenses: one per member who added them).
 */
data class PushContent(val channel: String, val title: String, val text: String, val route: String?, val tag: String, val group: String? = null)

fun pushContent(data: Map<String, String>, signedInUid: String?): PushContent? {
    if (signedInUid == null || data["uid"] != signedInUid) return null.also { Log.i("push", "drop ${data["type"]}: for another account") }
    val category = data["category"].orEmpty()
    return when (data["type"]) {
        "category_request" -> PushContent(
            Push.CH_REQUESTS, t("push.request_title"), t("push.request", data["name"].orEmpty(), category),
            Push.ROUTE_REQUESTS, "request:$category",
        )
        "category_resolved" -> PushContent(
            Push.CH_REQUESTS, t("push.request_title"),
            t(if (data["status"] == "approved") "push.approved" else "push.rejected", category),
            null, "resolved:$category",
        )
        "expense" -> {
            val id = data["id"] ?: return null.also { Log.i("push", "drop expense: no id") }
            val name = data["name"].orEmpty()
            PushContent(
                Push.CH_EXPENSES, t("push.expense_title"),
                t("push.expense", name, data["description"].orEmpty(), formatMoney(data["amount"]?.toLongOrNull() ?: 0)),
                Push.ROUTE_EXPENSE + id, "expense:$id", "expenses:${data["owner"].orEmpty()}",
            )
        }
        else -> null.also { Log.i("push", "drop: unknown type ${data["type"]}") }
    }
}

/** Shows [p]; grouped pushes also get (or refresh) their group's summary. Tapping opens [PushContent.route]. */
internal fun post(context: Context, p: PushContent) {
    if (!canNotify(context, p.channel)) return Unit.also { Log.i("push", "drop ${p.tag}: notifications off (app or channel ${p.channel})") }
    fun tap(route: String?, code: Int) = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { route?.let { putExtra(Push.EXTRA_ROUTE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    fun builder() = NotificationCompat.Builder(context, p.channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(p.title)
        .setContentText(p.text)
        .setAutoCancel(true)
        .setGroup(p.group)
    val id = p.tag.hashCode()
    val nm = NotificationManagerCompat.from(context)
    try {
        nm.notify(id, builder().setStyle(NotificationCompat.BigTextStyle().bigText(p.text)).setContentIntent(tap(p.route, id)).build())
        // Summary: no sound of its own; tapping it (where shown collapsed) just opens the app.
        if (p.group != null) {
            val sid = p.group.hashCode()
            nm.notify(sid, builder().setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setContentIntent(tap(null, sid)).build())
        }
    } catch (e: SecurityException) {
        Log.w("push", "notify: $e") // permission revoked in between
    }
}

/** Android 13+ needs POST_NOTIFICATIONS at runtime; older versions only the app switch. [channel]: also that channel not blocked. */
fun canNotify(context: Context, channel: String? = null) =
    notificationPermission(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (channel == null || Build.VERSION.SDK_INT < 26 ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE)

/** The runtime permission only (Android 13+); the app switch and channels are separate. */
fun notificationPermission(context: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Ask on Android 13+ while not granted, until the system dialog has answered once
 * ([answered]); closing our explanation doesn't count. After that, Settings' row is the way.
 */
fun shouldAskNotifications(sdk: Int, granted: Boolean, answered: Boolean) = sdk >= 33 && !granted && !answered

class PushMessagingService : FirebaseMessagingService() {
    private val push get() = (application as App).container.push

    override fun onNewToken(token: String) {
        push.scope.launch { push.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) = push.show(message.data)
}
