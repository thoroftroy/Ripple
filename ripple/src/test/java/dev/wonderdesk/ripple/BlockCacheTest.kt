package dev.wonderdesk.ripple

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BlockCacheTest {
    @Test fun progressAccountsForPartialRetries() {
        val progress = RangeProgress(2)
        progress.update(0, 100)
        progress.update(1, 50)
        assertEquals(150L, progress.total())
        progress.update(0, 0)
        assertEquals(50L, progress.total())
        progress.update(0, 200)
        progress.complete(0, 200)
        assertEquals(250L, progress.total())
        progress.complete(1, 100)
        assertEquals(300L, progress.total())
    }

    @Test fun urgentReadDoesNotWaitBehindPrefetch() {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val cache = BlockCache { index ->
            if (index < 4) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            byteArrayOf(index.toByte())
        }
        try {
            repeat(4) { cache.request(it.toLong(), false) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(99), cache.request(99, true).get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            cache.close()
        }
    }

    @Test fun failedBlockCanBeRetried() {
        val attempts = AtomicInteger()
        val cache = BlockCache {
            if (attempts.incrementAndGet() == 1) error("temporary failure")
            byteArrayOf(42)
        }
        try {
            try {
                cache.request(0, true).get(2, TimeUnit.SECONDS)
                fail("The first fetch should fail")
            } catch (_: ExecutionException) { }
            assertArrayEquals(byteArrayOf(42), cache.request(0, true).get(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(42), cache.request(0, true).get(2, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
        } finally { cache.close() }
    }

    @Test fun simultaneousRequestsShareOneFetch() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val cache = BlockCache {
            attempts.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            byteArrayOf(7)
        }
        try {
            val first = cache.request(3, true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val second = cache.request(3, false)
            release.countDown()
            assertArrayEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, attempts.get())
        } finally {
            release.countDown()
            cache.close()
        }
    }
}
