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
import com.sarvarbek.expense_tracker.ui.LocalShellBottom
import com.sarvarbek.expense_tracker.ui.Shell
import com.sarvarbek.expense_tracker.ui.TAB_PASSES
import com.sarvarbek.expense_tracker.ui.fitTabs
import com.sarvarbek.expense_tracker.ui.rememberShellListState
import com.sarvarbek.expense_tracker.ui.theme.AppTabs
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
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

        for (label in listOf("Asosiy", "Tahlil", "Muhokama", "Oila")) {
            rule.onNodeWithText(label, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("Tarix", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Tahlil").assertIsNotSelected().performClick()
        rule.onNodeWithText("Tahlil").assertIsSelected()
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
        rule.onNodeWithText("Tahlil").performClick()
        rule.onNodeWithText("page1").assertExists()
        rule.onNodeWithText("page0", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Asosiy").performClick()
        // Same `remember` instance: tab 0 was never disposed and rebuilt.
        rule.onNodeWithText("page0 1").assertExists()
        rule.onNodeWithText("page1").assertDoesNotExist()
    }

    @Test fun retapScrollsActiveTabToTop() {
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        rule.setContent {
            AppTheme {
                Shell(onAdd = {}) { i, _ ->
                    if (i == 0) {
                        list = rememberShellListState()
                        LazyColumn(state = list) { items(200) { Text("row $it", Modifier.height(48.dp)) } }
                    }
                }
            }
        }
        rule.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollToItem(120) } }
        rule.onNodeWithText("Asosiy").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
    }

    @Test fun pagesLeaveRoomForBarAndFab() {
        var bottom = 0.dp
        rule.setContent { AppTheme { Shell(onAdd = {}) { _, _ -> bottom = LocalShellBottom.current } } }
        // Bar (56 + 2×8 margin) plus the 56dp button and its gap.
        rule.runOnIdle { assertTrue("bottom=$bottom", bottom >= 140.dp) }
    }

    @Test fun longPressDragPicksTabUnderFinger() {
        rule.setContent { AppTheme { Shell(onAdd = {}) } }
        val start = rule.onNodeWithText("Asosiy").fetchSemanticsNode().boundsInRoot.center
        val end = rule.onNodeWithText("Oila").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(start) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onRoot().performTouchInput {
            moveTo(Offset((start.x + end.x) / 2, start.y))
            moveTo(end)
            up()
        }
        rule.onNodeWithText("Oila").assertIsSelected()
        rule.onNodeWithText("Asosiy").assertIsNotSelected()
    }

    @Test fun fitTabsFollowsTelegramPasses() {
        val pad = { dp: Float -> dp }
        // Short labels: first pass (16dp padding), grown to fill.
        val (p1, w1) = fitTabs({ listOf(40f, 40f, 60f, 30f) }, pad, avail = 320f, minTotal = 320f)
        assertEquals(TAB_PASSES[0], p1)
        assertEquals(320f, w1.sum(), 0.01f)
        // Too long for 16dp padding but fits with 8dp: second pass.
        val (p2, w2) = fitTabs({ listOf(60f, 60f, 60f, 60f) }, pad, avail = 320f, minTotal = 320f)
        assertEquals(TAB_PASSES[1], p2)
        assertEquals(320f, w2.sum(), 0.01f)
        // Nothing fits: last pass, shrunk to the available width.
        val (p3, w3) = fitTabs({ sp -> List(4) { sp * 10f } }, pad, avail = 320f, minTotal = 320f)
        assertEquals(TAB_PASSES[2], p3)
        assertEquals(320f, w3.sum(), 0.01f)
        // A wide label keeps its width; only the others grow (8dp pass: 210 + 64 fits).
        val (_, w4) = fitTabs({ listOf(20f, 20f, 20f, 150f) }, pad, avail = 320f, minTotal = 320f)
        assertEquals(166f, w4[3], 0.01f) // 8dp pass: 150 + 2×8
    }

    @Test fun appTabsSelectAndMeetTouchTarget() {
        var picked = 0
        rule.setContent {
            AppTheme {
                var sel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                AppTabs(listOf("Yozish", "Aytish", "Qo'lda"), sel, { sel = it; picked = it })
            }
        }
        rule.onNodeWithText("Yozish").assertIsSelected()
        rule.onNodeWithText("Qo'lda").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Qo'lda").assertIsSelected()
        rule.onNodeWithText("Yozish").assertIsNotSelected()
        assertEquals(2, picked)
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
