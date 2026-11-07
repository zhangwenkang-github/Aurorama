package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val IDENTITY_STATE_PREFERENCE_KEY = "pref_identity_state_v1"

class IdentityStateStore @Inject constructor(private val appPreferences: AppPreferences) {
    @Volatile private var highestObservedGeneration = 0L

    @Synchronized
    fun read(): IdentitySnapshot {
        val encoded = runCatching {
            appPreferences.sharedPreferences.getString(IDENTITY_STATE_PREFERENCE_KEY, null)
        }
            .getOrNull()
        val snapshot = IdentityStateCodec.decode(encoded) ?: EMPTY_IDENTITY_SNAPSHOT
        highestObservedGeneration = maxOf(highestObservedGeneration, snapshot.identityGeneration)
        return snapshot
    }

    @Synchronized
    fun write(snapshot: IdentitySnapshot): Boolean {
        val encoded = runCatching {
            IdentityStateCodec.encode(snapshot)
        }
            .getOrElse {
                return false
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
                false
            }
        if (committed) {
            highestObservedGeneration =
                maxOf(highestObservedGeneration, snapshot.identityGeneration)
        }
        return committed
    }

    @Synchronized
    fun nextSnapshot(
        selection: IdentitySelection,
        status: IdentityStateStatus,
    ): IdentitySnapshot {
        val current = read()
        val previous = maxOf(current.identityGeneration, highestObservedGeneration)
        check(previous < Long.MAX_VALUE) { "Identity generation exhausted" }
        return IdentitySnapshot(
            selection = selection,
            identityGeneration = previous + 1L,
            status = status,
        )
    }

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
