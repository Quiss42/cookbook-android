package de.ex_parrot.cookbook

import android.app.Application
import de.ex_parrot.cookbook.data.AccountStore
import de.ex_parrot.cookbook.data.SessionManager
import de.ex_parrot.cookbook.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class CookbookApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settingsStore = SettingsStore(app)
    val sessionManager = SessionManager(app, AccountStore(app), scope)
}
