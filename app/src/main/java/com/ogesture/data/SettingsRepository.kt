package com.ogesture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /**
     * The user-tunable gesture-zone geometry, clamped to valid ranges. The controller
     * observes [gestureZoneSettings] and rebuilds the overlay zones when it changes.
     */
    // Single mapping over one Preferences emission — deriving all four values in one pass instead
    // of four independent mapped flows combined together (less work per DataStore write).
    val gestureZoneSettings: Flow<GestureZoneSettings> = store.data.map { prefs ->
        GestureZoneSettings(
            backActivationHeightPercent = GestureZoneSettings.clampPercent(prefs[KEY_BACK_HEIGHT] ?: GestureZoneSettings.DEFAULT_BACK_HEIGHT_PERCENT),
            bottomActivationWidthPercent = GestureZoneSettings.clampPercent(prefs[KEY_BOTTOM_WIDTH] ?: GestureZoneSettings.DEFAULT_BOTTOM_WIDTH_PERCENT),
            backEdgeSensitivity = GestureZoneSettings.clampSensitivity(prefs[KEY_BACK_SENSITIVITY] ?: GestureZoneSettings.DEFAULT_BACK_SENSITIVITY),
            bottomEdgeSensitivity = GestureZoneSettings.clampSensitivity(prefs[KEY_BOTTOM_SENSITIVITY] ?: GestureZoneSettings.DEFAULT_BOTTOM_SENSITIVITY),
        )
    }.distinctUntilChanged()

    suspend fun setBackActivationHeight(percent: Int) {
        store.edit { it[KEY_BACK_HEIGHT] = GestureZoneSettings.clampPercent(percent) }
    }

    suspend fun setBottomActivationWidth(percent: Int) {
        store.edit { it[KEY_BOTTOM_WIDTH] = GestureZoneSettings.clampPercent(percent) }
    }

    suspend fun setBackEdgeSensitivity(multiplier: Float) {
        store.edit { it[KEY_BACK_SENSITIVITY] = GestureZoneSettings.clampSensitivity(multiplier) }
    }

    suspend fun setBottomEdgeSensitivity(multiplier: Float) {
        store.edit { it[KEY_BOTTOM_SENSITIVITY] = GestureZoneSettings.clampSensitivity(multiplier) }
    }

    val gestureCancellationSettings: Flow<GestureCancellationSettings> = store.data.map { GestureCancellationSettings(it[KEY_CANCEL_BACK] ?: false, it[KEY_CANCEL_HOME] ?: false) }.distinctUntilChanged()
    suspend fun setCancelBack(enabled: Boolean) { store.edit { it[KEY_CANCEL_BACK] = enabled } }
    suspend fun setCancelHome(enabled: Boolean) { store.edit { it[KEY_CANCEL_HOME] = enabled } }

    /**
     * Opt-in Xiaomi/HyperOS system-navigation watchdog. When true and the prerequisites
     * (supported device + WRITE_SECURE_SETTINGS granted + master gestures on + service bound)
     * are met, the [com.ogesture.service.SystemNavigationController] keeps the OEM three-button
     * navigation bar hidden by enforcing `force_fsg_nav_bar=1` and `hide_gesture_line=1`.
     * Defaults to false. Disabling it restores the pre-enforcement system state.
     */
    val hideSystemNavigation: Flow<Boolean> = store.data.map { it[KEY_HIDE_SYS_NAV] ?: false }

    suspend fun setHideSystemNavigation(enabled: Boolean) {
        store.edit { it[KEY_HIDE_SYS_NAV] = enabled }
    }

    /**
     * Crash-recovery snapshot of the two system-navigation settings captured before the watchdog
     * first enforces, so a process death during enforcement can still restore the real original
     * state. Cleared only after a successful restore. `*_present=false` means the key was absent.
     */
    data class NavBaseline(
        val captured: Boolean,
        val forcePresent: Boolean,
        val forceValue: Int,
        val hidePresent: Boolean,
        val hideValue: Int,
    )

    val navBaseline: Flow<NavBaseline> = store.data.map {
        NavBaseline(
            captured = it[KEY_NAV_BASELINE_CAPTURED] ?: false,
            forcePresent = it[KEY_NAV_FORCE_PRESENT] ?: false,
            forceValue = it[KEY_NAV_FORCE_VALUE] ?: 0,
            hidePresent = it[KEY_NAV_HIDE_PRESENT] ?: false,
            hideValue = it[KEY_NAV_HIDE_VALUE] ?: 0,
        )
    }

    suspend fun setNavBaseline(baseline: NavBaseline) {
        store.edit {
            it[KEY_NAV_BASELINE_CAPTURED] = baseline.captured
            it[KEY_NAV_FORCE_PRESENT] = baseline.forcePresent
            it[KEY_NAV_FORCE_VALUE] = baseline.forceValue
            it[KEY_NAV_HIDE_PRESENT] = baseline.hidePresent
            it[KEY_NAV_HIDE_VALUE] = baseline.hideValue
        }
    }

    suspend fun clearNavBaseline() {
        store.edit {
            it.remove(KEY_NAV_BASELINE_CAPTURED)
            it.remove(KEY_NAV_FORCE_PRESENT)
            it.remove(KEY_NAV_FORCE_VALUE)
            it.remove(KEY_NAV_HIDE_PRESENT)
            it.remove(KEY_NAV_HIDE_VALUE)
        }
    }

    companion object {
        private val KEY_MASTER = booleanPreferencesKey("master_enabled")
        private val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")
        private val KEY_BACK_HEIGHT = intPreferencesKey("back_activation_height_percent")
        private val KEY_BOTTOM_WIDTH = intPreferencesKey("bottom_activation_width_percent")
        private val KEY_BACK_SENSITIVITY = floatPreferencesKey("back_edge_sensitivity")
        private val KEY_BOTTOM_SENSITIVITY = floatPreferencesKey("bottom_edge_sensitivity")
        private val KEY_CANCEL_BACK = booleanPreferencesKey("cancel_back")
        private val KEY_CANCEL_HOME = booleanPreferencesKey("cancel_home")
        private val KEY_HIDE_SYS_NAV = booleanPreferencesKey("hide_system_navigation")
        private val KEY_NAV_BASELINE_CAPTURED = booleanPreferencesKey("nav_baseline_captured")
        private val KEY_NAV_FORCE_PRESENT = booleanPreferencesKey("nav_baseline_force_present")
        private val KEY_NAV_FORCE_VALUE = intPreferencesKey("nav_baseline_force_value")
        private val KEY_NAV_HIDE_PRESENT = booleanPreferencesKey("nav_baseline_hide_present")
        private val KEY_NAV_HIDE_VALUE = intPreferencesKey("nav_baseline_hide_value")

        @Volatile private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
