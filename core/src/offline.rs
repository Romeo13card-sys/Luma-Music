//! Private per-account offline audio. Network and file IO stay outside the UI thread.
use crate::{json, MusicClient, Result, Value};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Mutex,
    time::Duration,
};

const MAX_TRACK: u64 = 128 * 1024 * 1024;
const MAX_INDEX: u64 = 1024 * 1024;
const MAX_ENTRIES: usize = 500;
static WRITER: Mutex<()> = Mutex::new(());

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Entry {
    id: String,
    title: String,
    artists: String,
    bytes: u64,
    sha256: String,
    quality: u64,
}
fn number(raw: &str) -> Result<&str> {
    if raw.is_empty() || raw.len() > 64 || !raw.bytes().all(|b| b.is_ascii_digit()) {
        return Err("Некорректный идентификатор офлайн-библиотеки".into());
    }
    Ok(raw)
}
fn folder(v: &Value) -> Result<PathBuf> {
    let root = Path::new(v["root"].as_str().ok_or("Нет каталога загрузок")?);
    if !root.is_absolute() {
        return Err("Нужен приватный каталог приложения".into());
    }
    let owner = number(v["owner"].as_str().unwrap_or(""))?;
    let p = root.join(owner);
    if fs::symlink_metadata(&p).is_ok_and(|m| m.file_type().is_symlink()) {
        return Err("Некорректный каталог загрузок".into());
    }
    Ok(p)
}
fn read_index(dir: &Path) -> Result<Vec<Entry>> {
    let path = dir.join("index.json");
    if !path.exists() {
        return Ok(vec![]);
    }
    if fs::metadata(&path)
        .map_err(|_| "Не удалось прочитать библиотеку")?
        .len()
        > MAX_INDEX
    {
        return Err("Библиотека слишком велика".into());
    }
    let entries: Vec<Entry> =
        serde_json::from_slice(&fs::read(path).map_err(|_| "Не удалось прочитать библиотеку")?)
            .map_err(|_| "Повреждён индекс загрузок")?;
    if entries.len() > MAX_ENTRIES
        || entries.iter().any(|e| {
            number(&e.id).is_err()
                || e.title.len() > 1024
                || e.artists.len() > 2048
                || e.bytes == 0
                || e.bytes > MAX_TRACK
                || e.sha256.len() != 64
                || !e.sha256.bytes().all(|b| b.is_ascii_hexdigit())
        })
    {
        return Err("Повреждён индекс загрузок".into());
    }
    Ok(entries)
}
fn write_index(dir: &Path, entries: &[Entry]) -> Result<()> {
    let bytes = serde_json::to_vec(entries).map_err(|_| "Не удалось сохранить библиотеку")?;
    if bytes.len() as u64 > MAX_INDEX {
        return Err("Индекс загрузок слишком велик".into());
    }
    let temp = dir.join("index.part");
    let mut out = fs::File::create(&temp).map_err(|_| "Не удалось сохранить библиотеку")?;
    out.write_all(&bytes)
        .and_then(|_| out.sync_all())
        .map_err(|_| "Не удалось сохранить библиотеку")?;
    fs::rename(temp, dir.join("index.json")).map_err(|_| "Не удалось сохранить библиотеку".into())
}
fn audio_file(dir: &Path, id: &str) -> PathBuf {
    dir.join(format!("{id}.mp3"))
}
fn copy_audio(
    mut input: impl Read,
    out: &mut impl Write,
    expected: Option<u64>,
    budget: u64,
    mut progress: impl FnMut(i32) -> bool,
) -> Result<(u64, String)> {
    let cap = budget.min(MAX_TRACK);
    if expected.is_some_and(|n| n == 0 || n > cap) {
        return Err("Недостаточно места в лимите загрузок".into());
    }
    let mut buffer = [0u8; 32768];
    let mut bytes = 0u64;
    let mut hash = Sha256::new();
    let mut prefix = Vec::with_capacity(3);
    let mut previous = -2;
    loop {
        if !progress(previous.max(-1)) {
            return Err("Загрузка отменена".into());
        }
        let n = input
            .read(&mut buffer)
            .map_err(|_| "Загрузка прервана. Повторите попытку")?;
        if n == 0 {
            break;
        }
        bytes += n as u64;
        if bytes > cap {
            return Err("Превышен лимит места для загрузки".into());
        }
        if prefix.len() < 3 {
            prefix.extend(buffer[..n].iter().take(3 - prefix.len()));
        }
        out.write_all(&buffer[..n])
            .map_err(|_| "Не удалось записать аудио. Проверьте свободное место")?;
        hash.update(&buffer[..n]);
        let percent = expected.map_or(-1, |total| {
            (bytes.saturating_mul(100) / total).min(99) as i32
        });
        if percent != previous && !progress(percent) {
            return Err("Загрузка отменена".into());
        }
        previous = percent;
    }
    if bytes < 3 || expected.is_some_and(|n| n != bytes) {
        return Err("Аудиофайл загружен не полностью".into());
    }
    if !prefix.starts_with(b"ID3") && !(prefix[0] == 0xff && prefix[1] & 0xe0 == 0xe0) {
        return Err("Сервис вернул не MP3-аудио".into());
    }
    Ok((bytes, format!("{:x}", hash.finalize())))
}
fn completed(dir: &Path, e: &Entry) -> bool {
    fs::symlink_metadata(audio_file(dir, &e.id)).is_ok_and(|m| m.is_file() && m.len() == e.bytes)
}
pub fn dispatch(op: &str, v: &Value) -> Result<Value> {
    let dir = folder(v)?;
    match op {
        "offline_list" => {
            let items = read_index(&dir)?
                .into_iter()
                .filter(|e| completed(&dir, e))
                .collect::<Vec<_>>();
            let used: u64 = items.iter().map(|e| e.bytes).sum();
            Ok(json!({"items":items,"bytes":used,"maxEntries":MAX_ENTRIES}))
        }
        "offline_ready" => {
            let id = number(v["id"].as_str().unwrap_or(""))?;
            let entry = read_index(&dir)?.into_iter().find(|e| e.id == id);
            let Some(entry) = entry else {
                return Ok(Value::Null);
            };
            if !completed(&dir, &entry) {
                return Ok(Value::Null);
            }
            let mut file =
                fs::File::open(audio_file(&dir, id)).map_err(|_| "Аудиофайл недоступен")?;
            let mut hasher = Sha256::new();
            let mut buf = [0u8; 32768];
            loop {
                let n = file
                    .read(&mut buf)
                    .map_err(|_| "Не удалось проверить файл")?;
                if n == 0 {
                    break;
                }
                hasher.update(&buf[..n]);
            }
            if format!("{:x}", hasher.finalize()) != entry.sha256 {
                return Err("Скачанный трек повреждён. Удалите его и загрузите заново".into());
            }
            Ok(json!(audio_file(&dir, id).to_string_lossy()))
        }
        "offline_remove" => {
            let _guard = WRITER.lock().map_err(|_| "Библиотека занята")?;
            let id = number(v["id"].as_str().unwrap_or(""))?;
            let mut entries = read_index(&dir)?;
            entries.retain(|e| e.id != id);
            if dir.exists() {
                // Forget metadata first. A failed removal leaves an orphan, never a playable entry.
                write_index(&dir, &entries)?;
                let p = audio_file(&dir, id);
                if p.exists() {
                    fs::remove_file(p).map_err(|_| "Не удалось удалить аудио")?;
                }
            }
            Ok(json!(true))
        }
        "offline_clear" => {
            let _guard = WRITER.lock().map_err(|_| "Библиотека занята")?;
            if dir.exists() {
                fs::remove_dir_all(dir).map_err(|_| "Не удалось очистить загрузки")?;
            }
            Ok(json!(true))
        }
        _ => Err("Неизвестная операция загрузок".into()),
    }
}
pub fn download(token: &str, v: &Value, progress: impl FnMut(i32) -> bool) -> Result<Value> {
    let _guard = WRITER.lock().map_err(|_| "Библиотека занята")?;
    let dir = folder(v)?;
    let id = number(v["id"].as_str().unwrap_or(""))?;
    let title = v["title"].as_str().unwrap_or("Без названия");
    let artists = v["artists"].as_str().unwrap_or("");
    if title.len() > 1024 || artists.len() > 2048 {
        return Err("Слишком длинные сведения о треке".into());
    }
    let quality = v["quality"].as_u64().unwrap_or(128);
    let quota = v["quota"]
        .as_u64()
        .filter(|n| (256 * 1024 * 1024..=16 * 1024 * 1024 * 1024).contains(n))
        .ok_or("Некорректный лимит хранилища")?;
    let client = MusicClient::new(token)?;
    let account = client.account()?;
    let owner = account["uid"]
        .as_str()
        .map(str::to_string)
        .or_else(|| account["uid"].as_u64().map(|n| n.to_string()))
        .unwrap_or_default();
    if owner != v["owner"].as_str().unwrap_or("") {
        return Err("Аккаунт загрузок изменился. Начните загрузку заново".into());
    }
    fs::create_dir_all(&dir).map_err(|_| "Не удалось открыть каталог загрузок")?;
    let mut entries = read_index(&dir)?;
    entries.retain(|e| completed(&dir, e));
    if entries.len() >= MAX_ENTRIES && !entries.iter().any(|e| e.id == id) {
        return Err("Достигнут предел 500 скачанных треков".into());
    }
    let part = dir.join(format!("{id}.part"));
    if part.exists() {
        fs::remove_file(&part).map_err(|_| "Не удалось очистить загрузку")?;
    }
    // Include orphan/partial audio in the disk budget; replacement needs free staging space too.
    let used = fs::read_dir(&dir)
        .map_err(|_| "Не удалось проверить место")?
        .filter_map(|e| e.ok())
        .filter(|e| {
            e.path()
                .extension()
                .is_some_and(|x| x == "mp3" || x == "part")
        })
        .filter_map(|e| e.metadata().ok())
        .filter(|m| m.is_file())
        .map(|m| m.len())
        .sum::<u64>();
    let url = client.stream_at(id, quality)?;
    let http = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(90))
        .redirect(reqwest::redirect::Policy::custom(|a| {
            if a.previous().len() < 4 && crate::validate_download_url(a.url().as_str()).is_ok() {
                a.follow()
            } else {
                a.error("Untrusted audio redirect")
            }
        }))
        .build()
        .map_err(|_| "Не удалось создать загрузчик")?;
    let response = http
        .get(url)
        .send()
        .map_err(|_| "Не удалось начать загрузку")?;
    if !response.status().is_success() {
        return Err(format!(
            "Загрузка аудио: HTTP {}",
            response.status().as_u16()
        ));
    }
    let expected = response.content_length();
    let result = (|| {
        let mut out = fs::File::create(&part).map_err(|_| "Не удалось создать аудиофайл")?;
        let (bytes, sha256) = copy_audio(
            response,
            &mut out,
            expected,
            quota.saturating_sub(used),
            progress,
        )?;
        out.sync_all().map_err(|_| "Не удалось сохранить аудио")?;
        drop(out);
        let entry = Entry {
            id: id.into(),
            title: title.into(),
            artists: artists.into(),
            bytes,
            sha256,
            quality,
        };
        fs::rename(&part, audio_file(&dir, id)).map_err(|_| "Не удалось завершить загрузку")?;
        entries.retain(|e| e.id != id);
        entries.insert(0, entry.clone());
        write_index(&dir, &entries)?;
        Ok(json!(entry))
    })();
    if result.is_err() {
        let _ = fs::remove_file(part);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    fn mp3() -> Vec<u8> {
        let mut v = b"ID3".to_vec();
        v.resize(70000, 42);
        v
    }
    #[test]
    fn streaming_checks_limits_truncation_format_and_cancellation() {
        let b = mp3();
        let mut out = Vec::new();
        assert_eq!(
            copy_audio(&b[..], &mut out, Some(b.len() as u64), MAX_TRACK, |_| true)
                .unwrap()
                .0,
            b.len() as u64
        );
        assert_eq!(out, b);
        assert!(copy_audio(
            &b[..],
            &mut Vec::new(),
            Some(b.len() as u64 + 1),
            MAX_TRACK,
            |_| true
        )
        .is_err());
        assert!(copy_audio(&b[..], &mut Vec::new(), None, 100, |_| true).is_err());
        assert!(copy_audio(&b[..], &mut Vec::new(), None, MAX_TRACK, |_| false).is_err());
        assert!(copy_audio(
            &b"<!DOCTYPE html>"[..],
            &mut Vec::new(),
            None,
            MAX_TRACK,
            |_| true
        )
        .is_err());
    }
    #[test]
    fn account_paths_cannot_escape() {
        for s in ["", "../2", "2/3", "-1", "abc", "42.mp3"] {
            assert!(number(s).is_err());
        }
        assert!(folder(&json!({"root":"relative","owner":"2"})).is_err());
    }
    #[test]
    fn partial_missing_and_corrupt_files_never_become_ready() {
        let root = std::env::temp_dir().join(format!("luma-offline-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let dir = root.join("2");
        fs::create_dir_all(&dir).unwrap();
        let bytes = mp3();
        let e = Entry {
            id: "3".into(),
            title: "T".into(),
            artists: "A".into(),
            bytes: bytes.len() as u64,
            sha256: format!("{:x}", Sha256::digest(&bytes)),
            quality: 128,
        };
        write_index(&dir, &[e]).unwrap();
        let v = json!({"root":root,"owner":"2","id":"3"});
        fs::write(dir.join("3.part"), &bytes).unwrap();
        assert_eq!(dispatch("offline_ready", &v).unwrap(), Value::Null);
        fs::rename(dir.join("3.part"), dir.join("3.mp3")).unwrap();
        let other = json!({"root":root,"owner":"4","id":"3"});
        assert_eq!(dispatch("offline_ready", &other).unwrap(), Value::Null);
        assert!(dispatch("offline_list", &other).unwrap()["items"]
            .as_array()
            .unwrap()
            .is_empty());
        assert!(dispatch("offline_ready", &v).unwrap().is_string());
        let mut broken = bytes;
        broken[5] = 9;
        fs::write(dir.join("3.mp3"), broken).unwrap();
        assert!(dispatch("offline_ready", &v).is_err());
        dispatch("offline_remove", &v).unwrap();
        assert!(dispatch("offline_list", &v).unwrap()["items"]
            .as_array()
            .unwrap()
            .is_empty());
        fs::remove_dir_all(root).unwrap();
    }
}
