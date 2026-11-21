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

    LaunchedEffect(path, reload, state is LinkState.Connected) {
        if (state !is LinkState.Connected) return@LaunchedEffect
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { link.request(JSONObject().put("op", "ls").put("path", path)) } }
        r.onSuccess { o ->
            path = o.optString("path", path)
            val a = o.optJSONArray("entries")
            entries = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONObject(i).let { RemoteEntry(it.optString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("mtime"), it.optBoolean("hidden")) } }
            listState.scrollToItem(0)
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

    BackHandlerCompat(enabled = path != "/" && path != home) { path = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" } }

    WonderApp(
        appName = "Ripple", about = "Ripple",
        title = path.substringAfterLast('/').ifBlank { "/" },
        subtitle = "${link.computer.name} · ${path.replaceFirst(home, "~")}",
        onBack = onBack,
        actions = {
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
                Chip("Home", path == home, { path = home }, WonderIcons.Home)
                if (link.computer.sharedDir.isNotBlank()) Chip("Ripple folder", path == link.computer.sharedDir, { path = link.computer.sharedDir }, WonderIcons.FolderOpen)
                link.sessions.value.map { it.cwd }.filter { it.isNotBlank() && it != home }.distinct().take(5).forEach { d ->
                    Chip(d.substringAfterLast('/'), path == d, { path = d }, WonderIcons.Code)
                }
                Chip("/", path == "/", { path = "/" }, WonderIcons.Storage)
            }
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
                                    onClick = { if (e.dir) path = child(e) else open(e, share = false) },
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
