package dev.wonderdesk.ripple

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.wonderdesk.ripple.net.Native
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Paired computers as storage locations: Wayfinder and every app's file picker can open, save,
 * rename and delete files on them. Document ids are "<computer fingerprint>|<absolute path>".
 */
class ComputerFilesProvider : DocumentsProvider() {
    private val rootCols = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES)
    private val docCols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    private val io by lazy { HandlerThread("ripple-provider").apply { start() }.let { Handler(it.looper) } }

    override fun onCreate(): Boolean = true

    private fun ready() { Ripple.init(context!!) }

    private fun split(docId: String): Pair<String, String> {
        val i = docId.indexOf('|')
        if (i < 0) throw FileNotFoundException(docId)
        return docId.substring(0, i) to docId.substring(i + 1)
    }

    private fun handle(fp: String): Long = try { Transfers.handle(context!!, fp, 20_000) } catch (e: Exception) { throw FileNotFoundException(e.message) }

    private fun request(fp: String, req: JSONObject): JSONObject {
        val h = handle(fp)
        return try { JSONObject(Native.request(h, req.toString())) } catch (e: RuntimeException) { throw FileNotFoundException(e.message) }
    }

    private fun mime(name: String, dir: Boolean) = if (dir) Document.MIME_TYPE_DIR
    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun addRow(c: MatrixCursor, fp: String, path: String, e: JSONObject) {
        val name = e.optString("name")
        val dir = e.optBoolean("dir")
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = flags or if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        // Only the columns the caller asked for (see queryRoots): a subset projection would crash otherwise.
        val vals = mapOf<String, Any?>(
            Document.COLUMN_DOCUMENT_ID to "$fp|$path",
            Document.COLUMN_DISPLAY_NAME to name,
            Document.COLUMN_MIME_TYPE to mime(name, dir),
            Document.COLUMN_SIZE to if (dir) null else e.optLong("size"),
            Document.COLUMN_LAST_MODIFIED to e.optLong("mtime"),
            Document.COLUMN_FLAGS to flags,
        )
        c.newRow().let { r -> c.columnNames.forEach { r.add(vals[it]) } }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        ready()
        val c = MatrixCursor(projection ?: rootCols)
        Store.computers(context!!).forEach { comp ->
            // Only the columns the caller asked for: stuffing every value in crashes callers
            // that request a subset (MatrixCursor has exactly the requested columns).
            val vals = mapOf<String, Any?>(
                Root.COLUMN_ROOT_ID to comp.fp,
                Root.COLUMN_DOCUMENT_ID to "${comp.fp}|${comp.home.ifBlank { "/" }}",
                Root.COLUMN_TITLE to comp.name,
                Root.COLUMN_SUMMARY to "Ripple · ${comp.user.ifBlank { "computer" }}",
                Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD),
                Root.COLUMN_ICON to R.drawable.ic_stat_ripple,
                Root.COLUMN_MIME_TYPES to "*/*",
            )
            c.newRow().let { r -> c.columnNames.forEach { r.add(vals[it]) } }
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        ready()
        val (fp, path) = split(documentId)
        val c = MatrixCursor(projection ?: docCols)
        val r = request(fp, JSONObject().put("op", "stat").put("path", path))
        r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
        val e = r.optJSONObject("entry") ?: throw FileNotFoundException(path)
        if (path == Store.computer(context!!, fp)?.home) e.put("name", Store.computer(context!!, fp)?.name ?: e.optString("name"))
        addRow(c, fp, path, e)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        ready()
        val (fp, path) = split(parentDocumentId)
        val c = MatrixCursor(projection ?: docCols)
        val r = request(fp, JSONObject().put("op", "ls").put("path", path))
        r.optString("error").takeIf { it.isNotEmpty() }?.let { throw FileNotFoundException(it) }
        val a = r.optJSONArray("entries") ?: return c
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (e.optBoolean("hidden")) continue
            addRow(c, fp, path.trimEnd('/') + "/" + e.optString("name"), e)
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (pf, pp) = split(parentDocumentId)
        val (f, p) = split(documentId)
        return pf == f && (p == pp || p.startsWith(pp.trimEnd('/') + "/"))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        ready()
        val (fp, path) = split(documentId)
        val ctx = context!!
        val name = path.substringAfterLast('/')
        if (mode == "r") {
            val f = try { Transfers.downloadToCache(ctx, fp, path) { _, _ -> } } catch (e: Exception) { throw FileNotFoundException(e.message) }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        // Writing: collect into a local file, then send it when the app closes it.
        val dir = File(ctx.cacheDir, "write").apply { mkdirs() }
        val tmp = File(dir, "${System.nanoTime()}-$name")
        if (mode.contains('r') || mode.contains('a')) runCatching { Transfers.downloadToCache(ctx, fp, path) { _, _ -> }.copyTo(tmp, overwrite = true) }
        else tmp.createNewFile()
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode), io) { err ->
            if (err == null) {
                Thread {
                    runCatching {
                        val h = handle(fp)
                        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            Native.upload(h, pfd.fd, JSONObject().put("op", "put").put("path", path).put("overwrite", true).toString(), null)
                        }
                    }
                    tmp.delete()
                }.start()
            } else tmp.delete()
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        ready()
        val (fp, parent) = split(parentDocumentId)
        val path = parent.trimEnd('/') + "/" + displayName
        if (mimeType == Document.MIME_TYPE_DIR) {
            request(fp, JSONObject().put("op", "mkdir").put("path", path))
            return "$fp|$path"
        }
        val empty = File(context!!.cacheDir, "empty").apply { writeBytes(ByteArray(0)) }
        val h = handle(fp)
        val r = ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            try { JSONObject(Native.upload(h, pfd.fd, JSONObject().put("op", "put").put("path", path).toString(), null)) } catch (e: RuntimeException) { throw FileNotFoundException(e.message) }
        }
        return "$fp|${r.optString("path", path)}"
    }

    override fun deleteDocument(documentId: String) {
        ready()
        val (fp, path) = split(documentId)
        request(fp, JSONObject().put("op", "rm").put("path", path))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        ready()
        val (fp, path) = split(documentId)
        val to = path.substringBeforeLast('/') + "/" + displayName
        request(fp, JSONObject().put("op", "mv").put("path", path).put("to", to))
        return "$fp|$to"
    }
}
