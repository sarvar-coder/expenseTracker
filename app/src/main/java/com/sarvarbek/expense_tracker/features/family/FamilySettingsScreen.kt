package com.sarvarbek.expense_tracker.features.family

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.features.settings.AppBarScaffold
import com.sarvarbek.expense_tracker.services.FamilyMember
import com.sarvarbek.expense_tracker.services.FamilyOverview
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.DividedCard
import com.sarvarbek.expense_tracker.ui.common.SectionLabel
import com.sarvarbek.expense_tracker.ui.common.TextDialog
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape

/**
 * Oila sozlamalari (gear on the Oila tab): name, member roles, invites,
 * category requests, leave, and delete (admins). Pops itself once the user
 * is no longer in a family.
 */
@Composable
fun FamilySettingsScreen(state: FamilyState, onBack: () -> Unit) {
    val actions = rememberFamilyActions(state)
    val f = state.overview
    LaunchedEffect(f == null) { if (f == null) onBack() }
    AppBarScaffold("Oila sozlamalari", onBack) { pad ->
        if (f != null) LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AppSpace.page, pad.calculateTopPadding(), AppSpace.page, AppSpace.section),
        ) { item { FamilySettings(f, actions) } }
    }
    actions.dialog()?.invoke()
}

@Composable
private fun FamilySettings(f: FamilyOverview, a: FamilyActions) {
    val c = AppTheme.colors
    val close = { a.show(null) }
    Column {
        SectionLabel("Oila")
        DividedCard(listOf(f.name)) { name ->
            IconRow(
                if (f.isAdmin) Icons.Outlined.Edit else null, name, "Oila nomi",
                if (!f.isAdmin) Modifier else Modifier.clickable {
                    a.show {
                        TextDialog("Oila nomi", "Saqlash", close, initial = name, hint = "Oila nomi") { new ->
                            if (new.isEmpty() || new == name) close() else a.act(null) { rename(f.id, new) }
                        }
                    }
                },
            )
        }
        SectionLabel("A'zolar")
        DividedCard(f.members, indent = 16.dp) { m -> RoleRow(m, f, a) }
        if (f.isAdmin) AdminSection(f, a)
        Spacer(Modifier.height(AppSpace.section))
        OutlinedButton(
            {
                a.show {
                    ConfirmDialog("Oiladan chiqasizmi?", "Umumiy xarajatlaringiz oila tarixida faqat o'qish uchun qoladi.", "Chiqish", close) {
                        a.act(null) { leave() }
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
            Text("Oiladan chiqish", style = MaterialTheme.typography.labelLarge.copy(color = c.danger))
        }
        if (f.isAdmin) {
            TextButton(
                {
                    a.show {
                        ConfirmDialog("Oila o'chirilsinmi?", "Barcha a'zolar chiqariladi. Xarajatlar har kimning o'zida qoladi.", "O'chirish", close) {
                            a.act(null) { deleteFamily() }
                        }
                    }
                },
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = c.danger),
            ) { Text("Oilani o'chirish", style = MaterialTheme.typography.labelLarge.copy(color = c.danger)) }
        }
    }
}

/** Member + role; admins get a menu to promote, demote or remove. Anyone sets their own title. */
@Composable
private fun RoleRow(m: FamilyMember, f: FamilyOverview, a: FamilyActions) {
    val self = m.userId == f.myId
    var menu by remember { mutableStateOf(false) }
    val close = { a.show(null) }
    IconRow(null, memberLabel(m, f.myId), if (m.isAdmin) "Admin" else "A'zo") {
        if (f.isAdmin || self) Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Amallar: ${m.name}") }
            DropdownMenu(menu, { menu = false }, containerColor = AppTheme.colors.card) {
                DropdownMenuItem({ Text("Oiladagi o'rni") }, {
                    menu = false
                    a.show {
                        TitleDialog(m.title.orEmpty(), close) { t ->
                            if (t == m.title.orEmpty()) close() else a.act(null) { setTitle(m.userId, t) }
                        }
                    }
                })
                if (!f.isAdmin) return@DropdownMenu
                if (m.isAdmin) {
                    DropdownMenuItem({ Text("Adminlikdan olish") }, {
                        menu = false
                        a.act(null) { setRole(m.userId, admin = false) }
                    })
                } else {
                    DropdownMenuItem({ Text("Admin qilish") }, {
                        menu = false
                        a.show {
                            ConfirmDialog("${m.name} admin qilinsinmi?", "U ham a'zolar, takliflar va turkumlarni boshqara oladi.", "Admin qilish", close) {
                                a.act(null) { setRole(m.userId, admin = true) }
                            }
                        }
                    })
                }
                if (!self) DropdownMenuItem({ Text("Oiladan chiqarish") }, {
                    menu = false
                    a.show {
                        ConfirmDialog("${m.name} oiladan chiqarilsinmi?", "Umumiy xarajatlari oila tarixida faqat o'qish uchun qoladi.", "Chiqarish", close) {
                            a.act(null) { removeMember(m.userId) }
                        }
                    }
                })
            }
        }
    }
}

private val titles = listOf("Ota", "Ona", "Farzand", "Aka", "Opa", "Uka", "Singil")

/** Preset chips or free text (≤20); empty clears the title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Oiladagi o'rni") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in titles) AppChip(t, text == t, { text = if (text == t) "" else t })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    text, { if (it.length <= 20) text = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Boshqa (masalan, Buvi)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = fieldShape, colors = fieldColors(),
                )
            }
        },
        dismissButton = { LinkButton(onDismiss) { Text("Bekor qilish") } },
        confirmButton = { LinkButton({ onDone(text.trim()) }) { Text("Saqlash") } },
    )
}

@Composable
private fun AdminSection(f: FamilyOverview, a: FamilyActions) {
    val c = AppTheme.colors
    val close = { a.show(null) }
    SectionLabel("Takliflar")
    // null = the trailing "invite a member" row.
    DividedCard(f.invites + null, indent = 16.dp) { i ->
        if (i != null) {
            IconRow(Icons.Outlined.MailOutline, i.email, "Javob kutilmoqda") {
                IconButton({ a.act(null) { deleteInvite(i.id) } }) { Icon(Icons.Filled.Close, "Taklifni bekor qilish") }
            }
        } else {
            IconRow(Icons.Outlined.PersonAddAlt, "A'zo taklif qilish", null, Modifier.clickable {
                a.show {
                    TextDialog("A'zo taklif qilish", "Taklif qilish", close, hint = "email@misol.uz", keyboard = KeyboardType.Email) { email ->
                        if (email.isEmpty()) close() else a.act("Taklif yuborildi. U kirganda ko'radi.") { invite(f.id, email) }
                    }
                }
            })
        }
    }
    SectionLabel("Turkum so'rovlari", badge = f.requests.size)
    if (f.requests.isEmpty()) {
        Text("Yangi so'rov yo'q", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
    } else {
        DividedCard(f.requests, indent = 16.dp) { r ->
            IconRow(null, r.name, "Hozircha \"Boshqa\"da") {
                IconButton({ a.act(null) { rejectRequest(r.id) } }) { Icon(Icons.Filled.Close, "Rad etish") }
                IconButton({ a.act("“${r.name}” turkumi yaratildi") { approveRequest(r.id) } }) { Icon(Icons.Filled.Check, "Tasdiqlash", tint = c.accent) }
            }
        }
    }
}

@Composable
private fun IconRow(
    icon: ImageVector?,
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
        if (subtitle != null) Text(subtitle, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
    }
    trailing()
}
