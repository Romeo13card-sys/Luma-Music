package org.luma.music

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Renders a paged list of songs and reports user actions to the playback host.
 * Playback state and TrackSheet ownership remain outside this presentation class.
 */
internal class TrackListRenderer(
    private val context: Context,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val column: () -> LinearLayout,
    private val row: () -> LinearLayout,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val dp: (Int) -> Int,
    private val pageSize: Int,
    private val trackMarkers: MutableList<Triple<List<Song>, Int, TextView>>,
    private val initialIndex: Int,
    private val mutedColor: Int,
    private val onSelect: (List<Song>, Int) -> Unit,
    private val onVisible: (List<Song>) -> Unit,
    private val onUnavailable: () -> Unit,
    private val onTrackMenu: (Song) -> Unit,
    private val formatRange: (Int, Int, Int) -> String,
) {
    fun render(target: LinearLayout, songs: List<Song>) {
        if (songs.isEmpty()) {
            target.addView(text("Здесь пока тихо. Попробуйте другой запрос.", 16f, mutedColor, false, false))
            return
        }
        val host = column()
        target.addView(host)

        fun showPage(start: Int) {
            host.removeAllViews()
            trackMarkers.clear()
            onVisible(songs.drop(start).take(pageSize))
            songs.drop(start).take(pageSize).forEachIndexed { offset, song ->
                val index = start + offset
                val item = SongRow(context).apply { bind(song) }
                trackMarkers.add(Triple(songs, index, item.marker))
                item.setOnClickListener {
                    if (song.available) onSelect(songs, index) else onUnavailable()
                }
                item.more.setOnClickListener { onTrackMenu(song) }
                item.setOnLongClickListener {
                    onTrackMenu(song)
                    true
                }
                host.addView(
                    item,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
                )
            }

            val navigation = row()
            if (start > 0) {
                navigation.addView(
                    action("‹ Назад", false) { showPage(maxOf(0, start - pageSize)) },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
            if (start + pageSize < songs.size) {
                navigation.addView(
                    action("Ещё ›", false) { showPage(start + pageSize) },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
            host.addView(
                text(
                    formatRange(start + 1, minOf(start + pageSize, songs.size), songs.size),
                    12f,
                    mutedColor,
                    false,
                    false,
                ),
            )
            host.addView(navigation)
        }

        val firstPage = if (initialIndex >= 0) (initialIndex / pageSize) * pageSize else 0
        showPage(firstPage)
    }
}
