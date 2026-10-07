package com.sarvarbek.expense_tracker

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.sarvarbek.expense_tracker.ui.common.I18n
import com.sarvarbek.expense_tracker.ui.common.t
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** uz.json and uz_cyrl.json stay in lockstep, and every t("key") in code exists. */
@RunWith(RobolectricTestRunner::class)
class I18nTest {
    @get:Rule val rule = createComposeRule()

    private fun strings(lang: String): Map<String, String> =
        Json.decodeFromString(File("src/main/assets/i18n/$lang.json").readText())

    private val latin = strings(I18n.LATIN)
    private val cyrillic = strings(I18n.CYRILLIC)

    @Test fun sameKeysAndPlaceholders() {
        assertEquals(latin.keys - cyrillic.keys, emptySet<String>())
        assertEquals(cyrillic.keys - latin.keys, emptySet<String>())
        val slot = Regex("%(\\d+\\$)?[sd]")
        for ((k, v) in latin) {
            assertEquals("placeholders of $k", slot.findAll(v).map { it.value }.sorted().toList(),
                slot.findAll(cyrillic.getValue(k)).map { it.value }.sorted().toList())
        }
    }

    @Test fun everyKeyUsedInCodeExists() {
        val used = File("src/main/java").walk().filter { it.extension == "kt" }
            .flatMap { Regex("""\bt\("([^"$]+)"""").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertTrue(used.isNotEmpty())
        assertEquals("missing keys", emptySet<String>(), used - latin.keys)
    }

    @Test fun switchingScriptRecomposes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent { Text(t("common.cancel")) }
        rule.onNodeWithText("Bekor qilish").assertExists()
        try {
            I18n.load(context, I18n.CYRILLIC)
            rule.onNodeWithText("Бекор қилиш").assertExists()
        } finally {
            I18n.load(context, I18n.LATIN)
        }
    }
}
