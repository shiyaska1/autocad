package com.sketchdxf.app

import android.os.Bundle
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
import com.sketchdxf.app.ui.BlockLibraryScreen
import com.sketchdxf.app.ui.BootScreen
import com.sketchdxf.app.ui.DxfDetailScreen
import com.sketchdxf.app.ui.DxfHomeScreen
import com.sketchdxf.app.ui.DxfListScreen
import com.sketchdxf.app.ui.LicenseScreen
import com.sketchdxf.app.ui.SketchEditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        composable("list") {
            DxfListScreen(
                onBack = { },
                onNew = { nav.navigate("new") },
                onOpen = { id -> nav.navigate("detail/$id") },
                onBlocks = { nav.navigate("blocks") }
            )
        }
        composable("blocks") {
            BlockLibraryScreen(onBack = { nav.popBackStack() })
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
