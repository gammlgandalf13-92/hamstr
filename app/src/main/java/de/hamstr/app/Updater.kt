package de.hamstr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: Int,        // Build-Nummer aus dem Release-Tag (v1.0.N → N)
    val name: String,
    val downloadUrl: String,
)

/** Prüft die (öffentlichen) GitHub-Releases auf eine neuere APK, lädt sie herunter und startet die Installation. */
object Updater {
    private const val USER_AGENT = "Hamstr-Updater"
    private val repo get() = BuildConfig.UPDATE_REPO

    @Suppress("DEPRECATION")
    fun currentVersion(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    /** Neuestes Release – null, wenn es keins mit APK gibt. Blockierend. */
    fun latest(): UpdateInfo? {
        val c = open("https://api.github.com/repos/$repo/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (c.responseCode) {
                200 -> Unit
                403, 429 -> throw IOException("GitHub hat gerade zu viele Anfragen – später erneut versuchen.")
                404 -> throw IOException("Kein Release gefunden. Ist das Repo $repo öffentlich?")
                else -> throw IOException("GitHub HTTP ${c.responseCode}")
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            val version = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    return UpdateInfo(version, o.optString("name").ifBlank { tag }, a.optString("browser_download_url"))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Lädt die APK in den Cache (GitHub leitet auf seinen Download-Server weiter, das folgt HttpURLConnection selbst). */
    fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val target = File(context.cacheDir, "updates/hamstr.apk").apply { parentFile?.mkdirs(); delete() }
        var url = info.downloadUrl
        repeat(6) {
            val c = open(url)
            c.instanceFollowRedirects = false
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                    return@repeat
                }
                if (code != 200) throw IOException("Download fehlgeschlagen (HTTP $code)")
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                return target
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    /** Darf Hamstr Apps installieren? (Android fragt das einmal pro App.) */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        return c
    }
}
