package dev.wonderdesk.ripple

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Bounded cache with separate capacity for urgent reads and speculative reads. */
internal class BlockCache(private val fetch: (Long) -> ByteArray) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<Long, ByteArray>(64, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>?) = size > 64
    }
    private val pending = HashMap<Long, Future<ByteArray>>()
    private val urgentPool = Executors.newFixedThreadPool(2)
    private val prefetchPool = Executors.newFixedThreadPool(4)
    private var closed = false

    fun request(index: Long, urgent: Boolean): Future<ByteArray> = synchronized(lock) {
        check(!closed) { "File is closed" }
        cache[index]?.let { return CompletableFuture.completedFuture(it) }
        pending.getOrPut(index) {
            (if (urgent) urgentPool else prefetchPool).submit(Callable {
                try {
                    val bytes = fetch(index)
                    synchronized(lock) { if (!closed) cache[index] = bytes }
                    bytes
                } finally {
                    synchronized(lock) { pending.remove(index) }
                }
            })
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            pending.values.forEach { it.cancel(true) }
            pending.clear()
            cache.clear()
        }
        urgentPool.shutdownNow()
        prefetchPool.shutdownNow()
    }
}
