package dev.romeo.yamusic

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import java.util.concurrent.Executors
import org.json.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var mini: TextView
    private var playerTitle: TextView? = null
    private var playerSubtitle: TextView? = null
    private var playButton: TextView? = null
    private var time: TextView? = null
    private var seek: SeekBar? = null
    private var dragging = false
    private var page = "Главная"
    private var token = ""
    private var demo = false
    private var generation = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var visible = false
    private val tick =
        object : Runnable {
            override fun run() {
                if (!visible) return
                refreshPlayer()
                handler.postDelayed(this, 500)
            }
        }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val white = Color.rgb(239, 247, 248)
    private val muted = Color.rgb(172, 193, 201)
    private val mint = Color.rgb(182, 244, 221)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        token = TokenStore(this).load().orEmpty()
        if (token.isEmpty()) login() else shell("Главная")
        handleOAuth(intent)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun text(value: String, size: Float = 16f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, dp(6), 0, dp(6))
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun gap(target: LinearLayout, height: Int = 12) {
        target.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun action(label: String, accent: Boolean = false, click: () -> Unit) =
        text(label, 16f, if (accent) Color.rgb(16, 47, 45) else white, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = glass(dp(24).toFloat(), accent)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                click()
            }
        }

    private fun panel(target: LinearLayout): LinearLayout =
        column().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = glass(dp(28).toFloat())
            target.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    private fun backdrop() {
        generation++
        playerTitle = null
        playerSubtitle = null
        playButton = null
        seek = null
        time = null
        val frame = FrameLayout(this)
        frame.addView(Atmosphere(this), FrameLayout.LayoutParams(-1, -1))
        root = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        root.setOnApplyWindowInsetsListener { v, inset ->
            val bars = inset.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(
                dp(20) + bars.left,
                bars.top + dp(8),
                dp(20) + bars.right,
                bars.bottom + dp(8),
            )
            inset
        }
        root.requestApplyInsets()
    }

    private fun request(op: String, arg: String = "", done: (Any) -> Unit) {
        val version = generation
        val credential = token
        status.text = "Загрузка…"
        executor.execute {
            val result =
                try {
                    JSONObject(Native.request(credential, op, arg))
                } catch (_: Exception) {
                    JSONObject().put("ok", false).put("error", "Не удалось обработать ответ")
                }
            runOnUiThread {
                if (destroyed || version != generation) return@runOnUiThread
                if (result.optBoolean("ok")) {
                    status.text = ""
                    done(result.get("data"))
                } else status.text = result.optString("error", "Ошибка сервиса")
            }
        }
    }

    private fun login() {
        backdrop()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column()
        scroll.addView(body)
        gap(body, 36)
        body.addView(text(getString(R.string.app_name), 18f, mint, true))
        body.addView(text(getString(R.string.author_credit), 12f, muted))
        gap(body, 20)
        body.addView(text("Музыка.\nВ новом свете.", 40f, white, true))
        gap(body, 8)
        body.addView(text("Ваш аккаунт Яндекс Музыки.\nЛёгкий плеер. Ничего лишнего.", 17f, muted))
        gap(body, 28)
        val card = panel(body)
        card.addView(text("Добро пожаловать", 22f, white, true))
        val input =
            EditText(this).apply {
                hint = "OAuth-токен"
                setHintTextColor(muted)
                setTextColor(white)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        card.addView(input)
        status = text("Токен хранится зашифрованным через Android Keystore.", 13f, muted)
        card.addView(status)
        card.addView(
            action("Подключить аккаунт", true) {
                token = input.text.toString().trim()
                generation++
                request("account") {
                    try {
                        TokenStore(this).save(token)
                        input.text.clear()
                        demo = false
                        shell("Главная")
                    } catch (_: Exception) {
                        status.text = "Не удалось сохранить токен"
                    }
                }
            }
        )
        gap(body)
        body.addView(action("Войти через браузер") { browserLogin() })
        gap(body)
        body.addView(
            action("Попробовать без аккаунта") {
                demo = true
                shell("Главная")
            }
        )
        body.addView(
            text(
                "Неофициальный клиент. Для каталога и прослушивания нужна подписка. Для входа через браузер нужен Client ID зарегистрированного OAuth-приложения Яндекса.",
                12f,
                muted,
            )
        )
    }

    private fun browserLogin() {
        val prefs = getSharedPreferences("oauth", MODE_PRIVATE)
        val input =
            EditText(this).apply {
                hint = "Client ID OAuth-приложения"
                setSingleLine()
                setText(prefs.getString("client", ""))
            }
        AlertDialog.Builder(this)
            .setTitle("Вход через Яндекс")
            .setMessage(
                "Введите Client ID вашего OAuth-приложения с доступом к Яндекс Музыке. В нём должен быть зарегистрирован Redirect URI: yamusicrust://oauth. Client ID не является паролем."
            )
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть браузер") { _, _ ->
                val client = input.text.toString().trim()
                if (!client.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
                    status.text = "Некорректный Client ID"
                    return@setPositiveButton
                }
                val state = java.util.UUID.randomUUID().toString()
                prefs
                    .edit()
                    .putString("client", client)
                    .putString("state", state)
                    .putLong("created", System.currentTimeMillis())
                    .apply()
                val uri =
                    android.net.Uri.parse("https://oauth.yandex.ru/authorize")
                        .buildUpon()
                        .appendQueryParameter("response_type", "token")
                        .appendQueryParameter("client_id", client)
                        .appendQueryParameter("redirect_uri", "yamusicrust://oauth")
                        .appendQueryParameter("state", state)
                        .build()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    status.text = "Не найден браузер"
                }
            }
            .show()
    }

    private fun handleOAuth(incoming: Intent?) {
        val uri = incoming?.data ?: return
        if (uri.scheme != "yamusicrust" || uri.host != "oauth") return
        // Do not leave credentials attached to the activity intent.
        intent = Intent(this, MainActivity::class.java)
        val prefs = getSharedPreferences("oauth", MODE_PRIVATE)
        val params =
            android.net.Uri.parse(
                "https://callback.invalid/?" + (uri.fragment ?: uri.query.orEmpty())
            )
        val expected = prefs.getString("state", null)
        val age = System.currentTimeMillis() - prefs.getLong("created", 0)
        prefs.edit().remove("state").remove("created").apply()
        if (
            expected == null || params.getQueryParameter("state") != expected || age !in 0..600000
        ) {
            status.text = "Вход отклонён: неверный или просроченный ответ. Начните вход заново."
            return
        }
        val credential = params.getQueryParameter("access_token")
        if (credential.isNullOrBlank()) {
            status.text = "Яндекс не предоставил токен. Проверьте права OAuth-приложения."
            return
        }
        token = credential
        generation++
        request("account") {
            try {
                TokenStore(this).save(token)
                demo = false
                shell("Главная")
            } catch (_: Exception) {
                token = ""
                status.text = "Не удалось сохранить токен"
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        handleOAuth(newIntent)
    }

    private fun shell(target: String, loadContent: Boolean = true) {
        page = target
        backdrop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val header = row()
        header.addView(
            text(getString(R.string.app_name), 18f, white, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(action(if (demo) "Демо" else "Аккаунт") { settings() })
        root.addView(header)
        status = text(if (demo) "ДЕМОРЕЖИМ · БЕЗ ПОДКЛЮЧЕНИЯ К ЯНДЕКСУ" else "", 11f, mint)
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = false }
        body = column()
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        mini = action("Выберите трек") { fullPlayer() }
        root.addView(mini)
        gap(root, 8)
        val nav =
            row().apply {
                background = glass(dp(28).toFloat())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        listOf("Главная", "Поиск", "Медиатека").forEach { name ->
            nav.addView(
                action(name, name == page) { shell(name) },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }
        root.addView(nav)
        if (loadContent)
            when (target) {
                "Поиск" -> search()
                "Медиатека" -> library()
                else -> home()
            }
        refreshPlayer()
    }

    private fun home() {
        gap(body, 18)
        body.addView(text("Звучит по-вашему", 32f, white, true))
        body.addView(text("Ваш ритм. Ваш момент.", 16f, muted))
        gap(body, 16)
        val hero = panel(body)
        val art = AlbumArt(this)
        hero.addView(art, LinearLayout.LayoutParams(-1, dp(190)))
        gap(hero)
        hero.addView(
            text(if (demo) "Почувствуйте интерфейс" else "Ваша коллекция", 25f, white, true)
        )
        hero.addView(
            text(
                if (demo) "Стеклянные панели и локальный тестовый звук. Это не каталог Яндекса."
                else "Любимые треки, плейлисты и музыка с устройства.",
                14f,
                muted,
            )
        )
        hero.addView(
            action(if (demo) "Слушать тестовый звук" else "Открыть избранное", true) {
                if (demo) playDemo() else showTracks("Избранное", "likes")
            }
        )
        val local = panel(body)
        local.addView(text("На устройстве", 22f, white, true))
        local.addView(text("Откройте свой аудиофайл — без загрузки на сервер.", 14f, muted))
        local.addView(action("Выбрать файл") { openFile() })
        body.addView(text(getString(R.string.author_credit), 13f, muted))
    }

    private fun search() {
        body.addView(text("Найдите своё", 32f, white, true))
        val box =
            EditText(this).apply {
                hint = "Трек или исполнитель"
                setHintTextColor(muted)
                setTextColor(white)
                setSingleLine()
                background = glass()
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
        body.addView(box)
        gap(body)
        body.addView(
            action("Искать", true) {
                if (demo) {
                    status.text = "Подключите аккаунт для поиска в Яндекс Музыке"
                    return@action
                }
                val query = box.text.toString().trim()
                if (query.isEmpty()) return@action
                generation++
                request("search", query) { data ->
                    while (body.childCount > 4) body.removeViewAt(4)
                    renderTracks(body, (data as JSONArray).songs())
                }
            }
        )
        gap(body)
        body.addView(text("Поиск по каталогу Яндекс Музыки", 14f, muted))
    }

    private fun library() {
        body.addView(text("Всё любимое", 32f, white, true))
        gap(body)
        body.addView(
            action("Избранное · последние 100") {
                if (demo) playDemo() else showTracks("Избранное", "likes")
            }
        )
        gap(body)
        body.addView(action("Аудиофайл с устройства") { openFile() })
        gap(body)
        body.addView(text("Плейлисты", 22f, white, true))
        if (demo) {
            body.addView(text("Подключите аккаунт, чтобы увидеть свои плейлисты.", 15f, muted))
            return
        }
        request("library") { data ->
            val items = data as JSONArray
            if (items.length() == 0) body.addView(text("Пока нет плейлистов", 15f, muted))
            for (i in 0 until items.length()) {
                val p = items.getJSONObject(i)
                val title = p.optString("title", "Плейлист")
                val owner = p.optJSONObject("owner")?.optString("uid").orEmpty()
                val kind = p.optString("kind")
                body.addView(action("$title  ›") { showTracks(title, "playlist", "$owner:$kind") })
                gap(body, 8)
            }
        }
    }

    private fun JSONArray.songs() = (0 until length()).map { Song.from(getJSONObject(it)) }

    private fun showTracks(title: String, op: String, arg: String = "") {
        shell("Медиатека", false)
        generation++
        body.removeAllViews()
        body.addView(text(title, 30f, white, true))
        request(op, arg) { renderTracks(body, (it as JSONArray).songs()) }
    }

    private fun renderTracks(target: LinearLayout, songs: List<Song>) {
        if (songs.isEmpty()) {
            target.addView(text("Здесь пока тихо. Попробуйте другой запрос.", 16f, muted))
            return
        }
        songs.forEachIndexed { i, song ->
            val item =
                row().apply {
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = glass(dp(20).toFloat())
                }
            item.addView(
                text(String.format(java.util.Locale.ROOT, "%02d", i + 1), 13f, mint),
                LinearLayout.LayoutParams(dp(36), -2),
            )
            val labels = column()
            labels.addView(text(song.title, 16f, white, true))
            labels.addView(text(song.artist, 13f, muted))
            item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(text(if (song.available) "▶" else "—", 18f, mint))
            item.isEnabled = song.available
            item.alpha = if (song.available) 1f else .45f
            item.setOnClickListener {
                PlaybackService.queue = songs
                PlaybackService.command(this, "play", i)
                refreshPlayer()
            }
            target.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun fullPlayer() {
        if (PlaybackService.queue.isEmpty()) {
            status.text = "Сначала выберите трек"
            return
        }
        backdrop()
        root.addView(action("⌄  Сейчас играет") { shell(page) })
        val scroll = ScrollView(this)
        body = column()
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        gap(body, 20)
        body.addView(AlbumArt(this), LinearLayout.LayoutParams(-1, dp(270)))
        gap(body, 24)
        playerTitle = text("", 27f, white, true)
        body.addView(playerTitle)
        playerSubtitle = text("", 16f, muted)
        body.addView(playerSubtitle)
        seek =
            SeekBar(this).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(mint)
                thumbTintList = android.content.res.ColorStateList.valueOf(mint)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(v: SeekBar) {
                            dragging = true
                        }

                        override fun onStopTrackingTouch(v: SeekBar) {
                            PlaybackService.instance?.seek(v.progress)
                            dragging = false
                        }

                        override fun onProgressChanged(v: SeekBar, p: Int, user: Boolean) {}
                    }
                )
            }
        body.addView(seek)
        time = text("0:00", 13f, muted)
        body.addView(time)
        val controls = row()
        controls.addView(
            action("◀◀") { PlaybackService.command(this, "previous") },
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        playButton =
            action("▶", true) {
                PlaybackService.command(this, "toggle")
                refreshPlayer()
            }
        controls.addView(
            playButton,
            LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )
        controls.addView(
            action("▶▶") { PlaybackService.command(this, "next") },
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        body.addView(controls)
        gap(body)
        body.addView(
            action("Перемешивание: ${if(PlaybackService.shuffle) "вкл" else "выкл"}") {
                PlaybackService.shuffle = !PlaybackService.shuffle
                fullPlayer()
            }
        )
        gap(body, 8)
        body.addView(
            action("Повтор трека: ${if(PlaybackService.repeat) "вкл" else "выкл"}") {
                PlaybackService.repeat = !PlaybackService.repeat
                fullPlayer()
            }
        )
        gap(body)
        body.addView(text("Очередь", 22f, white, true))
        renderTracks(body, PlaybackService.queue)
        refreshPlayer()
    }

    private fun refreshPlayer() {
        val song = PlaybackService.queue.getOrNull(PlaybackService.index)
        if (::mini.isInitialized)
            mini.text =
                if (song == null) "Открыть плеер  ↑"
                else "${if(PlaybackService.playing) "♫" else "Ⅱ"}  ${song.title}  ↑"
        playerTitle?.text = song?.title ?: "Выберите трек"
        playerSubtitle?.text = "${song?.artist.orEmpty()}\n${PlaybackService.message}"
        playButton?.text = if (PlaybackService.playing) "Ⅱ" else "▶"
        val service = PlaybackService.instance
        if (!dragging) {
            seek?.max = service?.duration() ?: 0
            seek?.progress = service?.position() ?: 0
        }
        fun clock(ms: Int) =
            "${ms/60000}:${String.format(java.util.Locale.ROOT,"%02d",(ms/1000)%60)}"
        time?.text = "${clock(service?.position() ?: 0)}   /   ${clock(service?.duration() ?: 0)}"
    }

    private fun settings() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(
                "${getString(R.string.author_credit)}\n\nТема: ${getString(R.string.theme_name)}.\n\nЛёгкий стеклянный интерфейс без постоянного размытия.\n\nНеофициальный клиент Яндекс Музыки. Токен защищён Android Keystore.\n\nДоступность треков зависит от подписки и региона."
            )
            .setPositiveButton("Закрыть", null)
            .setNeutralButton(if (demo) "Войти" else "Выйти") { _, _ ->
                stopService(Intent(this, PlaybackService::class.java))
                PlaybackService.queue = emptyList()
                PlaybackService.index = -1
                TokenStore(this).clear()
                token = ""
                demo = false
                login()
            }
            .show()
    }

    private fun openFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("audio/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
            10,
        )
    }

    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {}
                var title = "Аудиофайл"
                contentResolver
                    .query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { if (it.moveToFirst()) title = it.getString(0) }
                PlaybackService.queue =
                    listOf(Song("local", title, "На устройстве", uri = uri.toString()))
                PlaybackService.command(this, "play", 0)
                fullPlayer()
            }
    }

    private fun playDemo() {
        val file = java.io.File(cacheDir, "glass-demo.wav")
        if (!file.exists())
            assets.open("glass-demo.wav").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        PlaybackService.queue =
            listOf(
                Song(
                    "demo",
                    "Luma · тестовый звук",
                    "Локальный синтезатор · 8 секунд",
                    uri = android.net.Uri.fromFile(file).toString(),
                )
            )
        PlaybackService.command(this, "play", 0)
        fullPlayer()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        handler.post(tick)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        executor.shutdownNow()
        super.onDestroy()
    }
}
