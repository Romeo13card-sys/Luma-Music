package org.luma.music

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class QueuePersistenceTest {
    @Test fun writesFinishInOrderAfterPlaybackWorkerStops() {
        val disk = Executors.newSingleThreadExecutor()
        val playback = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val writes = mutableListOf<String>()
        try {
            val queue = QueuePersistence(disk)
            queue.save { release.await(3,TimeUnit.SECONDS); writes.add("save") }
            queue.clear { writes.add("clear"); finished.countDown() }
            assertTrue(queue.cleared)
            playback.shutdownNow()
            release.countDown()
            assertTrue(finished.await(3,TimeUnit.SECONDS))
            assertEquals(listOf("save","clear"),writes)
            assertTrue(queue.cleared)
        } finally { release.countDown(); disk.shutdownNow(); playback.shutdownNow() }
    }
}
