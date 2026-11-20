package de.ex_parrot.cookbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.ex_parrot.cookbook.data.AppSettings
import de.ex_parrot.cookbook.data.Session
import de.ex_parrot.cookbook.data.SessionState
import de.ex_parrot.cookbook.ui.browse.CategoriesScreen
import de.ex_parrot.cookbook.ui.browse.CategoriesViewModel
import de.ex_parrot.cookbook.ui.browse.RecipeListScreen
import de.ex_parrot.cookbook.ui.browse.RecipeListViewModel
import de.ex_parrot.cookbook.ui.components.LoadingView
import de.ex_parrot.cookbook.ui.login.LoginScreen
import de.ex_parrot.cookbook.ui.login.LoginViewModel
import de.ex_parrot.cookbook.ui.recipe.RecipeScreen
import de.ex_parrot.cookbook.ui.recipe.RecipeViewModel
import de.ex_parrot.cookbook.ui.search.SearchScreen
import de.ex_parrot.cookbook.ui.search.SearchViewModel
import de.ex_parrot.cookbook.ui.settings.SettingsScreen
import de.ex_parrot.cookbook.ui.theme.CookbookTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object CategoriesRoute

@Serializable
data class RecipeListRoute(val category: String)

@Serializable
data class RecipeRoute(val id: String, val name: String)

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as CookbookApp).container
        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
            val sessionState by container.sessionManager.state.collectAsStateWithLifecycle()
            CookbookTheme(settings) {
                when (val state = sessionState) {
                    SessionState.Loading -> LoadingView()
                    SessionState.LoggedOut -> LoginScreen(viewModel { LoginViewModel(container.sessionManager) })
                    is SessionState.LoggedIn -> MainNavigation(container, state.session, settings)
                }
            }
        }
    }
}

@Composable
private fun MainNavigation(container: AppContainer, session: Session, settings: AppSettings) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val repository = session.repository

    NavHost(navController = nav, startDestination = CategoriesRoute) {
        composable<CategoriesRoute> {
            CategoriesScreen(
                session = session,
                viewModel = viewModel { CategoriesViewModel(repository) },
                onOpenCategory = { nav.navigate(RecipeListRoute(it)) },
                onSearch = { nav.navigate(SearchRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<RecipeListRoute> { entry ->
            val route = entry.toRoute<RecipeListRoute>()
            RecipeListScreen(
                session = session,
                category = route.category,
                viewModel = viewModel { RecipeListViewModel(repository, route.category) },
                onOpenRecipe = { nav.navigate(RecipeRoute(it.id, it.name)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<RecipeRoute> { entry ->
            val route = entry.toRoute<RecipeRoute>()
            RecipeScreen(
                session = session,
                title = route.name,
                viewModel = viewModel { RecipeViewModel(repository, route.id) },
                keepScreenOn = settings.keepScreenOn,
                onBack = { nav.popBackStack() },
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                session = session,
                viewModel = viewModel { SearchViewModel(repository) },
                onOpenRecipe = { nav.navigate(RecipeRoute(it.id, it.name)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                session = session,
                settings = settings,
                settingsStore = container.settingsStore,
                onLogout = { container.scope.launch { container.sessionManager.logout() } },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
