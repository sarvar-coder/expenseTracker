package com.sarvarbek.expense_tracker.services

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Background two-way sync between Room (source of truth) and Supabase.
 * Push dirty rows, pull rows changed since a per-table `synced_at` cursor.
 * Conflicts: newest `updatedAt` wins (server trigger + [applyCategories]).
 *
 * Only own expenses are pulled; the Oila tab reads other members' rows live.
 */
class SyncService(
    val database: AppDatabase,
    private val client: SupabaseClient,
    private val prefs: SharedPreferences,
    private val settings: SettingsStore,
) {
    private val db = database.dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var timer: Job? = null
    private var inFlight: Deferred<Unit>? = null

    /**
     * Triggers: sign-in / app start (stored session), local writes, resume.
     * ponytail: "reconnect" = retry every 30s after a failed run; add a
     * ConnectivityManager callback if that lag ever matters.
     */
    fun start() {
        client.auth.sessionStatus
            .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
            .distinctUntilChanged().filterNotNull()
            .onEach { schedule() }.launchIn(scope)
        // distinct: a row the server keeps refusing mustn't re-trigger forever.
        db.watchDirtyCount().distinctUntilChanged().filter { it > 0 }
            .onEach { schedule(2_000) }.launchIn(scope)
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = schedule()
            })
        }
    }

    fun schedule(delayMs: Long = 0) {
        synchronized(lock) {
            timer?.cancel()
            timer = scope.launch { delay(delayMs); run() }
        }
    }

    /** One sync pass; concurrent callers share it. Never throws. */
    suspend fun run() {
        val pass = synchronized(lock) {
            // LAZY: started after it's stored, so a pass that ends at once can't clear it first.
            inFlight ?: scope.async(start = CoroutineStart.LAZY) {
                try { guarded() } finally { synchronized(lock) { inFlight = null } }
            }.also { inFlight = it; it.start() }
        }
        pass.await()
    }

    private suspend fun guarded() {
        val uid = client.auth.currentUserOrNull()?.id ?: return
        try {
            sync(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: $e")
            schedule(RETRY_MS)
        }
    }

    private suspend fun sync(uid: String) {
        val prev = prefs.getString(K_UID, null)
        if (prev != null && prev != uid) {
            // Another account on this device: its rows are not ours to show or push.
            db.resetLocal()
            settings.applyProfile(budget = 0, defaultPrivate = false)
            prefs.edit { prefs.all.keys.filter { it.startsWith("sync.") }.forEach { remove(it) } }
        }
        prefs.edit { putString(K_UID, uid) }

        val member = client.from("family_members").select(Columns.list("family_id", "role")) {
            filter { eq("user_id", uid) }
        }.decodeSingleOrNull<JsonObject>()
        val familyId = member?.str("family_id")

        // Joined, left, or was removed (maybe on another device): re-pull from
        // scratch, the new family's categories are older than our cursor.
        val prevFamily = prefs.getString(K_FAMILY, null)
        prefs.edit {
            if (prevFamily != null && prevFamily != (familyId ?: "")) {
                remove("sync.categories"); remove("sync.expenses")
            }
            putString(K_FAMILY, familyId ?: "")
            putBoolean(K_ADMIN, member?.str("role") == "admin")
        }
        syncProfile(uid)

        // Categories first: merge local ones into same-name server ones before
        // pushing (a fresh device seeds defaults that already exist remotely).
        pull("categories") { rows ->
            database.withTransaction {
                applyCategories(database, rows)
                db.hideForeignCategories(familyId)
            }
        }
        db.hideForeignCategories(familyId) // no rows pulled after leaving
        adoptLocal(database, uid, familyId, canCreate = canCreateCategories(prefs))
        push("categories", db.dirtyCategories(), Category::toJson) { db.markCategoryClean(it.id, it.updatedAt) }
        push("expenses", db.dirtyExpenses(), Expense::toJson) { db.markExpenseClean(it.id, it.updatedAt) }
        pull("expenses") { applyExpenses(database, it) }
    }

    /**
     * Budget + private default: push a local edit, otherwise take the server's
     * (another device may have changed them).
     */
    private suspend fun syncProfile(uid: String) {
        val server = if (settings.profileDirty) null else client.from("profiles")
            .select(Columns.list("budget", "default_private")) { filter { eq("id", uid) } }
            .decodeSingle<JsonObject>()
        val local = settings.load()
        if (shouldPushProfile(settings.profileDirty, server?.get("budget")?.jsonPrimitive?.long, local.monthlyBudget)) {
            client.from("profiles").update(buildJsonObject {
                put("budget", local.monthlyBudget)
                put("default_private", local.defaultPrivate)
                put("updated_at", Instant.now().toString())
            }) { filter { eq("id", uid) } }
            settings.markProfileClean()
        } else {
            settings.applyProfile(server!!.getValue("budget").jsonPrimitive.long, server.getValue("default_private").jsonPrimitive.boolean)
        }
    }

    private suspend fun <T> push(table: String, rows: List<T>, toJson: (T) -> JsonObject, markClean: suspend (T) -> Unit) {
        val pushed = mutableListOf<T>()
        for (chunk in rows.chunked(500)) {
            try {
                client.from(table).upsert(JsonArray(chunk.map(toJson)))
                pushed += chunk
            } catch (_: PostgrestRestException) {
                // One refused row (RLS, frozen) mustn't block the rest; it stays dirty.
                for (r in chunk) {
                    try {
                        client.from(table).upsert(JsonArray(listOf(toJson(r))))
                        pushed += r
                    } catch (e: PostgrestRestException) {
                        Log.w(TAG, "sync: $table ${toJson(r)["id"]} refused: ${e.error}")
                    }
                }
            }
        }
        pushed.forEach { markClean(it) }
    }

    private suspend fun pull(table: String, apply: suspend (List<JsonObject>) -> Unit) {
        val key = "sync.$table"
        // Overlap a minute: rows stamped inside a still-open server transaction
        // commit after later stamps. Re-applying a row is harmless.
        var since = OffsetDateTime.parse(prefs.getString(key, null) ?: "1970-01-01T00:00:00Z")
            .minusMinutes(1).toInstant().toString()
        while (true) {
            val rows = client.from(table).select {
                filter { gt("synced_at", since) }
                order("synced_at", Order.ASCENDING)
                limit(PAGE.toLong())
            }.decodeList<JsonObject>()
            if (rows.isEmpty()) return
            apply(rows)
            since = rows.last().str("synced_at")!!
            prefs.edit { putString(key, since) }
            if (rows.size < PAGE) return
        }
    }

    companion object {
        private const val TAG = "sync"
        private const val K_UID = "sync.uid"
        const val K_FAMILY = "sync.family" // "" = not in a family
        const val K_ADMIN = "sync.admin"
        private const val RETRY_MS = 30_000L
        private const val PAGE = 1000
    }
}

/**
 * Push this device's budget/private default, or take the server's? Push
 * when edited here, or when the server has no budget but this device does
 * (set before sign-in or before profiles synced; 0 = unset) so it isn't wiped.
 */
fun shouldPushProfile(dirty: Boolean, serverBudget: Long?, localBudget: Long) =
    dirty || (serverBudget == 0L && localBudget > 0)

/** Outside a family, or as its admin, the user may create categories. As of the last sync; true before the first one. */
fun canCreateCategories(prefs: SharedPreferences) =
    prefs.getString(SyncService.K_FAMILY, null).isNullOrEmpty() || prefs.getBoolean(SyncService.K_ADMIN, false)

/**
 * Gives unowned local rows (made before sign-in or since the last sync) to
 * [uid]. Unowned categories named like an existing one are merged into it;
 * if the user can't create categories, the rest become Boshqa + request.
 */
suspend fun adoptLocal(database: AppDatabase, uid: String, familyId: String?, canCreate: Boolean = true) =
    database.withTransaction {
        val db = database.dao()
        val (orphans, rest) = db.allCategoryRows().partition { it.ownerId == null && it.familyId == null }
        val owned = rest.filter { it.deletedAt == null }.associate { norm(it.name) to it.id }
        val boshqa = if (canCreate) null else owned["boshqa"]
        for (k in orphans) {
            val twin = owned[norm(k.name)]
            if (twin == null && boshqa == null) continue
            db.moveExpenses(k.id, twin ?: boshqa!!, pending = if (twin == null) k.name else null)
            db.hardDeleteCategory(k.id) // never pushed, so a hard delete is safe
        }
        // In a family, new categories are the family's (admin only; a member's
        // were all turned into requests above).
        db.claimCategories(uid, familyId)
        db.claimExpenses(uid, familyId)
    }

private fun norm(s: String) = s.trim().lowercase()

/** Upserts server rows, except where a local unpushed edit is as new or newer. */
suspend fun applyCategories(database: AppDatabase, rows: List<JsonObject>) {
    val db = database.dao()
    val pending = db.dirtyCategories().associate { it.id to it.updatedAt }
    db.upsertCategories(rows.filter { isFresh(it, pending) }.map { r ->
        Category(
            id = r.str("id")!!, ownerId = r.str("owner_id"), familyId = r.str("family_id"),
            name = r.str("name")!!, iconKey = r.str("icon_key")!!, colorHex = r.str("color_hex")!!,
            isArchived = r.bool("is_archived"), updatedAt = r.ts("updated_at")!!, deletedAt = r.ts("deleted_at"),
            dirty = false,
        )
    })
}

suspend fun applyExpenses(database: AppDatabase, rows: List<JsonObject>) {
    val db = database.dao()
    val pending = db.dirtyExpenses().associate { it.id to it.updatedAt }
    db.upsertExpenses(rows.filter { isFresh(it, pending) }.map { r ->
        Expense(
            id = r.str("id")!!, ownerId = r.str("owner_id"), familyId = r.str("family_id"),
            description = r.str("description")!!, amount = r.getValue("amount").jsonPrimitive.long,
            categoryId = r.str("category_id")!!, date = r.ts("date")!!,
            source = ExpenseSource.valueOf(r.str("source")!!), rawInput = r.str("raw_input"),
            isPrivate = r.bool("is_private"), pendingCategory = r.str("pending_category"), frozen = r.bool("frozen"),
            createdAt = r.ts("created_at")!!, updatedAt = r.ts("updated_at")!!, deletedAt = r.ts("deleted_at"),
            dirty = false,
        )
    })
}

private fun isFresh(r: JsonObject, pending: Map<String, Long>): Boolean {
    val mine = pending[r.str("id")] ?: return true
    return r.ts("updated_at")!! > mine
}

// Wire helpers: server rows are JSON objects, times UTC ISO strings.
internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bool(k: String) = getValue(k).jsonPrimitive.boolean
private fun JsonObject.ts(k: String) = str(k)?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
private fun iso(ms: Long?) = ms?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull
private fun jsonOf(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) ->
    k to when (v) {
        null -> JsonNull
        is JsonPrimitive -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> error("unsupported $v")
    }
})

private fun Category.toJson() = jsonOf(
    "id" to id, "owner_id" to ownerId, "family_id" to familyId, "name" to name, "icon_key" to iconKey,
    "color_hex" to colorHex, "is_archived" to isArchived, "updated_at" to iso(updatedAt), "deleted_at" to iso(deletedAt),
)

private fun Expense.toJson() = jsonOf(
    "id" to id, "owner_id" to ownerId, "family_id" to familyId, "category_id" to categoryId,
    "description" to description, "amount" to amount, "date" to iso(date), "source" to source.name,
    "raw_input" to rawInput, "is_private" to isPrivate, "pending_category" to pendingCategory,
    "created_at" to iso(createdAt), "updated_at" to iso(updatedAt), "deleted_at" to iso(deletedAt),
)
