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
    const val DAILY = "hamstr-daily"          // alter täglicher Plan (bis 1.0.23) – wird entfernt
    const val SCHEDULED = "hamstr-scheduled"
    const val NOW = "hamstr-now"
    const val UPDATES = "hamstr-updates"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Beim App-Start: täglichen Check sicherstellen, ohne einen bestehenden Plan zu verschieben. */
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DAILY)
        scheduleNext(context, replace = false)
        // Update-Prüfung alle 6 Stunden, unabhängig vom Angebots-Check
        val updates = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPDATES, ExistingPeriodicWorkPolicy.KEEP, updates)
    }

    /**
     * Nächste geplante Prüfung einplanen. [replace] = true ersetzt einen vorhandenen Termin
     * (nach Änderung der Prüfzeiten oder am Ende einer Prüfung), sonst bleibt er bestehen.
     */
    fun scheduleNext(context: Context, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        val now = LocalDateTime.now()
        val next = Schedule.nextRun(Store(context).schedule, now)
        if (next == null) {
            wm.cancelUniqueWork(SCHEDULED)
            return
        }
        val request = OneTimeWorkRequestBuilder<ScheduledCheckWorker>()
            .setInitialDelay(Duration.between(now, next).seconds.coerceAtLeast(0), TimeUnit.SECONDS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(SCHEDULED, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
