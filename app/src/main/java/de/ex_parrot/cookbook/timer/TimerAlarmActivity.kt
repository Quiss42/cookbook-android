package de.ex_parrot.cookbook.timer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.ex_parrot.cookbook.MainActivity
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.AppSettings
import de.ex_parrot.cookbook.ui.theme.CookbookTheme
import kotlinx.coroutines.launch

/**
 * Shown over the lock screen when a timer ends (full-screen notification).
 * Deliberately shows no recipe content: only the timer's name and buttons to
 * stop the alarm or open the app (which asks to unlock first).
 */
class TimerAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val timerId = intent.getIntExtra(TimerController.EXTRA_TIMER_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            CookbookTheme(AppSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Alarm, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.timer_done_title), style = MaterialTheme.typography.headlineLarge)
                        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Button(onClick = { stop(timerId) }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.timer_stop))
                        }
                        OutlinedButton(onClick = { openTimers(timerId) }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.timer_open))
                        }
                    }
                }
            }
        }
    }

    /** Ends the ringing notification and closes the screen. */
    private fun stop(timerId: Int) {
        lifecycleScope.launch {
            TimerController.cancel(applicationContext, timerId)
            finish()
        }
    }

    /** Stops the alarm and opens the timers screen after unlocking. */
    private fun openTimers(timerId: Int) {
        lifecycleScope.launch { TimerController.cancel(applicationContext, timerId) }
        val open = {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(TimerController.EXTRA_OPEN_TIMERS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = open()
                },
            )
        } else {
            open()
        }
    }

    companion object {
        const val EXTRA_TITLE = "timer_title"

        fun intent(context: Context, timerId: Int, title: String): Intent =
            Intent(context, TimerAlarmActivity::class.java)
                .putExtra(TimerController.EXTRA_TIMER_ID, timerId)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
