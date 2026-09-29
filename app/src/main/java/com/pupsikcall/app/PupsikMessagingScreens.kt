package com.pupsikcall.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun PupsikMessagesScreen(
    state: ConversationListState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenConversation: (MessageConversation) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCalls: () -> Unit,
) {
    val palette = LocalPupsikPalette.current
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ScreenHeader(stringResource(R.string.messages))
        when (val currentState = state) {
            ConversationListState.Loading -> CenteredMessageState {
                CircularProgressIndicator(color = palette.bronze)
                Spacer(Modifier.height(PupsikSpacing.medium))
                Text(stringResource(R.string.messages_loading), color = palette.muted)
            }
            ConversationListState.Empty -> CenteredMessageState {
                Icon(Icons.Filled.Email, contentDescription = null, tint = palette.muted, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(PupsikSpacing.medium))
                Text(stringResource(R.string.messages_empty_conversations), color = palette.muted, style = MaterialTheme.typography.bodyLarge)
            }
            ConversationListState.SignedOut -> CenteredMessageState {
                Text(stringResource(R.string.profile_signed_out), color = palette.muted)
            }
            ConversationListState.Error -> CenteredMessageState {
                Text(stringResource(R.string.messages_error), color = palette.danger)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.messages_retry), color = palette.bronze) }
            }
            is ConversationListState.Loaded -> {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = PupsikSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PupsikSpacing.small),
                ) {
                    items(currentState.conversations, key = { it.id }) { conversation ->
                        ConversationRow(conversation, onClick = { onOpenConversation(conversation) })
                    }
                    if (currentState.nextCursor != null) {
                        item {
                            TextButton(onClick = onLoadMore, enabled = !currentState.loadingMore) {
                                if (currentState.loadingMore) CircularProgressIndicator(Modifier.size(16.dp), color = palette.bronze)
                                else Text(stringResource(R.string.messages_load_older), color = palette.bronze)
                            }
                        }
                    }
                }
            }
        }
        PupsikBottomNavigation("messages", onOpenContacts, onOpenCalls, {})
    }
}

@Composable
internal fun PupsikConversationScreen(
    conversation: MessageConversation,
    state: MessageListState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryRealtime: () -> Unit,
    onLoadOlder: () -> Unit,
    onSend: suspend (UUID, UUID, String) -> MessageSendResult,
) {
    val palette = LocalPupsikPalette.current
    val scope = rememberCoroutineScope()
    var draft by remember(conversation.id) { mutableStateOf("") }
    var pendingClientMessageId by remember(conversation.id) { mutableStateOf<UUID?>(null) }
    var sending by remember(conversation.id) { mutableStateOf(false) }
    var sendFailed by remember(conversation.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = PupsikSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = palette.text)
            }
            ConversationAvatar(conversation.displayName ?: stringResource(R.string.conversation))
            Text(
                conversation.displayName ?: stringResource(R.string.conversation),
                color = palette.text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = PupsikSpacing.small),
            )
        }

        when (val currentState = state) {
            MessageListState.Loading -> CenteredMessageState {
                CircularProgressIndicator(color = palette.bronze)
                Spacer(Modifier.height(PupsikSpacing.medium))
                Text(stringResource(R.string.messages_loading), color = palette.muted)
            }
            MessageListState.Empty -> CenteredMessageState {
                Text(stringResource(R.string.no_messages), color = palette.muted)
            }
            MessageListState.SignedOut -> CenteredMessageState {
                Text(stringResource(R.string.profile_signed_out), color = palette.muted)
            }
            MessageListState.Error -> CenteredMessageState {
                Text(stringResource(R.string.messages_error), color = palette.danger)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.messages_retry), color = palette.bronze) }
            }
            is MessageListState.Loaded -> {
                if (currentState.realtimeError) {
                    TextButton(onClick = onRetryRealtime) {
                        Text(stringResource(R.string.messages_realtime_error), color = palette.danger)
                    }
                }
                if (currentState.nextCursor != null) {
                    TextButton(onClick = onLoadOlder, enabled = !currentState.loadingOlder) {
                        if (currentState.loadingOlder) CircularProgressIndicator(Modifier.size(16.dp), color = palette.bronze)
                        else Text(stringResource(R.string.messages_load_older), color = palette.bronze)
                    }
                }
                if (currentState.messages.isEmpty()) {
                    CenteredMessageState { Text(stringResource(R.string.no_messages), color = palette.muted) }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = PupsikSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(PupsikSpacing.small),
                    ) {
                        items(currentState.messages, key = { it.id }) { message ->
                            MessageBubble(message.body)
                        }
                    }
                }
            }
        }

        if (state !is MessageListState.SignedOut) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = PupsikSpacing.medium, vertical = PupsikSpacing.small)) {
                if (sendFailed) {
                    Text(stringResource(R.string.message_send_failed), color = palette.danger, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            val updatedDraft = it.take(4000)
                            if (updatedDraft.trim() != draft.trim()) pendingClientMessageId = null
                            draft = updatedDraft
                            sendFailed = false
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !sending,
                        placeholder = { Text(stringResource(R.string.message_hint)) },
                        shape = PupsikShapes.capsule,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitMessage(draft, conversation.id, pendingClientMessageId, { pendingClientMessageId = it }, { draft = it }, { sending = it }, { sendFailed = it }, onSend, scope) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.bronze,
                            unfocusedBorderColor = palette.outline,
                            focusedTextColor = palette.text,
                            unfocusedTextColor = palette.text,
                            cursorColor = palette.bronze,
                        ),
                    )
                    TextButton(
                        enabled = !sending && draft.isNotBlank(),
                        onClick = { submitMessage(draft, conversation.id, pendingClientMessageId, { pendingClientMessageId = it }, { draft = it }, { sending = it }, { sendFailed = it }, onSend, scope) },
                    ) {
                        Text(stringResource(R.string.send_message), color = palette.bronze)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: MessageConversation, onClick: () -> Unit) {
    val palette = LocalPupsikPalette.current
    val title = conversation.displayName ?: stringResource(R.string.conversation)
    Row(
        Modifier.fillMaxWidth().clip(PupsikShapes.panel).background(palette.surfaceRaised)
            .then(if (palette == PupsikPalettes.Light) Modifier.border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.panel) else Modifier)
            .clickable(onClick = onClick).padding(PupsikSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationAvatar(title)
        Text(title, color = palette.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = PupsikSpacing.medium))
    }
}

@Composable
private fun ConversationAvatar(name: String) {
    val palette = LocalPupsikPalette.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(palette.surface)
            .border(1.dp, palette.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(contactInitials(name), color = palette.bronze, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MessageBubble(body: String) {
    val palette = LocalPupsikPalette.current
    Text(
        body,
        color = palette.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clip(PupsikShapes.panel)
            .background(if (palette == PupsikPalettes.Light) palette.surfaceRaised else palette.surface)
            .then(if (palette == PupsikPalettes.Light) Modifier.border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.panel) else Modifier)
            .padding(horizontal = PupsikSpacing.medium, vertical = PupsikSpacing.small),
    )
}

@Composable
private fun ColumnScope.CenteredMessageState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f).padding(PupsikSpacing.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

private fun submitMessage(
    body: String,
    conversationId: UUID,
    currentClientMessageId: UUID?,
    setClientMessageId: (UUID?) -> Unit,
    setDraft: (String) -> Unit,
    setSending: (Boolean) -> Unit,
    setSendFailed: (Boolean) -> Unit,
    onSend: suspend (UUID, UUID, String) -> MessageSendResult,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val normalizedBody = body.trim()
    if (normalizedBody.isEmpty()) return
    val clientMessageId = currentClientMessageId ?: UUID.randomUUID().also(setClientMessageId)
    setSending(true)
    scope.launch {
        val result = onSend(conversationId, clientMessageId, normalizedBody)
        setSending(false)
        if (result == MessageSendResult.SENT) {
            setClientMessageId(null)
            setDraft("")
            setSendFailed(false)
        } else {
            setSendFailed(true)
        }
    }
}