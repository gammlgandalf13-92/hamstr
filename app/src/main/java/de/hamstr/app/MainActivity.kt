package de.hamstr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureScheduled(this)
        setContent { HamstrTheme { HamstrApp() } }
    }
}

@Composable
fun HamstrTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFF4B860), secondary = Color(0xFFD9A066), tertiary = Color(0xFFF2D2A9))
    } else {
        lightColorScheme(primary = Color(0xFF8B5A2B), secondary = Color(0xFFB07A3E), tertiary = Color(0xFFF4B860))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HamstrApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }

    val workFlow = remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(Scheduler.NOW) }
    val work by workFlow.collectAsState(initial = emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(running) { if (!running) version++ }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val tabs = listOf("🛒" to "Treffer", "📋" to "Produkte", "⚙️" to "Einstellungen")
    Scaffold(
        topBar = { TopAppBar(title = { Text("🐹 Hamstr", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HitsScreen(store, version, running) { Scheduler.runNow(ctx) }
                1 -> ProductsScreen(store)
                else -> SettingsScreen(store)
            }
        }
    }

    UpdateOnStart()
}

// ------------------------------------------------------------------ Treffer
@Composable
fun HitsScreen(store: Store, version: Int, running: Boolean, onCheck: () -> Unit) {
    val ctx = LocalContext.current
    val hits = remember(version) { store.hits() }
    val log = remember(version) { store.log }
    val lastRun = remember(version) { store.lastRun }
    var showLog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCheck, enabled = !running) { Text(if (running) "Prüfe…" else "Jetzt prüfen") }
                if (running) {
                    Spacer(Modifier.size(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (lastRun == 0L) "Noch keine Prüfung gelaufen."
                else "Zuletzt geprüft: " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(lastRun)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hits.isEmpty() && lastRun != 0L) {
            item { Text("Gerade ist keines deiner Produkte im Angebot.", style = MaterialTheme.typography.bodyLarge) }
        }
        items(hits) { h ->
            Card(Modifier.fillMaxWidth().clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url)))
            }) {
                Row(Modifier.padding(12.dp)) {
                    ProductImage(h.image, 72.dp)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(h.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(h.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (log.isNotBlank()) {
            item {
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "Prüfprotokoll ausblenden" else "Prüfprotokoll anzeigen")
                }
                if (showLog) Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
