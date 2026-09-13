package com.anythingllm.importer.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anythingllm.importer.ui.home.HomeScreen
import com.anythingllm.importer.ui.import.ImportScreen
import com.anythingllm.importer.ui.import.ImportSessionViewModel
import com.anythingllm.importer.ui.import.TargetScreen
import com.anythingllm.importer.ui.logs.LogsScreen
import com.anythingllm.importer.ui.select.SelectScreen
import com.anythingllm.importer.ui.select.SelectViewModel
import com.anythingllm.importer.ui.settings.SettingsScreen
import com.anythingllm.importer.ui.validate.ValidationScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SELECT = "select"
    const val VALIDATE = "validate"
    const val TARGET = "target"
    const val IMPORT = "import"
    const val LOGS = "logs"       // 阶段 4
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    // UI-23:页面过渡动画(进入=淡入+轻微右滑,退出=淡出,pop 反向),时长 ≤220ms 保持轻快
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 20 } },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 20 } },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenImport = { navController.navigate(Routes.SELECT) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SELECT) {
            SelectScreen(
                onBack = { navController.popBackStack() },
                onValidate = { navController.navigate(Routes.VALIDATE) },
            )
        }
        composable(Routes.VALIDATE) {
            // 与选择页共享同一 SelectViewModel(选择/校验结果跨页共享状态)
            val parentEntry = remember(navController) {
                navController.getBackStackEntry(Routes.SELECT)
            }
            val selectViewModel: SelectViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = SelectViewModel.factory(),
            )
            ValidationScreen(
                viewModel = selectViewModel,
                onBack = { navController.popBackStack() },
                onNext = { navController.navigate(Routes.TARGET) },
            )
        }
        composable(Routes.TARGET) {
            val parentEntry = remember(navController) {
                navController.getBackStackEntry(Routes.SELECT)
            }
            val selectViewModel: SelectViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = SelectViewModel.factory(),
            )
            val selectState by selectViewModel.uiState.collectAsStateWithLifecycle()
            val passed = selectState.validation?.passed.orEmpty()
            val sessionViewModel: ImportSessionViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = ImportSessionViewModel.factory(passed),
            )
            TargetScreen(
                passedFiles = passed,
                viewModel = sessionViewModel,
                onBack = { navController.popBackStack() },
                onStart = { navController.navigate(Routes.IMPORT) },
            )
        }
        composable(Routes.IMPORT) {
            val parentEntry = remember(navController) {
                navController.getBackStackEntry(Routes.SELECT)
            }
            val selectViewModel: SelectViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = SelectViewModel.factory(),
            )
            val selectState by selectViewModel.uiState.collectAsStateWithLifecycle()
            val passed = selectState.validation?.passed.orEmpty()
            val sessionViewModel: ImportSessionViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = ImportSessionViewModel.factory(passed),
            )
            ImportScreen(
                viewModel = sessionViewModel,
                onDone = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}
