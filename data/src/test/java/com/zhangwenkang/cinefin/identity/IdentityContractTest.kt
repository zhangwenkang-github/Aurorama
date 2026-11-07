package com.zhangwenkang.cinefin.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityContractTest {
    @Test
    fun canonicalCodecRoundTripsEmptyOfflineAndRecoveringSnapshots() {
        val snapshots =
            listOf(
                IdentitySnapshot(
                    selection = IdentitySelection(),
                    identityGeneration = 0L,
                    status = IdentityStateStatus.READY,
                ),
                IdentitySnapshot(
                    selection =
                        IdentitySelection(
                            serverId = "服务器-α",
                            userId = "user-一",
                            addressId = "address-β",
                            offlineMode = true,
                        ),
                    identityGeneration = 41L,
                    status = IdentityStateStatus.RECOVERING,
                ),
            )

        snapshots.forEach { snapshot ->
            val encoded = IdentityStateCodec.encode(snapshot)

            assertTrue(encoded.startsWith("identity-v1:"))
            assertEquals(snapshot, IdentityStateCodec.decode(encoded))
        }
    }

    @Test
    fun canonicalCodecRejectsMissingUnknownCorruptAndInconsistentState() {
        assertNull(IdentityStateCodec.decode(null))
        assertNull(IdentityStateCodec.decode(""))
        assertNull(IdentityStateCodec.decode("identity-v2:not-a-valid-payload"))

        val encoded =
            IdentityStateCodec.encode(
                IdentitySnapshot(
                    selection = IdentitySelection(serverId = "server-a", offlineMode = true),
                    identityGeneration = 7L,
                    status = IdentityStateStatus.READY,
                )
            )
        val replacement = if (encoded.last() == 'A') 'B' else 'A'
        val corrupt = encoded.dropLast(1) + replacement

        assertNull(IdentityStateCodec.decode(corrupt))
        assertThrows(IllegalArgumentException::class.java) {
            IdentitySelection(
                serverId = null,
                userId = "user-without-server",
                addressId = "address-without-server",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdentitySnapshot(
                selection = IdentitySelection(),
                identityGeneration = -1L,
                status = IdentityStateStatus.READY,
            )
        }
    }

    @Test
    fun publicIdentityTypesDoNotExposeCredentialsOrInfrastructure() {
        val forbiddenFieldTypeTokens =
            listOf("Token", "Secret", "SharedPreferences", "Jellyfin", "Dao", "Database")

        (IdentitySelection::class.java.declaredFields + IdentitySnapshot::class.java.declaredFields)
            .filterNot { it.isSynthetic }
            .forEach { field ->
                forbiddenFieldTypeTokens.forEach { token ->
                    assertTrue(
                        "${field.name} must not expose $token",
                        !field.type.name.contains(token, ignoreCase = true),
                    )
                }
                assertTrue(
                    "${field.name} must not persist credentials",
                    !field.name.contains("token", ignoreCase = true) &&
                        !field.name.contains("secret", ignoreCase = true),
                )
            }
    }
}
