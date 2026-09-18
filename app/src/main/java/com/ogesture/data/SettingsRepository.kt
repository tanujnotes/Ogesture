package com.ogesture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(appContext: Context) {

    private val store = appContext.dataStore

    val masterEnabled: Flow<Boolean> = store.data.map { it[KEY_MASTER] ?: false }

    suspend fun setMasterEnabled(enabled: Boolean) {
        store.edit { it[KEY_MASTER] = enabled }
    }

    suspend fun isMasterEnabled(): Boolean = store.data.first()[KEY_MASTER] ?: false

    /** Packages the user turned Ogesture off for. Empty by default: gestures everywhere. */
    val excludedApps: Flow<Set<String>> = store.data.map { it[KEY_EXCLUDED_APPS] ?: emptySet() }

    suspend fun setAppExcluded(packageName: String, excluded: Boolean) {
        store.edit { prefs ->
            val current = prefs[KEY_EXCLUDED_APPS] ?: emptySet()
            prefs[KEY_EXCLUDED_APPS] = if (excluded) current + packageName else current - packageName
        }
    }

    fun getZoneConfigs(): Flow<List<ZoneConfig>> = store.data.map { prefs ->
        DEFAULT_GESTURE_ZONES.map { defaultZone ->
            val actionKey = stringPreferencesKey("zone_${defaultZone.id.name}_action")
            val longActionKey = stringPreferencesKey("zone_${defaultZone.id.name}_long_action")
            val action = prefs[actionKey]?.let { GestureAction.valueOf(it) } ?: defaultZone.action
            val longAction = prefs[longActionKey]?.let { GestureAction.valueOf(it) } ?: defaultZone.longAction
            defaultZone.copy(action = action, longAction = longAction)
        }
    }

    suspend fun setZoneAction(zoneId: ZoneId, action: GestureAction) {
        store.edit { prefs ->
            prefs[stringPreferencesKey("zone_${zoneId.name}_action")] = action.name
        }
    }

    suspend fun setZoneLongAction(zoneId: ZoneId, action: GestureAction?) {
        store.edit { prefs ->
            if (action != null) {
                prefs[stringPreferencesKey("zone_${zoneId.name}_long_action")] = action.name
            } else {
                prefs.remove(stringPreferencesKey("zone_${zoneId.name}_long_action"))
            }
        }
    }

    companion object {
        private val KEY_MASTER = booleanPreferencesKey("master_enabled")
        private val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")

        private val DEFAULT_GESTURE_ZONES = listOf(
            ZoneConfig(ZoneId.BOTTOM, GestureAction.HOME, longAction = GestureAction.RECENTS, lengthPercent = 80, thicknessDp = 12),
            ZoneConfig(ZoneId.LEFT_EDGE, GestureAction.BACK, longAction = GestureAction.NONE, lengthPercent = 80, thicknessDp = 16),
            ZoneConfig(ZoneId.RIGHT_EDGE, GestureAction.NONE, longAction = GestureAction.NONE, lengthPercent = 80, thicknessDp = 16),
        )

        @Volatile private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
