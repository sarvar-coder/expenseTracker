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
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.SecondaryButton
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

@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun buttonsMeetTouchTargets() {
        rule.setContent {
            AppTheme {
                androidx.compose.foundation.layout.Column {
                    PrimaryButton({}) { androidx.compose.material3.Text("Saqlash") }
                    SecondaryButton({}) { androidx.compose.material3.Text("Bekor") }
                    LinkButton({}) { androidx.compose.material3.Text("Ok") }
                }
            }
        }
        rule.onNodeWithText("Saqlash").assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("Bekor").assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("Ok").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
