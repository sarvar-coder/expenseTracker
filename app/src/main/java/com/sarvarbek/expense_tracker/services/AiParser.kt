package com.sarvarbek.expense_tracker.services

import android.util.Log
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/** AI-derived expense fields. [amount] is whole UZS units. */
data class ParsedExpense(val item: String, val amount: Long, val category: String, val date: LocalDate)

/**
 * Validates a Gemini reply into a [ParsedExpense]. Tolerates markdown fences /
 * surrounding prose (extracts the first `{...}` block) and amount as number or
 * separator-formatted string. A missing, invalid or future `date` becomes
 * [today]. Returns null on anything else invalid so callers fall back to
 * Manual entry.
 */
fun parseGeminiJson(text: String, today: LocalDate): ParsedExpense? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val m = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
    val item = m.str("item")?.trim().orEmpty()
    val category = m.str("category")?.trim().orEmpty()
    val raw = m["amount"] as? JsonPrimitive
    val amount = when {
        raw == null -> null
        raw.isString -> parseAmount(raw.content)
        else -> raw.content.toDoubleOrNull()?.toLong()
    }
    if (item.isEmpty() || category.isEmpty() || amount == null || amount <= 0) return null
    val day = m.str("date")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    return ParsedExpense(item, amount, category, if (day == null || day.isAfter(today)) today else day)
}

/** Calls the `parse-expense` Edge Function, which holds the Gemini key. */
class AiParser(private val client: SupabaseClient) {
    /** [categories] are existing names the model should reuse. */
    suspend fun parse(rawInput: String, categories: List<String> = emptyList()): ParsedExpense? {
        // ponytail: null on any failure (network/auth/bad JSON) — callers fall
        // back to Manual entry. Logic worth testing lives in [parseGeminiJson].
        val today = LocalDate.now()
        return try {
            val res = client.functions.invoke("parse-expense", body = buildJsonObject {
                put("input", rawInput)
                put("today", today.toString())
                putJsonArray("categories") { categories.forEach { add(it) } }
            })
            val body = res.bodyAsText()
            val text = (runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.str("text")
            text?.let { parseGeminiJson(it, today) }.also { if (it == null) Log.w("ai", "parse-expense: unusable reply $body") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ai", "parse-expense failed: $e")
            null
        }
    }
}
