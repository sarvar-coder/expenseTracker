@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.sarvarbek.expense_tracker.features.add

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.ExpenseSource
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.ParsedExpense
import com.sarvarbek.expense_tracker.services.ResolvedCategory
import com.sarvarbek.expense_tracker.services.SpeechService
import com.sarvarbek.expense_tracker.services.resolveCategory
import com.sarvarbek.expense_tracker.ui.common.ConfirmDeleteDialog
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.colorFromHex
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.toLocalDateTime
import com.sarvarbek.expense_tracker.ui.common.toMillis
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppSnackbar
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@Suppress("EnumEntryName")
enum class AddMode(val label: String) { type("Yozish"), speak("Aytish"), manual("Qo'lda") }

/** Manual form start values (edit, or a failed parse dropping into Manual). */
private data class Prefill(val amount: String, val desc: String, val categoryId: String?, val private: Boolean)

/**
 * Pushed from the shell FAB (new) or an expense row ([editing]). Segmented
 * Type / Speak / Manual; editing forces Manual. [parse] and [speech] are the
 * test seams.
 */
@Composable
fun AddScreen(
    db: ExpenseDao,
    settings: SettingsStore,
    parse: suspend (raw: String, categories: List<String>) -> ParsedExpense?,
    speech: SpeechService,
    canCreate: () -> Boolean,
    onClose: () -> Unit,
    editing: Expense? = null,
    /** Other family members (id to label) for O'tkazma; empty outside a family. */
    members: List<Pair<String, String>> = emptyList(),
) {
    val c = AppTheme.colors
    // Editing forces Manual; otherwise reopen in the last picked mode.
    var mode by rememberSaveable {
        mutableStateOf(if (editing != null) AddMode.manual else AddMode.entries.firstOrNull { it.name == settings.lastAddMode } ?: AddMode.type)
    }
    val prefs by settings.settings.collectAsStateWithLifecycle()
    var prefill by remember { mutableStateOf(editing?.let { Prefill(it.amount.toString(), it.description, it.categoryId, it.isPrivate) }) }
    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "Xarajat qo'shish" else "Xarajatni tahrirlash", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Orqaga") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg),
            )
        },
        snackbarHost = { SnackbarHost(LocalToaster.current.host) { AppSnackbar(it) } },
    ) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(start = AppSpace.page, end = AppSpace.page, top = 8.dp, bottom = AppSpace.section),
        ) {
            // Labels only: the Speak form has its own mic, one mic on screen.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AddMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; settings.lastAddMode = m.name },
                        shape = SegmentedButtonDefaults.itemShape(i, AddMode.entries.size),
                        icon = {},
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) { Text(m.label) }
                }
            }
            Spacer(Modifier.height(AppSpace.gap + 4.dp))
            when (mode) {
                AddMode.manual -> key(prefill) {
                    ManualForm(db, canCreate, editing, prefill ?: Prefill("", "", null, prefs.defaultPrivate), members, onClose)
                }
                AddMode.type, AddMode.speak -> key(mode) {
                    TypeForm(db, settings, parse, speech, canCreate, voice = mode == AddMode.speak, onClose) { raw ->
                        prefill = Prefill("", raw, null, settings.settings.value.defaultPrivate)
                        mode = AddMode.manual
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

@Composable
private fun ManualForm(
    db: ExpenseDao,
    canCreate: () -> Boolean,
    editing: Expense?,
    init: Prefill,
    members: List<Pair<String, String>>,
    onClose: () -> Unit,
) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf(init.amount) }
    var desc by rememberSaveable { mutableStateOf(init.desc) }
    var categoryId by rememberSaveable { mutableStateOf(init.categoryId) }
    // Requested (not yet approved) category name; expense sits in Boshqa meanwhile.
    var pending by rememberSaveable { mutableStateOf(editing?.pendingCategory) }
    var date by rememberSaveable { mutableStateOf(editing?.date?.toLocalDateTime() ?: LocalDateTime.now()) }
    var private by rememberSaveable { mutableStateOf(init.private) }
    var transfer by rememberSaveable { mutableStateOf(editing?.transferTo != null) }
    var transferTo by rememberSaveable { mutableStateOf(editing?.transferTo) }
    var saving by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // All (archived too): an edited expense may sit in an archived category.
    val all by remember(db) { db.watchAllCategories() }.collectAsStateWithLifecycle(emptyList())
    val selected = all.firstOrNull { it.id == categoryId }
    val today = LocalDate.now()
    val isToday = date.toLocalDate() == today
    val isYesterday = date.toLocalDate() == today.minusDays(1)

    fun save() {
        val a = parseAmount(amount)
        val to = transferTo.takeIf { transfer }
        // A transfer needs no description: "→ Singil" by default.
        val d = desc.trim().ifEmpty { if (to != null) "→ ${members.firstOrNull { it.first == to }?.second ?: "O'tkazma"}" else "" }
        when {
            a == null || a <= 0 -> return toaster.show("To'g'ri summa kiriting")
            transfer && to == null -> return toaster.show("Kimga berilganini tanlang")
            d.isEmpty() -> return toaster.show("Tavsif kiriting")
            to == null && categoryId == null -> return toaster.show("Turkum tanlang")
        }
        saving = true
        scope.launch {
            // Transfers sit in Boshqa (no category of their own); every total skips them.
            val cat = if (to != null) resolveCategory(db, "Boshqa", canCreate()).id else categoryId!!
            val pend = pending.takeIf { to == null }
            if (editing != null) {
                db.updateExpense(editing.copy(description = d, amount = a!!, categoryId = cat, date = date.toMillis(), isPrivate = private, pendingCategory = pend, transferTo = to))
            } else {
                db.insertExpense(Expense(description = d, amount = a!!, categoryId = cat, date = date.toMillis(), source = ExpenseSource.manual, pendingCategory = pend, isPrivate = private, transferTo = to))
            }
            onClose()
        }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
            Text("Summa", style = t.labelMedium)
            TextField(
                amount, { v -> amount = v.filter { it.isDigit() } },
                Modifier.fillMaxWidth().testTag("amount"),
                textStyle = t.displaySmall,
                placeholder = { Text("0", style = t.displaySmall.copy(color = c.border)) },
                suffix = { Text("UZS", style = t.titleMedium.copy(color = c.muted)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = c.accent,
                ),
            )
        }
    }
    Spacer(Modifier.height(AppSpace.gap))
    Label("Tavsif")
    OutlinedTextField(
        desc, { desc = it }, Modifier.fillMaxWidth().testTag("desc"),
        placeholder = { Text("Kofe va kruassan") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = fieldShape, colors = fieldColors(),
    )
    Spacer(Modifier.height(AppSpace.gap))
    if (members.isNotEmpty() || transfer) {
        // O'tkazma: money handed to a family member, who logs what they buy with it.
        AppCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.testTag("transfer").toggleable(transfer, role = Role.Switch) { transfer = it }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.SwapHoriz, null, tint = c.muted)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("O'tkazma", style = t.bodyLarge)
                    Text("Oila a'zosiga berilgan pul — xarajatga qo'shilmaydi", style = t.bodySmall)
                }
                Switch(transfer, null)
            }
        }
        Spacer(Modifier.height(AppSpace.gap))
    }
    if (transfer) {
        Label("Kimga")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, label) in members) AppChip(label, transferTo == id, { transferTo = id })
        }
        if (members.isEmpty()) Text("A'zolar yuklanmadi — internetni tekshiring", style = t.bodySmall, modifier = Modifier.padding(start = 4.dp))
    } else {
        Label("Turkum")
        AppCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.clickable { sheet = true }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(selected?.let { colorFromHex(it.colorHex) } ?: c.border)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: "Turkum tanlang", style = t.bodyLarge.copy(color = if (selected == null) c.muted else c.text))
                    if (pending != null) Text("So'raldi: $pending — hozircha Boshqa", style = t.bodySmall)
                }
                Icon(Icons.Filled.ExpandMore, null, tint = c.muted)
            }
        }
    }
    Spacer(Modifier.height(AppSpace.gap))
    Label("Sana")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppChip("Bugun", isToday, { date = LocalDateTime.now() })
        AppChip("Kecha", isYesterday, { date = LocalDateTime.now().minusDays(1) })
        AppChip(
            if (isToday || isYesterday) "Boshqa sana" else "${uzDayMonth(date.toLocalDate())} ${date.year}",
            !isToday && !isYesterday, { picking = true },
        )
    }
    // Per-expense "hide from family" toggle; starts from the Settings default.
    // Transfers never reach the family, so no toggle for them.
    if (!transfer) {
        Spacer(Modifier.height(AppSpace.gap))
        AppCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.toggleable(private, role = Role.Switch) { private = it }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.VisibilityOff, null, tint = c.muted)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Maxfiy", style = t.bodyLarge)
                    Text("Oila bu xarajatni ko'rmaydi", style = t.bodySmall)
                }
                Switch(private, null)
            }
        }
    }
    Spacer(Modifier.height(AppSpace.section))
    PrimaryButton(::save, Modifier.fillMaxWidth(), enabled = !saving) {
        Icon(Icons.Filled.Check, null)
        Spacer(Modifier.width(8.dp))
        Text("Saqlash")
    }
    if (editing != null) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            { confirmDelete = true }, Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp), enabled = !saving,
            shape = fieldShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger),
            border = BorderStroke(1.5.dp, c.danger),
        ) {
            Icon(Icons.Outlined.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("O'chirish", style = t.labelLarge.copy(color = c.danger))
        }
    }

    if (sheet) {
        CategorySheet(db, canCreate(), selectedId = categoryId.takeIf { pending == null }, onDismiss = { sheet = false }) { r ->
            categoryId = r.id
            pending = r.pending
            sheet = false
        }
    }
    if (picking) DatePick(date.toLocalDate(), onDismiss = { picking = false }) { date = it.atStartOfDay(); picking = false }
    if (confirmDelete && editing != null) {
        ConfirmDeleteDialog(editing.description, onDismiss = { confirmDelete = false }, onConfirm = {
            confirmDelete = false
            scope.launch {
                db.deleteExpense(editing.id)
                toaster.show("Xarajat o'chirildi")
                onClose()
            }
        })
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(16.dp).background(color, CircleShape))

/**
 * Pick a category, or add one (admin/solo) / request one (family member —
 * expense waits in Boshqa until the admin approves).
 */
@Composable
private fun CategorySheet(db: ExpenseDao, canCreate: Boolean, selectedId: String?, onDismiss: () -> Unit, onPick: (ResolvedCategory) -> Unit) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    val scope = rememberCoroutineScope()
    val cats by remember(db) { db.watchCategories() }.collectAsStateWithLifecycle(emptyList())
    var ask by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismiss, containerColor = c.card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = AppSpace.section)) {
            for (cat in cats) {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(ResolvedCategory(cat.id)) }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(colorFromHex(cat.colorHex))
                    Spacer(Modifier.width(16.dp))
                    Text(cat.name, style = t.bodyLarge, modifier = Modifier.weight(1f))
                    if (cat.id == selectedId) Icon(Icons.Filled.Check, "Tanlangan", tint = c.accent)
                }
            }
            HorizontalDivider(color = c.border)
            Row(
                Modifier.fillMaxWidth().clickable { ask = true }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (canCreate) "Yangi turkum" else "Turkum so'rash", style = t.bodyLarge)
                    if (!canCreate) Text("Admin tasdiqlaguncha Boshqa'da turadi", style = t.bodySmall)
                }
            }
        }
    }
    if (ask) {
        NameDialog(canCreate, onDismiss = { ask = false }) { name ->
            ask = false
            if (name.isNotEmpty()) scope.launch { onPick(resolveCategory(db, name, canCreate)) }
        }
    }
}

@Composable
private fun NameDialog(canCreate: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canCreate) "Yangi turkum" else "Turkum so'rash") },
        text = {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Turkum nomi") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = fieldShape, colors = fieldColors(),
            )
        },
        dismissButton = { LinkButton(onDismiss) { Text("Bekor qilish") } },
        confirmButton = { LinkButton({ onDone(name.trim()) }) { Text(if (canCreate) "Qo'shish" else "So'rash") } },
    )
    DisposableEffect(Unit) { runCatching { focus.requestFocus() }; onDispose {} }
}

/** Date picker, 2020 through today. DatePicker speaks UTC midnights. */
@Composable
private fun DatePick(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    fun LocalDate.utc() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.utc(),
        yearRange = 2020..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.utc()
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LinkButton({ state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text("OK") }
        },
        dismissButton = { LinkButton(onDismiss) { Text("Bekor qilish") } },
    ) { DatePicker(state) }
}

/**
 * Type / Speak mode: text (typed or transcribed) -> AI -> {item, amount,
 * category, date} -> saved straight away with an Undo snackbar. [voice] adds a
 * mic that streams on-device STT into the field. [onFail] drops the raw text
 * into the Manual form when parsing fails.
 */
@Composable
private fun TypeForm(
    db: ExpenseDao,
    settings: SettingsStore,
    parse: suspend (String, List<String>) -> ParsedExpense?,
    speech: SpeechService,
    canCreate: () -> Boolean,
    voice: Boolean,
    onClose: () -> Unit,
    onFail: (String) -> Unit,
) {
    val c = AppTheme.colors
    val t = MaterialTheme.typography
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val micError = "Mikrofon ishlamayapti — ruxsatlarni tekshiring"

    fun startListening() {
        speech.listen(settings.settings.value.sttLocale, onResult = { input = it }, onEnd = { listening = false })
        listening = true
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else toaster.show(micError)
    }
    DisposableEffect(speech) { onDispose { speech.release() } }

    fun toggleMic() {
        when {
            listening -> { speech.stop(); listening = false }
            !speech.available() -> toaster.show(micError)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startListening()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun submit() {
        val raw = input.trim()
        if (raw.isEmpty()) return toaster.show("Nima olganingiz va qanchaligini yozing")
        if (listening) { speech.stop(); listening = false }
        busy = true
        scope.launch {
            val p = parse(raw, db.getCategories().map { it.name })
            if (p == null) {
                busy = false
                toaster.show("Tahlil qilib bo'lmadi — qo'lda to'ldiring")
                onFail(raw)
                return@launch
            }
            val cat = resolveCategory(db, p.category, canCreate())
            val id = db.insertExpense(
                Expense(
                    description = p.item, amount = p.amount, categoryId = cat.id, date = p.date.startMillis(),
                    source = if (voice) ExpenseSource.voice else ExpenseSource.typed, rawInput = raw,
                    pendingCategory = cat.pending, isPrivate = settings.settings.value.defaultPrivate,
                ),
            )
            onClose()
            toaster.show("Qo'shildi: ${p.item} — ${formatMoney(p.amount)} UZS", "Bekor qilish") { db.deleteExpense(id) }
        }
    }

    Column {
        if (voice) {
            val tint = if (listening) c.danger else c.accent
            val ring by animateColorAsState(tint.copy(alpha = if (listening) 0.18f else 0.10f), label = "ring")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(ring, CircleShape).padding(10.dp)) {
                    FilledIconButton(
                        ::toggleMic, Modifier.size(84.dp), enabled = !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = tint, contentColor = c.onAccent),
                    ) {
                        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "To'xtatish" else "Gapirish", Modifier.size(36.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (listening) "Tinglanmoqda… to'xtatish uchun bosing" else "Mikrofonni bosing va nima olganingizni ayting",
                style = t.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpace.gap))
        }
        OutlinedTextField(
            input, { input = it }, Modifier.fillMaxWidth().testTag("input"),
            textStyle = t.bodyLarge, minLines = 3, maxLines = 5,
            placeholder = { Text(if (voice) "Matn shu yerda chiqadi — kerak bo'lsa tahrirlang" else "masalan: kofe va kruassan 45000") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = fieldShape, colors = fieldColors(),
        )
        Spacer(Modifier.height(AppSpace.gap))
        PrimaryButton(::submit, Modifier.fillMaxWidth(), enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.muted, strokeWidth = 2.dp)
            else Icon(Icons.Filled.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("AI bilan qo'shish")
        }
    }
}
