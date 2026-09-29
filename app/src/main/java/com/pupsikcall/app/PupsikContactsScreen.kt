package com.pupsikcall.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext

private const val ContactsPermissionPreferences = "pupsikcall.contacts.permission"
private const val ContactsPermissionRequestedKey = "read_contacts_requested"

@Composable
internal fun PupsikContactsScreen(
    profileName: String,
    onOpenCalls: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val context = LocalContext.current
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

    fun refreshPermission() {
        val updated = readContactsPermission(context, preferences)
        permission = updated
        state = contactsState(updated)
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

    val palette = LocalPupsikPalette.current
    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PupsikSpacing.large, vertical = PupsikSpacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.contacts), style = MaterialTheme.typography.headlineLarge, color = palette.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(Modifier.height(PupsikSpacing.medium))
                Text(stringResource(R.string.contacts_loading), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
            ContactsState.Empty -> CenteredContactsMessage {
                Text(stringResource(R.string.contacts_empty), color = palette.muted, style = MaterialTheme.typography.bodyLarge)
            }
            ContactsState.Error -> CenteredContactsMessage {
                Text(stringResource(R.string.contacts_error), color = palette.danger, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(PupsikSpacing.medium))
                Button(
                    onClick = { reloadKey += 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.bronze, contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.contacts_retry))
                }
            }
            is ContactsState.Loaded -> {
                val visibleContacts = filterLocalContacts(currentState.contacts, searchQuery)
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = PupsikSpacing.large)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = PupsikSpacing.small),
                        placeholder = { Text(stringResource(R.string.contacts_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = palette.muted) },
                        singleLine = true,
                        shape = PupsikShapes.control,
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
                            verticalArrangement = Arrangement.spacedBy(PupsikSpacing.small),
                        ) {
                            items(visibleContacts, key = LocalPhoneContact::lookupKey) { contact ->
                                LocalContactRow(contact)
                            }
                        }
                    }
                }
            }
        }
        PupsikBottomNavigation("contacts", {}, onOpenCalls, onOpenMessages)
    }
}

@Composable
private fun ColumnScope.PermissionRequiredContent(status: ContactsPermissionStatus, onAction: () -> Unit) {
    val palette = LocalPupsikPalette.current
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
        Spacer(Modifier.height(PupsikSpacing.small))
        Text(stringResource(message), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(PupsikSpacing.medium))
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
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = PupsikSpacing.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun LocalContactRow(contact: LocalPhoneContact) {
    val palette = LocalPupsikPalette.current
    val lightUi = palette == PupsikPalettes.Light
    Row(
        Modifier.fillMaxWidth()
            .clip(PupsikShapes.panel)
            .background(palette.surfaceRaised)
            .then(if (lightUi) Modifier.border(1.dp, palette.outline.copy(alpha = 0.72f), PupsikShapes.panel) else Modifier)
            .padding(PupsikSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact.displayName, contact.photoUri, 54.dp)
        Column(Modifier.weight(1f).padding(start = PupsikSpacing.medium)) {
            Text(contact.displayName, color = palette.text, style = MaterialTheme.typography.titleMedium)
            contact.phoneNumbers.forEach { phone ->
                Text(phone.displayValue, color = palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
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
    val palette = LocalPupsikPalette.current
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