package org.luma.music

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns search form and paging presentation state; network access and catalog rendering are injected.
 */
internal class SearchScreen(
    private val context: Context,
    private val target: LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val row: () -> LinearLayout,
    private val column: () -> LinearLayout,
    private val gap: (LinearLayout, Int) -> Unit,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val dp: (Int) -> Int,
    private val surface: (Float) -> android.graphics.drawable.GradientDrawable,
    private val textColor: Int,
    private val mutedColor: Int,
    private val translate: (String) -> String,
    private val canSearch: () -> Boolean,
    private val beginSearch: () -> Unit,
    private val searchCatalog: (String, String, Int, (JSONObject) -> Unit) -> Unit,
    private val renderResults: (LinearLayout, JSONArray, String) -> Unit,
    private val notify: (String) -> Unit,
) {
    fun render() {
        target.addView(text("DISCOVER  /  LUMA CATALOG", 10f, AppThemes.active.accent, true, true))
        target.addView(text("Найди свой звук", 31f, textColor, true, false))
        target.addView(text("Треки, альбомы, исполнители и плейлисты в одном поиске.", 13f, mutedColor, false, false))
        gap(target, 8)
        val box = EditText(context).apply {
            hint = translate("Трек, альбом, исполнитель или плейлист")
            setHintTextColor(mutedColor)
            setTextColor(textColor)
            setSingleLine()
            background = surface(dp(18).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val searchSurface = column().apply {
            background = surface(dp(22).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        searchSurface.addView(box)
        target.addView(searchSurface)

        val categories = listOf(
            "track" to "Треки",
            "album" to "Альбомы",
            "artist" to "Исполнители",
            "playlist" to "Плейлисты",
        )
        val categoryRow = row()
        target.addView(categoryRow)
        val results = column()
        val pageStatus = text("", 12f, mutedColor, false, false)
        var selectedKind = "track"
        var currentQuery = ""
        var currentPage = 0
        var pageSize = 20
        var hasNext = false
        var previous: LumaButton? = null
        var next: LumaButton? = null

        fun updateNavigation() {
            previous?.visibility = if (currentPage > 0) View.VISIBLE else View.INVISIBLE
            next?.visibility = if (hasNext) View.VISIBLE else View.INVISIBLE
        }

        fun runCatalogSearch(resetPage: Boolean = true) {
            if (!canSearch()) {
                notify("Подключите аккаунт для поиска в Яндекс Музыке")
                return
            }
            val query = box.text.toString().trim()
            if (query.isEmpty()) {
                notify("Введите запрос для поиска")
                return
            }
            if (query.length > 200) {
                notify("Запрос не должен превышать 200 символов")
                return
            }
            if (resetPage) currentPage = 0
            currentQuery = query
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(box.windowToken, 0)
            beginSearch()
            results.removeAllViews()
            pageStatus.text = "Ищем…"
            searchCatalog(currentQuery, selectedKind, currentPage) { data ->
                val items = data.optJSONArray("results") ?: JSONArray()
                val pager = data.optJSONObject("pager") ?: JSONObject()
                val total = pager.optInt("total", pager.optInt("totalCount", -1))
                pageSize = pager.optInt("perPage", pager.optInt("pageSize", 20)).coerceIn(1, 100)
                hasNext = if (total >= 0) {
                    (currentPage + 1) * pageSize < total
                } else {
                    items.length() >= pageSize
                }
                pageStatus.text = if (total >= 0) {
                    "Страница ${currentPage + 1} · найдено $total"
                } else {
                    "Страница ${currentPage + 1} · результатов: ${items.length()}"
                }
                renderResults(results, items, selectedKind)
                updateNavigation()
            }
        }

        fun drawCategories() {
            categoryRow.removeAllViews()
            categories.forEach { (kind, label) ->
                categoryRow.addView(
                    action(label, kind == selectedKind) {
                        selectedKind = kind
                        currentPage = 0
                        drawCategories()
                        runCatalogSearch()
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        rightMargin = dp(4)
                        topMargin = dp(8)
                    },
                )
            }
        }

        previous = action("‹ Назад", false) {
            if (currentPage > 0) {
                currentPage--
                runCatalogSearch(false)
            }
        }
        next = action("Ещё ›", false) {
            if (hasNext) {
                currentPage++
                runCatalogSearch(false)
            }
        }
        val navigation = row().apply {
            addView(previous!!, LinearLayout.LayoutParams(0, -2, 1f))
            addView(next!!, LinearLayout.LayoutParams(0, -2, 1f))
        }
        drawCategories()
        box.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                runCatalogSearch()
                true
            } else {
                false
            }
        }
        gap(target, 8)
        target.addView(
            action("Найти музыку", true) { runCatalogSearch() },
            LinearLayout.LayoutParams(-1, -2),
        )
        gap(target, 8)
        target.addView(pageStatus)
        target.addView(results)
        target.addView(navigation)
        updateNavigation()
    }
}
