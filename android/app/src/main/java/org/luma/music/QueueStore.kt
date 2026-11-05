package org.luma.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persist metadata only. No tokens, expiring stream URLs or wave feedback sessions. */
class QueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("queue_state", Context.MODE_PRIVATE)

    data class Snapshot(val songs: List<Song>, val index: Int, val position: Int)

    companion object {
        private fun localUri(uri: String?) =
            uri?.takeIf { it.startsWith("content://") || it.startsWith("file://") }

        fun encode(snapshot: Snapshot): String {
            val items = JSONArray()
            snapshot.songs.take(200).forEach { song ->
                items.put(
                    JSONObject()
                        .put("id", song.id.take(256))
                        .put("title", song.title.take(512))
                        .put("artists", song.artist.take(512))
                        .put("available", song.available)
                        .apply { localUri(song.uri)?.let { put("uri", it.take(4096)) } }
                )
            }
            return JSONObject()
                .put("songs", items)
                .put("index", snapshot.index.coerceIn(0, maxOf(0, items.length() - 1)))
                .put("position", snapshot.position.coerceAtLeast(0))
                .toString()
        }

        fun decode(raw: String?): Snapshot? {
            if (raw == null || raw.length > 512_000) return null
            val data = JSONObject(raw)
            val items = data.getJSONArray("songs")
            val songs =
                (0 until minOf(items.length(), 200)).map { i ->
                    val v = items.getJSONObject(i)
                    Song.from(v).copy(uri = localUri(v.optString("uri")))
                }
            return if (songs.isEmpty()) null
            else
                Snapshot(
                    songs,
                    data.optInt("index").coerceIn(0, songs.lastIndex),
                    data.optInt("position").coerceAtLeast(0),
                )
        }
    }

    fun save(snapshot: Snapshot) {
        prefs.edit().putString("state", encode(snapshot)).commit()
    }

    fun load(): Snapshot? =
        try {
            decode(prefs.getString("state", null))
        } catch (_: Exception) {
            clear()
            null
        }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
