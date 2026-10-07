package com.sarvarbek.expense_tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sarvarbek.expense_tracker.App
import com.sarvarbek.expense_tracker.features.auth.AuthScreen
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sarvarbek.expense_tracker.ui.theme.AppTheme

@Composable
fun AppRoot() {
    val auth = (LocalContext.current.applicationContext as App).container.supabase.auth
    val status by auth.sessionStatus.collectAsStateWithLifecycle()
    AppTheme { AuthGate(status, auth) { Routes() } }
}

/**
 * The whole app sits behind a Supabase session. A failed token refresh
 * (offline) keeps the app open: data is local, sync retries later.
 */
@Composable
fun AuthGate(status: SessionStatus, auth: Auth, content: @Composable () -> Unit) = when (status) {
    is SessionStatus.Authenticated, is SessionStatus.RefreshFailure -> content()
    is SessionStatus.NotAuthenticated -> AuthScreen(auth)
    SessionStatus.Initializing -> Box(Modifier.fillMaxSize().background(AppTheme.colors.bg))
}

/** Routes: shell (tabs) plus full-screen pushes (add, settings). */
@Composable
private fun Routes() {
    run {
        val nav = rememberNavController()
        NavHost(nav, startDestination = "shell") {
            composable("shell") {
                Shell(onAdd = { nav.navigate("add") })
            }
            // ponytail: placeholder until the Add step lands.
            composable("add") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Xarajat qo'shish") }
            }
        }
    }
}
