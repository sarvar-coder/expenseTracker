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
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
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
import com.sarvarbek.expense_tracker.services.SpeechService
import com.sarvarbek.expense_tracker.services.matchOrCreateCategory
import com.sarvarbek.expense_tracker.ui.common.ConfirmDeleteDialog
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.colorFromHex
import com.sarvarbek.expense_tracker.ui.common.formatMoney
import com.sarvarbek.expense_tracker.ui.common.parseAmount
import com.sarvarbek.expense_tracker.ui.common.startMillis
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.toLocalDateTime
import com.sarvarbek.expense_tracker.ui.common.toMillis
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppSnackbar
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppSheet
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
enum class AddMode(val label: String) { type("add.mode.type"), speak("add.mode.speak"), manual("add.mode.manual") }

/** Manual form start values (edit, or a failed parse dropping into Manual). */
private data class Prefill(val amount: String, val desc: String, val categoryId: String?)

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
    onClose: () -> Unit,
    editing: Expense? = null,
    // ponytail: Type/Speak hidden for now; flip default to true to bring them back.
    aiModes: Boolean = false,
) {
    val c = AppTheme.colors
    // Editing (or AI modes off) forces Manual; otherwise reopen in the last picked mode.
    var mode by rememberSaveable {
        mutableStateOf(if (editing != null || !aiModes) AddMode.manual else AddMode.entries.firstOrNull { it.name == settings.lastAddMode } ?: AddMode.type)
    }
    var prefill by remember { mutableStateOf(editing?.let { Prefill(it.amount.toString(), it.description, it.categoryId) }) }
    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) t("add.title_new") else t("add.title_edit"), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("common.back")) } },
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
            if (aiModes) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AddMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; settings.lastAddMode = m.name },
                        shape = SegmentedButtonDefaults.itemShape(i, AddMode.entries.size),
                        icon = {},
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) { Text(t(m.label)) }
                }
            }
            if (aiModes) Spacer(Modifier.height(AppSpace.gap + 4.dp))
            when (mode) {
                AddMode.manual -> key(prefill) {
                    ManualForm(db, editing, prefill ?: Prefill("", "", null), onClose)
                }
                AddMode.type, AddMode.speak -> key(mode) {
                    TypeForm(db, settings, parse, speech, voice = mode == AddMode.speak, onClose) { raw ->
                        prefill = Prefill("", raw, null)
                        mode = AddMode.manual
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

@Composable
private fun ManualForm(
    db: ExpenseDao,
    editing: Expense?,
    init: Prefill,
    onClose: () -> Unit,
) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf(init.amount) }
    var desc by rememberSaveable { mutableStateOf(init.desc) }
    var categoryId by rememberSaveable { mutableStateOf(init.categoryId) }
    var date by rememberSaveable { mutableStateOf(editing?.date?.toLocalDateTime() ?: LocalDateTime.now()) }
    // Transfers are gone (2.4.0): an old one is still editable, kept a transfer, without a category.
    val transfer = editing?.transferTo != null
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
        val d = desc.trim()
        when {
            a == null || a <= 0 -> return toaster.show(t("add.err_amount"))
            d.isEmpty() -> return toaster.show(t("add.err_description"))
            !transfer && categoryId == null -> return toaster.show(t("add.err_category"))
        }
        saving = true
        scope.launch {
            val cat = if (transfer) editing.categoryId else categoryId!!
            if (editing != null) {
                db.updateExpense(editing.copy(description = d, amount = a, categoryId = cat, date = date.toMillis(), isPrivate = false, pendingCategory = null))
            } else {
                db.insertExpense(Expense(description = d, amount = a, categoryId = cat, date = date.toMillis(), source = ExpenseSource.manual))
            }
            onClose()
        }
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
            Text(t("add.amount"), color = AppTheme.colors.muted, style = ty.labelMedium)
            TextField(
                amount, { v -> amount = v.filter { it.isDigit() } },
                Modifier.fillMaxWidth().testTag("amount"),
                textStyle = ty.displaySmall,
                placeholder = { Text("0", style = ty.displaySmall.copy(color = c.border)) },
                suffix = { Text("UZS", style = ty.titleMedium.copy(color = c.muted)) },
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
    Label(t("add.description"))
    OutlinedTextField(
        desc, { desc = it }, Modifier.fillMaxWidth().testTag("desc"),
        placeholder = { Text(t("add.description_hint")) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = fieldShape, colors = fieldColors(),
    )
    Spacer(Modifier.height(AppSpace.gap))
    if (!transfer) {
        Label(t("add.category"))
        AppCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.clickable { sheet = true }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(selected?.let { colorFromHex(it.colorHex) } ?: c.border)
                Spacer(Modifier.width(16.dp))
                Text(selected?.name ?: t("add.err_category"), style = ty.bodyLarge.copy(color = if (selected == null) c.muted else c.text), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, null, tint = c.muted)
            }
        }
    }
    Spacer(Modifier.height(AppSpace.gap))
    Label(t("add.date"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppChip(t("add.today"), isToday, { date = LocalDateTime.now() })
        AppChip(t("add.yesterday"), isYesterday, { date = LocalDateTime.now().minusDays(1) })
        AppChip(
            if (isToday || isYesterday) t("add.other_date") else "${uzDayMonth(date.toLocalDate())} ${date.year}",
            !isToday && !isYesterday, { picking = true },
        )
    }
    Spacer(Modifier.height(AppSpace.section))
    PrimaryButton(::save, Modifier.fillMaxWidth(), enabled = !saving) {
        Icon(Icons.Filled.Check, null)
        Spacer(Modifier.width(8.dp))
        Text(t("common.save"))
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
            Text(t("common.delete"), style = ty.labelLarge.copy(color = c.danger))
        }
    }

    if (sheet) {
        CategorySheet(db, selectedId = categoryId, onDismiss = { sheet = false }) { id ->
            categoryId = id
            sheet = false
        }
    }
    if (picking) DatePick(date.toLocalDate(), onDismiss = { picking = false }) { date = it.atStartOfDay(); picking = false }
    if (confirmDelete && editing != null) {
        ConfirmDeleteDialog(editing.description, onDismiss = { confirmDelete = false }, onConfirm = {
            confirmDelete = false
            scope.launch {
                db.deleteExpense(editing.id)
                toaster.show(t("widgets.expense_deleted"))
                onClose()
            }
        })
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(16.dp).background(color, CircleShape))

/** Pick a category, or add a new one. */
@Composable
private fun CategorySheet(db: ExpenseDao, selectedId: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val scope = rememberCoroutineScope()
    val cats by remember(db) { db.watchCategories() }.collectAsStateWithLifecycle(emptyList())
    var ask by remember { mutableStateOf(false) }
    AppSheet(onDismiss) { hide ->
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = AppSpace.section)) {
            for (cat in cats) {
                Row(
                    Modifier.fillMaxWidth().clickable { hide { onPick(cat.id) } }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(colorFromHex(cat.colorHex))
                    Spacer(Modifier.width(16.dp))
                    Text(cat.name, style = ty.bodyLarge, modifier = Modifier.weight(1f))
                    if (cat.id == selectedId) Icon(Icons.Filled.Check, t("add.selected"), tint = c.accent)
                }
            }
            HorizontalDivider(color = c.border)
            Row(
                Modifier.fillMaxWidth().clickable { ask = true }.defaultMinSize(minHeight = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(16.dp))
                Text(t("add.new_category"), style = ty.bodyLarge)
            }
        }
    }
    if (ask) {
        NameDialog(onDismiss = { ask = false }) { name ->
            ask = false
            if (name.isNotEmpty()) scope.launch { onPick(matchOrCreateCategory(db, name)) }
        }
    }
}

@Composable
private fun NameDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("add.new_category")) },
        text = {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(t("add.category_name")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = fieldShape, colors = fieldColors(),
            )
        },
        dismissButton = { LinkButton(onDismiss) { Text(t("common.cancel")) } },
        confirmButton = { LinkButton({ onDone(name.trim()) }) { Text(t("add.add")) } },
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
            LinkButton({ state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text(t("common.ok")) }
        },
        dismissButton = { LinkButton(onDismiss) { Text(t("common.cancel")) } },
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
    voice: Boolean,
    onClose: () -> Unit,
    onFail: (String) -> Unit,
) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val micError = t("add.mic_error")

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
        if (raw.isEmpty()) return toaster.show(t("add.err_input"))
        if (listening) { speech.stop(); listening = false }
        busy = true
        scope.launch {
            val p = parse(raw, db.getCategories().map { it.name })
            if (p == null) {
                busy = false
                toaster.show(t("add.err_parse"))
                onFail(raw)
                return@launch
            }
            val cat = matchOrCreateCategory(db, p.category)
            val id = db.insertExpense(
                Expense(
                    description = p.item, amount = p.amount, categoryId = cat, date = p.date.startMillis(),
                    source = if (voice) ExpenseSource.voice else ExpenseSource.typed, rawInput = raw,
                    isPrivate = false,
                ),
            )
            onClose()
            toaster.show(t("add.added", p.item, formatMoney(p.amount)), t("common.cancel")) { db.deleteExpense(id) }
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
                        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) t("add.stop") else t("add.speak"), Modifier.size(36.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (listening) t("add.listening") else t("add.mic_hint"),
                color = AppTheme.colors.muted, style = ty.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpace.gap))
        }
        OutlinedTextField(
            input, { input = it }, Modifier.fillMaxWidth().testTag("input"),
            textStyle = ty.bodyLarge, minLines = 3, maxLines = 5,
            placeholder = { Text(if (voice) t("add.voice_hint") else t("add.type_hint")) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = fieldShape, colors = fieldColors(),
        )
        Spacer(Modifier.height(AppSpace.gap))
        PrimaryButton(::submit, Modifier.fillMaxWidth(), enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.muted, strokeWidth = 2.dp)
            else Icon(Icons.Filled.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text(t("add.ai_add"))
        }
    }
}
