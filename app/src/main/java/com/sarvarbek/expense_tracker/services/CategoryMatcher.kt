package com.sarvarbek.expense_tracker.services

import androidx.core.graphics.ColorUtils
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.ExpenseDao

/**
 * Distinct hues for new categories (6-hex, no '#'). Seed colors first so they
 * are skipped (already used); the rest keep pie-chart slices easy to tell apart.
 */
private val palette = listOf(
    "E08A5B", "6FA86A", "C07FA6", "5B8DB8", "D9A24E", "7E88C3",
    "D0655F", "4FA3A0", "9C7A56", "A3B84F", "8D6BC4", "E07FA0",
)

private fun norm(s: String) = s.trim().lowercase()

/**
 * First palette color not taken by any category (archived included, so
 * unarchiving can't clash); past the palette, golden-angle hues.
 */
fun uniqueColor(used: Set<String>): String {
    palette.firstOrNull { it !in used }?.let { return it }
    return generateSequence(0) { it + 1 }
        .map { n -> ColorUtils.HSLToColor(floatArrayOf(((n * 137.508) % 360).toFloat(), 0.45f, 0.55f)) }
        .map { "%06X".format(it and 0xFFFFFF) }
        .first { it !in used }
}

/**
 * Finds an existing category whose name matches [rawName] (trim + case
 * insensitive) and returns its id; otherwise creates one and returns the new id.
 * Keeps categories unique — the AI never spawns duplicates.
 */
suspend fun matchOrCreateCategory(db: ExpenseDao, rawName: String): String {
    find(db.getCategories(), rawName)?.let { return it }
    val used = db.getAllCategories().map { it.colorHex.uppercase() }.toSet()
    return db.insertCategory(Category(name = rawName.trim(), colorHex = uniqueColor(used)))
}

private fun find(cats: List<Category>, name: String) = cats.firstOrNull { norm(it.name) == norm(name) }?.id
