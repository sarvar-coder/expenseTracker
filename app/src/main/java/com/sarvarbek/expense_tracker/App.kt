package com.sarvarbek.expense_tracker

import android.app.Application
import android.content.Context
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.SettingsStore

class App : Application() {
    val container by lazy { AppContainer(this) }
}

/** Manual DI: one instance of each shared service for the whole app. */
class AppContainer(context: Context) {
    val db = AppDatabase.open(context).dao()
    val settings = SettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
}
