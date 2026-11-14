package de.hamstr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Angebot antippen → direkt die Prospektseite bei Marktguru, auf der das Angebot steht.
 * Dort führt ein Tipp auf den Artikel weiter zur Angebotsseite. Klappt das nicht, öffnet sich gleich die Angebotsseite.
 */
object LeafletLink {
    private val cache = mutableMapOf<String, String>()

    fun open(context: Context, scope: CoroutineScope, offerUrl: String) {
        if (offerUrl.isBlank()) return
        val id = Marktguru.offerIdFrom(offerUrl)
        if (id == null) {
            view(context, offerUrl)
            return
        }
        cache[id]?.let { view(context, it); return }
        val store = Store(context)
        Toast.makeText(context, "Prospekt wird geöffnet…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    val plz = store.plz
                    val c = store.center?.let { it.first to it.second } ?: Geo.coordsFor(context, plz)
                    if (c == null || plz.isBlank()) null else Marktguru.leafletPageUrl(id, plz, c.first, c.second)
                }.getOrNull()
            }
            if (target != null) cache[id] = target
            view(context, target ?: offerUrl)
        }
    }

    private fun view(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
