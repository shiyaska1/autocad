package com.sketchdxf.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.sketchdxf.app.ui.BackupScreen
import com.sketchdxf.app.ui.BlockLibraryScreen
import com.sketchdxf.app.BuildConfig
import com.sketchdxf.app.data.PendingUpdateInfo
import com.sketchdxf.app.data.UpdateChecker
import com.sketchdxf.app.ui.BootScreen
import com.sketchdxf.app.ui.DxfDetailScreen
import com.sketchdxf.app.ui.DxfHomeScreen
import com.sketchdxf.app.ui.DxfListScreen
import com.sketchdxf.app.ui.ForceUpdateScreen
import com.sketchdxf.app.ui.LicenseScreen
import com.sketchdxf.app.ui.PdfMergeScreen
import com.sketchdxf.app.ui.PdfSplitScreen
import com.sketchdxf.app.ui.PdfToolsScreen
import com.sketchdxf.app.ar.ArMeasureScreen
import com.sketchdxf.app.ui.SketchEditorScreen
import com.sketchdxf.app.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Offer the Play update straight away, so users are not left on an old build. A no-op
        // until this app is actually published on Play (see AppUpdater) — safe to leave on now.
        AppUpdater.check(this)
        // Sketching/tracing has long stretches with no touch input while you look at the plan —
        // keep the screen from locking for as long as the app is open (normal lock/sleep behaviour
        // resumes the moment you leave it).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SketchDxfApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time the app comes back to the foreground — this is what makes an
        // update actually mandatory: backing out of the Play update screen just returns here
        // and immediately re-blocks, instead of leaving the user on the old build.
        AppUpdater.check(this)
    }
}

@Composable
private fun SketchDxfApp() {
    val nav = rememberNavController()

    // Runs once, in the background, independent of BootScreen's own (purely local/instant)
    // routing — this used to be an awaited network call at the front of every single boot,
    // which meant every app open paid a real network round-trip (up to UpdateChecker's own
    // timeout) before showing anything at all. Now the app opens immediately regardless, and
    // this only ever interrupts (jumping to "force_update" from wherever the user currently is)
    // in the rare case a forced update is actually found — the common case (nothing forced, or
    // a slow/offline network) no longer costs anything at startup.
    LaunchedEffect(Unit) {
        val update = withContext(Dispatchers.IO) { UpdateChecker.fetch() }
        if (update != null && BuildConfig.VERSION_CODE < update.minVersionCode) {
            PendingUpdateInfo.set(update.message, update.updateUrl)
            nav.navigate("force_update") { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = nav, startDestination = "boot") {
        composable("boot") {
            BootScreen(onResolved = { route ->
                nav.navigate(route) { popUpTo(0) { inclusive = true } }
            })
        }
        composable("license") {
            LicenseScreen(onActivated = {
                nav.navigate("list") { popUpTo(0) { inclusive = true } }
            })
        }
        composable("force_update") {
            ForceUpdateScreen(message = PendingUpdateInfo.message, updateUrl = PendingUpdateInfo.updateUrl)
        }
        composable("list") {
            DxfListScreen(
                onBack = { },
                onNew = { nav.navigate("new") },
                onOpen = { id -> nav.navigate("detail/$id") },
                onBlocks = { nav.navigate("blocks") },
                onBackup = { nav.navigate("backup") },
                onPdfTools = { nav.navigate("pdf_tools") }
            )
        }
        composable("blocks") {
            BlockLibraryScreen(onBack = { nav.popBackStack() })
        }
        composable("backup") {
            BackupScreen(onBack = { nav.popBackStack() })
        }
        composable("pdf_tools") {
            PdfToolsScreen(
                onBack = { nav.popBackStack() },
                onSplit = { nav.navigate("pdf_split") },
                onMerge = { nav.navigate("pdf_merge") }
            )
        }
        composable("pdf_split") {
            PdfSplitScreen(onBack = { nav.popBackStack() })
        }
        composable("pdf_merge") {
            PdfMergeScreen(onBack = { nav.popBackStack() })
        }
        composable("new") {
            DxfHomeScreen(
                onBack = { nav.popBackStack() },
                onGoToEditor = { nav.navigate("editor") },
                onBlankCanvas = {
                    com.sketchdxf.app.dxf.PendingSketchEditor.set(
                        workId = 0, createdAt = 0, name = "", baseImagePath = null,
                        shapes = emptyList(), sources = emptyList()
                    )
                    nav.navigate("editor")
                }
            )
        }
        composable("editor") {
            SketchEditorScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id ->
                    nav.navigate("detail/$id") { popUpTo("list") { inclusive = false } }
                },
                onArMeasure = { nav.navigate("ar_measure") }
            )
        }
        composable("ar_measure") {
            ArMeasureScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            DxfDetailScreen(
                workId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("editor") }
            )
        }
    }
}
