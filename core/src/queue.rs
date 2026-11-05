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
