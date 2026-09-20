package com.itsluminous.cleartravel.feature.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Route of the Menu tab root. */
const val MENU_ROUTE = "menu"

private const val HOME_ROUTE = "menu_home"
private const val SETTINGS_ROUTE = "menu_settings"
private const val PRESETS_ROUTE = "menu_presets"
private const val PRESET_EDIT_ROUTE = "menu_preset_edit/{$PRESET_ID_ARG}"
private const val ABOUT_ROUTE = "menu_about"

private fun presetEditRoute(presetId: String) = "menu_preset_edit/$presetId"

/**
 * Menu tab graph. The tab hosts its own nested NavHost (root list → Settings /
 * Manage presets / preset editor / About) so subscreens keep the bottom bar on the
 * Menu tab. Backup/Restore and Google-account rows are added by later milestones as
 * further entries + destinations.
 */
fun NavGraphBuilder.menuGraph() {
    composable(MENU_ROUTE) {
        MenuTabHost()
    }
}

@Composable
internal fun MenuTabHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = HOME_ROUTE,
        modifier = modifier,
    ) {
        composable(HOME_ROUTE) {
            MenuRootScreen(
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                onOpenPresets = { navController.navigate(PRESETS_ROUTE) },
                onOpenAbout = { navController.navigate(ABOUT_ROUTE) },
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(PRESETS_ROUTE) {
            PresetManagerScreen(
                onBack = { navController.popBackStack() },
                onOpenPreset = { presetId -> navController.navigate(presetEditRoute(presetId)) },
            )
        }
        composable(
            route = PRESET_EDIT_ROUTE,
            arguments = listOf(navArgument(PRESET_ID_ARG) { type = NavType.StringType }),
        ) {
            PresetEditScreen(onBack = { navController.popBackStack() })
        }
        composable(ABOUT_ROUTE) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
