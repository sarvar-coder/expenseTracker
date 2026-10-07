package com.sarvarbek.expense_tracker

import android.app.Application
import android.content.Context
import com.sarvarbek.expense_tracker.data.AppDatabase
import com.sarvarbek.expense_tracker.data.SettingsStore
import com.sarvarbek.expense_tracker.services.AiParser
import com.sarvarbek.expense_tracker.services.FamilyService
import com.sarvarbek.expense_tracker.services.SyncService
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest

class App : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.sync.start()
    }
}

/** Manual DI: one instance of each shared service for the whole app. */
class AppContainer(context: Context) {
    val database = AppDatabase.open(context)
    val db = database.dao()
    // Sync keys ('sync.*') share this file so an account switch can wipe them.
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val settings = SettingsStore(prefs)

    // Publishable key is meant for clients; RLS guards the data.
    val supabase = createSupabaseClient(
        supabaseUrl = "https://xgxygopxnchdobuooyzw.supabase.co",
        supabaseKey = "sb_publishable_0-lm1ze8Nz93QSuvegf3kA_jkH1EHn0",
    ) {
        // Explicit store: the default one finds its Context via androidx-startup.
        install(Auth) {
            val store = SharedPreferencesSettings(context.getSharedPreferences("supabase", Context.MODE_PRIVATE))
            sessionManager = SettingsSessionManager(store)
            codeVerifierCache = SettingsCodeVerifierCache(store)
        }
        install(Postgrest)
        install(Functions)
    }
    val sync = SyncService(database, supabase, prefs, settings)
    val family = FamilyService(supabase, sync)
    val aiParser = AiParser(supabase)
}
