package com.sarvarbek.expense_tracker.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Settings(
    val sttLocale: String = "uz_UZ",
    /** Name the family sees (profiles.display_name); empty until the first sync. */
    val displayName: String = "",
    /** UI script: "uz" (Latin) or "uz_cyrl" (Cyrillic). Local only. */
    val uiLanguage: String = "uz",
)

/** Plain settings in SharedPreferences; [settings] updates screens live. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()

    fun load() = Settings(
        sttLocale = prefs.getString(K_LOCALE, null) ?: "uz_UZ",
        displayName = prefs.getString(K_NAME, null) ?: "",
        uiLanguage = prefs.getString(K_UI_LANG, null) ?: "uz",
    )

    // Name also lives on the server profile, so edits wait there for the next sync.
    fun setDisplayName(v: String) {
        prefs.edit { putString(K_NAME, v); putBoolean(K_PROFILE_DIRTY, true) }
        state.update { it.copy(displayName = v) }
    }

    fun setLocale(v: String) {
        prefs.edit { putString(K_LOCALE, v) }
        state.update { it.copy(sttLocale = v) }
    }

    fun setUiLanguage(v: String) {
        prefs.edit { putString(K_UI_LANG, v) }
        state.update { it.copy(uiLanguage = v) }
    }

    val profileDirty get() = prefs.getBoolean(K_PROFILE_DIRTY, false)
    fun markProfileClean() = prefs.edit { remove(K_PROFILE_DIRTY) }

    /** Server value pulled by sync; not marked for push. */
    fun applyProfile(displayName: String) {
        prefs.edit { putString(K_NAME, displayName) }
        state.update { it.copy(displayName = displayName) }
    }

    /** Last Add mode the user picked (enum name), so Add reopens in it. */
    var lastAddMode: String?
        get() = prefs.getString(K_ADD_MODE, null)
        set(v) = prefs.edit { putString(K_ADD_MODE, v) }

    /** Tahlil's last mode: list (default) or categories. */
    var insightsList: Boolean
        get() = prefs.getBoolean(K_INSIGHTS_LIST, true)
        set(v) = prefs.edit { putBoolean(K_INSIGHTS_LIST, v) }

    /** The system notification-permission dialog has answered once (closing our explanation doesn't count). */
    var pushAnswered: Boolean
        get() = prefs.getBoolean(K_PUSH_ANSWERED, false)
        set(v) = prefs.edit { putBoolean(K_PUSH_ANSWERED, v) }

    companion object {
        // New key (old one was "push.asked", set even by a dismissed explanation): everyone is asked once more.
        private const val K_PUSH_ANSWERED = "push.answered"
        private const val K_LOCALE = "sttLocale"
        private const val K_ADD_MODE = "lastAddMode"
        private const val K_INSIGHTS_LIST = "insightsList"
        private const val K_NAME = "displayName"
        private const val K_UI_LANG = "uiLanguage"
        // 'sync.' prefix: wiped with the other sync keys when the account changes.
        private const val K_PROFILE_DIRTY = "sync.profileDirty"
    }
}
