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

/** Splash that routes to "license" or "list" — purely local/instant (installDateMillis +
 *  licensedMilestone from SharedPreferences), so first paint is never held up by a network call.
 *  The forced-update check runs separately, in the background, from SketchDxfApp — see its own
 *  doc comment for why it isn't part of this boot sequence any more. */
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
