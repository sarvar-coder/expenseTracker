package com.sarvarbek.expense_tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Badge
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.ui.common.Money
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sarvarbek.expense_tracker.ui.Shell
import com.sarvarbek.expense_tracker.ui.back
import com.sarvarbek.expense_tracker.ui.go
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppColors
import com.sarvarbek.expense_tracker.ui.theme.AppSnackbar
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.SecondaryButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShellTest {
    @get:Rule val rule = createComposeRule()

    @Test fun navDropsDoublePushAndNeverPopsShell() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, "shell") { composable("shell") {}; composable("add") {} }
        }
        rule.runOnIdle { nav.go("add"); nav.go("add") }
        rule.runOnIdle {
            assertEquals("add", nav.currentDestination?.route)
            assertEquals("shell", nav.previousBackStackEntry?.destination?.route)
        }
        rule.runOnIdle { nav.back(); nav.back() }
        rule.runOnIdle { assertEquals("shell", nav.currentDestination?.route) }
    }

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

    @Test fun tabsStayAliveAndOnlyActiveIsVisible() {
        var composed = 0
        rule.setContent {
            AppTheme {
                Shell(onAdd = {}) { i, _ ->
                    if (i == 0) {
                        val n = androidx.compose.runtime.remember { ++composed }
                        Text("page0 $n")
                    } else Text("page$i")
                }
            }
        }
        rule.onNodeWithText("Tarix").performClick()
        rule.onNodeWithText("page1").assertExists()
        rule.onNodeWithText("page0", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Asosiy").performClick()
        // Same `remember` instance: tab 0 was never disposed and rebuilt.
        rule.onNodeWithText("page0 1").assertExists()
        rule.onNodeWithText("page1").assertDoesNotExist()
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

/** Text color comes from its container, so text on hero/accent/danger surfaces stays readable in both modes. */
@RunWith(RobolectricTestRunner::class)
class TextColorTest {
    @get:Rule val rule = createComposeRule()

    private fun colorOf(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    @Test fun lightTextColors() = check(dark = false)

    @Test fun darkTextColors() = check(dark = true)

    private fun check(dark: Boolean) {
        val c = if (dark) AppColors.Dark else AppColors.Light
        val snack = object : SnackbarData {
            override val visuals = object : SnackbarVisuals {
                override val message = "Saqlandi"
                override val actionLabel: String? = null
                override val withDismissAction = false
                override val duration = SnackbarDuration.Short
            }
            override fun performAction() {}
            override fun dismiss() {}
        }
        rule.setContent {
            AppTheme(dark = dark) {
                Column {
                    AppSnackbar(snack)
                    AppChip("Tanlangan", selected = true, onClick = {})
                    Badge(containerColor = AppTheme.colors.danger) { Text("7") }
                    AppCard { Text("Kartada"); Money(1000) }
                }
            }
        }
        assertEquals(c.onHero, colorOf("Saqlandi"))
        assertEquals(c.onAccent, colorOf("Tanlangan"))
        assertNotEquals(c.muted, colorOf("7"))
        assertEquals(c.text, colorOf("Kartada"))
        assertEquals(c.text, colorOf("UZS")) // BasicText has no content color; Money must fall back to it
    }
}
