package com.sarvarbek.expense_tracker.features.family

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.features.activity.ActiveFilters
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.features.activity.ActivityFilterPage
import com.sarvarbek.expense_tracker.features.activity.ActivityFilterSaver
import com.sarvarbek.expense_tracker.features.activity.FilterButton
import com.sarvarbek.expense_tracker.features.home.HomeSummary
import com.sarvarbek.expense_tracker.features.insights.CategoryBreakdown
import com.sarvarbek.expense_tracker.features.insights.insightsFor
import com.sarvarbek.expense_tracker.services.FamilyException
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.FamilyInvite
import com.sarvarbek.expense_tracker.services.FamilyMember
import com.sarvarbek.expense_tracker.services.FamilyOverview
import com.sarvarbek.expense_tracker.services.FamilyService
import com.sarvarbek.expense_tracker.ui.common.CategoryBadge
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Money
import com.sarvarbek.expense_tracker.ui.common.SectionLabel
import com.sarvarbek.expense_tracker.ui.common.TextDialog
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.toLocalDate
import com.sarvarbek.expense_tracker.ui.common.toLocalDateTime
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.common.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun monthStart() = LocalDate.now().withDayOfMonth(1)

/**
 * Oila tab data. Kept above the tab (it re-enters on every visit) so the
 * last result stays on screen while a refresh runs. Online only.
 */
class FamilyState(
    val service: FamilyService?,
    private val load: suspend () -> FamilyOverview? = { monthStart().let { service!!.overview(it.startMillis(), it.plusMonths(1).startMillis()) } },
    private val loadInvites: suspend () -> List<FamilyInvite> = { service!!.myInvites() },
) {
    var loaded by mutableStateOf(false); private set
    var overview by mutableStateOf<FamilyOverview?>(null); private set
    var failed by mutableStateOf(false); private set
    var invites by mutableStateOf(emptyList<FamilyInvite>()); private set

    suspend fun refresh() = coroutineScope {
        launch { invites = attempt { loadInvites() } ?: emptyList() }
        // A failed refresh keeps the last overview on screen.
        attempt { overview = load(); loaded = true; failed = false } ?: run { failed = true }
    }

    private suspend fun <T> attempt(f: suspend () -> T): T? = try {
        f()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

internal typealias ShowDialog = ((@Composable () -> Unit)?) -> Unit
internal typealias Act = (String?, suspend FamilyService.() -> Unit) -> Unit

/** One dialog slot plus [Act]: runs a family action, shows its error (or done text), then refetches. */
internal class FamilyActions(val show: ShowDialog, val act: Act, val dialog: () -> (@Composable () -> Unit)?)

@Composable
internal fun rememberFamilyActions(state: FamilyState): FamilyActions {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    return FamilyActions({ dialog = it }, { done, action ->
        dialog = null
        scope.launch {
            try {
                state.service!!.action()
                done?.let { toaster.show(it) }
            } catch (e: FamilyException) {
                toaster.show(e.message)
            }
            state.refresh()
        }
    }, { dialog })
}

/**
 * Oila tab. Not in a family: pending invites + create. In one: this month's
 * family budget vs shared spending, members, then the shared expenses (this
 * month, or the filter's Sana) as a category donut and a list.
 * Management (roles, invites, requests, leave, delete) is behind the gear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(state: FamilyState, db: ExpenseDao, active: Boolean = true, onNeeds: () -> Unit = {}, onSettings: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val actions = rememberFamilyActions(state)
    var filter by rememberSaveable(stateSaver = ActivityFilterSaver) { mutableStateOf(ActivityFilter()) }
    var filterOpen by remember { mutableStateOf(false) }
    val show = actions.show
    val act = actions.act
    LaunchedEffect(state, active) { if (active) state.refresh() } // every time the tab opens

    PullToRefreshBox(
        refreshing,
        onRefresh = { scope.launch { refreshing = true; state.refresh(); refreshing = false } },
        Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            // Bottom padding clears the floating add button.
            contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.overview?.name ?: t("family.title"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (state.overview != null) {
                        FilterButton(filter.count) { filterOpen = true }
                        Spacer(Modifier.width(4.dp))
                        GearButton(t("family.settings"), onSettings)
                    }
                }
                Spacer(Modifier.height(AppSpace.gap))
            }
            item {
                val f = state.overview
                when {
                    state.loaded && f == null -> NoFamily(state.invites, show, act) { NeedsCard(db, onNeeds) }
                    state.loaded && f != null -> InFamily(f, db, filter, { filter = it }) { NeedsCard(db, onNeeds) }
                    state.failed -> Column {
                        EmptyState(Icons.Outlined.WifiOff, t("family.load_failed"), t("family.load_failed_hint"))
                        NeedsCard(db, onNeeds) // local, works offline
                    }
                    else -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.accent) }
                }
            }
        }
    }
    actions.dialog()?.invoke()
    val f = state.overview
    if (filterOpen && f != null) {
        val categories by remember(db) { db.watchCategories() }.collectAsStateWithLifecycle(emptyList())
        ActivityFilterPage(filter, categories, onDismiss = { filterOpen = false }, members = f.memberNames) { filter = it; filterOpen = false }
    }
}

private val FamilyOverview.memberNames get() = members.associate { it.userId to it.label }

/** Joining or creating: share past expenses too? */
@Composable
private fun HistoryDialog(onDismiss: () -> Unit, onPick: (Boolean) -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t("family.history_title")) },
    text = { Text(t("family.history_text")) },
    dismissButton = { LinkButton({ onPick(false) }) { Text(t("family.no")) } },
    confirmButton = { LinkButton({ onPick(true) }) { Text(t("family.yes_share")) } },
)

@Composable
private fun NoFamily(invites: List<FamilyInvite>, show: ShowDialog, act: Act, needs: @Composable () -> Unit) {
    val ty = MaterialTheme.typography
    val close = { show(null) }
    Column {
        EmptyState(
            Icons.Outlined.FamilyRestroom, t("family.none"),
            t("family.none_hint"),
        )
        for (i in invites) {
            AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp)) {
                    Text(t("family.invite_to", i.familyName), style = ty.titleMedium)
                    Text(t("family.invited_by", i.invitedBy), color = AppTheme.colors.muted, style = ty.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        LinkButton({ act(null) { deleteInvite(i.id) } }) { Text(t("family.decline")) }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton({
                            show { HistoryDialog(close) { h -> act(t("family.joined")) { accept(i.id, includeHistory = h) } } }
                        }) { Text(t("family.accept")) }
                    }
                }
            }
        }
        Spacer(Modifier.height(AppSpace.gap))
        PrimaryButton({
            show {
                TextDialog(t("family.new"), t("family.continue"), close, hint = t("family.name")) { name ->
                    if (name.isEmpty()) close()
                    else show { HistoryDialog(close) { h -> act(t("family.created")) { create(name, includeHistory = h) } } }
                }
            }
        }, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(t("family.create"))
        }
        needs()
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun InFamily(f: FamilyOverview, db: ExpenseDao, filter: ActivityFilter, onFilter: (ActivityFilter) -> Unit, needs: @Composable () -> Unit) {
    val expenses by remember(db) { db.watchExpenses() }.collectAsStateWithLifecycle(emptyList())
    val cats by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(emptyList())
    val catById = cats.associateBy { it.id }
    val names = f.memberNames
    // Own shared rows come from Room (live, offline edits included); the
    // server only sends the others'.
    val mine = expenses.filter { it.familyId == f.id && it.transferTo == null }
        .map { FamilyExpense(it.copy(ownerId = f.myId), names[f.myId] ?: t("family.you")) }
    // No Sana in the filter: this month.
    val range = filter.range ?: monthStart().let { it..it.plusMonths(1).minusDays(1) }
    // Current members' labels carry their title; ex-members keep the server name.
    val others = f.others.map { it.copy(ownerName = names[it.expense.ownerId] ?: it.ownerName) }
    val list = (mine + others).filter { filter.copy(range = range).matches(it.expense) }.sortedByDescending { it.expense.date }
    val data = insightsFor(list.map { it.expense }, cats, range.start, range.endInclusive.plusDays(1))

    Column {
        BudgetCard(HomeSummary(f.spent, f.budget))
        needs()
        SectionLabel(t("family.members"))
        DividedCard(f.members, indent = 16.dp) { m -> MemberRow(m, f.myId) }
        SectionLabel(if (filter.range == null) t("family.shared_month") else t("family.shared"))
        if (!filter.isEmpty) {
            ActiveFilters(filter, catById, onFilter, names)
            Spacer(Modifier.height(12.dp))
        }
        if (list.isEmpty()) {
            if (filter.isEmpty) EmptyState(Icons.Outlined.ReceiptLong, t("family.empty_month"))
            else EmptyState(Icons.Outlined.SearchOff, t("family.no_match"), t("family.no_match_hint"))
        } else {
            CategoryBreakdown(data)
            SectionLabel(t("family.list"))
            // ponytail: whole list in one lazy item; fine for a family's month, page it if wide ranges get slow.
            DividedCard(list) { e -> SharedRow(e, catById[e.expense.categoryId]) }
        }
    }
}

@Composable
private fun SharedRow(fe: FamilyExpense, category: com.sarvarbek.expense_tracker.data.Category?) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val e = fe.expense
    CategoryBadge(category)
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
        Text(e.description, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${fe.ownerName} · ${uzDayMonth(e.date.toLocalDate())} ${e.date.toLocalDateTime().format(hhmm)}",
            color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(12.dp))
    Money(e.amount)
}

/** "Ali (siz) · Ota · admin". */
internal fun memberLabel(m: FamilyMember, myId: String) = listOfNotNull(
    m.name, t("family.you_suffix").takeIf { m.userId == myId }, m.title?.let { "· $it" }, t("family.admin_suffix").takeIf { m.isAdmin },
).joinToString(" ")

@Composable
private fun MemberRow(m: FamilyMember, myId: String) = Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
        Text(memberLabel(m, myId), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t("family.contribution", formatMoney(m.contribution)), color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.width(8.dp))
    Money(m.shared)
}

/** Square outlined icon button, as on Home's header. */
@Composable
internal fun GearButton(label: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    OutlinedIconButton(
        onClick,
        Modifier.size(48.dp),
        shape = RoundedCornerShape(AppRadii.md),
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = c.card, contentColor = c.text),
        border = BorderStroke(1.dp, c.border),
    ) { Icon(Icons.Outlined.Settings, label) }
}

/** Family budget (sum of contributions) vs shared spending this month. */
@Composable
private fun BudgetCard(summary: HomeSummary) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val soft = c.onHero.copy(alpha = 0.72f)
    val over = summary.budget > 0 && summary.spent > summary.budget
    Column(Modifier.fillMaxWidth().background(c.hero, RoundedCornerShape(AppRadii.hero)).padding(22.dp, 22.dp, 22.dp, 18.dp)) {
        Text(t("family.spent"), style = ty.labelMedium.copy(color = soft))
        Spacer(Modifier.height(6.dp))
        Money(summary.spent, style = ty.displayMedium, color = c.onHero, autoSize = true)
        Spacer(Modifier.height(16.dp))
        if (summary.budget <= 0) {
            Text(t("family.no_budget"), style = ty.labelSmall.copy(color = soft))
        } else {
            LinearProgressIndicator(
                progress = { summary.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(AppRadii.chip)),
                color = if (over) c.danger else c.accent,
                trackColor = c.onHero.copy(alpha = 0.15f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text(t("family.budget", formatMoney(summary.budget)), style = ty.labelSmall.copy(color = soft), modifier = Modifier.weight(1f))
                Text(
                    if (over) t("family.over", formatMoney(-summary.remaining)) else t("family.left", formatMoney(summary.remaining)),
                    style = ty.labelSmall.copy(color = c.onHero, fontWeight = if (over) FontWeight.ExtraBold else null),
                )
            }
        }
    }
}
