package org.luma.music

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** App-scoped writes must survive PlaybackService.shutdownNow(). */
internal class QueuePersistence(private val writer: Executor = Executors.newSingleThreadExecutor()) {
    @Volatile var cleared = false
        private set
    @Synchronized fun save(action: () -> Unit) {
        cleared = false
        writer.execute(action)
    }
    @Synchronized fun clear(action: () -> Unit) {
        cleared = true
        writer.execute(action)
    }
}
