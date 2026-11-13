package de.hamstr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
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

    /** Eigenes Foto übernehmen: auf ca. 512 px verkleinern und als Datei speichern. Blockierend. */
    fun importFrom(context: Context, uri: Uri, productId: String): String? = try {
        val resolver = context.contentResolver
        fun sampleFor(w: Int, h: Int): Int {
            var s = 1
            while (w / (s * 2) >= 512 && h / (s * 2) >= 512) s *= 2
            return s
        }
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder beachtet die Drehung aus dem Foto (EXIF)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        if (bmp == null) null
        else {
            val png = bmp.hasAlpha()
            val target = File(dir(context), "$productId-own-${System.currentTimeMillis()}." + if (png) "png" else "jpg")
            target.outputStream().use {
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
            }
            bmp.recycle()
            target.absolutePath
        }
    } catch (e: Exception) {
        null
    }

    /** Bild aus einer Importdatei speichern. */
    fun saveBytes(context: Context, bytes: ByteArray, productId: String): String? = try {
        val target = File(dir(context), "$productId.img")
        target.writeBytes(bytes)
        target.absolutePath
    } catch (e: Exception) {
        null
    }

    /** Alle Bilder eines Produkts löschen. */
    fun delete(context: Context, productId: String) {
        dir(context).listFiles()?.filter { it.name.startsWith(productId) }?.forEach { it.delete() }
    }

    /** Nicht mehr benutzte Bilder eines Produkts löschen (z. B. ein vorher gewähltes eigenes Foto). */
    fun cleanup(context: Context, productId: String, keep: String) {
        dir(context).listFiles()
            ?.filter { it.name.startsWith(productId) && it.absolutePath != keep && it.name != "$productId.img" }
            ?.forEach { it.delete() }
    }

    /** Für die Anzeige: lokale Pfade als Datei, alles andere als URL. */
    fun model(image: String): Any? = when {
        image.isBlank() -> null
        image.startsWith("/") -> File(image)
        else -> image
    }
}
