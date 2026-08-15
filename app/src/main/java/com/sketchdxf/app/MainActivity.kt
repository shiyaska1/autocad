package com.sketchdxf.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.sketchdxf.app.ui.BackupScreen
import com.sketchdxf.app.ui.BlockLibraryScreen
import com.sketchdxf.app.data.PendingUpdateInfo
import com.sketchdxf.app.ui.BootScreen
import com.sketchdxf.app.ui.DxfDetailScreen
import com.sketchdxf.app.ui.DxfHomeScreen
import com.sketchdxf.app.ui.DxfListScreen
import com.sketchdxf.app.ui.ForceUpdateScreen
import com.sketchdxf.app.ui.LicenseScreen
import com.sketchdxf.app.ui.SketchEditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

@Composable
private fun SketchDxfApp() {
    val nav = rememberNavController()
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
                onBackup = { nav.navigate("backup") }
            )
        }
        composable("blocks") {
            BlockLibraryScreen(onBack = { nav.popBackStack() })
        }
        composable("backup") {
            BackupScreen(onBack = { nav.popBackStack() })
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
                }
            )
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
