package dev.wonderdesk.ripple

import android.content.Context
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import dev.wonderdesk.ripple.net.Native
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Fetching slices of a computer's file over Ripple, one stream per slice. */
object Ranges {
    /** Reads exactly len bytes, retrying a dropped or incomplete transfer. */
    fun fetch(c: Context, fp: String, path: String, start: Long, len: Int, progress: (Int) -> Unit = {}): ByteArray {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                progress(0)
                val h = Transfers.handle(c, fp, 20_000)
                val sh = Native.open(h, JSONObject().put("op", "get").put("path", path).put("offset", start).put("len", len).toString())
                try {
                    val head = JSONObject(Native.streamReply(sh))
                    if (head.has("error")) throw RuntimeException(head.optString("error"))
                    val out = ByteArray(len); var got = 0
                    while (got < len) {
                        val f = Native.read(sh) ?: break
                        if (f.isEmpty() || f[0] != Native.KIND_DATA) break // {"done": true} or an error message
                        val n = minOf(f.size - 1, len - got)
                        System.arraycopy(f, 1, out, got, n); got += n
                        progress(got)
                    }
                    if (got != len) throw java.io.EOFException("The computer sent only $got of $len bytes")
                    return out
                } finally { Native.closeStream(sh) }
            } catch (e: Exception) { last = e; if (attempt < 2) Thread.sleep(250L * (attempt + 1)) }
        }
        throw last ?: RuntimeException("Couldn't read the file")
    }

    /**
     * Downloads a whole file with several streams at once, writing each piece where it belongs.
     * One stream is limited by how fast a single flow ramps up; several fill the link much sooner.
     */
    fun parallelDownload(c: Context, fp: String, path: String, size: Long, out: java.nio.channels.FileChannel, onProgress: (Long) -> Unit) {
        val seg = 4L * 1024 * 1024
        val next = AtomicLong(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val workers = if (size > 256L * 1024 * 1024) 6 else 4
        val progress = RangeProgress(workers)
        val pool = Executors.newFixedThreadPool(workers)
        val tasks = (0 until workers).map { worker ->
            pool.submit {
                try {
                    while (failure.get() == null) {
                        val start = next.getAndAdd(seg)
                        if (start >= size) break
                        val len = minOf(seg, size - start).toInt()
                        val b = fetch(c, fp, path, start, len) { progress.update(worker, it) }
                        if (b.size != len) throw RuntimeException("The computer sent less of the file than it should have")
                        val buf = java.nio.ByteBuffer.wrap(b); var pos = start
                        while (buf.hasRemaining()) pos += out.write(buf, pos)
                        progress.complete(worker, len)
                    }
                } catch (e: Exception) { failure.compareAndSet(null, e) }
            }
        }
        try {
            while (tasks.any { !it.isDone }) { onProgress(progress.total()); Thread.sleep(250) }
            onProgress(progress.total())
        } finally { pool.shutdownNow() }
        failure.get()?.let { throw it }
    }
}

/** Counts bytes in flight without double counting a finished or retried range. */
internal class RangeProgress(workers: Int) {
    private var completed = 0L
    private val partial = IntArray(workers)

    @Synchronized fun update(worker: Int, bytes: Int) { partial[worker] = bytes }
    @Synchronized fun complete(worker: Int, bytes: Int) { completed += bytes; partial[worker] = 0 }
    @Synchronized fun total(): Long = completed + partial.sumOf { it.toLong() }
}

/**
 * A computer file that apps read as if it were on this phone, fetched in slices as they ask for it. A video starts at once and
 * seeking is quick, instead of waiting for the whole file to arrive first. The next slices are fetched ahead of the player.
 */
class RemoteFile(private val ctx: Context, private val fp: String, private val path: String, private val total: Long,
                 private val onClose: () -> Unit = {}) : ProxyFileDescriptorCallback() {
    // Return a small first block instead of waiting for 2 MiB over the network.
    private val block = 256 * 1024
    private val blocks = BlockCache { idx ->
        Ranges.fetch(ctx, fp, path, idx * block, minOf(block.toLong(), total - idx * block).toInt())
    }

    override fun onGetSize(): Long = total

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= total) return 0
        var done = 0; var at = offset
        try {
            while (done < size && at < total) {
                val idx = at / block
                val current = blocks.request(idx, true)
                // Start the following blocks before waiting for the first one.
                for (k in 1..6) {
                    val next = idx + k
                    if (next * block < total) blocks.request(next, false)
                }
                val b = current.get()
                val inside = (at - idx * block).toInt()
                val n = minOf(size - done, b.size - inside)
                if (n <= 0) break
                System.arraycopy(b, inside, data, done, n); done += n; at += n
            }
        } catch (e: Exception) { throw ErrnoException("read", OsConstants.EIO) }
        return done
    }

    override fun onRelease() {
        try { blocks.close() } finally { onClose() }
    }
}
