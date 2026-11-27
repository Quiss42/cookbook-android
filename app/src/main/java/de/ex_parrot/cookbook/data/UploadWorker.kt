package de.ex_parrot.cookbook.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.ex_parrot.cookbook.CookbookApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Uploads offline changes once a connection is available, also when the app
 * is not open. Android starts it again later if the server is still unreachable.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessions = (applicationContext as CookbookApp).container.sessionManager
        val state = sessions.state.first { it !is SessionState.Loading }
        val session = (state as? SessionState.LoggedIn)?.session ?: return Result.success()
        if (session.loginExpired.value || session.certificateProblem.value || session.offlineStore.closed) {
            return Result.success()
        }
        val pending = session.offlineStore.pending
        val repository = session.repository
        // Repeat while changes were made during the upload.
        do {
            val before = pending.changes.value
            repository.uploadPending()
        } while (pending.changes.value != before && repository.hasUploadsWaiting())
        // Changes without an error are only left if the server was unreachable.
        val waiting = pending.changes.value.any { !it.conflict && it.error == null }
        val blocked = session.loginExpired.value || session.certificateProblem.value || session.offlineStore.closed
        return if (waiting && !blocked) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "upload-offline-changes"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}
