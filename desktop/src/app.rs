use crate::engine::Jobs;
use crate::player::{prepare_audio, Player, Reply};
use serde_json::Value;
use std::{sync::mpsc, time::Duration};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Screen {
    Home,
    Search,
    Library,
    Wave,
    Queue,
    Settings,
}

pub(crate) struct App {
    pub(crate) screen: Screen,
    pub(crate) token: String,
    pub(crate) query: String,
    pub(crate) status: String,
    pub(crate) tracks: Vec<Value>,
    pub(crate) queue: Vec<Value>,
    pub(crate) connected: bool,
    pub(crate) jobs: Jobs,
    pub(crate) tx: mpsc::SyncSender<Reply>,
    pub(crate) rx: mpsc::Receiver<Reply>,
    pub(crate) player: Option<Player>,
    pub(crate) file: Option<tempfile::NamedTempFile>,
    pub(crate) title: String,
    pub(crate) index: Option<usize>,
    pub(crate) duration: Option<Duration>,
    pub(crate) volume: f32,
    pub(crate) quality: u64,
    pub(crate) autoplay: bool,
    pub(crate) repeat: bool,
    pub(crate) dark: bool,
    pub(crate) waiting_end: bool,
    pub(crate) downloaded: u64,
    pub(crate) smoke_until: Option<std::time::Instant>,
}

fn next_available_track_id(queue: &[Value], index: usize) -> Option<&str> {
    queue
        .iter()
        .skip(index.saturating_add(1))
        .find(|track| track["available"] == true)
        .and_then(|track| track["id"].as_str())
        .filter(|id| !id.is_empty() && id.bytes().all(|byte| byte.is_ascii_digit()))
}

impl App {
    pub(crate) fn new() -> Self {
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

    pub(crate) fn stop(&mut self) {
        if let Some(player) = &self.player {
            player.sink.stop();
        }
        self.waiting_end = false;
        self.file = None;
        self.title.clear();
        self.index = None;
        self.duration = None;
        self.queue.clear();
    }

    pub(crate) fn request(&mut self, operation: &str, argument: String) {
        let Some(ticket) = self.jobs.begin() else {
            return;
        };
        self.status = "Загрузка…".into();
        let token = self.token.clone();
        let tx = self.tx.clone();
        let operation = operation.to_owned();
        std::thread::spawn(move || {
            let result = (|| {
                let reply: Value =
                    serde_json::from_str(&yamusic_core::dispatch(&token, &operation, &argument))
                        .map_err(|_| "Некорректный ответ ядра")?;
                if reply["ok"] != true {
                    return Err(reply["error"].as_str().unwrap_or("Ошибка ядра").to_owned());
                }
                Ok(reply["data"].clone())
            })();
            let _ = tx.send(Reply::Data(ticket.id, operation, result));
        });
    }

    pub(crate) fn play(&mut self, index: usize) {
        self.play_from(self.queue.clone(), index);
    }

    pub(crate) fn play_from(&mut self, queue: Vec<Value>, index: usize) {
        let Some(track) = queue.get(index).filter(|track| track["available"] == true) else {
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

    fn prewarm_next(&self) {
        if !self.autoplay {
            return;
        }
        let Some(index) = self.index else {
            return;
        };
        let Some(id) = next_available_track_id(&self.queue, index) else {
            return;
        };
        let id = id.to_owned();
        let token = self.token.clone();
        let quality = self.quality;
        std::thread::spawn(move || {
            let args = serde_json::json!({"id": id, "quality": quality}).to_string();
            let _ = yamusic_core::dispatch(&token, "stream_quality", &args);
        });
    }

    pub(crate) fn adjacent(&mut self, step: isize) {
        if self.jobs.busy() {
            return;
        }
        let start = self
            .index
            .map(|index| index as isize)
            .unwrap_or(if step > 0 {
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

    pub(crate) fn consume(&mut self) {
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Progress(id, bytes) => {
                    if self.jobs.matches(id) {
                        self.downloaded = bytes;
                    }
                }
                Reply::Data(id, operation, result) => {
                    if !self.jobs.matches(id) {
                        continue;
                    }
                    if !self.jobs.finish(id) {
                        self.status = "Запрос отменён".into();
                        continue;
                    }
                    match result {
                        Ok(data) => {
                            if operation == "account" {
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
                        Err(error) => self.status = error,
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
                    let started = result.is_ok();
                    self.status = result
                        .map(|_| "Воспроизведение".into())
                        .unwrap_or_else(|error: String| error);
                    if started {
                        self.prewarm_next();
                    }
                }
            }
        }
        if self.waiting_end
            && self
                .player
                .as_ref()
                .is_some_and(|player| player.sink.empty())
        {
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

#[cfg(test)]
mod playback_prewarm_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn prewarm_selects_first_available_following_track_only() {
        let queue = vec![
            json!({"id":"1","available":true}),
            json!({"id":"2","available":false}),
            json!({"id":"3","available":true}),
        ];
        assert_eq!(next_available_track_id(&queue, 0), Some("3"));
        assert_eq!(next_available_track_id(&queue, 2), None);
    }

    #[test]
    fn prewarm_rejects_invalid_track_ids() {
        let queue = vec![
            json!({"id":"１２３","available":true}),
            json!({"id":"42","available":true}),
        ];
        assert_eq!(next_available_track_id(&queue, 0), Some("42"));
    }
}
