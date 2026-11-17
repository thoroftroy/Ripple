package dev.wonderdesk.ripple

import android.content.Context
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import dev.wonderdesk.ripple.net.Native
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Fetching slices of a computer's file over Ripple, one stream per slice. */
object Ranges {
    /** Reads bytes [start, start+len) of [path]; fewer at the end of the file. Retries once on a dropped connection. */
    fun fetch(c: Context, fp: String, path: String, start: Long, len: Int): ByteArray {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
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
                    }
                    return if (got == len) out else out.copyOf(got)
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
        val next = AtomicLong(0); val done = AtomicLong(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val workers = if (size > 256L * 1024 * 1024) 6 else 4
        val pool = Executors.newFixedThreadPool(workers)
        val tasks = (0 until workers).map {
            pool.submit {
                try {
                    while (failure.get() == null) {
                        val start = next.getAndAdd(seg)
                        if (start >= size) break
                        val len = minOf(seg, size - start).toInt()
                        val b = fetch(c, fp, path, start, len)
                        if (b.size != len) throw RuntimeException("The computer sent less of the file than it should have")
                        val buf = java.nio.ByteBuffer.wrap(b); var pos = start
                        while (buf.hasRemaining()) pos += out.write(buf, pos)
                        done.addAndGet(len.toLong())
                    }
                } catch (e: Exception) { failure.compareAndSet(null, e) }
            }
        }
        try {
            while (tasks.any { !it.isDone }) { onProgress(done.get()); Thread.sleep(250) }
            onProgress(done.get())
        } finally { pool.shutdownNow() }
        failure.get()?.let { throw it }
    }
}

/**
 * A computer file that apps read as if it were on this phone, fetched in slices as they ask for it. A video starts at once and
 * seeking is quick, instead of waiting for the whole file to arrive first. The next slices are fetched ahead of the player.
 */
class RemoteFile(private val ctx: Context, private val fp: String, private val path: String, private val total: Long) : ProxyFileDescriptorCallback() {
    private val block = 2 shl 20
    private val lock = Any()
    private val cache = object : LinkedHashMap<Long, ByteArray>(24, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>?) = size > 32 }
    private val pending = HashMap<Long, Future<ByteArray>>()
    private val pool = Executors.newFixedThreadPool(6)

    override fun onGetSize(): Long = total

    private fun slice(idx: Long): Future<ByteArray> = synchronized(lock) {
        cache[idx]?.let { return java.util.concurrent.CompletableFuture.completedFuture(it) }
        pending.getOrPut(idx) {
            pool.submit(Callable {
                val b = Ranges.fetch(ctx, fp, path, idx * block, minOf(block.toLong(), total - idx * block).toInt())
                synchronized(lock) { cache[idx] = b; pending.remove(idx) }
                b
            })
        }
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= total) return 0
        var done = 0; var at = offset
        try {
            while (done < size && at < total) {
                val idx = at / block
                val b = slice(idx).get()
                val inside = (at - idx * block).toInt()
                val n = minOf(size - done, b.size - inside)
                if (n <= 0) break
                System.arraycopy(b, inside, data, done, n); done += n; at += n
            }
        } catch (e: Exception) { throw ErrnoException("read", OsConstants.EIO) }
        val nextIdx = at / block
        // Keep up to 12 MB in flight ahead of the reader, spread over several streams, so the link stays full.
        for (k in 1..6) { val i = nextIdx + k - 1; if (i * block < total) slice(i) }
        return done
    }

    override fun onRelease() { pool.shutdownNow(); synchronized(lock) { cache.clear(); pending.clear() } }
}
