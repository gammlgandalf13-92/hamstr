package de.hamstr.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL = "angebote"
    private const val CHANNEL_UPDATES = "updates"
    private const val UPDATE_ID = 4711

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Angebote", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Meldungen, wenn deine Produkte im Angebot sind"
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_UPDATES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATES, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Hinweis, wenn eine neue Hamstr-Version verfügbar ist"
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun hit(context: Context, hit: Hit, lastSeen: String?) {
        val body = hit.body(lastSeen)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(hit.offer.url))
        post(context, hit.dedupeKey.hashCode(), hit.title(), body, open)
    }

    fun info(context: Context, title: String, body: String) {
        val open = Intent(context, MainActivity::class.java)
        post(context, title.hashCode(), title, body, open)
    }

    /** Update-Hinweis. Antippen startet Hamstr neu, damit der Update-Dialog sofort erscheint. */
    fun update(context: Context, info: UpdateInfo, reminder: Boolean): Boolean {
        val title = if (reminder) "Hamstr-Update wartet noch" else "Neue Hamstr-Version verfügbar"
        val body = "${info.name} – tippen, um jetzt zu aktualisieren."
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return post(context, UPDATE_ID, title, body, open, CHANNEL_UPDATES)
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context, id: Int, title: String, body: String, intent: Intent, channel: String = CHANNEL,
    ): Boolean {
        if (!canNotify(context)) return false
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, id, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        return true
    }
}
