package de.hamstr.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Produktbilder werden beim Anlegen aufs Handy kopiert – Angebotsbilder verschwinden sonst mit dem Angebot. */
object Images {
    private fun dir(context: Context) = File(context.filesDir, "images").apply { mkdirs() }

    /** Lädt das Bild herunter und gibt den lokalen Pfad zurück, oder null bei Fehler. Blockierend – nur im Hintergrund aufrufen. */
    fun download(context: Context, url: String, productId: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Hamstr/1.0 (Android)")
        try {
            if (c.responseCode != 200) null
            else {
                val target = File(dir(context), "$productId.img")
                c.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                if (target.length() > 0) target.absolutePath else null
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun delete(context: Context, productId: String) {
        File(dir(context), "$productId.img").delete()
    }

    /** Für die Anzeige: lokale Pfade als Datei, alles andere als URL. */
    fun model(image: String): Any? = when {
        image.isBlank() -> null
        image.startsWith("/") -> File(image)
        else -> image
    }
}
