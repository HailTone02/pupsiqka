package com.pupsikcall.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PhoneVerificationSection(
    state: PhoneVerificationState,
    onLoad: suspend () -> Unit,
    onRequest: suspend (String) -> Unit,
    onResend: suspend () -> Unit,
    onVerify: suspend (String) -> Unit,
    resendCooldownSeconds: () -> Int,
) {
    val palette = LocalHailTonePalette.current
    val scope = rememberCoroutineScope()
    var phoneInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var changingNumber by remember { mutableStateOf(false) }
    var remainingCooldownSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(state) {
        val hasOtp = state == PhoneVerificationState.AwaitingOtp ||
            (state is PhoneVerificationState.Error && state.reason == PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED)
        if (hasOtp) {
            while (true) {
                remainingCooldownSeconds = resendCooldownSeconds()
                if (remainingCooldownSeconds == 0) break
                delay(1_000)
            }
        } else {
            remainingCooldownSeconds = 0
        }
    }

    LaunchedEffect(state) {
        if (state == PhoneVerificationState.Verified) {
            phoneInput = ""
            otpInput = ""
            changingNumber = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = HailToneSpacing.large)) {
        Text(stringResource(R.string.phone_number), color = palette.text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(HailToneSpacing.small))

        when {
            state == PhoneVerificationState.Checking || state == PhoneVerificationState.RequestingOtp || state == PhoneVerificationState.Verifying -> {
                Row(horizontalArrangement = Arrangement.spacedBy(HailToneSpacing.small)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = palette.bronze, strokeWidth = 2.dp)
                    Text(
                        stringResource(if (state == PhoneVerificationState.Checking) R.string.phone_checking else R.string.phone_working),
                        color = palette.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state == PhoneVerificationState.Verified && !changingNumber -> {
                Text(stringResource(R.string.phone_verified), color = palette.online, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { changingNumber = true }) {
                    Text(stringResource(R.string.phone_change_number), color = palette.bronze)
                }
            }
            state == PhoneVerificationState.AwaitingOtp ||
                (state is PhoneVerificationState.Error && state.reason == PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED) -> {
                if (state is PhoneVerificationState.Error) {
                    Text(phoneErrorString(state.reason), color = palette.danger, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.phone_code_sent), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { value -> otpInput = value.filter { it in '0'..'9' }.take(8) },
                    modifier = Modifier.fillMaxWidth().padding(top = HailToneSpacing.small),
                    label = { Text(stringResource(R.string.phone_code_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.bronze,
                        unfocusedBorderColor = palette.outline,
                        focusedTextColor = palette.text,
                        unfocusedTextColor = palette.text,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(HailToneSpacing.small)) {
                    Button(
                        onClick = {
                            val submittedOtp = otpInput
                            otpInput = ""
                            scope.launch { onVerify(submittedOtp) }
                        },
                        enabled = otpInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.bronze),
                    ) {
                        Text(stringResource(R.string.phone_verify_code))
                    }
                    TextButton(
                        onClick = { scope.launch { onResend() } },
                        enabled = remainingCooldownSeconds == 0,
                    ) {
                        Text(
                            if (remainingCooldownSeconds > 0) stringResource(R.string.phone_resend_cooldown, remainingCooldownSeconds)
                            else stringResource(R.string.phone_resend_code),
                            color = palette.bronze,
                        )
                    }
                }
            }
            else -> {
                if (state is PhoneVerificationState.Error) {
                    Text(phoneErrorString(state.reason), color = palette.danger, style = MaterialTheme.typography.bodyMedium)
                    if (state.reason == PhoneVerificationError.SESSION_LOST || state.reason == PhoneVerificationError.ACCOUNT_CHANGED) {
                        TextButton(onClick = { scope.launch { onLoad() } }) {
                            Text(stringResource(R.string.phone_retry), color = palette.bronze)
                        }
                    }
                }
                Text(stringResource(R.string.phone_e164_help), color = palette.muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    modifier = Modifier.fillMaxWidth().padding(top = HailToneSpacing.small),
                    label = { Text(stringResource(R.string.phone_input_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.bronze,
                        unfocusedBorderColor = palette.outline,
                        focusedTextColor = palette.text,
                        unfocusedTextColor = palette.text,
                    ),
                )
                Button(
                    onClick = { scope.launch { onRequest(phoneInput) } },
                    enabled = phoneInput.isNotBlank(),
                    modifier = Modifier.padding(top = HailToneSpacing.small),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.bronze),
                ) {
                    Text(stringResource(R.string.phone_request_code))
                }
            }
        }
    }
}

@Composable
private fun phoneErrorString(reason: PhoneVerificationError): String = when (reason) {
    PhoneVerificationError.INVALID_PHONE -> stringResource(R.string.phone_invalid_number)
    PhoneVerificationError.REQUEST_FAILED -> stringResource(R.string.phone_request_failed)
    PhoneVerificationError.CODE_INVALID_EXPIRED_OR_RATE_LIMITED -> stringResource(R.string.phone_code_failed)
    PhoneVerificationError.STATUS_CHECK_FAILED -> stringResource(R.string.phone_status_failed)
    PhoneVerificationError.SESSION_LOST -> stringResource(R.string.phone_session_lost)
    PhoneVerificationError.ACCOUNT_CHANGED -> stringResource(R.string.phone_identity_changed)
    PhoneVerificationError.CONFIRMATION_MISSING -> stringResource(R.string.phone_confirmation_failed)
}