package com.sarvarbek.expense_tracker.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json

/**
 * UI strings from `assets/i18n/<lang>.json` (`uz` Latin, `uz_cyrl` Cyrillic; same keys).
 * Snapshot state: composables reading [t] recompose when the language changes.
 */
object I18n {
    const val LATIN = "uz"
    const val CYRILLIC = "uz_cyrl"

    var strings by mutableStateOf(emptyMap<String, String>())

    fun load(context: Context, lang: String) {
        val file = if (lang == CYRILLIC) CYRILLIC else LATIN
        strings = parse(context.assets.open("i18n/$file.json").bufferedReader().use { it.readText() })
    }

    fun parse(json: String): Map<String, String> = Json.decodeFromString(json)
}

/** Translated string for [key]; [args] fill `%1$s`-style slots. A missing key shows as itself. */
fun t(key: String, vararg args: Any?): String {
    val s = I18n.strings[key] ?: key
    return if (args.isEmpty()) s else s.format(*args)
}
