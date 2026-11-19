@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clipforge.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.ui.screens.ClipPreviewScreen
import com.clipforge.app.ui.screens.DashboardScreen
import com.clipforge.app.ui.screens.ExportsScreen
import com.clipforge.app.ui.screens.GalleryScreen
import com.clipforge.app.ui.screens.NewProjectScreen
import com.clipforge.app.ui.screens.OwnershipScreen
import com.clipforge.app.ui.screens.ProjectsScreen
import com.clipforge.app.ui.screens.QueueScreen
import com.clipforge.app.ui.screens.SettingsScreen

sealed class Screen(val title: String) {
    data object Dashboard : Screen("Dashboard")
    data object NewProject : Screen("New project")
    data object Queue : Screen("Processing queue")
    data object Projects : Screen("Clip gallery")
    data class Gallery(val jobId: String) : Screen("Clips")
    data class Preview(val clipId: String) : Screen("Clip preview")
    data object Exports : Screen("Export center")
    data object Settings : Screen("Settings")
    data object Ownership : Screen("Ownership")
}

/** Navigation handle passed to screens. */
class Nav(private val stack: MutableList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(s: Screen) { stack.add(s) }

    /** Switches top-level tab: clears the stack back to the root. */
    fun tab(s: Screen) {
        stack.clear()
        stack.add(Screen.Dashboard)
        if (s != Screen.Dashboard) stack.add(s)
    }

    fun replace(s: Screen) { stack[stack.size - 1] = s }

    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Screen.Dashboard, "Home", Icons.Filled.Home),
    Tab(Screen.NewProject, "New", Icons.Filled.Add),
    Tab(Screen.Queue, "Queue", Icons.AutoMirrored.Filled.List),
    Tab(Screen.Projects, "Clips", Icons.Filled.PlayArrow),
    Tab(Screen.Exports, "Exports", Icons.Filled.Share),
)

@Composable
fun AppRoot(app: ClipforgeApp, sharedVideo: Uri?, onSharedVideoConsumed: () -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Dashboard) }
    val nav = remember { Nav(stack) }
    val current = stack.last()

    LaunchedEffect(sharedVideo) {
        if (sharedVideo != null) nav.tab(Screen.NewProject)
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }

    val rootTab = TABS.firstOrNull { it.screen == current }?.screen ?: when (current) {
        is Screen.Gallery, is Screen.Preview -> Screen.Projects
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current == Screen.Dashboard) "CLIPFORGE" else current.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (stack.size > 1 && TABS.none { it.screen == current }) {
                        IconButton(onClick = { nav.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { nav.tab(Screen.Ownership) }, modifier = Modifier.testTag("nav_ownership")) {
                        Icon(Icons.Filled.Lock, contentDescription = "Ownership")
                    }
                    IconButton(onClick = { nav.tab(Screen.Settings) }, modifier = Modifier.testTag("nav_settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Graphite),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                for (t in TABS) {
                    NavigationBarItem(
                        selected = rootTab == t.screen,
                        onClick = { nav.tab(t.screen) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        modifier = Modifier.testTag("tab_" + t.label.lowercase()),
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Lime.copy(alpha = 0.25f)),
                    )
                }
            }
        },
        containerColor = Graphite,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = current) {
                Screen.Dashboard -> DashboardScreen(app, nav)
                Screen.NewProject -> NewProjectScreen(app, nav, sharedVideo, onSharedVideoConsumed)
                Screen.Queue -> QueueScreen(app, nav)
                Screen.Projects -> ProjectsScreen(app, nav)
                is Screen.Gallery -> GalleryScreen(app, nav, s.jobId)
                is Screen.Preview -> ClipPreviewScreen(app, nav, s.clipId)
                Screen.Exports -> ExportsScreen(app, nav)
                Screen.Settings -> SettingsScreen(app)
                Screen.Ownership -> OwnershipScreen(app, nav)
            }
        }
    }
}
