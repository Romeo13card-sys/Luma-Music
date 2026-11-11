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
        private val persistence = QueuePersistence()
        @Volatile var restoring=false
        fun fence()=persistence.fence()
        fun acknowledgeRestore(){persistence.restored()}
        fun saveAsync(context: Context, snapshot: Snapshot) {
            val app = context.applicationContext
            persistence.save {
                try { QueueStore(app).save(snapshot) }
                catch (_: Exception) { android.util.Log.w("Luma", "Could not save playback queue") }
            }
        }
        fun clearAsync(context: Context) {
            val app = context.applicationContext
            persistence.clear {
                try { QueueStore(app).clear() }
                catch (_: Exception) { android.util.Log.w("Luma", "Could not clear playback queue") }
            }
        }
        fun runtime(songs:List<Song>,index:Int,position:Int)=JSONObject()
            .put("songs",JSONArray(songs.take(200).map {it.json()})).put("index",index).put("position",position)
        fun encode(snapshot:Snapshot):String =
            NativeCore.call("queue_normalize",runtime(snapshot.songs,snapshot.index,snapshot.position).toString()) as String

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

    fun load(): Snapshot? {
        if (persistence.cleared) return null
        return try {
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
    }

    fun clear() {
        database.call("queue_clear")
        prefs.edit().remove("state").commit()
    }
}
