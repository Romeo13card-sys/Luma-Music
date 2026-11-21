use crate::engine::{self, Ticket};
use eframe::egui;
use rodio::Source;
use serde_json::Value;
use std::{
    fs::File,
    io::BufReader,
    sync::{atomic::Ordering, mpsc},
    time::Duration,
};

pub(crate) type AudioSource = rodio::Decoder<BufReader<File>>;

pub(crate) struct Ready {
    pub(crate) file: tempfile::NamedTempFile,
    pub(crate) source: AudioSource,
    pub(crate) title: String,
    pub(crate) index: usize,
    pub(crate) duration: Option<Duration>,
    pub(crate) queue: Vec<Value>,
}

pub(crate) enum Reply {
    Data(u64, String, Result<Value, String>),
    Audio(u64, Result<Ready, String>),
    Progress(u64, u64),
}

pub(crate) struct Player {
    pub(crate) sink: rodio::Sink,
    _handle: rodio::OutputStreamHandle,
    _stream: rodio::OutputStream,
}

impl Player {
    pub(crate) fn new() -> Result<Self, String> {
        let (stream, handle) = rodio::OutputStream::try_default()
            .map_err(|_| "Аудиоустройство недоступно. Проверьте выход звука и попробуйте снова.")?;
        let sink = rodio::Sink::try_new(&handle).map_err(|_| "Не удалось открыть плеер")?;
        Ok(Self {
            sink,
            _handle: handle,
            _stream: stream,
        })
    }

    pub(crate) fn start(&mut self, source: AudioSource, volume: f32) -> Result<(), String> {
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

pub(crate) fn prepare_audio(
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

pub(crate) fn draw_tracks(
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
