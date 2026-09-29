package com.siams.kern_prototype.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.siams.kern_prototype.viewmodel.StartViewModel
import com.siams.kern_prototype.ui.screens.CameraProfilesScreen
import com.siams.kern_prototype.ui.screens.CaptureScreen
import com.siams.kern_prototype.ui.screens.DrillingFormScreen
import com.siams.kern_prototype.ui.screens.DrillingListScreen
import com.siams.kern_prototype.ui.screens.SendQueueScreen
import com.siams.kern_prototype.ui.screens.SettingsScreen
import com.siams.kern_prototype.ui.screens.TableViewScreen
import com.siams.kern_prototype.ui.screens.TablesScreen

@Composable
fun KernNavGraph(navController: NavHostController = rememberNavController()) {
    // Онбординг первого запуска (§3.1): если сервер не настроен — открыть настройки один раз.
    val startVm: StartViewModel = hiltViewModel()
    val configured by startVm.configured.collectAsStateWithLifecycle()
    LaunchedEffect(configured) {
        if (configured == false && !startVm.onboarded) {
            startVm.onboarded = true
            navController.navigate(Routes.SETTINGS)
        }
    }

    NavHost(navController = navController, startDestination = Routes.DRILLING_LIST) {

        composable(Routes.DRILLING_LIST) {
            DrillingListScreen(
                onOpenDrilling = { id -> navController.navigate(Routes.tables(id)) },
                onNewDrilling = { navController.navigate(Routes.drillingForm()) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenProfiles = { navController.navigate(Routes.CAMERA_PROFILES) },
                onOpenQueue = { navController.navigate(Routes.SEND_QUEUE) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SEND_QUEUE) {
            SendQueueScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CAMERA_PROFILES) {
            CameraProfilesScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.DRILLING_FORM,
            arguments = listOf(
                navArgument(Routes.ARG_DRILLING_ID) {
                    type = NavType.StringType; defaultValue = ""
                },
            ),
        ) {
            DrillingFormScreen(
                onBack = { navController.popBackStack() },
                onSavedNew = { id ->
                    navController.navigate(Routes.capture(id)) {
                        popUpTo(Routes.DRILLING_LIST)
                    }
                },
            )
        }

        composable(
            route = Routes.TABLES,
            arguments = listOf(navArgument(Routes.ARG_DRILLING_ID) { type = NavType.StringType }),
        ) { entry ->
            val drillingId = entry.arguments?.getString(Routes.ARG_DRILLING_ID).orEmpty()
            TablesScreen(
                onBack = { navController.popBackStack() },
                onOpenTable = { tableId -> navController.navigate(Routes.tableView(tableId)) },
                onAddCapture = { navController.navigate(Routes.capture(drillingId)) },
                onEditPassport = { navController.navigate(Routes.drillingForm(drillingId)) },
            )
        }

        composable(
            route = Routes.TABLE_VIEW,
            arguments = listOf(navArgument(Routes.ARG_TABLE_ID) { type = NavType.StringType }),
        ) { entry ->
            val tableId = entry.arguments?.getString(Routes.ARG_TABLE_ID).orEmpty()
            TableViewScreen(
                onBack = { navController.popBackStack() },
                onReshoot = { drillingId, lamp ->
                    navController.navigate(Routes.capture(drillingId, tableId = tableId, lamp = lamp))
                },
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(
                navArgument(Routes.ARG_DRILLING_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_TABLE_ID) { type = NavType.StringType; defaultValue = "" },
                navArgument(Routes.ARG_LAMP) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val drillingId = entry.arguments?.getString(Routes.ARG_DRILLING_ID).orEmpty()
            CaptureScreen(
                onBack = {
                    navController.navigate(Routes.tables(drillingId)) {
                        popUpTo(Routes.DRILLING_LIST)
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
