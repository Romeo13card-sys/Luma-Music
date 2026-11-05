package org.luma.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persist metadata only. No tokens, expiring stream URLs or wave feedback sessions. */
class QueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("queue_state", Context.MODE_PRIVATE)
    private val database = NativeDatabase(context)

    data class Snapshot(val songs: List<Song>, val index: Int, val position: Int)

    companion object {
        fun encode(snapshot: Snapshot): String {
            val items = JSONArray()
            snapshot.songs.take(200).forEach { song ->
                items.put(
                    JSONObject()
                        .put("id", song.id)
                        .put("title", song.title)
                        .put("artists", song.artist)
                        .put("available", song.available)
                        .apply { song.uri?.let { put("uri", it) } }
                )
            }
            val raw =
                JSONObject()
                    .put("songs", items)
                    .put("index", snapshot.index)
                    .put("position", snapshot.position)
                    .toString()
            return NativeCore.call("queue_normalize", raw) as String
        }

        fun decode(raw: String?): Snapshot? {
            if (raw == null) return null
            val data = JSONObject(NativeCore.call("queue_normalize", raw) as String)
            val items = data.getJSONArray("songs")
            val songs =
                (0 until items.length()).map { i ->
                    val v = items.getJSONObject(i)
                    Song.from(v).copy(uri = v.optString("uri").takeIf { it.isNotEmpty() })
                }
            return if (songs.isEmpty()) null
            else Snapshot(songs, data.getInt("index"), data.getInt("position"))
        }
    }

    fun save(snapshot: Snapshot) {
        database.call("queue_save", encode(snapshot))
    }

    fun load(): Snapshot? = try {
        val legacy = prefs.getString("state", null)
        var saved = database.call("queue_load") as? String
        if (legacy != null && saved == null) {
            // Remove the old snapshot only after a successful Rust transaction.
            database.call("queue_import", legacy)
            saved = database.call("queue_load") as? String
        }
        val decoded = decode(saved)
        if (legacy != null && saved != null) prefs.edit().remove("state").commit()
        decoded
    } catch (_: Exception) {
        // Preserve the legacy snapshot when migration fails.
        null
    }

    fun clear() {
        database.call("queue_clear")
        prefs.edit().remove("state").commit()
    }
}
