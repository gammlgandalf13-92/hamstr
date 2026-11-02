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

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Angebote", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Meldungen, wenn deine Produkte im Angebot sind"
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

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, body: String, intent: Intent) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, id, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
