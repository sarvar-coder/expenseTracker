package com.sarvarbek.expense_tracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private data class Tab(val labelKey: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("shell.home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab("shell.insights", Icons.Outlined.DonutLarge, Icons.Rounded.DonutLarge),
    Tab("shell.discuss", Icons.Outlined.Forum, Icons.Rounded.Forum),
    Tab("shell.family", Icons.Outlined.FamilyRestroom, Icons.Rounded.FamilyRestroom),
)

// Bar geometry, after Telegram's MainTabsLayout (DialogsActivity.MAIN_TABS_*): a 56dp pill floating 8dp off
// the edges, tabs inset 4dp inside it, at most 320dp of tabs.
private val BAR_HEIGHT = 56.dp
private val BAR_MARGIN = 8.dp
private val BAR_INSET = 4.dp
private val TABS_MAX = 320.dp
private val FAB_SIZE = 56.dp
private val FAB_GAP = 12.dp
/** Height the floating bar takes above the system nav bar, margins included. */
private val BAR_SPACE = BAR_HEIGHT + BAR_MARGIN * 2

/**
 * Bottom padding a tab page's scrolling list needs so its last row clears the floating bar and the add
 * button (they float over the page, content scrolls behind them). Includes the system nav bar.
 */
val LocalShellBottom = staticCompositionLocalOf { 0.dp }

/** Bumped each time the user taps the tab that's already showing. */
private val LocalReselect = compositionLocalOf { 0 }

/** List state for a tab page's main list: scrolls back to the top when its tab is tapped again. */
@Composable
fun rememberShellListState(): LazyListState {
    val state = rememberLazyListState()
    val reselect = LocalReselect.current
    LaunchedEffect(reselect) { if (reselect > 0) state.animateScrollToItem(0) }
    return state
}

/**
 * Shell: 4 tabs in a floating Telegram-style bar + a round add button above it. Visited tabs stay
 * composed, so a switch is a 150 ms crossfade of ready content: no data reload, no empty-state flash,
 * scroll kept. [page] gets whether its tab is showing.
 * ponytail: hidden tabs keep collecting their flows; unload if a tab gets heavy.
 */
@Composable
fun Shell(
    onAdd: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    /** Switch to this tab once (a notification tap), then [onTab]. */
    tab: Int? = null,
    onTab: () -> Unit = {},
    page: @Composable (index: Int, active: Boolean) -> Unit = { i, _ -> Placeholder(tabs[i].labelKey) },
) {
    val c = AppTheme.colors
    // Keys name the tab layout: a state saved by another layout (index 2 was Oila, then Kerakli) is ignored, not restored.
    var index by rememberSaveable(key = "tab5") { mutableIntStateOf(0) }
    var visited by rememberSaveable(key = "visited5") { mutableStateOf(listOf(0)) }
    var reselect by remember { mutableStateOf(List(tabs.size) { 0 }) }
    LaunchedEffect(tab) { if (tab != null) { index = tab; onTab() } }
    if (index !in visited) visited = visited + index
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        CompositionLocalProvider(LocalShellBottom provides navBottom + BAR_SPACE + FAB_SIZE + FAB_GAP) {
            Box(Modifier.statusBarsPadding().fillMaxSize()) {
                for (i in visited) key(i) {
                    val active = i == index
                    val alpha by animateFloatAsState(if (active) 1f else 0f, tween(150), label = "tab")
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (active) 1f else 0f)
                            // Faded-out tabs aren't placed: no drawing, touches or semantics.
                            .layout { m, c -> m.measure(c).let { p -> layout(p.width, p.height) { if (active || alpha > 0f) p.placeWithLayer(0, 0) { this.alpha = alpha } } } }
                            .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
                    ) { CompositionLocalProvider(LocalReselect provides reselect[i]) { page(i, active) } }
                }
            }
        }
        // Content dissolves into the background under the bar (Telegram's 60dp fade).
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBottom + BAR_SPACE)
                .background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg.copy(alpha = 0.92f)))),
        )
        NavBar(
            index,
            onTap = { i ->
                if (i == index) reselect = reselect.toMutableList().also { it[i]++ } else index = i
            },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(BAR_MARGIN),
        )
        Fab(onAdd, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = BAR_SPACE + 4.dp))
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = BAR_SPACE + FAB_SIZE + FAB_GAP)) {
            snackbarHost()
        }
    }
}

// ponytail: empty tab bodies until each feature step lands.
@Composable
internal fun Placeholder(@Suppress("UNUSED_PARAMETER") label: String = "") = Box(Modifier.fillMaxSize())

/** One pass of Telegram's tab fitting: label size and side padding per tab. */
internal data class TabPass(val textSp: Float, val padDp: Float)

internal val TAB_PASSES = listOf(TabPass(12f, 16f), TabPass(12f, 8f), TabPass(10f, 4f))

/**
 * Tab widths, ported from Telegram's MainTabsLayout.onMeasure: try each pass until labels + padding fit
 * [avail]; tabs that fit an equal share grow to fill [minTotal], an overflowing row shrinks to [avail].
 * [textWidth] gives each label's width at a text size. Returns the chosen pass and the widths (px).
 */
internal fun fitTabs(textWidth: (sizeSp: Float) -> List<Float>, padPx: (dp: Float) -> Float, avail: Float, minTotal: Float): Pair<TabPass, List<Float>> {
    var chosen = TAB_PASSES.last()
    var texts = textWidth(chosen.textSp)
    for (p in TAB_PASSES) {
        val w = textWidth(p.textSp)
        if (w.sumOf { (it + padPx(p.padDp) * 2).toDouble() } <= avail || p == TAB_PASSES.last()) { chosen = p; texts = w; break }
    }
    val pad = padPx(chosen.padDp)
    val equalText = avail / texts.size - pad * 2
    val widths = texts.map { it + pad * 2 }.toMutableList()
    var weights = texts.map { if (it + pad * 2 > equalText + pad * 2) 0 else 1 }
    if (weights.sum() == 0) weights = texts.map { 1 }
    val total = widths.sum()
    if (total > avail) {
        val m = avail / total
        for (i in widths.indices) widths[i] *= m
    } else if (total < minTotal) {
        val grow = (minTotal - total) / weights.sum()
        for (i in widths.indices) widths[i] += grow * weights[i]
    }
    return chosen to widths
}

private val decelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val easeOutQuint = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Floating frosted pill (Telegram 12.4+ main tabs): text-measured tabs, one selection pill that springs
 * between them, long-press + drag to slide the pill and pick a tab on release.
 */
@Composable
private fun NavBar(index: Int, onTap: (Int) -> Unit, modifier: Modifier) {
    val c = AppTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val labels = tabs.map { t(it.labelKey) }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val availDp = minOf(maxWidth - BAR_INSET * 2, TABS_MAX)
        val (pass, widthsPx) = remember(labels, availDp, density, labelStyle) {
            with(density) {
                fitTabs(
                    textWidth = { sp -> labels.map { measurer.measure(it, labelStyle.copy(fontSize = sp.sp), maxLines = 1).size.width.toFloat() } },
                    padPx = { it.dp.toPx() },
                    avail = availDp.toPx(),
                    minTotal = availDp.toPx(),
                )
            }
        }
        val lefts = remember(widthsPx) { widthsPx.runningFold(0f) { a, w -> a + w } }
        val centers = remember(widthsPx) { widthsPx.indices.map { lefts[it] + widthsPx[it] / 2 } }

        var dragX by remember { mutableStateOf<Float?>(null) }
        fun nearestTo(x: Float) = centers.indices.minBy { abs(centers[it] - x) }
        val nearest = dragX?.let(::nearestTo)
        // The gesture block outlives recompositions: read the latest callback through this.
        val tap by rememberUpdatedState(onTap)
        val shown = nearest ?: index
        val pillCenter = remember { Animatable(centers[index]) }
        val pillWidth = remember { Animatable(widthsPx[index]) }
        // Pill target: the finger while dragging (width blends between the two nearest tabs), else the tab.
        val target = dragX?.let { x ->
            val cx = x.coerceIn(centers.first(), centers.last())
            val r = centers.indexOfFirst { it >= cx }.coerceAtLeast(1)
            val f = (cx - centers[r - 1]) / (centers[r] - centers[r - 1])
            cx to widthsPx[r - 1] + (widthsPx[r] - widthsPx[r - 1]) * f
        } ?: (centers[index] to widthsPx[index])
        LaunchedEffect(target) {
            val s = spring<Float>(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)
            launch { pillCenter.animateTo(target.first, s) }
            launch { pillWidth.animateTo(target.second, s) }
        }
        LaunchedEffect(nearest) { if (nearest != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
        val pressScale by animateFloatAsState(if (dragX != null) 1.019f else 1f, tween(380, easing = easeOutQuint), label = "barScale")

        val shape = CircleShape
        Box(
            Modifier
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .dropShadow(shape, Shadow(radius = 2.67.dp, offset = DpOffset(0.dp, 0.85.dp), color = c.glassShadow))
                .clip(shape)
                .background(c.card.copy(alpha = 0.85f))
                .border(0.4.dp, c.glassStroke, shape)
                .height(BAR_HEIGHT)
                .padding(BAR_INSET)
                .pointerInput(centers) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o: Offset -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); dragX = o.x },
                        onDrag = { change, _ -> dragX = change.position.x; change.consume() },
                        onDragEnd = { dragX?.let { tap(nearestTo(it)) }; dragX = null },
                        onDragCancel = { dragX = null },
                    )
                }
                .selectableGroup(),
        ) {
            // Selection pill behind the tabs: 9% of the selected colour, fully rounded.
            Box(
                Modifier
                    .offset { IntOffset((pillCenter.value - pillWidth.value / 2).roundToInt(), 0) }
                    .width(with(density) { pillWidth.value.toDp() })
                    .fillMaxHeight()
                    .background(c.accent.copy(alpha = 0.09f), CircleShape),
            )
            Row(Modifier.fillMaxHeight()) {
                tabs.forEachIndexed { i, tab ->
                    NavItem(
                        tab, labels[i], selected = i == index, highlighted = i == shown, textSp = pass.textSp,
                        onTap = { onTap(i) },
                        modifier = Modifier.width(with(density) { widthsPx[i].toDp() }),
                    )
                }
            }
        }
    }
}

/** Tab: 24dp icon over a 12sp bold label; colour blends to accent over 320 ms when highlighted. */
@Composable
private fun NavItem(tab: Tab, label: String, selected: Boolean, highlighted: Boolean, textSp: Float, onTap: () -> Unit, modifier: Modifier) {
    val c = AppTheme.colors
    val f by animateFloatAsState(if (highlighted) 1f else 0f, tween(320, easing = decelerate), label = "tabColor")
    val color = lerp(c.navInactive, c.accent, f)
    Column(
        modifier
            .fillMaxHeight()
            .selectable(selected = selected, interactionSource = null, indication = null, role = Role.Tab, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ponytail: Telegram morphs outline → filled with Lottie; we swap the static icons.
        Icon(if (highlighted) tab.selectedIcon else tab.icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontSize = textSp.sp,
                fontWeight = if (highlighted) FontWeight.ExtraBold else FontWeight.Bold,
            ),
        )
    }
}

/** Round add button floating above the bar, bottom-right (Telegram's compose button spot). */
@Composable
private fun Fab(onClick: () -> Unit, modifier: Modifier) {
    val c = AppTheme.colors
    Box(
        modifier
            .shadow(12.dp, CircleShape, ambientColor = c.accent.copy(alpha = 0.35f), spotColor = c.accent.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(c.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .size(FAB_SIZE)
            .semantics { contentDescription = t("add.title_new") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, null, tint = c.onAccent, modifier = Modifier.size(28.dp))
    }
}
