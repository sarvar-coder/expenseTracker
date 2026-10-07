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
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.features.home.HomeSummary
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

private typealias ShowDialog = ((@Composable () -> Unit)?) -> Unit

/**
 * Oila tab. Not in a family: pending invites + create. In one: this month's
 * family budget vs shared spending, members, the shared expense list, and
 * the admin's invites / category requests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(state: FamilyState, db: ExpenseDao) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val show: ShowDialog = { dialog = it }
    LaunchedEffect(state) { state.refresh() } // every time the tab opens

    /** Runs a family action, shows its error (or [done]), then refetches. */
    fun act(done: String? = null, action: suspend FamilyService.() -> Unit) {
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
    }

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
                Text(state.overview?.name ?: "Oila", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(AppSpace.gap))
            }
            item {
                val f = state.overview
                when {
                    state.loaded && f == null -> NoFamily(state.invites, show, ::act)
                    state.loaded && f != null -> InFamily(f, db, show, ::act)
                    state.failed -> EmptyState(Icons.Outlined.WifiOff, "Oila ma'lumotini yuklab bo'lmadi", "Internetni tekshirib, pastga torting")
                    else -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.accent) }
                }
            }
        }
    }
    dialog?.let { d -> d() }
}

private typealias Act = (String?, suspend FamilyService.() -> Unit) -> Unit

/** Joining or creating: share past expenses too? */
@Composable
private fun HistoryDialog(onDismiss: () -> Unit, onPick: (Boolean) -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Avvalgi xarajatlar") },
    text = { Text("Oldingi xarajatlaringiz ham oilaga ko'rsatilsinmi? Maxfiy xarajatlar baribir yashirin qoladi.") },
    dismissButton = { LinkButton({ onPick(false) }) { Text("Yo'q") } },
    confirmButton = { LinkButton({ onPick(true) }) { Text("Ha, ulashish") } },
)

@Composable
private fun NoFamily(invites: List<FamilyInvite>, show: ShowDialog, act: Act) {
    val t = MaterialTheme.typography
    val close = { show(null) }
    Column {
        EmptyState(
            Icons.Outlined.FamilyRestroom, "Siz hali oilada emassiz",
            "Oila yarating yoki taklifni qabul qiling. Umumiy xarajatlar va byudjet shu yerda ko'rinadi.",
        )
        for (i in invites) {
            AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp)) {
                    Text("“${i.familyName}” oilasiga taklif", style = t.titleMedium)
                    Text("${i.invitedBy} taklif qildi", style = t.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        LinkButton({ act(null) { deleteInvite(i.id) } }) { Text("Rad etish") }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton({
                            show { HistoryDialog(close) { h -> act("Oilaga qo'shildingiz") { accept(i.id, includeHistory = h) } } }
                        }) { Text("Qabul qilish") }
                    }
                }
            }
        }
        Spacer(Modifier.height(AppSpace.gap))
        PrimaryButton({
            show {
                TextDialog("Yangi oila", "Davom etish", close, hint = "Oila nomi") { name ->
                    if (name.isEmpty()) close()
                    else show { HistoryDialog(close) { h -> act("Oila yaratildi") { create(name, includeHistory = h) } } }
                }
            }
        }, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Oila yaratish")
        }
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun InFamily(f: FamilyOverview, db: ExpenseDao, show: ShowDialog, act: Act) {
    val c = AppTheme.colors
    val close = { show(null) }
    val expenses by remember(db) { db.watchExpenses() }.collectAsStateWithLifecycle(emptyList())
    val cats by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(emptyList())
    val catById = cats.associateBy { it.id }
    val from = monthStart().startMillis()
    val me = f.members.firstOrNull { it.userId == f.myId }
    // Own shared rows come from Room (live, offline edits included); the
    // server only sends the others'.
    val mine = expenses.filter { it.familyId == f.id && !it.isPrivate && it.date >= from }
        .map { FamilyExpense(it.id, me?.name ?: "Siz", it.categoryId, it.description, it.amount, it.date) }
    val list = (mine + f.others).sortedByDescending { it.date }

    Column {
        BudgetCard(HomeSummary(f.spent, f.budget))
        SectionLabel("A'zolar")
        DividedCard(f.members, indent = 16.dp) { m -> MemberRow(m, f, show, act) }
        if (f.isAdmin) AdminSection(f, show, act)
        SectionLabel("Bu oygi umumiy xarajatlar")
        if (list.isEmpty()) EmptyState(Icons.Outlined.ReceiptLong, "Bu oy umumiy xarajat yo'q")
        else DividedCard(list) { e -> SharedRow(e, catById[e.categoryId]) }
        Spacer(Modifier.height(AppSpace.section))
        OutlinedButton(
            {
                show {
                    ConfirmDialog("Oiladan chiqasizmi?", "Umumiy xarajatlaringiz oila tarixida faqat o'qish uchun qoladi.", "Chiqish", close) {
                        act(null) { leave() }
                    }
                }
            },
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            shape = RoundedCornerShape(AppRadii.md),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger),
            border = BorderStroke(1.5.dp, c.danger),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("Oiladan chiqish", style = MaterialTheme.typography.labelLarge)
        }
        if (f.isAdmin) {
            TextButton(
                {
                    show {
                        ConfirmDialog("Oila o'chirilsinmi?", "Barcha a'zolar chiqariladi. Xarajatlar har kimning o'zida qoladi.", "O'chirish", close) {
                            act(null) { deleteFamily() }
                        }
                    }
                },
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = c.danger),
            ) { Text("Oilani o'chirish", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun SharedRow(e: FamilyExpense, category: com.sarvarbek.expense_tracker.data.Category?) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    CategoryBadge(category)
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
        Text(e.description, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${e.ownerName} · ${uzDayMonth(e.date.toLocalDate())} ${e.date.toLocalDateTime().format(hhmm)}",
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(12.dp))
    Money(e.amount)
}

@Composable
private fun MemberRow(m: FamilyMember, f: FamilyOverview, show: ShowDialog, act: Act) {
    val self = m.userId == f.myId
    var menu by remember { mutableStateOf(false) }
    val close = { show(null) }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(start = 16.dp, end = if (f.isAdmin && !self) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                listOfNotNull(m.name, "(siz)".takeIf { self }, "· admin".takeIf { m.isAdmin }).joinToString(" "),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("Byudjetga hissa: ${formatMoney(m.contribution)} UZS", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Money(m.shared)
        if (f.isAdmin && !self) {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Amallar") }
                DropdownMenu(menu, { menu = false }, containerColor = AppTheme.colors.card) {
                    DropdownMenuItem({ Text("Admin qilish") }, {
                        menu = false
                        show { ConfirmDialog("${m.name} admin qilinsinmi?", "Siz oddiy a'zo bo'lasiz.", "Admin qilish", close) { act(null) { transferAdmin(m.userId) } } }
                    })
                    DropdownMenuItem({ Text("Oiladan chiqarish") }, {
                        menu = false
                        show {
                            ConfirmDialog("${m.name} oiladan chiqarilsinmi?", "Umumiy xarajatlari oila tarixida faqat o'qish uchun qoladi.", "Chiqarish", close) {
                                act(null) { removeMember(m.userId) }
                            }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun AdminSection(f: FamilyOverview, show: ShowDialog, act: Act) {
    val c = AppTheme.colors
    val close = { show(null) }
    SectionLabel("Takliflar")
    // null = the trailing "invite a member" row.
    DividedCard(f.invites + null, indent = 16.dp) { i ->
        if (i != null) {
            IconRow(Icons.Outlined.MailOutline, i.email, "Javob kutilmoqda") {
                IconButton({ act(null) { deleteInvite(i.id) } }) { Icon(Icons.Filled.Close, "Taklifni bekor qilish") }
            }
        } else {
            IconRow(Icons.Outlined.PersonAddAlt, "A'zo taklif qilish", null, Modifier.clickable {
                show {
                    TextDialog("A'zo taklif qilish", "Taklif qilish", close, hint = "email@misol.uz", keyboard = KeyboardType.Email) { email ->
                        if (email.isEmpty()) close() else act("Taklif yuborildi. U kirganda ko'radi.") { invite(f.id, email) }
                    }
                }
            })
        }
    }
    SectionLabel("Turkum so'rovlari", badge = f.requests.size)
    if (f.requests.isEmpty()) {
        Text("Yangi so'rov yo'q", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
    } else {
        DividedCard(f.requests, indent = 16.dp) { r ->
            IconRow(null, r.name, "Hozircha \"Boshqa\"da") {
                IconButton({ act(null) { rejectRequest(r.id) } }) { Icon(Icons.Filled.Close, "Rad etish") }
                IconButton({ act("“${r.name}” turkumi yaratildi") { approveRequest(r.id) } }) { Icon(Icons.Filled.Check, "Tasdiqlash", tint = c.accent) }
            }
        }
    }
}

@Composable
private fun IconRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) = Row(
    modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(start = 16.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (icon != null) {
        Icon(icon, null, tint = AppTheme.colors.muted)
        Spacer(Modifier.width(16.dp))
    }
    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    trailing()
}

/** Family budget (sum of contributions) vs shared spending this month. */
@Composable
private fun BudgetCard(summary: HomeSummary) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    val soft = c.onHero.copy(alpha = 0.72f)
    val over = summary.budget > 0 && summary.spent > summary.budget
    Column(Modifier.fillMaxWidth().background(c.hero, RoundedCornerShape(AppRadii.hero)).padding(22.dp, 22.dp, 22.dp, 18.dp)) {
        Text("Oila bu oy sarfladi", style = t.labelMedium.copy(color = soft))
        Spacer(Modifier.height(6.dp))
        Money(summary.spent, style = t.displayMedium, color = c.onHero, autoSize = true)
        Spacer(Modifier.height(16.dp))
        if (summary.budget <= 0) {
            Text("Oila byudjeti yo'q — a'zolar Sozlamalarda byudjet belgilaydi", style = t.labelSmall.copy(color = soft))
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
                Text("Byudjet: ${formatMoney(summary.budget)}", style = t.labelSmall.copy(color = soft), modifier = Modifier.weight(1f))
                Text(
                    if (over) "${formatMoney(-summary.remaining)} oshdi" else "${formatMoney(summary.remaining)} qoldi",
                    style = t.labelSmall.copy(color = c.onHero, fontWeight = if (over) FontWeight.ExtraBold else null),
                )
            }
        }
    }
}
