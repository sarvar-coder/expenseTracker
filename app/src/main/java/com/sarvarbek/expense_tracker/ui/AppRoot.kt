package com.sarvarbek.expense_tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sarvarbek.expense_tracker.ui.theme.AppTheme

/** Routes: shell (tabs) plus full-screen pushes (add, settings). */
@Composable
fun AppRoot() {
    AppTheme {
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
