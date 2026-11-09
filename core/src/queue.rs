//! Bounded queue snapshots. Only metadata and local URIs cross the persistence boundary.
use serde::{Deserialize, Serialize};

pub const MAX_BYTES: usize = 512_000;
#[derive(Deserialize, Serialize)]
struct Song {
    id: String,
    title: String,
    artists: String,
    #[serde(default)]
    available: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    uri: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    cover: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    album: Option<String>,
    #[serde(default, rename = "waveBatch", skip_serializing_if = "Option::is_none")]
    wave_batch: Option<String>,
    #[serde(
        default,
        rename = "waveStation",
        skip_serializing_if = "Option::is_none"
    )]
    wave_station: Option<String>,
    #[serde(
        default,
        rename = "waveSession",
        skip_serializing_if = "Option::is_none"
    )]
    wave_session: Option<String>,
}
#[derive(Deserialize, Serialize)]
struct Snapshot {
    songs: Vec<Song>,
    #[serde(default)]
    index: i64,
    #[serde(default)]
    position: i64,
}
fn limit(value: &mut String, count: usize) {
    if let Some((offset, _)) = value.char_indices().nth(count) {
        value.truncate(offset);
    }
}
pub fn normalize(raw: &str) -> Result<String, String> {
    if raw.len() > MAX_BYTES {
        return Err("Снимок очереди слишком большой".into());
    }
    let mut state: Snapshot =
        serde_json::from_str(raw).map_err(|_| "Повреждённый снимок очереди")?;
    state.songs.truncate(200);
    for song in &mut state.songs {
        limit(&mut song.id, 256);
        limit(&mut song.title, 512);
        limit(&mut song.artists, 512);
        song.album = song
            .album
            .take()
            .filter(|a| !a.is_empty() && a.len() <= 64 && a.bytes().all(|b| b.is_ascii_digit()));
        song.wave_batch = None;
        song.wave_station = None;
        song.wave_session = None;
        song.cover = song
            .cover
            .take()
            .and_then(|raw| crate::artwork::normalize(&raw));
        song.uri = song
            .uri
            .take()
            .filter(|v| v.starts_with("content://") || v.starts_with("file://"));
        if let Some(uri) = song.uri.as_mut() {
            limit(uri, 4096);
        }
    }
    state.index = state
        .index
        .clamp(0, state.songs.len().saturating_sub(1) as i64);
    state.position = state.position.clamp(0, i32::MAX as i64);
    let result = serde_json::to_string(&state).map_err(|_| "Не удалось сохранить очередь")?;
    if result.len() > MAX_BYTES {
        return Err("Снимок очереди слишком большой".into());
    }
    Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Value};
    #[test]
    fn preserves_position_local_uri_and_drops_private_sessions() {
        let raw=json!({"songs":[{"id":"42","title":"Трек","artists":"А","available":true,"uri":"content://docs/42","waveBatch":"expired","token":"secret"}],"index":0,"position":12345}).to_string();
        let normalized = normalize(&raw).unwrap();
        let state: Value = serde_json::from_str(&normalized).unwrap();
        assert_eq!(state["position"], 12345);
        assert_eq!(state["songs"][0]["uri"], "content://docs/42");
        assert!(!normalized.contains("expired"));
        assert!(!normalized.contains("secret"));
    }
    #[test]
    fn bounds_queue_and_strips_network_urls() {
        let songs:Vec<_>=(0..500).map(|i|json!({"id":i.to_string(),"title":"track","artists":"a","uri":"https://example.com/private"})).collect();
        let result =
            normalize(&json!({"songs":songs,"index":499,"position":-1}).to_string()).unwrap();
        let state: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(state["songs"].as_array().unwrap().len(), 200);
        assert_eq!(state["index"], 199);
        assert_eq!(state["position"], 0);
        assert!(!result.contains("private"));
    }
    #[test]
    fn rejects_oversized_and_corrupt_state_and_handles_unicode() {
        assert!(normalize(&"x".repeat(MAX_BYTES + 1)).is_err());
        assert!(normalize("{}").is_err());
        let state: Value = serde_json::from_str(
            &normalize(
                &json!({"songs":[{"id":"1","title":"🎶".repeat(600),"artists":"a"}]}).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(
            state["songs"][0]["title"].as_str().unwrap().chars().count(),
            512
        );
        assert_eq!(
            normalize(&normalize(&state.to_string()).unwrap()).unwrap(),
            normalize(&state.to_string()).unwrap()
        );
    }
}

/// Playback navigation policy. Android supplies availability and entropy; Rust chooses the index.
pub fn next(value: &serde_json::Value) -> Result<serde_json::Value, String> {
    use serde_json::{json, Value};
    let items = value["available"]
        .as_array()
        .filter(|v| v.len() <= 2000)
        .ok_or("Некорректная очередь")?;
    if items.is_empty() {
        return Ok(Value::Null);
    }
    let len = items.len() as i64;
    let index = value["index"].as_i64().unwrap_or(0).clamp(-1, len - 1);
    let step = match value["step"].as_i64() {
        Some(-1) => -1,
        Some(1) => 1,
        _ => return Err("Некорректное направление".into()),
    };
    if value["shuffle"].as_bool() == Some(true) && step > 0 {
        let mut available: Vec<_> = items
            .iter()
            .enumerate()
            .filter(|(_, v)| v.as_bool() == Some(true))
            .map(|(i, _)| i)
            .collect();
        if available.len() > 1 {
            available.retain(|i| *i != index as usize);
        }
        if available.is_empty() {
            return Ok(Value::Null);
        }
        return Ok(json!(
            available[(value["entropy"].as_u64().unwrap_or(0) % available.len() as u64) as usize]
        ));
    }
    Ok((1..=len)
        .map(|n| (index + step * n).rem_euclid(len) as usize)
        .find(|i| items[*i].as_bool() == Some(true))
        .map(|i| json!(i))
        .unwrap_or(Value::Null))
}
#[cfg(test)]
mod navigation_tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn skip_unavailable_wrap_and_shuffle_without_repeating_current() {
        let mut v = json!({"available":[true,false,true],"index":0,"step":1});
        assert_eq!(next(&v).unwrap(), 2);
        v["step"] = json!(-1);
        assert_eq!(next(&v).unwrap(), 2);
        v["shuffle"] = json!(true);
        v["step"] = json!(1);
        for seed in 0..10 {
            v["entropy"] = json!(seed);
            assert_eq!(next(&v).unwrap(), 2);
        }
        v["shuffle"] = json!(false);
        v["index"] = json!(-1);
        assert_eq!(next(&v).unwrap(), 0);
        v["available"] = json!([false, false, false]);
        assert_eq!(next(&v).unwrap(), serde_json::Value::Null);
        v["available"] = json!([]);
        assert_eq!(next(&v).unwrap(), serde_json::Value::Null);
    }
}

#[cfg(test)]
mod artwork_tests {
    use super::*;
    use serde_json::{json, Value};
    #[test]
    fn preserves_public_artwork_but_never_an_arbitrary_url() {
        let cover = "avatars.yandex.net/get-music-content/42/album/%%";
        let raw=json!({"songs":[{"id":"42","title":"A","artists":"B","cover":cover},{"id":"43","title":"C","artists":"D","cover":"https://evil.test/private?token=secret"}]}).to_string();
        let result = normalize(&raw).unwrap();
        let parsed: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(parsed["songs"][0]["cover"], format!("https://{cover}"));
        assert!(parsed["songs"][1]["cover"].is_null());
        assert!(!result.contains("secret"));
        assert_eq!(normalize(&result).unwrap(), result);
    }
}

/// Edits keep the current queue slot, including duplicate ids, stable unless that slot is removed.
pub fn edit(v: &serde_json::Value) -> Result<serde_json::Value, String> {
    use serde_json::{json, Value};
    let original = &v["queue"];
    let input = original["songs"].as_array().ok_or("Некорректная очередь")?;
    if input.len() > 200 {
        return Err("Лимит очереди — 200 треков".into());
    }
    let raw = normalize(&original.to_string())?;
    let mut q: Value = serde_json::from_str(&raw).map_err(|_| "Некорректная очередь")?;
    let songs = q["songs"].as_array_mut().unwrap();
    for (clean, raw) in songs.iter_mut().zip(input) {
        if let Some(batch) = raw["waveBatch"]
            .as_str()
            .filter(|s| !s.is_empty() && s.len() <= 512)
        {
            if let Some(station) = raw["waveStation"]
                .as_str()
                .filter(|s| crate::wave::valid(s))
            {
                clean["waveBatch"] = json!(batch);
                clean["waveStation"] = json!(station);
                if let Some(session) = raw["waveSession"]
                    .as_str()
                    .filter(|s| crate::wave::session_id(s))
                {
                    clean["waveSession"] = json!(session);
                }
            }
        }
    }
    let mut index = if songs.is_empty() {
        -1
    } else {
        original["index"]
            .as_i64()
            .unwrap_or(0)
            .clamp(0, songs.len() as i64 - 1)
    };
    let mut changed = false;
    match v["action"].as_str().unwrap_or("") {
        "next" | "append" => {
            if songs.len() >= 200 {
                return Err("Лимит очереди — 200 треков".into());
            }
            let single = normalize(&json!({"songs":[v["song"]]}).to_string())?;
            let song: Value = serde_json::from_str(&single).map_err(|_| "Некорректный трек")?;
            let at = if v["action"] == "next" {
                (index + 1).max(0) as usize
            } else {
                songs.len()
            };
            songs.insert(at, song["songs"][0].clone());
            if index < 0 {
                index = 0;
                changed = true;
            }
        }
        "remove" => {
            let at = v["at"]
                .as_u64()
                .filter(|n| *n < songs.len() as u64)
                .ok_or("Нет трека в очереди")? as usize;
            songs.remove(at);
            if at as i64 == index {
                changed = true;
                index = (at as i64).min(songs.len() as i64 - 1);
            } else if (at as i64) < index {
                index -= 1;
            }
        }
        "move" => {
            let at = v["at"]
                .as_u64()
                .filter(|n| *n < songs.len() as u64)
                .ok_or("Нет трека в очереди")? as usize;
            let to = v["to"]
                .as_u64()
                .filter(|n| *n < songs.len() as u64)
                .ok_or("Нет места в очереди")? as usize;
            let song = songs.remove(at);
            songs.insert(to, song);
            if index == at as i64 {
                index = to as i64;
            } else {
                if (at as i64) < index {
                    index -= 1;
                }
                if (to as i64) <= index {
                    index += 1;
                }
            }
        }
        _ => return Err("Неизвестное действие очереди".into()),
    }
    q["index"] = json!(index);
    q["position"] = json!(if changed {
        0
    } else {
        original["position"]
            .as_i64()
            .unwrap_or(0)
            .clamp(0, i32::MAX as i64)
    });
    q["currentChanged"] = json!(changed);
    Ok(q)
}
pub fn navigate(v: &serde_json::Value) -> Result<serde_json::Value, String> {
    use serde_json::{json, Value};
    if v["step"].as_i64() == Some(-1) && v["position"].as_u64().unwrap_or(0) >= 5000 {
        let index = v["index"].as_u64().ok_or("Нет текущего трека")?;
        if v["available"]
            .as_array()
            .is_some_and(|a| a.get(index as usize) == Some(&json!(true)))
        {
            return Ok(json!({"index":index,"restart":true}));
        }
    }
    Ok(next(v)?
        .as_u64()
        .map(|i| json!({"index":i,"restart":false}))
        .unwrap_or(Value::Null))
}
#[cfg(test)]
mod edit_tests {
    use super::*;
    use serde_json::{json, Value};
    fn queue() -> Value {
        json!({"songs":[{"id":"1","title":"A","artists":"X","available":true},{"id":"1","title":"duplicate","artists":"X","available":true},{"id":"3","title":"C","artists":"X","available":true}],"index":1,"position":7000})
    }
    #[test]
    fn edits_preserve_the_active_duplicate_slot_and_position() {
        let q = queue();
        let moved = edit(&json!({"queue":q,"action":"move","at":0,"to":2})).unwrap();
        assert_eq!(moved["index"], 0);
        assert_eq!(moved["position"], 7000);
        assert_eq!(moved["songs"][0]["title"], "duplicate");
        let removed = edit(&json!({"queue":q,"action":"remove","at":0})).unwrap();
        assert_eq!(removed["index"], 0);
        assert_eq!(removed["currentChanged"], false);
        let current = edit(&json!({"queue":q,"action":"remove","at":1})).unwrap();
        assert_eq!(current["songs"][1]["id"], "3");
        assert_eq!(current["position"], 0);
        assert_eq!(current["currentChanged"], true);
    }
    #[test]
    fn insert_empty_bounds_and_transient_wave_metadata() {
        let mut q = queue();
        q["songs"][1]["waveBatch"] = json!("batch");
        q["songs"][1]["waveStation"] = json!("mood:calm");
        q["songs"][1]["waveSession"] = json!("session-123");
        let inserted=edit(&json!({"queue":q,"action":"next","song":{"id":"4","title":"D","artists":"Y","available":true}})).unwrap();
        assert_eq!(inserted["songs"][2]["id"], "4");
        assert_eq!(inserted["songs"][1]["waveBatch"], "batch");
        assert_eq!(inserted["songs"][1]["waveSession"], "session-123");
        let saved = normalize(&inserted.to_string()).unwrap();
        assert!(!saved.contains("batch"));
        assert!(!saved.contains("session-123"));
        assert!(edit(&json!({"queue":q,"action":"remove","at":99})).is_err());
        let empty=edit(&json!({"queue":{"songs":[]},"action":"append","song":{"id":"4","title":"D","artists":"Y"}})).unwrap();
        assert_eq!(empty["index"], 0);
        let full = json!({"songs":vec![q["songs"][0].clone();200]});
        assert!(edit(&json!({"queue":full,"action":"append","song":q["songs"][0]})).is_err());
    }
    #[test]
    fn previous_restarts_after_five_seconds_and_manual_next_ignores_repeat() {
        let mut v = json!({"available":[true,true],"index":1,"position":6000,"step":-1});
        assert_eq!(navigate(&v).unwrap()["restart"], true);
        v["position"] = json!(4999);
        assert_eq!(navigate(&v).unwrap()["index"], 0);
        v["step"] = json!(1);
        v["repeat"] = json!(true);
        assert_eq!(navigate(&v).unwrap()["index"], 0);
    }
}
