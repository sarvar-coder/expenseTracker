package com.sarvarbek.expense_tracker.features.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.features.insights.OfflineNote
import com.sarvarbek.expense_tracker.features.settings.AppBarScaffold
import com.sarvarbek.expense_tracker.services.LedgerRow
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.monthName
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Hissa page: my contributions and the transfers I sent or got (who → who)
 * for a month, newest first; "+" opens Hissa qo'shish. Online only ([load] =
 * FamilyService.ledger); a failed refresh keeps the last rows with an offline note.
 * ponytail: rows refetch on every visit (no cache); hold them above the route if that flickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(load: suspend (LocalDate) -> List<LedgerRow>, onBack: () -> Unit, onAdd: () -> Unit) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val scope = rememberCoroutineScope()
    val now = monthStart()
    var month by rememberSaveable { mutableStateOf(now) }
    var rows by remember { mutableStateOf<List<LedgerRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    suspend fun fetch(m: LocalDate) {
        try {
            val r = load(m)
            if (m == month) { rows = r; failed = false }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (m == month) failed = true
        }
    }
    LaunchedEffect(month) { fetch(month) }
    val pick = { m: LocalDate -> rows = null; failed = false; month = m }

    AppBarScaffold(t("ledger.title"), onBack, actions = {
        IconButton(onAdd) { Icon(Icons.Filled.Add, t("contrib.add")) }
    }) { pad ->
        PullToRefreshBox(
            refreshing,
            onRefresh = { scope.launch { refreshing = true; fetch(month); refreshing = false } },
            Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(AppSpace.page, 0.dp, AppSpace.page, AppSpace.section)) {
                item {
                    val label = "${monthName(month.monthValue)} ${month.year}"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ pick(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, t("ledger.prev")) }
                        Text(label, style = ty.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        IconButton({ pick(month.plusMonths(1)) }, enabled = month < now) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, t("ledger.next"))
                        }
                    }
                    rows?.let { r ->
                        val total = r.filter { !it.isTransfer }.sumOf { it.amount }
                        Column(Modifier.padding(top = 8.dp).clearAndSetSemantics { contentDescription = t("ledger.total_a11y", formatMoney(total)) }) {
                            Text(t("ledger.total"), color = c.muted, style = ty.labelMedium)
                            Money(total, style = ty.headlineMedium, autoSize = true)
                        }
                        if (failed) OfflineNote()
                    }
                    Spacer(Modifier.height(AppSpace.gap))
                }
                val r = rows
                when {
                    r == null && failed -> item { EmptyState(Icons.Outlined.WifiOff, t("ledger.load_failed"), t("family.load_failed_hint")) }
                    r == null -> item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                    }
                    r.isEmpty() -> item { EmptyState(Icons.Outlined.Savings, t("ledger.empty"), t("ledger.empty_hint")) }
                    else -> item { DividedCard(r, indent = 16.dp) { LedgerItem(it) } }
                }
            }
        }
    }
}

/** "Ali → Ona · Ota" with the note, or "Ali · Hissa" with the note; amount on the right. */
@Composable
private fun LedgerItem(row: LedgerRow) = Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val ty = MaterialTheme.typography
    Column(Modifier.weight(1f)) {
        Text(
            if (row.to != null) "${row.from} → ${row.to}" else "${row.from} · ${t("ledger.contribution")}",
            style = ty.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(row.note, uzDayMonth(row.date.toLocalDate())).filter { it.isNotBlank() }.joinToString(" · "),
            color = AppTheme.colors.muted, style = ty.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(12.dp))
    Money(row.amount)
}
