package com.sarvarbek.expense_tracker.ui

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.core.content.edit
import com.sarvarbek.expense_tracker.services.Push
import com.sarvarbek.expense_tracker.services.shouldAskNotifications
import com.sarvarbek.expense_tracker.ui.common.ConfirmDialog
import com.sarvarbek.expense_tracker.ui.common.t
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.sarvarbek.expense_tracker.data.Category
import com.sarvarbek.expense_tracker.data.Expense
import com.sarvarbek.expense_tracker.services.FamilyExpense
import com.sarvarbek.expense_tracker.ui.common.ExpenseDetailSheet
import kotlinx.coroutines.flow.first
import com.sarvarbek.expense_tracker.features.add.AddScreen
import com.sarvarbek.expense_tracker.features.insights.CategoryExpensesScreen
import com.sarvarbek.expense_tracker.features.insights.CategoryPick
import com.sarvarbek.expense_tracker.features.insights.InsightsScreen
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import com.sarvarbek.expense_tracker.features.family.FamilyScreen
import com.sarvarbek.expense_tracker.features.family.FamilySettingsScreen
import com.sarvarbek.expense_tracker.features.family.FamilyState
import com.sarvarbek.expense_tracker.features.family.NeedsScreen
import com.sarvarbek.expense_tracker.services.SyncService
import com.sarvarbek.expense_tracker.features.settings.CategoriesScreen
import com.sarvarbek.expense_tracker.features.settings.SettingsScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sarvarbek.expense_tracker.ui.theme.AppMotion
import com.sarvarbek.expense_tracker.ui.theme.AppTheme

@Composable
fun AppRoot(pushRoute: String? = null, onPushRouted: () -> Unit = {}) {
    val auth = (LocalContext.current.applicationContext as App).container.supabase.auth
    val status by auth.sessionStatus.collectAsStateWithLifecycle()
    AppTheme { AuthGate(status, auth) { Routes(pushRoute, onPushRouted) } }
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

/** Routes: shell (tabs) plus full-screen pushes (add, edit, settings, categories, one category's expenses). */
@Composable
private fun Routes(pushRoute: String?, onPushRouted: () -> Unit) {
    val container = (LocalContext.current.applicationContext as App).container
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val toaster = Toaster(remember { SnackbarHostState() }, scope)
    val canCreate = { canCreateCategories(container.prefs) }
    // Above the tab so the last overview survives tab switches.
    val family = remember { FamilyState(container.family) }
    // Activity-scoped ViewModel: survives rotation / theme switch. Holds a lambda, so not process death: then the route pops.
    val picked = viewModel<Picked>()
    val openCategory = { p: CategoryPick -> picked.pick = p; nav.go("category") }
    val edit = { e: Expense -> nav.go("edit/${e.id}") }
    @Composable
    fun Add(editing: Expense?) {
        // O'tkazma recipients come from the Oila overview; fetch it if the tab wasn't opened yet.
        LaunchedEffect(Unit) {
            if (family.overview == null && !container.prefs.getString(SyncService.K_FAMILY, null).isNullOrEmpty()) family.refresh()
        }
        val members = family.overview?.let { f -> f.members.filter { it.userId != f.myId }.map { it.userId to it.label } }.orEmpty()
        AddScreen(
            container.db, container.settings, container.aiParser::parse, container.speech, canCreate,
            onClose = { nav.back() }, editing = editing, members = members,
        )
    }
    // Notification taps: a new member expense opens read-only over any screen.
    var pushed by remember { mutableStateOf<Pair<FamilyExpense, Category?>?>(null) }
    var tab by remember { mutableStateOf<Int?>(null) }
    pushed?.let { (fe, category) -> ExpenseDetailSheet(fe, category) { pushed = null } }
    LaunchedEffect(pushRoute) {
        // The requests screen pops itself without an overview, so load it first.
        if (pushRoute == Push.ROUTE_REQUESTS) {
            family.refresh()
            if (family.overview != null && nav.currentDestination?.route != pushRoute) nav.navigate(pushRoute)
        } else if (pushRoute?.startsWith(Push.ROUTE_EXPENSE) == true) {
            val id = pushRoute.removePrefix(Push.ROUTE_EXPENSE)
            suspend fun find() = family.feed.first().firstOrNull { it.expense.id == id }
            // Others' rows arrive with a sync pass; the pushed one is usually newer than the last.
            val fe = find() ?: run { container.sync.run(); find() }
            if (fe != null) {
                pushed = fe to container.db.getAllCategories().firstOrNull { it.id == fe.expense.categoryId }
            } else {
                // Offline or since deleted: the Oila tab instead.
                nav.popBackStack("shell", inclusive = false)
                tab = 2
            }
        }
        if (pushRoute != null) onPushRouted()
    }
    CompositionLocalProvider(LocalToaster provides toaster) {
        NavHost(
            nav, startDestination = "shell",
            enterTransition = { AppMotion.pushEnter },
            exitTransition = { AppMotion.pushExit },
            popEnterTransition = { AppMotion.popEnter },
            popExitTransition = { AppMotion.popExit },
            // Back gesture/button (predictive back) uses these; default is a shrink-to-center.
            predictivePopEnterTransition = { AppMotion.popEnter },
            predictivePopExitTransition = { AppMotion.popExit },
        ) {
            composable("shell") {
                Shell(
                    onAdd = { nav.go("add") },
                    tab = tab, onTab = { tab = null },
                    snackbarHost = { SnackbarHost(toaster.host) { AppSnackbar(it) } },
                ) { index, active ->
                    when (index) {
                        0 -> {
                            val stale by container.family.othersStale.collectAsStateWithLifecycle()
                            HomeScreen(
                                container.db, container.settings, family.feed, offline = stale,
                                inFamily = !container.prefs.getString(SyncService.K_FAMILY, null).isNullOrEmpty(),
                                onSettings = { nav.go("settings") }, onEdit = edit,
                            )
                        }
                        1 -> InsightsScreen(
                            container.db, family.feed, container.settings.insightsList, { container.settings.insightsList = it },
                            onCategory = openCategory, onEdit = edit,
                        )
                        else -> {
                            FamilyScreen(family, container.db, active, onNeeds = { nav.go("needs") }, onEdit = edit, onCategory = openCategory) { nav.go("family-settings") }
                            // In a family is when pushes start to matter (requests, later new expenses).
                            if (active && family.overview != null) AskNotifications(container.prefs)
                        }
                    }
                }
            }
            composable("add") { Add(null) }
            composable("edit/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val editing by produceState<Expense?>(null, id) { value = container.db.getExpense(id) }
                editing?.let { key(it.id) { Add(it) } }
            }
            composable("settings") {
                SettingsScreen(
                    container.settings, container.db,
                    email = container.supabase.auth.currentUserOrNull()?.email.orEmpty(),
                    onBack = { nav.back() },
                    onCategories = { nav.go("categories") },
                ) {
                    // Routes' scope: Settings' own is cancelled by the pop below.
                    scope.launch {
                        // Flush unsynced edits: the next account to sign in here wipes local rows.
                        withTimeoutOrNull(5_000) { container.sync.run() }
                        // No pushes for this account on this phone after sign-out.
                        withTimeoutOrNull(5_000) { container.push.unregister() }
                        nav.popBackStack("shell", inclusive = false)
                        container.supabase.auth.signOut()
                    }
                }
            }
            // Also popped when the family vanishes (maybe while backgrounded): unguarded, route-scoped.
            composable("family-settings") { FamilySettingsScreen(family) { nav.popBackStack("family-settings", inclusive = true) } }
            composable("needs") {
                val familyId = container.prefs.getString(SyncService.K_FAMILY, null)?.ifEmpty { null }
                NeedsScreen(container.db, familyId, container.sync::run) { nav.back() }
            }
            composable("category") {
                val p = picked.pick
                if (p == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    val stale by container.family.othersStale.collectAsStateWithLifecycle()
                    CategoryExpensesScreen(container.db, family.feed, p, offline = stale, onBack = { nav.back() }, onEdit = edit)
                }
            }
            composable("categories") { CategoriesScreen(container.db, canCreate()) { nav.back() } }
        }
    }
}

/** Android 13+: explain, then ask for POST_NOTIFICATIONS once ever (either answer counts). */
@Composable
private fun AskNotifications(prefs: SharedPreferences) {
    val context = LocalContext.current
    var show by remember {
        // The runtime permission only: if just the app switch is off, the system dialog can't help.
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        mutableStateOf(shouldAskNotifications(Build.VERSION.SDK_INT, granted, prefs.getBoolean(K_PUSH_ASKED, false)))
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    if (!show) return
    val done = { show = false; prefs.edit { putBoolean(K_PUSH_ASKED, true) } }
    ConfirmDialog(t("push.ask_title"), t("push.ask_text"), t("push.ask_allow"), onDismiss = done) {
        done()
        if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val K_PUSH_ASKED = "push.asked"

/** The category the category route shows ([CategoryPick] holds the source screen's selector). */
class Picked : ViewModel() {
    var pick by mutableStateOf<CategoryPick?>(null)
}

// Only the settled (RESUMED) page may navigate: taps during a transition or a
// second back press are dropped, so nothing pushes twice and shell never pops.
private val NavController.settled get() = currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

internal fun NavController.go(route: String) {
    if (settled) navigate(route)
}

internal fun NavController.back() {
    if (settled && previousBackStackEntry != null) popBackStack()
}
