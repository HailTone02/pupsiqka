package com.pupsikcall.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun HailToneRegisteredContactsScreen(
    profileName: String,
    onOpenCalls: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onLoadContacts: suspend () -> List<HailToneContactLink>,
    onLoadRequests: suspend () -> List<HailToneContactRequest>,
    onSearchAccounts: suspend (String) -> List<MatchedHailToneAccount>,
    onSendRequest: suspend (UUID) -> Unit,
    onAcceptRequest: suspend (UUID) -> Unit,
    onDeclineRequest: suspend (UUID) -> Unit,
    onCancelRequest: suspend (UUID) -> Unit,
    onBlockContact: suspend (UUID) -> Unit,
    onUnblockContact: suspend (UUID) -> Unit,
    trustedAutoAnswerUserIds: Set<UUID>,
    onTrustedContactChange: (UUID, Boolean) -> Unit,
    onCallContact: (UUID, String?) -> Unit,
    onMessageContact: (UUID) -> Unit,
) {
    val palette = LocalHailTonePalette.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<HailToneContactLink>>(emptyList()) }
    var requests by remember { mutableStateOf<List<HailToneContactRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MatchedHailToneAccount>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableStateOf(0) }
    var selectedContact by remember { mutableStateOf<HailToneContactLink?>(null) }
    var actionUserId by remember { mutableStateOf<UUID?>(null) }
    var actionFailed by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        loading = true
        loadFailed = false
        try {
            contacts = onLoadContacts()
            requests = onLoadRequests()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    fun searchAccounts() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        val generation = ++searchGeneration
        scope.launch {
            searching = true
            searchFailed = false
            searchResults = null
            try {
                val results = onSearchAccounts(query)
                if (generation == searchGeneration && query == searchQuery.trim()) searchResults = results
            } catch (_: Exception) {
                if (generation == searchGeneration) searchFailed = true
            } finally {
                if (generation == searchGeneration) searching = false
            }
        }
    }

    fun refreshDirectory() {
        reloadKey += 1
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = HailToneSpacing.medium),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = palette.muted)
            }
            IconButton(onClick = onOpenProfile) {
                Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.profile), tint = palette.muted)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = HailToneSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.meettone_contacts_brand),
                contentDescription = stringResource(R.string.meettone_logo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(66.dp),
            )
            Text(
                stringResource(R.string.contacts),
                style = MaterialTheme.typography.headlineMedium,
                color = palette.text,
                modifier = Modifier.padding(top = HailToneSpacing.xSmall),
            )
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                searchGeneration += 1
                searchResults = null
                searching = false
                searchFailed = false
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = HailToneSpacing.large, vertical = HailToneSpacing.medium),
            placeholder = { Text(stringResource(R.string.contacts_registered_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = palette.muted) },
            trailingIcon = {
                IconButton(onClick = ::searchAccounts, enabled = searchQuery.isNotBlank() && !searching) {
                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), color = palette.bronze, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.contacts_search_action), tint = palette.bronze)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchAccounts() }),
            shape = HailToneShapes.control,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.field,
                unfocusedContainerColor = palette.field,
                focusedBorderColor = palette.bronze,
                unfocusedBorderColor = palette.outline,
                focusedTextColor = palette.text,
                unfocusedTextColor = palette.text,
            ),
        )
        if (searchFailed || actionFailed) {
            Text(
                stringResource(R.string.contact_operation_failed),
                color = palette.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = HailToneSpacing.large),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = HailToneSpacing.large),
            verticalArrangement = Arrangement.spacedBy(HailToneSpacing.small),
        ) {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(HailToneSpacing.large), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = palette.bronze)
                    }
                }
            } else if (loadFailed) {
                item {
                    TextButton(onClick = ::refreshDirectory) { Text(stringResource(R.string.contacts_retry)) }
                }
            } else {
                val incomingRequests = requests.filter { it.direction == ContactRequestDirection.INCOMING }
                val outgoingRequests = requests.filter { it.direction == ContactRequestDirection.OUTGOING }
                if (incomingRequests.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.contacts_incoming_requests_count, incomingRequests.size)) }
                    items(incomingRequests, key = { "request-${it.requestId}" }) { request ->
                        ContactRequestRow(
                            request = request,
                            busy = actionUserId == request.account.userId,
                            onAccept = {
                                scope.launch {
                                    actionUserId = request.account.userId
                                    actionFailed = false
                                    try {
                                        onAcceptRequest(request.requestId)
                                        refreshDirectory()
                                    } catch (_: Exception) {
                                        actionFailed = true
                                    } finally {
                                        actionUserId = null
                                    }
                                }
                            },
                            onDecline = {
                                scope.launch {
                                    actionUserId = request.account.userId
                                    actionFailed = false
                                    try {
                                        onDeclineRequest(request.requestId)
                                        refreshDirectory()
                                    } catch (_: Exception) {
                                        actionFailed = true
                                    } finally {
                                        actionUserId = null
                                    }
                                }
                            },
                            onBlock = {
                                scope.launch {
                                    actionUserId = request.account.userId
                                    actionFailed = false
                                    try {
                                        onBlockContact(request.account.userId)
                                        refreshDirectory()
                                    } catch (_: Exception) {
                                        actionFailed = true
                                    } finally {
                                        actionUserId = null
                                    }
                                }
                            },
                            onCancel = {},
                        )
                    }
                }
                if (outgoingRequests.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.contacts_outgoing_requests)) }
                    items(outgoingRequests, key = { "outgoing-${it.requestId}" }) { request ->
                        ContactRequestRow(
                            request = request,
                            busy = actionUserId == request.account.userId,
                            onAccept = {},
                            onDecline = {},
                            onBlock = {},
                            onCancel = {
                                scope.launch {
                                    actionUserId = request.account.userId
                                    actionFailed = false
                                    try {
                                        onCancelRequest(request.requestId)
                                        refreshDirectory()
                                    } catch (_: Exception) {
                                        actionFailed = true
                                    } finally {
                                        actionUserId = null
                                    }
                                }
                            },
                        )
                    }
                }
                item { SectionTitle(stringResource(R.string.contacts_registered_section)) }
                if (contacts.isEmpty()) {
                    item { Text(stringResource(R.string.contacts_registered_empty), color = palette.muted) }
                } else {
                    items(contacts, key = { "contact-${it.account.userId}" }) { contact ->
                        RegisteredContactRow(contact) { selectedContact = contact }
                    }
                }
                if (searching) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = HailToneSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = palette.bronze, strokeWidth = 2.dp)
                            Text(stringResource(R.string.contacts_searching), color = palette.muted, modifier = Modifier.padding(start = HailToneSpacing.small))
                        }
                    }
                } else if (searchResults != null) {
                    item { SectionTitle(stringResource(R.string.contacts_search_results)) }
                    if (searchResults.isNullOrEmpty()) {
                        item { Text(stringResource(R.string.contacts_account_not_found), color = palette.muted) }
                    } else {
                        items(searchResults.orEmpty(), key = { "result-${it.userId}" }) { account ->
                            val linked = contacts.any { it.account.userId == account.userId }
                            val request = requests.firstOrNull { it.account.userId == account.userId }
                            SearchAccountRow(
                                account = account,
                                connected = linked,
                                request = request,
                                busy = actionUserId == account.userId,
                                onCall = { onCallContact(account.userId, account.displayName) },
                                onMessage = { onMessageContact(account.userId) },
                                onSendRequest = {
                                    scope.launch {
                                        actionUserId = account.userId
                                        actionFailed = false
                                        try {
                                            onSendRequest(account.userId)
                                            refreshDirectory()
                                        } catch (_: Exception) {
                                            actionFailed = true
                                        } finally {
                                            actionUserId = null
                                        }
                                    }
                                },
                                onAcceptRequest = { requestId ->
                                    scope.launch {
                                        actionUserId = account.userId
                                        actionFailed = false
                                        try {
                                            onAcceptRequest(requestId)
                                            refreshDirectory()
                                        } catch (_: Exception) {
                                            actionFailed = true
                                        } finally {
                                            actionUserId = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        HailToneBottomNavigation("contacts", {}, onOpenCalls, onOpenMessages, onOpenSettings)
    }

    selectedContact?.let { contact ->
        RegisteredContactProfileDialog(
            contact = contact,
            trusted = contact.account.userId in trustedAutoAnswerUserIds,
            onTrustedChange = onTrustedContactChange,
            onCall = { onCallContact(contact.account.userId, contact.account.displayName) },
            onMessage = { onMessageContact(contact.account.userId) },
            onBlock = onBlockContact,
            onUnblock = onUnblockContact,
            onDismiss = { selectedContact = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = LocalHailTonePalette.current.text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = HailToneSpacing.medium, bottom = HailToneSpacing.xSmall),
    )
}

@Composable
private fun RegisteredContactRow(contact: HailToneContactLink, onClick: () -> Unit) {
    val palette = LocalHailTonePalette.current
    Row(
        Modifier.fillMaxWidth().clip(HailToneShapes.control).background(palette.surface)
            .border(1.dp, palette.outline, HailToneShapes.control)
            .clickable(onClick = onClick)
            .padding(HailToneSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactInitial(contact.account.displayName ?: contact.account.username.orEmpty())
        Column(Modifier.weight(1f).padding(start = HailToneSpacing.medium)) {
            Text(contact.account.displayName ?: stringResource(R.string.profile_name_not_set), color = palette.text)
            contact.account.username?.let { Text("@$it", color = palette.bronze, style = MaterialTheme.typography.bodySmall) }
            if (contact.account.blockedByMe) Text(stringResource(R.string.contact_blocked_by_you), color = palette.muted, style = MaterialTheme.typography.bodySmall)
            else if (contact.account.blockedMe) Text(stringResource(R.string.contact_blocked_you), color = palette.muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.profile), tint = palette.muted)
    }
}

@Composable
private fun ContactRequestRow(
    request: HailToneContactRequest,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onBlock: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalHailTonePalette.current
    Row(
        Modifier.fillMaxWidth().clip(HailToneShapes.control).background(palette.surface)
            .border(1.dp, palette.outline, HailToneShapes.control).padding(HailToneSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactInitial(request.account.displayName ?: request.account.username.orEmpty())
        Column(Modifier.weight(1f).padding(start = HailToneSpacing.medium)) {
            Text(request.account.displayName ?: stringResource(R.string.profile_name_not_set), color = palette.text)
            request.account.username?.let { Text("@$it", color = palette.bronze, style = MaterialTheme.typography.bodySmall) }
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = palette.bronze, strokeWidth = 2.dp)
        else if (request.direction == ContactRequestDirection.OUTGOING) {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.contacts_pending), color = palette.muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.contacts_cancel_request), color = palette.muted) }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(HailToneSpacing.xSmall)) {
                    TextButton(onClick = onDecline) {
                        Text(stringResource(R.string.contacts_decline_request), color = palette.muted)
                    }
                    TextButton(onClick = onAccept, enabled = !request.account.blockedByMe && !request.account.blockedMe) {
                        Text(stringResource(R.string.contacts_accept_request), color = palette.bronze)
                    }
                }
                TextButton(onClick = onBlock) { Text(stringResource(R.string.contacts_block_request), color = palette.danger) }
            }
        }
    }
}

@Composable
private fun SearchAccountRow(
    account: MatchedHailToneAccount,
    connected: Boolean,
    request: HailToneContactRequest?,
    busy: Boolean,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onSendRequest: () -> Unit,
    onAcceptRequest: (UUID) -> Unit,
) {
    val palette = LocalHailTonePalette.current
    val blocked = account.blockedByMe || account.blockedMe
    Row(
        Modifier.fillMaxWidth().clip(HailToneShapes.control).background(palette.surface)
            .border(1.dp, palette.outline, HailToneShapes.control).padding(HailToneSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactInitial(account.displayName ?: account.username.orEmpty())
        Column(Modifier.weight(1f).padding(start = HailToneSpacing.medium)) {
            Text(account.displayName ?: stringResource(R.string.profile_name_not_set), color = palette.text)
            account.username?.let { Text("@$it", color = palette.bronze, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(HailToneSpacing.small)) {
                TextButton(onClick = onCall, enabled = connected && !blocked) { Text(stringResource(R.string.contact_call)) }
                TextButton(onClick = onMessage, enabled = connected && !blocked) { Text(stringResource(R.string.contact_message)) }
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), color = palette.bronze, strokeWidth = 2.dp)
            blocked -> Text(stringResource(R.string.contact_blocked_by_you), color = palette.muted, style = MaterialTheme.typography.bodySmall)
            connected -> Text(stringResource(R.string.contacts_connected), color = palette.online, style = MaterialTheme.typography.bodySmall)
            request?.direction == ContactRequestDirection.INCOMING -> TextButton(onClick = { onAcceptRequest(request.requestId) }) {
                Text(stringResource(R.string.contacts_accept_request), color = palette.bronze)
            }
            request?.direction == ContactRequestDirection.OUTGOING -> Text(stringResource(R.string.contacts_pending), color = palette.muted, style = MaterialTheme.typography.bodySmall)
            else -> TextButton(onClick = onSendRequest) { Text(stringResource(R.string.contacts_send_request), color = palette.bronze) }
        }
    }
}

@Composable
private fun ContactInitial(name: String) {
    val palette = LocalHailTonePalette.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(palette.glow),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = palette.text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RegisteredContactProfileDialog(
    contact: HailToneContactLink,
    trusted: Boolean,
    onTrustedChange: (UUID, Boolean) -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onBlock: suspend (UUID) -> Unit,
    onUnblock: suspend (UUID) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalHailTonePalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val account = contact.account
    val blocked = account.blockedByMe || account.blockedMe
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(account.displayName ?: stringResource(R.string.profile_name_not_set)) },
        text = {
            Column {
                account.username?.let { Text("@$it", color = palette.bronze) }
                if (!blocked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.trusted_auto_answer_contacts), color = palette.text, modifier = Modifier.weight(1f))
                        Switch(checked = trusted, onCheckedChange = { onTrustedChange(account.userId, it) })
                    }
                    Text(stringResource(R.string.trusted_auto_answer_explanation), color = palette.muted, style = MaterialTheme.typography.bodySmall)
                }
                if (failed) Text(stringResource(R.string.contact_operation_failed), color = palette.danger)
                TextButton(enabled = !blocked, onClick = onCall) { Text(stringResource(R.string.contact_call)) }
                TextButton(enabled = !blocked, onClick = onMessage) { Text(stringResource(R.string.contact_message)) }
                if (account.blockedByMe) {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            failed = false
                            try { onUnblock(account.userId); onDismiss() } catch (_: Exception) { failed = true } finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.contact_unblock)) }
                } else if (!account.blockedMe) {
                    TextButton(enabled = !busy, onClick = { confirmBlock = true }) { Text(stringResource(R.string.contact_block), color = palette.danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(stringResource(R.string.contact_block)) },
            text = { Text(stringResource(R.string.contact_block_confirmation)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmBlock = false
                    scope.launch {
                        busy = true
                        failed = false
                        try { onBlock(account.userId); onDismiss() } catch (_: Exception) { failed = true } finally { busy = false }
                    }
                }) { Text(stringResource(R.string.contact_block)) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}