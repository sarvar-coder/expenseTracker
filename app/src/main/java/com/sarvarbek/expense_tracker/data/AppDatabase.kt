package com.sarvarbek.expense_tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Default categories seeded on first launch. AI can add more later. */
private val defaultCategories = listOf(
    "Food & dining" to "E08A5B",
    "Groceries" to "6FA86A",
    "Shopping" to "C07FA6",
    "Transport" to "5B8DB8",
    "Bills" to "D9A24E",
)

private fun seedCategories() = defaultCategories.map { (name, color) -> Category(name = name, colorHex = color) }

/** v2: "Kerakli" needs table. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS `needs` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, " +
            "`owner_id` TEXT, `family_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "`deleted_at` INTEGER, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
}

// ponytail: exportSchema off, migrations hand-written and checked by DataTest's
// v1 upgrade test; export schemas if migrations pile up.
@Database(entities = [Category::class, Expense::class, Need::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ExpenseDao

    companion object {
        /** [name] null = in-memory (tests). */
        fun open(context: Context, name: String? = "expense_tracker.db"): AppDatabase =
            // In-memory = tests: run queries inline. Room's pooled flow threads
            // race Robolectric's paused looper and a first emission can get lost.
            (if (name == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.allowMainThreadQueries()
            else Room.databaseBuilder(context, AppDatabase::class.java, name))
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (c in seedCategories()) {
                            db.insert("categories", CONFLICT_ABORT, ContentValues().apply {
                                put("id", c.id); put("name", c.name); put("color_hex", c.colorHex)
                                put("icon_key", c.iconKey); put("is_archived", 0)
                                put("updated_at", c.updatedAt); put("dirty", 1)
                            })
                        }
                    }
                })
                .build()
    }
}

/** Every read skips soft-deleted rows (deletedAt set, kept for sync). */
@Dao
abstract class ExpenseDao {
    // --- Categories ---
    @Query("SELECT * FROM categories WHERE is_archived = 0 AND deleted_at IS NULL")
    abstract suspend fun getCategories(): List<Category>

    @Query("SELECT * FROM categories WHERE is_archived = 0 AND deleted_at IS NULL")
    abstract fun watchCategories(): Flow<List<Category>>

    /** Includes archived — for the Settings manage sheet (unarchive). */
    @Query("SELECT * FROM categories WHERE deleted_at IS NULL")
    abstract suspend fun getAllCategories(): List<Category>

    @Query("SELECT * FROM categories WHERE deleted_at IS NULL")
    abstract fun watchAllCategories(): Flow<List<Category>>

    @Insert abstract suspend fun insertCategoryRow(c: Category)

    suspend fun insertCategory(c: Category): String = c.id.also { insertCategoryRow(c) }

    /** Raw write, no dirty/updatedAt bump (sync applies server rows with it). */
    @Upsert abstract suspend fun upsertCategories(rows: List<Category>)

    /**
     * Rename or archive (flip [Category.isArchived]) — categories are never
     * hard-deleted, expenses keep pointing at them.
     */
    suspend fun updateCategory(c: Category) =
        upsertCategories(listOf(c.copy(updatedAt = System.currentTimeMillis(), dirty = true)))

    // --- Expenses ---
    @Insert abstract suspend fun insertExpenseRow(e: Expense)

    suspend fun insertExpense(e: Expense): String = e.id.also { insertExpenseRow(e) }

    @Upsert abstract suspend fun upsertExpenses(rows: List<Expense>)

    suspend fun updateExpense(e: Expense) =
        upsertExpenses(listOf(e.copy(updatedAt = System.currentTimeMillis(), dirty = true)))

    /** Soft delete: the row stays so sync can push the deletion. */
    @Query("UPDATE expenses SET deleted_at = :now, updated_at = :now, dirty = 1 WHERE id = :id")
    abstract suspend fun deleteExpense(id: String, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM expenses WHERE id = :id AND deleted_at IS NULL")
    abstract suspend fun getExpense(id: String): Expense?

    @Query("SELECT * FROM expenses WHERE deleted_at IS NULL ORDER BY date DESC")
    abstract fun watchExpenses(): Flow<List<Expense>>

    /** One-shot fetch (date desc) for CSV export. */
    @Query("SELECT * FROM expenses WHERE deleted_at IS NULL ORDER BY date DESC")
    abstract suspend fun getExpenses(): List<Expense>

    data class CategoryTotal(val categoryId: String, val total: Long)

    @Query(
        "SELECT category_id AS categoryId, SUM(amount) AS total FROM expenses " +
            "WHERE deleted_at IS NULL AND date >= :start AND date < :end GROUP BY category_id",
    )
    abstract suspend fun categoryTotalRows(start: Long, end: Long): List<CategoryTotal>

    /** Sum per category in [start, end) (epoch millis): categoryId -> total. */
    suspend fun categoryTotals(start: Long, end: Long): Map<String, Long> =
        categoryTotalRows(start, end).associate { it.categoryId to it.total }

    /** Total spent in [start, end). */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE deleted_at IS NULL AND date >= :start AND date < :end")
    abstract suspend fun totalSpent(start: Long, end: Long): Long

    // --- Needs ---
    /** Open first, newest first. */
    @Query("SELECT * FROM needs WHERE deleted_at IS NULL ORDER BY done, created_at DESC")
    abstract fun watchNeeds(): Flow<List<Need>>

    @Insert abstract suspend fun insertNeed(n: Need)

    @Upsert abstract suspend fun upsertNeeds(rows: List<Need>)

    suspend fun updateNeed(n: Need) = upsertNeeds(listOf(n.copy(updatedAt = System.currentTimeMillis(), dirty = true)))

    @Query("UPDATE needs SET deleted_at = :now, updated_at = :now, dirty = 1 WHERE id = :id")
    abstract suspend fun deleteNeed(id: String, now: Long = System.currentTimeMillis())

    // --- Sync (raw: no dirty/updatedAt bump unless stated) ---
    /** Every row, soft-deleted included. */
    @Query("SELECT * FROM categories") abstract suspend fun allCategoryRows(): List<Category>

    @Query("SELECT * FROM categories WHERE dirty = 1") abstract suspend fun dirtyCategories(): List<Category>

    @Query("SELECT * FROM expenses WHERE dirty = 1") abstract suspend fun dirtyExpenses(): List<Expense>

    @Query("SELECT * FROM needs WHERE dirty = 1") abstract suspend fun dirtyNeeds(): List<Need>

    @Query(
        "SELECT (SELECT count(*) FROM categories WHERE dirty = 1) + (SELECT count(*) FROM expenses WHERE dirty = 1) " +
            "+ (SELECT count(*) FROM needs WHERE dirty = 1)",
    )
    abstract fun watchDirtyCount(): Flow<Int>

    /** Clean only if not edited again while the push was in flight. */
    @Query("UPDATE categories SET dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    abstract suspend fun markCategoryClean(id: String, updatedAt: Long)

    @Query("UPDATE expenses SET dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    abstract suspend fun markExpenseClean(id: String, updatedAt: Long)

    @Query("UPDATE needs SET dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    abstract suspend fun markNeedClean(id: String, updatedAt: Long)

    /** Drops family items of any family but [familyId] (left or removed); the server refuses their edits anyway. */
    @Query("DELETE FROM needs WHERE family_id IS NOT NULL AND (:familyId IS NULL OR family_id != :familyId)")
    abstract suspend fun dropForeignNeeds(familyId: String?)

    /** Repoints expenses (dirty); [pending] null keeps their pendingCategory. */
    @Query(
        "UPDATE expenses SET category_id = :to, pending_category = COALESCE(:pending, pending_category), " +
            "updated_at = :now, dirty = 1 WHERE category_id = :from",
    )
    abstract suspend fun moveExpenses(from: String, to: String, pending: String?, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM categories WHERE id = :id") abstract suspend fun hardDeleteCategory(id: String)

    /** Unowned categories become the family's, or the user's outside one. */
    @Query(
        "UPDATE categories SET owner_id = CASE WHEN :familyId IS NULL THEN :uid END, family_id = :familyId " +
            "WHERE owner_id IS NULL AND family_id IS NULL",
    )
    abstract suspend fun claimCategories(uid: String, familyId: String?)

    @Query("UPDATE expenses SET owner_id = :uid, family_id = :familyId WHERE owner_id IS NULL")
    abstract suspend fun claimExpenses(uid: String, familyId: String?)

    /** Hides categories of any family but [familyId] (null = all); never pushed. */
    @Query(
        "UPDATE categories SET deleted_at = :now, dirty = 0 WHERE family_id IS NOT NULL " +
            "AND (:familyId IS NULL OR family_id != :familyId) AND deleted_at IS NULL",
    )
    abstract suspend fun hideForeignCategories(familyId: String?, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM expenses") abstract suspend fun deleteAllExpenses()

    @Query("DELETE FROM categories") abstract suspend fun deleteAllCategories()

    @Query("DELETE FROM needs") abstract suspend fun deleteAllNeeds()

    /** Another account signed in on this device: drop the previous one's rows. */
    @Transaction
    open suspend fun resetLocal() {
        deleteAllExpenses()
        deleteAllCategories()
        deleteAllNeeds()
        upsertCategories(seedCategories())
    }
}
