package com.sarvarbek.expense_tracker

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.ui.Shell
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShellTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tabsSwitchAndFabOpensAdd() {
        var added = false
        rule.setContent { AppTheme { Shell(onAdd = { added = true }) } }

        for (label in listOf("Asosiy", "Tarix", "Tahlil", "Oila")) {
            rule.onNodeWithText(label, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("Tarix").assertIsNotSelected().performClick()
        rule.onNodeWithText("Tarix").assertIsSelected()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

        rule.onNodeWithContentDescription("Xarajat qo'shish")
            .assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
            .performClick()
        assertTrue(added)
    }
}
