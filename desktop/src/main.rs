use eframe::egui;
use serde_json::Value;
use std::{
    io::{BufReader, Read, Write},
    sync::mpsc,
};
enum Reply {
    Data(String, Result<Value, String>),
    Audio(Result<tempfile::NamedTempFile, String>),
}
struct App {
    token: String,
    query: String,
    status: String,
    tracks: Vec<Value>,
    busy: bool,
    connected: bool,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    audio: Option<(rodio::OutputStream, rodio::OutputStreamHandle, rodio::Sink)>,
    file: Option<tempfile::NamedTempFile>,
    title: String,
}
impl App {
    fn new() -> Self {
        let (tx, rx) = mpsc::channel();
        Self {
            token: String::new(),
            query: String::new(),
            status: "Предварительная desktop-версия. Токен хранится только в памяти сеанса.".into(),
            tracks: vec![],
            busy: false,
            connected: false,
            tx,
            rx,
            audio: None,
            file: None,
            title: String::new(),
        }
    }
    fn request(&mut self, op: &str, arg: String) {
        if self.busy {
            return;
        }
        self.busy = true;
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
            let _ = tx.send(Reply::Data(op, result));
        });
    }
    fn play(&mut self, id: String, title: String) {
        if self.busy {
            return;
        }
        self.busy = true;
        self.title = title;
        self.status = "Подготавливаем аудио во временный файл…".into();
        let token = self.token.clone();
        let tx = self.tx.clone();
        std::thread::spawn(move || {
            let result = (|| -> Result<tempfile::NamedTempFile, String> {
                let url = yamusic_core::MusicClient::new(&token)?.stream(&id)?;
                let client = reqwest::blocking::Client::builder()
                    .timeout(std::time::Duration::from_secs(180))
                    .build()
                    .map_err(|_| "Ошибка HTTP")?;
                let mut response = client
                    .get(url)
                    .send()
                    .and_then(|r| r.error_for_status())
                    .map_err(|_| "Не удалось загрузить аудио")?;
                let mut file =
                    tempfile::NamedTempFile::new().map_err(|_| "Недоступен временный каталог")?;
                let mut buffer = [0u8; 32768];
                let mut count = 0usize;
                loop {
                    let n = response
                        .read(&mut buffer)
                        .map_err(|_| "Ошибка аудиопотока")?;
                    if n == 0 {
                        break;
                    }
                    count += n;
                    if count > 100 * 1024 * 1024 {
                        return Err("Аудио больше 100 МиБ".into());
                    }
                    file.write_all(&buffer[..n])
                        .map_err(|_| "Не удалось сохранить аудио")?;
                }
                Ok(file)
            })();
            let _ = tx.send(Reply::Audio(result));
        });
    }
}
impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        while let Ok(reply) = self.rx.try_recv() {
            self.busy = false;
            match reply {
                Reply::Data(op, Ok(data)) => {
                    if op == "account" {
                        self.connected = true;
                        self.status =
                            format!("Аккаунт: {}", data["name"].as_str().unwrap_or("Яндекс"));
                    } else {
                        self.tracks = data.as_array().cloned().unwrap_or_default();
                        self.status = format!("Треков: {}", self.tracks.len());
                    }
                }
                Reply::Data(_, Err(e)) => self.status = e,
                Reply::Audio(result) => {
                    let result = result.and_then(|file| {
                        let source = rodio::Decoder::new(BufReader::new(
                            file.reopen().map_err(|_| "Ошибка файла")?,
                        ))
                        .map_err(|_| "Неподдерживаемое аудио")?;
                        let (stream, handle) = rodio::OutputStream::try_default()
                            .map_err(|_| "Недоступно аудиоустройство")?;
                        let sink = rodio::Sink::try_new(&handle).map_err(|_| "Недоступен плеер")?;
                        if let Some((_, _, old)) = &self.audio {
                            old.stop();
                        }
                        sink.append(source);
                        self.audio = Some((stream, handle, sink));
                        self.file = Some(file);
                        Ok(())
                    });
                    self.status = result
                        .map(|_| "Воспроизведение".into())
                        .unwrap_or_else(|e| e);
                }
            }
        }
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("Luma Music");
            ui.label("Desktop preview · made by Romeo13card");
            ui.horizontal(|ui| {
                ui.label("OAuth-токен");
                ui.add(egui::TextEdit::singleline(&mut self.token).password(true));
                if ui
                    .add_enabled(!self.busy, egui::Button::new("Войти"))
                    .clicked()
                {
                    self.connected = false;
                    self.tracks.clear();
                    if let Some((_, _, sink)) = &self.audio {
                        sink.stop();
                    }
                    self.request("account", String::new());
                }
            });
            ui.add_enabled_ui(self.connected && !self.busy, |ui| {
                ui.horizontal(|ui| {
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
            ui.separator();
            ui.label(&self.title);
            if let Some((_, _, sink)) = &self.audio {
                if ui
                    .button(if sink.is_paused() {
                        "Продолжить"
                    } else {
                        "Пауза"
                    })
                    .clicked()
                {
                    if sink.is_paused() {
                        sink.play()
                    } else {
                        sink.pause()
                    }
                }
            }
            let mut selected = None;
            egui::ScrollArea::vertical().show(ui, |ui| {
                for track in self.tracks.iter().take(200) {
                    let title = track["title"].as_str().unwrap_or("Трек");
                    let artist = track["artists"].as_str().unwrap_or("");
                    if ui
                        .add_enabled(
                            !self.busy && track["available"] == true,
                            egui::Button::new(format!("▶  {title} — {artist}")),
                        )
                        .clicked()
                    {
                        selected = Some((
                            track["id"].as_str().unwrap_or("").to_owned(),
                            title.to_owned(),
                        ));
                    }
                }
            });
            if let Some((id, title)) = selected {
                self.play(id, title);
            }
            ui.separator();
            ui.hyperlink_to(
                "Релизы и исходники",
                "https://github.com/Romeo13card-sys/Luma-Music",
            );
        });
        if self.busy {
            ctx.request_repaint_after(std::time::Duration::from_millis(100));
        }
    }
}
fn main() -> eframe::Result {
    eframe::run_native(
        "Luma Music",
        eframe::NativeOptions::default(),
        Box::new(|_| Ok(Box::new(App::new()))),
    )
}
