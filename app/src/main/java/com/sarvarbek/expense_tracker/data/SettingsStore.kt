package com.sarvarbek.expense_tracker.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Settings(
    val monthlyBudget: Long = 0,
    val sttLocale: String = "uz_UZ",
    /** New expenses start private (hidden from the family) when set. */
    val defaultPrivate: Boolean = false,
)

/** Plain settings in SharedPreferences; [settings] updates screens live. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()

    fun load() = Settings(
        monthlyBudget = prefs.getLong(K_BUDGET, 0),
        sttLocale = prefs.getString(K_LOCALE, null) ?: "uz_UZ",
        defaultPrivate = prefs.getBoolean(K_PRIVATE, false),
    )

    // Budget and private default also live on the server profile (the family
    // contribution needs the budget), so edits wait there for the next sync.
    fun setBudget(v: Long) {
        prefs.edit { putLong(K_BUDGET, v); putBoolean(K_PROFILE_DIRTY, true) }
        state.update { it.copy(monthlyBudget = v) }
    }

    fun setDefaultPrivate(v: Boolean) {
        prefs.edit { putBoolean(K_PRIVATE, v); putBoolean(K_PROFILE_DIRTY, true) }
        state.update { it.copy(defaultPrivate = v) }
    }

    fun setLocale(v: String) {
        prefs.edit { putString(K_LOCALE, v) }
        state.update { it.copy(sttLocale = v) }
    }

    val profileDirty get() = prefs.getBoolean(K_PROFILE_DIRTY, false)
    fun markProfileClean() = prefs.edit { remove(K_PROFILE_DIRTY) }

    /** Server values pulled by sync; not marked for push. */
    fun applyProfile(budget: Long, defaultPrivate: Boolean) {
        prefs.edit { putLong(K_BUDGET, budget); putBoolean(K_PRIVATE, defaultPrivate) }
        state.update { it.copy(monthlyBudget = budget, defaultPrivate = defaultPrivate) }
    }

    /** Last Add mode the user picked (enum name), so Add reopens in it. */
    var lastAddMode: String?
        get() = prefs.getString(K_ADD_MODE, null)
        set(v) = prefs.edit { putString(K_ADD_MODE, v) }

    companion object {
        private const val K_BUDGET = "monthlyBudget"
        private const val K_LOCALE = "sttLocale"
        private const val K_ADD_MODE = "lastAddMode"
        private const val K_PRIVATE = "defaultPrivate"
        // 'sync.' prefix: wiped with the other sync keys when the account changes.
        private const val K_PROFILE_DIRTY = "sync.profileDirty"
    }
}
