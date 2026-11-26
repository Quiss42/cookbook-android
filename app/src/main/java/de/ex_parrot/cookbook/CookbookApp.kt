package de.ex_parrot.cookbook

import android.app.Application
import de.ex_parrot.cookbook.data.AccountStore
import de.ex_parrot.cookbook.data.CookingProgressStore
import de.ex_parrot.cookbook.data.Http
import de.ex_parrot.cookbook.data.ImageTools
import de.ex_parrot.cookbook.data.UpdateInfo
import de.ex_parrot.cookbook.timer.TimerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        Http.init(filesDir)
        container = AppContainer(this)
        TimerController.createChannels(this)
        // Leftover prepared pictures and camera photos.
        container.scope.launch(Dispatchers.IO) { ImageTools.cleanUp(this@CookbookApp) }
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settingsStore = SettingsStore(app)
    val cookingProgress = CookingProgressStore(app)
    val sessionManager = SessionManager(app, AccountStore(app), scope)

    /** Navigation requests from intents (share menu, timer notifications). */
    val pendingImportUrl = MutableStateFlow<String?>(null)
    val pendingOpenTimers = MutableStateFlow(false)

    /** A newer version to offer in the update dialog. */
    val availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    /** The automatic check runs once per app start. */
    var startupUpdateCheckDone = false
}
