package com.sarvarbek.expense_tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.features.add.AddScreen
import com.sarvarbek.expense_tracker.features.home.HomeScreen
import com.sarvarbek.expense_tracker.services.canCreateCategories
import com.sarvarbek.expense_tracker.ui.common.LocalToaster
import com.sarvarbek.expense_tracker.ui.common.Toaster
import com.sarvarbek.expense_tracker.ui.theme.AppSnackbar
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

/** Routes: shell (tabs) plus full-screen pushes (add, edit, settings). */
@Composable
private fun Routes() {
    val container = (LocalContext.current.applicationContext as App).container
    val nav = rememberNavController()
    val toaster = Toaster(remember { SnackbarHostState() }, rememberCoroutineScope())
    val canCreate = { canCreateCategories(container.prefs) }
    @Composable
    fun Add(editing: Expense?) = AddScreen(
        container.db, container.settings, container.aiParser::parse, container.speech, canCreate,
        onClose = { nav.popBackStack() }, editing = editing,
    )
    CompositionLocalProvider(LocalToaster provides toaster) {
        NavHost(nav, startDestination = "shell") {
            composable("shell") {
                Shell(
                    onAdd = { nav.navigate("add") },
                    snackbarHost = { SnackbarHost(toaster.host) { AppSnackbar(it) } },
                ) { index, _ ->
                    when (index) {
                        0 -> HomeScreen(container.db, container.settings, onSettings = { nav.navigate("settings") }) { nav.navigate("edit/${it.id}") }
                        else -> Placeholder()
                    }
                }
            }
            composable("add") { Add(null) }
            composable("edit/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val editing by produceState<Expense?>(null, id) { value = container.db.getExpense(id) }
                editing?.let { key(it.id) { Add(it) } }
            }
            // ponytail: placeholder until the Settings step lands.
            composable("settings") { Placeholder() }
        }
    }
}
