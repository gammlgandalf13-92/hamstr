package de.hamstr.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prüft beim Öffnen der App still, ob es eine neue Version gibt, und bietet sie dann an.
 * Ohne Netz oder bei Fehlern passiert einfach nichts.
 */
@Composable
fun UpdateOnStart() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by rememberSaveable { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        val latest = withContext(Dispatchers.IO) { runCatching { Updater.latest() }.getOrNull() }
        if (latest != null && latest.version > Updater.currentVersion(ctx)) info = latest
    }

    val update = info
    if (update == null || dismissed) return

    fun installNow() {
        if (!Updater.canInstall(ctx)) {
            status = "Erlaube Hamstr einmalig, Apps zu installieren – danach hier erneut auf „Jetzt aktualisieren“ tippen."
            Updater.openInstallPermission(ctx)
            return
        }
        busy = true
        progress = 0f
        status = "Lade herunter…"
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { Updater.download(ctx, update) { p -> progress = p } }
                status = "Installation wird gestartet…"
                Updater.install(ctx, apk)
            } catch (e: Exception) {
                status = e.message ?: "Download fehlgeschlagen."
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismissed = true },
        title = { Text("Neue Version verfügbar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${update.name} ist da (installiert: 1.0.${Updater.currentVersion(ctx)}).")
                if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(onClick = { installNow() }, enabled = !busy) { Text("Jetzt aktualisieren") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }, enabled = !busy) { Text("Später") }
        },
    )
}
