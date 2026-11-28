package dev.wonderdesk.ripple

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.ripple.net.Native
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A note that lives in two places: a Markdown file on this phone (edited in Wonderdesk Scribble) and the same file in a folder
 * on a computer. Whichever side changed is copied to the other; if both changed, the phone's version wins and the computer's is
 * kept next to it so nothing is lost.
 */
data class NoteLink(val id: String, val fp: String, val name: String, val local: String, val remoteDir: String, val pushed: Long = 0L, val remoteStamp: Long = 0L) {
    val file: File get() = File(local)
    fun toJson() = JSONObject().put("id", id).put("fp", fp).put("name", name).put("local", local).put("remoteDir", remoteDir).put("pushed", pushed).put("remoteStamp", remoteStamp)
}

object NoteLinks {
    private fun prefs(c: Context) = c.getSharedPreferences("note_links", Context.MODE_PRIVATE)
    @Synchronized fun all(c: Context): List<NoteLink> = runCatching {
        val a = JSONArray(prefs(c).getString("links", "[]")); (0 until a.length()).map { i -> a.getJSONObject(i).let { NoteLink(it.getString("id"), it.getString("fp"), it.getString("name"), it.getString("local"), it.getString("remoteDir"), it.optLong("pushed"), it.optLong("remoteStamp")) } }
    }.getOrDefault(emptyList())
    @Synchronized private fun save(c: Context, l: List<NoteLink>) = prefs(c).edit().putString("links", JSONArray(l.map { it.toJson() }).toString()).apply()
    @Synchronized fun add(c: Context, n: NoteLink) = save(c, all(c) + n)
    @Synchronized fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })
    @Synchronized private fun update(c: Context, n: NoteLink) = save(c, all(c).map { if (it.id == n.id) n else it })

    fun defaultFolder() = File(android.os.Environment.getExternalStorageDirectory(), "Documents/Ripple Notes").path

    /** Makes the local file (empty if new) and the link. */
    fun create(c: Context, fp: String, name: String, localDir: String, remoteDir: String): NoteLink {
        val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), " ").ifBlank { "Notes" }
        val f = File(localDir, if (safe.contains('.')) safe else "$safe.md").also { it.parentFile?.mkdirs(); if (!it.exists()) it.writeText("") }
        return NoteLink(java.util.UUID.randomUUID().toString(), fp, f.nameWithoutExtension, f.path, remoteDir).also { add(c, it) }
    }

    /** Opens the note in Scribble, which saves straight into the file. */
    fun openInScribble(c: Context, n: NoteLink): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage("dev.wonderdesk.scribble") ?: return false
        i.putExtra("path", n.local).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching { c.startActivity(i) }.isSuccess
    }

    private fun stat(h: Long, path: String): JSONObject? = runCatching { JSONObject(Native.request(h, JSONObject().put("op", "stat").put("path", path).toString())).optJSONObject("entry") }.getOrNull()

    /** Brings one link up to date. Returns what happened, or null when nothing needed doing. Needs a live connection. */
    fun syncOne(c: Context, n: NoteLink): String? {
        val link = Ripple.link(n.fp) ?: return null
        val h = link.handle; if (h == 0L) return null
        val f = n.file
        val remote = n.remoteDir.trimEnd('/') + "/" + f.name
        val r = stat(h, remote)
        val rStamp = r?.optLong("mtime") ?: 0L
        val localChanged = f.exists() && f.lastModified() != n.pushed
        val remoteChanged = r != null && rStamp != n.remoteStamp
        when {
            localChanged && (r == null || !remoteChanged || n.pushed == 0L && n.remoteStamp == 0L && rStamp == 0L) -> return push(c, n, h, remote)
            localChanged && remoteChanged -> {
                // Both sides changed: keep the computer's copy beside the phone's, then send the phone's.
                val copy = File(f.parentFile, f.nameWithoutExtension + " (from ${link.computer.name}).md")
                runCatching { Transfers.downloadToCache(c, n.fp, remote) { _, _ -> }.copyTo(copy, overwrite = true) }
                return push(c, n, h, remote)?.let { "$it; the computer's version was kept as ${copy.name}" }
            }
            !f.exists() && r != null || remoteChanged -> {
                val got = runCatching { Transfers.downloadToCache(c, n.fp, remote) { _, _ -> } }.getOrNull() ?: return null
                got.copyTo(f, overwrite = true)
                update(c, n.copy(pushed = f.lastModified(), remoteStamp = rStamp))
                return "Updated ${f.name} from ${link.computer.name}"
            }
        }
        return null
    }

    private fun push(c: Context, n: NoteLink, h: Long, remote: String): String? {
        val ok = runCatching { Transfers.upload(c, n.fp, android.net.Uri.fromFile(n.file), n.remoteDir, notify = false) }.isSuccess
        if (!ok) return null
        val st = stat(h, remote)
        update(c, n.copy(pushed = n.file.lastModified(), remoteStamp = st?.optLong("mtime") ?: 0L))
        return "Sent ${n.file.name} to ${Ripple.link(n.fp)?.computer?.name ?: "the computer"}"
    }

    /** Syncs every link whose computer is connected right now. */
    fun syncAll(c: Context): List<String> = all(c).mapNotNull { n -> runCatching { syncOne(c, n) }.getOrNull() }
}

@Composable
fun NotepadsScreen(fp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val link = remember(fp) { Ripple.link(fp) }
    val computerName = link?.computer?.name ?: "computer"
    val home = link?.computer?.home?.ifBlank { "~" } ?: "~"
    var rev by remember { mutableIntStateOf(0) }
    var ui by remember { mutableIntStateOf(0) }
    val links = remember(rev, ui) { NoteLinks.all(context).filter { it.fp == fp } }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<NoteLink?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val connected by (link?.state ?: kotlinx.coroutines.flow.MutableStateFlow<LinkState>(LinkState.Idle)).collectAsState()
    // Sync now, then every 20 seconds while this screen is open and on screen.
    val resumed = rememberIsResumed()
    LaunchedEffect(connected, resumed) {
        while (resumed && connected is LinkState.Connected) {
            val done = withContext(Dispatchers.IO) { NoteLinks.syncAll(context) }
            if (done.isNotEmpty()) status = done.last()
            ui++; delay(20_000)
        }
    }
    WonderApp(appName = "Ripple", about = "Ripple", title = "Notepads", subtitle = computerName, onBack = onBack,
        actions = { ToolbarButton(WonderIcons.Add, "New notepad", { adding = true }) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("A notepad is a note you write in Scribble on this phone. Ripple keeps it in a folder on $computerName, so it is already there when you get to the computer.", style = MaterialTheme.typography.bodyMedium, color = wonderPalette().secondaryText)
            if (!android.os.Environment.isExternalStorageManager()) Panel { ListRow("Allow all files access", subtitle = "Needed so Scribble and Ripple can share the note's file", icon = WonderIcons.Storage, tint = Color(0xFFE39A12), chevron = true, onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, help = "Lets Ripple browse the phone's files so you can send them to the computer.") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = wonderPalette().success) }
            if (links.isEmpty()) EmptyState("No notepads yet", "Make one for a project, like “Wonderdesk ideas” saved in your project folder on $computerName.", action = { WButton("New notepad", { adding = true }, icon = WonderIcons.NoteAdd, kind = ButtonKind.Primary) })
            else Panel {
                links.forEachIndexed { i, n ->
                    if (i > 0) PanelDivider()
                    val waiting = n.file.exists() && n.file.lastModified() != n.pushed
                    ListRow(n.name, subtitle = n.remoteDir.replaceFirst(home, "~") + " on $computerName · " + (if (connected !is LinkState.Connected) "waiting for the computer" else if (waiting) "waiting to send" else "in sync"),
                        icon = WonderIcons.Notes, tint = Color(0xFF1E7FE8), chevron = true,
                        onLongClick = { removing = n },
                        onClick = { if (!NoteLinks.openInScribble(context, n)) toast("Install Wonderdesk Scribble to edit notepads") })
                }
            }
        }
    }
    if (adding) NewNotepadDialog(home, computerName, { adding = false }) { name, local, remote ->
        adding = false
        scope.launch(Dispatchers.IO) {
            val n = runCatching { NoteLinks.create(context, fp, name, local, remote) }.getOrNull()
            withContext(Dispatchers.Main) { if (n == null) toast("Couldn't create the note in that folder") else { rev++; NoteLinks.openInScribble(context, n) } }
        }
    }
    removing?.let { n -> ConfirmDialog("Stop syncing “${n.name}”?", "The note stays on this phone and on $computerName. They just stop updating each other.", "Stop syncing", { NoteLinks.remove(context, n.id); removing = null; rev++ }, { removing = null }) }
}

@Composable
private fun NewNotepadDialog(home: String, computer: String, onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var local by remember { mutableStateOf(NoteLinks.defaultFolder()) }
    var remote by remember { mutableStateOf("$home/Notes") }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("New notepad") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, placeholder = { Text("Wonderdesk ideas") })
            OutlinedTextField(local, { local = it }, Modifier.fillMaxWidth(), label = { Text("Folder on this phone") })
            OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), label = { Text("Folder on $computer") })
            Text("Put it anywhere you like, such as your project's folder, so the note lives with the work.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        }
    }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name, local.trim(), remote.trim()) }, enabled = name.isNotBlank()) { Text("Create and open") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
