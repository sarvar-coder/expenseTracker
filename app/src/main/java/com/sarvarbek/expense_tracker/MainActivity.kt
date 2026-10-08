package com.sarvarbek.expense_tracker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.sarvarbek.expense_tracker.services.Push
import com.sarvarbek.expense_tracker.ui.AppRoot

class MainActivity : ComponentActivity() {
    /** Screen a tapped notification asks for; cleared once opened. */
    private val pushRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nav bar card runs under the 3-button bar instead of a grey scrim.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        // Not on recreation (rotation): the tap was already handled.
        if (savedInstanceState == null) pushRoute.value = Push.route(intent)
        setContent { AppRoot(pushRoute.value) { pushRoute.value = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // a later recreation must not replay the old route
        Push.route(intent)?.let { pushRoute.value = it }
    }
}
