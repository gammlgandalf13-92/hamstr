package de.hamstr.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Direkt zum Markt: öffnet die App des Händlers, wenn sie installiert ist – sonst dessen Website.
 * Paketnamen, soweit bekannt; zusätzlich wird unter den installierten Apps nach dem Namen gesucht
 * (z. B. „EDEKA“), damit es auch klappt, wenn ein Händler seine App umbenennt.
 */
object RetailerApps {
    data class Retailer(
        val label: String,           // für Knöpfe: „In der EDEKA-App öffnen“
        val packages: List<String>,  // bekannte Paketnamen, wahrscheinlichster zuerst
        val appNames: List<String>,  // so heißen die Apps auf dem Startbildschirm
        val website: String,
        val notNames: List<String> = emptyList(), // gleichnamige Apps, die nicht gemeint sind
        val search: String? = null,  // Produktsuche auf der Website, {q} = Suchbegriff (Test)
    )

    val RETAILERS = mapOf(
        "edeka" to Retailer("EDEKA", listOf("de.edeka.genuss", "de.edeka.app"), listOf("EDEKA"),
            "https://www.edeka.de/", notNames = listOf("smart", "24", "foodservice")),
        "rewe" to Retailer("REWE", listOf("de.rewe.app.mobile", "de.rewe.app"), listOf("REWE"), "https://www.rewe.de/angebote/",
            search = "https://www.rewe.de/suche/?searchTerm={q}"),
        "lidl" to Retailer("Lidl", listOf("com.lidl.eci.lidlplus", "de.sec.mobile"), listOf("Lidl Plus", "Lidl"), "https://www.lidl.de/",
            search = "https://www.lidl.de/q/search?q={q}"),
        "aldi" to Retailer("ALDI", listOf("de.apptiv.business.android.aldi_de", "de.aldinord.android", "de.aldi.nord.app"),
            listOf("ALDI SÜD", "ALDI Nord", "ALDI"), "https://www.aldi-sued.de/", notNames = listOf("talk", "mobile", "life")),
        "netto" to Retailer("Netto", listOf("com.valuephone.vpnetto"), listOf("Netto"), "https://www.netto-online.de/"),
        "penny" to Retailer("PENNY", listOf("de.penny.app", "de.penny.mobile"), listOf("PENNY"), "https://www.penny.de/angebote"),
        "kaufland" to Retailer("Kaufland", listOf("com.kaufland.Kaufland"), listOf("Kaufland"), "https://filiale.kaufland.de/",
            search = "https://filiale.kaufland.de/suche.html?q={q}"),
        "norma" to Retailer("NORMA", listOf("de.online.norma"), listOf("NORMA"),
            "https://www.norma-online.de/"),
    )

    fun retailerFor(storeOrChain: String): Retailer? =
        RETAILERS[storeOrChain.lowercase()] ?: RETAILERS[Geo.chainKey(storeOrChain)]

    /** Passt der App-Name auf dem Startbildschirm zu diesem Händler? */
    fun nameMatches(appLabel: String, r: Retailer): Boolean {
        val n = Matcher.norm(appLabel)
        if (r.notNames.any { Matcher.norm(it) in n.split(" ") || n.endsWith(Matcher.norm(it)) }) return false
        return r.appNames.any { name -> val m = Matcher.norm(name); n == m || n.startsWith("$m ") }
    }

    /** Start-Intent der installierten Händler-App, sonst null. */
    fun appIntent(context: Context, r: Retailer): Intent? {
        val pm = context.packageManager
        r.packages.forEach { pkg -> pm.getLaunchIntentForPackage(pkg)?.let { return it } }
        // Fallback: installierte Apps nach Namen durchsuchen (sichtbar über <queries> im Manifest)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { launcherApps(pm, launcher) }.getOrDefault(emptyList())
        val hit = apps.firstOrNull {
            it.activityInfo.packageName != context.packageName && nameMatches(it.loadLabel(pm).toString(), r)
        } ?: return null
        return pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
    }

    @Suppress("DEPRECATION")
    private fun launcherApps(pm: PackageManager, launcher: Intent) =
        if (android.os.Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentActivities(launcher, 0)

    fun hasApp(context: Context, storeOrChain: String): Boolean =
        retailerFor(storeOrChain)?.let { appIntent(context, it) != null } ?: false

    /** Händler-App öffnen, sonst Website. Gibt false zurück, wenn der Händler unbekannt ist. */
    fun open(context: Context, storeOrChain: String): Boolean {
        val r = retailerFor(storeOrChain) ?: return false
        val intent = appIntent(context, r) ?: Intent(Intent.ACTION_VIEW, Uri.parse(r.website))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Angebot antippen: Produktsuche des Marktes mit dem Namen öffnen – möglichst in der App
     * (wenn sie solche Links annimmt), sonst im Browser. Märkte ohne bekannte Suche: App/Website.
     */
    fun openSearch(context: Context, storeOrChain: String, query: String): Boolean {
        val r = retailerFor(storeOrChain) ?: return false
        val template = r.search ?: return open(context, storeOrChain)
        val uri = Uri.parse(template.replace("{q}", Uri.encode(query.trim())))
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Erst gezielt die installierte Händler-App fragen, ob sie den Link öffnen kann
        appIntent(context, r)?.let { it.component?.packageName ?: it.`package` }?.let { pkg ->
            val inApp = Intent(view).setPackage(pkg)
            if (inApp.resolveActivity(context.packageManager) != null &&
                runCatching { context.startActivity(inApp) }.isSuccess
            ) return true
        }
        return runCatching { context.startActivity(view) }.isSuccess || open(context, storeOrChain)
    }

    /** Text für den Knopf: „EDEKA-App öffnen“ oder „EDEKA-Website öffnen“. */
    fun buttonText(context: Context, storeOrChain: String): String? {
        val r = retailerFor(storeOrChain) ?: return null
        return if (appIntent(context, r) != null) "${r.label}-App öffnen" else "${r.label}-Website öffnen"
    }
}
