package com.showtracker.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.showtracker.app.AppContainer
import com.showtracker.app.ui.detail.DetailScreen
import com.showtracker.app.ui.library.LibraryScreen
import com.showtracker.app.ui.search.SearchScreen
import com.showtracker.app.ui.search.SearchViewModel
import com.showtracker.app.ui.settings.SettingsScreen

private object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{showId}"

    fun detail(showId: Int) = "detail/$showId"
}

@Composable
fun ShowTrackerNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    // Scoped to the activity rather than to a screen: the library is shared by every
    // destination, and a per-screen instance would reopen the database subscription on each
    // navigation.
    val libraryViewModel: LibraryViewModel =
        viewModel(factory = LibraryViewModel.factory(container))

    // Refresh when the app comes to the foreground, not only on first composition. The
    // periodic worker is best-effort, so this is what actually keeps the library current.
    LifecycleResumeEffect(Unit) {
        libraryViewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                viewModel = libraryViewModel,
                onOpenShow = { navController.navigate(Routes.detail(it)) },
                onAddShow = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SEARCH) {
            val searchViewModel: SearchViewModel =
                viewModel(factory = SearchViewModel.factory(container))
            SearchScreen(
                searchViewModel = searchViewModel,
                libraryViewModel = libraryViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = libraryViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("showId") { type = NavType.IntType }),
        ) { entry ->
            DetailScreen(
                showId = entry.arguments?.getInt("showId") ?: return@composable,
                viewModel = libraryViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
