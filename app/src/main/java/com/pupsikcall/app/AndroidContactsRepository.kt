package com.pupsikcall.app

import android.content.ContentResolver
import android.database.Cursor
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.ContactsContract
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidContactsRepository(
    private val contentResolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalContactsRepository {
    override suspend fun load(): List<LocalPhoneContact> = suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        try {
            dispatcher.dispatch(continuation.context) {
                try {
                    val contacts = queryContacts(cancellationSignal)
                    continuation.completeWithValue(contacts)
                } catch (error: Exception) {
                    if (cancellationSignal.isCanceled || error is OperationCanceledException) {
                        continuation.cancel(CancellationException("Contacts query cancelled", error))
                    } else {
                        continuation.completeWithError(error)
                    }
                }
            }
        } catch (error: Exception) {
            continuation.completeWithError(error)
        }
    }

    private fun queryContacts(cancellationSignal: CancellationSignal): List<LocalPhoneContact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL AND ${ContactsContract.CommonDataKinds.Phone.NUMBER} != ''",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC, ${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} ASC, ${ContactsContract.CommonDataKinds.Phone.NUMBER} ASC",
            cancellationSignal,
        ) ?: throw IllegalStateException("Contacts provider returned no cursor")

        return cursor.use(::readContacts)
    }

    private fun readContacts(cursor: Cursor): List<LocalPhoneContact> {
        val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val lookupKeyIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
        val displayNameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val phoneNumberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val photoUriIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
        val records = mutableListOf<ContactRecord>()

        while (cursor.moveToNext()) {
            records += ContactRecord(
                contactId = cursor.getLong(contactIdIndex),
                lookupKey = cursor.getString(lookupKeyIndex),
                displayName = cursor.getString(displayNameIndex),
                phoneNumber = cursor.getString(phoneNumberIndex),
                photoUri = cursor.getString(photoUriIndex),
            )
        }
        return assembleLocalContacts(records)
    }

    private fun CancellableContinuation<List<LocalPhoneContact>>.completeWithValue(value: List<LocalPhoneContact>) {
        resume(value)
    }

    private fun CancellableContinuation<List<LocalPhoneContact>>.completeWithError(error: Exception) {
        resumeWithException(error)
    }
}