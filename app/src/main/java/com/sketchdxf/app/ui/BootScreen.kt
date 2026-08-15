package com.sketchdxf.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sketchdxf.app.BuildConfig
import com.sketchdxf.app.data.AppPrefs
import com.sketchdxf.app.data.License
import com.sketchdxf.app.data.PendingUpdateInfo
import com.sketchdxf.app.data.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Splash that checks for a forced update, then the trial/license, then routes to "force_update",
 *  "license" or "list". The update check runs first — an out-of-date, potentially-broken build
 *  shouldn't get as far as showing its own (possibly stale) licensing UI. */
@Composable
fun BootScreen(onResolved: (route: String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val update = withContext(Dispatchers.IO) { UpdateChecker.fetch() }
        if (update != null && BuildConfig.VERSION_CODE < update.minVersionCode) {
            PendingUpdateInfo.set(update.message, update.updateUrl)
            onResolved("force_update")
            return@LaunchedEffect
        }

        val prefs = AppPrefs(context)
        if (prefs.installDateMillis <= 0L) prefs.installDateMillis = System.currentTimeMillis()
        val route = if (License.dueMilestone(prefs.installDateMillis) > prefs.licensedMilestone) "license" else "list"
        onResolved(route)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
