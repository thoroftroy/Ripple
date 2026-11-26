package dev.wonderdesk.ripple

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

data class RemoteEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long, val hidden: Boolean)

@Composable
fun FilesScreen(fp: String, startPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val link = remember(fp) { Ripple.link(fp) } ?: run { onBack(); return }
    LaunchedEffect(Unit) { link.start() }
    val state by link.state.collectAsState()
    val home = link.computer.home.ifBlank { "~" }
    var path by rememberSaveable { mutableStateOf(startPath.ifBlank { home }) }
    var entries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableStateOf<Float?>(null) }
    var busyDetail by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<RemoteEntry?>(null) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val listState = rememberLazyListState()

    fun child(e: RemoteEntry) = path.trimEnd('/') + "/" + e.name

    // Where you have been, like a browser: Back returns to the previous folder (at the same place in the list), not just its parent.
    var history by rememberSaveable { mutableStateOf(listOf<String>()) }
    val scrollAt = remember { mutableMapOf<String, Int>() }
    var restoreTo by remember { mutableIntStateOf(0) }
    fun go(to: String) {
        if (to == path) return
        scrollAt[path] = listState.firstVisibleItemIndex
        history = (history + path).takeLast(50); restoreTo = 0; path = to
    }
    fun back(): Boolean {
        val prev = history.lastOrNull() ?: return false
        history = history.dropLast(1); restoreTo = scrollAt[prev] ?: 0; path = prev
        return true
    }
    fun parentOf(p: String) = p.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }

    LaunchedEffect(path, reload, state is LinkState.Connected) {
        if (state !is LinkState.Connected) return@LaunchedEffect
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { link.request(JSONObject().put("op", "ls").put("path", path)) } }
        r.onSuccess { o ->
            path = o.optString("path", path)
            val a = o.optJSONArray("entries")
            entries = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONObject(i).let { RemoteEntry(it.optString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("mtime"), it.optBoolean("hidden")) } }
            listState.scrollToItem(restoreTo.coerceAtMost((entries?.size ?: 1) - 1).coerceAtLeast(0)); restoreTo = 0
        }.onFailure { error = it.message; entries = emptyList() }
    }

    fun work(label: String, block: suspend () -> Unit) {
        busy = label; busyProgress = null; busyDetail = null
        scope.launch {
            try { block() } catch (e: Exception) { toast(e.message ?: "Something went wrong") }
            busy = null
        }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        work("Sending ${uris.size} file${if (uris.size == 1) "" else "s"}…") {
            uris.forEachIndexed { i, u ->
                val (name, _) = Transfers.displayName(context, u)
                busy = "Sending $name (${i + 1} of ${uris.size})"
                withContext(Dispatchers.IO) {
                    Transfers.upload(context, fp, u, path, onProgress = { t, pct -> busyDetail = t; busyProgress = pct / 100f }, notify = false)
                }
            }
            toast("Sent to ${path.replaceFirst(home, "~")}")
            reload++
        }
    }

    fun open(e: RemoteEntry, share: Boolean) {
        // Videos and music open in Memories or Bliss Player right away and are read as they play, instead of downloading first.
        if (!share) {
            val t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase()) ?: ""
            val player = when { t.startsWith("video/") -> "dev.wonderdesk.memories"; t.startsWith("audio/") -> "com.blissplayer.music.debug"; else -> null }
            if (player != null) {
                val live = android.net.Uri.parse("content://dev.wonderdesk.ripple.link").buildUpon().appendPath("file").appendPath(fp).appendQueryParameter("path", child(e))
                    .appendQueryParameter("name", e.name).appendQueryParameter("v", "${e.size}-${e.mtime}").build()
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(live, t).setPackage(player).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.clipData = android.content.ClipData.newRawUri(e.name, live)
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
        }
        work("Opening ${e.name}…") {
            val f = withContext(Dispatchers.IO) { Transfers.downloadToCache(context, fp, child(e)) { t, pct -> busyDetail = t; busyProgress = pct / 100f } }
            val uri = FileProvider.getUriForFile(context, "dev.wonderdesk.ripple.share", f)
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(intent, if (share) "Share ${e.name}" else "Open ${e.name} with")) }
                .onFailure { toast("No app here can open ${e.name}") }
        }
    }

    BackHandlerCompat(enabled = history.isNotEmpty()) { back() }

    WonderApp(
        appName = "Ripple", about = "Ripple",
        title = path.substringAfterLast('/').ifBlank { "/" },
        subtitle = "${link.computer.name} · ${path.replaceFirst(home, "~")}",
        // The arrow steps back through the folders you opened; from the first one it leaves Files.
        onBack = { if (!back()) onBack() },
        actions = {
            ToolbarButton(WonderIcons.ArrowUpward, "Up to the folder above", { go(parentOf(path)) }, enabled = path != "/")
            ToolbarButton(WonderIcons.UploadFile, "Send files here", { upload.launch(arrayOf("*/*")) }, enabled = state is LinkState.Connected)
            ToolbarButton(WonderIcons.CreateNewFolder, "New folder", { newFolder = true }, enabled = state is LinkState.Connected)
        },
        menu = listOf(
            WonderMenuItem(if (showHidden) "Hide hidden files" else "Show hidden files", WonderIcons.Visibility) { showHidden = !showHidden },
            WonderMenuItem("Refresh", WonderIcons.Refresh) { reload++ },
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Places
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Home", path == home, { go(home) }, WonderIcons.Home)
                if (link.computer.sharedDir.isNotBlank()) Chip("Ripple folder", path == link.computer.sharedDir, { go(link.computer.sharedDir) }, WonderIcons.FolderOpen)
                link.sessions.value.map { it.cwd }.filter { it.isNotBlank() && it != home }.distinct().take(5).forEach { d ->
                    Chip(d.substringAfterLast('/'), path == d, { go(d) }, WonderIcons.Code)
                }
                Chip("/", path == "/", { go("/") }, WonderIcons.Storage)
            }
            // Where you are, one tap per folder: jump straight back up to any of them.
            PathBar(path, home) { go(it) }
            busy?.let { LoadingStatus(it, Modifier.fillMaxWidth(), progress = busyProgress, detail = busyDetail, compact = true) }
            val list = entries
            when {
                state !is LinkState.Connected -> LoadingPane((state as? LinkState.Connecting)?.step ?: (state as? LinkState.Offline)?.message ?: "Connecting to ${link.computer.name}…")
                list == null -> LoadingPane("Reading ${path.replaceFirst(home, "~")}…")
                error != null -> EmptyState("Can't open this folder", error!!, icon = WonderIcons.Error)
                else -> {
                    val shown = list.filter { showHidden || !it.hidden }
                    if (shown.isEmpty()) EmptyState("Empty folder", "Send files here with the upload button.", icon = WonderIcons.FolderOpen)
                    LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
                        itemsIndexed(shown, key = { _, e -> e.name }) { i, e ->
                            Box {
                                ListRow(
                                    e.name,
                                    Modifier.panelItem(i, shown.size),
                                    subtitle = if (e.dir) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.mtime)) else "${Notifier.human(e.size)} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.mtime))}",
                                    icon = iconFor(e), tint = if (e.dir) androidx.compose.ui.graphics.Color(0xFF3D8BFD) else androidx.compose.ui.graphics.Color(0xFF6B7078),
                                    chevron = e.dir,
                                    onLongClick = { menu = e },
                                    onClick = { if (e.dir) go(child(e)) else open(e, share = false) },
                                )
                                DropdownMenu(menu == e, { menu = null }) {
                                    if (!e.dir) {
                                        DropdownMenuItem({ Text("Save to Downloads") }, { menu = null; scope.launch(Dispatchers.IO) { Transfers.downloadToDownloads(context, fp, child(e), e.name) }; toast("Saving ${e.name}…") })
                                        DropdownMenuItem({ Text("Open with…") }, { menu = null; open(e, false) })
                                        DropdownMenuItem({ Text("Share") }, { menu = null; open(e, true) })
                                    }
                                    DropdownMenuItem({ Text("Copy path") }, {
                                        menu = null
                                        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Path", child(e)))
                                        toast("Path copied")
                                    })
                                    DropdownMenuItem({ Text("Rename") }, { menu = null; renaming = e })
                                    DropdownMenuItem({ Text("Delete") }, { menu = null; deleting = e })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { e ->
        InputDialog("Rename", "Name", e.name, "Rename", { renaming = null }) { n ->
            renaming = null
            work("Renaming…") { withContext(Dispatchers.IO) { link.request(JSONObject().put("op", "mv").put("path", child(e)).put("to", path.trimEnd('/') + "/" + n)) }; reload++ }
        }
    }
    deleting?.let { e ->
        ConfirmDialog("Delete ${e.name}?", if (e.dir) "The folder and everything in it is deleted from ${link.computer.name}. This can't be undone." else "It's deleted from ${link.computer.name}. This can't be undone.", "Delete", {
            deleting = null
            work("Deleting ${e.name}…") { withContext(Dispatchers.IO) { link.request(JSONObject().put("op", "rm").put("path", child(e))) }; reload++ }
        }, { deleting = null }, danger = true)
    }
    if (newFolder) InputDialog("New folder", "Name", "", "Create", { newFolder = false }) { n ->
        newFolder = false
        work("Creating $n…") { withContext(Dispatchers.IO) { link.request(JSONObject().put("op", "mkdir").put("path", path.trimEnd('/') + "/" + n)) }; reload++ }
    }
}

/** "~ › Projects › site": each folder in the path is a button. The last one (where you are) is highlighted. */
@Composable
private fun PathBar(path: String, home: String, onGo: (String) -> Unit) {
    val p = wonderPalette()
    val underHome = home.length > 1 && (path == home || path.startsWith(home.trimEnd('/') + "/"))
    val parts = ArrayList<Pair<String, String>>()   // label to full path
    if (underHome) {
        parts += "~" to home
        path.removePrefix(home.trimEnd('/')).trim('/').split('/').filter { it.isNotEmpty() }.fold(home.trimEnd('/')) { acc, n -> (acc + "/" + n).also { parts += n to it } }
    } else {
        parts += "/" to "/"
        path.trim('/').split('/').filter { it.isNotEmpty() }.fold("") { acc, n -> ("$acc/$n").also { parts += n to it } }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp).padding(bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        parts.forEachIndexed { i, (label, full) ->
            val last = i == parts.lastIndex
            Text(label, Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(if (last) p.accent.copy(alpha = .16f) else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(enabled = !last) { onGo(full) }.padding(horizontal = 8.dp, vertical = 5.dp),
                color = if (last) p.accent else p.text, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) androidx.compose.ui.text.font.FontWeight.SemiBold else null, maxLines = 1)
            if (!last) Icon(WonderIcons.ChevronRight, null, Modifier.size(16.dp), tint = p.secondaryText)
        }
    }
}

@Composable
private fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

fun iconFor(e: RemoteEntry) = when {
    e.dir -> WonderIcons.Folder
    else -> when (e.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic" -> WonderIcons.Image
        "mp4", "mkv", "webm", "mov", "avi" -> WonderIcons.Movie
        "mp3", "flac", "ogg", "wav", "m4a", "opus" -> WonderIcons.MusicNote
        "pdf" -> WonderIcons.PictureAsPdf
        "zip", "tar", "gz", "xz", "7z", "rar", "zst", "bz2" -> WonderIcons.FolderZip
        "apk" -> WonderIcons.Android
        "kt", "rs", "py", "js", "ts", "c", "h", "cpp", "java", "sh", "go", "rb", "kts", "toml", "json", "yml", "yaml", "xml", "html", "css" -> WonderIcons.Code
        "md", "txt", "log" -> WonderIcons.Description
        else -> WonderIcons.InsertDriveFile
    }
}
