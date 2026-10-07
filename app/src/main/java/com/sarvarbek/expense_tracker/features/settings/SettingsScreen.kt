package com.sarvarbek.expense_tracker.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.expensesCsv
import com.sarvarbek.expense_tracker.services.matchOrCreateCategory
import com.sarvarbek.expense_tracker.services.shareCsv
import com.sarvarbek.expense_tracker.ui.common.CategoryBadge
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.SectionLabel
import com.sarvarbek.expense_tracker.ui.common.TextDialog
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSnackbar
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import kotlinx.coroutines.launch

private val locales = linkedMapOf(
    "en_US" to "Inglizcha",
    "uz_UZ" to "O'zbekcha",
    "ru_RU" to "Ruscha",
)

/** Pushed from the Home gear; owns its top bar (back arrow). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    db: ExpenseDao,
    email: String,
    onBack: () -> Unit,
    onCategories: () -> Unit,
    onSignOut: () -> Unit,
) {
    val s by settings.settings.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val close = { dialog = null }

    AppBarScaffold("Sozlamalar", onBack) { pad ->
        LazyColumn(contentPadding = PaddingValues(AppSpace.page, 0.dp, AppSpace.page, AppSpace.section), modifier = Modifier.padding(pad)) {
            item {
                SectionLabel("Umumiy")
                DividedCard(listOf<@Composable () -> Unit>(
                    {
                        SettingRow(Icons.Outlined.AccountBalanceWallet, "Oylik byudjet", if (s.monthlyBudget > 0) "${formatMoney(s.monthlyBudget)} UZS" else "Belgilanmagan") {
                            dialog = {
                                TextDialog(
                                    "Oylik byudjet", "Saqlash", close,
                                    initial = if (s.monthlyBudget > 0) s.monthlyBudget.toString() else "",
                                    hint = "4000000", suffix = "UZS", keyboard = KeyboardType.Number,
                                    accept = { t -> t.all(Char::isDigit) },
                                ) { text ->
                                    close()
                                    // 0 or empty clears the budget ("Belgilanmagan").
                                    val amount = if (text.isEmpty()) 0L else parseAmount(text)
                                    if (amount == null) toaster.show("To'g'ri summa kiriting") else settings.setBudget(amount)
                                }
                            }
                        }
                    },
                    { SettingRow(Icons.Outlined.Category, "Turkumlar", "Qo'shish, tahrirlash, arxivlash", onClick = onCategories) },
                    { PrivateRow(s.defaultPrivate, settings::setDefaultPrivate) },
                )) { it() }
            }
            item {
                SectionLabel("AI va ovoz")
                DividedCard(listOf<@Composable () -> Unit>({
                    SettingRow(Icons.Outlined.MicNone, "Ovoz tili", locales[s.sttLocale] ?: s.sttLocale) {
                        dialog = { LocalePicker(s.sttLocale, close) { close(); settings.setLocale(it) } }
                    }
                })) { it() }
                Text(
                    "Bepul tarif so'rovlari Google tomonidan modellarini yaxshilash uchun ishlatilishi mumkin.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
                )
            }
            item {
                SectionLabel("Hisob")
                DividedCard(listOf<@Composable () -> Unit>({
                    SettingRow(Icons.Outlined.Person, "Ism", s.displayName.ifEmpty { "Belgilanmagan" }) {
                        dialog = {
                            TextDialog("Ism", "Saqlash", close, initial = s.displayName, hint = "Oila sizni shunday ko'radi", accept = { it.length <= 40 }) { name ->
                                close()
                                if (name.isEmpty()) toaster.show("Ism kiriting") else if (name != s.displayName) settings.setDisplayName(name)
                            }
                        }
                    }
                }, {
                    SettingRow(Icons.AutoMirrored.Outlined.Logout, "Chiqish", email) {
                        dialog = { ConfirmDialog("Hisobdan chiqasizmi?", null, "Chiqish", close) { close(); onSignOut() } }
                    }
                })) { it() }
            }
            item {
                SectionLabel("Ma'lumotlar")
                DividedCard(listOf<@Composable () -> Unit>({
                    SettingRow(Icons.Outlined.IosShare, "Ma'lumotni eksport (CSV)", "Barcha xarajatlarni ulashish") {
                        scope.launch {
                            val rows = db.getExpenses()
                            if (rows.isEmpty()) return@launch toaster.show("Eksport uchun hech narsa yo'q")
                            shareCsv(context, expensesCsv(rows, db.getAllCategories().associate { it.id to it.name }))
                        }
                    }
                })) { it() }
            }
        }
    }
    dialog?.invoke()
}

/** Back-arrow top bar + the app snackbar, for pushed pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBarScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val c = AppTheme.colors
    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Orqaga") } },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg),
            )
        },
        snackbarHost = { SnackbarHost(LocalToaster.current.host) { AppSnackbar(it) } },
        content = content,
    )
}

@Composable
private fun RowIcon(icon: ImageVector) {
    val c = AppTheme.colors
    Box(Modifier.size(40.dp).background(c.accent.copy(alpha = 0.12f), RoundedCornerShape(AppRadii.sm)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String, modifier: Modifier) = Column(modifier) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    RowIcon(icon)
    Spacer(Modifier.width(16.dp))
    RowText(title, subtitle, Modifier.weight(1f))
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AppTheme.colors.muted)
}

/** Whole row toggles; reads as one labelled switch. */
@Composable
private fun PrivateRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .toggleable(on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(Icons.Outlined.VisibilityOff)
        Spacer(Modifier.width(16.dp))
        RowText("Yangi xarajatlar maxfiy", "Oila maxfiy xarajatlarni ko'rmaydi", Modifier.weight(1f))
        Switch(on, null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}

@Composable
private fun LocalePicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Ovoz tili") },
    text = {
        Column {
            for ((code, label) in locales) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { onPick(code) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Check, null, tint = AppTheme.colors.accent, modifier = Modifier.size(18.dp).alpha(if (code == current) 1f else 0f))
                    Text(label)
                }
            }
        }
    },
    confirmButton = {},
)

/**
 * Manage categories: add (name only, color auto-assigned), rename,
 * archive/unarchive. Archived stay listed (muted) so they can be restored.
 * Family categories are the admin's; a member only views them.
 */
@Composable
fun CategoriesScreen(db: ExpenseDao, canEdit: Boolean, onBack: () -> Unit) {
    val cats by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val close = { dialog = null }

    fun rename(c: Category) {
        dialog = {
            TextDialog("Turkum nomini o'zgartirish", "Saqlash", close, initial = c.name) { name ->
                close()
                if (name.isNotEmpty() && name != c.name) scope.launch { db.updateCategory(c.copy(name = name)) }
            }
        }
    }

    val add = {
        dialog = {
            TextDialog("Yangi turkum", "Qo'shish", close, hint = "Turkum nomi") { name ->
                close()
                if (name.isNotEmpty()) scope.launch { matchOrCreateCategory(db, name) }
            }
        }
    }

    AppBarScaffold("Turkumlar", onBack, actions = {
        if (canEdit) IconButton(add) { Icon(Icons.Filled.Add, "Turkum qo'shish") }
    }) { pad ->
        // ponytail: local DB, resolves in a frame — blank beats a spinner flash.
        val all = cats ?: return@AppBarScaffold
        if (all.isEmpty()) {
            Box(Modifier.padding(pad)) { EmptyState(Icons.Outlined.Category, "Hali turkum yo'q", "Yuqoridagi + tugmasi bilan qo'shing") }
            return@AppBarScaffold
        }
        val (archived, active) = all.partition { it.isArchived }
        LazyColumn(contentPadding = PaddingValues(AppSpace.page, 0.dp, AppSpace.page, AppSpace.section), modifier = Modifier.padding(pad)) {
            for ((label, group) in listOf("Faol" to active, "Arxivlangan" to archived)) {
                if (group.isNotEmpty()) item(label) {
                    SectionLabel(label, top = 12.dp)
                    DividedCard(group) { cat -> CategoryRow(cat, canEdit, { rename(cat) }) { scope.launch { db.updateCategory(cat.copy(isArchived = !cat.isArchived)) } } }
                    Spacer(Modifier.size(AppSpace.gap))
                }
            }
        }
    }
    dialog?.invoke()
}

@Composable
private fun CategoryRow(c: Category, canEdit: Boolean, onRename: () -> Unit, onToggleArchive: () -> Unit) = Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(start = 16.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.alpha(if (c.isArchived) 0.45f else 1f)) { CategoryBadge(c, 40.dp) }
    Spacer(Modifier.width(16.dp))
    Text(
        c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleSmall.let { if (c.isArchived) it.copy(color = AppTheme.colors.muted) else it },
    )
    if (canEdit) {
        IconButton(onRename) { Icon(Icons.Outlined.Edit, "Nomini o'zgartirish", Modifier.size(20.dp)) }
        IconButton(onToggleArchive) {
            Icon(
                if (c.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                if (c.isArchived) "Arxivdan chiqarish" else "Arxivlash", Modifier.size(20.dp),
            )
        }
    }
}

