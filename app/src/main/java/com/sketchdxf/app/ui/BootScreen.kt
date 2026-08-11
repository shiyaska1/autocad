package com.sketchdxf.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sketchdxf.app.data.AppPrefs
import com.sketchdxf.app.data.License

/** Splash that checks the trial/license, then routes to "license" or "list". */
@Composable
fun BootScreen(onResolved: (route: String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = AppPrefs(context)
        if (prefs.installDateMillis <= 0L) prefs.installDateMillis = System.currentTimeMillis()
        val route = if (License.dueMilestone(prefs.installDateMillis) > prefs.licensedMilestone) "license" else "list"
        onResolved(route)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
