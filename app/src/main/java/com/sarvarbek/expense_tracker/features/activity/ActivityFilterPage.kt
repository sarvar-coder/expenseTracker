package com.sarvarbek.expense_tracker.features.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.ui.common.colorFromHex
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppMotion
import com.sarvarbek.expense_tracker.ui.theme.AppSheet
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.SecondaryButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** "5 Iyul" or "5 Iyul – 9 Iyul". Shared by the page and the active chips. */
fun rangeLabel(r: ClosedRange<LocalDate>) =
    if (r.start == r.endInclusive) uzDayMonth(r.start) else "${uzDayMonth(r.start)} – ${uzDayMonth(r.endInclusive)}"

/** "10 000 – 50 000", "10 000 dan", "50 000 gacha". */
fun amountLabel(min: Long?, max: Long?) = when {
    min != null && max != null -> "${formatMoney(min)} – ${formatMoney(max)}"
    min != null -> t("activity.amount_from", formatMoney(min))
    else -> t("activity.amount_to", formatMoney(max!!))
}

/** Tune icon with the active-group count badge. Shared by Tarix and Tahlil. */
@Composable
fun FilterButton(count: Int, onClick: () -> Unit) = IconButton(onClick, Modifier.size(48.dp)) {
    BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
        Icon(Icons.Filled.Tune, t("activity.filter"))
    }
}

/**
 * Tarix filter page, full screen over the shell. [onApply] gets the new
 * filter on Qo'llash; [onDismiss] on back. Non-empty [members] (id to name)
 * means the Oila list: adds an A'zo row.
 * ponytail: Ko'rinish (Shaxsiy/Umumiy) row hidden while Maxfiy is off.
 */
@Composable
fun ActivityFilterPage(
    current: ActivityFilter,
    categories: List<Category>,
    onDismiss: () -> Unit,
    members: Map<String, String> = emptyMap(),
    onApply: (ActivityFilter) -> Unit,
) {
    // Slides in like a pushed page; every close slides out first, then reports.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun close(then: () -> Unit) {
        if (!shown.targetState) return // already closing: first action wins
        after = then
        shown.targetState = false
    }
    Dialog({ close(onDismiss) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AnimatedVisibility(shown, enter = AppMotion.pushEnter, exit = AppMotion.popExit) {
            FilterPage(current, categories, members, onBack = { close(onDismiss) }) { f -> close { onApply(f) } }
        }
    }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState) after?.also { after = null }?.invoke()
    }
}

@Composable
private fun FilterPage(current: ActivityFilter, categories: List<Category>, members: Map<String, String>, onBack: () -> Unit, onApply: (ActivityFilter) -> Unit) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    var cats by remember { mutableStateOf(current.categoryIds) }
    var range by remember { mutableStateOf(current.range) }
    var private by remember { mutableStateOf(current.isPrivate) }
    var who by remember { mutableStateOf(current.memberIds) }
    var min by remember { mutableStateOf(current.minAmount?.toString().orEmpty()) }
    var max by remember { mutableStateOf(current.maxAmount?.toString().orEmpty()) }
    var catSheet by remember { mutableStateOf(false) }
    var memberSheet by remember { mutableStateOf(false) }
    var rangePicker by remember { mutableStateOf(false) }
    var amountOpen by remember { mutableStateOf(false) }

    fun apply() {
        var lo = parseAmount(min)
        var hi = parseAmount(max)
        if (lo != null && hi != null && lo > hi) lo = hi.also { hi = lo }
        onApply(ActivityFilter(cats, range, lo, hi, private, who))
    }

    val catLabel = when (cats.size) {
        0 -> t("activity.all")
        1 -> categories.firstOrNull { it.id == cats.single() }?.name ?: t("activity.n_categories", 1)
        else -> t("activity.n_categories", cats.size)
    }
    val whoLabel = when (who.size) {
        0 -> t("activity.all")
        1 -> members[who.single()] ?: t("activity.n_members", 1)
        else -> t("activity.n_members", who.size)
    }
    val amountText = parseAmount(min).let { lo -> parseAmount(max).let { hi -> if (lo == null && hi == null) t("activity.any") else amountLabel(lo, hi) } }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("common.back")) }
            Text(t("activity.filter"), style = ty.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            LinkButton({
                cats = emptySet(); range = null; private = null; who = emptySet(); min = ""; max = ""
            }) { Text(t("activity.clear")) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            if (members.isNotEmpty()) {
                FilterRow(Icons.Outlined.Person, t("activity.member"), whoLabel, onClick = { memberSheet = true }) {
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
            FilterRow(Icons.Outlined.Category, t("activity.category"), catLabel, onClick = { catSheet = true }) {
                Icon(Icons.Filled.ChevronRight, null)
            }
            FilterRow(Icons.Outlined.DateRange, t("activity.date"), range?.let(::rangeLabel) ?: t("activity.any"), onClick = { rangePicker = true }) {
                if (range == null) {
                    Icon(Icons.Filled.ChevronRight, null)
                } else {
                    IconButton({ range = null }) { Icon(Icons.Filled.Close, t("activity.clear_date")) }
                }
            }
            FilterRow(Icons.Outlined.Payments, t("activity.amount"), amountText, onClick = { amountOpen = !amountOpen }) {
                Icon(if (amountOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (amountOpen) {
                Row(Modifier.padding(start = AppSpace.page, end = AppSpace.page, bottom = AppSpace.gap), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AmountField(min, t("activity.from"), Modifier.weight(1f).testTag("min")) { min = it }
                    AmountField(max, t("activity.to"), Modifier.weight(1f).testTag("max")) { max = it }
                }
            }
        }
        PrimaryButton(::apply, Modifier.fillMaxWidth().padding(start = AppSpace.page, end = AppSpace.page, top = 8.dp, bottom = AppSpace.gap)) {
            Text(t("activity.apply"))
        }
    }

    if (catSheet) {
        PickSheet(categories.map { Triple(it.id, it.name, colorFromHex(it.colorHex)) }, cats, onDismiss = { catSheet = false }) { cats = it; catSheet = false }
    }
    if (memberSheet) {
        PickSheet(members.map { Triple(it.key, it.value, null) }, who, onDismiss = { memberSheet = false }) { who = it; memberSheet = false }
    }
    if (rangePicker) {
        RangePick(range, onDismiss = { rangePicker = false }) { range = it; rangePicker = false }
    }
}

/** List row: muted icon, title over current value, trailing control. */
@Composable
private fun FilterRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val ty = MaterialTheme.typography
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 64.dp).padding(horizontal = AppSpace.page, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AppTheme.colors.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ty.bodyLarge)
            Text(value, color = AppTheme.colors.muted, style = ty.bodySmall)
        }
        trailing()
    }
}

@Composable
private fun AmountField(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) = OutlinedTextField(
    value, { v -> onChange(v.filter { it in '0'..'9' }) }, modifier,
    placeholder = { Text(hint) }, singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    shape = fieldShape, colors = fieldColors(),
)

/** Multi-select list of (id, label, dot color); "Tayyor" returns the picked ids. */
@Composable
private fun PickSheet(options: List<Triple<String, String, Color?>>, selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val ty = MaterialTheme.typography
    var picked by remember { mutableStateOf(selected) }
    AppSheet(onDismiss) { hide ->
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            for ((id, label, dot) in options) {
                val on = id in picked
                Row(
                    Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox) { picked = if (it) picked + id else picked - id }
                        .defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dot != null) {
                        Box(Modifier.size(16.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(label, style = ty.bodyLarge, modifier = Modifier.weight(1f))
                    Checkbox(on, null)
                }
            }
        }
        Row(Modifier.padding(start = AppSpace.page, end = AppSpace.page, top = 8.dp, bottom = AppSpace.gap), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton({ picked = emptySet() }, Modifier.weight(1f)) { Text(t("activity.all")) }
            PrimaryButton({ hide { onDone(picked) } }, Modifier.weight(1f)) { Text(t("common.done")) }
        }
    }
}

/** Date range, five years back through today. DateRangePicker speaks UTC midnights. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePick(initial: ClosedRange<LocalDate>?, onDismiss: () -> Unit, onPick: (ClosedRange<LocalDate>) -> Unit) {
    val today = LocalDate.now()
    fun LocalDate.utc() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    fun Long.day() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.utc(),
        initialSelectedEndDateMillis = initial?.endInclusive?.utc(),
        yearRange = today.year - 5..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.utc()
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LinkButton({
                val start = state.selectedStartDateMillis?.day()
                // Only a start picked = that one day.
                if (start == null) onDismiss() else onPick(start..(state.selectedEndDateMillis?.day() ?: start))
            }) { Text(t("common.ok")) }
        },
        dismissButton = { LinkButton(onDismiss) { Text(t("common.cancel")) } },
    ) {
        DateRangePicker(
            state, Modifier.weight(1f),
            title = { Text(t("activity.range_hint"), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

/**
 * Removable chips for each active filter part; tapping one drops just that
 * part. [leading] is a non-removable chip shown first (Tahlil's period).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilters(
    filter: ActivityFilter,
    catById: Map<String, Category>,
    onChange: (ActivityFilter) -> Unit,
    members: Map<String, String> = emptyMap(),
    leading: (@Composable () -> Unit)? = null,
) {
    @Composable
    fun Chip(label: String, without: ActivityFilter, dot: Color? = null) = InputChip(
        selected = false,
        onClick = { onChange(without) },
        label = { Text(label) },
        avatar = dot?.let { { Box(Modifier.size(10.dp).background(it, CircleShape)) } },
        trailingIcon = { Icon(Icons.Filled.Close, t("activity.remove"), Modifier.size(InputChipDefaults.IconSize)) },
    )
    val f = filter
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke()
        for (id in f.memberIds) Chip(members[id] ?: continue, f.copy(memberIds = f.memberIds - id))
        for (id in f.categoryIds) {
            val cat = catById[id] ?: continue
            Chip(cat.name, f.copy(categoryIds = f.categoryIds - id), colorFromHex(cat.colorHex))
        }
        f.range?.let { Chip(rangeLabel(it), f.copy(range = null)) }
        if (f.minAmount != null || f.maxAmount != null) Chip(amountLabel(f.minAmount, f.maxAmount), f.copy(minAmount = null, maxAmount = null))
        f.isPrivate?.let { Chip(if (it) t("activity.personal") else t("activity.shared"), f.copy(isPrivate = null)) }
    }
}
