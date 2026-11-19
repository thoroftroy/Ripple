package dev.wonderdesk.ripple

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.ListRow
import dev.wonderdesk.shared.WonderDialog
import dev.wonderdesk.shared.WonderIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Copied text moves both ways between this phone and the computer. */
object ClipSync {
    @Volatile var last = ""

    fun enabled(c: Context) = Store.clipSync(c)

    /** The computer copied something: put it on this phone's clipboard. */
    fun fromComputer(c: Context, text: String) {
        if (!enabled(c) || text.isEmpty() || text == last) return
        last = text
        Ripple.main.post { runCatching { c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ripple", text)) } }
    }

    /** This phone copied something (or the app came to the front with new text on the clipboard): send it to the computer. */
    fun toComputer(c: Context, link: Link, text: String) {
        if (!enabled(c) || text.isEmpty() || text == last || link.handle == 0L) return
        last = text
        Thread { runCatching { link.request(JSONObject().put("op", "clip_set").put("text", text)) } }.start()
    }

    fun current(c: Context): String? = runCatching { c.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() }.getOrNull()
}

@Composable
fun ClipHistoryDialog(link: Link, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            runCatching { link.request(JSONObject().put("op", "clip_list")).optJSONArray("clips")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("text") to o.optString("from") } } } }.getOrNull() ?: emptyList()
        }
    }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Clipboard history") }, text = {
        val list = items
        if (list == null) Text("Loading…")
        else if (list.isEmpty()) Text("Nothing copied yet. Text you copy here or on the computer shows up in this list on both.")
        else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            list.forEach { entry ->
                val t = entry.first
                ListRow(t.take(160).replace('\n', ' '), subtitle = if (entry.second == "phone") "Copied on this phone" else "Copied on the computer", icon = WonderIcons.ContentPaste,
                    onClick = { ClipSync.last = t; context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ripple", t)); onDismiss() })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
