package com.sarvarbek.expense_tracker.features.needs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sarvarbek.expense_tracker.data.Need
import com.sarvarbek.expense_tracker.data.newId
import com.sarvarbek.expense_tracker.features.settings.AppBarScaffold
import com.sarvarbek.expense_tracker.services.NeedComment
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.EmptyState
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.t
import com.sarvarbek.expense_tracker.ui.common.toLocalDateTime
import com.sarvarbek.expense_tracker.ui.common.uzDayMonth
import com.sarvarbek.expense_tracker.ui.theme.AppCard
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Kerakli chat (#111) for one family need ([need] null = not in Room yet / deleted: no header).
 * Online only: [load] polls every [pollMs] while the screen is resumed; a failed poll keeps the
 * last messages with an offline note. A failed send keeps the typed text.
 * Own messages sit on the right; tap or long-press one to delete it.
 */
@Composable
fun NeedChatScreen(
    need: Need?,
    myId: String?,
    load: suspend () -> List<NeedComment>,
    send: suspend (id: String, body: String) -> Unit,
    delete: suspend (id: String) -> Unit,
    onBack: () -> Unit,
    pollMs: Long = 5_000,
) {
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var messages by remember { mutableStateOf<List<NeedComment>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    // Kept across retries of the same text: a lost reply then counts as sent, not a duplicate.
    var draftId by rememberSaveable { mutableStateOf(newId()) }
    var sending by remember { mutableStateOf(false) }
    var sendFailed by remember { mutableStateOf(false) } // shown under the field: a snackbar would cover it
    var deleting by remember { mutableStateOf<NeedComment?>(null) }
    val list = rememberLazyListState()
    // Polls, sends and deletes take turns: a poll started before a send can't overwrite its result.
    val lock = remember { Mutex() }

    /** [follow]: scroll to the newest even if the reader had scrolled up (own send). */
    suspend fun fetch(follow: Boolean = false) {
        try {
            val atEnd = !list.canScrollForward
            val before = messages?.size
            val m = load()
            messages = m
            failed = false
            if (m.isNotEmpty() && m.size != before && (atEnd || follow)) list.animateScrollToItem(m.size) // header is item 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // ponytail: whole thread (≤500) each poll; fetch since the last created_at if threads get long.
            while (true) { lock.withLock { fetch() }; delay(pollMs) }
        }
    }

    AppBarScaffold(t("needs.chat"), onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(AppSpace.page, 0.dp, AppSpace.page, 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    if (need != null) NeedHeader(need)
                    if (failed && messages != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WifiOff, null, tint = c.muted)
                        Text(t("chat.offline"), color = c.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                val m = messages
                when {
                    m == null && failed -> item { EmptyState(Icons.Outlined.WifiOff, t("chat.load_failed"), t("chat.offline_hint")) }
                    m == null -> item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                    }
                    m.isEmpty() -> item { EmptyState(Icons.Outlined.Forum, t("chat.empty"), t("chat.empty_hint")) }
                    else -> items(m, key = { it.id }) { msg ->
                        Bubble(msg, mine = msg.authorId == myId, onDelete = { deleting = msg })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(c.bg).padding(start = AppSpace.page, end = 4.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    draft, { draft = it.take(1000); draftId = newId(); sendFailed = false }, Modifier.weight(1f),
                    placeholder = { Text(t("chat.placeholder")) }, maxLines = 5, isError = sendFailed,
                    supportingText = if (sendFailed) { { Text(t("chat.send_failed")) } } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = fieldShape, colors = fieldColors(),
                )
                IconButton(
                    {
                        sending = true
                        sendFailed = false
                        scope.launch {
                            val body = draft
                            val id = draftId
                            try {
                                lock.withLock {
                                    send(id, body.trim())
                                    if (draft == body) { draft = ""; draftId = newId() } // keep text typed meanwhile
                                    fetch(follow = true)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                sendFailed = true // draft kept
                            } finally {
                                sending = false
                            }
                        }
                    },
                    Modifier.size(48.dp), enabled = draft.isNotBlank() && !sending,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, t("chat.send"), tint = if (draft.isNotBlank() && !sending) c.accent else c.muted)
                }
            }
        }
    }
    deleting?.let { msg ->
        ConfirmDialog(t("chat.delete_title"), msg.body, t("common.delete"), onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                try {
                    lock.withLock {
                        delete(msg.id)
                        messages = messages?.filter { it.id != msg.id }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    toaster.show(t("chat.delete_failed"))
                }
            }
        }
    }
}

@Composable
private fun NeedHeader(n: Need) = AppCard(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Column(Modifier.padding(16.dp)) {
        Text(n.text, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(t("needs.status.${n.status}"), t("needs.money").takeIf { n.needMoney }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(msg: NeedComment, mine: Boolean, onDelete: () -> Unit) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val shape = RoundedCornerShape(AppRadii.md)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .semantics(mergeDescendants = true) {}
                .then(
                    if (mine) Modifier.background(c.accent, shape)
                        .combinedClickable(onClickLabel = t("chat.delete"), onLongClickLabel = t("chat.delete"), onLongClick = onDelete, onClick = onDelete)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    else Modifier.background(c.card, shape).border(1.dp, c.border, shape),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) Text(msg.authorName.ifBlank { t("chat.former") }, style = ty.labelMedium, color = c.accent)
            Text(msg.body, style = ty.bodyMedium, color = if (mine) c.onAccent else c.text)
            Spacer(Modifier.height(2.dp))
            Text(
                stamp(msg.createdAt), style = ty.labelSmall,
                color = if (mine) c.onAccent.copy(alpha = 0.8f) else c.muted, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** "14:05" today, "3 Oktabr, 14:05" otherwise. */
private fun stamp(ms: Long): String {
    val dt = ms.toLocalDateTime()
    val hm = dt.toLocalTime().truncatedTo(ChronoUnit.MINUTES).toString()
    return if (dt.toLocalDate() == LocalDate.now()) hm else "${uzDayMonth(dt.toLocalDate())}, $hm"
}
