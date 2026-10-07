package com.sarvarbek.expense_tracker.services

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sarvarbek.expense_tracker.data.Expense
import java.io.File
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.common.t

/** RFC 4180 field: quoted (inner quotes doubled) only when it needs to be. */
private fun field(v: Any): String {
    val s = v.toString()
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
}

/** Expenses CSV (CRLF rows, ISO dates). Pure — directly unit-testable. */
fun expensesCsv(rows: List<Expense>, catNames: Map<String, String>): String =
    (listOf(listOf("date", "description", "category", "amount", "source")) +
        rows.map { e -> listOf(e.date.toLocalDate(), e.description, catNames[e.categoryId] ?: "", e.amount, e.source.name) })
        .joinToString("\r\n") { row -> row.joinToString(",", transform = ::field) }

/** Writes [csv] to a cache file and opens the share sheet (FileProvider, see file_paths.xml). */
fun shareCsv(context: Context, csv: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "expenses_${System.currentTimeMillis()}.csv").apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, t("svc.csv.share_text"))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
