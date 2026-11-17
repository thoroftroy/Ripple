package dev.wonderdesk.ripple

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import dev.wonderdesk.ripple.net.Native
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Machine-readable link for Wonderdesk apps (Wayfinder network shares). Unlike the
 * DocumentsProvider which Android only lets system pickers touch, this plain provider is
 * guarded by a signature permission, so our own apps can list and browse paired computers
 * automatically, with no picker scavenger hunt. Only apps signed with our key get in;
 * everyone else uses the system picker flow.
 */
class LinkProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.wonderdesk.ripple.link"
        const val PERMISSION = "dev.wonderdesk.ripple.ACCESS_LINK"
        private const val COMPUTERS = 1
        private const val LS = 2
        private const val FILE = 3
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "computers", COMPUTERS)
            addURI(AUTHORITY, "computers/*/ls", LS)
            addURI(AUTHORITY, "file/*", FILE)
        }
        private val io by lazy { HandlerThread("ripple-link").apply { start() }.let { Handler(it.looper) } }
    }

    override fun onCreate() = true

    private fun ready() { Ripple.init(context!!) }
    private fun fp(uri: Uri) = uri.pathSegments.getOrNull(1) ?: throw FileNotFoundException("no computer")

    /** Phone side paths must stay absolute. An old build once trimmed the leading
     *  slash, which turned /home/user/file into home/user/file. The daemon reads
     *  that as relative and answers that it does not exist. */
    private fun absPath(p: String): String = when {
        p.isEmpty() -> p
        p.startsWith("/") || p.startsWith("~") -> p
        else -> "/$p"
    }

    private fun handle(fp: String): Long = try {
        Transfers.handle(context!!, fp, 20_000)
    } catch (e: Exception) {
        throw FileNotFoundException(e.message)
    }

    private fun request(fp: String, req: JSONObject): JSONObject {
        val h = handle(fp)
        return try {
            JSONObject(Native.request(h, req.toString()))
        } catch (e: RuntimeException) {
            throw FileNotFoundException(e.message)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        ready()
        return when (MATCHER.match(uri)) {
            COMPUTERS -> {
                val c = MatrixCursor(projection ?: arrayOf("fp", "name", "summary", "home", "shared"))
                Store.computers(context!!).forEach { comp ->
                    val vals = mapOf<String, Any?>(
                        "fp" to comp.fp,
                        "name" to comp.name.ifBlank { "Computer" },
                        "summary" to "Ripple · ${comp.user.ifBlank { "computer" }}",
                        "home" to comp.home,
                        "shared" to comp.sharedDir,
                    )
                    c.newRow().let { r -> c.columnNames.forEach { r.add(vals[it]) } }
                }
                c
            }
            LS -> {
                val f = fp(uri)
                val raw = uri.getQueryParameter("path").orEmpty()
                val path = absPath(raw)
                val r = request(f, JSONObject().put("op", "ls").put("path", path))
                r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
                val a = r.optJSONArray("entries")
                val c = MatrixCursor(projection ?: arrayOf("name", "dir", "size", "mtime", "hidden", "path"))
                if (a != null) for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    val vals = mapOf<String, Any?>(
                        "name" to e.optString("name"),
                        "dir" to if (e.optBoolean("dir")) 1 else 0,
                        "size" to e.optLong("size"),
                        "mtime" to e.optLong("mtime"),
                        "hidden" to if (e.optBoolean("hidden")) 1 else 0,
                        "path" to ("/" + (path.trimEnd('/') + "/" + e.optString("name")).trim('/')),
                    )
                    c.newRow().let { row -> c.columnNames.forEach { row.add(vals[it]) } }
                }
                c
            }
            FILE -> {
                // Name and size, so a viewer that asks "what is this file?" gets an answer.
                val f = fp(uri)
                val path = absPath(uri.getQueryParameter("path") ?: throw FileNotFoundException("no path"))
                val st = request(f, JSONObject().put("op", "stat").put("path", path)).optJSONObject("entry")
                val c = MatrixCursor(projection ?: arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
                val vals = mapOf<String, Any?>(android.provider.OpenableColumns.DISPLAY_NAME to (uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')), android.provider.OpenableColumns.SIZE to (st?.optLong("size") ?: 0L))
                c.newRow().let { r -> c.columnNames.forEach { r.add(vals[it]) } }
                c
            }
            else -> throw FileNotFoundException(uri.toString())
        }
    }

    override fun getType(uri: Uri): String? = null

    /** Streams a remote file out ("r"), or collects an upload and pushes it on close ("w"). */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        ready()
        if (uri.pathSegments.firstOrNull() != "file") throw FileNotFoundException(uri.toString())
        val f = fp(uri)
        val path = absPath(uri.getQueryParameter("path") ?: throw FileNotFoundException("no path"))
        val ctx = context!!
        val h = handle(f)
        if (mode == "r") {
            val st = request(f, JSONObject().put("op", "stat").put("path", path))
            st.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
            if (st.optJSONObject("entry")?.optBoolean("dir") == true) throw FileNotFoundException("That's a folder")
            // Big files are read on demand through a seekable file, so a video starts at once and can jump around
            // without the whole file arriving first.
            val size = st.optJSONObject("entry")?.optLong("size") ?: 0L
            if (size > 6L * 1024 * 1024 && android.os.Build.VERSION.SDK_INT >= 26) {
                val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
                val reader = HandlerThread("ripple-link-read").apply { start() }
                return try { sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, RemoteFile(ctx, f, path, size), Handler(reader.looper)) }
                catch (e: Exception) { reader.quitSafely(); throw FileNotFoundException(e.message) }
            }
            // Stream through a pipe so the reader sees bytes from the start and can
            // report progress with ETA. Wayfinder copies this into its own cache file
            // before opening a viewer, so seeking still works on the final copy.
            // A stat check above already throws the real reason for missing paths.
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            io.post {
                try {
                    Native.download(h, writeEnd.fd, path) { }
                } catch (_: Exception) {
                } finally {
                    runCatching { writeEnd.close() }
                }
            }
            return readEnd
        }
        // Writing: collect into a local file, then send it when the app closes it.
        val dir = File(ctx.cacheDir, "link-write").apply { mkdirs() }
        val tmp = File(dir, "${System.nanoTime()}-${path.substringAfterLast('/')}")
        if (mode.contains('r')) runCatching {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use { pfd ->
                Native.download(h, pfd.fd, path) { }
            }
        }
        else tmp.createNewFile()
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode), io) { err ->
            if (err == null) {
                Thread {
                    runCatching {
                        val uh = handle(f)
                        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            Native.upload(uh, pfd.fd, JSONObject().put("op", "put").put("path", path).put("overwrite", true).toString(), null)
                        }
                    }
                    tmp.delete()
                }.start()
            } else tmp.delete()
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        ready()
        return when (method) {
            "mkdir" -> {
                val f = extras?.getString("fp") ?: return null
                val path = absPath(extras.getString("path") ?: return null)
                val r = request(f, JSONObject().put("op", "mkdir").put("path", path))
                r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
                Bundle().apply { putString("path", path) }
            }
            "rm" -> {
                val f = extras?.getString("fp") ?: return null
                val path = absPath(extras.getString("path") ?: return null)
                val r = request(f, JSONObject().put("op", "rm").put("path", path))
                r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
                Bundle()
            }
            "mv" -> {
                val f = extras?.getString("fp") ?: return null
                val path = absPath(extras.getString("path") ?: return null)
                val to = absPath(extras.getString("to") ?: return null)
                val r = request(f, JSONObject().put("op", "mv").put("path", path).put("to", to))
                r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
                Bundle().apply { putString("path", to) }
            }
            "stat" -> {
                val f = extras?.getString("fp") ?: return null
                val path = absPath(extras.getString("path") ?: return null)
                val r = request(f, JSONObject().put("op", "stat").put("path", path))
                r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
                val e = r.optJSONObject("entry")
                Bundle().apply {
                    putString("name", e?.optString("name") ?: path.substringAfterLast('/'))
                    putBoolean("dir", e?.optBoolean("dir") == true)
                    putLong("size", e?.optLong("size") ?: 0L)
                    putLong("mtime", e?.optLong("mtime") ?: 0L)
                }
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
