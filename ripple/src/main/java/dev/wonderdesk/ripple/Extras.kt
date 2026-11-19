package dev.wonderdesk.ripple

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ripple's own section of the Settings page. */
@Composable
fun RippleSettings() {
    val context = LocalContext.current
    var stay by remember { mutableStateOf(Store.stayConnected(context)) }
    var alerts by remember { mutableStateOf(Store.alerts(context)) }
    var clipSync by remember { mutableStateOf(Store.clipSync(context)) }
    var showFiles by remember { mutableStateOf(Store.showFiles(context)) }
    var showPower by remember { mutableStateOf(Store.showPower(context)) }
    var showTabs by remember { mutableStateOf(Store.showSessionTabs(context)) }
    var usageBar by remember { mutableStateOf(Store.showUsage(context)) }
    var keys by remember { mutableStateOf(Store.extraKeys(context)) }
    var vibrate by remember { mutableStateOf(Store.vibrateKeys(context)) }
    var screenOn by remember { mutableStateOf(Store.keepScreenOn(context)) }
    var scheme by remember { mutableStateOf(Store.scheme(context)) }
    var font by remember { mutableIntStateOf(Store.fontSize(context)) }
    var quick by remember { mutableStateOf(Store.quickCommands(context)) }
    var editing by remember { mutableStateOf<Int?>(null) }

    Panel(title = "Features", footer = "Choose what shows up. Changes apply the next time you open a computer.") {
        SwitchRow("Share the clipboard with the computer", clipSync, { clipSync = it; Store.setClipSync(context, it) }, subtitle = "Text you copy on either side can be pasted on the other, with a history list", icon = WonderIcons.ContentPaste)
        PanelDivider()
        SwitchRow("Files button", showFiles, { showFiles = it; Store.setShowFiles(context, it) }, subtitle = "Browse and move files in the top bar", icon = WonderIcons.Folder)
        PanelDivider()
        SwitchRow("Sleep, lock and wake", showPower, { showPower = it; Store.setShowPower(context, it) }, subtitle = "Power actions in the menu", icon = WonderIcons.Bolt)
        PanelDivider()
        SwitchRow("Session tabs", showTabs, { showTabs = it; Store.setShowSessionTabs(context, it) }, subtitle = "The row of tabs above the terminal", icon = WonderIcons.Tune)
    }
    Panel(title = "Connection") {
        SwitchRow("Show computer usage in the top bar", usageBar, { usageBar = it; Store.setShowUsage(context, it) },
            subtitle = "Processor, memory, graphics and temperature of the computer. Tap it for more.", icon = WonderIcons.Tune)
        PanelDivider()
        SwitchRow("Stay connected in the background", stay, { stay = it; Store.setStayConnected(context, it) },
            subtitle = "Keeps your computer's sessions one tap away and lets them alert you. Uses a little battery and data.", icon = WonderIcons.Sync)
        PanelDivider()
        SwitchRow("Session alerts", alerts, { alerts = it; Store.setAlerts(context, it) },
            subtitle = "When a session rings the bell, sends a message, or goes quiet after working (a job finished, or a program is waiting for you)", icon = WonderIcons.Notifications)
    }
    Panel(title = "Terminal") {
        ListRow("Colours", subtitle = if (scheme == "auto") "Match my look" else Schemes.all[scheme]?.label, icon = WonderIcons.Palette)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Match my look", scheme == "auto", { scheme = "auto"; Store.setScheme(context, "auto") })
            Schemes.all.forEach { (k, s) -> Chip(s.label, scheme == k, { scheme = k; Store.setScheme(context, k) }) }
        }
        PanelDivider()
        ListRow("Text size", subtitle = "$font (pinch the terminal to change it too)", icon = WonderIcons.FormatSize, trailing = {
            Row {
                WButton("−", { font = (font - 1).coerceAtLeast(6); Store.setFontSize(context, font) }, kind = ButtonKind.Plain)
                WButton("+", { font = (font + 1).coerceAtMost(40); Store.setFontSize(context, font) }, kind = ButtonKind.Plain)
            }
        })
        PanelDivider()
        SwitchRow("Extra keys row", keys, { keys = it; Store.setExtraKeys(context, it) }, subtitle = "Esc, Tab, Ctrl, Alt, arrows and more above the keyboard", icon = WonderIcons.Tune)
        PanelDivider()
        var suggestions by remember { mutableStateOf(Store.keyboardSuggestions(context)) }
        var autoSug by remember { mutableStateOf(Store.autoSuggestions(context)) }
        SwitchRow("Autocorrect in chat apps", autoSug, { autoSug = it; Store.setAutoSuggestions(context, it) },
            subtitle = "Turns on by itself inside full screen apps like chats, and stays off at the shell prompt", icon = WonderIcons.Edit)
        PanelDivider()
        SwitchRow("Keyboard suggestions everywhere", suggestions, { suggestions = it; Store.setKeyboardSuggestions(context, it) },
            subtitle = if (suggestions) "Autocorrect is on. Great for prose but risky for commands" else "No fixing while you type, so commands stay exactly as typed", icon = WonderIcons.Edit)
        PanelDivider()
        SwitchRow("Vibrate on key press", vibrate, { vibrate = it; Store.setVibrateKeys(context, it) }, icon = WonderIcons.TouchApp)
        PanelDivider()
        SwitchRow("Keep the screen on", screenOn, { screenOn = it; Store.setKeepScreenOn(context, it) }, subtitle = "While a terminal is open", icon = WonderIcons.WbSunny)
        PanelDivider()
        var bell by remember { mutableStateOf(Store.bellVibrate(context)) }
        SwitchRow("Vibrate on the terminal bell", bell, { bell = it; Store.setBellVibrate(context, it) }, subtitle = "When a program rings the bell, like a finished build", icon = WonderIcons.Notifications)
        PanelDivider()
        var cur by remember { mutableIntStateOf(Store.cursorStyle(context)) }
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Cursor shape", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Block", "Underline", "Bar").forEachIndexed { i, n -> Chip(n, cur == i, { cur = i; Store.setCursorStyle(context, i) }) } }
            Text("Applies to terminals you open next.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText) }
        PanelDivider()
        var sb by remember { mutableIntStateOf(Store.scrollback(context)) }
        Column(Modifier.padding(16.dp, 10.dp)) { Text("Scrollback: $sb lines", style = MaterialTheme.typography.labelLarge)
            Slider(sb.toFloat(), { sb = ((it / 500).toInt() * 500).coerceAtLeast(500) }, valueRange = 500f..20000f, onValueChangeFinished = { Store.setScrollback(context, sb) })
            Text("Applies to terminals you open next. More lines keep more history and use more memory.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText) }
    }
    Panel(title = "Quick commands", footer = "Commands you start often, offered when you open a new session. Each one runs in the folder you give it.") {
        if (quick.isEmpty()) ListRow("None yet", subtitle = "For example “btop”, or your build script in your project folder", icon = WonderIcons.PlayArrow)
        quick.forEachIndexed { i, q ->
            ListRow(q.label.ifBlank { q.command }, subtitle = listOf(q.command, q.folder).filter { it.isNotBlank() }.joinToString(" · "), icon = WonderIcons.PlayArrow, onClick = { editing = i })
            PanelDivider()
        }
        ListRow("Add a quick command", icon = WonderIcons.Add, onClick = { editing = -1 })
    }
    editing?.let { idx ->
        val q = quick.getOrNull(idx)
        var label by remember { mutableStateOf(q?.label ?: "") }
        var cmd by remember { mutableStateOf(q?.command ?: "") }
        var folder by remember { mutableStateOf(q?.folder ?: "") }
        WonderDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (q == null) "New quick command" else "Edit quick command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(label, { label = it }, "Name")
                    Field(cmd, { cmd = it }, "Command")
                    Field(folder, { folder = it }, "Folder on the computer (optional)")
                    if (q != null) WButton("Delete", {
                        quick = quick.filterIndexed { i, _ -> i != idx }; Store.saveQuickCommands(context, quick); editing = null
                    }, kind = ButtonKind.Danger)
                }
            },
            confirmButton = {
                WButton("Save", {
                    val item = QuickCommand(label.trim(), cmd.trim(), folder.trim())
                    quick = if (q == null) quick + item else quick.mapIndexed { i, x -> if (i == idx) item else x }
                    Store.saveQuickCommands(context, quick); editing = null
                }, kind = ButtonKind.Primary, enabled = cmd.isNotBlank())
            },
            dismissButton = { WButton("Cancel", { editing = null }, kind = ButtonKind.Plain) },
        )
    }
}

/** "Send to computer" from any app's share sheet. */
class ShareActivity : WonderActivity() {
    private lateinit var uris: List<Uri>
    private var text: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ripple.init(this)
        Notifier.channels(this)
        uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        text = intent.getStringExtra(Intent.EXTRA_TEXT)
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Content() {
            val computers = remember { Store.computers(this) }
            var status by remember { mutableStateOf<String?>(null) }
            var progress by remember { mutableStateOf<Float?>(null) }
            var detail by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            fun send(c: Computer) {
                scope.launch {
                    status = "Connecting to ${c.name}…"
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val list = uris.ifEmpty {
                                // Plain text becomes a .txt file.
                                val f = java.io.File(cacheDir, "Shared text ${System.currentTimeMillis()}.txt").apply { writeText(text ?: "") }
                                listOf(Uri.fromFile(f))
                            }
                            list.forEachIndexed { i, u ->
                                val (name, _) = Transfers.displayName(this@ShareActivity, u)
                                status = "Sending $name (${i + 1} of ${list.size})"
                                Transfers.upload(this@ShareActivity, c.fp, u, null, onProgress = { t, pct -> detail = t; progress = pct / 100f }, notify = false)
                            }
                            list.size
                        }
                    }
                    r.onSuccess { n -> android.widget.Toast.makeText(this@ShareActivity, "Sent $n file${if (n == 1) "" else "s"} to ${c.name}'s Ripple folder", android.widget.Toast.LENGTH_LONG).show(); finish() }
                        .onFailure { status = null; android.widget.Toast.makeText(this@ShareActivity, it.message ?: "Couldn't send", android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            LaunchedEffect(Unit) { if (computers.size == 1) send(computers[0]) }
            WonderApp(appName = "Ripple", about = "Ripple", title = "Send to computer", onBack = { finish() }) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val s = status
                    when {
                        s != null -> Panel { LoadingStatus(s, Modifier.fillMaxWidth(), progress = progress, detail = detail) }
                        computers.isEmpty() -> EmptyState("No computers yet", "Pair a computer in Ripple first.", icon = WonderIcons.DeveloperBoard)
                        else -> Panel(title = "Send ${if (uris.size > 1) "${uris.size} files" else "to"}…", footer = "Files land in the computer's Ripple folder.") {
                            computers.forEachIndexed { i, c ->
                                ListRow(c.name, subtitle = c.sharedDir, icon = WonderIcons.DeveloperBoard, chevron = true, onClick = { send(c) })
                                if (i < computers.lastIndex) PanelDivider()
                            }
                        }
                    }
                }
            }
    }
}
