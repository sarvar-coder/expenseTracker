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
import com.sarvarbek.expense_tracker.ui.common.t

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
    AppBarScaffold(t("family.settings"), onBack) { pad ->
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
        SectionLabel(t("family.title"))
        DividedCard(listOf(f.name)) { name ->
            IconRow(
                if (f.isAdmin) Icons.Outlined.Edit else null, name, t("family.name"),
                if (!f.isAdmin) Modifier else Modifier.clickable {
                    a.show {
                        TextDialog(t("family.name"), t("common.save"), close, initial = name, hint = t("family.name")) { new ->
                            if (new.isEmpty() || new == name) close() else a.act(null) { rename(f.id, new) }
                        }
                    }
                },
            )
        }
        SectionLabel(t("family.members"))
        DividedCard(f.members, indent = 16.dp) { m -> RoleRow(m, f, a) }
        if (f.isAdmin) AdminSection(f, a)
        Spacer(Modifier.height(AppSpace.section))
        OutlinedButton(
            {
                a.show {
                    ConfirmDialog(t("familySettings.leave_confirm"), t("familySettings.leave_text"), t("familySettings.leave_action"), close) {
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
            Text(t("familySettings.leave"), style = MaterialTheme.typography.labelLarge.copy(color = c.danger))
        }
        if (f.isAdmin) {
            TextButton(
                {
                    a.show {
                        ConfirmDialog(t("familySettings.delete_confirm"), t("familySettings.delete_text"), t("common.delete"), close) {
                            a.act(null) { deleteFamily() }
                        }
                    }
                },
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = c.danger),
            ) { Text(t("familySettings.delete"), style = MaterialTheme.typography.labelLarge.copy(color = c.danger)) }
        }
    }
}

/** Member + role; admins get a menu to promote, demote or remove. Anyone sets their own title. */
@Composable
private fun RoleRow(m: FamilyMember, f: FamilyOverview, a: FamilyActions) {
    val self = m.userId == f.myId
    var menu by remember { mutableStateOf(false) }
    val close = { a.show(null) }
    IconRow(null, memberLabel(m, f.myId), if (m.isAdmin) t("familySettings.admin") else t("familySettings.member")) {
        if (f.isAdmin || self) Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, t("familySettings.actions", m.name)) }
            DropdownMenu(menu, { menu = false }, containerColor = AppTheme.colors.card) {
                DropdownMenuItem({ Text(t("familySettings.role")) }, {
                    menu = false
                    a.show {
                        TitleDialog(m.title.orEmpty(), close) { v ->
                            if (v == m.title.orEmpty()) close() else a.act(null) { setTitle(m.userId, v) }
                        }
                    }
                })
                if (!f.isAdmin) return@DropdownMenu
                if (m.isAdmin) {
                    DropdownMenuItem({ Text(t("familySettings.unadmin")) }, {
                        menu = false
                        a.act(null) { setRole(m.userId, admin = false) }
                    })
                } else {
                    DropdownMenuItem({ Text(t("familySettings.make_admin")) }, {
                        menu = false
                        a.show {
                            ConfirmDialog(t("familySettings.make_admin_confirm", m.name), t("familySettings.make_admin_text"), t("familySettings.make_admin"), close) {
                                a.act(null) { setRole(m.userId, admin = true) }
                            }
                        }
                    })
                }
                if (!self) DropdownMenuItem({ Text(t("familySettings.remove")) }, {
                    menu = false
                    a.show {
                        ConfirmDialog(t("familySettings.remove_confirm", m.name), t("familySettings.remove_text"), t("familySettings.remove_action"), close) {
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
        title = { Text(t("familySettings.role")) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (ti in titles) AppChip(ti, text == ti, { text = if (text == ti) "" else ti })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    text, { if (it.length <= 20) text = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text(t("familySettings.title_other")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = fieldShape, colors = fieldColors(),
                )
            }
        },
        dismissButton = { LinkButton(onDismiss) { Text(t("common.cancel")) } },
        confirmButton = { LinkButton({ onDone(text.trim()) }) { Text(t("common.save")) } },
    )
}

@Composable
private fun AdminSection(f: FamilyOverview, a: FamilyActions) {
    val c = AppTheme.colors
    val close = { a.show(null) }
    SectionLabel(t("familySettings.invites"))
    // null = the trailing "invite a member" row.
    DividedCard(f.invites + null, indent = 16.dp) { i ->
        if (i != null) {
            IconRow(Icons.Outlined.MailOutline, i.email, t("familySettings.pending")) {
                IconButton({ a.act(null) { deleteInvite(i.id) } }) { Icon(Icons.Filled.Close, t("familySettings.cancel_invite")) }
            }
        } else {
            IconRow(Icons.Outlined.PersonAddAlt, t("familySettings.invite_member"), null, Modifier.clickable {
                a.show {
                    TextDialog(t("familySettings.invite_member"), t("familySettings.invite"), close, hint = "email@misol.uz", keyboard = KeyboardType.Email) { email ->
                        if (email.isEmpty()) close() else a.act(t("familySettings.invite_sent")) { invite(f.id, email) }
                    }
                }
            })
        }
    }
    SectionLabel(t("familySettings.requests"), badge = f.requests.size)
    if (f.requests.isEmpty()) {
        Text(t("familySettings.no_requests"), color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
    } else {
        DividedCard(f.requests, indent = 16.dp) { r ->
            IconRow(null, r.name, t("familySettings.in_other")) {
                IconButton({ a.act(null) { rejectRequest(r.id) } }) { Icon(Icons.Filled.Close, t("family.decline")) }
                IconButton({ a.act(t("familySettings.category_created", r.name)) { approveRequest(r.id) } }) { Icon(Icons.Filled.Check, t("auth.confirm"), tint = c.accent) }
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
