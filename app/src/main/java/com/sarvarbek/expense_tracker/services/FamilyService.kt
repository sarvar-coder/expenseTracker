package com.sarvarbek.expense_tracker.services

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

/** A pending invite addressed to the signed-in user. */
data class FamilyInvite(val id: String, val familyName: String, val invitedBy: String)

/** A member's request for a new family category (admin sees all pending). */
data class CategoryRequest(val id: String, val name: String)

/**
 * One current member's month: shared spending and budget contribution
 * (personal budget minus own private spending, computed server-side).
 */
data class FamilyMember(val userId: String, val name: String, val isAdmin: Boolean, val shared: Long, val contribution: Long)

/** A shared family expense, with who added it. [date] is epoch millis. */
data class FamilyExpense(
    val id: String, val ownerName: String, val categoryId: String,
    val description: String, val amount: Long, val date: Long,
)

data class SentInvite(val id: String, val email: String)

/** Everything the Oila tab shows for the family the user is in. */
data class FamilyOverview(
    val id: String,
    val name: String,
    val myId: String,
    val isAdmin: Boolean,
    val members: List<FamilyMember>,
    /** Other members' shared expenses this month (own ones come from Room). */
    val others: List<FamilyExpense>,
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

    /**
     * Null when not in a family. Syncs first so the server has our latest
     * budget and expenses. Online only: the summary is computed server-side.
     * [from]/[to] are epoch millis, [to] exclusive.
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
            // ponytail: fetches the family's whole history and filters here; add a
            // dated RPC if families ever get big enough for that to be slow.
            val rows = async { rpc("family_expenses_since", buildJsonObject { put("p_since", "1970-01-01T00:00:00Z") }).decodeList<JsonObject>() }
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
                        it.num("shared_total"), it.num("contribution"),
                    )
                },
                others = rows.await().mapNotNull { e ->
                    // Private rows are tombstones (date and details null): skip before parsing.
                    val private = (e["is_private"] as? JsonPrimitive)?.booleanOrNull == true
                    if (private || e.str("deleted_at") != null) return@mapNotNull null
                    val d = OffsetDateTime.parse(e.str("date")!!).toInstant().toEpochMilli()
                    if (d < from || d >= to) null
                    else FamilyExpense(e.str("id")!!, e.str("owner_name")!!, e.str("category_id")!!, e.str("description")!!, e.num("amount"), d)
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
        throw FamilyException("Internet aloqasini tekshiring") // offline, timeout
    }
}

private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.long

/** Server error codes (raised by the RPCs) to Uzbek text. */
fun familyErrorText(message: String, code: String? = null) = when {
    message == "already_in_family" -> "Siz allaqachon oiladasiz"
    message == "invite_not_found" -> "Taklif topilmadi"
    message == "not_in_family" -> "Siz oilada emassiz"
    message == "not_admin" -> "Faqat admin bajara oladi"
    message == "transfer_admin_first" -> "Avval boshqa a'zoni admin qiling"
    message == "last_admin" -> "Oilada kamida bitta admin bo'lishi kerak"
    message == "cannot_remove_self" -> "O'zingizni chiqarib bo'lmaydi"
    message == "not_a_member" -> "Bu foydalanuvchi oila a'zosi emas"
    message == "request_not_found" -> "So'rov topilmadi"
    code == "23505" -> "Bu email allaqachon taklif qilingan"
    else -> "Xatolik yuz berdi. Qayta urinib ko'ring"
}
