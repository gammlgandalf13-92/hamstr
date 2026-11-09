package de.hamstr.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Eine Prüfzeit: an diesen Wochentagen zu dieser Uhrzeit. */
data class CheckSlot(val days: Set<DayOfWeek>, val hour: Int, val minute: Int) {
    val time: String get() = "%02d:%02d".format(hour, minute)

    fun toJson(): JSONObject = JSONObject()
        .put("days", JSONArray(days.sorted().map { it.value }))
        .put("hour", hour).put("minute", minute)

    companion object {
        fun fromJson(o: JSONObject): CheckSlot {
            val arr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until arr.length()).mapNotNull { runCatching { DayOfWeek.of(arr.getInt(it)) }.getOrNull() }.toSet()
            return CheckSlot(days, o.optInt("hour", 7).coerceIn(0, 23), o.optInt("minute", 15).coerceIn(0, 59))
        }
    }
}

object Schedule {
    val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.values().toSet()
    val WEEKDAYS: Set<DayOfWeek> = ALL_DAYS - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY

    /** Nächster Zeitpunkt nach [now] – null, wenn keine Prüfzeit einen Tag hat. */
    fun nextRun(slots: List<CheckSlot>, now: LocalDateTime): LocalDateTime? {
        val active = slots.filter { it.days.isNotEmpty() }
        if (active.isEmpty()) return null
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val candidate = active.filter { date.dayOfWeek in it.days }
                .map { date.atTime(it.hour, it.minute) }
                .filter { it.isAfter(now) }
                .minOrNull()
            if (candidate != null) return candidate
        }
        return null
    }

    fun dayShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.GERMAN).trimEnd('.')

    /** "täglich", "werktags", "Mo, Mi, Fr" … */
    fun describeDays(days: Set<DayOfWeek>): String = when (days) {
        ALL_DAYS -> "täglich"
        WEEKDAYS -> "werktags"
        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "am Wochenende"
        emptySet<DayOfWeek>() -> "nie (kein Tag gewählt)"
        else -> days.sorted().joinToString(", ") { dayShort(it) }
    }

    fun describeNext(next: LocalDateTime?): String {
        if (next == null) return "Keine automatische Prüfung geplant."
        val today = LocalDateTime.now().toLocalDate()
        val day = when (next.toLocalDate()) {
            today -> "heute"
            today.plusDays(1) -> "morgen"
            else -> next.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN)
        }
        return "Nächste Prüfung: $day, ${"%02d:%02d".format(next.hour, next.minute)} Uhr"
    }
}

/** Geplante Prüfung: prüft und plant danach selbst den nächsten Termin. */
class ScheduledCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { Checker.run(applicationContext) }
        Scheduler.scheduleNext(applicationContext, replace = true)
        Result.success()
    }
}

// ------------------------------------------------------------------ Oberfläche
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSection(store: Store) {
    val ctx = LocalContext.current
    var slots by remember { mutableStateOf(store.schedule) }
    var editingTime by remember { mutableStateOf<Int?>(null) }
    var next by remember { mutableStateOf(Schedule.nextRun(slots, LocalDateTime.now())) }

    fun save(newSlots: List<CheckSlot>) {
        slots = newSlots
        store.schedule = newSlots
        Scheduler.scheduleNext(ctx, replace = true)
        next = Schedule.nextRun(newSlots, LocalDateTime.now())
    }

    Text("Wann prüfen?", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Text(
        "Lege fest, an welchen Tagen und um wie viel Uhr Hamstr nach Angeboten sucht. " +
            "Android kann den Zeitpunkt um einige Minuten verschieben.",
        style = MaterialTheme.typography.bodySmall,
    )

    slots.forEachIndexed { index, slot ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editingTime = index }) {
                        Text(slot.time + " Uhr", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Schedule.describeDays(slot.days), style = MaterialTheme.typography.bodySmall)
                    if (slots.size > 1) {
                        TextButton(onClick = { save(slots.filterIndexed { i, _ -> i != index }) }) { Text("✕") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.values().forEach { d ->
                        FilterChip(
                            selected = d in slot.days,
                            onClick = {
                                val days = if (d in slot.days) slot.days - d else slot.days + d
                                save(slots.mapIndexed { i, s -> if (i == index) s.copy(days = days) else s })
                            },
                            label = { Text(Schedule.dayShort(d)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Täglich" to Schedule.ALL_DAYS, "Werktags" to Schedule.WEEKDAYS).forEach { (label, days) ->
                        TextButton(onClick = {
                            save(slots.mapIndexed { i, s -> if (i == index) s.copy(days = days) else s })
                        }) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }

    OutlinedButton(onClick = {
        save(slots + CheckSlot(Schedule.ALL_DAYS, 18, 0))
        editingTime = slots.size - 1
    }) { Text("+ Weitere Prüfzeit") }

    Text(
        Schedule.describeNext(next),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    editingTime?.let { index ->
        slots.getOrNull(index)?.let { slot ->
            TimeDialog(slot.hour, slot.minute, onDismiss = { editingTime = null }) { h, m ->
                editingTime = null
                save(slots.mapIndexed { i, s -> if (i == index) s.copy(hour = h, minute = m) else s })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uhrzeit") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
