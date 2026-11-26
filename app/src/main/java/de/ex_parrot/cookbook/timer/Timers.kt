package de.ex_parrot.cookbook.timer

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.ex_parrot.cookbook.MainActivity
import de.ex_parrot.cookbook.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
data class CookingTimer(
    val id: Int,
    val recipeId: String,
    val title: String,
    val endsAt: Long,
    val durationMillis: Long,
)

private val Context.timerDataStore by preferencesDataStore(name = "timers")
private val timersKey = stringPreferencesKey("timers")

/** Running timers, persisted so they survive app restarts and reboots. */
class TimerStore(private val context: Context) {
    val timers: Flow<List<CookingTimer>> = context.timerDataStore.data.map { prefs ->
        prefs[timersKey]?.let { runCatching { Json.decodeFromString<List<CookingTimer>>(it) }.getOrNull() }
            .orEmpty()
            .sortedBy { it.endsAt }
    }

    suspend fun add(timer: CookingTimer) = update { it + timer }
    suspend fun remove(id: Int) = update { list -> list.filterNot { it.id == id } }

    private suspend fun update(change: (List<CookingTimer>) -> List<CookingTimer>) {
        context.timerDataStore.edit { prefs ->
            val current = prefs[timersKey]?.let { runCatching { Json.decodeFromString<List<CookingTimer>>(it) }.getOrNull() }.orEmpty()
            prefs[timersKey] = Json.encodeToString(change(current))
        }
    }
}

/** Schedules the alarms and shows the notifications for cooking timers. */
object TimerController {
    private const val CHANNEL_RUNNING = "timer_running"
    /** Replaced "timer_done": a channel's sound can't be changed once created. */
    private const val CHANNEL_ALARM = "timer_alarm"
    private const val OLD_CHANNEL_DONE = "timer_done"
    const val ACTION_FINISHED = "de.ex_parrot.cookbook.TIMER_FINISHED"
    const val ACTION_CANCEL = "de.ex_parrot.cookbook.TIMER_CANCEL"
    const val EXTRA_TIMER_ID = "timer_id"
    const val EXTRA_OPEN_TIMERS = "open_timers"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, context.getString(R.string.timer_channel_running), NotificationManager.IMPORTANCE_LOW),
        )
        manager.deleteNotificationChannel(OLD_CHANNEL_DONE)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, context.getString(R.string.timer_channel_done), NotificationManager.IMPORTANCE_HIGH).apply {
                // Rings like an alarm clock: alarm volume, also when the phone is on silent for media.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
            },
        )
    }

    /** False if notifications (or the alarm channel) are switched off: a timer would end silently. */
    fun canAlert(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ALARM)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun start(context: Context, recipeId: String, title: String, durationMillis: Long): CookingTimer {
        val timer = CookingTimer(
            id = Random.nextInt(1, Int.MAX_VALUE),
            recipeId = recipeId,
            title = title,
            endsAt = System.currentTimeMillis() + durationMillis,
            durationMillis = durationMillis,
        )
        TimerStore(context).add(timer)
        schedule(context, timer)
        return timer
    }

    suspend fun cancel(context: Context, timerId: Int) {
        TimerStore(context).remove(timerId)
        context.getSystemService(AlarmManager::class.java).cancel(finishIntent(context, timerId))
        NotificationManagerCompat.from(context).cancel(timerId)
    }

    /** Re-creates alarms (after reboot or app update); expired timers are reported as done. */
    suspend fun restoreAll(context: Context) {
        val now = System.currentTimeMillis()
        TimerStore(context).timers.first().forEach { timer ->
            if (timer.endsAt <= now) finish(context, timer.id) else schedule(context, timer)
        }
    }

    private fun schedule(context: Context, timer: CookingTimer) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = finishIntent(context, timer.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending)
        }
        notify(context, timer.id, runningNotification(context, timer))
    }

    /** Called by the alarm: replaces the countdown with a "done" notification. */
    suspend fun finish(context: Context, timerId: Int) {
        val store = TimerStore(context)
        val timer = store.timers.first().firstOrNull { it.id == timerId } ?: return
        store.remove(timerId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.timer_done_title))
            .setContentText(timer.title)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, timerId))
            // Shown over the lock screen where Android allows it.
            .setFullScreenIntent(openAppIntent(context, timerId), true)
            .addAction(0, context.getString(R.string.timer_stop), cancelIntent(context, timerId))
            .build()
        // Keeps ringing until the notification is tapped, stopped or swiped away.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        notify(context, timerId, notification)
    }

    private fun runningNotification(context: Context, timer: CookingTimer) =
        NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(timer.title)
            .setContentText(context.getString(R.string.timer_running_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(timer.endsAt)
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context, timer.id))
            .addAction(0, context.getString(R.string.timer_cancel), cancelIntent(context, timer.id))
            .build()

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun finishIntent(context: Context, timerId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            timerId,
            Intent(context, TimerReceiver::class.java).setAction(ACTION_FINISHED).putExtra(EXTRA_TIMER_ID, timerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelIntent(context: Context, timerId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            timerId,
            Intent(context, TimerReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TIMER_ID, timerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(context: Context, timerId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            timerId,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TIMERS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Handles alarm and notification actions as well as reboots. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val id = intent.getIntExtra(TimerController.EXTRA_TIMER_ID, 0)
                when (intent.action) {
                    TimerController.ACTION_FINISHED -> TimerController.finish(appContext, id)
                    TimerController.ACTION_CANCEL -> TimerController.cancel(appContext, id)
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> TimerController.restoreAll(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
