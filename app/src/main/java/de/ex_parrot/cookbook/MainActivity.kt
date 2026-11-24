package de.ex_parrot.cookbook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.ex_parrot.cookbook.data.AppSettings
import de.ex_parrot.cookbook.data.Session
import de.ex_parrot.cookbook.data.SessionState
import de.ex_parrot.cookbook.data.UpdateChecker
import de.ex_parrot.cookbook.data.UpdateInfo
import de.ex_parrot.cookbook.timer.TimerController
import de.ex_parrot.cookbook.timer.TimersScreen
import de.ex_parrot.cookbook.ui.browse.CategoriesScreen
import de.ex_parrot.cookbook.ui.browse.CategoriesViewModel
import de.ex_parrot.cookbook.ui.browse.RecipeListScreen
import de.ex_parrot.cookbook.ui.browse.RecipeListViewModel
import de.ex_parrot.cookbook.ui.components.LoadingView
import de.ex_parrot.cookbook.ui.edit.EditRecipeScreen
import de.ex_parrot.cookbook.ui.edit.EditRecipeViewModel
import de.ex_parrot.cookbook.ui.importer.ImportScreen
import de.ex_parrot.cookbook.ui.importer.ImportViewModel
import de.ex_parrot.cookbook.ui.importer.extractUrl
import de.ex_parrot.cookbook.ui.login.LoginScreen
import de.ex_parrot.cookbook.ui.login.LoginViewModel
import de.ex_parrot.cookbook.ui.recipe.RecipeScreen
import de.ex_parrot.cookbook.ui.recipe.RecipeViewModel
import de.ex_parrot.cookbook.ui.search.SearchScreen
import de.ex_parrot.cookbook.ui.search.SearchViewModel
import de.ex_parrot.cookbook.ui.settings.SettingsScreen
import de.ex_parrot.cookbook.ui.theme.CookbookTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object CategoriesRoute

@Serializable
data class RecipeListRoute(val category: String)

@Serializable
data class RecipeRoute(val id: String, val name: String)

/** [id] null creates a new recipe. */
@Serializable
data class EditRecipeRoute(val id: String? = null)

@Serializable
data class ImportRoute(val url: String = "")

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Serializable
data object TimersRoute

class MainActivity : ComponentActivity() {
    private val container get() = (application as CookbookApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
            val sessionState by container.sessionManager.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                // Wait for the stored settings (not the defaults) before deciding.
                if (!container.startupUpdateCheckDone && container.settingsStore.settings.first().checkUpdatesOnStart) {
                    container.startupUpdateCheckDone = true
                    // Silent at startup: no message if offline or up to date.
                    runCatching { UpdateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
                        ?.let { container.availableUpdate.value = it }
                }
            }
            CookbookTheme(settings) {
                when (val state = sessionState) {
                    SessionState.Loading -> LoadingView()
                    SessionState.LoggedOut -> LoginScreen(viewModel { LoginViewModel(container.sessionManager) })
                    is SessionState.LoggedIn -> MainNavigation(container, state.session, settings)
                }
                val update by container.availableUpdate.collectAsStateWithLifecycle()
                update?.let { info ->
                    UpdateDialog(
                        info = info,
                        onInstall = {
                            container.availableUpdate.value = null
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.downloadUrl)))
                        },
                        onDismiss = { container.availableUpdate.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent == null -> Unit
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                container.pendingImportUrl.value = extractUrl(text) ?: text
            }
            intent.getBooleanExtra(TimerController.EXTRA_OPEN_TIMERS, false) -> container.pendingOpenTimers.value = true
        }
    }
}

@Composable
private fun MainNavigation(container: AppContainer, session: Session, settings: AppSettings) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val repository = session.repository
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Requests coming from outside the app (share menu, notifications).
    val pendingImport by container.pendingImportUrl.collectAsStateWithLifecycle()
    val pendingTimers by container.pendingOpenTimers.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImport) {
        pendingImport?.let { url ->
            container.pendingImportUrl.value = null
            nav.navigate(ImportRoute(url))
        }
    }
    LaunchedEffect(pendingTimers) {
        if (pendingTimers) {
            container.pendingOpenTimers.value = false
            nav.navigate(TimersRoute) { launchSingleTop = true }
        }
    }

    fun openRecipe(id: String, name: String) = nav.navigate(RecipeRoute(id, name))

    // Keep the offline copy up to date (at most every 10 minutes).
    LaunchedEffect(session) { session.requestSync() }

    val offlineNow by session.offlineStore.offline.collectAsStateWithLifecycle()
    val pendingChanges by session.offlineStore.pending.changes.collectAsStateWithLifecycle()
    val offline = offlineNow || pendingChanges.isNotEmpty()

    LaunchedEffect(session) {
        session.repository.notices.collect { detail ->
            Toast.makeText(context, context.getString(R.string.upload_image_missing, detail), Toast.LENGTH_LONG).show()
        }
    }

    // Offline changes that clash with changes on the server: ask the user.
    pendingChanges.firstOrNull { it.conflict }?.let { change ->
        ConflictDialog(
            change = change,
            onChoice = { choice ->
                container.scope.launch {
                    try {
                        session.repository.resolveConflict(change, choice)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Toast.makeText(context, R.string.conflict_failed, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
    androidx.compose.foundation.layout.Column {
    NavHost(
        navController = nav,
        startDestination = CategoriesRoute,
        // The banner below takes the navigation bar space while it is shown.
        modifier = androidx.compose.ui.Modifier
            .weight(1f)
            .then(
                if (offline) {
                    androidx.compose.ui.Modifier.consumeWindowInsets(
                        androidx.compose.foundation.layout.WindowInsets.navigationBars,
                    )
                } else {
                    androidx.compose.ui.Modifier
                },
            ),
    ) {
        composable<CategoriesRoute> {
            CategoriesScreen(
                session = session,
                viewModel = viewModel { CategoriesViewModel(repository) },
                onOpenCategory = { nav.navigate(RecipeListRoute(it)) },
                onSearch = { nav.navigate(SearchRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
                onNewRecipe = { nav.navigate(EditRecipeRoute()) },
                onImport = { nav.navigate(ImportRoute()) },
                onTimers = { nav.navigate(TimersRoute) },
                columns = settings.categoryColumns,
            )
        }
        composable<RecipeListRoute> { entry ->
            val route = entry.toRoute<RecipeListRoute>()
            RecipeListScreen(
                session = session,
                category = route.category,
                viewModel = viewModel { RecipeListViewModel(repository, route.category) },
                onOpenRecipe = { openRecipe(it.id, it.name) },
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
                onEdit = { nav.navigate(EditRecipeRoute(route.id)) },
                onStartTimer = { recipe, duration ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    container.scope.launch {
                        TimerController.start(context.applicationContext, recipe.id, recipe.name, duration.toMillis())
                        Toast.makeText(context, R.string.timer_started, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        composable<EditRecipeRoute> { entry ->
            val route = entry.toRoute<EditRecipeRoute>()
            EditRecipeScreen(
                imageLoader = session.imageLoader,
                viewModel = viewModel {
                    EditRecipeViewModel(repository, session.files, context.applicationContext, route.id)
                },
                onSaved = { id, name ->
                    if (route.id == null) {
                        // Show the new recipe instead of the editor.
                        nav.navigate(RecipeRoute(id, name)) { popUpTo<EditRecipeRoute> { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
                onClose = { nav.popBackStack() },
            )
        }
        composable<ImportRoute> { entry ->
            val route = entry.toRoute<ImportRoute>()
            ImportScreen(
                viewModel = viewModel { ImportViewModel(repository, route.url) },
                onImported = { id, name ->
                    nav.navigate(RecipeRoute(id, name)) { popUpTo<ImportRoute> { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                session = session,
                viewModel = viewModel { SearchViewModel(repository) },
                onOpenRecipe = { openRecipe(it.id, it.name) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<TimersRoute> {
            TimersScreen(
                onOpenRecipe = ::openRecipe,
                onBack = { nav.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                session = session,
                settings = settings,
                settingsStore = container.settingsStore,
                onUpdateFound = { container.availableUpdate.value = it },
                onLogout = { container.scope.launch { container.sessionManager.logout() } },
                onBack = { nav.popBackStack() },
            )
        }
    }
    de.ex_parrot.cookbook.ui.components.OfflineBanner(session)
    }
}

@Composable
private fun UpdateDialog(info: UpdateInfo, onInstall: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.update_found_title)) },
        text = {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(R.string.update_found_text, info.version, BuildConfig.VERSION_NAME),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onInstall) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.yes))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.no))
            }
        },
    )
}

@Composable
private fun ConflictDialog(
    change: de.ex_parrot.cookbook.data.PendingChange,
    onChoice: (de.ex_parrot.cookbook.data.ConflictChoice) -> Unit,
) {
    val isDelete = change.type == de.ex_parrot.cookbook.data.PendingChange.Type.DELETE
    androidx.compose.material3.AlertDialog(
        // Must be decided; dismissing just shows it again later.
        onDismissRequest = {},
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.conflict_title, change.name)) },
        text = {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (isDelete) R.string.conflict_text_delete else R.string.conflict_text_update),
            )
        },
        confirmButton = {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                androidx.compose.material3.TextButton(onClick = { onChoice(de.ex_parrot.cookbook.data.ConflictChoice.MINE) }) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(if (isDelete) R.string.conflict_delete_anyway else R.string.conflict_mine),
                    )
                }
                androidx.compose.material3.TextButton(onClick = { onChoice(de.ex_parrot.cookbook.data.ConflictChoice.SERVER) }) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(if (isDelete) R.string.conflict_keep_recipe else R.string.conflict_server),
                    )
                }
                if (!isDelete) {
                    androidx.compose.material3.TextButton(onClick = { onChoice(de.ex_parrot.cookbook.data.ConflictChoice.BOTH) }) {
                        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.conflict_both))
                    }
                }
            }
        },
    )
}
