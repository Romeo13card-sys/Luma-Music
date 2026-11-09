package org.luma.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SongMetadataTest {
    @Test fun oldAndNewTrackMetadataRemainCompatible() {
        val old=JSONObject().put("id","42").put("title","Название").put("artists","Artist").put("available",true)
        assertNull(Song.from(old).cover)
        old.put("cover",JSONObject.NULL)
        assertNull(Song.from(old).cover)
        val cover="https://avatars.yandex.net/get-music-content/42/album/%%"
        old.put("cover",cover)
        val song=Song.from(old)
        assertEquals(cover,song.cover)
        assertEquals("Название",song.title)
        assertTrue(song.available)
        val runtime=song.copy(album="7",waveBatch="batch",waveStation="mood:calm",waveSession="session-1",uri="file:///music.mp3")
        assertEquals(runtime,Song.from(runtime.json()))
        old.put("uri","https://example.com/expiring-token").put("album",JSONObject.NULL)
        assertNull(Song.from(old).uri)
        assertNull(Song.from(old).album)
    }
}
