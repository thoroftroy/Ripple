package dev.wonderdesk.ripple

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** A repeating power action for one computer: wake, sleep or eco at a time, on chosen days. */
data class PowerSchedule(val id: String, val fp: String, val action: String, val hour: Int, val minute: Int, val days: Int) {
    /** days is a bit mask: bit 0 Monday ... bit 6 Sunday. */
    fun runsOn(dayOfWeek: Int) = days and (1 shl ((dayOfWeek + 5) % 7)) != 0
    fun label(): String {
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val d = if (days == 127) "every day" else if (days == 31) "weekdays" else if (days == 96) "weekends" else names.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
        val act = when (action) { "wake" -> "Wake"; "sleep" -> "Sleep"; "eco" -> "Eco mode"; "poweroff" -> "Shut down"; else -> action }
        return "$act at %d:%02d, %s".format(hour, minute, d)
    }
}

/**
 * Timed wake, sleep and eco for your computers. The phone keeps the schedule (so it works from anywhere the phone is) and sends the
 * command when the time comes: waking uses the same network or another awake computer of yours, sleeping needs the computer reachable.
 */
object PowerSchedules {
    private fun prefs(c: Context) = c.getSharedPreferences("power", Context.MODE_PRIVATE)
    fun all(c: Context): List<PowerSchedule> = runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); PowerSchedule(o.getString("id"), o.getString("fp"), o.getString("action"), o.getInt("h"), o.getInt("m"), o.getInt("d")) }
    }.getOrDefault(emptyList())
    private fun save(c: Context, l: List<PowerSchedule>) { prefs(c).edit().putString("list", JSONArray(l.map { JSONObject().put("id", it.id).put("fp", it.fp).put("action", it.action).put("h", it.hour).put("m", it.minute).put("d", it.days) }).toString()).apply(); arm(c) }
    fun add(c: Context, s: PowerSchedule) = save(c, all(c) + s)
    fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    fun nextRun(s: PowerSchedule, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        repeat(8) { if (cal.timeInMillis > from + 1000 && s.runsOn(cal.get(Calendar.DAY_OF_WEEK))) return cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1) }
        return from + 24 * 3_600_000L
    }

    /** Sets one alarm for the soonest schedule; it re-arms itself after it fires. */
    fun arm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7700, Intent(c, PowerAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val next = all(c).map { it to nextRun(it) }.minByOrNull { it.second } ?: return
        val exact = runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, pi)
    }

    /** Waits for a link to come up (up to ~25 s), then runs [action]. */
    fun perform(c: Context, fp: String, action: String): Boolean {
        Ripple.init(c)
        val comp = Store.computer(c, fp) ?: return false
        if (action == "wake") { Wake.send(comp); return true }
        val link = Ripple.link(fp) ?: return false
        link.start()
        var waited = 0
        while (link.state.value !is LinkState.Connected && waited < 25) { Thread.sleep(1000); waited++ }
        if (link.state.value !is LinkState.Connected) return false
        val r = link.ask(JSONObject().put("op", "power").put("action", action))
        if (r.isSuccess) { if (action == "sleep") Store.setSleptAt(c, fp, System.currentTimeMillis()) else if (action == "poweroff") Store.setPoweredOffAt(c, fp, System.currentTimeMillis()) }
        return r.isSuccess
    }
}

class PowerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Ripple.init(c)
        val pending = goAsync()
        Thread {
            try {
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                // Everything due in the last two minutes (the alarm can be a little late) runs now.
                PowerSchedules.all(c).filter { s -> s.runsOn(cal.get(Calendar.DAY_OF_WEEK)) && Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute); set(Calendar.SECOND, 0) }.timeInMillis.let { t -> now - t in 0..120_000 } }
                    .forEach { runCatching { PowerSchedules.perform(c, it.fp, it.action) } }
            } finally { PowerSchedules.arm(c); pending.finish() }
        }.start()
    }
}

class PowerBootReceiver : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { PowerSchedules.arm(c) } }

private fun statusLine(c: Computer, st: LinkState): Pair<String, Color> = when (st) {
    is LinkState.Connected -> "Awake and connected" to Color(0xFF2E9E44)
    is LinkState.Connecting -> st.step to Color(0xFFE39A12)
    is LinkState.Offline -> {
        val off = Store.poweredOffAt(Ripple.app, c.fp)
        (if (off > 0 && System.currentTimeMillis() - off < 24 * 3_600_000L) "Shut down from this phone" else Presence.short(c)) to Color(0xFF7A7F87)
    }
    LinkState.Idle -> "Checking…" to Color(0xFF7A7F87)
}

@Composable
fun PowerManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var computers by remember { mutableStateOf(Store.computers(context)) }
    var schedules by remember { mutableStateOf(PowerSchedules.all(context)) }
    var confirm by remember { mutableStateOf<Triple<Computer, String, String>?>(null) }
    var addFor by remember { mutableStateOf<Computer?>(null) }
    val toast: (String) -> Unit = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    // Look for every computer as soon as this screen opens, so each shows whether it is awake.
    LaunchedEffect(computers) { computers.forEach { runCatching { Ripple.link(it.fp)?.start() } } }
    fun run(c: Computer, action: String, doneText: String) {
        scope.launch {
            val link = Ripple.link(c.fp)
            val r = withContext(Dispatchers.IO) { link?.ask(JSONObject().put("op", "power").put("action", action)) }
            if (r == null) { toast("Couldn't reach ${c.name}"); return@launch }
            r.onSuccess {
                when (action) { "sleep" -> Store.setSleptAt(context, c.fp, System.currentTimeMillis()); "poweroff" -> Store.setPoweredOffAt(context, c.fp, System.currentTimeMillis()) }
                toast(doneText)
            }.onFailure { toast(friendlyError(it.message, c.name)) }
        }
    }
    WonderApp(appName = "Ripple", about = "Wake, sleep and manage your computers from anywhere.", title = "Power and wake", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (computers.isEmpty()) { EmptyState("No computers yet", "Add a computer first, then you can wake and sleep it from here.", icon = WonderIcons.DeveloperBoard); return@Column }
            computers.forEach { c ->
                val link = remember(c.fp) { Ripple.link(c.fp) }
                val st by (link?.state ?: kotlinx.coroutines.flow.MutableStateFlow<LinkState>(LinkState.Idle)).collectAsState()
                val (text, dot) = statusLine(c, st)
                val awake = st is LinkState.Connected
                // Other computers of yours that are awake can wake this one when you are away from home.
                val helpers = computers.filter { it.fp != c.fp && (Ripple.link(it.fp)?.state?.value is LinkState.Connected) }
                Panel(title = c.name) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(text, style = MaterialTheme.typography.titleSmall, color = wonderPalette().text)
                            Text(if (awake) "Sessions, files and power controls are available" else if (helpers.isNotEmpty()) "Can be woken from home, or through ${helpers.first().name}, which is awake" else "Can be woken from its own network (Wake-on-LAN)", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                        }
                    }
                    PanelDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WButton("Wake", { scope.launch { toast(withContext(Dispatchers.IO) { Wake.send(c) }) } }, Modifier.weight(1f), icon = WonderIcons.Bolt, kind = if (!awake) ButtonKind.Primary else ButtonKind.Secondary, enabled = !awake)
                            WButton("Sleep", { confirm = Triple(c, "sleep", "Sleep ${c.name}?") }, Modifier.weight(1f), icon = WonderIcons.Bedtime, enabled = awake)
                            WButton("Eco", { run(c, "eco", "${c.name} is in eco mode: screen off, low power, still reachable") }, Modifier.weight(1f), icon = WonderIcons.Speed, enabled = awake)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WButton("Lock", { run(c, "lock", "Locked ${c.name}") }, Modifier.weight(1f), icon = WonderIcons.Lock, enabled = awake)
                            WButton("Restart", { confirm = Triple(c, "reboot", "Restart ${c.name}?") }, Modifier.weight(1f), icon = WonderIcons.Refresh, enabled = awake)
                            WButton("Shut down", { confirm = Triple(c, "poweroff", "Shut down ${c.name}?") }, Modifier.weight(1f), icon = WonderIcons.Close, kind = ButtonKind.Danger, enabled = awake)
                        }
                        if (awake) WButton("Leave eco mode", { run(c, "eco_off", "${c.name} is back to normal power") }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain)
                        else WButton("Try to connect", { link?.reconnectNow() }, Modifier.fillMaxWidth(), kind = ButtonKind.Plain, icon = WonderIcons.Refresh)
                    }
                    PanelDivider()
                    val mine = schedules.filter { it.fp == c.fp }
                    mine.forEach { s -> ListRow(s.label(), subtitle = "Runs from this phone", icon = WonderIcons.Alarm, tint = Color(0xFF8E44AD), trailing = { TextButton(onClick = { PowerSchedules.remove(context, s.id); schedules = PowerSchedules.all(context) }) { Text("Delete") } }); PanelDivider() }
                    ListRow("Add a schedule", subtitle = "Wake it every morning, sleep it at night", icon = WonderIcons.Add, tint = Color(0xFF8E44AD), chevron = true, onClick = { addFor = c },
                        help = "The phone sends the command at the time you choose, so it must be on. Waking works from the computer's own network or through another awake computer of yours. Sleeping needs the computer reachable at that time.")
                }
            }
            Text("Wake-on-LAN has to be switched on in the computer's BIOS. Ripple turns it on in the operating system each time it sleeps or shuts down, and tells you if the card says it is not ready.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        }
    }
    confirm?.let { (c, action, title) ->
        ConfirmDialog(title, when (action) { "sleep" -> "Every session stops being reachable until it wakes."; "reboot" -> "It restarts now and comes back in a minute or two. Running programs close."; else -> "It turns off completely. Waking it again needs Wake-on-LAN from power-off, or its power button." }, title.substringBefore(' '),
            { confirm = null; run(c, action, when (action) { "sleep" -> "${c.name} is going to sleep"; "reboot" -> "${c.name} is restarting"; else -> "${c.name} is shutting down" }) }, { confirm = null }, danger = action != "sleep")
    }
    addFor?.let { c -> AddScheduleDialog(c, onDismiss = { addFor = null }) { s -> PowerSchedules.add(context, s); schedules = PowerSchedules.all(context); addFor = null } }
}

@Composable
private fun AddScheduleDialog(c: Computer, onDismiss: () -> Unit, onAdd: (PowerSchedule) -> Unit) {
    val context = LocalContext.current
    var action by remember { mutableStateOf("wake") }
    var hour by remember { mutableIntStateOf(7) }; var minute by remember { mutableIntStateOf(30) }
    var days by remember { mutableIntStateOf(31) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Schedule for ${c.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("wake" to "Wake", "sleep" to "Sleep", "eco" to "Eco", "poweroff" to "Shut down").forEach { (k, n) -> Chip(n, action == k, { action = k }) }
            }
            WButton("%d:%02d".format(hour, minute), { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, android.text.format.DateFormat.is24HourFormat(context)).show() }, Modifier.fillMaxWidth(), icon = WonderIcons.Alarm)
            Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, n -> Chip(n, days and (1 shl i) != 0, { days = days xor (1 shl i) }) }
            }
        }
    }, confirmButton = { TextButton(enabled = days != 0, onClick = { onAdd(PowerSchedule(System.currentTimeMillis().toString(), c.fp, action, hour, minute, days)) }) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun Modifier.horizontalScrollCompat(): Modifier = this
