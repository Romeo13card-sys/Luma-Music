//! LRC parsing reused verbatim from YAYMA (GPL-3.0); bounded native adapter by Luma.
use serde::Serialize;
use serde_json::{json, Value};
use std::{io::Read, time::Duration};
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
pub fn fetch(v: &Value) -> Result<Value, String> {
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
    decode(&value)
}
fn decode(value: &Value) -> Result<Value, String> {
    let synced = value["syncedLyrics"].as_str().unwrap_or("");
    let mut lines = lrc::parse(synced);
    lines.sort_by(|a, b| a.start.total_cmp(&b.start));
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
    Ok(
        json!({"source":"LrcLib","text":text,"lines":lines,"wordSync":lrc::has_inline_words(synced)}),
    )
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
}
