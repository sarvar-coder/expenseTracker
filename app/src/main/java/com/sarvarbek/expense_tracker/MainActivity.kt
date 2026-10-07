package com.sarvarbek.expense_tracker

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sarvarbek.expense_tracker.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nav bar card runs under the 3-button bar instead of a grey scrim.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { AppRoot() }
    }
}
