package com.mtt.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mtt.app.ui.glossary.GlossaryScreen
import com.mtt.app.ui.result.ResultScreen
import com.mtt.app.ui.settings.SettingsScreen
import com.mtt.app.ui.translation.TranslationScreen

/**
 * Top-level navigation destinations.
 *
 * Each entry maps to a route, a bottom-tab label, and an icon.
 */
private data class BottomNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomNavDestinations = listOf(
    BottomNavDestination("translation", "翻译", Icons.AutoMirrored.Filled.Send),
    BottomNavDestination("glossary", "术语表", Icons.AutoMirrored.Filled.List),
    BottomNavDestination("settings", "设置", Icons.Default.Settings)
)

/**
 * Root composable that hosts the bottom navigation bar and the
 * navigation graph containing all top-level screens.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val startDestination = bottomNavDestinations.first().route
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(windowInsets = NavigationBarDefaults.windowInsets) {
                bottomNavDestinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Pop up to the start destination to avoid building
                                // up a large back-stack of destinations
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination
                                launchSingleTop = true
                                // Restore state when re-selecting a previously selected item
                                restoreState = true
                            }
                            selectedTabIndex = index
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("translation") {
                TranslationScreen(
                    onNavigateToSettings = {
                        navController.navigate("settings") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable("glossary") {
                GlossaryScreen()
            }
            composable("settings") {
                SettingsScreen()
            }
            composable("result") {
                ResultScreen()
            }
        }
    }
}
