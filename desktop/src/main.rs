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
struct App {
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
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("Luma Music");
            ui.label(format!("{} · made by Romeo13card", app_version()));
            ui.horizontal(|ui| {
                ui.label("OAuth-токен");
                if ui
                    .add_enabled(
                        !self.jobs.busy(),
                        egui::TextEdit::singleline(&mut self.token).password(true),
                    )
                    .changed()
                {
                    self.connected = false;
                    self.tracks.clear();
                    self.stop();
                }
                if ui
                    .add_enabled(
                        !self.jobs.busy() && !self.token.trim().is_empty(),
                        egui::Button::new("Войти"),
                    )
                    .clicked()
                {
                    self.connected = false;
                    self.tracks.clear();
                    self.stop();
                    self.request("account", String::new());
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
                }
            });
            ui.add_enabled_ui(self.connected && !self.jobs.busy(), |ui| {
                ui.horizontal_wrapped(|ui| {
                    ui.text_edit_singleline(&mut self.query);
                    if ui.button("Поиск").clicked() {
                        self.request("search", self.query.clone());
                    }
                    if ui.button("Избранное").clicked() {
                        self.request("likes", String::new());
                    }
                    if ui.button("Моя волна · партия").clicked() {
                        self.request("wave", String::new());
                    }
                });
            });
            ui.label(&self.status);
            if self.jobs.busy() {
                ui.horizontal(|ui| {
                    ui.spinner();
                    if self.downloaded > 0 {
                        ui.label(format!(
                            "Загружено {:.1} МиБ / не более 100 МиБ",
                            self.downloaded as f64 / 1048576.0
                        ));
                    }
                    if ui.button("Отменить запрос").clicked() {
                        self.jobs.cancel();
                        self.status =
                            "Отменяем запрос… Соединение может завершаться до сетевого тайм-аута."
                                .into();
                    }
                });
            }
            ui.separator();
            ui.label(&self.title);
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(!self.jobs.busy(), egui::Button::new("Предыдущий"))
                    .clicked()
                {
                    self.adjacent(-1);
                }
                if let Some(p) = &self.player {
                    if ui
                        .button(if p.sink.is_paused() {
                            "Продолжить"
                        } else {
                            "Пауза"
                        })
                        .clicked()
                    {
                        if p.sink.is_paused() {
                            p.sink.play()
                        } else {
                            p.sink.pause()
                        }
                    }
                }
                if ui
                    .add_enabled(!self.jobs.busy(), egui::Button::new("Следующий"))
                    .clicked()
                {
                    self.adjacent(1);
                }
                if ui.button("Стоп").clicked() {
                    self.jobs.cancel();
                    self.stop();
                }
                if ui
                    .add_enabled(
                        !self.jobs.busy() && self.player.is_some(),
                        egui::Button::new("Перезапустить аудиовыход"),
                    )
                    .on_hover_text("Повторить текущий трек с начала после смены устройства вывода")
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
            if let Some(p) = &self.player {
                if let Some(duration) = self.duration.filter(|d| !d.is_zero()) {
                    let mut seconds = p.sink.get_pos().as_secs_f64().min(duration.as_secs_f64());
                    if ui
                        .add(
                            egui::Slider::new(&mut seconds, 0.0..=duration.as_secs_f64())
                                .text("сек"),
                        )
                        .changed()
                    {
                        if p.sink.try_seek(Duration::from_secs_f64(seconds)).is_err() {
                            self.status = "Перемотка этого файла недоступна".into();
                        }
                    }
                }
            }
            ui.horizontal_wrapped(|ui| {
                if ui
                    .add(egui::Slider::new(&mut self.volume, 0.0..=1.0).text("Громкость"))
                    .changed()
                {
                    if let Some(p) = &self.player {
                        p.sink.set_volume(self.volume);
                    }
                }
                ui.checkbox(&mut self.autoplay, "Следующий автоматически");
                ui.checkbox(&mut self.repeat, "Повтор");
                egui::ComboBox::from_label("Качество следующего трека")
                    .selected_text(format!("{} кбит/с", self.quality))
                    .show_ui(ui, |ui| {
                        for n in [128, 192, 320] {
                            ui.selectable_value(&mut self.quality, n, n.to_string());
                        }
                    });
                if ui.checkbox(&mut self.dark, "Тёмная тема").changed() {
                    ctx.set_visuals(if self.dark {
                        egui::Visuals::dark()
                    } else {
                        egui::Visuals::light()
                    });
                }
            });
            ui.separator();
            let mut selected = None;
            egui::ScrollArea::vertical().show(ui, |ui| {
                for (index, track) in self.tracks.iter().enumerate() {
                    let label = format!(
                        "{} {} — {}",
                        if self
                            .index
                            .and_then(|i| self.queue.get(i))
                            .is_some_and(|current| current["id"] == track["id"])
                        {
                            "●"
                        } else {
                            "▶"
                        },
                        track["title"].as_str().unwrap_or("Трек"),
                        track["artists"].as_str().unwrap_or("")
                    );
                    if ui
                        .add_enabled(
                            !self.jobs.busy() && track["available"] == true,
                            egui::Button::new(label),
                        )
                        .clicked()
                    {
                        selected = Some(index);
                    }
                }
            });
            if let Some(index) = selected {
                self.play_from(self.tracks.clone(), index);
            }
            ui.separator();
            ui.hyperlink_to(
                "Выпуски, исходники и лицензия GPL-3.0",
                "https://github.com/Romeo13card-sys/Luma-Music/releases",
            );
        });
        if self.jobs.busy() || self.waiting_end {
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
