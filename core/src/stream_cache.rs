//! Four short-lived signed URLs in RAM. No secrets/URLs are persisted or shared across accounts.
use crate::{validate_download_url, Result};
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
    sync::Mutex,
    time::{Duration, Instant},
};
const TTL: Duration = Duration::from_secs(45);
#[derive(Default)]
struct Cache {
    scope: [u8; 32],
    epoch: u64,
    items: VecDeque<(String, u64, String, Instant)>,
}
impl Cache {
    fn select(&mut self, token: &str) {
        let scope: [u8; 32] = Sha256::digest(token.as_bytes()).into();
        if self.scope != scope {
            self.scope = scope;
            self.epoch = self.epoch.wrapping_add(1);
            self.items.clear()
        }
    }
    fn get(&mut self, id: &str, quality: u64) -> Option<String> {
        self.items.retain(|(_, _, _, time)| time.elapsed() < TTL);
        self.items
            .iter()
            .find(|(i, q, _, _)| i == id && *q == quality)
            .map(|(_, _, url, _)| url.clone())
    }
    fn put(&mut self, id: &str, quality: u64, url: &str) {
        self.items.retain(|(i, q, _, _)| i != id || *q != quality);
        self.items
            .push_back((id.into(), quality, url.into(), Instant::now()));
        while self.items.len() > 4 {
            self.items.pop_front();
        }
    }
}
static CACHE: Mutex<Option<Cache>> = Mutex::new(None);
pub fn clear() {
    if let Ok(mut guard) = CACHE.lock() {
        if let Some(cache) = guard.as_mut() {
            cache.items.clear();
            cache.epoch = cache.epoch.wrapping_add(1)
        }
    }
}
pub fn resolve(
    token: &str,
    id: &str,
    quality: u64,
    fetch: impl FnOnce() -> Result<String>,
) -> Result<String> {
    if id.is_empty()
        || id.len() > 64
        || !id.bytes().all(|b| b.is_ascii_digit())
        || !matches!(quality, 128 | 192 | 320)
    {
        return Err("Некорректные параметры аудио".into());
    }
    let (scope, epoch) = {
        let mut guard = CACHE.lock().map_err(|_| "Кэш аудио занят")?;
        let cache = guard.get_or_insert_with(Cache::default);
        cache.select(token);
        if let Some(url) = cache.get(id, quality) {
            return Ok(url);
        }
        (cache.scope, cache.epoch)
    };
    let url = fetch()?;
    validate_download_url(&url)?;
    if url.len() > 8192 {
        return Err("Некорректный адрес аудио".into());
    }
    if let Ok(mut guard) = CACHE.lock() {
        if let Some(cache) = guard
            .as_mut()
            .filter(|c| c.scope == scope && c.epoch == epoch)
        {
            cache.put(id, quality, &url)
        }
    }
    Ok(url)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn quality_expiry_capacity_and_account_isolation() {
        let mut c = Cache::default();
        c.select("account-a");
        for id in 0..8 {
            c.put(&id.to_string(), 128, "https://storage.mds.yandex.net/audio")
        }
        assert_eq!(c.items.len(), 4);
        assert!(c.get("0", 128).is_none());
        assert!(c.get("7", 320).is_none());
        assert!(c.get("7", 128).is_some());
        c.items.back_mut().unwrap().3 = Instant::now() - TTL;
        assert!(c.get("7", 128).is_none());
        let epoch = c.epoch;
        c.select("account-b");
        assert!(c.get("6", 128).is_none());
        assert_ne!(epoch, c.epoch);
    }
    #[test]
    fn invalid_inputs_do_not_resolve_and_unsigned_hosts_are_rejected() {
        assert!(resolve("x", "../2", 128, || panic!(
            "invalid id must not reach network"
        ))
        .is_err());
        assert!(resolve("x", "42", 999, || panic!(
            "invalid quality must not reach network"
        ))
        .is_err());
        assert!(resolve("x", "42", 128, || Ok("http://example.com/private".into())).is_err());
    }
}
