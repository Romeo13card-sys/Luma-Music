use crate::engine::Jobs;
use crate::player::{prepare_audio, Player, Reply};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{fs, io::{Read, Write}, path::PathBuf, sync::mpsc, time::Duration};

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
    pub(crate) update_status: String,
    pub(crate) update_url: Option<String>,
    pub(crate) update_busy: bool,
    pub(crate) download_busy: bool,
    pub(crate) download_status: String,
}

fn compare_release_versions(candidate: &str, installed: &str) -> Option<std::cmp::Ordering> {
    fn parse(raw: &str) -> Option<([u64; 3], bool)> {
        let raw = raw.strip_prefix('v').unwrap_or(raw);
        let stable = !raw.contains('-');
        let core = raw.split(['-', '+']).next()?;
        let mut parts = core.split('.');
        let major = parts.next()?.parse().ok()?;
        let minor = parts.next()?.parse().ok()?;
        let patch = parts.next().map(str::parse).transpose().ok()?.unwrap_or(0);
        if parts.next().is_some() {
            return None;
        }
        Some(([major, minor, patch], stable))
    }
    let (candidate_version, candidate_stable) = parse(candidate)?;
    let (installed_version, installed_stable) = parse(installed)?;
    Some(candidate_version.cmp(&installed_version).then_with(|| candidate_stable.cmp(&installed_stable)))
}

fn next_available_track_id(queue: &[Value], index: usize) -> Option<&str> {
    queue
        .iter()
        .skip(index.saturating_add(1))
        .find(|track| {
            track["available"] == true
                && track["id"]
                    .as_str()
                    .is_some_and(|id| !id.is_empty() && id.bytes().all(|byte| byte.is_ascii_digit()))
        })
        .and_then(|track| track["id"].as_str())
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
            update_status: "Проверка обновлений ещё не выполнялась".into(),
            update_url: None,
            update_busy: false,
            download_busy: false,
            download_status: String::new(),
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

    pub(crate) fn check_updates(&mut self, ctx: eframe::egui::Context) {
        if self.update_busy {
            return;
        }
        self.update_busy = true;
        self.update_status = "Проверяем GitHub Releases…".into();
        self.update_url = None;
        let tx = self.tx.clone();
        let current = crate::app_version().to_owned();
        std::thread::spawn(move || {
            let result = (|| {
                let client = reqwest::blocking::Client::builder()
                    .connect_timeout(Duration::from_secs(8))
                    .timeout(Duration::from_secs(15))
                    .redirect(reqwest::redirect::Policy::none())
                    .user_agent("Luma-Music-Desktop-Updater")
                    .build()
                    .map_err(|_| "Не удалось создать клиент обновлений")?;
                let response = client
                    .get("https://api.github.com/repos/Romeo13card-sys/Luma-Music/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .send()
                    .map_err(|_| "Не удалось подключиться к GitHub")?;
                if !response.status().is_success() {
                    return Err(format!("GitHub вернул HTTP {}", response.status().as_u16()));
                }
                let mut body = String::new();
                response
                    .take(1024 * 1024 + 1)
                    .read_to_string(&mut body)
                    .map_err(|_| "Не удалось прочитать ответ GitHub")?;
                if body.len() > 1024 * 1024 {
                    return Err("Ответ GitHub слишком большой".into());
                }
                let release: Value = serde_json::from_str(&body)
                    .map_err(|_| "GitHub вернул некорректные данные релиза")?;
                let tag = release["tag_name"]
                    .as_str()
                    .filter(|tag| !tag.is_empty())
                    .ok_or("В релизе отсутствует версия")?;
                let url = release["html_url"]
                    .as_str()
                    .filter(|url| url.starts_with("https://github.com/Romeo13card-sys/Luma-Music/releases/tag/"))
                    .ok_or("Некорректная ссылка на релиз")?
                    .to_owned();
                match compare_release_versions(tag, &current) {
                    Some(std::cmp::Ordering::Greater) => Ok((format!("Доступна новая версия {tag}"), url)),
                    Some(_) => Ok((format!("Установлена актуальная версия ({current})"), url)),
                    None => Err("Не удалось сравнить версии релиза".into()),
                }
            })();
            let _ = tx.send(Reply::Update(result));
            ctx.request_repaint();
        });
    }

    pub(crate) fn download_update(&mut self, ctx: eframe::egui::Context) {
        if self.download_busy { return; }
        self.download_busy = true;
        self.download_status = "Загрузка и проверка SHA-256…".into();
        let tx = self.tx.clone();
        std::thread::spawn(move || {
            let result = download_latest_desktop_release();
            let _ = tx.send(Reply::Download(result));
            ctx.request_repaint();
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
                Reply::Update(result) => {
                    self.update_busy = false;
                    match result {
                        Ok((status, url)) => {
                            self.update_status = status;
                            self.update_url = Some(url);
                        }
                        Err(error) => {
                            self.update_status = error;
                            self.update_url = None;
                        }
                    }
                }
                Reply::Download(result) => {
                    self.download_busy = false;
                    self.download_status = match result {
                        Ok(path) => format!("Проверено и сохранено: {path}"),
                        Err(error) => format!("Ошибка загрузки: {error}"),
                    };
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


fn download_latest_desktop_release() -> Result<String, String> {
    let platform = match std::env::consts::OS {
        "windows" => "Windows-10-11",
        "macos" => "macOS",
        "linux" => "Linux",
        _ => return Err("Эта платформа пока не поддерживается".into()),
    };
    let arch = match std::env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "arm64",
        "x86" => "x86",
        _ => return Err("Эта архитектура пока не поддерживается".into()),
    };
    if platform == "Windows-10-11" && arch != "x64" {
        return Err("Для этой архитектуры Windows нет опубликованного архива".into());
    }
    let client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(180))
        .user_agent("Luma-Music-Desktop-Updater")
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let url = attempt.url();
            let host_ok = matches!(url.host_str(), Some("github.com" | "api.github.com" | "release-assets.githubusercontent.com" | "objects.githubusercontent.com" | "github-releases.githubusercontent.com"));
            if url.scheme() == "https" && host_ok && attempt.previous().len() < 5 {
                attempt.follow()
            } else {
                attempt.stop()
            }
        }))
        .build().map_err(|_| "Не удалось создать HTTP-клиент")?;
    let release_response = client.get("https://api.github.com/repos/Romeo13card-sys/Luma-Music/releases/latest")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send().and_then(|r| r.error_for_status()).map_err(|_| "Не удалось получить список файлов релиза")?;
    let mut body = String::new();
    release_response.take(1024 * 1024 + 1).read_to_string(&mut body).map_err(|_| "Не удалось прочитать релиз")?;
    if body.len() > 1024 * 1024 { return Err("Ответ GitHub слишком большой".into()); }
    let release: Value = serde_json::from_str(&body).map_err(|_| "Некорректный ответ GitHub")?;
    let release_tag = release["tag_name"].as_str().ok_or("В релизе отсутствует версия")?;
    let version = release_tag.strip_prefix('v').unwrap_or(release_tag);
    if compare_release_versions(release_tag, crate::app_version()) != Some(std::cmp::Ordering::Greater) {
        return Err("Для этой версии нет более нового стабильного релиза".into());
    }
    let filename = format!("Luma-Music-{version}-{platform}-{arch}{}.zip",
        if platform.starts_with("Windows") { "-Portable" } else { "" });
    let checksum_name = format!("{filename}.sha256");
    let assets = release["assets"].as_array().ok_or("В релизе отсутствуют файлы")?;
    let asset_url = |name: &str| -> Result<String, String> {
        assets.iter().find(|asset| asset["name"].as_str() == Some(name))
            .and_then(|asset| asset["browser_download_url"].as_str())
            .filter(|url| url.starts_with("https://github.com/Romeo13card-sys/Luma-Music/releases/download/"))
            .map(str::to_owned).ok_or_else(|| format!("В релизе нет обязательного файла {name}"))
    };
    let archive_url = asset_url(&filename)?;
    let checksum_url = asset_url(&checksum_name)?;
    let download = |url: &str, limit: u64| -> Result<Vec<u8>, String> {
        let mut response = client.get(url).send().and_then(|r| r.error_for_status()).map_err(|_| "Ошибка скачивания файла")?;
        if response.content_length().is_some_and(|length| length > limit) { return Err("Размер файла превышает лимит".into()); }
        let mut bytes = Vec::new();
        response.take(limit + 1).read_to_end(&mut bytes).map_err(|_| "Не удалось прочитать скачанный файл")?;
        if bytes.len() as u64 > limit { return Err("Файл превышает допустимый размер".into()); }
        Ok(bytes)
    };
    let checksum = download(&checksum_url, 512)?;
    let checksum_text = std::str::from_utf8(&checksum).map_err(|_| "Некорректный SHA-256 файл")?;
    let mut checksum_fields = checksum_text.split_whitespace();
    let expected = checksum_fields.next().filter(|value| value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or("В SHA-256 файле отсутствует контрольная сумма")?.to_ascii_lowercase();
    if checksum_fields.next() != Some(filename.as_str()) {
        return Err("SHA-256 файл относится к другому архиву".into());
    }
    let archive = download(&archive_url, 512 * 1024 * 1024)?;
    let actual = format!("{:x}", Sha256::digest(&archive));
    if actual != expected { return Err("Контрольная сумма SHA-256 не совпала; файл не сохранён".into()); }
    let downloads = std::env::var_os("USERPROFILE").map(|p| PathBuf::from(p).join("Downloads"))
        .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join("Downloads")))
        .ok_or("Не удалось определить каталог загрузок")?;
    fs::create_dir_all(&downloads).map_err(|_| "Не удалось создать каталог загрузок")?;
    let destination = downloads.join(&filename);
    let temporary = downloads.join(format!("{filename}.part"));
    {
        let mut file = fs::File::create(&temporary).map_err(|_| "Не удалось создать временный файл")?;
        file.write_all(&archive).map_err(|_| "Не удалось записать архив")?;
        file.sync_all().map_err(|_| "Не удалось завершить запись архива")?;
    }
    fs::rename(&temporary, &destination).map_err(|_| {
        let _ = fs::remove_file(&temporary);
        "Не удалось переместить проверенный архив в каталог загрузок".to_owned()
    })?;
    Ok(destination.display().to_string())
}

#[cfg(test)]
mod playback_prewarm_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn release_comparison_handles_versions_and_stable_over_preview() {
        assert_eq!(compare_release_versions("v2.1.0", "2.0.9"), Some(std::cmp::Ordering::Greater));
        assert_eq!(compare_release_versions("v2.1.0", "2.1.0-rc.1"), Some(std::cmp::Ordering::Greater));
        assert_eq!(compare_release_versions("v2.0.0", "2.1.0"), Some(std::cmp::Ordering::Less));
        assert_eq!(compare_release_versions("bad", "2.0.0"), None);
        assert_eq!(compare_release_versions("v2.1.0", "2.0"), Some(std::cmp::Ordering::Greater));
        assert_eq!(compare_release_versions("v2.0.0", "2.0"), Some(std::cmp::Ordering::Equal));
    }

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
