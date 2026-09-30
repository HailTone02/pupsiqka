package com.pupsikcall.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

private val Context.pupsikSettingsDataStore by preferencesDataStore(name = "pupsikcall.settings")

internal enum class AutoAnswerDelay(val seconds: Int) {
    ZERO(0),
    TWO(2),
    FIVE(5);

    companion object {
        fun fromSeconds(seconds: Int?): AutoAnswerDelay = entries.firstOrNull { it.seconds == seconds } ?: TWO
    }
}

internal data class AppSettings(
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    val autoAnswerEnabled: Boolean = false,
    val autoAnswerDelay: AutoAnswerDelay = AutoAnswerDelay.TWO,
    val trustedAutoAnswerUserIds: Set<UUID> = emptySet(),
)

internal data class StoredAppSettings(
    val appearance: String? = null,
    val autoAnswerEnabled: Boolean? = null,
    val autoAnswerDelaySeconds: Int? = null,
    val trustedAutoAnswerUserIds: Set<String> = emptySet(),
)

internal object AppSettingsCodec {
    fun decode(stored: StoredAppSettings): AppSettings = AppSettings(
        appearance = AppearanceMode.fromPreference(stored.appearance),
        autoAnswerEnabled = stored.autoAnswerEnabled ?: false,
        autoAnswerDelay = AutoAnswerDelay.fromSeconds(stored.autoAnswerDelaySeconds),
        trustedAutoAnswerUserIds = stored.trustedAutoAnswerUserIds.mapNotNull { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        }.toSet(),
    )

    fun encode(settings: AppSettings): StoredAppSettings = StoredAppSettings(
        appearance = settings.appearance.preferenceValue,
        autoAnswerEnabled = settings.autoAnswerEnabled,
        autoAnswerDelaySeconds = settings.autoAnswerDelay.seconds,
        trustedAutoAnswerUserIds = settings.trustedAutoAnswerUserIds.map(UUID::toString).toSet(),
    )
}

internal data class AutoAnswerPreferenceNames(
    val enabled: String,
    val delaySeconds: String,
    val trustedUserIds: String,
)

internal fun autoAnswerPreferenceNames(userId: UUID): AutoAnswerPreferenceNames = AutoAnswerPreferenceNames(
    enabled = "auto_answer_enabled_$userId",
    delaySeconds = "auto_answer_delay_seconds_$userId",
    trustedUserIds = "trusted_auto_answer_user_ids_$userId",
)

internal sealed interface AppSettingsState {
    data object Loading : AppSettingsState
    data object Error : AppSettingsState
    data class Ready(val settings: AppSettings) : AppSettingsState
}

internal interface AppSettingsPersistence {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

internal class AppSettingsRepository(
    private val persistence: AppSettingsPersistence,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    constructor(context: Context) : this(context, null)

    constructor(context: Context, authenticatedUserId: UUID?) : this(
        DataStoreAppSettingsPersistence(context.pupsikSettingsDataStore, authenticatedUserId),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<AppSettingsState>(AppSettingsState.Loading)
    private val observeJob = scope.launch {
        persistence.settings
            .catch { failure ->
                if (failure is CancellationException) throw failure
                mutableState.value = AppSettingsState.Error
            }
            .collect { settings -> mutableState.value = AppSettingsState.Ready(settings) }
    }
    val state = mutableState.asStateFlow()

    suspend fun setAppearance(appearance: AppearanceMode) = mutate { it.copy(appearance = appearance) }

    suspend fun setAutoAnswerEnabled(enabled: Boolean) = mutate { it.copy(autoAnswerEnabled = enabled) }

    suspend fun setAutoAnswerDelay(delay: AutoAnswerDelay) = mutate { it.copy(autoAnswerDelay = delay) }

    suspend fun addTrustedAuthenticatedUser(
        userId: UUID,
        currentUserId: UUID?,
        matchedAuthenticatedUserId: UUID?,
    ): Boolean {
        if (currentUserId == null || matchedAuthenticatedUserId != userId || userId == currentUserId) return false
        val updated = mutate { current ->
            current.copy(trustedAutoAnswerUserIds = current.trustedAutoAnswerUserIds + userId)
        }
        return updated
    }

    suspend fun removeTrustedUser(userId: UUID) = mutate { current ->
        current.copy(trustedAutoAnswerUserIds = current.trustedAutoAnswerUserIds - userId)
    }

    suspend fun clearAutoAnswerForSignedOut() = mutate { current ->
        current.copy(autoAnswerEnabled = false, trustedAutoAnswerUserIds = emptySet())
    }

    private suspend fun mutate(transform: (AppSettings) -> AppSettings): Boolean = try {
        val updated = persistence.update(transform)
        mutableState.value = AppSettingsState.Ready(updated)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        mutableState.value = AppSettingsState.Error
        false
    }

    override fun close() {
        observeJob.cancel()
        scope.cancel()
    }
}

private class DataStoreAppSettingsPersistence(
    private val dataStore: DataStore<Preferences>,
    authenticatedUserId: UUID?,
) : AppSettingsPersistence {
    private val autoAnswerNames = authenticatedUserId?.let(::autoAnswerPreferenceNames)
    private val autoAnswerEnabledKey = autoAnswerNames?.let { booleanPreferencesKey(it.enabled) }
    private val autoAnswerDelayKey = autoAnswerNames?.let { intPreferencesKey(it.delaySeconds) }
    private val trustedAutoAnswerUserIdsKey = autoAnswerNames?.let { stringSetPreferencesKey(it.trustedUserIds) }

    override val settings: Flow<AppSettings> = dataStore.data
        .map(::decodePreferences)

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var updated = AppSettings()
        dataStore.edit { preferences ->
            updated = transform(decodePreferences(preferences))
            encodePreferences(preferences, updated)
        }
        return updated
    }

    private fun decodePreferences(preferences: Preferences): AppSettings = AppSettingsCodec.decode(
        StoredAppSettings(
            appearance = preferences[AppearanceKey],
            autoAnswerEnabled = autoAnswerEnabledKey?.let { preferences[it] },
            autoAnswerDelaySeconds = autoAnswerDelayKey?.let { preferences[it] },
            trustedAutoAnswerUserIds = trustedAutoAnswerUserIdsKey?.let { preferences[it] }.orEmpty(),
        ),
    )

    private fun encodePreferences(preferences: MutablePreferences, settings: AppSettings) {
        preferences[AppearanceKey] = settings.appearance.preferenceValue
        autoAnswerEnabledKey?.let { preferences[it] = settings.autoAnswerEnabled }
        autoAnswerDelayKey?.let { preferences[it] = settings.autoAnswerDelay.seconds }
        trustedAutoAnswerUserIdsKey?.let {
            preferences[it] = settings.trustedAutoAnswerUserIds.map(UUID::toString).toSet()
        }
    }

    private companion object {
        val AppearanceKey = stringPreferencesKey("appearance")
    }
}

internal enum class AutoAnswerCallState {
    RINGING,
    ACCEPTED,
    CONNECTED,
    DECLINED,
    ENDED,
}

internal sealed interface AutoAnswerDecision {
    data object ManualAnswerRequired : AutoAnswerDecision
    data class EligibleAfter(val delaySeconds: Int) : AutoAnswerDecision {
        fun isReady(elapsedSeconds: Int): Boolean = elapsedSeconds >= delaySeconds
    }
}

internal data class AutoAnswerPolicyInput(
    val featureEnabled: Boolean,
    val signedIn: Boolean,
    val localAuthenticatedUserId: UUID?,
    val incomingCallerUserId: String?,
    val routedCallerUserId: UUID?,
    val trustedUserIds: Set<UUID>,
    val delay: AutoAnswerDelay,
    val callState: AutoAnswerCallState,
)

internal object AutoAnswerPolicy {
    fun evaluate(input: AutoAnswerPolicyInput): AutoAnswerDecision {
        if (!input.featureEnabled || !input.signedIn || input.localAuthenticatedUserId == null ||
            input.callState != AutoAnswerCallState.RINGING
        ) {
            return AutoAnswerDecision.ManualAnswerRequired
        }
        val incomingCaller = input.incomingCallerUserId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return AutoAnswerDecision.ManualAnswerRequired
        if (input.routedCallerUserId == null || incomingCaller != input.routedCallerUserId) {
            return AutoAnswerDecision.ManualAnswerRequired
        }
        if (incomingCaller !in input.trustedUserIds) return AutoAnswerDecision.ManualAnswerRequired
        return AutoAnswerDecision.EligibleAfter(input.delay.seconds)
    }

    fun shouldCancelTimer(
        expectedCallId: UUID,
        expectedCallerUserId: UUID,
        currentCallId: UUID?,
        currentCallerUserId: UUID?,
        signedIn: Boolean,
        callState: AutoAnswerCallState,
        manualAction: ManualCallAction,
    ): Boolean = !signedIn ||
        currentCallId != expectedCallId ||
        currentCallerUserId != expectedCallerUserId ||
        callState != AutoAnswerCallState.RINGING ||
        manualAction != ManualCallAction.NONE
}

internal enum class ManualCallAction {
    NONE,
    ANSWERED,
    DECLINED,
}
