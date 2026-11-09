package de.hamstr.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prüft im Hintergrund (alle paar Stunden) auf eine neue App-Version und meldet sie per Benachrichtigung. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        UpdateNotifications.check(applicationContext)
        Scheduler.scheduleNext(applicationContext, replace = false)
        Result.success()
    }
}

object UpdateNotifications {
    private const val REMIND_AFTER_MS = 3L * 24 * 60 * 60 * 1000 // 3 Tage

    enum class Notice { NONE, FIRST, REMINDER }

    /** Erste Meldung pro Version, danach höchstens eine Erinnerung nach 3 Tagen. */
    fun decide(latest: Int, current: Int, notified: Int, notifiedAt: Long, reminded: Int, now: Long): Notice = when {
        latest <= current -> Notice.NONE
        latest > notified -> Notice.FIRST
        reminded < latest && now - notifiedAt >= REMIND_AFTER_MS -> Notice.REMINDER
        else -> Notice.NONE
    }

    /** Blockierend; Fehler (kein Netz, GitHub nicht erreichbar) werden still ignoriert. */
    fun check(context: Context) {
        runCatching {
            val latest = Updater.latest() ?: return
            val st = Store(context)
            val now = System.currentTimeMillis()
            when (decide(latest.version, Updater.currentVersion(context), st.updateNotified,
                st.updateNotifiedAt, st.updateReminded, now)) {
                Notice.FIRST -> {
                    if (Notifier.update(context, latest, reminder = false)) {
                        st.updateNotified = latest.version
                        st.updateNotifiedAt = now
                    }
                }
                Notice.REMINDER -> {
                    if (Notifier.update(context, latest, reminder = true)) st.updateReminded = latest.version
                }
                Notice.NONE -> Unit
            }
        }
    }
}
