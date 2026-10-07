package com.sarvarbek.expense_tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.Need
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.ResolvedCategory
import com.sarvarbek.expense_tracker.services.matchOrCreateCategory
import com.sarvarbek.expense_tracker.services.resolveCategory
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

    @Test fun upgradesV1DatabaseKeepingRowsAddingNeedsAndTransfers() = runBlocking {
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
            dao.insertNeed(Need(text = "Non"))
            assertEquals(listOf("Non"), dao.watchNeeds().first().map { it.text })
            // v3: transfer_to round-trips and stays out of the sums
            val day = LocalDate.of(2026, 10, 7)
            dao.insertExpense(expense("gift", 500, "c1", day).copy(transferTo = "u2"))
            dao.insertExpense(expense("bread", 20, "c1", day))
            assertEquals(listOf("u2", null), dao.getExpenses().sortedByDescending { it.amount }.map { it.transferTo })
            val range = day.startMillis() to day.plusDays(1).startMillis()
            assertEquals(20L, dao.totalSpent(range.first, range.second))
            assertEquals(mapOf("c1" to 20L), dao.categoryTotals(range.first, range.second))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun needsOpenFirstSoftDeleteAndCountAsDirty() = runBlocking {
        db.insertNeed(Need(text = "a", createdAt = 1))
        db.insertNeed(Need(text = "b", createdAt = 2, done = true))
        db.insertNeed(Need(text = "c", createdAt = 3))
        assertEquals(listOf("c", "a", "b"), db.watchNeeds().first().map { it.text })
        val c = db.watchNeeds().first().first()
        db.deleteNeed(c.id)
        assertEquals(listOf("a", "b"), db.watchNeeds().first().map { it.text })
        assertTrue("deletion waits to be pushed", db.dirtyNeeds().any { it.id == c.id && it.deletedAt != null })
        assertTrue(db.watchDirtyCount().first() >= 3)
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

    @Test fun familyMemberUnknownNameGoesToBoshqaPending() = runBlocking {
        val food = cat("Food & dining")
        val boshqa = db.insertCategory(Category(name = "Boshqa", colorHex = "9E9E9E"))
        val before = db.getAllCategories().size

        assertEquals(ResolvedCategory(food.id), resolveCategory(db, "food & dining", canCreate = false))
        assertEquals(ResolvedCategory(boshqa, "Gym"), resolveCategory(db, " Gym ", canCreate = false))
        assertEquals("nothing created", before, db.getAllCategories().size)
    }

    @Test fun canCreateCreatesAsBefore() = runBlocking {
        val r = resolveCategory(db, "Gym", canCreate = true)
        assertNull(r.pending)
        assertTrue(db.getCategories().any { it.id == r.id && it.name == "Gym" })
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
        assertEquals(0L, store.settings.value.monthlyBudget) // unset
        assertEquals("uz_UZ", store.settings.value.sttLocale)
        assertFalse(store.profileDirty)

        store.setBudget(5_000_000)
        assertEquals(5_000_000L, store.settings.value.monthlyBudget)
        assertEquals(5_000_000L, SettingsStore(prefs).load().monthlyBudget) // written through
        assertTrue(store.profileDirty)

        store.markProfileClean()
        store.applyProfile(budget = 1000, defaultPrivate = true, displayName = "Ali")
        assertFalse("pulled values aren't pushed back", store.profileDirty)
        assertEquals(Triple(1000L, true, "Ali"), store.settings.value.let { Triple(it.monthlyBudget, it.defaultPrivate, it.displayName) })

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
