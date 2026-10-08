package com.sarvarbek.expense_tracker.services

import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseSource
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import com.sarvarbek.expense_tracker.ui.common.t

/** A pending invite addressed to the signed-in user. */
data class FamilyInvite(val id: String, val familyName: String, val invitedBy: String)

/** A member's request for a new family category (admin sees all pending). */
data class CategoryRequest(val id: String, val name: String)

/**
 * One current member's month: shared spending and budget contribution
 * (the member's personal budget, computed server-side).
 */
data class FamilyMember(
    val userId: String,
    val name: String,
    val isAdmin: Boolean,
    val shared: Long,
    val contribution: Long,
    /** Role in the family: Ota, Ona, Farzand… or free text; null = not set. */
    val title: String? = null,
) {
    /** "Ali · Ota". */
    val label get() = listOfNotNull(name, title).joinToString(" · ")
}

/**
 * A family feed row with who added it. [mine] = own row from Room (its
 * ownerId may still be null before the first sync); others' rows are read-only.
 */
data class FamilyExpense(val expense: Expense, val ownerName: String, val mine: Boolean = false) {
    /** Tap routing for every list: own, non-frozen rows open edit; the rest open the read-only sheet. */
    val editable get() = mine && !expense.frozen
}

data class SentInvite(val id: String, val email: String)

/** Everything the Oila tab shows for the family the user is in. */
data class FamilyOverview(
    val id: String,
    val name: String,
    val myId: String,
    val isAdmin: Boolean,
    val members: List<FamilyMember>,
    /** Admin only: sent invites and pending category requests. */
    val invites: List<SentInvite> = emptyList(),
    val requests: List<CategoryRequest> = emptyList(),
) {
    /** Family budget = live sum of contributions; spent = shared spending. */
    val budget get() = members.sumOf { it.contribution }
    val spent get() = members.sumOf { it.shared }
}

/** Thrown by [FamilyService] with a message ready to show (Uzbek). */
class FamilyException(override val message: String) : Exception(message)

/**
 * Family lifecycle over the Supabase RPCs (rules live server-side). Each
 * change is bracketed by syncs: push local edits first so the server moves
 * them too, then pull what the change did (sync re-pulls on family change).
 */
class FamilyService(private val client: SupabaseClient, private val sync: SyncService) {
    private val uid get() = client.auth.currentUserOrNull()!!.id
    private val others = MutableStateFlow(emptyList<FamilyExpense>())

    /**
     * The family feed: own Room rows (live, offline edits included) plus other
     * members' shared rows, newest first. Others' rows are refreshed in memory
     * after every sync pass (sign-in, resume, writes, pull-to-refresh); offline
     * they stay as last fetched. Not in a family: own rows only.
     * ponytail: in memory, not Room; store others' rows if the offline gap hurts.
     */
    val feed: Flow<List<FamilyExpense>> =
        combine(sync.database.dao().watchExpenses(), others) { own, o -> mergeFeed(own, o, t("family.you"), client.auth.currentUserOrNull()?.id) }

    init { sync.onSynced = ::refreshOthers }

    /** Never throws: a failed fetch keeps the last list. Dropped if the account changed meanwhile. */
    private suspend fun refreshOthers(familyId: String?) {
        val me = client.auth.currentUserOrNull()?.id
        val rows = try {
            if (familyId == null || me == null) emptyList() else fetchOthers(familyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("family", "feed fetch failed: $e")
            return
        }
        if (client.auth.currentUserOrNull()?.id == me) others.value = rows
    }

    private suspend fun fetchOthers(familyId: String): List<FamilyExpense> =
        // ponytail: fetches the family's whole history each pass; add a dated
        // RPC if families ever get big enough for that to be slow.
        parseOthers(rpc("family_expenses_since", buildJsonObject { put("p_since", "1970-01-01T00:00:00Z") }).decodeList(), familyId)

    /**
     * Null when not in a family. Syncs first so the server has our latest
     * budget and expenses. Online only: the summary is computed server-side.
     * [from]/[to] (epoch millis, [to] exclusive) bound the summary only.
     * Others' expenses come from [feed] (the sync above refreshed it).
     */
    suspend fun overview(from: Long, to: Long): FamilyOverview? {
        sync.run()
        val me = call {
            client.from("family_members").select(Columns.raw("family_id, role, families(name)")) {
                filter { eq("user_id", uid) }
            }.decodeSingleOrNull<JsonObject>()
        } ?: return null
        val fid = me.str("family_id")!!
        val isAdmin = me.str("role") == "admin"
        return coroutineScope {
            val summary = async {
                rpc("family_summary", buildJsonObject {
                    put("p_from", Instant.ofEpochMilli(from).toString()); put("p_to", Instant.ofEpochMilli(to).toString())
                }).decodeList<JsonObject>()
            }
            val invites = async {
                if (!isAdmin) emptyList()
                else call { client.from("invites").select(Columns.list("id", "email")) { filter { eq("family_id", fid) } }.decodeList<JsonObject>() }
                    .map { SentInvite(it.str("id")!!, it.str("email")!!) }
            }
            val requests = async { if (isAdmin) categoryRequests() else emptyList() }
            FamilyOverview(
                id = fid,
                name = me.getValue("families").jsonObject.str("name")!!,
                myId = uid,
                isAdmin = isAdmin,
                members = summary.await().map {
                    FamilyMember(
                        it.str("user_id")!!, it.str("display_name")!!, it.str("role") == "admin",
                        it.num("shared_total"), it.num("contribution"), it.str("title"),
                    )
                },
                invites = invites.await(),
                requests = requests.await(),
            )
        }
    }

    suspend fun create(name: String, includeHistory: Boolean): String =
        change { rpc("create_family", buildJsonObject { put("p_name", name.trim()); put("p_include_history", includeHistory) }) }
            .decodeAs<String>()

    /** Admin only (RLS). The invitee sees it after signing in; no email is sent. */
    suspend fun invite(familyId: String, email: String) {
        call {
            client.from("invites").insert(buildJsonObject {
                put("family_id", familyId); put("email", email.trim().lowercase()); put("invited_by", uid)
            })
        }
    }

    /** Admin cancels an invite, or the invitee declines it. */
    suspend fun deleteInvite(id: String) {
        call { client.from("invites").delete { filter { eq("id", id) } } }
    }

    suspend fun myInvites(): List<FamilyInvite> = rpc("my_invites").decodeList<JsonObject>().map {
        FamilyInvite(it.str("id")!!, it.str("family_name")!!, it.str("invited_by_name")!!)
    }

    suspend fun accept(inviteId: String, includeHistory: Boolean) {
        change { rpc("accept_invite", buildJsonObject { put("p_invite_id", inviteId); put("p_include_history", includeHistory) }) }
    }

    suspend fun leave() { change { rpc("leave_family") } }

    suspend fun removeMember(userId: String) { change { rpc("remove_member", buildJsonObject { put("p_user", userId) }) } }

    /** Admin only. Any number of admins; the last one can't step down. */
    suspend fun setRole(userId: String, admin: Boolean) {
        change { rpc("set_role", buildJsonObject { put("p_user", userId); put("p_role", if (admin) "admin" else "member") }) }
    }

    /** Own title, or anyone's for an admin. Blank clears it. */
    suspend fun setTitle(userId: String, title: String) {
        change { rpc("set_title", buildJsonObject { put("p_user", userId); put("p_title", title.trim()) }) }
    }

    /** Admin only (RLS). */
    suspend fun rename(familyId: String, name: String) {
        call { client.from("families").update(buildJsonObject { put("name", name.trim()) }) { filter { eq("id", familyId) } } }
    }

    suspend fun deleteFamily() { change { rpc("delete_family") } }

    /** Pending category requests: all of them for the admin, own for a member (RLS). */
    suspend fun categoryRequests(): List<CategoryRequest> = call {
        client.from("category_requests").select(Columns.list("id", "name")) {
            filter { eq("status", "pending") }
            order("created_at", Order.ASCENDING)
        }.decodeList<JsonObject>()
    }.map { CategoryRequest(it.str("id")!!, it.str("name")!!) }

    /** Creates the category (unused color) and moves waiting expenses into it. */
    suspend fun approveRequest(id: String) {
        val used = sync.database.dao().allCategoryRows().map { it.colorHex.uppercase() }.toSet()
        change {
            rpc("approve_category_request", buildJsonObject {
                put("p_id", id); put("p_color_hex", uniqueColor(used)); put("p_icon_key", JsonNull)
            })
        }
    }

    /** Waiting expenses stay in Boshqa. */
    suspend fun rejectRequest(id: String) { change { rpc("reject_category_request", buildJsonObject { put("p_id", id) }) } }

    private suspend fun rpc(fn: String, params: JsonObject = JsonObject(emptyMap())): PostgrestResult =
        call { client.postgrest.rpc(fn, params) }

    private suspend fun <T> change(rpc: suspend () -> T): T {
        sync.run()
        return rpc().also { sync.run() }
    }

    private suspend fun <T> call(f: suspend () -> T): T = try {
        f()
    } catch (e: PostgrestRestException) {
        throw FamilyException(familyErrorText(e.error, e.code))
    } catch (e: CancellationException) {
        throw e
    } catch (e: FamilyException) {
        throw e
    } catch (_: Exception) {
        throw FamilyException(t("svc.family.offline")) // offline, timeout
    }
}

private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.long

/** `family_expenses_since` rows to feed rows. Transfers (and old private rows) come as tombstones flagged is_private, details null: skipped. */
internal fun parseOthers(rows: List<JsonObject>, familyId: String) = rows.mapNotNull { e ->
    val hidden = (e["is_private"] as? JsonPrimitive)?.booleanOrNull == true
    if (hidden || e.str("deleted_at") != null) return@mapNotNull null
    FamilyExpense(
        Expense(
            id = e.str("id")!!, description = e.str("description")!!, amount = e.num("amount"),
            categoryId = e.str("category_id")!!, date = OffsetDateTime.parse(e.str("date")!!).toInstant().toEpochMilli(),
            // raw_input: servers before 20261009120000 don't send it.
            source = e.str("source")?.let { s -> ExpenseSource.entries.firstOrNull { it.name == s } } ?: ExpenseSource.manual,
            rawInput = e.str("raw_input"), ownerId = e.str("owner_id")!!, familyId = familyId,
            frozen = (e["frozen"] as? JsonPrimitive)?.booleanOrNull == true, dirty = false,
        ),
        e.str("owner_name")!!,
    )
}

/**
 * Own rows (tagged [FamilyExpense.mine], named [myName]) plus others', de-duped by id (own wins), newest first.
 * Own rows not yet synced get [myId] as owner, so a member filter sees them.
 */
fun mergeFeed(own: List<Expense>, others: List<FamilyExpense>, myName: String, myId: String? = null): List<FamilyExpense> {
    val ids = own.mapTo(HashSet()) { it.id }
    return (own.map { FamilyExpense(if (it.ownerId == null && myId != null) it.copy(ownerId = myId) else it, myName, mine = true) } + others.filter { it.expense.id !in ids })
        .sortedByDescending { it.expense.date }
}

/** Server error codes (raised by the RPCs) to UI text. */
fun familyErrorText(message: String, code: String? = null) = when {
    message == "already_in_family" -> t("svc.family.already_in_family")
    message == "invite_not_found" -> t("svc.family.invite_not_found")
    message == "not_in_family" -> t("svc.family.not_in_family")
    message == "not_admin" -> t("svc.family.not_admin")
    message == "transfer_admin_first" -> t("svc.family.transfer_admin_first")
    message == "last_admin" -> t("svc.family.last_admin")
    message == "cannot_remove_self" -> t("svc.family.cannot_remove_self")
    message == "not_a_member" -> t("svc.family.not_a_member")
    message == "request_not_found" -> t("svc.family.request_not_found")
    code == "23505" -> t("svc.family.already_invited")
    else -> t("svc.family.unknown")
}
