package com.sarvarbek.expense_tracker.ui.common

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val grouping = DecimalFormat("#,###", DecimalFormatSymbols().apply { groupingSeparator = ' ' })

/** UZS-style grouped amount with space separators, e.g. 2450000 -> "2 450 000". */
fun formatMoney(amount: Long): String = grouping.format(amount)

/** Uzbek month names (hardcoded — no locale data needed). */
val uzMonths = listOf(
    "Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
    "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr",
)

/** "5 Iyul" */
fun uzDayMonth(d: LocalDate) = "${d.dayOfMonth} ${uzMonths[d.monthValue - 1]}"

/**
 * Parse a user-entered amount, ignoring spaces/separators. "45 000" -> 45000.
 * Null when no digits are present (or too many to fit).
 */
fun parseAmount(raw: String): Long? = raw.filter { it in '0'..'9' }.toLongOrNull()

// Expense dates are epoch millis in the device's zone.
fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

fun Long.toLocalDate(): LocalDate = toLocalDateTime().toLocalDate()

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Local midnight starting [this] day, epoch millis. */
fun LocalDate.startMillis(): Long = atStartOfDay().toMillis()
