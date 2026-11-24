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
        de.ex_parrot.cookbook.timer.TimerController.createChannels(this)
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settingsStore = SettingsStore(app)
    val sessionManager = SessionManager(app, AccountStore(app), scope)

    /** Navigation requests from intents (share menu, timer notifications). */
    val pendingImportUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingOpenTimers = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** A newer version to offer in the update dialog. */
    val availableUpdate = kotlinx.coroutines.flow.MutableStateFlow<de.ex_parrot.cookbook.data.UpdateInfo?>(null)
    /** The automatic check runs once per app start. */
    var startupUpdateCheckDone = false
}
