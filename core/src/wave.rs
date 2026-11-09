//! Bounded station presets adapted from YAYMA's mobile wave panel.
use crate::{json, Result, Value};
pub const STATIONS: &[(&str, &str, &str)] = &[
    ("user:onyourwave", "Моя волна", "Для вас"),
    ("activity:wake-up", "Просыпаюсь", "Под занятие"),
    ("activity:road-trip", "В дороге", "Под занятие"),
    ("activity:work-background", "Работаю", "Под занятие"),
    ("activity:workout", "Тренируюсь", "Под занятие"),
    ("activity:fall-asleep", "Засыпаю", "Под занятие"),
    ("mood:energetic", "Бодрое", "Под настроение"),
    ("mood:happy", "Весёлое", "Под настроение"),
    ("mood:calm", "Спокойное", "Под настроение"),
    ("mood:sad", "Грустное", "Под настроение"),
    ("local-language:russian", "Русский", "По языку"),
    ("local-language:english", "Иностранный", "По языку"),
    ("local-language:instrumental", "Без слов", "По языку"),
];
pub fn valid(s: &str) -> bool {
    STATIONS.iter().any(|(id, _, _)| *id == s)
}
pub fn stations() -> Value {
    json!(STATIONS
        .iter()
        .map(|(id, title, group)| json!({"id":id,"title":title,"group":group}))
        .collect::<Vec<_>>())
}
pub fn options(raw: &str) -> Result<(String, String)> {
    let (station, previous) = if raw.starts_with('{') {
        let v: Value = serde_json::from_str(raw).map_err(|_| "Некорректная волна")?;
        (
            v["station"]
                .as_str()
                .unwrap_or("user:onyourwave")
                .to_string(),
            v["previous"].as_str().unwrap_or("").to_string(),
        )
    } else {
        ("user:onyourwave".into(), raw.into())
    };
    if !valid(&station) || previous.len() > 64 || !previous.bytes().all(|b| b.is_ascii_digit()) {
        return Err("Некорректные параметры волны".into());
    }
    Ok((station, previous))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn presets_and_legacy_continuation_are_bounded() {
        assert_eq!(
            options("42").unwrap(),
            ("user:onyourwave".into(), "42".into())
        );
        assert_eq!(
            options(r#"{"station":"mood:calm","previous":"7"}"#)
                .unwrap()
                .0,
            "mood:calm"
        );
        for raw in [
            r#"{"station":"../escape"}"#,
            "../3",
            r#"{"station":"http://example.com"}"#,
        ] {
            assert!(options(raw).is_err());
        }
        assert_eq!(stations().as_array().unwrap().len(), 13);
    }
}

pub fn session_id(raw: &str) -> bool {
    !raw.is_empty()
        && raw.len() <= 128
        && raw
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
}
pub fn session_request(raw: &str) -> Result<(String, Value)> {
    let (station, previous) = options(raw)?;
    let v: Value = serde_json::from_str(raw).unwrap_or(json!({}));
    if let Some(session) = v["session"].as_str() {
        if !session_id(session) {
            return Err("Некорректная сессия волны".into());
        }
        let previous = if let Some(album) = v["album"].as_str() {
            if album.is_empty() || album.len() > 64 || !album.bytes().all(|b| b.is_ascii_digit()) {
                return Err("Некорректный альбом волны".into());
            }
            format!("{previous}:{album}")
        } else {
            previous
        };
        let queue = if previous.is_empty() {
            vec![]
        } else {
            vec![previous]
        };
        Ok((
            format!("/rotor/session/{session}/tracks"),
            json!({"queue":queue,"feedbacks":[]}),
        ))
    } else {
        Ok((
            "/rotor/session/new".into(),
            json!({"seeds":[station],"queue":[],"includeTracksInResponse":true,"includeWaveModel":true,"interactive":true}),
        ))
    }
}
pub fn decorate(value: &Value, station: &str, require_session: bool) -> Result<Value> {
    if value["terminated"].as_bool() == Some(true)
        || value["unknownSession"].as_bool() == Some(true)
    {
        return Err("Сессия волны завершена".into());
    }
    let session = value["radioSessionId"].as_str().filter(|s| session_id(s));
    if require_session && session.is_none() {
        return Err("Сервис не вернул активную сессию волны".into());
    }
    let mut songs = crate::parse_wave(value)?;
    for song in songs.as_array_mut().ok_or("Нет треков волны")? {
        song["waveStation"] = json!(station);
        if let Some(session) = session {
            song["waveSession"] = json!(session);
        }
    }
    Ok(songs)
}
#[cfg(test)]
mod session_tests {
    use super::*;
    #[test]
    fn uses_yayma_session_protocol_and_rejects_dead_sessions() {
        let (path, body) = session_request(r#"{"station":"mood:calm"}"#).unwrap();
        assert_eq!(path, "/rotor/session/new");
        assert_eq!(body["seeds"][0], "mood:calm");
        let (path, body) = session_request(
            r#"{"station":"mood:calm","session":"session-123","previous":"42","album":"7"}"#,
        )
        .unwrap();
        assert_eq!(path, "/rotor/session/session-123/tracks");
        assert_eq!(body["queue"][0], "42:7");
        assert!(session_request(r#"{"session":"../escape"}"#).is_err());
        let value = json!({"batchId":"batch","radioSessionId":"session-123","sequence":[{"track":{"id":"42","available":true}}]});
        assert_eq!(
            decorate(&value, "mood:calm", true).unwrap()[0]["waveSession"],
            "session-123"
        );
        let mut dead = value;
        dead["terminated"] = json!(true);
        assert!(decorate(&dead, "mood:calm", true).is_err());
    }
}
