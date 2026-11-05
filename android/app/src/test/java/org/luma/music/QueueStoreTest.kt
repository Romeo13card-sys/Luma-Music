package org.luma.music

import org.junit.Assert.*
import org.junit.Test

class QueueStoreTest {
    @Test
    fun queueRoundTripKeepsPositionAndLocalPermissionUri() {
        val song =
            Song(
                "42",
                "Трек",
                "Исполнитель",
                uri = "content://documents/42",
                waveBatch = "expired-session",
            )
        val raw = QueueStore.encode(QueueStore.Snapshot(listOf(song), 0, 12345))
        val result = QueueStore.decode(raw)!!
        assertEquals(12345, result.position)
        assertEquals("content://documents/42", result.songs[0].uri)
        assertNull(result.songs[0].waveBatch)
        assertFalse(raw.contains("expired-session"))
    }

    @Test
    fun queueIsBoundedAndNetworkUrlsNeverPersist() {
        val songs =
            (1..500).map {
                Song("$it", "track", "artist", uri = "https://example.com/secret-stream")
            }
        val raw = QueueStore.encode(QueueStore.Snapshot(songs, 499, -5))
        val result = QueueStore.decode(raw)!!
        assertEquals(200, result.songs.size)
        assertEquals(199, result.index)
        assertEquals(0, result.position)
        assertFalse(raw.contains("secret-stream"))
        assertNull(QueueStore.decode("x".repeat(512001)))
    }
}
