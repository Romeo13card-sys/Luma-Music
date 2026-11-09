//! YAYMA-style likes/dislikes and paged collections, with bounded account-scoped caches.
use crate::{json, track, MusicClient, Result, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex,
    },
    time::{Duration, Instant},
};
const MAX_IDS: usize = 5000;
const TTL: Duration = Duration::from_secs(60);
#[derive(Clone)]
struct Index {
    ids: Vec<String>,
    total: usize,
    at: Instant,
}
#[derive(Default)]
struct Cache {
    scope: [u8; 32],
    epoch: u64,
    uid: Option<String>,
    likes: Option<Index>,
    dislikes: Option<Index>,
    overrides: Vec<(String, String, bool, Instant)>,
}
static EPOCH: AtomicU64 = AtomicU64::new(0);
fn next_epoch() -> u64 {
    EPOCH.fetch_add(1, Ordering::SeqCst).wrapping_add(1)
}
static CACHE: Mutex<Option<Cache>> = Mutex::new(None);
fn cache<T>(token: &str, f: impl FnOnce(&mut Cache) -> T) -> Result<T> {
    let key: [u8; 32] = Sha256::digest(token.as_bytes()).into();
    let mut guard = CACHE.lock().map_err(|_| "Коллекция занята")?;
    let c = guard.get_or_insert_with(Cache::default);
    if c.scope != key {
        let epoch = next_epoch();
        *c = Cache {
            scope: key,
            epoch,
            ..Cache::default()
        };
    }
    Ok(f(c))
}
pub fn clear() {
    if let Ok(mut c) = CACHE.lock() {
        *c = None;
        next_epoch();
    }
}
fn number(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.bytes().all(|b| b.is_ascii_digit())
}
fn numeric_id(v: &Value) -> Option<String> {
    v.as_str()
        .map(str::to_string)
        .or_else(|| v.as_u64().map(|n| n.to_string()))
        .filter(|s| number(s))
}
fn kind(v: &Value) -> Result<&str> {
    match v.as_str() {
        Some("likes") => Ok("likes"),
        Some("dislikes") => Ok("dislikes"),
        _ => Err("Некорректный раздел коллекции".into()),
    }
}
fn uid(client: &MusicClient) -> Result<String> {
    if let Some(uid) = cache(&client.token, |c| c.uid.clone())? {
        return Ok(uid);
    }
    let uid = numeric_id(&client.account()?["uid"]).ok_or("Некорректный аккаунт")?;
    let scope: [u8; 32] = Sha256::digest(client.token.as_bytes()).into();
    if let Ok(mut guard) = CACHE.lock() {
        if let Some(c) = guard.as_mut().filter(|c| c.scope == scope) {
            c.uid = Some(uid.clone());
        }
    }
    Ok(uid)
}
fn index_from(v: &Value) -> Result<Index> {
    let items = v["library"]["tracks"]
        .as_array()
        .ok_or("Сервис не вернул коллекцию")?;
    let ids = items
        .iter()
        .filter_map(|t| numeric_id(&t["id"]))
        .take(MAX_IDS)
        .collect();
    Ok(Index {
        ids,
        total: items.len(),
        at: Instant::now(),
    })
}
fn index(client: &MusicClient, kind: &str, refresh: bool) -> Result<Index> {
    if !refresh {
        if let Some(cached) = cache(&client.token, |c| {
            if kind == "likes" {
                c.likes.clone()
            } else {
                c.dislikes.clone()
            }
        })?
        .filter(|i| i.at.elapsed() < TTL)
        {
            return Ok(cached);
        }
    }
    let epoch = cache(&client.token, |c| c.epoch)?;
    let scope: [u8; 32] = Sha256::digest(client.token.as_bytes()).into();
    let uid = uid(client)?;
    let value = client.get(&format!("/users/{uid}/{kind}/tracks"), &[])?;
    let loaded = index_from(&value)?;
    // A request started before an account change or confirmed edit must not overwrite it.
    if let Ok(mut guard) = CACHE.lock() {
        if let Some(c) = guard
            .as_mut()
            .filter(|c| c.scope == scope && c.epoch == epoch)
        {
            if kind == "likes" {
                c.likes = Some(loaded.clone())
            } else {
                c.dislikes = Some(loaded.clone())
            }
        }
    }
    Ok(loaded)
}
pub fn cached(token: &str, track_id: &str) -> Result<Value> {
    if !number(track_id) {
        return Err("Некорректный трек".into());
    }
    if token.is_empty() {
        return Ok(json!({"liked":null,"disliked":null}));
    }
    cache(token, |c| {
        let status = |kind: &str, index: &Option<Index>| -> Option<bool> {
            if let Some((_, _, set, _)) = c
                .overrides
                .iter()
                .rev()
                .find(|(id, k, _, at)| id == track_id && k == kind && at.elapsed() < TTL)
            {
                return Some(*set);
            }
            let i = index.as_ref().filter(|i| i.at.elapsed() < TTL)?;
            if i.ids.iter().any(|id| id == track_id) {
                Some(true)
            } else if i.total <= i.ids.len() {
                Some(false)
            } else {
                None
            }
        };
        json!({"liked":status("likes",&c.likes),"disliked":status("dislikes",&c.dislikes)})
    })
}
fn page_options(v: &Value) -> Result<(usize, usize)> {
    let offset = match v.get("offset") {
        None => 0,
        Some(n) => n
            .as_u64()
            .filter(|n| *n <= MAX_IDS as u64)
            .ok_or("Некорректная страница коллекции")?,
    } as usize;
    let limit = match v.get("limit") {
        None => 40,
        Some(n) => n
            .as_u64()
            .filter(|n| (1..=100).contains(n))
            .ok_or("Некорректный размер страницы")?,
    } as usize;
    Ok((offset, limit))
}
pub fn page(client: &MusicClient, v: &Value) -> Result<Value> {
    let kind = kind(&v["kind"])?;
    let (offset, limit) = page_options(v)?;
    let index = index(client, kind, v["refresh"].as_bool() == Some(true))?;
    let selected: Vec<_> = index.ids.iter().skip(offset).take(limit).cloned().collect();
    let mut songs = Vec::new();
    if !selected.is_empty() {
        let ids = selected.join(",");
        let result = client.get("/tracks", &[("track-ids", &ids)])?;
        let items = result.as_array().ok_or("Нет сведений о треках")?;
        let mut metadata: HashMap<_, _> = items
            .iter()
            .take(200)
            .filter_map(track)
            .filter(|t| selected.contains(&t.id))
            .map(|t| (t.id.clone(), t))
            .collect();
        for id in &selected {
            if let Some(song) = metadata.remove(id) {
                songs.push(song);
            }
        }
    }
    Ok(
        json!({"tracks":songs,"offset":offset,"nextOffset":offset+selected.len(),"hasMore":offset+selected.len()<index.ids.len(),"total":index.total,"accessible":index.ids.len(),"limit":MAX_IDS}),
    )
}
pub fn plan(v: &Value) -> Result<Value> {
    let kind = kind(&v["kind"])?;
    let id = numeric_id(&v["id"]).ok_or("Некорректный трек")?;
    let set = v["set"].as_bool().ok_or("Нужно явное действие")?;
    let album = if v["album"].is_null() {
        None
    } else {
        Some(id_or_album(&v["album"])?)
    };
    let entity = album.map(|a| format!("{id}:{a}")).unwrap_or(id);
    Ok(
        json!({"kind":kind,"set":set,"entity":entity,"suffix":format!("{kind}/tracks/{}",if set{"add-multiple"}else{"remove"})}),
    )
}
fn id_or_album(v: &Value) -> Result<String> {
    numeric_id(v).ok_or_else(|| "Некорректный альбом".into())
}
pub fn confirmed(v: &Value) -> Result<u64> {
    v["revision"]
        .as_u64()
        .or_else(|| v["revision"].as_str()?.parse().ok())
        .ok_or_else(|| "Сервис не подтвердил изменение коллекции".into())
}
pub fn react(client: &MusicClient, v: &Value) -> Result<Value> {
    let mut args = v.clone();
    // Older queue snapshots had no album id. Resolve it before constructing id:album.
    plan(&args)?;
    if args["album"].is_null() {
        if let Ok(meta) = client.track_info(args["id"].as_str().unwrap_or("")) {
            if !meta["album"].is_null() {
                args["album"] = meta["album"].clone();
            }
        }
    }
    let plan = plan(&args)?;
    let uid = uid(client)?;
    let reply = client.post(
        &format!("/users/{uid}/{}", plan["suffix"].as_str().unwrap()),
        &[("track-ids", plan["entity"].as_str().unwrap())],
    )?;
    let revision = confirmed(&reply)?;
    let track_id = numeric_id(&v["id"]).ok_or("Некорректный трек")?;
    let kind = plan["kind"].as_str().unwrap();
    let set = plan["set"].as_bool().unwrap();
    let scope: [u8; 32] = Sha256::digest(client.token.as_bytes()).into();
    let mut guard = CACHE.lock().map_err(|_| "Коллекция занята")?;
    if let Some(c) = guard.as_mut().filter(|c| c.scope == scope) {
        c.epoch = next_epoch();
        c.overrides
            .retain(|(id, k, _, _)| id != &track_id || k != kind);
        c.overrides
            .push((track_id.clone(), kind.into(), set, Instant::now()));
        if c.overrides.len() > 256 {
            c.overrides.remove(0);
        }
        let target = if kind == "likes" {
            &mut c.likes
        } else {
            &mut c.dislikes
        };
        if let Some(index) = target {
            let existed = index.ids.iter().any(|id| id == &track_id);
            index.ids.retain(|id| id != &track_id);
            if set {
                index.ids.insert(0, track_id.clone());
                if !existed {
                    index.total += 1;
                }
            } else if existed {
                index.total = index.total.saturating_sub(1);
            }
            index.ids.truncate(MAX_IDS);
        }
    }
    drop(guard);
    let mut state = json!({"liked":null,"disliked":null});
    state[if kind == "likes" { "liked" } else { "disliked" }] = json!(set);
    state["revision"] = json!(revision);
    Ok(state)
}
pub fn link(v: &Value) -> Result<Value> {
    let id = numeric_id(&v["id"]).ok_or("Для локального файла нет ссылки Яндекса")?;
    let path = if let Some(album) = numeric_id(&v["album"]) {
        format!("/album/{album}/track/{id}")
    } else {
        format!("/track/{id}")
    };
    Ok(json!(format!("https://music.yandex.ru{path}")))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mutation_requires_valid_entity_explicit_state_and_server_revision() {
        let p = plan(&json!({"kind":"likes","set":true,"id":"42","album":"9"})).unwrap();
        assert_eq!(p["entity"], "42:9");
        assert_eq!(p["suffix"], "likes/tracks/add-multiple");
        assert_eq!(
            plan(&json!({"kind":"dislikes","set":false,"id":"42"})).unwrap()["suffix"],
            "dislikes/tracks/remove"
        );
        for bad in [
            json!({"kind":"unknown","id":"42","set":true}),
            json!({"kind":"likes","id":"../3","set":true}),
            json!({"kind":"likes","id":"42"}),
            json!({"kind":"likes","id":"42","set":true,"album":"bad"}),
        ] {
            assert!(plan(&bad).is_err());
        }
        assert_eq!(confirmed(&json!({"revision":"12"})).unwrap(), 12);
        assert!(confirmed(&json!({"ok":true})).is_err());
        assert_eq!(
            link(&json!({"id":"42","album":"9"})).unwrap(),
            "https://music.yandex.ru/album/9/track/42"
        );
        assert!(link(&json!({"id":"local"})).is_err());
    }
    #[test]
    fn collection_caps_and_preserves_server_order() {
        let index = index_from(
            &json!({"library":{"tracks":(0..6000).map(|id|json!({"id":id})).collect::<Vec<_>>()}}),
        )
        .unwrap();
        assert_eq!(index.ids.len(), MAX_IDS);
        assert_eq!(index.total, 6000);
        assert_eq!(index.ids[1], "1");
        assert!(index_from(&json!({"library":{}})).is_err());
    }
    #[test]
    fn cached_reactions_do_not_cross_accounts() {
        clear();
        cache("account-a", |c| {
            c.overrides
                .push(("42".into(), "likes".into(), true, Instant::now()))
        })
        .unwrap();
        assert_eq!(cached("account-a", "42").unwrap()["liked"], true);
        assert!(cached("account-b", "42").unwrap()["liked"].is_null());
        clear();
        assert!(cached("account-a", "42").unwrap()["liked"].is_null());
        let old = cache("account-a", |c| c.epoch).unwrap();
        clear();
        let new = cache("account-a", |c| c.epoch).unwrap();
        assert_ne!(
            old, new,
            "A late response must not match a new cache generation"
        );
        assert!(page_options(&json!({"offset":5001})).is_err());
        assert!(page_options(&json!({"offset":-1})).is_err());
        assert!(page_options(&json!({"limit":0})).is_err());
    }
}
