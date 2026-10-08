package com.sarvarbek.expense_tracker.features.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.features.activity.ActiveFilters
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.features.activity.ActivityFilterPage
import com.sarvarbek.expense_tracker.features.activity.ActivityFilterSaver
import com.sarvarbek.expense_tracker.features.activity.FilterButton
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.mergeFeed
import com.sarvarbek.expense_tracker.ui.common.CategoryBadge
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.colorFromHex
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.monthName
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppSheet
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private const val FIRST_YEAR = 2020 // same floor as the add screen's date picker

/** Last visible month of [year]: future months of the current year are hidden. */
private fun lastMonth(year: Int): Int = LocalDate.now().let { if (year == it.year) it.monthValue else 12 }

/**
 * Tahlil: category-share donut with a center total + ranked legend, over a
 * month of the chosen year, narrowed by the same filter page Tarix uses (its
 * Sana range replaces the month). Aggregation lives in the pure [insightsFor].
 * Data is the family [feed]: all my rows plus other members' shared rows
 * (others' as last fetched when offline); not in a family, just mine.
 */
@Composable
fun InsightsScreen(
    db: ExpenseDao,
    feed: Flow<List<FamilyExpense>> = remember(db) { db.watchExpenses().map { mergeFeed(it, emptyList(), t("family.you")) } },
) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val rows by feed.collectAsStateWithLifecycle(emptyList())
    // Archived too: past spend in an archived category still gets its slice.
    val categories by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(emptyList())
    val now = LocalDate.now()
    var year by rememberSaveable { mutableIntStateOf(now.year) }
    var month by rememberSaveable { mutableIntStateOf(now.monthValue) }
    var saved by rememberSaveable(stateSaver = ActivityFilterSaver) { mutableStateOf(ActivityFilter()) }
    var filterOpen by remember { mutableStateOf(false) }
    var yearSheet by remember { mutableStateOf(false) }

    // Everyone in the feed, ex-members' history included; A'zo row only with someone besides me.
    val members = rows.mapNotNull { r -> r.expense.ownerId?.let { it to r.ownerName } }.toMap()
    // A picked member who left the feed (left the family) stops filtering instead of hiding everything.
    val filter = saved.copy(memberIds = saved.memberIds.filterTo(HashSet()) { it in members })
    val range = filter.range
    val (start, end) = if (range == null) {
        LocalDate.of(year, month, 1).let { it to it.plusMonths(1) }
    } else {
        range.start to range.endInclusive.plusDays(1) // end day inclusive
    }
    val data = insightsFor(rows.map { it.expense }, categories, start, end, filter)

    // Bottom padding clears the floating add button.
    LazyColumn(contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("insights.title"), style = ty.headlineMedium, modifier = Modifier.weight(1f))
                TextButton({ yearSheet = true }, Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text("$year", style = ty.labelLarge.copy(color = c.accent))
                    Icon(Icons.Filled.ExpandMore, null, tint = c.accent)
                }
                FilterButton(filter.count) { filterOpen = true }
            }
            Spacer(Modifier.height(AppSpace.gap))
            if (!filter.isEmpty) {
                ActiveFilters(
                    filter, categories.associateBy { it.id }, onChange = { saved = it }, members = members,
                    // No Sana in the filter: the month still applies, so show it.
                    leading = if (range == null) {
                        { SuggestionChip({}, { Text("${monthName(month)} $year") }) }
                    } else {
                        null
                    },
                )
            } else {
                MonthPills(year, month) { month = it }
            }
            Spacer(Modifier.height(AppSpace.gap))
        }
        if (data.slices.isEmpty()) {
            item { EmptyState(Icons.Outlined.DonutLarge, t("insights.empty_title"), t("insights.empty_body")) }
        } else {
            item { CategoryBreakdown(data) }
        }
    }

    if (yearSheet) {
        YearSheet(year, onDismiss = { yearSheet = false }) { y ->
            yearSheet = false
            year = y
            month = month.coerceAtMost(lastMonth(y))
        }
    }
    if (filterOpen) {
        ActivityFilterPage(
            filter, categories.filter { !it.isArchived }, onDismiss = { filterOpen = false },
            members = if (members.size > 1) members else emptyMap(),
        ) { saved = it; filterOpen = false }
    }
}

/** Month chips for [year], scrolled so the selected one sits in view (also after a year change). */
@Composable
private fun MonthPills(year: Int, month: Int, onPick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    var selectedCenter by remember { mutableIntStateOf(-1) }
    LaunchedEffect(year) {
        val (center, viewport) = snapshotFlow { selectedCenter to scroll.viewportSize }.first { it.first >= 0 && it.second > 0 }
        scroll.animateScrollTo((center - viewport / 2).coerceIn(0, scroll.maxValue))
    }
    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in 1..lastMonth(year)) {
            AppChip(
                monthName(m), month == m, { onPick(m) },
                if (month == m) Modifier.onPlaced { selectedCenter = it.positionInParent().x.toInt() + it.size.width / 2 } else Modifier,
            )
        }
    }
}

@Composable
private fun YearSheet(selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val ty = MaterialTheme.typography
    AppSheet(onDismiss) { hide ->
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = AppSpace.section)) {
            for (y in LocalDate.now().year downTo FIRST_YEAR) {
                Row(
                    Modifier.fillMaxWidth().clickable { hide { onPick(y) } }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$y", style = ty.bodyLarge, modifier = Modifier.weight(1f))
                    if (y == selected) Icon(Icons.Filled.Check, t("add.selected"), tint = AppTheme.colors.accent)
                }
            }
        }
    }
}

/** Donut card, then "Turkumlar" with one share row per category. Shared with Oila. */
@Composable
internal fun CategoryBreakdown(data: InsightsData) {
    AppCard(Modifier.fillMaxWidth()) { Donut(data) }
    Spacer(Modifier.height(AppSpace.section))
    Text(t("insights.categories"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(12.dp))
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            data.slices.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 74.dp, end = 16.dp), color = AppTheme.colors.border)
                LegendRow(s, data.fraction(s.amount))
            }
        }
    }
}

/** Share donut (3dp gaps between slices) with "Jami" + total in the hole. Read out as one label. */
@Composable
private fun Donut(data: InsightsData) {
    val ty = MaterialTheme.typography
    val colors = data.slices.map { colorFromHex(it.category.colorHex) }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 28.dp).height(232.dp)
            .clearAndSetSemantics { contentDescription = t("insights.total_a11y", formatMoney(data.total)) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(216.dp)) {
            val stroke = 30.dp.toPx()
            val radius = size.minDimension / 2 - stroke / 2
            // Gap as an angle at the ring's middle radius; skipped for a single full ring.
            val gap = if (data.slices.size > 1) Math.toDegrees(3.dp.toPx() / radius.toDouble()).toFloat() else 0f
            var angle = -90f
            data.slices.forEachIndexed { i, s ->
                val sweep = (data.fraction(s.amount) * 360).toFloat()
                drawArc(
                    colors[i], angle + gap / 2, (sweep - gap).coerceAtLeast(0.5f), useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(stroke),
                )
                angle += sweep
            }
        }
        Column(Modifier.padding(horizontal = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("insights.total"), color = AppTheme.colors.muted, style = ty.labelMedium)
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.text.BasicText(
                formatMoney(data.total), style = ty.displaySmall.copy(color = LocalContentColor.current, textAlign = TextAlign.Center), maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(maxFontSize = ty.displaySmall.fontSize),
            )
            Text("UZS", color = AppTheme.colors.muted, style = ty.labelSmall)
        }
    }
}

/** Badge; name + amount over a share bar in the category color + percent. */
@Composable
private fun LegendRow(slice: Slice, fraction: Double) {
    val ty = MaterialTheme.typography
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(slice.category)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(slice.category.name, style = ty.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(formatMoney(slice.amount), style = ty.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { fraction.toFloat() },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(AppRadii.chip)),
                    color = colorFromHex(slice.category.colorHex),
                    trackColor = AppTheme.colors.border,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text("${Math.round(fraction * 100)}%", color = AppTheme.colors.muted, style = ty.bodySmall.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            }
        }
    }
}
