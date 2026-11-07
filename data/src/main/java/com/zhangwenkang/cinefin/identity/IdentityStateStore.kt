package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val IDENTITY_STATE_PREFERENCE_KEY = "pref_identity_state_v1"

internal enum class IdentityWriteOutcome {
    COMMITTED,
    NOT_COMMITTED,
    UNCERTAIN,
}

class IdentityStateStore @Inject constructor(private val appPreferences: AppPreferences) {
    @Volatile private var highestObservedGeneration = 0L
    private var lastKnownSafeSnapshot = EMPTY_IDENTITY_SNAPSHOT
    private var quarantined = false
    private var quarantineGeneration = 0L

    @Synchronized
    fun read(): IdentitySnapshot {
        val persisted = readPersistedState()
        val snapshot = persisted.snapshot
        highestObservedGeneration = maxOf(highestObservedGeneration, snapshot.identityGeneration)
        if (!persisted.projectionMatches) {
            beginQuarantine(snapshot.identityGeneration)
        } else if (!quarantined) {
            lastKnownSafeSnapshot = snapshot
        }
        return if (quarantined) unavailableSnapshot() else snapshot
    }

    @Synchronized
    internal fun write(snapshot: IdentitySnapshot): IdentityWriteOutcome {
        val persistedBefore = readPersistedState()
        if (!quarantined && persistedBefore.projectionMatches) {
            lastKnownSafeSnapshot = persistedBefore.snapshot
        }
        val encoded = runCatching {
            IdentityStateCodec.encode(snapshot)
        }
            .getOrElse {
                return IdentityWriteOutcome.NOT_COMMITTED
            }
        val committed =
            try {
                appPreferences.sharedPreferences
                    .edit()
                    .putString(IDENTITY_STATE_PREFERENCE_KEY, encoded)
                    .putString(
                        appPreferences.currentServer.backendName,
                        snapshot.selection.serverId,
                    )
                    .putBoolean(
                        appPreferences.offlineMode.backendName,
                        snapshot.selection.offlineMode,
                    )
                    .commit()
            } catch (_: Exception) {
                beginQuarantine(lastKnownSafeSnapshot.identityGeneration)
                highestObservedGeneration =
                    maxOf(highestObservedGeneration, snapshot.identityGeneration)
                return IdentityWriteOutcome.UNCERTAIN
            }
        if (!committed) {
            beginQuarantine(lastKnownSafeSnapshot.identityGeneration)
            highestObservedGeneration =
                maxOf(highestObservedGeneration, snapshot.identityGeneration)
            return IdentityWriteOutcome.UNCERTAIN
        }

        val persistedAfter = readPersistedState()
        if (!persistedAfter.projectionMatches || persistedAfter.snapshot != snapshot) {
            beginQuarantine(lastKnownSafeSnapshot.identityGeneration)
            highestObservedGeneration =
                maxOf(highestObservedGeneration, snapshot.identityGeneration)
            return IdentityWriteOutcome.UNCERTAIN
        }

        highestObservedGeneration = maxOf(highestObservedGeneration, snapshot.identityGeneration)
        lastKnownSafeSnapshot = snapshot
        quarantined = false
        return IdentityWriteOutcome.COMMITTED
    }

    @Synchronized
    fun nextSnapshot(
        selection: IdentitySelection,
        status: IdentityStateStatus,
    ): IdentitySnapshot {
        val current = readPersistedState().snapshot
        val previous = maxOf(current.identityGeneration, highestObservedGeneration)
        check(previous < Long.MAX_VALUE) { "Identity generation exhausted" }
        highestObservedGeneration = previous
        return IdentitySnapshot(
            selection = selection,
            identityGeneration = previous + 1L,
            status = status,
        )
    }

    @Synchronized
    internal fun persistedForTransition(): IdentitySnapshot {
        val persisted = readPersistedState()
        highestObservedGeneration =
            maxOf(highestObservedGeneration, persisted.snapshot.identityGeneration)
        if (!persisted.projectionMatches) {
            beginQuarantine(persisted.snapshot.identityGeneration)
        }
        return persisted.snapshot
    }

    @Synchronized internal fun isQuarantined(): Boolean = quarantined

    private fun readPersistedState(): PersistedIdentityState {
        val encoded = runCatching {
            appPreferences.sharedPreferences.getString(IDENTITY_STATE_PREFERENCE_KEY, null)
        }
            .getOrNull()
        val snapshot = IdentityStateCodec.decode(encoded) ?: EMPTY_IDENTITY_SNAPSHOT
        val projectedServer = runCatching {
            appPreferences.sharedPreferences.getString(
                appPreferences.currentServer.backendName,
                null,
            )
        }
            .getOrNull()
        val projectedOfflineMode = runCatching {
            appPreferences.sharedPreferences.getBoolean(
                appPreferences.offlineMode.backendName,
                false,
            )
        }
            .getOrNull()
        return PersistedIdentityState(
            snapshot = snapshot,
            projectionMatches =
                projectedServer == snapshot.selection.serverId &&
                    projectedOfflineMode == snapshot.selection.offlineMode,
        )
    }

    private fun beginQuarantine(generation: Long) {
        quarantineGeneration = generation
        quarantined = true
    }

    private fun unavailableSnapshot(): IdentitySnapshot =
        IdentitySnapshot(
            selection = IdentitySelection(),
            identityGeneration = quarantineGeneration,
            status = IdentityStateStatus.RECOVERING,
        )

    private data class PersistedIdentityState(
        val snapshot: IdentitySnapshot,
        val projectionMatches: Boolean,
    )

    internal companion object {
        val EMPTY_IDENTITY_SNAPSHOT =
            IdentitySnapshot(
                selection = IdentitySelection(),
                identityGeneration = 0L,
                status = IdentityStateStatus.READY,
            )
    }
}

internal object IdentityStateCodec {
    private const val PREFIX = "identity-v1"
    private const val SEPARATOR = ":"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(snapshot: IdentitySnapshot): String {
        val payload =
            IdentityStatePayload(
                serverId = snapshot.selection.serverId,
                userId = snapshot.selection.userId,
                addressId = snapshot.selection.addressId,
                offlineMode = snapshot.selection.offlineMode,
                identityGeneration = snapshot.identityGeneration,
                status = snapshot.status.name,
            )
        val bytes =
            json
                .encodeToString(IdentityStatePayload.serializer(), payload)
                .toByteArray(StandardCharsets.UTF_8)
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes)
        return listOf(
                PREFIX,
                encoder.encodeToString(checksum),
                encoder.encodeToString(bytes),
            )
            .joinToString(SEPARATOR)
    }

    fun decode(value: String?): IdentitySnapshot? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split(SEPARATOR, limit = 3)
            if (parts.size != 3 || parts[0] != PREFIX) return null

            val bytes = decoder.decode(parts[2])
            val actualChecksum = MessageDigest.getInstance("SHA-256").digest(bytes)
            val expectedChecksum = decoder.decode(parts[1])
            if (!MessageDigest.isEqual(actualChecksum, expectedChecksum)) return null

            val payload =
                json.decodeFromString(
                    IdentityStatePayload.serializer(),
                    bytes.toString(StandardCharsets.UTF_8),
                )
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = payload.serverId,
                        userId = payload.userId,
                        addressId = payload.addressId,
                        offlineMode = payload.offlineMode,
                    ),
                identityGeneration = payload.identityGeneration,
                status = IdentityStateStatus.valueOf(payload.status),
            )
        }
            .getOrNull()
    }

    @Serializable
    private data class IdentityStatePayload(
        val serverId: String?,
        val userId: String?,
        val addressId: String?,
        val offlineMode: Boolean,
        val identityGeneration: Long,
        val status: String,
    )
}
