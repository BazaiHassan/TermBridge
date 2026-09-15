package io.termbridge.feature.terminal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How terminals look and behave, chosen in settings. */
data class TerminalPrefs(
    val fontSizeSp: Float = TerminalPrefsStore.DEFAULT_FONT_SP,
    /** [TerminalPalette.NIGHT] or [TerminalPalette.DAY]. */
    val palette: String = TerminalPalette.NIGHT,
    val keepScreenOn: Boolean = true,
)

/** Persists [TerminalPrefs]. Nothing secret here, so plain DataStore. */
@Singleton
class TerminalPrefsStore @Inject constructor(@ApplicationContext context: Context) {
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("terminal") }

    val prefs: Flow<TerminalPrefs> = store.data.map { p ->
        TerminalPrefs(
            fontSizeSp = p[FONT] ?: DEFAULT_FONT_SP,
            palette = p[PALETTE] ?: TerminalPalette.NIGHT,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
        )
    }

    suspend fun setFontSize(sp: Float) {
        store.edit { it[FONT] = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP) }
    }

    suspend fun setPalette(name: String) {
        store.edit { it[PALETTE] = name }
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = on }
    }

    companion object {
        const val DEFAULT_FONT_SP = 13f
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 28f

        private val FONT = floatPreferencesKey("font_size_sp")
        private val PALETTE = stringPreferencesKey("palette")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
