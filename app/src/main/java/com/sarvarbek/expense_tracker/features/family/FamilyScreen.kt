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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.features.activity.ActivityFilter
import com.sarvarbek.expense_tracker.features.insights.CategoryBreakdown
import com.sarvarbek.expense_tracker.features.insights.insightsFor
import com.sarvarbek.expense_tracker.services.FamilyException
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.services.FamilyInvite
import com.sarvarbek.expense_tracker.services.FamilyMember
import com.sarvarbek.expense_tracker.services.FamilyOverview
import com.sarvarbek.expense_tracker.services.FamilyService
import com.sarvarbek.expense_tracker.ui.common.CategoryBadge
import com.sarvarbek.expense_tracker.ui.common.rememberExpenseTap
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.ListBanner
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun monthStart() = LocalDate.now().withDayOfMonth(1)

/**
 * Oila tab data. Kept above the tab (it re-enters on every visit) so the
 * last result stays on screen while a refresh runs. Online only.
 */
class FamilyState(
    val service: FamilyService?,
    private val load: suspend () -> FamilyOverview? = { monthStart().let { service!!.overview(it.startMillis(), it.plusMonths(1).startMillis()) } },
    private val loadInvites: suspend () -> List<FamilyInvite> = { service!!.myInvites() },
    /** Own + others' expenses ([FamilyService.feed]). */
    val feed: Flow<List<FamilyExpense>> = service?.feed ?: flowOf(emptyList()),
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
 * Oila tab. Not in a family: pending invites + create. In one: what the
 * family spent this month, members with their spending, then this month's
 * family expenses by category (merged by name) and a Ro'yxat banner.
 * Management (roles, invites, leave, delete) is behind the gear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(
    state: FamilyState,
    db: ExpenseDao,
    active: Boolean = true,
    onList: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val actions = rememberFamilyActions(state)
    val show = actions.show
    val act = actions.act
    LaunchedEffect(state, active) { if (active) state.refresh() } // every time the tab opens
    val feedRows by state.feed.collectAsStateWithLifecycle(null)
    val allCats by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(null)

    PullToRefreshBox(
        refreshing,
        onRefresh = { scope.launch { refreshing = true; state.refresh(); refreshing = false } },
        Modifier.fillMaxSize(),
    ) {
        // Blank until loaded, so a returning list restores its scroll (see HomeScreen).
        val rows = feedRows ?: return@PullToRefreshBox
        val cats = allCats ?: return@PullToRefreshBox
        LazyColumn(
            Modifier.fillMaxSize(),
            // Bottom padding clears the floating add button.
            contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.overview?.name ?: t("family.title"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (state.overview != null) GearButton(t("family.settings"), onSettings)
                }
                Spacer(Modifier.height(AppSpace.gap))
            }
            item {
                val f = state.overview
                when {
                    state.loaded && f == null -> NoFamily(state.invites, show, act)
                    state.loaded && f != null -> InFamily(f, rows, cats, onList)
                    state.failed -> EmptyState(Icons.Outlined.WifiOff, t("family.load_failed"), t("family.load_failed_hint"))
                    else -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.accent) }
                }
            }
        }
    }
    actions.dialog()?.invoke()
}

internal val FamilyOverview.memberNames get() = members.associate { it.userId to it.label }

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
private fun NoFamily(invites: List<FamilyInvite>, show: ShowDialog, act: Act) {
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
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun InFamily(f: FamilyOverview, rows: List<FamilyExpense>, cats: List<Category>, onList: () -> Unit) {
    val range = familyRange()
    val list = familySelect(f)(rows)
    val data = familyInsights(list, familyCategories(list, cats), range.start, range.endInclusive.plusDays(1))

    Column {
        SpentCard(f.spent)
        SectionLabel(t("family.members"))
        DividedCard(f.members, indent = 16.dp) { m -> MemberRow(m, f.myId) }
        SectionLabel(t("family.shared_month"))
        if (list.isEmpty()) {
            EmptyState(Icons.Outlined.ReceiptLong, t("family.empty_month"))
        } else {
            CategoryBreakdown(data)
            Spacer(Modifier.height(AppSpace.section))
            ListBanner(t("family.list"), t("family.list_summary", list.count { it.expense.transferTo == null }, formatMoney(data.total)), onList) // same rows as the total
        }
    }
}

/** This month. */
internal fun familyRange() = monthStart().let { it..it.plusMonths(1).minusDays(1) }

/**
 * The Oila feed as this tab shows it: this month. Own rows: only the family's,
 * no transfers; ownerId set to the member's. Current members' labels carry their
 * title; ex-members keep the server name. Also [FamilyListScreen]'s rows, so
 * its rows and totals match.
 */
internal fun familySelect(f: FamilyOverview): (List<FamilyExpense>) -> List<FamilyExpense> {
    val names = f.memberNames
    val month = ActivityFilter(range = familyRange())
    return { all ->
        all.filter { !it.mine || (it.expense.familyId == f.id && it.expense.transferTo == null) }
            .map { if (it.mine) it.copy(it.expense.copy(ownerId = f.myId), names[f.myId] ?: it.ownerName) else it.copy(ownerName = names[it.expense.ownerId] ?: it.ownerName) }
            .filter { month.matches(it.expense) }
    }
}

/** Category name as the family groups it: categories are per user, so the same name (any case/spacing) is one category. */
internal fun categoryKey(name: String) = name.trim().lowercase()

/**
 * Each row's category as the family sees it, by expense id: one synthetic [Category]
 * per [categoryKey] (its id), so the same name across members is one category and a
 * name only one member has is its own. Own rows take name/color/icon from Room ([own]),
 * others' from the feed; own first, so on a clash my color wins. No category (old
 * server, missing row): "Turkumsiz".
 */
internal fun familyCategories(rows: List<FamilyExpense>, own: List<Category>): Map<String, Category> {
    val ownById = own.associateBy { it.id }
    val byKey = HashMap<String, Category>()
    return rows.sortedByDescending { it.mine }.associate { fe ->
        val local = if (fe.mine) ownById[fe.expense.categoryId] else null
        val name = (local?.name ?: fe.categoryName)?.trim().orEmpty()
        val key = categoryKey(name)
        // ponytail: a bad hex from the feed would crash the donut; fall back to the no-category grey.
        val color = listOfNotNull(local?.colorHex, fe.categoryColor).firstOrNull { HEX6.matches(it) }
        fe.expense.id to byKey.getOrPut(key) {
            if (key.isEmpty()) Category(id = "", name = t("widgets.no_category"), colorHex = NO_CATEGORY_HEX)
            else Category(id = key, name = name, colorHex = color ?: NO_CATEGORY_HEX, iconKey = local?.iconKey ?: fe.categoryIcon ?: "category")
        }
    }
}

private const val NO_CATEGORY_HEX = "9AA0A6"
private val HEX6 = Regex("[0-9A-Fa-f]{6}")

/** [insightsFor] over [rows] with their [familyCategories] ([byExpense]): one slice per merged name. */
internal fun familyInsights(rows: List<FamilyExpense>, byExpense: Map<String, Category>, start: LocalDate, end: LocalDate) =
    insightsFor(rows.map { it.expense.copy(categoryId = byExpense.getValue(it.expense.id).id) }, byExpense.values.distinct(), start, end)

@Composable
internal fun SharedRow(fe: FamilyExpense, category: Category?, onEdit: (Expense) -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = rememberExpenseTap(fe, category, onEdit)).padding(horizontal = 16.dp, vertical = 12.dp),
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

/** "Ali (siz) · Ota · admin", and what they spent this month on the right. */
@Composable
private fun MemberRow(m: FamilyMember, myId: String) = Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val ty = MaterialTheme.typography
    Text(memberLabel(m, myId), style = ty.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))
    Column(horizontalAlignment = Alignment.End) {
        Money(m.shared)
        Text(t("family.member_spent"), color = AppTheme.colors.muted, style = ty.bodySmall)
    }
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

/** What the family spent this month (transfers excluded), on the hero card. */
@Composable
private fun SpentCard(spent: Long) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    Column(Modifier.fillMaxWidth().background(c.hero, RoundedCornerShape(AppRadii.hero)).padding(22.dp)) {
        Text(t("family.spent"), style = ty.labelMedium.copy(color = c.onHero.copy(alpha = 0.72f)))
        Spacer(Modifier.height(6.dp))
        Money(spent, style = ty.displayMedium, color = c.onHero, autoSize = true)
    }
}
