package com.sarvarbek.expense_tracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.theme.AppMotion
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppTheme

private data class Tab(val labelKey: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("shell.home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab("shell.history", Icons.Outlined.ReceiptLong, Icons.Rounded.ReceiptLong),
    Tab("shell.insights", Icons.Outlined.DonutLarge, Icons.Rounded.DonutLarge),
    Tab("shell.family", Icons.Outlined.FamilyRestroom, Icons.Rounded.FamilyRestroom),
)

/** Bottom-nav shell: 4 tabs (state kept per tab) + bottom-right FAB that opens Add. */
@Composable
fun Shell(
    onAdd: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    page: @Composable (index: Int, select: (Int) -> Unit) -> Unit = { i, _ -> Placeholder(tabs[i].labelKey) },
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    Scaffold(
        containerColor = AppTheme.colors.bg,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = { Fab(onAdd) },
        snackbarHost = snackbarHost,
        bottomBar = { NavBar(index) { index = it } },
    ) { pad ->
        Box(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) {
            AnimatedContent(index, Modifier.fillMaxSize(), transitionSpec = { AppMotion.tabIn togetherWith AppMotion.tabOut }, label = "tab") { i ->
                holder.SaveableStateProvider(i) { page(i) { index = it } }
            }
        }
    }
}

// ponytail: empty tab bodies until each feature step lands.
@Composable
internal fun Placeholder(@Suppress("UNUSED_PARAMETER") label: String = "") = Box(Modifier.fillMaxSize())

@Composable
private fun NavBar(index: Int, onTap: (Int) -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(topStart = AppRadii.card, topEnd = AppRadii.card)
    Box(
        Modifier
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.06f))
            .background(c.card, shape)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(60.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                NavItem(tab, selected = i == index, onTap = { onTap(i) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private val easeOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** Tab: filled icon in a tinted pill when selected, outlined otherwise. */
@Composable
private fun NavItem(tab: Tab, selected: Boolean, onTap: () -> Unit, modifier: Modifier) {
    val c = AppTheme.colors
    val color = if (selected) c.accent else c.navInactive
    val pill by animateDpAsState(if (selected) 56.dp else 40.dp, tween(220, easing = easeOutCubic), label = "pill")
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(AppRadii.md))
            .selectable(selected = selected, role = Role.Tab, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(pill)
                .height(30.dp)
                .background(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            t(tab.labelKey),
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
        )
    }
}

/** Floating add button: chunky accent square above the bar. */
@Composable
private fun Fab(onClick: () -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(AppRadii.card)
    Box(
        Modifier
            .shadow(18.dp, shape, ambientColor = c.accent.copy(alpha = 0.35f), spotColor = c.accent.copy(alpha = 0.35f))
            .clip(shape)
            .background(c.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .size(64.dp)
            .semantics { contentDescription = t("add.title_new") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, null, tint = c.onAccent, modifier = Modifier.size(30.dp))
    }
}
