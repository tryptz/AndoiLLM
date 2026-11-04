package com.tryptz.neuron.terminal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalStore: DataStore<Preferences> by preferencesDataStore("neuron_terminal")

/** Terminal settings that Termux forgets (pinch-zoom font size) or hides in a properties file. */
@Singleton
class TerminalPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val FONT_SP = intPreferencesKey("font_sp")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val VIBRATE_ON_BELL = booleanPreferencesKey("vibrate_on_bell")
    }

    val fontSp: Flow<Int> = context.terminalStore.data.map { it[Keys.FONT_SP] ?: DEFAULT_FONT_SP }
    val keepAwake: Flow<Boolean> = context.terminalStore.data.map { it[Keys.KEEP_AWAKE] ?: false }
    val vibrateOnBell: Flow<Boolean> = context.terminalStore.data.map { it[Keys.VIBRATE_ON_BELL] ?: false }

    suspend fun setFontSp(sp: Int) = context.terminalStore.edit { it[Keys.FONT_SP] = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP) }
    suspend fun setKeepAwake(on: Boolean) = context.terminalStore.edit { it[Keys.KEEP_AWAKE] = on }
    suspend fun setVibrateOnBell(on: Boolean) = context.terminalStore.edit { it[Keys.VIBRATE_ON_BELL] = on }

    companion object {
        const val DEFAULT_FONT_SP = 13
        const val MIN_FONT_SP = 6
        const val MAX_FONT_SP = 40
    }
}
