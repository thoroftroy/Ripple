package dev.wonderdesk.ripple

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Controls watches owned by the computer, not by the phone. */
@Composable
fun RippleMonitorPane(link: Link, sessions: List<SessionInfo>, connected: Boolean, usage: Usage?, showStats: () -> Unit) {
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var mode by remember { mutableStateOf("claude") }
    var condition by remember { mutableStateOf("all") }
    var action by remember { mutableStateOf("notify") }
    var command by remember { mutableStateOf("") }
    var startNew by remember { mutableStateOf(false) }
    var runCommand by remember { mutableStateOf("") }
    var cwd by remember(link) { mutableStateOf(link.computer.home) }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(link, connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            val response = withContext(Dispatchers.IO) { link.ask(JSONObject().put("op", "monitor_list")) }
            response.onSuccess { obj ->
                val list = obj.optJSONArray("monitors") ?: JSONArray()
                items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            }.onFailure { message = friendlyError(it.message, link.computer.name) }
            delay(3000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Monitor", style = MaterialTheme.typography.titleLarge)
        if (usage != null) UsageStrip(usage, showStats)
        else Text("Reading computer usage", style = MaterialTheme.typography.bodySmall)
        Text("Select sessions", style = MaterialTheme.typography.titleSmall)
        sessions.forEach { session ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(session.id in selected, { checked ->
                    selected = if (checked) selected + session.id else selected - session.id
                })
                Column {
                    Text(session.name)
                    Text(session.program, style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WButton("Select all", { selected = sessions.map { it.id }.toSet() })
            WButton("Clear", { selected = emptySet() })
        }
        Text("What to watch", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Claude response", mode == "claude", { mode = "claude" })
            Chip("Successful command exit", mode == "exit_success", { mode = "exit_success" })
        }
        Text("A command must be started in its own session using the Run field. Claude completion is estimated from its screen.",
            style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(startNew, { checked -> startNew = checked; if (checked) mode = "exit_success" })
            Text("Start a new command and monitor its exit")
        }
        if (startNew) {
            Field(runCommand, { runCommand = it }, "Command to start now")
            Field(cwd, { cwd = it }, "Folder")
        }
        Text("When", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All selected", condition == "all", { condition = "all" })
            Chip("Any selected", condition == "any", { condition = "any" })
        }
        Text("Action", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Notify", action == "notify", { action = "notify" })
            Chip("Sleep computer", action == "sleep", { action = "sleep" })
            Chip("Run command", action == "command", { action = "command" })
        }
        if (action == "command") Field(command, { command = it }, "Command to run")
        WButton("Start monitoring", { confirm = true }, kind = ButtonKind.Primary,
            enabled = connected && (if (startNew) runCommand.isNotBlank() && mode == "exit_success" else selected.isNotEmpty())
                && (action != "command" || command.isNotBlank()))
        Text("Monitors", style = MaterialTheme.typography.titleSmall)
        if (items.isEmpty()) Text("No monitors yet", style = MaterialTheme.typography.bodySmall)
        items.forEach { item ->
            val state = item.optString("status")
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Monitor ${item.optInt("id")}: $state" +
                    if (!item.isNull("remaining")) " (${item.optInt("remaining")} seconds)" else "")
                val subjects = item.optJSONArray("sessions") ?: JSONArray()
                for (i in 0 until subjects.length()) {
                    val s = subjects.optJSONObject(i) ?: continue
                    Text("${s.optString("name")}: ${s.optString("status")}",
                        style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                }
                if (state == "watching" || state == "countdown") WButton("Cancel", {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            link.ask(JSONObject().put("op", "monitor_cancel").put("id", item.optInt("id")))
                        }
                        message = result.fold({ "Monitor cancelled" }, { friendlyError(it.message, link.computer.name) })
                    }
                })
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
    if (confirm) {
        val actionText = when (action) {
            "sleep" -> "The computer will actually sleep after a 30 second countdown."
            "command" -> "This command will run after a 30 second countdown:\n$command"
            else -> "A notification will be sent to connected phones."
        }
        val warning = if (mode == "claude") " Claude completion is estimated and may be wrong." else ""
        val runText = if (startNew) " This command starts now:\n$runCommand\n" else ""
        ConfirmDialog("Start this monitor?", "$actionText$warning$runText The monitor stays active when this phone disconnects.",
            "Start", {
                confirm = false
                val request = JSONObject().put("op", "monitor_start").put("sessions", JSONArray(if (startNew) emptyList<Int>() else selected.toList()))
                    .put("mode", mode).put("condition", condition).put("action", action).put("command", command)
                    .put("run_command", if (startNew) runCommand else "").put("cwd", cwd)
                scope.launch {
                    val result = withContext(Dispatchers.IO) { link.ask(request) }
                    message = result.fold({ "Monitor ${it.optInt("id")} started" }, { friendlyError(it.message, link.computer.name) })
                }
            }, { confirm = false }, danger = action != "notify")
    }
}
