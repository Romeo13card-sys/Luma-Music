package org.luma.music

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray

/**
 * Builds catalog result rows. Catalog requests and navigation remain owned by MainActivity.
 */
internal class CatalogResultsRenderer(
    private val context: Context,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val row: () -> LinearLayout,
    private val column: () -> LinearLayout,
    private val dp: (Int) -> Int,
    private val surface: (Float) -> android.graphics.drawable.GradientDrawable,
    private val renderTracks: (LinearLayout, List<Song>) -> Unit,
    private val openPlaylist: (String, String) -> Unit,
    private val notify: (String) -> Unit,
    private val textColor: Int,
    private val mutedColor: Int,
) {
    fun render(target: LinearLayout, items: JSONArray, kind: String) {
        target.removeAllViews()
        if (kind == "track") {
            val songs = (0 until items.length()).mapNotNull { index ->
                runCatching { Song.from(items.getJSONObject(index)) }.getOrNull()
            }
            renderTracks(target, songs)
            return
        }
        if (items.length() == 0) {
            target.addView(
                text("Ничего не найдено. Попробуйте изменить запрос.", 15f, mutedColor, false, false),
            )
            return
        }

        for (index in 0 until minOf(items.length(), 100)) {
            val item = items.optJSONObject(index) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() }
                ?: "Без названия"
            val artistNames = item.optJSONArray("artists")?.let { artists ->
                (0 until artists.length())
                    .mapNotNull { artists.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
            }.orEmpty()
            val subtitle = when (kind) {
                "album" -> listOfNotNull(
                    artistNames.takeIf { it.isNotBlank() },
                    item.optString("year").takeIf { it.isNotBlank() && it != "0" },
                    item.optString("genre").takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Альбом" }
                "artist" -> item.optJSONArray("genres")?.let { genres ->
                    (0 until genres.length())
                        .map { genres.optString(it) }
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }.orEmpty().ifBlank { "Исполнитель" }
                else -> item.optJSONObject("owner")?.optString("name").orEmpty().ifBlank { "Плейлист" }
            }

            val card = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(8), dp(10))
                background = surface(dp(16).toFloat())
                isFocusable = true
            }
            val art = AlbumArt(context).apply { bind(item.optString("cover")) }
            card.addView(
                art,
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(12) },
            )
            val labels = column()
            labels.addView(
                text(title, 16f, textColor, true, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            labels.addView(
                text(subtitle, 12f, mutedColor, false, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val count = item.optInt("trackCount", item.optInt("tracksCount", 0))
            if (count > 0) card.addView(text(count.toString(), 12f, mutedColor, false, false))

            card.setOnClickListener {
                if (kind == "playlist") {
                    val ownerId = item.optJSONObject("owner")?.optString("uid").orEmpty()
                    val playlistId = item.optString("kind")
                    if (
                        ownerId.isNotBlank() && ownerId.all { it.isDigit() } &&
                        playlistId.isNotBlank() && playlistId.all { it.isDigit() }
                    ) {
                        openPlaylist(title, "$ownerId:$playlistId")
                    } else {
                        notify("Не удалось определить владельца плейлиста")
                    }
                } else {
                    val details = buildString {
                        append(title)
                        if (subtitle.isNotBlank()) append("\n").append(subtitle)
                        item.optString("description").takeIf { it.isNotBlank() }?.let {
                            append("\n\n").append(it)
                        }
                        if (count > 0) append("\n\nТреков: ").append(count)
                        item.optString("id").takeIf { it.isNotBlank() }?.let {
                            append("\nID: ").append(it)
                        }
                    }
                    LumaDialog(context)
                        .setTitle(if (kind == "album") "Альбом" else "Исполнитель")
                        .setMessage(details)
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            }
            target.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }
    }
}
