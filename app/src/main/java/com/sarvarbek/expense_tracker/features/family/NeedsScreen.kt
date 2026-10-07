package com.sarvarbek.expense_tracker.features.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.Need
import com.sarvarbek.expense_tracker.features.settings.AppBarScaffold
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.SectionLabel
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.launch

private enum class NeedsFilter(val label: String) { All("Hammasi"), Mine("Mening"), Family("Oila") }

/** Oila tab card: up to 3 open items and how many in all; tap opens [NeedsScreen]. */
@Composable
fun NeedsCard(db: ExpenseDao, onOpen: () -> Unit) {
    val needs by remember(db) { db.watchNeeds() }.collectAsStateWithLifecycle(emptyList())
    val open = needs.filter { !it.done }
    val t = MaterialTheme.typography
    SectionLabel("Kerakli")
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClickLabel = "Kerakli ro'yxatini ochish", onClick = onOpen).padding(16.dp, 12.dp)) {
            if (open.isEmpty()) {
                Text("Hammasi olingan", style = t.titleSmall)
                Text("Nima kerakligini yozib qo'ying", style = t.bodySmall)
            } else {
                for (n in open.take(3)) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("•  ${n.text}", style = t.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (n.familyId != null) FamilyMark()
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp).defaultMinSize(minHeight = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (open.size > 3) "Yana ${open.size - 3} ta" else "Ro'yxatni ochish", style = t.labelLarge, color = AppTheme.colors.accent, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AppTheme.colors.accent)
            }
        }
    }
}

@Composable
private fun FamilyMark() = Icon(Icons.Outlined.Groups, "Oila uchun", tint = AppTheme.colors.muted, modifier = Modifier.size(16.dp))

/**
 * "Kerakli": what to buy. Personal items, plus the family's shared ones
 * when [familyId] is set (as of the last sync). Local first; pull to sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsScreen(db: ExpenseDao, familyId: String?, onSync: suspend () -> Unit, onBack: () -> Unit) {
    val needs by remember(db) { db.watchNeeds() }.collectAsStateWithLifecycle(null)
    var filter by rememberSaveable { mutableStateOf(NeedsFilter.All) }
    var text by rememberSaveable { mutableStateOf("") }
    var forFamily by rememberSaveable { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val inFamily = familyId != null
    val add = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            text = ""
            scope.launch { db.insertNeed(Need(text = t.take(200), familyId = familyId.takeIf { forFamily })) }
        }
    }

    AppBarScaffold("Kerakli", onBack) { pad ->
        PullToRefreshBox(
            refreshing,
            onRefresh = { scope.launch { refreshing = true; onSync(); refreshing = false } },
            Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            text, { text = it }, Modifier.weight(1f),
                            placeholder = { Text("Nima kerak?") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            shape = fieldShape, colors = fieldColors(),
                        )
                        Spacer(Modifier.width(8.dp))
                        val c = AppTheme.colors
                        FilledIconButton(
                            add, Modifier.size(56.dp), enabled = text.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent),
                        ) { Icon(Icons.Filled.Add, "Qo'shish") }
                    }
                    if (inFamily) {
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                .toggleable(forFamily, role = Role.Switch) { forFamily = it }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Oila uchun", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(forFamily, null, colors = SwitchDefaults.colors(checkedTrackColor = AppTheme.colors.accent, checkedThumbColor = AppTheme.colors.onAccent))
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (f in NeedsFilter.entries) AppChip(f.label, filter == f, { filter = f })
                        }
                    }
                    Spacer(Modifier.height(AppSpace.gap))
                }
                item {
                    val all = needs ?: return@item // first Room read, a blink
                    val shown = all.filter {
                        when (filter) {
                            NeedsFilter.All -> true
                            NeedsFilter.Mine -> it.familyId == null
                            NeedsFilter.Family -> it.familyId != null
                        }
                    }
                    if (shown.isEmpty()) {
                        EmptyState(Icons.Outlined.Checklist, "Ro'yxat bo'sh", "Kerakli narsani yozib, + ni bosing")
                    } else {
                        DividedCard(shown, indent = 56.dp) { n ->
                            NeedRow(
                                n, showFamily = inFamily,
                                onToggle = { scope.launch { db.updateNeed(n.copy(done = it)) } },
                                onDelete = {
                                    scope.launch {
                                        db.deleteNeed(n.id)
                                        toaster.show("O'chirildi", "Qaytarish") { db.updateNeed(n) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedRow(n: Need, showFamily: Boolean, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .toggleable(n.done, role = Role.Checkbox, onValueChange = onToggle).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(n.done, null, Modifier.padding(12.dp), colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
        Text(
            n.text, style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (n.done) TextDecoration.LineThrough else null),
            color = if (n.done) c.muted else c.text, modifier = Modifier.weight(1f),
        )
        if (showFamily && n.familyId != null) FamilyMark()
        IconButton(onDelete) { Icon(Icons.Outlined.Close, "O'chirish", tint = c.muted) }
    }
}
