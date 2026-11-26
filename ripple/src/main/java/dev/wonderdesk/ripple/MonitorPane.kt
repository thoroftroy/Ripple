package dev.wonderdesk.ripple

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val CLAUDE_TINT = Color(0xFFD9772B)
private val COMMAND_TINT = Color(0xFF2E9E44)
private val SLEEP_TINT = Color(0xFF5B5FD6)
private val NOTIFY_TINT = Color(0xFF1E7FE8)

private fun looksLikeClaude(s: SessionInfo) = listOf(s.program, s.title, s.name).any { "claude" in it.lowercase() }

/**
 * Watches that run on the computer, not the phone: "when Claude finishes in these sessions" or "when this command finishes",
 * then notify, put the computer to sleep or run a command. They keep going when the phone disconnects.
 */
@Composable
fun RippleMonitorPane(link: Link, sessions: List<SessionInfo>, connected: Boolean, usage: Usage?, showStats: () -> Unit) {
    val p = wonderPalette()
    val toast = LocalToast.current
    var mode by remember { mutableStateOf("claude") }               // claude or exit_success
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var condition by remember { mutableStateOf("all") }
    var action by remember { mutableStateOf("notify") }
    var command by remember { mutableStateOf("") }
    var runCommand by remember { mutableStateOf("") }
    var cwd by remember(link) { mutableStateOf(link.computer.home) }
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var showEarlier by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val claudeSessions = sessions.filter(::looksLikeClaude)
    // The terminal's keyboard has no business over this form; fields bring it back when tapped.
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) { view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }
    // The Claude sessions are picked for you the first time; you can change it.
    LaunchedEffect(sessions.map { it.id }) {
        if (selected.isEmpty() && claudeSessions.isNotEmpty()) selected = claudeSessions.map { it.id }.toSet()
        selected = selected.filter { id -> sessions.any { it.id == id } }.toSet()
    }
    fun refreshList() = scope.launch {
        withContext(Dispatchers.IO) { link.ask(JSONObject().put("op", "monitor_list")) }.onSuccess { obj ->
            val list = obj.optJSONArray("monitors") ?: JSONArray()
            items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.sortedByDescending { it.optLong("created") }
        }
    }
    LaunchedEffect(link, connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            val r = withContext(Dispatchers.IO) { link.ask(JSONObject().put("op", "monitor_list")) }
            r.onSuccess { obj ->
                val list = obj.optJSONArray("monitors") ?: JSONArray()
                items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.sortedByDescending { it.optLong("created") }
            }.onFailure { if (items == null) items = emptyList() }
            // Faster while something is counting down, so the seconds tick smoothly.
            delay(if (items.orEmpty().any { it.optString("status") == "countdown" }) 1000 else 3000)
        }
    }

    val ready = connected && when (mode) {
        "claude" -> selected.isNotEmpty()
        else -> runCommand.isNotBlank()
    } && (action != "command" || command.isNotBlank())
    val summary = buildString {
        append("When ")
        if (mode == "claude") {
            val n = selected.size
            append(when {
                n == 0 -> "Claude finishes"
                n == 1 -> "Claude finishes in ${sessions.find { it.id in selected }?.name ?: "1 session"}"
                condition == "all" -> "Claude finishes in all $n sessions"
                else -> "Claude finishes in any of $n sessions"
            })
        } else append(if (runCommand.isBlank()) "the command finishes" else "“${runCommand.trim()}” finishes without errors")
        append(", ")
        append(when (action) {
            "sleep" -> "put ${link.computer.name} to sleep"
            "command" -> if (command.isBlank()) "run a command" else "run “${command.trim()}”"
            else -> "send a notification to this phone"
        })
        append(".")
    }
    fun start() {
        starting = true
        val request = JSONObject().put("op", "monitor_start").put("sessions", JSONArray(if (mode == "claude") selected.toList() else emptyList<Int>()))
            .put("mode", mode).put("condition", condition).put("action", action).put("command", command.trim())
            .put("run_command", if (mode == "exit_success") runCommand.trim() else "").put("cwd", cwd)
        scope.launch {
            val result = withContext(Dispatchers.IO) { link.ask(request) }
            starting = false
            result.onSuccess { toast("Watching. It keeps going even if this phone disconnects"); if (mode == "exit_success") runCommand = ""; refreshList() }
                .onFailure { toast(friendlyError(it.message, link.computer.name)) }
        }
    }

    val list = items.orEmpty()
    val active = list.filter { it.optString("status") in setOf("watching", "countdown", "running") }
    val earlier = list.filter { it !in active }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // What is being watched right now comes first: a countdown is something you may want to stop.
        if (active.isNotEmpty()) Panel(title = "Watching now") {
            active.forEachIndexed { i, item ->
                MonitorCard(item, link.computer.name, Modifier.panelItem(i, active.size)) {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { link.ask(JSONObject().put("op", "monitor_cancel").put("id", item.optInt("id"))) }
                        toast(r.fold({ "Stopped" }, { friendlyError(it.message, link.computer.name) })); refreshList()
                    }
                }
                if (i < active.lastIndex) PanelDivider(0.dp)
            }
        }

        Panel(title = "New monitor", footer = "Runs on ${link.computer.name}, so it keeps watching when this phone is off or away. Claude is judged from its screen, so now and then it may guess wrong.") {
            Column(Modifier.panelItem(0, 1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StepTitle(1, "Watch for")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceTile("Claude finishes", "Replies, or stops at a usage limit", WonderIcons.Lightbulb, CLAUDE_TINT, mode == "claude", Modifier.weight(1f)) { mode = "claude" }
                    ChoiceTile("A command finishes", "Starts it now and waits for it to end", WonderIcons.Code, COMMAND_TINT, mode == "exit_success", Modifier.weight(1f)) { mode = "exit_success" }
                }

                StepTitle(2, if (mode == "claude") "In which sessions" else "Which command")
                if (mode == "claude") {
                    if (sessions.isEmpty()) Text("No sessions are open on ${link.computer.name}. Start Claude in a session, then come back here.", style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                    else {
                        val ordered = sessions.sortedByDescending(::looksLikeClaude)
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, p.panelBorder, RoundedCornerShape(12.dp))) {
                            ordered.forEachIndexed { i, s ->
                                val on = s.id in selected
                                Row(Modifier.fillMaxWidth().background(if (on) p.accent.copy(alpha = .10f) else Color.Transparent)
                                    .clickable { selected = if (on) selected - s.id else selected + s.id }.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Tick(on)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name.ifBlank { "Session ${s.id}" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(s.program, s.cwd.replaceFirst(link.computer.home, "~")).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (looksLikeClaude(s)) Badge("Claude", CLAUDE_TINT)
                                }
                                if (i < ordered.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(p.separator))
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (claudeSessions.isNotEmpty() && claudeSessions.size < sessions.size) Chip("Just Claude", selected == claudeSessions.map { it.id }.toSet(), { selected = claudeSessions.map { it.id }.toSet() })
                            Chip("All", selected.size == sessions.size, { selected = sessions.map { it.id }.toSet() })
                            Chip("None", selected.isEmpty(), { selected = emptySet() })
                        }
                        AnimatedVisibility(selected.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Act when", style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                                Chip("all are done", condition == "all", { condition = "all" })
                                Chip("any is done", condition == "any", { condition = "any" })
                            }
                        }
                    }
                } else {
                    Field(runCommand, { runCommand = it }, "Command, like ./build.sh or cargo test")
                    Field(cwd, { cwd = it }, "In folder")
                    Text("It opens in a new session you can watch, and counts as finished when it ends without an error.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                }

                StepTitle(3, "Then")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceTile("Notify me", "On this phone", WonderIcons.Notifications, NOTIFY_TINT, action == "notify", Modifier.weight(1f)) { action = "notify" }
                    ChoiceTile("Sleep", "Put ${link.computer.name} to sleep", WonderIcons.Bedtime, SLEEP_TINT, action == "sleep", Modifier.weight(1f)) { action = "sleep" }
                    ChoiceTile("Run", "A command of yours", WonderIcons.PlayArrow, COMMAND_TINT, action == "command", Modifier.weight(1f)) { action = "command" }
                }
                if (action == "command") Field(command, { command = it }, "Command to run, like systemctl poweroff")
                if (action != "notify") Text("You get 30 seconds to cancel first, from here or the notification.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)

                // The whole thing in one plain sentence before you start it.
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accent.copy(alpha = .08f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(WonderIcons.Visibility, null, Modifier.size(20.dp), tint = p.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                WButton(if (starting) "Starting…" else "Start watching", { if (action == "notify") start() else confirm = true }, Modifier.fillMaxWidth(),
                    icon = WonderIcons.Visibility, kind = ButtonKind.Primary, enabled = ready && !starting)
                if (!connected) Text("Connect to ${link.computer.name} to start a monitor.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }
        }

        if (earlier.isNotEmpty()) Panel(title = "Earlier", trailing = {
            Text(if (showEarlier) "Hide" else "Show ${earlier.size}", Modifier.clickable { showEarlier = !showEarlier }.padding(6.dp), color = p.accent, style = MaterialTheme.typography.labelLarge)
        }) {
            if (showEarlier) earlier.take(10).let { shown ->
                shown.forEachIndexed { i, item ->
                    MonitorCard(item, link.computer.name, Modifier.panelItem(i, shown.size), onCancel = null)
                    if (i < shown.lastIndex) PanelDivider(0.dp)
                }
            }
        }
        if (items == null && connected) LoadingStatus("Asking ${link.computer.name} what it is watching…", compact = true)
    }

    if (confirm) {
        val what = if (action == "sleep") "${link.computer.name} goes to sleep" else "this runs on ${link.computer.name}:\n${command.trim()}"
        ConfirmDialog("Start this monitor?", "$summary\n\nWhen it happens you get 30 seconds to cancel, then $what" +
            (if (mode == "claude") "\n\nClaude is judged from its screen, so it can occasionally be wrong." else ""),
            "Start", { confirm = false; start() }, { confirm = false }, danger = true)
    }
}

@Composable
private fun StepTitle(n: Int, text: String) {
    val p = wonderPalette()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Text("$n", color = p.onAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** A big tappable option with an icon, like a radio button you can't miss. */
@Composable
private fun ChoiceTile(title: String, detail: String, icon: ImageVector, tint: Color, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = wonderPalette()
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (on) tint.copy(alpha = .14f) else p.window.copy(alpha = .6f))
        .border(if (on) 2.dp else 1.dp, if (on) tint else p.panelBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .padding(10.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = if (on) FontWeight.SemiBold else null, maxLines = 2)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tick(on: Boolean) {
    val p = wonderPalette()
    Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) p.accent else Color.Transparent).border(2.dp, if (on) p.accent else p.panelBorder, CircleShape),
        contentAlignment = Alignment.Center) { if (on) Icon(WonderIcons.Check, null, Modifier.size(15.dp), tint = p.onAccent) }
}

@Composable
private fun Badge(text: String, tint: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = .15f)).padding(horizontal = 7.dp, vertical = 2.dp),
        color = tint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
}

/** One monitor: what it waits for, how each session is doing, and during the 30 second countdown a big Cancel. */
@Composable
private fun MonitorCard(item: JSONObject, computer: String, modifier: Modifier, onCancel: (() -> Unit)?) {
    val p = wonderPalette()
    val status = item.optString("status")
    val action = item.optString("action")
    val claude = item.optString("mode") == "claude"
    val remaining = if (item.isNull("remaining")) null else item.optInt("remaining")
    val subjects = item.optJSONArray("sessions") ?: JSONArray()
    val names = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
    val (statusText, statusTint, statusIcon) = when (status) {
        "watching" -> Triple("Watching", p.accent, WonderIcons.Visibility)
        "countdown" -> Triple(when (action) { "sleep" -> "Sleeping in ${remaining ?: 0} s"; "command" -> "Running in ${remaining ?: 0} s"; else -> "Notifying" }, Color(0xFFE8831E), WonderIcons.HourglassBottom)
        "running" -> Triple("Doing it now", Color(0xFFE8831E), WonderIcons.Bolt)
        "completed" -> Triple("Done", p.success, WonderIcons.CheckCircle)
        "failed" -> Triple("Couldn't finish", p.danger, WonderIcons.Error)
        "cancelled" -> Triple("Stopped", p.secondaryText, WonderIcons.Close)
        else -> Triple(status.replaceFirstChar { it.uppercase() }, p.secondaryText, WonderIcons.Info)
    }
    val what = (if (claude) "Claude in " + (names.ifEmpty { listOf("${subjects.length()} sessions") }.joinToString(", ")) else "Command " + (names.firstOrNull() ?: "")) +
        " → " + when (action) { "sleep" -> "sleep $computer"; "command" -> "run a command"; else -> "notify" }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(statusTint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(statusIcon, null, Modifier.size(18.dp), tint = statusTint)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(statusText, style = MaterialTheme.typography.titleSmall, color = statusTint, fontWeight = FontWeight.SemiBold)
                Text(what, style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onCancel != null && (status == "watching" || status == "countdown"))
                WButton(if (status == "countdown") "Cancel" else "Stop", onCancel, kind = if (status == "countdown") ButtonKind.Primary else ButtonKind.Plain)
        }
        if (status == "countdown" && remaining != null)
            LinearProgressIndicator(progress = { (remaining / 30f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFFE8831E), trackColor = p.panelBorder)
        // Each session and what Ripple thinks it is doing.
        if (subjects.length() > 0) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until subjects.length()) {
                val s = subjects.optJSONObject(i) ?: continue
                val st = s.optString("status")
                val done = listOf("finished", "succeeded", "limit").any { it in st.lowercase() }
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (done) p.success.copy(alpha = .14f) else p.window.copy(alpha = .7f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (done) p.success else p.accent))
                    Spacer(Modifier.width(6.dp))
                    Text(s.optString("name") + if (st.isNotBlank()) ": $st" else "", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
