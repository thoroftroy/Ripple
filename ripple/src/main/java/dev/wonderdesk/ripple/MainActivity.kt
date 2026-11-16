package dev.wonderdesk.ripple

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.termux.view.TerminalView
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class Screen {
    data object Home : Screen()
    data class Pair(val uri: String? = null) : Screen()
    data class Remote(val fp: String, val session: Int = -1) : Screen()
    data class Files(val fp: String, val path: String = "") : Screen()
    data object Phone : Screen()
}

class MainActivity : WonderActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        Ripple.init(this)
        handle(intent, first = true)
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Composable
    override fun Content() = App()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, first = false)
    }

    private fun handle(intent: Intent?, first: Boolean) {
        val data = intent?.data
        if (data?.scheme == "ripple") { screen = Screen.Pair(data.toString()); return }
        val fp = intent?.getStringExtra("computer")
        if (fp != null && Store.computer(this, fp) != null) { screen = Screen.Remote(fp, intent.getIntExtra("session", -1)); return }
        if (first) {
            // Open where you left off.
            val last = Store.lastComputer(this)
            if (last != null && Store.computer(this, last) != null) screen = Screen.Remote(last)
        }
    }

    override fun onStart() {
        super.onStart()
        RippleService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        visibleSession = null
        // Keep connections in the background (alerts) if allowed; otherwise let them go.
        if (Ripple.active().isNotEmpty()) {
            if (Store.stayConnected(this)) RippleService.start(this)
            else Ripple.active().forEach { it.stop() }
        }
    }

    @Composable
    private fun App() {
        when (val s = screen) {
            Screen.Home -> HomeScreen(open = { screen = it })
            is Screen.Pair -> PairScreen(s.uri, onDone = { fp -> screen = if (fp != null) Screen.Remote(fp) else Screen.Home })
            is Screen.Remote -> RemoteScreen(s.fp, s.session, open = { screen = it })
            is Screen.Files -> FilesScreen(s.fp, s.path, onBack = { screen = Screen.Remote(s.fp) })
            Screen.Phone -> PhoneShellScreen(onBack = { screen = Screen.Home })
        }
    }

    companion object {
        /** Computer + session currently on screen, so alerts for it aren't duplicated as notifications. */
        @Volatile var visibleSession: kotlin.Pair<String, Int>? = null
    }
}

/* ------------------------------------------------------------------ Home */

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val context = LocalContext.current
    var computers by remember { mutableStateOf(Store.computers(context)) }
    var menuFor by remember { mutableStateOf<Computer?>(null) }
    var confirmForget by remember { mutableStateOf<Computer?>(null) }
    WonderApp(
        appName = "Ripple",
        about = "Your computer's terminal on your phone: run programs there, keep them going when you disconnect, and pick up from anywhere. Files move both ways. Everything connects directly between your own devices.",
        actions = { ToolbarButton(WonderIcons.Add, "Add a computer", { open(Screen.Pair()) }) },
        settings = { RippleSettings() },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (computers.isEmpty()) {
                EmptyState(
                    "Add your computer",
                    "Install Ripple on your Linux computer, open it and choose “Pair a phone”. Then scan the code here.",
                    action = { WButton("Add a computer", { open(Screen.Pair()) }, kind = ButtonKind.Primary, icon = WonderIcons.Add) }
                )
                Panel(title = "Get Ripple for Linux") {
                    ListRow("github.com/thoroftroy/Ripple", subtitle = "Download the Linux release and run install.sh (no root needed)", icon = WonderIcons.Download, onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/thoroftroy/Ripple/releases")))
                    })
                }
            } else {
                Panel(title = "Computers") {
                    computers.forEachIndexed { i, c ->
                        val link = remember(c.fp) { Ripple.link(c.fp) }
                        val st by (link?.state ?: kotlinx.coroutines.flow.MutableStateFlow(LinkState.Idle)).collectAsState()
                        val sub = when (val x = st) {
                            is LinkState.Connected -> "Connected · ${x.via}"
                            is LinkState.Connecting -> x.step
                            is LinkState.Offline -> x.message
                            LinkState.Idle -> if (c.hasDoorbell) "Reachable at home and away" else "Reachable on its home network"
                        }
                        Box {
                            ListRow(c.name, subtitle = sub, icon = WonderIcons.DeveloperBoard, chevron = true,
                                onLongClick = { menuFor = c }, onClick = { open(Screen.Remote(c.fp)) })
                            DropdownMenu(menuFor == c, { menuFor = null }) {
                                DropdownMenuItem({ Text("Files") }, { menuFor = null; open(Screen.Files(c.fp)) })
                                DropdownMenuItem({ Text("Forget this computer") }, { menuFor = null; confirmForget = c })
                            }
                        }
                        if (i < computers.lastIndex) PanelDivider()
                    }
                }
                WButton("Add another computer", { open(Screen.Pair()) }, Modifier.fillMaxWidth(), icon = WonderIcons.Add)
            }
            Panel(title = "This phone", footer = "A command line for Android itself, with wd commands for managing your Wonderdesk apps.") {
                ListRow("Android command line", subtitle = "Shell on this phone", icon = WonderIcons.PhoneAndroid, chevron = true, onClick = { open(Screen.Phone) })
            }
        }
    }
    confirmForget?.let { c ->
        ConfirmDialog("Forget ${c.name}?", "This phone stops connecting to it. To unpair on the computer too, remove this phone under Settings › Phones there.", "Forget", {
            Ripple.forget(c.fp); Store.removeComputer(context, c.fp); computers = Store.computers(context); confirmForget = null
        }, { confirmForget = null }, danger = true)
    }
}

/* ------------------------------------------------------------------ Remote terminal */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(fp: String, startSession: Int, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val link = remember(fp) { Ripple.link(fp) }
    if (link == null) { LaunchedEffect(Unit) { open(Screen.Home) }; return }
    LaunchedEffect(fp) { link.start(); Store.setLastComputer(context, fp) }
    val state by link.state.collectAsState()
    val sessions by link.sessions.collectAsState()
    var current by rememberSaveable(fp) { mutableIntStateOf(if (startSession >= 0) startSession else Store.lastSession(context, fp)) }
    var termView by remember { mutableStateOf<TerminalView?>(null) }
    val client = remember { ScreenClient(context) { termView } }
    val mods = remember { Modifiers() }
    var showNew by remember { mutableStateOf(false) }
    var tabMenu by remember { mutableStateOf<SessionInfo?>(null) }
    var renaming by remember { mutableStateOf<SessionInfo?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    // Pick a session: the one asked for, else the most recent.
    LaunchedEffect(sessions) {
        // The one you were on is gone: go to the session used most recently (least idle), not simply the newest.
        if (sessions.isNotEmpty() && sessions.none { it.id == current }) current = sessions.minByOrNull { it.idleSecs }!!.id
        if (sessions.isEmpty() && state is LinkState.Connected) current = -1
    }
    val info = sessions.firstOrNull { it.id == current }
    val terminal = remember(current, info != null) {
        if (info != null) link.terminal(info.id, info.name, client).also { it.onState = { refresh++ } } else null
    }
    DisposableEffect(current) {
        MainActivity.visibleSession = fp to current
        if (current >= 0) Store.setLastSession(context, fp, current)
        onDispose { }
    }
    // Put the keyboard away when leaving the terminal.
    DisposableEffect(Unit) {
        onDispose { termView?.let { v -> context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) } }
    }
    val connected = state is LinkState.Connected
    val subtitle = when (val s = state) {
        is LinkState.Connected -> s.via.replaceFirstChar { it.uppercase() }
        is LinkState.Connecting -> s.step
        is LinkState.Offline -> "Offline"
        LinkState.Idle -> null
    }

    WonderApp(
        appName = "Ripple",
        about = "Ripple",
        title = link.computer.name,
        subtitle = subtitle,
        onBack = { open(Screen.Home) },
        actions = {
            ToolbarButton(WonderIcons.Add, "New session", { showNew = true }, enabled = connected)
            ToolbarButton(WonderIcons.Folder, "Files", { open(Screen.Files(fp, info?.cwd ?: "")) })
        },
        menu = listOfNotNull(
            info?.let { WonderMenuItem("Rename session", WonderIcons.Edit) { renaming = it } },
            info?.let { WonderMenuItem("End session", WonderIcons.Close, danger = true) { scope.launch(Dispatchers.IO) { runCatching { link.request(JSONObject().put("op", "kill").put("id", it.id)) } } } },
            WonderMenuItem("Reconnect", WonderIcons.Refresh) { link.reconnectNow() },
        ),
        settings = { RippleSettings() },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Connection banner while not connected.
            when (val s = state) {
                is LinkState.Connecting -> Banner { LoadingStatus(s.step, compact = true, detail = if (s.attempt > 1) "Attempt ${s.attempt}" else null) }
                is LinkState.Offline -> Banner {
                    var left by remember(s) { mutableLongStateOf((s.retryAt - System.currentTimeMillis()) / 1000) }
                    LaunchedEffect(s) { while (left > 0) { delay(1000); left = (s.retryAt - System.currentTimeMillis()) / 1000 } }
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(WonderIcons.Warning, null, tint = wonderPalette().warning)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.message, style = MaterialTheme.typography.bodyMedium)
                            Text(if (left > 0) "Trying again in ${left}s" else "Trying again…", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                        }
                        WButton("Try now", { link.reconnectNow() })
                    }
                }
                else -> {}
            }
            // Session tabs.
            if (sessions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    sessions.forEach { s ->
                        Box {
                            SessionTab(s, s.id == current, onClick = { current = s.id }, onLong = { tabMenu = s })
                            DropdownMenu(tabMenu == s, { tabMenu = null }) {
                                DropdownMenuItem({ Text("Rename") }, { tabMenu = null; renaming = s })
                                DropdownMenuItem({ Text("Files in this folder") }, { tabMenu = null; open(Screen.Files(fp, s.cwd)) })
                                DropdownMenuItem({ Text("End session") }, {
                                    tabMenu = null
                                    scope.launch(Dispatchers.IO) { runCatching { link.request(JSONObject().put("op", "kill").put("id", s.id)) } }
                                })
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    SessionTabAdd { showNew = true }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (terminal != null) {
                    key(terminal) {
                        TerminalPane(terminal.session, Modifier.fillMaxSize(), mods, onViewReady = { termView = it })
                    }
                    if (!terminal.connected && !terminal.isEnded && state !is LinkState.Connected) {
                        Text(
                            "Reconnecting… keys you type are sent when it's back",
                            Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(50))
                                .background(Color(0xE0202326)).padding(horizontal = 14.dp, vertical = 6.dp),
                            color = Color.White, fontSize = 12.sp
                        )
                    }
                    if (terminal.isEnded) {
                        Banner(Modifier.align(Alignment.BottomCenter)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("This session has ended.", Modifier.weight(1f))
                                WButton("Close", { link.closeTerminal(terminal.sessionId); current = -1 })
                            }
                        }
                    }
                } else if (connected) {
                    EmptyState("No sessions running", "Start one to use ${link.computer.name}'s terminal. It keeps running when you leave.", icon = WonderIcons.Code,
                        action = { WButton("Start a session", { showNew = true }, kind = ButtonKind.Primary, icon = WonderIcons.Add) })
                } else if (state !is LinkState.Offline) {
                    LoadingPane((state as? LinkState.Connecting)?.step ?: "Connecting to ${link.computer.name}…")
                }
            }
            if (terminal != null && Store.extraKeys(context)) {
                ExtraKeys({ termView }, mods,
                    onPaste = {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text != null) terminal.session.emulator?.paste(text)
                    },
                    onKeyboard = {
                        termView?.let { v -> v.requestFocus(); context.getSystemService(InputMethodManager::class.java).toggleSoftInput(0, 0) }
                    })
            }
        }
    }
    if (showNew) NewSessionDialog(link, info?.cwd, onDismiss = { showNew = false }) { id -> showNew = false; current = id }
    renaming?.let { s ->
        InputDialog("Rename session", "Name", s.name, "Rename", { renaming = null }) { name ->
            renaming = null
            scope.launch(Dispatchers.IO) { runCatching { link.request(JSONObject().put("op", "rename").put("id", s.id).put("name", name)) } }
        }
    }
    @Suppress("UNUSED_EXPRESSION") refresh
}

@Composable
private fun Banner(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = wonderPalette()
    Box(modifier.fillMaxWidth().background(p.panel).border(1.dp, p.panelBorder)) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(s: SessionInfo, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val p = wonderPalette()
    val shape = RoundedCornerShape(if (LocalLook.current == WonderLook.BLISS) 3.dp else 10.dp)
    val bg = if (selected) Brush.verticalGradient(listOf(p.accent.lighten(.35f), p.accent, p.accent.darken(.12f)))
    else Brush.verticalGradient(if (p.dark) listOf(Color(0xFF3A3F47), Color(0xFF2B2F35)) else listOf(Color.White, Color(0xFFE6E8EB)))
    val label = s.name.ifBlank { s.program }
    val busy = s.idleSecs < 3
    Row(
        Modifier.padding(horizontal = 3.dp).clip(shape).background(bg).border(1.dp, if (selected) p.accent.darken(.3f) else p.fieldBorder, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (busy) Color(0xFF34C759) else if (selected) Color.White.copy(.6f) else p.secondaryText.copy(.5f)))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, color = if (selected) Color.White else p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            if (s.program.isNotBlank() && s.program != label) Text(s.program, color = if (selected) Color.White.copy(.8f) else p.secondaryText, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SessionTabAdd(onClick: () -> Unit) {
    val p = wonderPalette()
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).border(1.dp, p.fieldBorder, RoundedCornerShape(50)).combinedClickableCompat(onClick), contentAlignment = Alignment.Center) {
        Icon(WonderIcons.Add, "New session", tint = p.accent, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
fun NewSessionDialog(link: Link, currentCwd: String?, onDismiss: () -> Unit, onStarted: (Int) -> Unit) {
    val context = LocalContext.current
    val recents = remember { Store.recentFolders(context, link.computer.fp) }
    // New sessions start at home; the current folder and recents are one tap away as chips.
    var folder by remember { mutableStateOf(link.computer.home) }
    var command by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val quick = remember { Store.quickCommands(context) }
    val scope = rememberCoroutineScope()
    fun start(cmd: String, dir: String, label: String) {
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    link.request(JSONObject().put("op", "new").put("cwd", dir).put("command", cmd.ifBlank { null }).put("name", label.ifBlank { null }).put("cols", 80).put("rows", 30))
                }
            }
            busy = false
            r.onSuccess { Store.rememberFolder(context, link.computer.fp, dir); onStarted(it.optInt("id")) }.onFailure { error = it.message }
        }
    }
    WonderDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(folder, { folder = it }, "Folder")
                val home = link.computer.home
                val chips = (listOfNotNull(currentCwd) + recents + listOf(home)).distinct().take(6)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { f -> Chip(f.replaceFirst(home, "~").ifBlank { "/" }, f == folder, { folder = f }, WonderIcons.Folder) }
                }
                Field(command, { command = it }, "Run (optional)", supporting = "Leave empty for a shell. A command runs in its own session, which ends when it exits.")
                Field(name, { name = it }, "Tab name (optional)")
                if (quick.isNotEmpty()) {
                    SectionLabel("Quick commands")
                    quick.forEach { q -> ListRow(q.label.ifBlank { q.command }, subtitle = listOf(q.command, q.folder).filter { it.isNotBlank() }.joinToString(" · "), icon = WonderIcons.PlayArrow, onClick = { start(q.command, q.folder.ifBlank { folder }, q.label) }) }
                }
                if (busy) LoadingStatus("Starting the session…", compact = true)
                error?.let { Text(it, color = wonderPalette().danger) }
            }
        },
        confirmButton = { WButton("Start", { start(command, folder, name) }, kind = ButtonKind.Primary, enabled = !busy) },
        dismissButton = { WButton("Cancel", onDismiss, kind = ButtonKind.Plain) },
    )
}
