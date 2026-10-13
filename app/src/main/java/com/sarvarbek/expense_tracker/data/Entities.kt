package com.sarvarbek.expense_tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** How an expense was entered. Names match the server's `source` values. */
@Suppress("EnumEntryName")
enum class ExpenseSource { typed, voice, manual }

/** Client-generated UUID, so rows made offline keep their id on the server. */
fun newId(): String = UUID.randomUUID().toString()

// Both tables carry the sync columns of the Supabase rows: id, ownerId (null
// until first sign-in), familyId, updatedAt, deletedAt (soft delete) and the
// local-only dirty flag (changed since last push). Times are epoch millis.

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey val id: String = newId(),
    val name: String,
    @ColumnInfo(name = "color_hex") val colorHex: String, // e.g. E08A5B
    @ColumnInfo(name = "icon_key") val iconKey: String = "category",
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "owner_id") val ownerId: String? = null,
    @ColumnInfo(name = "family_id") val familyId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

// ponytail: no Room ForeignKey on category_id — Room would turn on
// PRAGMA foreign_keys and sync could then fail on pull order. Categories are
// never hard-deleted, same as the Drift schema in practice.
@Entity(tableName = "expenses", indices = [Index("category_id")])
data class Expense(
    @PrimaryKey val id: String = newId(),
    val description: String,
    val amount: Long, // UZS, whole units
    @ColumnInfo(name = "category_id") val categoryId: String,
    val date: Long,
    val source: ExpenseSource,
    @ColumnInfo(name = "raw_input") val rawInput: String? = null,
    @ColumnInfo(name = "is_private") val isPrivate: Boolean = false,
    @ColumnInfo(name = "pending_category") val pendingCategory: String? = null, // awaiting admin approval
    val frozen: Boolean = false, // ex-member history
    /** O'tkazma: money given to this family member (user id). Kept out of every spending total. */
    @ColumnInfo(name = "transfer_to") val transferTo: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "owner_id") val ownerId: String? = null,
    @ColumnInfo(name = "family_id") val familyId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

/** "Kerakli" item. familyId null = personal; set = the whole family sees it. */
@Entity(tableName = "needs")
data class Need(
    @PrimaryKey val id: String = newId(),
    val text: String,
    val done: Boolean = false, // mirrors status == done (old APKs read it)
    @ColumnInfo(defaultValue = NeedStatus.NEW) val status: String = NeedStatus.NEW,
    @ColumnInfo(name = "need_money", defaultValue = "0") val needMoney: Boolean = false, // false = has money, wants opinions
    val link: String? = null, // http(s) only
    @ColumnInfo(name = "photo_path") val photoPath: String? = null, // storage object, set once uploaded
    @ColumnInfo(name = "owner_id") val ownerId: String? = null,
    @ColumnInfo(name = "family_id") val familyId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

/**
 * Hissa: money a member puts into their month (repeat top-ups, note required).
 * Own rows only (the server sets the owner); the family sees totals via family_summary.
 */
@Entity(tableName = "contributions")
data class Contribution(
    @PrimaryKey val id: String = newId(),
    val amount: Long, // UZS, > 0
    val note: String,
    val date: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val dirty: Boolean = true,
)

/** Need.status values, in tab order. Pending is set by the server (first comment). */
object NeedStatus {
    const val NEW = "new"
    const val PENDING = "pending"
    const val DONE = "done"
    const val REJECTED = "rejected"
    val all = listOf(NEW, PENDING, DONE, REJECTED)
}

fun Need.withStatus(s: String) = copy(status = s, done = s == NeedStatus.DONE)
