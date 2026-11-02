package de.hamstr.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Hintergrund-Aufgabe: eine Prüfung. Android startet sie auch nach Neustart des Handys. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Checker.run(applicationContext)
        if (s.products > 0 && s.errors == s.products && runAttemptCount < 2) Result.retry() else Result.success()
    }
}

object Scheduler {
    const val DAILY = "hamstr-daily"
    const val NOW = "hamstr-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Beim App-Start: täglichen Check sicherstellen, ohne einen bestehenden Plan zu verschieben. */
    fun ensureScheduled(context: Context) =
        schedule(context, Store(context).hour, ExistingPeriodicWorkPolicy.KEEP)

    fun schedule(
        context: Context,
        hour: Int,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
    ) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 15)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<CheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, policy, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
