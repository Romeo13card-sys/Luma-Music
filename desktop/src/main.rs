#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
fn app_version() -> &'static str {
    let v = env!("CARGO_PKG_VERSION");
    if v.contains('-') {
        v
    } else {
        v.strip_suffix(".0").unwrap_or(v)
    }
}
mod engine;
use eframe::egui;
use engine::{Jobs, Ticket};
use rodio::Source;
use serde_json::{json, Value};
use std::{
    fs::File,
    io::BufReader,
    sync::{atomic::Ordering, mpsc},
    time::Duration,
};
type AudioSource = rodio::Decoder<BufReader<File>>;
struct Ready {
    file: tempfile::NamedTempFile,
    source: AudioSource,
    title: String,
    index: usize,
    duration: Option<Duration>,
    queue: Vec<Value>,
}
enum Reply {
    Data(u64, String, Result<Value, String>),
    Audio(u64, Result<Ready, String>),
    Progress(u64, u64),
}
struct Player {
    sink: rodio::Sink,
    _handle: rodio::OutputStreamHandle,
    _stream: rodio::OutputStream,
}
impl Player {
    fn new() -> Result<Self, String> {
        let (stream, handle) = rodio::OutputStream::try_default()
            .map_err(|_| "Аудиоустройство недоступно. Проверьте выход звука и попробуйте снова.")?;
        let sink = rodio::Sink::try_new(&handle).map_err(|_| "Не удалось открыть плеер")?;
        Ok(Self {
            sink,
            _handle: handle,
            _stream: stream,
        })
    }
    fn start(&mut self, source: AudioSource, volume: f32) -> Result<(), String> {
        // A fresh sink avoids append() waiting on a stopped/paused old queue.
        // Reuse the device/stream; only the inexpensive playback queue is replaced.
        let sink =
            rodio::Sink::try_new(&self._handle).map_err(|_| "Не удалось открыть очередь плеера")?;
        sink.set_volume(volume);
        sink.append(source);
        self.sink.stop();
        self.sink = sink;
        Ok(())
    }
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum Screen {
    Home,
    Search,
    Library,
    Wave,
    Queue,
    Settings,
}

struct App {
    screen: Screen,
    token: String,
    query: String,
    status: String,
    tracks: Vec<Value>,
    queue: Vec<Value>,
    connected: bool,
    jobs: Jobs,
    tx: mpsc::SyncSender<Reply>,
    rx: mpsc::Receiver<Reply>,
    player: Option<Player>,
    file: Option<tempfile::NamedTempFile>,
    title: String,
    index: Option<usize>,
    duration: Option<Duration>,
    volume: f32,
    quality: u64,
    autoplay: bool,
    repeat: bool,
    dark: bool,
    waiting_end: bool,
    downloaded: u64,
    smoke_until: Option<std::time::Instant>,
}
impl App {
    fn new() -> Self {
        let (tx, rx) = mpsc::sync_channel(8);
        Self {
            screen: Screen::Home,
            token: String::new(),
            query: String::new(),
            status: "Войдите с OAuth-токеном. Он хранится только до закрытия приложения.".into(),
            tracks: vec![],
            queue: vec![],
            connected: false,
            jobs: Jobs::default(),
            tx,
            rx,
            player: None,
            file: None,
            title: String::new(),
            index: None,
            duration: None,
            volume: 0.8,
            quality: 320,
            autoplay: true,
            repeat: false,
            dark: true,
            waiting_end: false,
            downloaded: 0,
            smoke_until: None,
        }
    }
    fn stop(&mut self) {
        if let Some(p) = &self.player {
            p.sink.stop();
        }
        self.waiting_end = false;
        self.file = None;
        self.title.clear();
        self.index = None;
        self.duration = None;
        self.queue.clear();
    }
    fn request(&mut self, op: &str, arg: String) {
        let Some(ticket) = self.jobs.begin() else {
            return;
        };
        self.status = "Загрузка…".into();
        let token = self.token.clone();
        let tx = self.tx.clone();
        let op = op.to_owned();
        std::thread::spawn(move || {
            let result = (|| {
                let reply: Value = serde_json::from_str(&yamusic_core::dispatch(&token, &op, &arg))
                    .map_err(|_| "Некорректный ответ ядра")?;
                if reply["ok"] != true {
                    return Err(reply["error"].as_str().unwrap_or("Ошибка ядра").to_owned());
                }
                Ok(reply["data"].clone())
            })();
            let _ = tx.send(Reply::Data(ticket.id, op, result));
        });
    }
    fn play(&mut self, index: usize) {
        self.play_from(self.queue.clone(), index);
    }
    fn play_from(&mut self, queue: Vec<Value>, index: usize) {
        let Some(track) = queue.get(index).filter(|t| t["available"] == true) else {
            return;
        };
        let id = track["id"].as_str().unwrap_or("").to_owned();
        let title = track["title"].as_str().unwrap_or("Трек").to_owned();
        let Some(ticket) = self.jobs.begin() else {
            return;
        };
        self.waiting_end = false;
        self.downloaded = 0;
        self.status = format!("Подготовка: {title}");
        let token = self.token.clone();
        let quality = self.quality;
        let tx = self.tx.clone();
        std::thread::spawn(move || {
            let result = prepare_audio(&ticket, &token, &id, quality, title, index, queue, &tx);
            let _ = tx.send(Reply::Audio(ticket.id, result));
        });
    }
    fn adjacent(&mut self, step: isize) {
        if self.jobs.busy() {
            return;
        }
        let start = self.index.map(|i| i as isize).unwrap_or(if step > 0 {
            -1
        } else {
            self.queue.len() as isize
        });
        let mut next = start + step;
        while next >= 0 && (next as usize) < self.queue.len() {
            if self.queue[next as usize]["available"] == true {
                self.play(next as usize);
                return;
            }
            next += step;
        }
    }
    fn consume(&mut self) {
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Progress(id, bytes) => {
                    if self.jobs.matches(id) {
                        self.downloaded = bytes;
                    }
                }
                Reply::Data(id, op, result) => {
                    if !self.jobs.matches(id) {
                        continue;
                    }
                    if !self.jobs.finish(id) {
                        self.status = "Запрос отменён".into();
                        continue;
                    }
                    match result {
                        Ok(data) => {
                            if op == "account" {
                                self.connected = true;
                                self.status = format!(
                                    "Аккаунт: {}",
                                    data["name"].as_str().unwrap_or("Яндекс")
                                );
                            } else {
                                self.tracks = data
                                    .as_array()
                                    .cloned()
                                    .unwrap_or_default()
                                    .into_iter()
                                    .take(200)
                                    .collect();
                                self.status = format!("Треков: {}", self.tracks.len());
                            }
                        }
                        Err(e) => self.status = e,
                    }
                }
                Reply::Audio(id, result) => {
                    if !self.jobs.matches(id) {
                        continue;
                    }
                    if !self.jobs.finish(id) {
                        self.status = "Загрузка отменена".into();
                        continue;
                    }
                    let result = result.and_then(|ready| {
                        if self.player.is_none() {
                            self.player = Some(Player::new()?);
                        }
                        self.player
                            .as_mut()
                            .unwrap()
                            .start(ready.source, self.volume)?;
                        self.file = Some(ready.file);
                        self.title = ready.title;
                        self.queue = ready.queue;
                        self.index = Some(ready.index);
                        self.duration = ready.duration;
                        self.waiting_end = true;
                        Ok(())
                    });
                    self.status = result
                        .map(|_| "Воспроизведение".into())
                        .unwrap_or_else(|e| e);
                }
            }
        }
        if self.waiting_end && self.player.as_ref().is_some_and(|p| p.sink.empty()) {
            self.waiting_end = false;
            self.status = "Трек завершён".into();
            if self.repeat {
                if let Some(index) = self.index {
                    self.play(index);
                }
            } else if self.autoplay {
                self.adjacent(1);
            }
        }
    }
}
fn prepare_audio(
    t: &Ticket,
    token: &str,
    id: &str,
    quality: u64,
    title: String,
    index: usize,
    queue: Vec<Value>,
    tx: &mpsc::SyncSender<Reply>,
) -> Result<Ready, String> {
    let url = yamusic_core::MusicClient::new(token)?.stream_at(id, quality)?;
    if t.cancelled.load(Ordering::Relaxed) {
        return Err("Загрузка отменена".into());
    }
    let mut response = yamusic_core::audio_client()?
        .get(url)
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(|_| "Не удалось получить аудио. Проверьте сеть и повторите попытку.")?;
    let expected = response.content_length();
    let mut file = tempfile::NamedTempFile::new().map_err(|_| "Недоступен временный каталог")?;
    let mut last = 0;
    engine::copy_audio(&mut response, &mut file, expected, &t.cancelled, |bytes| {
        if bytes - last >= 256 * 1024 {
            last = bytes;
            let _ = tx.try_send(Reply::Progress(t.id, bytes));
        }
    })?;
    let source = rodio::Decoder::new(BufReader::new(
        file.reopen().map_err(|_| "Ошибка временного файла")?,
    ))
    .map_err(|_| "Формат аудио не поддерживается или файл повреждён")?;
    let duration = source.total_duration();
    Ok(Ready {
        file,
        source,
        title,
        index,
        duration,
        queue,
    })
}
fn draw_tracks(
    ui: &mut egui::Ui,
    tracks: &[Value],
    current: Option<&str>,
    enabled: bool,
    selected: &mut Option<usize>,
) {
    if tracks.is_empty() {
        ui.add_space(8.0);
        ui.label("Здесь пока пусто. Выберите раздел или выполните поиск.");
        return;
    }
    for (index, track) in tracks.iter().enumerate() {
        let id = track["id"].as_str().unwrap_or("");
        let playing = current.is_some_and(|current| current == id);
        let title = track["title"].as_str().unwrap_or("Трек");
        let artist = track["artists"].as_str().unwrap_or("");
        let state = if playing {
            "СЕЙЧАС"
        } else if track["available"] == true {
            "▶"
        } else {
            "—"
        };
        let label = format!("{state}   {title}\n          {artist}");
        let response = ui.add_enabled(
            enabled && track["available"] == true,
            egui::Button::new(label).min_size(egui::vec2(ui.available_width(), 58.0)),
        );
        if response.clicked() {
            *selected = Some(index);
        }
        ui.add_space(5.0);
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if let Some(deadline) = self.smoke_until {
            if std::time::Instant::now() >= deadline {
                ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                return;
            }
            ctx.request_repaint_after(Duration::from_millis(250));
        }

        self.consume();

        let mut visuals = if self.dark {
            egui::Visuals::dark()
        } else {
            egui::Visuals::light()
        };
        visuals.selection.bg_fill = egui::Color32::from_rgb(255, 216, 77);
        visuals.selection.stroke = egui::Stroke::new(1.0, egui::Color32::from_rgb(255, 216, 77));
        if self.dark {
            visuals.panel_fill = egui::Color32::from_rgb(11, 14, 18);
            visuals.window_fill = egui::Color32::from_rgb(20, 25, 33);
            visuals.extreme_bg_color = egui::Color32::from_rgb(8, 10, 14);
            visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(29, 35, 45);
            visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(42, 51, 64);
            visuals.widgets.active.bg_fill = egui::Color32::from_rgb(255, 216, 77);
        } else {
            visuals.panel_fill = egui::Color32::from_rgb(244, 246, 250);
            visuals.window_fill = egui::Color32::from_rgb(255, 255, 255);
            visuals.extreme_bg_color = egui::Color32::from_rgb(235, 239, 245);
            visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(232, 237, 244);
            visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(220, 227, 237);
            visuals.widgets.active.bg_fill = egui::Color32::from_rgb(255, 216, 77);
        }
        ctx.set_visuals(visuals);
        ctx.style_mut(|style| {
            style.spacing.item_spacing = egui::vec2(10.0, 10.0);
            style.spacing.button_padding = egui::vec2(14.0, 10.0);
            style.visuals.widgets.noninteractive.rounding = egui::Rounding::same(14.0);
            style.visuals.widgets.inactive.rounding = egui::Rounding::same(14.0);
            style.visuals.widgets.hovered.rounding = egui::Rounding::same(14.0);
            style.visuals.widgets.active.rounding = egui::Rounding::same(14.0);
        });

        let mut request: Option<(String, String)> = None;
        let mut selected_track: Option<usize> = None;
        let mut seek_to: Option<f64> = None;
        let mut play_queue = false;

        egui::TopBottomPanel::top("luma_status")
            .exact_height(42.0)
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(
                        egui::RichText::new(if self.connected {
                            "● ПОДКЛЮЧЕНО"
                        } else {
                            "○ НЕ ПОДКЛЮЧЕНО"
                        })
                        .small()
                        .strong()
                        .color(if self.connected {
                            egui::Color32::from_rgb(255, 216, 77)
                        } else {
                            ui.visuals().weak_text_color()
                        }),
                    );
                    ui.separator();
                    ui.label(&self.status);
                    if self.jobs.busy() {
                        ui.spinner();
                        if self.downloaded > 0 {
                            ui.label(format!("{:.1} МиБ", self.downloaded as f64 / 1048576.0));
                        }
                        if ui.small_button("Отменить").clicked() {
                            self.jobs.cancel();
                            self.status = "Отменяем операцию…".into();
                        }
                    }
                });
            });

        egui::SidePanel::left("luma_navigation")
            .resizable(false)
            .default_width(224.0)
            .show(ctx, |ui| {
                ui.add_space(16.0);
                ui.horizontal(|ui| {
                    ui.label(
                        egui::RichText::new("L")
                            .size(30.0)
                            .strong()
                            .color(egui::Color32::from_rgb(255, 216, 77)),
                    );
                    ui.vertical(|ui| {
                        ui.heading("LUMA MUSIC");
                        ui.label(egui::RichText::new("ВАША МУЗЫКА · ВАШ РИТМ").small().weak());
                    });
                });
                ui.add_space(12.0);
                ui.separator();
                ui.add_space(8.0);

                ui.label(egui::RichText::new("МУЗЫКА").small().weak().strong());
                if ui
                    .selectable_label(self.screen == Screen::Home, "⌂   Главная")
                    .clicked()
                {
                    self.screen = Screen::Home;
                }
                if ui
                    .selectable_label(self.screen == Screen::Search, "⌕   Поиск")
                    .clicked()
                {
                    self.screen = Screen::Search;
                }
                if ui
                    .selectable_label(self.screen == Screen::Library, "♡   Коллекция")
                    .clicked()
                {
                    self.screen = Screen::Library;
                    if self.connected && !self.jobs.busy() {
                        self.tracks.clear();
                        request = Some(("likes".into(), String::new()));
                    }
                }
                if ui
                    .selectable_label(self.screen == Screen::Wave, "✦   Моя волна")
                    .clicked()
                {
                    self.screen = Screen::Wave;
                }
                if ui
                    .selectable_label(self.screen == Screen::Queue, "≡   Очередь")
                    .clicked()
                {
                    self.screen = Screen::Queue;
                }

                ui.add_space(14.0);
                ui.separator();
                ui.add_space(10.0);
                ui.label(egui::RichText::new("АККАУНТ").small().weak().strong());
                ui.add(
                    egui::TextEdit::singleline(&mut self.token)
                        .password(true)
                        .hint_text("OAuth-токен Яндекса")
                        .desired_width(f32::INFINITY),
                );
                if ui
                    .add_enabled(
                        !self.jobs.busy() && !self.token.trim().is_empty(),
                        egui::Button::new("Подключить"),
                    )
                    .clicked()
                {
                    self.connected = false;
                    self.tracks.clear();
                    self.stop();
                    request = Some(("account".into(), String::new()));
                }
                if ui
                    .add_enabled(
                        !self.jobs.busy() && self.connected,
                        egui::Button::new("Выйти"),
                    )
                    .clicked()
                {
                    self.connected = false;
                    self.token.clear();
                    self.tracks.clear();
                    self.stop();
                    self.status = "Аккаунт отключён".into();
                }

                ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                    if ui
                        .selectable_label(self.screen == Screen::Settings, "⚙   Настройки")
                        .clicked()
                    {
                        self.screen = Screen::Settings;
                    }
                    ui.label(
                        egui::RichText::new(format!("Luma Music · {}", app_version()))
                            .small()
                            .weak(),
                    );
                });
            });

        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical()
                .auto_shrink([false, false])
                .show(ui, |ui| {
                    ui.add_space(12.0);
                    match self.screen {
                        Screen::Home => {
                            ui.label(
                                egui::RichText::new("ВАША МУЗЫКАЛЬНАЯ СРЕДА")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.add_space(4.0);
                            ui.heading(
                                egui::RichText::new("Что послушаем сегодня?")
                                    .size(30.0)
                                    .strong(),
                            );
                            ui.label(
                                "Подборки, любимые треки и музыка, которую хочется открыть заново.",
                            );
                            ui.add_space(18.0);
                            egui::Frame::group(ui.style())
                                .fill(ui.visuals().window_fill)
                                .stroke(egui::Stroke::new(
                                    1.0,
                                    ui.visuals().widgets.noninteractive.bg_stroke.color,
                                ))
                                .inner_margin(egui::Margin::same(20.0))
                                .show(ui, |ui| {
                                    ui.label(
                                        egui::RichText::new("LUMA · PERSONAL RADIO")
                                            .small()
                                            .strong()
                                            .color(egui::Color32::from_rgb(255, 190, 45)),
                                    );
                                    ui.add_space(4.0);
                                    ui.heading(
                                        egui::RichText::new("Моя волна").size(26.0).strong(),
                                    );
                                    ui.label("Персональная музыкальная подборка из Яндекс Музыки.");
                                    ui.add_space(10.0);
                                    if ui
                                        .add_enabled(
                                            self.connected && !self.jobs.busy(),
                                            egui::Button::new("Слушать волну"),
                                        )
                                        .clicked()
                                    {
                                        request = Some(("wave".into(), String::new()));
                                        self.screen = Screen::Wave;
                                    }
                                });
                            ui.add_space(14.0);
                            ui.heading("Быстрый доступ");
                            ui.horizontal_wrapped(|ui| {
                                if ui.button("♡   Избранное").clicked() {
                                    request = Some(("likes".into(), String::new()));
                                    self.screen = Screen::Library;
                                }
                                if ui.button("⌕   Найти музыку").clicked() {
                                    self.screen = Screen::Search;
                                }
                                if ui.button("≡   Очередь").clicked() {
                                    self.screen = Screen::Queue;
                                }
                            });
                            if !self.tracks.is_empty() {
                                ui.add_space(18.0);
                                ui.heading("Недавно открыто");
                                let current = self
                                    .queue
                                    .get(self.index.unwrap_or(usize::MAX))
                                    .and_then(|t| t["id"].as_str());
                                draw_tracks(
                                    ui,
                                    &self.tracks,
                                    current,
                                    !self.jobs.busy(),
                                    &mut selected_track,
                                );
                            }
                        }
                        Screen::Search => {
                            ui.label(
                                egui::RichText::new("КАТАЛОГ")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Поиск музыки").size(28.0).strong());
                            ui.label("Найдите трек или исполнителя по названию.");
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                let response = ui.add(
                                    egui::TextEdit::singleline(&mut self.query)
                                        .hint_text("Название трека или исполнитель")
                                        .desired_width(ui.available_width() - 110.0),
                                );
                                let enter = response.lost_focus()
                                    && ui.input(|i| i.key_pressed(egui::Key::Enter));
                                if ui
                                    .add_enabled(!self.jobs.busy(), egui::Button::new("Найти"))
                                    .clicked()
                                    || enter
                                {
                                    request = Some(("search".into(), self.query.clone()));
                                }
                            });
                            ui.add_space(12.0);
                            ui.heading("Результаты");
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.tracks,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                        }
                        Screen::Library => {
                            ui.label(
                                egui::RichText::new("ВАША МУЗЫКА")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Коллекция").size(28.0).strong());
                            ui.label("Избранные композиции вашего аккаунта.");
                            ui.add_space(10.0);
                            if ui
                                .add_enabled(
                                    self.connected && !self.jobs.busy(),
                                    egui::Button::new("Обновить избранное"),
                                )
                                .clicked()
                            {
                                request = Some(("likes".into(), String::new()));
                            }
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.tracks,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                        }
                        Screen::Wave => {
                            ui.label(
                                egui::RichText::new("ПЕРСОНАЛЬНАЯ ПОДБОРКА")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Моя волна").size(28.0).strong());
                            ui.label("Получите новую партию рекомендаций.");
                            ui.add_space(10.0);
                            if ui
                                .add_enabled(
                                    self.connected && !self.jobs.busy(),
                                    egui::Button::new("Обновить волну"),
                                )
                                .clicked()
                            {
                                request = Some(("wave".into(), String::new()));
                            }
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.tracks,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                        }
                        Screen::Queue => {
                            ui.label(
                                egui::RichText::new("ВОСПРОИЗВЕДЕНИЕ")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Очередь").size(28.0).strong());
                            ui.label(format!("Композиции в текущей сессии: {}", self.queue.len()));
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.queue,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                            play_queue = true;
                        }
                        Screen::Settings => {
                            ui.label(
                                egui::RichText::new("ПЕРСОНАЛИЗАЦИЯ")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Настройки").size(28.0).strong());
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                ui.label("Качество следующего трека");
                                egui::ComboBox::from_id_source("quality")
                                    .selected_text(format!("{} кбит/с", self.quality))
                                    .show_ui(ui, |ui| {
                                        for quality in [128, 192, 320] {
                                            ui.selectable_value(
                                                &mut self.quality,
                                                quality,
                                                format!("{quality} кбит/с"),
                                            );
                                        }
                                    });
                            });
                            ui.add(
                                egui::Slider::new(&mut self.volume, 0.0..=1.0)
                                    .text("Громкость")
                                    .show_value(true),
                            );
                            if let Some(player) = &self.player {
                                player.sink.set_volume(self.volume);
                            }
                            ui.checkbox(
                                &mut self.autoplay,
                                "Автоматически включать следующий трек",
                            );
                            ui.checkbox(&mut self.repeat, "Повторять текущий трек");
                            if ui.checkbox(&mut self.dark, "Тёмная тема").changed() {
                                self.status = if self.dark {
                                    "Включена тёмная тема"
                                } else {
                                    "Включена светлая тема"
                                }
                                .into();
                            }
                            ui.add_space(12.0);
                            ui.hyperlink_to(
                                "GitHub Releases",
                                "https://github.com/Romeo13card-sys/Luma-Music/releases",
                            );
                            ui.hyperlink_to(
                                "Исходный код и лицензия GPL-3.0",
                                "https://github.com/Romeo13card-sys/Luma-Music",
                            );
                        }
                    }
                });
        });

        egui::TopBottomPanel::bottom("luma_player")
            .resizable(false)
            .show(ctx, |ui| {
                ui.add_space(4.0);
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        ui.label(egui::RichText::new("СЕЙЧАС ИГРАЕТ").small().weak().strong());
                        ui.label(
                            egui::RichText::new(if self.title.is_empty() {
                                "Выберите музыку"
                            } else {
                                &self.title
                            })
                            .strong(),
                        );
                        if let Some(track) = self.queue.get(self.index.unwrap_or(usize::MAX)) {
                            ui.label(
                                egui::RichText::new(track["artists"].as_str().unwrap_or(""))
                                    .small()
                                    .weak(),
                            );
                        }
                    });
                    ui.separator();
                    if ui.button("Предыдущий").clicked() {
                        self.adjacent(-1);
                    }
                    let playing = self
                        .player
                        .as_ref()
                        .is_some_and(|p| !p.sink.is_paused() && !p.sink.empty());
                    if ui
                        .button(if playing {
                            "Пауза"
                        } else {
                            "Продолжить"
                        })
                        .clicked()
                    {
                        if let Some(player) = &self.player {
                            if player.sink.is_paused() {
                                player.sink.play();
                            } else {
                                player.sink.pause();
                            }
                        }
                    }
                    if ui.button("Следующий").clicked() {
                        self.adjacent(1);
                    }
                    if ui.button("Стоп").clicked() {
                        self.jobs.cancel();
                        self.stop();
                    }
                    if ui
                        .add_enabled(
                            !self.jobs.busy() && self.player.is_some(),
                            egui::Button::new("Перезапустить выход"),
                        )
                        .clicked()
                    {
                        self.waiting_end = false;
                        self.player = None;
                        self.file = None;
                        if let Some(index) = self.index {
                            self.play(index);
                        }
                    }
                });
                if let (Some(player), Some(duration)) =
                    (&self.player, self.duration.filter(|d| !d.is_zero()))
                {
                    let mut seconds = player
                        .sink
                        .get_pos()
                        .as_secs_f64()
                        .min(duration.as_secs_f64());
                    if ui
                        .add(
                            egui::Slider::new(&mut seconds, 0.0..=duration.as_secs_f64())
                                .show_value(false)
                                .text(""),
                        )
                        .changed()
                    {
                        seek_to = Some(seconds);
                    }
                }
                ui.add_space(3.0);
            });

        if let Some((operation, argument)) = request {
            self.request(&operation, argument);
        }
        if let Some(index) = selected_track {
            if play_queue {
                self.play(index);
            } else {
                self.play_from(self.tracks.clone(), index);
            }
        }
        if let Some(seconds) = seek_to {
            if let Some(player) = &self.player {
                if player
                    .sink
                    .try_seek(Duration::from_secs_f64(seconds))
                    .is_err()
                {
                    self.status = "Перемотка этого файла недоступна".into();
                }
            }
        }

        if self.jobs.busy()
            || self.waiting_end
            || self
                .player
                .as_ref()
                .is_some_and(|p| !p.sink.is_paused() && !p.sink.empty())
        {
            ctx.request_repaint_after(Duration::from_millis(250));
        }
    }
}

fn main() -> eframe::Result {
    if std::env::args().any(|a| a == "--self-check") {
        println!(
            "{}",
            json!({"version":app_version(),"os":std::env::consts::OS,"arch":std::env::consts::ARCH,"legacy_windows":cfg!(all(windows,target_vendor="win7"))})
        );
        return Ok(());
    }
    let smoke = std::env::args().any(|arg| arg == "--ui-smoke");
    eframe::run_native(
        "Luma Music",
        eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default()
                .with_inner_size([1100.0, 760.0])
                .with_min_inner_size([680.0, 480.0])
                .with_icon(
                    eframe::icon_data::from_png_bytes(include_bytes!(
                        "../../assets/branding/luma-icon.png"
                    ))
                    .expect("Luma icon"),
                ),
            ..Default::default()
        },
        Box::new(move |cc| {
            cc.egui_ctx.set_visuals(egui::Visuals::dark());
            let mut app = App::new();
            if smoke {
                app.smoke_until = Some(std::time::Instant::now() + Duration::from_secs(3));
            }
            Ok(Box::new(app))
        }),
    )
}

#[cfg(test)]
mod state_tests {
    use super::*;
    #[test]
    fn browsing_keeps_playback_queue_and_stale_reply_cannot_unlock_current_job() {
        let mut app = App::new();
        app.queue = vec![json!({"id":"old","available":true})];
        app.index = Some(0);
        app.title = "Old".into();
        let first = app.jobs.begin().unwrap();
        app.tx
            .send(Reply::Data(
                first.id,
                "search".into(),
                Ok(json!([{"id":"new"}])),
            ))
            .unwrap();
        app.consume();
        assert_eq!(app.tracks[0]["id"], "new");
        assert_eq!(app.queue[0]["id"], "old");
        assert_eq!(app.title, "Old");
        let second = app.jobs.begin().unwrap();
        app.tx
            .send(Reply::Data(
                first.id,
                "account".into(),
                Ok(json!({"name":"stale"})),
            ))
            .unwrap();
        app.consume();
        assert!(!app.connected);
        assert!(app.jobs.matches(second.id));
        app.jobs.cancel();
        app.tx
            .send(Reply::Data(
                second.id,
                "account".into(),
                Ok(json!({"name":"cancelled"})),
            ))
            .unwrap();
        app.consume();
        assert!(!app.connected);
    }
}
