//! LRC parsing reused verbatim from YAYMA (GPL-3.0); bounded native adapter by Luma.
use serde::Serialize;
use serde_json::{json, Value};
use std::{
    io::Read,
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex,
    },
    time::Duration,
};
#[rustfmt::skip]
#[path = "../../vendor/yayma/src/rust/src/lyrics/lrc.rs"]
pub mod lrc;
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ParsedLine {
    pub start: f64,
    pub text: String,
    pub words: Vec<ParsedWord>,
}
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ParsedWord {
    pub text: String,
    pub start: f64,
    pub end: f64,
}
const MAX_TEXT: usize = 256 * 1024;
const MAX_LINES: usize = 1000;
static NEXT: AtomicU64 = AtomicU64::new(1);
static CURRENT: Mutex<Option<Document>> = Mutex::new(None);
struct Document {
    session: u64,
    key: String,
    value: Value,
}
fn key(v: &Value) -> Result<&str, String> {
    v["key"]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 8192)
        .ok_or_else(|| "Некорректный трек".into())
}
pub fn cached(v: &Value) -> Result<Value, String> {
    let key = key(v)?;
    let current = CURRENT.lock().map_err(|_| "Тексты временно недоступны")?;
    Ok(current
        .as_ref()
        .filter(|d| d.key == key)
        .map(|d| d.value.clone())
        .unwrap_or(Value::Null))
}
pub fn release(v: &Value) -> Result<Value, String> {
    let mut current = CURRENT.lock().map_err(|_| "Тексты временно недоступны")?;
    if current
        .as_ref()
        .is_some_and(|d| Some(d.session) == v["session"].as_u64())
    {
        *current = None;
    }
    Ok(Value::Null)
}
fn locate(lines: &[Value], position: f64) -> (i64, usize) {
    let index =
        lines.partition_point(|line| line["start"].as_f64().unwrap_or(f64::MAX) <= position);
    if index == 0 {
        return (-1, 0);
    }
    let line = &lines[index - 1];
    let words = line["words"]
        .as_array()
        .map(|words| {
            words.partition_point(|word| word["start"].as_f64().unwrap_or(f64::MAX) <= position)
        })
        .unwrap_or(0);
    ((index - 1) as i64, words)
}
pub fn cursor(v: &Value) -> Result<Value, String> {
    let current = CURRENT.lock().map_err(|_| "Тексты временно недоступны")?;
    let doc = current
        .as_ref()
        .filter(|d| Some(d.session) == v["session"].as_u64())
        .ok_or("Текст уже закрыт")?;
    let position = v["positionMs"].as_u64().ok_or("Некорректная позиция")? as f64 / 1000.0;
    let (line, words) = locate(doc.value["lines"].as_array().unwrap(), position);
    Ok(json!({"line":line,"words":words}))
}
fn remember(v: &Value, mut value: Value) -> Result<Value, String> {
    let session = NEXT.fetch_add(1, Ordering::Relaxed);
    value["session"] = json!(session);
    *CURRENT.lock().map_err(|_| "Тексты временно недоступны")? = Some(Document {
        session,
        key: key(v)?.into(),
        value: value.clone(),
    });
    Ok(value)
}
pub fn fetch(v: &Value) -> Result<Value, String> {
    key(v)?;
    if let Some(value) = cached(v)?.as_object() {
        return Ok(Value::Object(value.clone()));
    }
    let title = v["title"]
        .as_str()
        .filter(|s| !s.trim().is_empty() && s.len() <= 2048)
        .ok_or("Нет названия трека")?;
    let artist = v["artist"]
        .as_str()
        .filter(|s| !s.trim().is_empty() && s.len() <= 2048)
        .ok_or("Нет исполнителя")?;
    // Only track metadata is sent after the user explicitly requests lyrics. Never an OAuth token.
    let client = reqwest::blocking::Client::builder()
        .user_agent("Luma-Music/0.5 (lyrics)")
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none())
        .pool_max_idle_per_host(0)
        .build()
        .map_err(|_| "Ошибка клиента текстов")?;
    let response = client
        .get("https://lrclib.net/api/get")
        .query(&[("track_name", title), ("artist_name", artist)])
        .send()
        .map_err(|_| "Не удалось подключиться к LrcLib")?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Err("Текст в LrcLib не найден".into());
    }
    if !response.status().is_success() {
        return Err("LrcLib временно недоступен".into());
    }
    let mut body = Vec::new();
    response
        .take((MAX_TEXT + 1) as u64)
        .read_to_end(&mut body)
        .map_err(|_| "Ошибка чтения текста")?;
    if body.len() > MAX_TEXT {
        return Err("Текст слишком большой".into());
    }
    let value: Value = serde_json::from_slice(&body).map_err(|_| "Некорректный ответ LrcLib")?;
    remember(v, decode(&value)?)
}
pub fn import(v: &Value) -> Result<Value, String> {
    let raw = v["text"].as_str().ok_or("Текст не найден")?;
    if raw.len() > MAX_TEXT {
        return Err("Текст слишком большой".into());
    }
    let mut value = decode(&json!({"syncedLyrics":raw,"plainLyrics":raw}))?;
    value["source"] = json!("file");
    remember(v, value)
}
fn decode(value: &Value) -> Result<Value, String> {
    let synced = value["syncedLyrics"].as_str().unwrap_or("");
    if synced.len() > MAX_TEXT || value["plainLyrics"].as_str().unwrap_or("").len() > MAX_TEXT {
        return Err("Текст слишком большой".into());
    }
    static INTEGER_TIME: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"([\[<]\d+:\d+)([\]>])").unwrap());
    let normalized = INTEGER_TIME.replace_all(synced, "$1.00$2");
    let mut lines = lrc::parse(&normalized);
    lines.retain(|line| line.start.is_finite() && line.start >= 0.0 && line.start <= 86400.0);
    lines.sort_by(|a, b| a.start.total_cmp(&b.start));
    if lines.len() > MAX_LINES {
        return Err("Текст слишком большой".into());
    }
    // Highlight words by actual start tags, never by the upstream parser's estimated last-word end.
    for i in 0..lines.len() {
        let next_line = lines.get(i + 1).map(|l| l.start);
        let line = &mut lines[i];
        if line
            .words
            .iter()
            .any(|w| !w.start.is_finite() || w.start < line.start || w.start > 86400.0)
            || line.words.windows(2).any(|w| w[0].start > w[1].start)
        {
            line.words.clear(); // Invalid word order falls back to honest line sync.
        }
        for w in 0..line.words.len() {
            line.words[w].end = line
                .words
                .get(w + 1)
                .map(|w| w.start)
                .or(next_line)
                .unwrap_or(line.words[w].start)
                .max(line.words[w].start);
        }
    }
    let text = if lines.is_empty() {
        value["plainLyrics"].as_str().unwrap_or("").to_owned()
    } else {
        lines
            .iter()
            .map(|l| l.text.as_str())
            .collect::<Vec<_>>()
            .join("\n")
    };
    if text.trim().is_empty() {
        return Err(if value["instrumental"].as_bool() == Some(true) {
            "Инструментальная композиция"
        } else {
            "Текст не найден"
        }
        .into());
    }
    let plain: Vec<_> = if lines.is_empty() {
        text.lines().collect()
    } else {
        Vec::new()
    };
    if plain.len() > MAX_LINES
        || text.lines().any(|s| s.len() > 4096)
        || lines.iter().map(|line| line.words.len()).sum::<usize>() > 10000
    {
        return Err("Текст слишком большой".into());
    }
    let word_sync = lines.iter().any(|line| !line.words.is_empty());
    Ok(json!({"source":"LrcLib","text":text,"lines":lines,"plainLines":plain,"wordSync":word_sync}))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sorts_lines_and_preserves_word_sync() {
        let v = decode(
            &json!({"syncedLyrics":"[00:03.00]second\n[00:01.00]<00:01.00>hello <00:02.00>world"}),
        )
        .unwrap();
        assert_eq!(v["text"], "hello world\nsecond");
        assert_eq!(v["wordSync"], true);
    }
    #[test]
    fn plain_and_instrumental_fallback() {
        assert_eq!(
            decode(&json!({"plainLyrics":"Привет"})).unwrap()["text"],
            "Привет"
        );
        assert!(decode(&json!({"instrumental":true})).is_err());
    }
    #[test]
    fn integer_timestamps_and_invalid_word_order() {
        let v = decode(&json!({"syncedLyrics":"[00:01]<00:01>one <00:02>two"})).unwrap();
        assert_eq!(v["wordSync"], true);
        assert_eq!(v["lines"][0]["start"], 1.0);
        let invalid =
            decode(&json!({"syncedLyrics":"[00:01.00]<00:03.00>one <00:02.00>two"})).unwrap();
        assert_eq!(invalid["wordSync"], false);
        assert_eq!(invalid["text"], "one two");
        assert_eq!(
            decode(&json!({"plainLyrics":"one\ntwo"})).unwrap()["plainLines"],
            json!(["one", "two"])
        );
    }
}

#[cfg(test)]
mod timeline_tests {
    use super::*;
    #[test]
    fn seeking_before_first_backwards_and_duplicate_timestamps() {
        let lines = json!([{"start":1.0,"words":[{"start":1.0},{"start":1.5}]},{"start":3.0,"words":[]},{"start":3.0,"words":[]}]);
        let lines = lines.as_array().unwrap();
        assert_eq!(locate(lines, 0.5), (-1, 0));
        assert_eq!(locate(lines, 1.6), (0, 2));
        assert_eq!(locate(lines, 3.0), (2, 0));
        assert_eq!(locate(lines, 1.1), (0, 1));
    }
    #[test]
    fn last_word_has_no_invented_duration_and_documents_are_bounded() {
        let v =
            decode(&json!({"syncedLyrics":"[00:01.00]<00:01.00>hello <00:02.00>world"})).unwrap();
        assert_eq!(v["lines"][0]["words"][1]["end"], 2.0);
        assert!(decode(&json!({"plainLyrics":"x".repeat(MAX_TEXT+1)})).is_err());
        assert!(decode(&json!({"syncedLyrics":"[00:01.00]x\n".repeat(MAX_LINES+1)})).is_err());
    }
    #[test]
    fn stale_release_cannot_clear_new_document() {
        let a = remember(
            &json!({"key":"first"}),
            decode(&json!({"plainLyrics":"first"})).unwrap(),
        )
        .unwrap();
        let b = remember(
            &json!({"key":"second"}),
            decode(&json!({"plainLyrics":"second"})).unwrap(),
        )
        .unwrap();
        release(&json!({"session":a["session"]})).unwrap();
        assert_eq!(
            cached(&json!({"key":"second"})).unwrap()["session"],
            b["session"]
        );
        assert!(cursor(&json!({"session":a["session"],"positionMs":0})).is_err());
        release(&json!({"session":b["session"]})).unwrap();
    }
}
