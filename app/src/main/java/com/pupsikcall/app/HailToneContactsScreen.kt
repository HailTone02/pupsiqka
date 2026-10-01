package com.pupsikcall.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val ContactsPermissionPreferences = "pupsikcall.contacts.permission"
private const val ContactsPermissionRequestedKey = "read_contacts_requested"

@Composable
internal fun HailToneContactsScreen(
    profileName: String,
    onOpenCalls: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    identityMatcher: SelectedContactIdentityMatcher?,
    onCreateInvite: suspend (LocalPhoneContact) -> ContactInviteCapability,
    onAcceptInvite: suspend (LocalPhoneContact, String) -> HailToneContactLink,
    onBlockContact: suspend (UUID) -> Unit,
    onUnblockContact: suspend (UUID) -> Unit,
    trustedAutoAnswerUserIds: Set<UUID>,
    onTrustedContactChange: (UUID, Boolean) -> Unit,
    onCallContact: (UUID, String?) -> Unit,
    onMessageContact: (UUID) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences(ContactsPermissionPreferences, Context.MODE_PRIVATE)
    }
    val repository = remember(context) { AndroidContactsRepository(context.contentResolver) }
    var permission by remember(context, preferences) {
        mutableStateOf(readContactsPermission(context, preferences))
    }
    var state by remember { mutableStateOf(contactsState(permission)) }
    var reloadKey by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedContact by remember { mutableStateOf<LocalPhoneContact?>(null) }
    var identityResolution by remember { mutableStateOf<ContactIdentityResolution>(ContactIdentityResolution.LookupUnavailable) }
    var identityRetry by remember { mutableStateOf(0) }
    var showAddContact by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var operationFailed by remember { mutableStateOf(false) }

    val contactEditorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reloadKey += 1
    }

    fun refreshPermission() {
        val updated = readContactsPermission(context, preferences)
        permission = updated
        state = contactsState(updated)
        if (updated != ContactsPermissionStatus.GRANTED) selectedContact = null
        if (updated == ContactsPermissionStatus.GRANTED) reloadKey += 1
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermission()
    }
    val lifecycleOwner = remember(context) { context.findActivityLifecycleOwner() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermission()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(permission, reloadKey, repository) {
        if (permission != ContactsPermissionStatus.GRANTED) {
            state = contactsState(permission)
        } else {
            state = ContactsState.Loading
            try {
                val loadedContacts = repository.load()
                val currentPermission = readContactsPermission(context, preferences)
                permission = currentPermission
                state = contactsState(currentPermission, Result.success(loadedContacts))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                val currentPermission = readContactsPermission(context, preferences)
                permission = currentPermission
                state = if (currentPermission == ContactsPermissionStatus.GRANTED) {
                    ContactsState.Error
                } else {
                    contactsState(currentPermission)
                }
            } catch (_: Exception) {
                state = ContactsState.Error
            }
        }
    }

    LaunchedEffect(selectedContact?.lookupKey, selectedContact, identityMatcher, identityRetry) {
        val contact = selectedContact
        if (contact == null) {
            identityResolution = ContactIdentityResolution.LookupUnavailable
        } else {
            identityResolution = ContactIdentityResolution.Checking
            identityResolution = resolveContactIdentity(contact, identityMatcher)
        }
    }

    LaunchedEffect(state, selectedContact?.lookupKey) {
        val selected = selectedContact ?: return@LaunchedEffect
        val loadedContacts = (state as? ContactsState.Loaded)?.contacts ?: return@LaunchedEffect
        selectedContact = reconcileSelectedLocalContact(selected, loadedContacts)
    }

    val palette = LocalHailTonePalette.current
    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HailToneSpacing.large, vertical = HailToneSpacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.contacts), style = MaterialTheme.typography.headlineLarge, color = palette.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showAddContact = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.contact_add), tint = palette.bronze)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = palette.muted)
                }
                IconButton(onClick = onOpenProfile) {
                    ContactAvatar(profileName, null, 38.dp)
                }
            }
        }

        when (val currentState = state) {
            is ContactsState.PermissionRequired -> PermissionRequiredContent(
                status = currentState.status,
                onAction = {
                    if (currentState.status == ContactsPermissionStatus.PERMANENTLY_DENIED) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } else {
                        preferences.edit().putBoolean(ContactsPermissionRequestedKey, true).apply()
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
            )
            ContactsState.Loading -> CenteredContactsMessage {
                CircularProgressIndicator(color = palette.bronze)
                Spacer(Modifier.height(HailToneSpacing.medium))
                Text(stringResource(R.string.contacts_loading), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
            ContactsState.Empty -> CenteredContactsMessage {
                Text(stringResource(R.string.contacts_empty), color = palette.muted, style = MaterialTheme.typography.bodyLarge)
            }
            ContactsState.Error -> CenteredContactsMessage {
                Text(stringResource(R.string.contacts_error), color = palette.danger, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(HailToneSpacing.medium))
                Button(
                    onClick = { reloadKey += 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.bronze, contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.contacts_retry))
                }
            }
            is ContactsState.Loaded -> {
                val visibleContacts = filterLocalContacts(currentState.contacts, searchQuery)
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = HailToneSpacing.large)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = HailToneSpacing.small),
                        placeholder = { Text(stringResource(R.string.contacts_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = palette.muted) },
                        singleLine = true,
                        shape = HailToneShapes.control,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.field,
                            unfocusedContainerColor = palette.field,
                            focusedBorderColor = palette.bronze,
                            unfocusedBorderColor = palette.outline,
                        ),
                    )
                    if (visibleContacts.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.contacts_search_empty), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(HailToneSpacing.small),
                        ) {
                            items(visibleContacts, key = LocalPhoneContact::lookupKey) { contact ->
                                LocalContactRow(contact) {
                                    operationFailed = false
                                    selectedContact = contact
                                }
                            }
                        }
                    }
                }
            }
        }
        if (operationFailed) {
            Text(
                stringResource(R.string.contact_operation_failed),
                color = palette.danger,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = HailToneSpacing.large, vertical = HailToneSpacing.small),
            )
        }
        HailToneBottomNavigation("contacts", {}, onOpenCalls, onOpenMessages, onOpenSettings)
    }

    if (showAddContact) {
        AddLocalContactDialog(
            onDismiss = { showAddContact = false },
            onAdd = { contact ->
                val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.Contacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, contact.displayName)
                    putExtra(ContactsContract.Intents.Insert.PHONE, contact.phoneNumber)
                    putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
                try {
                    contactEditorLauncher.launch(intent)
                    showAddContact = false
                    operationFailed = false
                } catch (_: ActivityNotFoundException) {
                    operationFailed = true
                }
            },
        )
    }

    selectedContact?.let { contact ->
        ContactDetailsDialog(
            contact = contact,
            resolution = identityResolution,
            operationFailed = operationFailed,
            onDismiss = { selectedContact = null },
            onCall = { userId, verifiedName ->
                if ((identityResolution as? ContactIdentityResolution.Resolved)?.account?.userId == userId &&
                    routeAuthenticatedContactAction(
                        identityResolution,
                        AuthenticatedContactAction.CALL,
                        onCall = { onCallContact(it, verifiedName) },
                        onMessage = {},
                    )
                ) selectedContact = null
            },
            onMessage = { userId ->
                if ((identityResolution as? ContactIdentityResolution.Resolved)?.account?.userId == userId &&
                    routeAuthenticatedContactAction(
                        identityResolution,
                        AuthenticatedContactAction.MESSAGE,
                        onCall = {},
                        onMessage = onMessageContact,
                    )
                ) selectedContact = null
            },
            onShare = {
                try {
                    shareText(context, context.getString(R.string.contact_share_chooser), localContactShareText(contact))
                } catch (_: Exception) {
                    operationFailed = true
                }
            },
            onCreateInvite = onCreateInvite,
            onAcceptInvite = onAcceptInvite,
            onBlock = onBlockContact,
            onUnblock = onUnblockContact,
            trustedAutoAnswer = accountUserId(identityResolution)?.let { it in trustedAutoAnswerUserIds } == true,
            onTrustedContactChange = onTrustedContactChange,
            onDelete = { confirmDelete = true },
            onRetryIdentity = {
                identityRetry += 1
                operationFailed = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.contact_delete)) },
            text = { Text(stringResource(R.string.contact_delete_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    val contact = selectedContact ?: return@TextButton
                    val lookupUri = ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey)
                    try {
                        contactEditorLauncher.launch(Intent(Intent.ACTION_DELETE, lookupUri))
                        confirmDelete = false
                        operationFailed = false
                    } catch (_: Exception) {
                        confirmDelete = false
                        operationFailed = true
                    }
                }) { Text(stringResource(R.string.contact_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

}

private fun accountUserId(resolution: ContactIdentityResolution): UUID? =
    (resolution as? ContactIdentityResolution.Resolved)?.account?.userId

@Composable
private fun ColumnScope.PermissionRequiredContent(status: ContactsPermissionStatus, onAction: () -> Unit) {
    val palette = LocalHailTonePalette.current
    val message = when (status) {
        ContactsPermissionStatus.NOT_REQUESTED -> R.string.contacts_permission_message
        ContactsPermissionStatus.GRANTED -> R.string.contacts_permission_message
        ContactsPermissionStatus.DENIED -> R.string.contacts_permission_denied
        ContactsPermissionStatus.PERMANENTLY_DENIED -> R.string.contacts_permission_settings_message
    }
    val action = when (status) {
        ContactsPermissionStatus.PERMANENTLY_DENIED -> R.string.contacts_permission_open_settings
        ContactsPermissionStatus.DENIED -> R.string.contacts_retry
        else -> R.string.contacts_permission_allow
    }
    CenteredContactsMessage {
        Text(stringResource(R.string.contacts_permission_title), color = palette.text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(HailToneSpacing.small))
        Text(stringResource(message), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(HailToneSpacing.medium))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = palette.bronze, contentColor = Color.White),
        ) {
            Text(stringResource(action))
        }
    }
}

@Composable
private fun ColumnScope.CenteredContactsMessage(content: @Composable () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = HailToneSpacing.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun LocalContactRow(contact: LocalPhoneContact, onClick: () -> Unit) {
    val palette = LocalHailTonePalette.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(HailToneShapes.panel)
            .background(palette.surfaceRaised)
            .border(1.dp, palette.outline, HailToneShapes.panel)
            .padding(HailToneSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact.displayName, contact.photoUri, 54.dp)
        Column(Modifier.weight(1f).padding(start = HailToneSpacing.medium)) {
            Text(contact.displayName, color = palette.text, style = MaterialTheme.typography.titleMedium)
            contact.phoneNumbers.forEach { phone ->
                Text(phone.displayValue, color = palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AddLocalContactDialog(onDismiss: () -> Unit, onAdd: (NewLocalContact) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val contact = newLocalContact(name, phone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.contact_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.phone_number)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { contact?.let(onAdd) }, enabled = contact != null) {
                Text(stringResource(R.string.contact_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ContactDetailsDialog(
    contact: LocalPhoneContact,
    resolution: ContactIdentityResolution,
    operationFailed: Boolean,
    onDismiss: () -> Unit,
    onCall: (UUID, String?) -> Unit,
    onMessage: (UUID) -> Unit,
    onShare: () -> Unit,
    onCreateInvite: suspend (LocalPhoneContact) -> ContactInviteCapability,
    onAcceptInvite: suspend (LocalPhoneContact, String) -> HailToneContactLink,
    onBlock: suspend (UUID) -> Unit,
    onUnblock: suspend (UUID) -> Unit,
    trustedAutoAnswer: Boolean,
    onTrustedContactChange: (UUID, Boolean) -> Unit,
    onDelete: () -> Unit,
    onRetryIdentity: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inviteCode by rememberSaveable(contact.lookupKey) { mutableStateOf("") }
    var actionBusy by rememberSaveable(contact.lookupKey) { mutableStateOf(false) }
    var actionFailed by rememberSaveable(contact.lookupKey) { mutableStateOf(operationFailed) }
    var showBlockConfirmation by rememberSaveable(contact.lookupKey) { mutableStateOf(false) }
    val account = (resolution as? ContactIdentityResolution.Resolved)?.account
    val statusMessage = when {
        account?.blockedByMe == true -> R.string.contact_blocked_by_you
        account?.blockedMe == true -> R.string.contact_blocked_you
        resolution == ContactIdentityResolution.Checking -> R.string.contact_identity_checking
        resolution == ContactIdentityResolution.LookupUnavailable -> R.string.contact_identity_unavailable
        resolution == ContactIdentityResolution.Unverified -> R.string.contact_identity_unverified
        resolution == ContactIdentityResolution.Failed -> R.string.contact_identity_failed
        resolution is ContactIdentityResolution.Resolved -> R.string.contact_identity_verified
        else -> R.string.contact_identity_unavailable
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(contact.displayName, contact.photoUri, 48.dp)
                Text(contact.displayName, modifier = Modifier.padding(start = HailToneSpacing.medium))
            }
        },
        text = {
            Column {
                account?.displayName?.takeIf(String::isNotBlank)?.let { verifiedName ->
                    Text(verifiedName, color = LocalHailTonePalette.current.text)
                }
                account?.username?.let { username ->
                    Text("@$username", color = LocalHailTonePalette.current.bronze)
                }
                contact.phoneNumbers.forEach { number ->
                    Text(number.displayValue, color = LocalHailTonePalette.current.muted)
                }
                Text(stringResource(statusMessage), modifier = Modifier.padding(top = HailToneSpacing.medium))
                if (operationFailed || actionFailed) {
                    Text(stringResource(R.string.contact_operation_failed), color = LocalHailTonePalette.current.danger)
                }
                if (resolution == ContactIdentityResolution.Failed) {
                    TextButton(onClick = onRetryIdentity) { Text(stringResource(R.string.contacts_retry)) }
                }
                if (account != null && !account.blockedByMe && !account.blockedMe) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = HailToneSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.trusted_auto_answer_contacts), color = LocalHailTonePalette.current.text)
                            Text(stringResource(R.string.trusted_auto_answer_explanation), color = LocalHailTonePalette.current.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = trustedAutoAnswer,
                            onCheckedChange = { onTrustedContactChange(account.userId, it) },
                        )
                    }
                    TextButton(onClick = { onCall(account.userId, account.displayName) }) {
                        Text(stringResource(R.string.contact_call))
                    }
                    TextButton(onClick = { onMessage(account.userId) }) {
                        Text(stringResource(R.string.contact_message))
                    }
                    TextButton(onClick = { showBlockConfirmation = true }) {
                        Text(stringResource(R.string.contact_block))
                    }
                } else if (account?.blockedByMe == true) {
                    TextButton(
                        enabled = !actionBusy,
                        onClick = {
                            scope.launch {
                                actionBusy = true
                                actionFailed = false
                                try {
                                    onUnblock(account.userId)
                                    onRetryIdentity()
                                } catch (_: Exception) {
                                    actionFailed = true
                                } finally {
                                    actionBusy = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.contact_unblock)) }
                } else if (account?.blockedMe == true) {
                    Text(stringResource(R.string.contact_blocked_you), color = LocalHailTonePalette.current.muted)
                } else if (shouldOfferContactInvite(resolution)) {
                    TextButton(
                        enabled = !actionBusy,
                        onClick = {
                            scope.launch {
                                actionBusy = true
                                actionFailed = false
                                try {
                                    val invite = onCreateInvite(contact)
                                    val message = context.getString(R.string.contact_invite_code_message, invite.code)
                                    shareText(context, context.getString(R.string.contact_invite_chooser), message)
                                } catch (_: Exception) {
                                    actionFailed = true
                                } finally {
                                    actionBusy = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.contact_invite)) }
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it; actionFailed = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.contact_invite_code)) },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = !actionBusy && normalizeContactInvitationCode(inviteCode) != null,
                        onClick = {
                            scope.launch {
                                actionBusy = true
                                actionFailed = false
                                try {
                                    onAcceptInvite(contact, inviteCode)
                                    inviteCode = ""
                                    onRetryIdentity()
                                } catch (_: Exception) {
                                    actionFailed = true
                                } finally {
                                    actionBusy = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.contact_accept_invite)) }
                }
                TextButton(onClick = onShare) { Text(stringResource(R.string.contact_share)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.contact_delete)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )

    if (showBlockConfirmation && account != null) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmation = false },
            title = { Text(stringResource(R.string.contact_block)) },
            text = { Text(stringResource(R.string.contact_block_confirmation)) },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = {
                        showBlockConfirmation = false
                        scope.launch {
                            actionBusy = true
                            actionFailed = false
                            try {
                                onBlock(account.userId)
                                onRetryIdentity()
                            } catch (_: Exception) {
                                actionFailed = true
                            } finally {
                                actionBusy = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.contact_block)) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun HailToneBlockedContactsDialog(
    onDismiss: () -> Unit,
    onLoad: suspend () -> List<MatchedHailToneAccount>,
    onUnblock: suspend (UUID) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<MatchedHailToneAccount>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var workingUserId by remember { mutableStateOf<UUID?>(null) }

    suspend fun reload() {
        loading = true
        failed = false
        try {
            contacts = onLoad()
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_blocked_list)) },
        text = {
            when {
                loading -> CircularProgressIndicator()
                failed -> Text(stringResource(R.string.contact_blocked_load_failed))
                contacts.isNullOrEmpty() -> Text(stringResource(R.string.contact_blocked_empty))
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    contacts.orEmpty().forEach { account ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(account.displayName ?: stringResource(R.string.profile_name_not_set), modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = workingUserId == null,
                                onClick = {
                                    scope.launch {
                                        workingUserId = account.userId
                                        try {
                                            onUnblock(account.userId)
                                            reload()
                                        } catch (_: Exception) {
                                            failed = true
                                        } finally {
                                            workingUserId = null
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.contact_unblock)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { reload() } }, enabled = failed) {
                Text(stringResource(R.string.contacts_retry))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun shareText(context: Context, chooserTitle: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

@Composable
private fun ContactAvatar(name: String, photoUri: String?, size: Dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, photoUri) {
        value = photoUri?.let { encodedUri ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(encodedUri))?.use(BitmapFactory::decodeStream)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val palette = LocalHailTonePalette.current
    val initials = contactAvatarFallbackInitials(name, bitmap != null)
    Box(
        Modifier.size(size).clip(CircleShape).background(palette.surface)
            .border(1.dp, palette.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.contact_photo_description, name),
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(initials.orEmpty(), color = palette.bronze, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun readContactsPermission(context: Context, preferences: android.content.SharedPreferences): ContactsPermissionStatus {
    val granted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val requestedBefore = preferences.getBoolean(ContactsPermissionRequestedKey, false)
    val rationale = context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) == true
    return contactsPermissionStatus(granted, requestedBefore, rationale)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.findActivityLifecycleOwner(): LifecycleOwner? = findActivity() as? LifecycleOwner