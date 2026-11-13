package de.hamstr.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Ein Produkt aus einer Importdatei – mit eingebettetem Bild, falls vorhanden. */
class ImportedProduct(val product: Product, val imageBytes: ByteArray?)

/**
 * Produktliste als Datei teilen und wieder einlesen (z. B. für Familie mit anderer PLZ).
 * Nur die Produkte – PLZ, Märkte und Prüfzeiten stellt jeder selbst ein.
 * Eigene Bilder und Prospektbilder liegen nur auf dem Handy, deshalb stecken sie direkt in der Datei.
 */
object Transfer {
    const val MIME = "application/json"

    fun exportFile(context: Context, products: List<Product>): File {
        val store = Store(context)
        val arr = JSONArray()
        products.forEach { p ->
            val o = store.productTo(p)
            if (p.image.startsWith("/")) {
                val f = File(p.image)
                if (f.isFile && f.length() in 1..1_500_000) {
                    o.put("imageData", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                }
                o.put("image", "")
            }
            arr.put(o)
        }
        val json = JSONObject()
            .put("app", "hamstr")
            .put("format", 1)
            .put("exported", LocalDate.now().toString())
            .put("products", arr)
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        return File(dir, "hamstr-produkte.json").apply { writeText(json.toString(1)) }
    }

    /** Teilen-Menü mit der Datei (WhatsApp, Mail, Drive …). */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Hamstr-Produktliste")
            .putExtra(Intent.EXTRA_TEXT, "Meine Hamstr-Produktliste – in Hamstr öffnen oder unter Produkte ⋮ → „Produktliste importieren“.")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        return Intent.createChooser(send, "Produktliste teilen")
    }

    /** Datei lesen. Wirft IOException, wenn es keine Hamstr-Liste ist. Blockierend. */
    fun read(context: Context, uri: Uri): List<ImportedProduct> {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: throw IOException("Datei nicht lesbar")
        val json = runCatching { JSONObject(text) }.getOrNull()
        val arr = json?.optJSONArray("products")
        if (json == null || json.optString("app") != "hamstr" || arr == null) {
            throw IOException("Das ist keine Hamstr-Produktliste.")
        }
        val store = Store(context)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val bytes = o.optString("imageData").takeIf { it.isNotBlank() }
                ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            // Neue ID, damit nichts Vorhandenes überschrieben wird
            val p = store.productFrom(o).copy(id = UUID.randomUUID().toString())
            if (p.names.isEmpty() && p.brand.isBlank()) null else ImportedProduct(p, bytes)
        }
    }

    /** Eingebettete Bilder als Dateien ablegen; gibt die Produkte mit lokalem Bildpfad zurück. */
    fun materialize(context: Context, items: List<ImportedProduct>): List<Product> = items.map { item ->
        val bytes = item.imageBytes ?: return@map item.product
        val path = Images.saveBytes(context, bytes, item.product.id) ?: return@map item.product
        item.product.copy(image = path, imageSource = item.product.imageSource.ifBlank { "own" })
    }

    /** Uri aus „Öffnen mit“ (VIEW) oder „Teilen an“ (SEND). */
    fun uriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
}
