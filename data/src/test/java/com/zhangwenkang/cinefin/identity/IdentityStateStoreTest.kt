package com.zhangwenkang.cinefin.identity

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityStateStoreTest {
    @Test
    fun legacyProjectionWithoutCanonicalStateFailsClosed() {
        val sharedPreferences = IdentityTestSharedPreferences()
        sharedPreferences
            .edit()
            .putString(AppPreferences(sharedPreferences).currentServer.backendName, "legacy-server")
            .putBoolean(AppPreferences(sharedPreferences).offlineMode.backendName, true)
            .commit()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))

        assertEquals(
            IdentitySnapshot(
                selection = IdentitySelection(),
                identityGeneration = 0L,
                status = IdentityStateStatus.RECOVERING,
            ),
            store.read(),
        )
    }

    @Test
    fun generationAndStatusSurviveStoreRecreation() {
        val sharedPreferences = IdentityTestSharedPreferences()
        val expected =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "server-a",
                        userId = "user-a",
                        addressId = "address-a",
                        offlineMode = false,
                    ),
                identityGeneration = 73L,
                status = IdentityStateStatus.READY,
            )

        assertEquals(
            IdentityWriteOutcome.COMMITTED,
            IdentityStateStore(AppPreferences(sharedPreferences)).write(expected),
        )
        assertEquals(
            expected,
            IdentityStateStore(AppPreferences(sharedPreferences)).read(),
        )
    }

    @Test
    fun canonicalAndCompatibilityProjectionShareOneCommittedEditor() {
        val sharedPreferences = IdentityTestSharedPreferences()
        val appPreferences = AppPreferences(sharedPreferences)
        val store = IdentityStateStore(appPreferences)
        sharedPreferences.resetWriteCounts()
        val expected =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "server-b",
                        userId = "user-b",
                        addressId = "address-b",
                        offlineMode = true,
                    ),
                identityGeneration = 11L,
                status = IdentityStateStatus.RECOVERING,
            )

        assertEquals(IdentityWriteOutcome.COMMITTED, store.write(expected))

        assertEquals(1, sharedPreferences.editorCreations)
        assertEquals(1, sharedPreferences.commitCalls)
        assertEquals("server-b", sharedPreferences.getString("pref_current_server", null))
        assertTrue(sharedPreferences.getBoolean("pref_offline_mode", false))
        assertEquals(
            setOf("pref_identity_state_v1", "pref_current_server", "pref_offline_mode"),
            sharedPreferences.lastCommittedKeys,
        )
    }

    @Test
    fun uncertainCommitQuarantinesNewCanonicalAndProjectionBeforeLaterSuccess() {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val oldSnapshot =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "server-old",
                        userId = "user-old",
                        addressId = "address-old",
                        offlineMode = false,
                    ),
                identityGeneration = 12L,
                status = IdentityStateStatus.READY,
            )
        assertEquals(IdentityWriteOutcome.COMMITTED, store.write(oldSnapshot))
        sharedPreferences.failNextCommit = true

        val uncertain =
            store.write(
                IdentitySnapshot(
                    selection =
                        IdentitySelection(
                            serverId = "server-new",
                            userId = "user-new",
                            addressId = "address-new",
                            offlineMode = true,
                        ),
                    identityGeneration = 13L,
                    status = IdentityStateStatus.RECOVERING,
                )
            )

        assertEquals(IdentityWriteOutcome.UNCERTAIN, uncertain)
        assertEquals(
            IdentitySnapshot(
                selection = IdentitySelection(),
                identityGeneration = oldSnapshot.identityGeneration,
                status = IdentityStateStatus.RECOVERING,
            ),
            store.read(),
        )
        assertEquals("server-new", sharedPreferences.getString("pref_current_server", null))
        assertTrue(sharedPreferences.getBoolean("pref_offline_mode", false))

        assertEquals(
            IdentityWriteOutcome.COMMITTED,
            store.write(oldSnapshot.copy(identityGeneration = 13L)),
        )
        assertEquals(oldSnapshot.copy(identityGeneration = 13L), store.read())
    }

    @Test
    fun corruptCanonicalStateFailsClosedWithoutInferringLegacyProjection() {
        val sharedPreferences = IdentityTestSharedPreferences()
        sharedPreferences
            .edit()
            .putString("pref_identity_state_v1", "identity-v1:corrupt")
            .putString("pref_current_server", "legacy-server")
            .putBoolean("pref_offline_mode", true)
            .commit()

        val snapshot = IdentityStateStore(AppPreferences(sharedPreferences)).read()

        assertEquals(IdentitySelection(), snapshot.selection)
        assertEquals(0L, snapshot.identityGeneration)
        assertEquals(IdentityStateStatus.RECOVERING, snapshot.status)
        assertEquals("legacy-server", sharedPreferences.getString("pref_current_server", null))
        assertTrue(sharedPreferences.getBoolean("pref_offline_mode", false))
    }

    @Test
    fun readySnapshotWithCompatibilityMismatchFailsClosed() {
        val sharedPreferences = IdentityTestSharedPreferences()
        val canonical =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "canonical-server",
                        userId = "canonical-user",
                        addressId = "canonical-address",
                        offlineMode = false,
                    ),
                identityGeneration = 21L,
                status = IdentityStateStatus.READY,
            )
        sharedPreferences
            .edit()
            .putString(IDENTITY_STATE_PREFERENCE_KEY, IdentityStateCodec.encode(canonical))
            .putString("pref_current_server", "different-server")
            .putBoolean("pref_offline_mode", false)
            .commit()

        val snapshot = IdentityStateStore(AppPreferences(sharedPreferences)).read()

        assertEquals(IdentitySelection(), snapshot.selection)
        assertEquals(canonical.identityGeneration, snapshot.identityGeneration)
        assertEquals(IdentityStateStatus.RECOVERING, snapshot.status)
    }
}

internal class IdentityTestSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners =
        CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    var failNextCommit = false
    var failCommitOnCall: Int? = null
    var editorCreations = 0
        private set

    var commitCalls = 0
        private set

    var lastCommittedKeys: Set<String> = emptySet()
        private set

    val committedSnapshots = mutableListOf<IdentitySnapshot>()

    fun resetWriteCounts() {
        editorCreations = 0
        commitCalls = 0
        lastCommittedKeys = emptySet()
        failCommitOnCall = null
        committedSnapshots.clear()
    }

    override fun getAll(): MutableMap<String, *> = Collections.unmodifiableMap(values.toMap())

    override fun getString(key: String, defValue: String?): String? =
        values[key] as String? ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor {
        editorCreations += 1
        return Editor()
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) removals += key else updates[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            if (values == null) removals += key else updates[key] = values.toSet()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            commitCalls += 1
            lastCommittedKeys = (updates.keys + removals).toSet()
            applyChanges()
            return when {
                failNextCommit -> {
                    failNextCommit = false
                    false
                }
                failCommitOnCall == commitCalls -> false
                else -> true
            }
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            val changed = mutableSetOf<String>()
            if (clearRequested) {
                changed += values.keys
                values.clear()
            }
            removals.forEach { key ->
                if (values.remove(key) != null || key in changed) changed += key
            }
            updates.forEach { (key, value) ->
                if (values[key] != value) changed += key
                values[key] = value
            }
            (values[IDENTITY_STATE_PREFERENCE_KEY] as? String)
                ?.let(IdentityStateCodec::decode)
                ?.let(committedSnapshots::add)
            changed.forEach { key ->
                listeners.forEach {
                    it.onSharedPreferenceChanged(this@IdentityTestSharedPreferences, key)
                }
            }
        }
    }
}
