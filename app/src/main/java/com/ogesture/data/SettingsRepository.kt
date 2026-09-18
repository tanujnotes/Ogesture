package com.ogesture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(appContext: Context) {

    private val store = appContext.dataStore

    // Distinct because store.data re-emits on a write to any key, and collectors here act on
    // every emission — the second key added below would otherwise double that traffic.
    val masterEnabled: Flow<Boolean> =
        store.data.map { it[KEY_MASTER] ?: false }.distinctUntilChanged()

    /** The user's own choice, which always cancels a pending auto-restore. */
    suspend fun setMasterEnabled(enabled: Boolean) {
        store.edit {
            it[KEY_MASTER] = enabled
            it[KEY_AUTO_DISABLED] = false
        }
    }

    suspend fun isMasterEnabled(): Boolean = store.data.first()[KEY_MASTER] ?: false

    /**
     * Turns gestures off because something they need has gone away, remembering that the user
     * did not ask for this — so [restoreIfAutoDisabled] can put it back. Both keys move in one
     * transaction, so the pair is never observed half-updated.
     *
     * Deliberately a no-op once gestures are already off, which is what stops a manual off
     * from being recorded as an automatic one and silently undone later: the watchers gate on
     * a cached flag that lags this store, so one can still fire a tick after the user has hit
     * the switch. Reading the persisted value here, inside the transaction, settles it however
     * the two writes interleave. Returns whether this call is what turned them off, so of
     * several watchers noticing the same loss only one announces it.
     */
    suspend fun disableForMissingRequirement(): Boolean {
        var disabled = false
        store.edit {
            disabled = it[KEY_MASTER] == true
            if (disabled) {
                it[KEY_MASTER] = false
                it[KEY_AUTO_DISABLED] = true
            }
        }
        return disabled
    }

    /**
     * Turns gestures back on iff the app was the one that turned them off. Returns true only
     * for the caller that actually made the change: several watchers can notice the missing
     * permission return at once, and only one of them should announce it.
     */
    suspend fun restoreIfAutoDisabled(): Boolean {
        var restored = false
        store.edit {
            // Assigned, never merely set to true, so the answer stays right for the caller
            // even if this transform is ever evaluated more than once.
            restored = it[KEY_AUTO_DISABLED] == true
            if (restored) {
                it[KEY_MASTER] = true
                it[KEY_AUTO_DISABLED] = false
            }
        }
        return restored
    }

    /** Packages the user turned Ogesture off for. Empty by default: gestures everywhere. */
    val excludedApps: Flow<Set<String>> = store.data.map { it[KEY_EXCLUDED_APPS] ?: emptySet() }

    suspend fun setAppExcluded(packageName: String, excluded: Boolean) {
        store.edit { prefs ->
            val current = prefs[KEY_EXCLUDED_APPS] ?: emptySet()
            prefs[KEY_EXCLUDED_APPS] = if (excluded) current + packageName else current - packageName
        }
    }

    companion object {
        private val KEY_MASTER = booleanPreferencesKey("master_enabled")
        private val KEY_AUTO_DISABLED = booleanPreferencesKey("auto_disabled")
        private val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")

        @Volatile private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
