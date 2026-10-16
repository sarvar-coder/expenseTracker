package com.sarvarbek.expense_tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Contribution
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.matchOrCreateCategory
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.toMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/** Ports db_test, settings_test, category_matcher_test, add_manual_test, providers_test. */
@RunWith(RobolectricTestRunner::class)
class DataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ExpenseDao

    @Before fun setUp() {
        db = AppDatabase.open(context, name = null).dao()
    }

    private suspend fun cat(name: String) = db.getCategories().first { it.name == name }

    private fun expense(desc: String, amount: Long, catId: String, date: LocalDate, source: ExpenseSource = ExpenseSource.manual) =
        Expense(description = desc, amount = amount, categoryId = catId, date = date.startMillis(), source = source)

    @Test fun upgradesV1DatabaseKeepingRowsAddingTransfersAndContributions() = runBlocking {
        val name = "v1-upgrade.db"
        context.deleteDatabase(name)
        // v1 schema as Room 1 created it (copied from the generated AppDatabase_Impl).
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color_hex` TEXT NOT NULL, `icon_key` TEXT NOT NULL, `is_archived` INTEGER NOT NULL, `owner_id` TEXT, `family_id` TEXT, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            it.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` TEXT NOT NULL, `description` TEXT NOT NULL, `amount` INTEGER NOT NULL, `category_id` TEXT NOT NULL, `date` INTEGER NOT NULL, `source` TEXT NOT NULL, `raw_input` TEXT, `is_private` INTEGER NOT NULL, `pending_category` TEXT, `frozen` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `owner_id` TEXT, `family_id` TEXT, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_category_id` ON `expenses` (`category_id`)")
            it.execSQL("INSERT INTO categories VALUES ('c1', 'Old', 'AAAAAA', 'category', 0, NULL, NULL, 1, NULL, 0)")
            it.version = 1
        }
        val upgraded = AppDatabase.open(context, name)
        try {
            val dao = upgraded.dao()
            assertEquals(listOf("Old"), dao.getCategories().map { it.name })
            assertFalse("v7 drops Kerakli", upgraded.hasTable("needs"))
            // v3: transfer_to round-trips and stays out of the sums
            val day = LocalDate.of(2026, 10, 7)
            dao.insertExpense(expense("gift", 500, "c1", day).copy(transferTo = "u2"))
            dao.insertExpense(expense("bread", 20, "c1", day))
            assertEquals(listOf("u2", null), dao.getExpenses().sortedByDescending { it.amount }.map { it.transferTo })
            val range = day.startMillis() to day.plusDays(1).startMillis()
            assertEquals(20L, dao.totalSpent(range.first, range.second))
            assertEquals(mapOf("c1" to 20L), dao.categoryTotals(range.first, range.second))
            // v4 (3→4): contributions, soft delete, dirty count
            dao.insertContribution(Contribution(amount = 400_000, note = "Oylik"))
            val gone = Contribution(amount = 5, note = "x").also { dao.insertContribution(it) }
            dao.deleteContribution(gone.id)
            assertEquals(listOf("Oylik"), dao.watchContributions().first().map { it.note })
            assertEquals(2, dao.dirtyContributions().size) // the deletion waits to be pushed
            dao.resetLocal()
            assertTrue(dao.getContributions().isEmpty())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private fun AppDatabase.hasTable(name: String) =
        openHelper.readableDatabase.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    @Test fun upgradesV6DroppingNeedsKeepingExpenses() = runBlocking {
        val name = "v6-upgrade.db"
        context.deleteDatabase(name)
        // v6 schema as MIGRATION_1_2..5_6 leave it.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color_hex` TEXT NOT NULL, `icon_key` TEXT NOT NULL, `is_archived` INTEGER NOT NULL, `owner_id` TEXT, `family_id` TEXT, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            it.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` TEXT NOT NULL, `description` TEXT NOT NULL, `amount` INTEGER NOT NULL, `category_id` TEXT NOT NULL, `date` INTEGER NOT NULL, `source` TEXT NOT NULL, `raw_input` TEXT, `is_private` INTEGER NOT NULL, `pending_category` TEXT, `frozen` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `owner_id` TEXT, `family_id` TEXT, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `dirty` INTEGER NOT NULL, `transfer_to` TEXT, PRIMARY KEY(`id`))")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_category_id` ON `expenses` (`category_id`)")
            it.execSQL("CREATE TABLE IF NOT EXISTS `needs` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `owner_id` TEXT, `family_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `dirty` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'new', `need_money` INTEGER NOT NULL DEFAULT 0, `link` TEXT, `photo_path` TEXT, `comment_count` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
            it.execSQL("CREATE TABLE IF NOT EXISTS `contributions` (`id` TEXT NOT NULL, `amount` INTEGER NOT NULL, `note` TEXT NOT NULL, `date` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            it.execSQL("INSERT INTO categories VALUES ('c1', 'Old', 'AAAAAA', 'category', 0, NULL, NULL, 1, NULL, 0)")
            it.execSQL("INSERT INTO expenses (id, description, amount, category_id, date, source, is_private, frozen, created_at, updated_at, dirty) VALUES ('e1', 'Non', 5000, 'c1', 1, 'manual', 0, 0, 1, 1, 0)")
            it.execSQL("INSERT INTO needs (id, text, done, created_at, updated_at, dirty) VALUES ('n1', 'Non', 0, 1, 1, 1)")
            it.version = 6
        }
        val upgraded = AppDatabase.open(context, name)
        try {
            assertEquals(listOf("Non"), upgraded.dao().getExpenses().map { it.description })
            assertFalse(upgraded.hasTable("needs"))
            assertEquals("dirty needs no longer counted", 0, upgraded.dao().watchDirtyCount().first())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun seedsFiveDefaultCategories() = runBlocking {
        val cats = db.getCategories()
        assertEquals(5, cats.size)
        assertTrue(cats.any { it.name == "Food & dining" })
    }

    @Test fun insertReadBackAndAggregate() = runBlocking {
        val food = cat("Food & dining")
        db.insertExpense(expense("Coffee", 45000, food.id, LocalDate.of(2026, 7, 5), ExpenseSource.typed).copy(rawInput = "Coffee, 45 000"))
        db.insertExpense(expense("Lunch", 30000, food.id, LocalDate.of(2026, 7, 6)))

        assertEquals(2, db.watchExpenses().first().size)
        val start = LocalDate.of(2026, 7, 1).startMillis()
        val end = LocalDate.of(2026, 8, 1).startMillis()
        assertEquals(75000L, db.totalSpent(start, end))
        assertEquals(75000L, db.categoryTotals(start, end)[food.id])
        // End bound is exclusive.
        assertEquals(45000L, db.totalSpent(start, LocalDate.of(2026, 7, 6).startMillis()))
        assertEquals(0L, db.totalSpent(0, 1))
    }

    @Test fun softDeleteHidesRowAndMarksDirty() = runBlocking {
        val food = cat("Food & dining")
        val id = db.insertExpense(expense("Coffee", 1000, food.id, LocalDate.of(2026, 7, 5)).copy(dirty = false))
        db.deleteExpense(id)
        assertTrue(db.getExpenses().isEmpty())
        assertEquals(0L, db.totalSpent(0, Long.MAX_VALUE))
    }

    @Test fun updateSetsDirtyAndUpdatedAt() = runBlocking {
        val food = cat("Food & dining")
        val id = db.insertExpense(expense("Coffee", 1000, food.id, LocalDate.of(2026, 7, 5)).copy(dirty = false, updatedAt = 0))
        db.updateExpense(db.getExpenses().first { it.id == id }.copy(amount = 2000))
        val e = db.getExpenses().single()
        assertEquals(2000L, e.amount)
        assertTrue(e.dirty)
        assertTrue(e.updatedAt > 0)
    }

    @Test fun resetLocalDropsRowsAndReseeds() = runBlocking {
        val gym = db.insertCategory(Category(name = "Gym", colorHex = "123456"))
        db.insertExpense(expense("X", 1, gym, LocalDate.of(2026, 7, 5)))
        db.resetLocal()
        assertTrue(db.getExpenses().isEmpty())
        assertEquals(5, db.getAllCategories().size)
    }

    @Test fun renameCategoryPersists() = runBlocking {
        val food = cat("Food & dining")
        db.updateCategory(food.copy(name = "Eating out"))
        assertEquals("Eating out", db.getCategories().first { it.id == food.id }.name)
    }

    @Test fun archiveHidesUntilUnarchived() = runBlocking {
        val transport = cat("Transport")
        db.updateCategory(transport.copy(isArchived = true))
        assertFalse(db.getCategories().any { it.id == transport.id })
        assertTrue(db.getAllCategories().any { it.id == transport.id })

        db.updateCategory(db.getAllCategories().first { it.id == transport.id }.copy(isArchived = false))
        assertTrue(db.getCategories().any { it.id == transport.id })
    }

    @Test fun matcherReusesIgnoringCaseAndWhitespace() = runBlocking {
        val before = db.getCategories().size
        assertEquals(cat("Food & dining").id, matchOrCreateCategory(db, "food & dining"))
        assertEquals(cat("Transport").id, matchOrCreateCategory(db, "  Transport "))
        assertEquals(before, db.getCategories().size)
    }

    @Test fun matcherCreatesOnceThenReuses() = runBlocking {
        val before = db.getCategories().size
        val id1 = matchOrCreateCategory(db, "Coffee shops")
        assertEquals(before + 1, db.getCategories().size)
        assertEquals(id1, matchOrCreateCategory(db, "coffee shops "))
        assertEquals(before + 1, db.getCategories().size)

        val created = db.getCategories().first { it.id == id1 }
        assertEquals("Coffee shops", created.name) // original display name
        assertTrue(Regex("^[0-9A-Fa-f]{6}$").matches(created.colorHex))
    }

    @Test fun everyCategoryGetsItsOwnColor() = runBlocking {
        repeat(20) { matchOrCreateCategory(db, "Cat $it") }
        val cats = db.getAllCategories()
        assertEquals(cats.size, cats.map { it.colorHex }.toSet().size)
    }

    @Test fun manualExpensePersistsWithSource() = runBlocking {
        val food = db.getCategories().first()
        db.insertExpense(
            Expense(description = "Coffee", amount = parseAmount("45 000")!!, categoryId = food.id,
                date = LocalDateTime.now().toMillis(), source = ExpenseSource.manual),
        )
        val list = db.watchExpenses().first()
        assertEquals(1, list.size)
        assertEquals(ExpenseSource.manual, list.first().source)
        assertEquals(45000L, list.first().amount)
    }

    @Test fun settingsDefaultThenPersist() {
        val prefs = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        val store = SettingsStore(prefs)
        assertEquals("uz_UZ", store.settings.value.sttLocale)
        assertFalse(store.profileDirty)

        store.applyProfile(displayName = "Ali")
        assertFalse("pulled values aren't pushed back", store.profileDirty)
        assertEquals("Ali", store.settings.value.displayName)

        // received: cached per month, written through, 0 in another month
        store.setReceived("2026-10", 500)
        assertEquals(500L, SettingsStore(prefs).load().receivedIn(LocalDate.of(2026, 10, 31)))
        assertEquals(0L, store.settings.value.receivedIn(LocalDate.of(2026, 11, 1)))

        store.markProfileClean()
        store.setDisplayName("Vali")
        assertTrue(store.profileDirty)
        assertEquals("Vali", SettingsStore(prefs).load().displayName)

        store.lastAddMode = "voice"
        assertEquals("voice", SettingsStore(prefs).lastAddMode)
    }

    @Test fun containerDatabaseIsSeeded() = runBlocking {
        assertEquals(5, context.applicationContext.let { it as App }.container.db.getCategories().size)
    }
}
