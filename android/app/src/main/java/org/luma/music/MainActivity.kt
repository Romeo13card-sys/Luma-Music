package org.luma.music

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
    private var mini: TextView? = null
    private var miniArtist: TextView? = null
    private var miniToggle: TextView? = null
    private val trackMarkers = mutableListOf<Triple<List<Song>, Int, TextView>>()
    private var playerTitle: TextView? = null
    private var playerSubtitle: TextView? = null
    private var playButton: TextView? = null
    private var time: TextView? = null
    private var seek: SeekBar? = null
    private var dragging = false
    private var page = "Главная"
    private var creditScreen = false
    private var settingsSection = ""
    private var pendingUpdate: java.io.File? = null
    private var lyricsDialog: AlertDialog? = null
    private var checkingUpdates = false
    private val hardwarePolicy by lazy {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        RenderPolicy.choose(manager.isLowRamDevice, memory.totalMem, manager.memoryClass)
    }
    private val economyPolicy = RenderPolicy(true)
    private val renderPolicy
        get() =
            if (preferences.getBoolean("reduce_effects", false)) economyPolicy else hardwarePolicy

    private val palette
        get() =
            AppThemes.resolve(
                preferences.getString("theme_mode", "system").orEmpty(),
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES,
            )

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
                handler.postDelayed(this, renderPolicy.uiIntervalMs)
            }
        }

    private val preferences by lazy { SettingsStore.get(this) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val white
        get() = palette.text

    private val muted
        get() = palette.muted

    private val mint
        get() = palette.accent

    override fun onCreate(state: Bundle?) {
        setTheme(
            if (palette.light) R.style.Theme_Luma_Light
            else R.style.Theme_Luma_Dark
        )
        super.onCreate(state)
        // Public update feed no longer uses stored GitHub credentials.
        TokenStore(this, "github_auth").clear()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                navigateBack()
            }
        }
        PlaybackService.shuffle = preferences.getBoolean("shuffle", false)
        PlaybackService.repeat = preferences.getBoolean("repeat", false)
        PlaybackService.restore(this)
        token = TokenStore(this).load().orEmpty()
        demo = state?.getBoolean("demo", false) ?: false
        page = state?.getString("page") ?: "Главная"
        if (token.isEmpty() && !demo) login() else shell(page)
        if (state?.getBoolean("settings", false) == true) settings(state.getString("settings_section").orEmpty())
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
            textSize = size * preferences.getInt("font_size", 100) / 100f
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
        text(label, 16f, if (accent) Color.rgb(25, 25, 25) else white, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = surface(dp(14).toFloat(), accent)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener {
                if (preferences.getBoolean("haptics", true)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                click()
            }
        }

    private fun panel(target: LinearLayout): LinearLayout =
        column().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = surface(dp(18).toFloat())
            target.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    private fun backdrop() {
        AppThemes.active = palette
        creditScreen = false
        mini = null
        miniArtist = null
        miniToggle = null
        trackMarkers.clear()
        body = column()
        status = text("")
        generation++
        playerTitle = null
        playerSubtitle = null
        playButton = null
        seek = null
        time = null
        val frame = FrameLayout(this)
        frame.setBackgroundColor(palette.background)
        root = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        // PhoneWindow.getInsetsController() can throw until setContentView creates DecorView.
        val bars =
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (palette.light) bars else 0, bars)
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

    private fun request(
        op: String,
        arg: String = "",
        finished: (() -> Unit)? = null,
        done: (Any) -> Unit,
    ) {
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
                finished?.invoke()
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
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column()
        scroll.addView(body)
        gap(body, 24)
        body.addView(text(getString(R.string.app_name), 18f, mint, true))
        body.addView(text("Ваша музыка.\nВаш момент.", 36f, white, true))
        body.addView(
            text("Войдите в Яндекс Музыку, чтобы открыть свою коллекцию и Мою волну.", 15f, muted)
        )
        gap(body, 18)
        status = text("", 13f, muted)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(status)
        body.addView(action("Открыть окно входа Яндекса", true) {
            startActivityForResult(Intent(this, YandexLoginActivity::class.java), 12)
        })
        gap(body, 8)
        body.addView(action("Браузер · свой OAuth-клиент") { browserLogin() })
        body.addView(
            text(
                "Вход как в YAYMA: защищённая страница Яндекса в отдельном окне. Пароль обрабатывает Яндекс.",
                13f,
                muted,
            )
        )
        val alternative = action("Другой способ: OAuth-токен") {}
        body.addView(alternative)
        val card = panel(body)
        card.visibility = View.GONE
        alternative.setOnClickListener {
            card.visibility = if (card.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        card.addView(text("Вход в аккаунт", 24f, white, true))
        card.addView(text("Личный OAuth-токен", 13f, mint, true))
        val input =
            EditText(this).apply {
                hint = "Вставьте токен"
                setTextColor(white)
                setHintTextColor(muted)
                setSingleLine()
                background = surface(dp(16).toFloat())
                setPadding(dp(14), dp(14), dp(14), dp(14))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
        card.addView(input)
        val show =
            CheckBox(this).apply {
                text = "Показать токен"
                setTextColor(muted)
                setOnCheckedChangeListener { _, checked ->
                    val cursor = input.selectionStart
                    input.transformationMethod =
                        if (checked) null
                        else android.text.method.PasswordTransformationMethod.getInstance()
                    input.setSelection(cursor.coerceAtLeast(0).coerceAtMost(input.length()))
                }
            }
        card.addView(show)
        val tokenNotice =
            text(
                "Токен шифруется на устройстве через Android Keystore. Пароль Яндекса не нужен.",
                13f,
                muted,
            )
        card.addView(tokenNotice)
        lateinit var submit: TextView
        fun connect() {
            if (!submit.isEnabled) return
            val candidate = input.text.toString().trim()
            if (candidate.isEmpty()) {
                status.text = "Сначала вставьте OAuth-токен"
                input.requestFocus()
                return
            }
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            submit.isEnabled = false
            submit.alpha = .55f
            submit.text = "Проверяем аккаунт…"
            token = candidate
            generation++
            request(
                "account",
                finished = {
                    submit.isEnabled = true
                    submit.alpha = 1f
                    submit.text = "Войти"
                },
            ) {
                try {
                    TokenStore(this).save(token)
                    input.text.clear()
                    demo = false
                    shell("Главная")
                } catch (_: Exception) {
                    status.text = "Не удалось защитить токен. Попробуйте ещё раз."
                }
            }
        }
        submit = action("Войти", true) { connect() }
        card.addView(submit)
        input.setOnEditorActionListener { _, id, _ ->
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                connect()
                true
            } else false
        }

        gap(body)
        body.addView(
            action("Попробовать без аккаунта") {
                demo = true
                shell("Главная")
            }
        )
        body.addView(
            text(
                "Неофициальный клиент. Для каталога и прослушивания нужна подписка Яндекс Музыки.",
                12f,
                muted,
            )
        )
    }

    private fun browserLogin() {
        val prefs = getSharedPreferences("oauth", MODE_PRIVATE)
        val client =
            prefs.getString("client", null).orEmpty().ifBlank { BuildConfig.YANDEX_CLIENT_ID }
        if (client.isNotBlank()) {
            launchOAuth(client)
            return
        }
        configureOAuth()
    }

    private fun configureOAuth() {
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
                prefs.edit().putString("client", client).apply()
                launchOAuth(client)
            }
            .show()
    }

    private fun launchOAuth(client: String) {
        val state = java.util.UUID.randomUUID().toString()
        getSharedPreferences("oauth", MODE_PRIVATE)
            .edit()
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
        header.addView(action("Настройки") { settings() })
        root.addView(header)
        status = text(if (demo) "ДЕМОРЕЖИМ · БЕЗ ПОДКЛЮЧЕНИЯ К ЯНДЕКСУ" else "", 11f, mint)
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = false }
        body = column()
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val miniBar = row().apply { background = surface(dp(14).toFloat()); setPadding(dp(12),dp(4),dp(4),dp(4)) }
        val miniLabels = column().apply { isClickable=true; isFocusable=true; setOnClickListener { fullPlayer() } }
        mini = text("Выберите трек",15f,white,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        miniArtist = text("Открыть плеер",12f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        miniLabels.addView(mini);miniLabels.addView(miniArtist)
        miniBar.addView(miniLabels,LinearLayout.LayoutParams(0,-2,1f))
        miniToggle=action("▶") { PlaybackService.command(this,"toggle");refreshPlayer() }
        miniBar.addView(miniToggle,LinearLayout.LayoutParams(dp(52),dp(52)))
        root.addView(miniBar)
        gap(root, 8)
        val nav =
            row().apply {
                background = surface(dp(18).toFloat())
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
        gap(body,16)
        body.addView(text("Главная",32f,white,true))
        body.addView(text(if(demo) "Демо · музыка с устройства" else "Музыка для вас",14f,muted))
        gap(body,16)
        val wave=panel(body)
        wave.addView(text(if(demo) "Попробуйте Luma" else "Моя волна",30f,white,true))
        wave.addView(text(if(demo) "Локальный звук, без аккаунта и сети." else "Нажмите — и слушайте персональный поток.",14f,muted))
        gap(wave,12)
        wave.addView(action(if(demo) "Слушать демо" else "Включить волну",true) {
            if(demo) playDemo() else request("wave") {
                val songs=(it as JSONArray).songs()
                if(songs.isEmpty()) {status.text="Волна пока недоступна";return@request}
                PlaybackService.queue=songs;PlaybackService.command(this,"wave",0);fullPlayer()
            }
        })
        if(PlaybackService.queue.isNotEmpty()) {
            val current=PlaybackService.queue.getOrNull(PlaybackService.index)
            val resume=panel(body)
            resume.addView(text("Продолжить слушать",20f,white,true))
            resume.addView(text(current?.title ?: "Сохранённая очередь",16f,white))
            resume.addView(action("Открыть очередь · ${PlaybackService.queue.size} треков") {fullPlayer()})
        }
        body.addView(text("Ваша музыка",22f,white,true));gap(body,8)
        body.addView(action("Избранное") {if(demo) playDemo() else showTracks("Избранное","likes")})
        gap(body,8)
        body.addView(action("Музыка с устройства") {openFile()})
        gap(body,8)
        body.addView(action("Медиатека и плейлисты") {shell("Медиатека")})
        gap(body,20)
        body.addView(text("Luma Music · made by Romeo13card",12f,muted))
    }

    private fun search() {
        body.addView(text("Найдите своё", 32f, white, true))
        val box =
            EditText(this).apply {
                hint = "Трек или исполнитель"
                setHintTextColor(muted)
                setTextColor(white)
                setSingleLine()
                background = surface()
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
        val host = column()
        target.addView(host)
        fun showPage(start: Int) {
            host.removeAllViews()
            trackMarkers.clear()
            songs.drop(start).take(renderPolicy.pageSize).forEachIndexed { offset, song ->
                val i = start + offset
                val item =
                    row().apply {
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = surface(dp(20).toFloat())
                    }
                val marker=text((i+1).toString(),13f,muted)
                trackMarkers.add(Triple(songs,i,marker))
                item.addView(marker,LinearLayout.LayoutParams(dp(36),-2))
                val labels = column()
                labels.addView(text(song.title, 16f, white, true).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
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
                host.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            val navigation = row()
            if (start > 0)
                navigation.addView(
                    action("‹ Назад") { showPage(maxOf(0, start - renderPolicy.pageSize)) },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            if (start + renderPolicy.pageSize < songs.size)
                navigation.addView(
                    action("Ещё ›") { showPage(start + renderPolicy.pageSize) },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            host.addView(
                text(
                    "${start+1}–${minOf(start+renderPolicy.pageSize,songs.size)} из ${songs.size}",
                    12f,
                    muted,
                )
            )
            host.addView(navigation)
        }
        val first=if(songs === PlaybackService.queue && PlaybackService.index>=0) (PlaybackService.index/renderPolicy.pageSize)*renderPolicy.pageSize else 0
        showPage(first)
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
        body.addView(AlbumArt(this), LinearLayout.LayoutParams(-1, dp(220)))
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
        val jumps=row()
        fun jump(delta:Int) {
            val service=PlaybackService.instance ?: return
            val duration=service.duration().toLong()
            if(duration<=0) {status.text="Перемотка для этого потока пока недоступна";return}
            service.seek((service.position().toLong()+delta).coerceIn(0,duration).toInt())
            refreshPlayer()
        }
        jumps.addView(action("−10 с") {jump(-10000)},LinearLayout.LayoutParams(0,-2,1f))
        jumps.addView(action("+10 с") {jump(10000)},LinearLayout.LayoutParams(0,-2,1f))
        body.addView(jumps)
        gap(body)
        body.addView(
            action("Перемешивание: ${if(PlaybackService.shuffle) "вкл" else "выкл"}") {
                PlaybackService.shuffle = !PlaybackService.shuffle
                preferences.edit().putBoolean("shuffle", PlaybackService.shuffle).apply()
                fullPlayer()
            }
        )
        gap(body, 8)
        body.addView(
            action("Повтор трека: ${if(PlaybackService.repeat) "вкл" else "выкл"}") {
                PlaybackService.repeat = !PlaybackService.repeat
                preferences.edit().putBoolean("repeat", PlaybackService.repeat).apply()
                fullPlayer()
            }
        )
        gap(body, 8)
        body.addView(action("Текст песни · LrcLib") { showLyrics() })
        gap(body)
        body.addView(action("Очередь · к текущему треку") {fullPlayer()})
        body.addView(action("Качество звука") {settings("sound")})
        body.addView(action("Таймер сна") {settings("player")})
        renderTracks(body, PlaybackService.queue)
        gap(body)
        body.addView(
            action("Очистить очередь") {
                stopService(Intent(this, PlaybackService::class.java))
                PlaybackService.forgetQueue(this)
                shell(page)
            }
        )
        refreshPlayer()
    }

    private fun showLyrics() {
        val song = PlaybackService.queue.getOrNull(PlaybackService.index) ?: return
        AlertDialog.Builder(this).setTitle("Текст песни")
            .setMessage("Найти текст в LrcLib? Сервис получит название трека и исполнителя.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Найти") { _, _ ->
                val version = generation
                lyricsDialog?.dismiss()
                val loading = AlertDialog.Builder(this).setTitle("Текст песни").setMessage("Ищем в LrcLib…").setNegativeButton("Отмена", null).show()
                lyricsDialog = loading
                executor.execute {
                    val result = runCatching {
                        NativeCore.call("lyrics", JSONObject().put("title", song.title).put("artist", song.artist).toString()) as JSONObject
                    }
                    runOnUiThread {
                        val dismissed = !loading.isShowing
                        if (!destroyed) loading.dismiss()
                        if (destroyed || dismissed || version != generation) return@runOnUiThread
                        val content = result.getOrNull()?.optString("text")
                            ?: result.exceptionOrNull()?.message ?: "Текст не найден"
                        val scroll = ScrollView(this).apply {
                            addView(text(content, 18f, white))
                            setPadding(dp(16), dp(8), dp(16), dp(8))
                        }
                        AlertDialog.Builder(this).setTitle("${song.title} · LrcLib")
                            .setView(scroll).setPositiveButton("Закрыть", null).show()
                    }
                }
            }.show()
    }

    private fun refreshPlayer() {
        val song = PlaybackService.queue.getOrNull(PlaybackService.index)
        mini?.text = song?.title ?: "Выберите трек"
        miniArtist?.text = song?.artist ?: "Музыка появится здесь"
        miniToggle?.text = if(PlaybackService.playing) "Ⅱ" else "▶"
        miniToggle?.isEnabled = song != null
        miniToggle?.contentDescription = if(PlaybackService.playing) "Приостановить" else "Воспроизвести"
        trackMarkers.forEach { (songs,index,view) ->
            val current=songs === PlaybackService.queue && index==PlaybackService.index
            view.text=if(current) "●" else (index+1).toString()
            view.setTextColor(if(current) mint else muted)
        }
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

    private fun settingRow(parent: LinearLayout, title: String, summary: String, onClick: () -> Unit) {
        val item = row().apply {
            minimumHeight = dp(68)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $summary"
            setOnClickListener { onClick() }
        }
        val labels = column()
        labels.addView(text(title, 17f, white, true))
        labels.addView(text(summary, 13f, muted))
        item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        item.addView(text("›", 24f, muted), LinearLayout.LayoutParams(dp(24), -2))
        parent.addView(item)
    }

    private fun settingSwitch(parent: LinearLayout, title: String, summary: String, key: String, default: Boolean,
                              changed: (Boolean) -> Unit = {}) {
        parent.addView(Switch(this).apply {
            text = title
            textSize = 16f * preferences.getInt("font_size", 100) / 100f
            setTextColor(white)
            minHeight = dp(52)
            isChecked = preferences.getBoolean(key, default)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean(key, enabled).apply()
                changed(enabled)
            }
        })
        parent.addView(text(summary, 13f, muted))
        gap(parent, 8)
    }

    private fun settings(section: String = "") {
        backdrop()
        creditScreen = true
        settingsSection = section
        val titles = mapOf("sound" to "Звук и сеть", "appearance" to "Оформление", "player" to "Воспроизведение",
            "storage" to "Хранилище", "updates" to "Обновления", "account" to "Аккаунт", "about" to "О приложении")
        root.addView(action(if (section.isBlank()) "‹ К музыке" else "‹ Настройки") { navigateBack() })
        root.addView(text(titles[section] ?: "Настройки", 30f, white, true))
        val scroll = ScrollView(this)
        body = column()
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("", 13f, muted)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(status)
        val mode = preferences.getString("theme_mode", "system")
        val modes = arrayOf("system", "dark", "light", "oled")
        val labels = arrayOf("Как в системе", "Тёмная", "Светлая", "OLED · Чёрная")
        val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        fun themed() {
            setTheme(if (palette.light) R.style.Theme_Luma_Light else R.style.Theme_Luma_Dark)
            settings(section)
        }
        fun group(title: String): LinearLayout = panel(body).also { it.addView(text(title, 21f, white, true)) }
        when (section) {
            "sound" -> {
                val network = getSystemService(android.net.ConnectivityManager::class.java)
                val metered = network.isActiveNetworkMetered
                val qualityKey = if (metered) "quality_mobile" else "quality_wifi"
                val selected = preferences.getInt(qualityKey, if (metered) 128 else 320)
                val state = if (network.activeNetwork == null) "Нет подключённой сети" else
                    "${if (metered) "Сеть с лимитом" else "Сеть без лимита"} · до $selected кбит/с"
                body.addView(text(state, 15f, mint, true))
                val sound = group("Качество MP3")
                fun quality(title: String, key: String, default: Int, detail: String) {
                    settingRow(sound, "$title · до ${preferences.getInt(key, default)} кбит/с", detail) {
                        val values = intArrayOf(128,192,320)
                        AlertDialog.Builder(this).setTitle(title)
                            .setSingleChoiceItems(arrayOf("Экономия · 128 кбит/с", "Баланс · 192 кбит/с", "Высокое · 320 кбит/с"),
                                values.indexOf(preferences.getInt(key, default)).coerceAtLeast(0)) { dialog, index ->
                                preferences.edit().putInt(key, values[index]).apply()
                                dialog.dismiss(); settings("sound")
                            }.setNegativeButton("Отмена", null).show()
                    }
                }
                quality("Безлимитная сеть", "quality_wifi", 320, "Обычно Wi-Fi. Максимальное качество в пределах выбранного лимита.")
                quality("Сеть с лимитом", "quality_mobile", 128, "Обычно мобильная связь. Android может считать Wi-Fi тарифицируемым.")
                sound.addView(text("Применяется к следующему запуску трека. Если Яндекс предлагает только более высокий битрейт, Luma сообщит об этом и не превысит лимит автоматически.", 13f, muted))
                val traffic = group("Трафик")
                settingSwitch(traffic, "Только сеть без лимита", "В сети с лимитом сетевое аудио не загружается. Файлы с устройства доступны.", "unmetered_only", false)
                body.addView(action("Повторить текущий трек", true) {
                    if (PlaybackService.index !in PlaybackService.queue.indices) {status.text="Сначала выберите трек";return@action}
                    PlaybackService.command(this, "play", PlaybackService.index)
                    fullPlayer()
                })
            }
            "appearance" -> {
                val appearance = group("Ваш интерфейс")
                settingRow(appearance, "Тема", labels[modes.indexOf(mode).coerceAtLeast(0)]) {
                    AlertDialog.Builder(this).setTitle("Тема оформления")
                        .setSingleChoiceItems(labels, modes.indexOf(mode).coerceAtLeast(0)) { dialog,index ->
                            preferences.edit().putString("theme_mode", modes[index]).apply();dialog.dismiss();themed()
                        }.setNegativeButton("Отмена",null).show()
                }
                settingRow(appearance, "Размер текста Luma", "${preferences.getInt("font_size",100)}% · вместе с системным размером текста") {
                    val sizes=intArrayOf(90,100,115,130)
                    AlertDialog.Builder(this).setTitle("Размер текста")
                        .setSingleChoiceItems(arrayOf("Компактный · 90%","Обычный · 100%","Крупный · 115%","Очень крупный · 130%"),sizes.indexOf(preferences.getInt("font_size",100)).coerceAtLeast(0)) { dialog,index ->
                            preferences.edit().putInt("font_size",sizes[index]).apply();dialog.dismiss();settings("appearance")
                        }.setNegativeButton("Отмена",null).show()
                }
                settingSwitch(appearance,"Вибрация кнопок","Лёгкий тактильный отклик при нажатии.","haptics",true)
                val performance=group("Быстродействие")
                settingSwitch(performance,"Экономичный интерфейс","Меньше строк на странице и реже обновляется плеер. На малом объёме ОЗУ режим включается автоматически.","reduce_effects",false) {settings("appearance")}
                performance.addView(text("Сейчас: ${if(renderPolicy.economy) "экономичный" else "обычный"} профиль · ${renderPolicy.pageSize} треков на странице. Анимации фона и размытие не используются.",13f,muted))
            }
            "player" -> {
                val player=group("Поведение плеера")
                settingSwitch(player,"Перемешивание","Выбор следующего доступного трека в случайном порядке.","shuffle",false) {PlaybackService.shuffle=it}
                settingSwitch(player,"Повтор трека","Повторять текущую композицию после окончания.","repeat",false) {PlaybackService.repeat=it}
                settingSwitch(player,"Пауза без наушников","Останавливать звук при отключении проводных или Bluetooth-наушников.","pause_on_unplug",true)
                val remaining=PlaybackService.instance?.sleepMinutesRemaining() ?: 0
                settingRow(player,"Таймер сна",if(remaining>0) "Осталось около $remaining мин" else "Выключен") {
                    val service=PlaybackService.instance
                    if(service==null) {status.text="Сначала запустите трек";return@settingRow}
                    val minutes=intArrayOf(0,15,30,60,90)
                    AlertDialog.Builder(this).setTitle("Таймер сна")
                        .setItems(arrayOf("Выключить","15 минут","30 минут","60 минут","90 минут")) { _,index ->
                            service.setSleepTimer(minutes[index]);settings("player")
                        }.show()
                }
                settingRow(player,"Уведомления и экран блокировки","Разрешения и категории уведомлений Android") {
                    startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
                }
            }
            "storage" -> {
                val storage=group("Место на устройстве")
                val files=java.io.File(cacheDir,"updates").listFiles()?.filter {it.isFile && it.name in setOf("update.apk","update.part")}.orEmpty()
                val bytes=files.sumOf {it.length()}
                storage.addView(text("Скачанные обновления: ${String.format(java.util.Locale.ROOT,"%.1f",bytes/1048576.0)} МиБ",16f,white))
                storage.addView(text("Свободно: ${String.format(java.util.Locale.ROOT,"%.1f",android.os.StatFs(filesDir.path).availableBytes/1073741824.0)} ГиБ",14f,muted))
                settingRow(storage,"Удалить файлы обновлений","Скачанные APK и незавершённая загрузка. Аккаунт и очередь сохранятся.") {
                    AlertDialog.Builder(this).setTitle("Удалить скачанные обновления?").setNegativeButton("Отмена",null)
                        .setPositiveButton("Удалить") { _,_ ->
                            val version=generation
                            executor.execute {
                                val deleted=java.io.File(cacheDir,"updates").listFiles()?.filter {it.isFile && it.name in setOf("update.apk","update.part")}
                                    ?.map {it.delete()}?.all {it} ?: true
                                runOnUiThread {
                                    if(!destroyed && version==generation) {pendingUpdate=null;settings("storage");status.text=if(deleted) "Файлы удалены" else "Не все файлы удалось удалить"}
                                }
                            }
                        }.show()
                }
                storage.addView(text("Офлайн-загрузка каталога пока не реализована. Luma не удаляет выбранные вами аудиофайлы.",13f,muted))
            }
            "updates" -> {
                val update=group("Luma $current")
                val preview=preferences.getBoolean("test_updates",true)
                settingRow(update,"Канал обновлений",if(preview) "Предварительные · включая RC" else "Только стабильные выпуски") {
                    AlertDialog.Builder(this).setTitle("Канал обновлений")
                        .setSingleChoiceItems(arrayOf("Только стабильные","Предварительные и стабильные"),if(preview) 1 else 0) {dialog,index ->
                            preferences.edit().putBoolean("test_updates",index==1).apply();dialog.dismiss();settings("updates")
                        }.setNegativeButton("Отмена",null).show()
                }
                settingSwitch(update,"Проверять при запуске","Не чаще раза в сутки. Скачивание и установка требуют вашего действия.","auto_update_check",true)
                val last=preferences.getLong("last_update_check",0)
                update.addView(text(if(last<=0) "Проверка ещё не запускалась" else "Последняя попытка: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(last))}",13f,muted))
                update.addView(action("Проверить обновления",true) {checkUpdates()})
                gap(update,8)
                update.addView(action("Установить APK из файла") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").addCategory(Intent.CATEGORY_OPENABLE),11)
                })
                update.addView(text("Перед установкой проверяются версия и подпись. Источник: GitHub Releases Luma-Music.",13f,muted))
            }
            "account" -> {
                val account=group("Яндекс Музыка")
                account.addView(text(if(token.isBlank()) "Вход не выполнен" else "Вход сохранён на устройстве",15f,muted))
                account.addView(action(if(token.isBlank()) "Войти через Яндекс" else "Сменить аккаунт",true) {startActivityForResult(Intent(this,YandexLoginActivity::class.java),12)})
                gap(account,8)
                settingRow(account,"Дополнительный вход","Браузер со своим OAuth-клиентом") {configureOAuth()}
                if(token.isNotBlank()) account.addView(action("Выйти из аккаунта") {logout()})
                account.addView(text("Токен хранится зашифрованным на этом устройстве. Для каталога и прослушивания нужна действующая подписка.",13f,muted))
            }
            "about" -> {
        val about = panel(body)
        about.addView(text(getString(R.string.app_name), 22f, white, true))
        about.addView(
            text(
                "Оформление · ${if(renderPolicy.economy) "экономичный режим" else "обычный режим"}",
                14f,
                muted,
            )
        )
        val memory = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
        val selectedTheme = labels[modes.indexOf(mode).coerceAtLeast(0)]
        val effectiveTheme = if (palette.oled) "OLED" else if (palette.light) "Светлая" else "Тёмная"
        about.addView(text("Версия: $current", 14f, muted))
        about.addView(text("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}", 14f, muted))
        about.addView(text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", 14f, muted))
        about.addView(text("ОЗУ: ${String.format(java.util.Locale.ROOT, "%.1f", memory.totalMem / 1073741824.0)} ГиБ · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}", 14f, muted))
        about.addView(text("Тема: $selectedTheme · сейчас $effectiveTheme", 14f, muted))
        about.addView(text(getString(R.string.author_credit), 18f, mint, true))
        gap(about, 8)
        about.addView(action("Telegram · Romeo13card") { openAuthorLink("https://t.me/romeo_13card") })
        gap(about, 8)
        about.addView(action("Поддержать автора") { openAuthorLink("https://dalink.to/romeo_13card_fan") })
        gap(about, 8)
        about.addView(action("Открытый исходный код и лицензия") {
            val license = assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
            val view = ScrollView(this).apply {
                addView(text("Основа: YAYMA — DarkPlayOff и участники; YaMusic — Vyfor.\nИзменения Luma — Romeo13card. Без гарантий.\n\n$license", 13f, white))
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            AlertDialog.Builder(this).setTitle("GPL-3.0 · YAYMA / Luma")
                .setView(view).setNegativeButton("Закрыть", null)
                .setPositiveButton("Исходники") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Romeo13card-sys/Luma-Music")))
                }.show()
        })
                gap(body,8)
                body.addView(action("Скопировать сведения для поддержки") {
                    val network=getSystemService(android.net.ConnectivityManager::class.java)
                    val report="Luma $current\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\nТема: ${mode ?: "system"}\nЭкономичный профиль: ${renderPolicy.economy}\nКачество безлимитной сети: ${preferences.getInt("quality_wifi",320)}\nКачество сети с лимитом: ${preferences.getInt("quality_mobile",128)}\nТарифицируемая сеть: ${network.isActiveNetworkMetered}"
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Luma · сведения",report))
                    status.text="Сведения скопированы. Токенов и адресов аудио в них нет."
                })
            }
            else -> {
                val categories=listOf(
                    Triple("sound","Звук и сеть","Качество MP3, лимиты трафика, повторный запуск трека"),
                    Triple("appearance","Оформление","Тема, размер текста, вибрация и быстродействие"),
                    Triple("player","Воспроизведение","Повтор, перемешивание, наушники и таймер сна"),
                    Triple("storage","Хранилище","Свободное место и файлы обновлений"),
                    Triple("updates","Обновления","Канал выпусков, проверка и установка APK"),
                    Triple("account","Аккаунт","Вход через Яндекс и выход из аккаунта"),
                    Triple("about","О приложении","Телефон, текущая тема, автор и сведения для поддержки")
                )
                val keywords=mapOf(
                    "sound" to "128 192 320 битрейт wifi wi-fi мобильная mp3 лимит",
                    "appearance" to "oled светлая тёмная черная чёрная шрифт текст масштаб",
                    "player" to "пауза bluetooth наушники сон таймер 15 30 60 90",
                    "storage" to "кэш память apk удалить очистить место",
                    "updates" to "ota github rc релизы версия стабильная",
                    "account" to "авторизация oauth токен логин пароль",
                    "about" to "telegram телеграм донат поддержка телефон android api ram озу"
                )
                val search=EditText(this).apply {
                    hint="Найти настройку";setTextColor(white);setHintTextColor(muted);setSingleLine()
                    background=surface(dp(14).toFloat());setPadding(dp(14),dp(8),dp(14),dp(8))
                }
                body.addView(search)
                val rows=panel(body)
                fun filter(query:String) {
                    rows.removeAllViews()
                    val found=categories.filter {(id,title,summary)->("$title $summary ${keywords[id].orEmpty()}").contains(query.trim(),ignoreCase=true)}
                    found.forEach {(id,title,summary)->settingRow(rows,title,summary) {
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(search.windowToken,0)
                        settings(id)
                    }}
                    if(found.isEmpty()) rows.addView(text("Настройка не найдена",15f,muted))
                }
                search.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {filter(s?.toString().orEmpty())}
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                filter("")
                body.addView(action("Сбросить настройки") {
                    AlertDialog.Builder(this).setTitle("Вернуть настройки по умолчанию?")
                        .setMessage("Тема, качество и поведение плеера будут сброшены. Аккаунт, очередь и ваши файлы сохранятся.")
                        .setNegativeButton("Отмена",null).setPositiveButton("Сбросить") {_,_->
                            preferences.reset {saved->if(!destroyed && creditScreen) status.text=if(saved) "Настройки сброшены" else "Не удалось сохранить сброс. Проверьте свободное место."}
                            PlaybackService.shuffle=false;PlaybackService.repeat=false
                            PlaybackService.instance?.setSleepTimer(0)
                            setTheme(if(palette.light) R.style.Theme_Luma_Light else R.style.Theme_Luma_Dark)
                            settings()
                        }.show()
                })
                body.addView(text("Luma Music · $current",12f,muted))
            }
        }
    }

    private fun openAuthorLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { status.text = "Не найдено приложение для открытия ссылки" }
    }

    private fun checkUpdates(manual: Boolean = true) {
        if (checkingUpdates) return
        val now = System.currentTimeMillis()
        if (
            !manual && !UpdatePolicy.dailyCheckDue(now, preferences.getLong("last_update_check", 0))
        )
            return
        checkingUpdates = true
        preferences.edit().putLong("last_update_check", now).apply()
        val version = generation
        if (manual) status.text = "Проверяем GitHub…"
        executor.execute {
            try {
                val updater = GitHubUpdater(this)
                val release = updater.check(preferences.getBoolean("test_updates", true))
                runOnUiThread {
                    checkingUpdates = false
                    if (destroyed || version != generation) return@runOnUiThread
                    if (release == null) {
                        if (manual) status.text = "Нового выпуска в выбранном канале нет"
                        return@runOnUiThread
                    }
                    status.text = "Найден ${release.tag}"
                    AlertDialog.Builder(this)
                        .setTitle(release.tag)
                        .setMessage(release.notes)
                        .setNegativeButton("Позже", null)
                        .setPositiveButton("Скачать") { _, _ ->
                            executor.execute {
                                try {
                                    val file =
                                        updater.download(release) { percent ->
                                            runOnUiThread {
                                                if (!destroyed && version == generation)
                                                    status.text = "Загрузка: $percent%"
                                            }
                                        }
                                    runOnUiThread {
                                        if (!destroyed && version == generation) installUpdate(file)
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        if (!destroyed && version == generation)
                                            status.text = e.message ?: "Ошибка обновления"
                                    }
                                }
                            }
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    checkingUpdates = false
                    if (manual && !destroyed && version == generation)
                        status.text = e.message ?: "Ошибка проверки"
                }
            }
        }
    }

    private fun installUpdate(file: java.io.File) {
        try {
            GitHubUpdater(this).validate(file)
            if (!packageManager.canRequestPackageInstalls()) {
                pendingUpdate = file
                AlertDialog.Builder(this)
                    .setTitle("Разрешение установки")
                    .setMessage(
                        "Разрешите Luma Music установку обновлений в системных настройках, затем вернитесь в приложение."
                    )
                    .setNegativeButton("Отмена") { _, _ -> pendingUpdate = null }
                    .setPositiveButton("Открыть") { _, _ ->
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"),
                            )
                        )
                    }
                    .show()
                return
            }
            val uri = android.net.Uri.parse("content://$packageName.updates/update.apk")
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("Luma update", uri) }
            startActivity(intent)
        } catch (e: Exception) {
            status.text = e.message ?: "Установка недоступна"
        }
    }

    override fun onResume() {
        super.onResume()
        if (preferences.getBoolean("auto_update_check", true)) checkUpdates(false)
        pendingUpdate?.let { file ->
            if (packageManager.canRequestPackageInstalls()) {
                pendingUpdate = null
                installUpdate(file)
            }
        }
    }

    // Legacy Android 11/12 only; API 33+ uses the native dispatcher registered in onCreate.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Platform back navigation")
    override fun onBackPressed() {
        navigateBack()
    }

    private fun navigateBack() {
        if (creditScreen) {
            if (settingsSection.isNotBlank()) settings() else {
                creditScreen = false
                shell(page)
            }
        } else finish()
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Выйти из аккаунта?")
            .setMessage("Воспроизведение остановится, сохранённая очередь будет очищена.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выйти") { _, _ ->
                stopService(Intent(this, PlaybackService::class.java))
                PlaybackService.forgetQueue(this)
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
        if (requestCode == 12 && resultCode == RESULT_OK) {
            val credential = TokenStore(this).load().orEmpty()
            if (credential.isNotEmpty()) {
                stopService(Intent(this, PlaybackService::class.java))
                PlaybackService.forgetQueue(this)
                token = credential
                demo = false
                shell("Главная")
            }
            return
        }
        if (requestCode == 11 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val version = generation
                status.text = "Проверка APK…"
                executor.execute {
                    try {
                        val folder = java.io.File(cacheDir, "updates").apply { mkdirs() }
                        val file = java.io.File(folder, "update.apk")
                        contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output ->
                                val bytes = ByteArray(32768)
                                var count = 0L
                                while (true) {
                                    val n = input.read(bytes)
                                    if (n < 0) break
                                    count += n
                                    require(count <= UpdatePolicy.MAX_APK) { "APK слишком большой" }
                                    output.write(bytes, 0, n)
                                }
                            }
                        } ?: error("Не удалось открыть файл")
                        GitHubUpdater(this).validate(file)
                        runOnUiThread {
                            if (!destroyed && version == generation) installUpdate(file)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!destroyed && version == generation)
                                status.text = e.message ?: "Ошибка APK"
                        }
                    }
                }
            }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("demo", demo)
        outState.putString("page", page)
        outState.putBoolean("settings", creditScreen)
        outState.putString("settings_section", settingsSection)
        super.onSaveInstanceState(outState)
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
        lyricsDialog?.dismiss()
        lyricsDialog = null
        destroyed = true
        executor.shutdownNow()
        super.onDestroy()
    }
}
