package com.sarvarbek.expense_tracker.features.needs

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.Need
import com.sarvarbek.expense_tracker.data.NeedStatus
import com.sarvarbek.expense_tracker.data.withStatus
import com.sarvarbek.expense_tracker.services.NeedPhotos
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppChip
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSheet
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.SecondaryButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// label is a string key.
private enum class NeedsScope(val label: String) { All("needs.filter.all"), Mine("needs.filter.mine"), Family("family.title") }

/** Same rule as the server (needs.link check): http(s), no spaces, ≤ 500. */
internal fun validLink(s: String) = s.length <= 500 && Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(s)

/**
 * What a viewer may do with [n]. Owner (unsynced rows have no owner yet) marks
 * done; the family admin marks any family item done or rejects it, and only the
 * admin reopens a rejected one. Mirrors private.need_guard.
 */
internal class NeedActions(n: Need, myId: String?, isAdmin: Boolean) {
    private val mine = n.ownerId == null || n.ownerId == myId
    private val admin = isAdmin && n.familyId != null
    private val open = n.status == NeedStatus.NEW || n.status == NeedStatus.PENDING
    val done = (mine || admin) && open
    val reject = admin && open
    val reopen = (n.status == NeedStatus.DONE && (mine || admin)) || (n.status == NeedStatus.REJECTED && admin)
    val delete = mine || admin
}

/**
 * Kerakli tab: personal items plus the family's shared ones (as of the last
 * sync), split by status. Local first; pull to sync.
 * [names]: family member labels by user id (empty = unknown, offline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsScreen(
    db: ExpenseDao,
    familyId: String?,
    myId: String?,
    isAdmin: Boolean,
    names: Map<String, String> = emptyMap(),
    photos: NeedPhotos? = null,
    onSync: suspend () -> Unit = {},
    onChat: (Need) -> Unit = {},
) {
    val needs by remember(db) { db.watchNeeds() }.collectAsStateWithLifecycle(null)
    var scopeFilter by rememberSaveable { mutableStateOf(NeedsScope.All) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val inFamily = familyId != null

    PullToRefreshBox(
        refreshing,
        onRefresh = { scope.launch { refreshing = true; onSync(); refreshing = false } },
        Modifier.fillMaxSize(),
    ) {
        val scoped = needs?.filter {
            when (scopeFilter) {
                NeedsScope.All -> true
                NeedsScope.Mine -> it.familyId == null
                NeedsScope.Family -> it.familyId != null
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            // Bottom padding clears the floating add button.
            contentPadding = PaddingValues(AppSpace.page, 8.dp, AppSpace.page, AppSpace.section + 72.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("needs.title"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    val c = AppTheme.colors
                    OutlinedIconButton(
                        { adding = true }, Modifier.size(48.dp),
                        shape = RoundedCornerShape(AppRadii.md),
                        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = c.card, contentColor = c.text),
                        border = BorderStroke(1.dp, c.border),
                    ) { Icon(Icons.Filled.Add, t("needs.add")) }
                }
                Spacer(Modifier.height(8.dp))
                StatusTabs(tab, NeedStatus.all.map { s -> scoped?.count { it.status == s } ?: 0 }) { tab = it }
                if (inFamily) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in NeedsScope.entries) AppChip(t(f.label), scopeFilter == f, { scopeFilter = f })
                    }
                }
                Spacer(Modifier.height(AppSpace.gap))
            }
            if (scoped == null) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppTheme.colors.accent) } }
                return@LazyColumn
            }
            val shown = scoped.filter { it.status == NeedStatus.all[tab] }
            if (shown.isEmpty()) item {
                if (tab == 0) EmptyState(Icons.Outlined.Checklist, t("needs.empty"), t("needs.empty_hint"))
                else EmptyState(Icons.Outlined.Inbox, t("needs.empty_status"))
            }
            items(shown, key = { it.id }) { n ->
                NeedCard(
                    n, NeedActions(n, myId, isAdmin), owner = if (inFamily && n.familyId != null) ownerLabel(n, myId, names) else null,
                    photos = photos,
                    onChat = { onChat(n) },
                    onStatus = { s -> scope.launch { db.updateNeed(n.withStatus(s)) } },
                    onDelete = {
                        scope.launch {
                            db.deleteNeed(n.id)
                            toaster.show(t("needs.deleted"), t("needs.undo")) { db.restoreNeed(n.id) }
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (adding) AddNeedSheet(db, familyId, photos) { adding = false }
}

@Composable
private fun ownerLabel(n: Need, myId: String?, names: Map<String, String>): String? =
    if (n.ownerId == null || n.ownerId == myId) t("needs.you") else names[n.ownerId]

private val statusLabels = listOf("needs.status.new", "needs.status.pending", "needs.status.done", "needs.status.rejected")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusTabs(selected: Int, counts: List<Int>, onSelect: (Int) -> Unit) {
    val c = AppTheme.colors
    PrimaryScrollableTabRow(
        selected, containerColor = c.bg, contentColor = c.accent, edgePadding = 0.dp,
        indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(selected), width = 48.dp, color = c.accent) },
        divider = {},
    ) {
        statusLabels.forEachIndexed { i, key ->
            Tab(
                selected = i == selected, onClick = { onSelect(i) },
                selectedContentColor = c.accent, unselectedContentColor = c.muted,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    if (counts[i] > 0) "${t(key)} ${counts[i]}" else t(key),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedCard(
    n: Need,
    can: NeedActions,
    owner: String?,
    photos: NeedPhotos?,
    onChat: () -> Unit,
    onStatus: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val context = LocalContext.current
    val toaster = LocalToaster.current
    AppCard(Modifier.fillMaxWidth()) {
        if (photos != null && photos.hasPhoto(n)) NeedPhoto(n, photos)
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(n.text, style = ty.titleMedium, modifier = Modifier.weight(1f).padding(top = 2.dp, end = 8.dp))
                if (can.delete) IconButton(onDelete, Modifier.size(48.dp).padding(start = 0.dp)) {
                    Icon(Icons.Outlined.Delete, t("common.delete"), tint = c.muted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (n.needMoney) Pill(t("needs.money"))
                if (owner != null) Text(owner, style = ty.bodySmall, color = c.muted, modifier = Modifier.align(Alignment.CenterVertically))
            }
            n.link?.let { link ->
                LinkButton({
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                    } catch (_: ActivityNotFoundException) {
                        toaster.show(t("needs.no_browser"))
                    }
                }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(link.toUri().host ?: link, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Chat: family items only.
                if (n.familyId != null) IconButton(onChat, Modifier.size(48.dp).offset(x = (-12).dp)) {
                    BadgedBox(badge = {
                        if (n.commentCount > 0) Badge(containerColor = c.accent, contentColor = c.onAccent) { Text("${n.commentCount}") }
                    }) { Icon(Icons.Outlined.ChatBubbleOutline, t("needs.chat_a11y", "${n.commentCount}"), tint = c.muted) }
                }
                Spacer(Modifier.weight(1f))
                if (can.reject) LinkButton({ onStatus(NeedStatus.REJECTED) }) { Text(t("needs.action.reject"), color = c.danger) }
                if (can.reopen) LinkButton({ onStatus(NeedStatus.NEW) }) { Text(t("needs.action.reopen")) }
                if (can.done) LinkButton({ onStatus(NeedStatus.DONE) }) { Text(t("needs.action.done")) }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    val c = AppTheme.colors
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = c.accent,
        modifier = Modifier.background(c.accent.copy(alpha = 0.14f), RoundedCornerShape(AppRadii.chip)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Card-wide photo; a failed download (offline, not uploaded yet) shows a retry tile. */
@Composable
private fun NeedPhoto(n: Need, photos: NeedPhotos) {
    val c = AppTheme.colors
    var attempt by remember { mutableIntStateOf(0) }
    val widthPx = with(LocalDensity.current) { 360.dp.roundToPx() }
    // null = loading; failure = retry tile.
    val photo by produceState<Result<ImageBitmap?>?>(null, n.id, n.photoPath, attempt) {
        value = null
        value = try { Result.success(photos.load(n, widthPx)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val box = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(topStart = AppRadii.card, topEnd = AppRadii.card)).background(c.border)
    val loaded = photo
    when {
        loaded == null -> Box(box)
        loaded.getOrNull() != null -> Image(loaded.getOrNull()!!, t("needs.photo"), box, contentScale = ContentScale.Crop)
        else -> Box(
            box.clickable(onClickLabel = t("needs.photo_retry")) { attempt++ },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, null, tint = c.muted)
                Text(t("needs.photo_failed"), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}

/** Add form: text, money or opinion, optional link and photo, family or personal. */
@Composable
private fun AddNeedSheet(db: ExpenseDao, familyId: String?, photos: NeedPhotos?, onDismiss: () -> Unit) = AppSheet(onDismiss) { hide ->
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var text by rememberSaveable { mutableStateOf("") }
    var needMoney by rememberSaveable { mutableStateOf(true) }
    var link by rememberSaveable { mutableStateOf("") }
    var forFamily by rememberSaveable { mutableStateOf(true) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }
    val linkOk = link.isBlank() || validLink(link.trim())
    val pick = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null && photos != null) scope.launch { jpeg = photos.encode(uri) ?: run { toaster.show(t("needs.photo_unreadable")); null } }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = AppSpace.section)) {
        Text(t("needs.add"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(AppSpace.gap))
        OutlinedTextField(
            text, { text = it.take(200) }, Modifier.fillMaxWidth(),
            placeholder = { Text(t("needs.placeholder")) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = fieldShape, colors = fieldColors(),
        )
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip(t("needs.money"), needMoney, { needMoney = true })
            AppChip(t("needs.opinion"), !needMoney, { needMoney = false })
        }
        OutlinedTextField(
            link, { link = it.take(500) }, Modifier.fillMaxWidth(),
            label = { Text(t("needs.link")) }, singleLine = true, isError = !linkOk,
            supportingText = if (linkOk) null else { { Text(t("needs.link_invalid")) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = fieldShape, colors = fieldColors(),
        )
        if (photos != null) {
            Spacer(Modifier.height(12.dp))
            val preview by produceState<ImageBitmap?>(null, jpeg) {
                value = jpeg?.let { j -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(j, 0, j.size)?.asImageBitmap() } }
            }
            val shown = preview
            if (jpeg == null || shown == null) {
                SecondaryButton({ pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("needs.photo_add"))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(shown, t("needs.photo"), Modifier.size(72.dp).clip(RoundedCornerShape(AppRadii.sm)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.weight(1f))
                    IconButton({ jpeg = null }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, t("needs.photo_remove"), tint = c.muted) }
                }
            }
        }
        if (familyId != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).defaultMinSize(minHeight = 48.dp)
                    .toggleable(forFamily, role = Role.Switch) { forFamily = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("needs.for_family"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(forFamily, null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
            }
        }
        Spacer(Modifier.height(AppSpace.gap))
        PrimaryButton({
            saving = true
            scope.launch {
                val n = Need(
                    text = text.trim(), familyId = familyId.takeIf { forFamily }, needMoney = needMoney,
                    link = link.trim().ifEmpty { null },
                )
                db.insertNeed(n)
                jpeg?.let { j -> runCatching { photos?.stash(n.id, j) }.onFailure { toaster.show(t("needs.photo_unreadable")) } }
                hide(onDismiss)
            }
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && linkOk && !saving) { Text(t("common.save")) }
    }
}
