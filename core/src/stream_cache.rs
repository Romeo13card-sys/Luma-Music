//! Short-lived signed URLs in RAM. No secrets/URLs are persisted or shared across accounts.
use crate::{validate_download_url, Result};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, Condvar, Mutex},
    time::{Duration, Instant},
};

const TTL: Duration = Duration::from_secs(45);

type CacheKey = ([u8; 32], u64, String, u64);

struct Flight {
    result: Mutex<Option<Result<String>>>,
    ready: Condvar,
}

impl Flight {
    fn new() -> Self {
        Self {
            result: Mutex::new(None),
            ready: Condvar::new(),
        }
    }

    fn finish(&self, result: Result<String>) {
        if let Ok(mut slot) = self.result.lock() {
            *slot = Some(result);
            self.ready.notify_all();
        }
    }

    fn wait(&self) -> Result<String> {
        let mut slot = self.result.lock().map_err(|_| "Кэш аудио занят")?;
        while slot.is_none() {
            slot = self.ready.wait(slot).map_err(|_| "Кэш аудио занят")?;
        }
        slot.as_ref().expect("flight result was checked").clone()
    }
}

#[derive(Default)]
struct Cache {
    scope: [u8; 32],
    epoch: u64,
    items: VecDeque<(String, u64, String, Instant)>,
    inflight: HashMap<CacheKey, Arc<Flight>>,
}

impl Cache {
    fn select(&mut self, token: &str) {
        let scope: [u8; 32] = Sha256::digest(token.as_bytes()).into();
        if self.scope != scope {
            self.scope = scope;
            self.epoch = self.epoch.wrapping_add(1);
            self.items.clear();
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
        while self.items.len() > 8 {
            self.items.pop_front();
        }
    }
}

static CACHE: Mutex<Option<Cache>> = Mutex::new(None);

pub fn clear() {
    if let Ok(mut guard) = CACHE.lock() {
        if let Some(cache) = guard.as_mut() {
            cache.items.clear();
            cache.epoch = cache.epoch.wrapping_add(1);
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

    let (key, flight, owner) = {
        let mut guard = CACHE.lock().map_err(|_| "Кэш аудио занят")?;
        let cache = guard.get_or_insert_with(Cache::default);
        cache.select(token);
        if let Some(url) = cache.get(id, quality) {
            return Ok(url);
        }

        let key = (cache.scope, cache.epoch, id.to_owned(), quality);
        if let Some(existing) = cache.inflight.get(&key) {
            (key, Arc::clone(existing), false)
        } else {
            let flight = Arc::new(Flight::new());
            cache.inflight.insert(key.clone(), Arc::clone(&flight));
            (key, flight, true)
        }
    };

    if !owner {
        return flight.wait();
    }

    let result = fetch().and_then(|url| {
        validate_download_url(&url)?;
        if url.len() > 8192 {
            return Err("Некорректный адрес аудио".into());
        }
        Ok(url)
    });

    if let Ok(url) = &result {
        if let Ok(mut guard) = CACHE.lock() {
            if let Some(cache) = guard
                .as_mut()
                .filter(|cache| cache.scope == key.0 && cache.epoch == key.1)
            {
                cache.put(id, quality, url);
            }
        }
    }

    flight.finish(result.clone());
    if let Ok(mut guard) = CACHE.lock() {
        if let Some(cache) = guard.as_mut() {
            if cache
                .inflight
                .get(&key)
                .is_some_and(|current| Arc::ptr_eq(current, &flight))
            {
                cache.inflight.remove(&key);
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::atomic::{AtomicUsize, Ordering},
        thread,
    };

    #[test]
    fn quality_expiry_capacity_and_account_isolation() {
        let mut c = Cache::default();
        c.select("account-a");
        for id in 0..12 {
            c.put(&id.to_string(), 128, "https://storage.mds.yandex.net/audio")
        }
        assert_eq!(c.items.len(), 8);
        assert!(c.get("0", 128).is_none());
        assert!(c.get("11", 320).is_none());
        assert!(c.get("11", 128).is_some());
        c.items.back_mut().unwrap().3 = Instant::now() - TTL;
        assert!(c.get("11", 128).is_none());
        let epoch = c.epoch;
        c.select("account-b");
        assert!(c.get("10", 128).is_none());
        assert_ne!(epoch, c.epoch);
    }

    #[test]
    fn concurrent_requests_share_one_stream_resolution() {
        let calls = Arc::new(AtomicUsize::new(0));
        let barrier = Arc::new(std::sync::Barrier::new(8));
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let calls = Arc::clone(&calls);
                let barrier = Arc::clone(&barrier);
                thread::spawn(move || {
                    barrier.wait();
                    resolve(
                        "stream-cache-dedupe-test-account",
                        "9876543210123",
                        192,
                        || {
                            calls.fetch_add(1, Ordering::SeqCst);
                            thread::sleep(Duration::from_millis(60));
                            Ok("https://storage.mds.yandex.net/deduplicated-audio".into())
                        },
                    )
                    .unwrap()
                })
            })
            .collect();

        for worker in workers {
            assert_eq!(
                worker.join().unwrap(),
                "https://storage.mds.yandex.net/deduplicated-audio"
            );
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1);
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
