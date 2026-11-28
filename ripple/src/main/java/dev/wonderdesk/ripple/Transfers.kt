package dev.wonderdesk.ripple

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.wonderdesk.ripple.net.Native
import org.json.JSONObject

/** Progress text with a speed-based time estimate once one is reliable. */
class Progress(private val total: Long) {
    private val start = System.currentTimeMillis()
    fun text(done: Long): String {
        val secs = (System.currentTimeMillis() - start) / 1000.0
        val pct = if (total > 0) (done * 100 / total).toInt() else -1
        val speed = if (secs > 0.8) done / secs else 0.0
        val eta = if (speed > 0 && total > 0 && secs > 1.5) ((total - done) / speed).toLong() else -1
        return buildString {
            if (pct >= 0) append("$pct% · ")
            append(Notifier.human(done))
            if (total > 0) append(" of ${Notifier.human(total)}")
            if (speed > 0) append(" · ${Notifier.human(speed.toLong())}/s")
            if (eta >= 0) append(" · ${eta(eta)} left")
        }
    }
    fun percent(done: Long) = if (total > 0) (done * 100 / total).toInt() else 0

    companion object {
        fun eta(s: Long) = when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min ${s % 60}s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}

object Transfers {
    /** Connects (if needed) and waits; returns the connection handle. */
    fun handle(c: Context, fp: String, timeoutMs: Long = 30_000): Long {
        val link = Ripple.link(fp) ?: throw RuntimeException("That computer isn't paired any more")
        if (link.handle == 0L) link.start()
        val until = System.currentTimeMillis() + timeoutMs
        while (link.handle == 0L) {
            if (System.currentTimeMillis() > until) {
                val st = link.state.value
                throw RuntimeException(if (st is LinkState.Offline) st.message else "Couldn't reach ${link.computer.name}")
            }
            Thread.sleep(150)
        }
        return link.handle
    }

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun progressNote(c: Context, id: Int, title: String, text: String, pct: Int) {
        Notifier.channels(c)
        Notifier.post(c, id, Notification.Builder(c, Notifier.CH_FILES).setSmallIcon(R.drawable.ic_stat_ripple)
            .setContentTitle(title).setContentText(text).setProgress(100, pct, pct <= 0).setOngoing(true).setOnlyAlertOnce(true).build())
    }

    /** Saves a computer file into Downloads/Ripple, with a progress notification. */
    fun downloadToDownloads(c: Context, fp: String, path: String, name: String): Uri? {
        val id = (path.hashCode() and 0xffff) + 5000
        return try {
            progressNote(c, id, "Saving $name", "Connecting…", 0)
            val h = handle(c, fp)
            val size = runCatching { JSONObject(Native.request(h, JSONObject().put("op", "stat").put("path", path).toString())).optJSONObject("entry")?.optLong("size") ?: 0L }.getOrDefault(0L)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ripple")
                // Say what kind of file it is, so photos and videos show up in Memories and other galleries straight away.
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw RuntimeException("Couldn't create the file")
            val prog = Progress(size)
            c.contentResolver.openFileDescriptor(uri, "w")!!.use { pfd ->
                if (size > 16L * 1024 * 1024) {
                    // Several streams at once fill the link much sooner than one.
                    java.io.FileOutputStream(pfd.fileDescriptor).channel.let { ch -> Ranges.parallelDownload(c, fp, path, size, ch) { got -> progressNote(c, id, "Saving $name", prog.text(got), prog.percent(got)) }; ch.force(false) }
                } else Native.download(h, pfd.fd, path) { s ->
                    if (s.startsWith("progress:")) {
                        val got = s.removePrefix("progress:").substringBefore('/').toLongOrNull() ?: 0
                        progressNote(c, id, "Saving $name", prog.text(got), prog.percent(got))
                    }
                }
            }
            c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, c.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, id, Intent.createChooser(view, "Open $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            nm(c).cancel(id)
            Notifier.post(c, id, Notification.Builder(c, Notifier.CH_FILES).setSmallIcon(R.drawable.ic_stat_ripple)
                .setContentTitle("Saved $name").setContentText("In Downloads › Ripple").setContentIntent(pi).setAutoCancel(true).build())
            uri
        } catch (e: Exception) {
            nm(c).cancel(id)
            Notifier.post(c, id, Notification.Builder(c, Notifier.CH_FILES).setSmallIcon(R.drawable.ic_stat_ripple)
                .setContentTitle("Couldn't save $name").setContentText(e.message ?: "Something went wrong").setAutoCancel(true).build())
            null
        }
    }

    fun displayName(c: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getString(0)?.let { name = it }
                    if (!cur.isNull(1)) size = cur.getLong(1)
                }
            }
        }
        return name to size
    }

    /** Sends a phone file to the computer (into `destDir`, or the shared Ripple folder). Returns where it was saved. */
    fun upload(c: Context, fp: String, uri: Uri, destDir: String?, onProgress: ((String, Int) -> Unit)? = null, notify: Boolean = true): String {
        val (name, size) = displayName(c, uri)
        val id = (uri.hashCode() and 0xffff) + 9000
        try {
            if (notify) progressNote(c, id, "Sending $name", "Connecting…", 0)
            val h = handle(c, fp)
            val prog = Progress(size)
            val req = JSONObject().put("op", "put").put("name", name).put("size", size)
            if (!destDir.isNullOrBlank()) req.put("path", destDir.trimEnd('/') + "/" + name)
            val r = c.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                Native.upload(h, pfd.fd, req.toString()) { s ->
                    if (s.startsWith("progress:")) {
                        val sent = s.removePrefix("progress:").toLongOrNull() ?: 0
                        val t = prog.text(sent)
                        onProgress?.invoke(t, prog.percent(sent))
                        if (notify) progressNote(c, id, "Sending $name", t, prog.percent(sent))
                    }
                }
            }
            val saved = JSONObject(r).optString("path")
            nm(c).cancel(id)
            if (notify) Notifier.post(c, id, Notification.Builder(c, Notifier.CH_FILES).setSmallIcon(R.drawable.ic_stat_ripple)
                .setContentTitle("Sent $name").setContentText(saved).setAutoCancel(true).build())
            return saved
        } catch (e: Exception) {
            nm(c).cancel(id)
            if (notify) Notifier.post(c, id, Notification.Builder(c, Notifier.CH_FILES).setSmallIcon(R.drawable.ic_stat_ripple)
                .setContentTitle("Couldn't send $name").setContentText(e.message ?: "Something went wrong").setAutoCancel(true).build())
            throw e
        }
    }

    /**
     * Downloads into the app cache (for opening and sharing). A file already fetched, and unchanged on the computer, is
     * reused instead of downloaded again; big files come down over several streams at once.
     */
    fun downloadToCache(c: Context, fp: String, path: String, onProgress: (String, Int) -> Unit): java.io.File {
        val h = handle(c, fp)
        val name = path.substringAfterLast('/')
        val entry = runCatching { JSONObject(Native.request(h, JSONObject().put("op", "stat").put("path", path).toString())).optJSONObject("entry") }.getOrNull()
        val size = entry?.optLong("size") ?: 0L
        val stamp = entry?.optLong("mtime") ?: 0L
        val root = java.io.File(c.cacheDir, "open").apply { mkdirs() }
        val key = (path.hashCode() and 0x7fffffff).toString()
        val dir = java.io.File(root, "$key-$size-$stamp")
        val f = java.io.File(dir, name)
        if (size > 0 && f.isFile && f.length() == size) { f.setLastModified(System.currentTimeMillis()); return f }
        // Older copies of this file, and the oldest of the rest when the cache grows past 1 GB.
        root.listFiles()?.filter { it.name.startsWith("$key-") }?.forEach { it.deleteRecursively() }
        trimCache(root, 1L shl 30)
        dir.mkdirs()
        val prog = Progress(size)
        val part = java.io.File(dir, "$name.part")
        if (size > 16L * 1024 * 1024) {
            java.io.RandomAccessFile(part, "rw").use { raf -> raf.setLength(size)
                Ranges.parallelDownload(c, fp, path, size, raf.channel) { got -> onProgress(prog.text(got), prog.percent(got)) } }
        } else {
            android.os.ParcelFileDescriptor.open(part, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY).use { pfd ->
                Native.download(h, pfd.fd, path) { s ->
                    if (s.startsWith("progress:")) {
                        val got = s.removePrefix("progress:").substringBefore('/').toLongOrNull() ?: 0
                        onProgress(prog.text(got), prog.percent(got))
                    }
                }
            }
        }
        part.renameTo(f)
        return f
    }

    private fun trimCache(root: java.io.File, limit: Long) {
        val dirs = root.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (d in dirs) { if (total <= limit) break; val n = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }; d.deleteRecursively(); total -= n }
    }
}
