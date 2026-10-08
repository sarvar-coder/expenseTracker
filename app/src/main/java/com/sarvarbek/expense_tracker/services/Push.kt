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
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sarvarbek.expense_tracker.App
import com.sarvarbek.expense_tracker.MainActivity
import com.sarvarbek.expense_tracker.R
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
        client.auth.sessionStatus
            .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
            .distinctUntilChanged().filterNotNull()
            .onEach { register() }.launchIn(scope)
    }

    /** Saves [token] (default: this phone's) for the signed-in user. Never throws. */
    suspend fun register(token: String? = null) = attempt("register") {
        if (client.auth.currentUserOrNull() == null) return@attempt
        val t = token ?: Tasks.await(FirebaseMessaging.getInstance().token)
        client.postgrest.rpc("register_push_token", buildJsonObject { put("p_token", t) })
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
            withTimeoutOrNull(5_000) { client.auth.sessionStatus.first { it !is SessionStatus.Initializing } }
            client.auth.currentUserOrNull()?.id
        }
        val p = pushContent(data, uid) ?: return
        if (!canNotify(context)) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { p.route?.let { putExtra(EXTRA_ROUTE, it) } }
        val id = p.tag.hashCode()
        val n = NotificationCompat.Builder(context, p.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p.title)
            .setContentText(p.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify: $e") // permission revoked in between
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_REQUESTS, t("push.channel_requests"), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private suspend fun attempt(what: String, f: suspend () -> Unit) = withContext(Dispatchers.IO) {
        try {
            f()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "push $what failed: $e")
        }
    }

    companion object {
        private const val TAG = "push"
        const val CH_REQUESTS = "category_requests"
        const val EXTRA_ROUTE = "route"
        const val ROUTE_REQUESTS = "family-settings"

        /** Tap targets a notification may open; anything else in the intent is ignored. */
        fun route(intent: Intent?) = intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it == ROUTE_REQUESTS }
    }
}

/** What to show for a data push, or null: not for the signed-in account, or a type this version doesn't know. */
data class PushContent(val channel: String, val title: String, val text: String, val route: String?, val tag: String)

fun pushContent(data: Map<String, String>, signedInUid: String?): PushContent? {
    if (signedInUid == null || data["uid"] != signedInUid) return null
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
        else -> null
    }
}

/** Android 13+ needs POST_NOTIFICATIONS at runtime; older versions only the app switch. */
fun canNotify(context: Context) =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Ask once, only on Android 13+, only if not granted yet; a "no" is final (Android settings can still turn it on). */
fun shouldAskNotifications(sdk: Int, granted: Boolean, askedBefore: Boolean) = sdk >= 33 && !granted && !askedBefore

class PushMessagingService : FirebaseMessagingService() {
    private val push get() = (application as App).container.push

    override fun onNewToken(token: String) {
        push.scope.launch { push.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) = push.show(message.data)
}
