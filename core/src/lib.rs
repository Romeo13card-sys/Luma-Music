mod auth;
mod lyrics;
mod queue;
mod storage;
mod update_transport;
mod updates;
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::Duration;

const API: &str = "https://api.music.yandex.net";
type Result<T> = std::result::Result<T, String>;

#[derive(Debug, Deserialize, Serialize)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artists: String,
    pub available: bool,
}

fn track(value: &Value) -> Option<Track> {
    let id = match &value["id"] {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        _ => return None,
    };
    Some(Track {
        id,
        title: value["title"].as_str().unwrap_or("Без названия").into(),
        artists: value["artists"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v["name"].as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            })
            .unwrap_or_default(),
        available: value["available"].as_bool().unwrap_or(false),
    })
}

pub struct MusicClient {
    client: Client,
    token: String,
}
impl MusicClient {
    pub fn new(token: &str) -> Result<Self> {
        if token.trim().is_empty() || token.chars().any(char::is_whitespace) {
            return Err("Введите OAuth-токен без пробелов".into());
        }
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .user_agent("YaMusicRust/0.1")
            .build()
            .map_err(|_| "Не удалось создать HTTP-клиент")?;
        Ok(Self {
            client,
            token: token.into(),
        })
    }
    fn get(&self, path: &str, query: &[(&str, &str)]) -> Result<Value> {
        let response = self
            .client
            .get(format!("{API}{path}"))
            .header("Authorization", format!("OAuth {}", self.token))
            .query(query)
            .send()
            .map_err(|_| "Ошибка сети. Проверьте подключение")?;
        match response.status().as_u16() {
            401 | 403 => return Err("Доступ отклонён: проверьте токен и подписку".into()),
            200 => {}
            status => return Err(format!("Сервис вернул HTTP {status}")),
        }
        use std::io::Read;
        let mut bytes = Vec::new();
        response
            .take(4 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "Не удалось прочитать ответ сервиса")?;
        if bytes.len() > 4 * 1024 * 1024 {
            return Err("Ответ сервиса слишком большой. Попробуйте другой запрос.".into());
        }
        let body: Value =
            serde_json::from_slice(&bytes).map_err(|_| "Неизвестный формат ответа сервиса")?;
        if body.get("error").is_some() {
            return Err("Яндекс Музыка отклонила запрос".into());
        }
        body.get("result")
            .cloned()
            .ok_or_else(|| "В ответе отсутствует result".into())
    }
    pub fn account(&self) -> Result<Value> {
        let value = self.get("/account/status", &[])?;
        let account = &value["account"];
        if account["uid"].is_null() {
            return Err("Не удалось подтвердить аккаунт".into());
        }
        Ok(
            json!({"uid": account["uid"], "name": account["displayName"].as_str()
            .or(account["login"].as_str()).unwrap_or("Аккаунт Яндекса")}),
        )
    }
    pub fn search(&self, text: &str) -> Result<Vec<Track>> {
        if text.trim().is_empty() {
            return Ok(vec![]);
        }
        let value = self.get(
            "/search",
            &[("text", text), ("type", "track"), ("page", "0")],
        )?;
        if value["tracks"].is_null() {
            return Ok(vec![]);
        }
        let results = value["tracks"]["results"]
            .as_array()
            .ok_or("Сервис не вернул список треков")?;
        Ok(results.iter().filter_map(track).collect())
    }
    pub fn library(&self) -> Result<Value> {
        let account = self.account()?;
        let uid = account["uid"]
            .as_u64()
            .map(|v| v.to_string())
            .or_else(|| account["uid"].as_str().map(String::from))
            .ok_or("Некорректный аккаунт")?;
        self.get(&format!("/users/{uid}/playlists/list"), &[])
    }
    pub fn playlist(&self, argument: &str) -> Result<Vec<Track>> {
        let parts: Vec<_> = argument.split(':').collect();
        if parts.len() != 2
            || parts
                .iter()
                .any(|s| s.is_empty() || !s.bytes().all(|c| c.is_ascii_digit()))
        {
            return Err("Некорректный плейлист".into());
        }
        let value = self.get(&format!("/users/{}/playlists/{}", parts[0], parts[1]), &[])?;
        let items = value["tracks"].as_array().ok_or("Нет списка треков")?;
        Ok(items.iter().filter_map(|v| track(&v["track"])).collect())
    }
    pub fn likes(&self) -> Result<Vec<Track>> {
        let account = self.account()?;
        let uid = account["uid"]
            .as_u64()
            .map(|v| v.to_string())
            .or_else(|| account["uid"].as_str().map(String::from))
            .ok_or("Некорректный аккаунт")?;
        let value = self.get(&format!("/users/{uid}/likes/tracks"), &[])?;
        let items = value["library"]["tracks"]
            .as_array()
            .ok_or("Нет списка избранного")?;
        let ids = items
            .iter()
            .take(100)
            .filter_map(|v| {
                v["id"]
                    .as_str()
                    .map(String::from)
                    .or_else(|| v["id"].as_u64().map(|id| id.to_string()))
            })
            .collect::<Vec<_>>()
            .join(",");
        if ids.is_empty() {
            return Ok(vec![]);
        }
        let value = self.get("/tracks", &[("track-ids", &ids)])?;
        Ok(value
            .as_array()
            .ok_or("Нет данных треков")?
            .iter()
            .filter_map(track)
            .collect())
    }
    pub fn wave(&self, previous: &str) -> Result<Value> {
        if !previous.is_empty() && !previous.bytes().all(|b| b.is_ascii_digit()) {
            return Err("Некорректный трек продолжения волны".into());
        }
        let mut query = vec![("settings2", "True")];
        if !previous.is_empty() {
            query.push(("queue", previous));
        }
        parse_wave(&self.get("/rotor/station/user:onyourwave/tracks", &query)?)
    }
    pub fn wave_feedback(&self, argument: &str) -> Result<Value> {
        let body: Value = serde_json::from_str(argument).map_err(|_| "Некорректный отзыв волны")?;
        let kind = body["type"].as_str().unwrap_or("");
        if !["radioStarted", "trackStarted", "trackFinished", "skip"].contains(&kind)
            || body["timestamp"].as_str().is_none()
            || body["batchId"].as_str().is_none()
            || (kind != "radioStarted"
                && !body["trackId"]
                    .as_str()
                    .map(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
                    .unwrap_or(false))
        {
            return Err("Некорректный отзыв волны".into());
        }
        let mut data = json!({"type":kind, "timestamp":body["timestamp"]});
        if kind == "radioStarted" {
            data["from"] = json!("luma-music");
        } else {
            data["trackId"] = body["trackId"].clone();
        }
        if let Some(seconds) = body["totalPlayedSeconds"].as_u64() {
            data["totalPlayedSeconds"] = json!(seconds);
        }
        let response = self
            .client
            .post(format!("{API}/rotor/station/user:onyourwave/feedback"))
            .header("Authorization", format!("OAuth {}", self.token))
            .query(&[("batch-id", body["batchId"].as_str().unwrap_or(""))])
            .json(&data)
            .send()
            .map_err(|_| "Ошибка отправки отзыва волны")?
            .error_for_status()
            .map_err(|_| "Сервис отклонил отзыв волны")?;
        let result: Value = response
            .json()
            .map_err(|_| "Неизвестный ответ на отзыв волны")?;
        if result["result"] != "ok" {
            return Err("Отзыв волны не принят".into());
        }
        Ok(json!(true))
    }
    pub fn stream(&self, id: &str) -> Result<String> {
        self.stream_at(id, 320)
    }
    pub fn stream_at(&self, id: &str, quality: u64) -> Result<String> {
        if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
            return Err("Некорректный идентификатор трека".into());
        }
        let value = self.get(&format!("/tracks/{id}/download-info"), &[])?;
        let info = download_choice(&value, quality)?;
        let url = info["downloadInfoUrl"]
            .as_str()
            .ok_or("Отсутствует адрес потока")?;
        validate_download_url(url)?;
        let response = self
            .client
            .get(url)
            .send()
            .map_err(|_| "Не удалось получить адрес аудио")?
            .error_for_status()
            .map_err(|_| "Сервис отклонил получение аудио")?;
        use std::io::Read;
        let mut metadata = String::new();
        response
            .take(256 * 1024 + 1)
            .read_to_string(&mut metadata)
            .map_err(|_| "Не удалось прочитать данные аудио")?;
        parse_download_metadata(&metadata)?.signed_url()
    }
}

fn bitrate(value: &Value) -> Option<u64> {
    value["bitrateInKbps"]
        .as_u64()
        .or_else(|| value["bitrateInKbps"].as_str()?.parse().ok())
}
fn download_choice(value: &Value, quality: u64) -> Result<&Value> {
    if ![128, 192, 320].contains(&quality) {
        return Err("Неизвестное качество звука".into());
    }
    let items = value
        .as_array()
        .ok_or("Неизвестный формат списка аудиопотоков")?;
    let compatible: Vec<_> = items
        .iter()
        .filter(|v| {
            v["codec"]
                .as_str()
                .is_some_and(|s| s.eq_ignore_ascii_case("mp3"))
                && (v["transport"].is_null()
                    || v["transport"]
                        .as_str()
                        .is_some_and(|s| s.eq_ignore_ascii_case("raw")))
                && bitrate(v).is_some_and(|rate| rate > 0)
        })
        .collect();
    if let Some(info) = compatible
        .iter()
        .copied()
        .filter(|v| bitrate(v).unwrap_or(u64::MAX) <= quality)
        .max_by_key(|v| bitrate(v))
    {
        return Ok(info);
    }
    if !compatible.is_empty() {
        let mut rates: Vec<_> = compatible.iter().filter_map(|v| bitrate(v)).collect();
        rates.sort_unstable();
        rates.dedup();
        return Err(format!("Лимит качества {quality} кбит/с, доступны {} кбит/с. Откройте настройки качества звука и повысьте лимит для текущей сети.",rates.iter().map(u64::to_string).collect::<Vec<_>>().join(", ")));
    }
    Err("Яндекс не предоставил доступный обычный MP3-поток. Проверьте подписку и права аккаунта или попробуйте другой трек. Защищённые потоки не поддерживаются.".into())
}

fn parse_wave(value: &Value) -> Result<Value> {
    let batch = value["batchId"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or("Отсутствует партия волны")?;
    let sequence = value["sequence"].as_array().ok_or("Нет треков волны")?;
    let tracks: Vec<Value> = sequence
        .iter()
        .filter_map(|v| track(&v["track"]))
        .filter(|t| t.available)
        .take(30)
        .map(|t| {
            let mut value = json!(t);
            value["waveBatch"] = json!(batch);
            value
        })
        .collect();
    if tracks.is_empty() {
        return Err("Волна не вернула доступных треков".into());
    }
    Ok(json!(tracks))
}

/// Audio transport never sends an account token and follows only trusted HTTPS redirects.
pub fn audio_client() -> Result<Client> {
    Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .user_agent("Luma-Music-Audio")
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 5
                || validate_download_url(attempt.url().as_str()).is_err()
            {
                attempt.error("Untrusted audio redirect")
            } else {
                attempt.follow()
            }
        }))
        .build()
        .map_err(|_| "Не удалось создать аудиоклиент".into())
}
fn validate_download_url(raw: &str) -> Result<()> {
    let url = reqwest::Url::parse(raw).map_err(|_| "Некорректный адрес аудио")?;
    let host = url.host_str().unwrap_or("");
    if url.scheme() != "https"
        || !(host.ends_with(".yandex.net") || host.ends_with(".yandex.ru"))
        || !url.username().is_empty()
        || url.password().is_some()
        || url.port().is_some()
    {
        return Err("Недоверенный адрес аудио".into());
    }
    Ok(())
}
#[derive(Deserialize)]
struct Download {
    host: String,
    path: String,
    ts: String,
    s: String,
}
fn parse_download_metadata(raw: &str) -> Result<Download> {
    if raw.len() > 256 * 1024 {
        return Err("Ответ с параметрами аудио слишком большой".into());
    }
    if raw.trim_start().starts_with('{') {
        let mut value: Value =
            serde_json::from_str(raw).map_err(|_| "Не удалось прочитать JSON-параметры аудио")?;
        if let Some(ts) = value["ts"].as_u64() {
            value["ts"] = json!(ts.to_string());
        }
        serde_json::from_value(value)
            .map_err(|_| "В ответе Яндекса отсутствуют параметры аудио".into())
    } else {
        quick_xml::de::from_str(raw).map_err(|_| "Не удалось прочитать XML-параметры аудио".into())
    }
}
impl Download {
    fn signed_url(&self) -> Result<String> {
        if !self.path.starts_with('/') || self.path.contains(['\r', '\n', '?', '#']) {
            return Err("Яндекс вернул некорректный путь аудио".into());
        }
        // ts is an opaque URL segment, often hexadecimal, NOT necessarily a decimal integer.
        if self.ts.is_empty()
            || self.ts.len() > 64
            || !self
                .ts
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"_-".contains(&c))
        {
            return Err("Яндекс вернул некорректную метку времени аудио".into());
        }
        validate_download_url(&format!("https://{}/", self.host))?;
        let sign = md5::compute(format!(
            "XGRlBW9FXlekgbPrRHuSiA{}{}",
            &self.path[1..],
            self.s
        ));
        Ok(format!(
            "https://{}/get-mp3/{:x}/{}{}",
            self.host, sign, self.ts, self.path
        ))
    }
}

pub fn dispatch(token: &str, operation: &str, argument: &str) -> String {
    let result = (|| {
        let client = MusicClient::new(token)?;
        match operation {
            "account" => client.account(),
            "search" => client.search(argument).map(|tracks| json!(tracks)),
            "library" => client.library(),
            "playlist" => client.playlist(argument).map(|v| json!(v)),
            "likes" => client.likes().map(|v| json!(v)),
            "wave" => client.wave(argument),
            "wave_feedback" => client.wave_feedback(argument),
            "stream_quality" => {
                let options: Value = serde_json::from_str(argument)
                    .map_err(|_| "Не удалось прочитать запрос качества звука")?;
                client
                    .stream_at(
                        options["id"].as_str().unwrap_or(""),
                        options["quality"].as_u64().unwrap_or(0),
                    )
                    .map(|url| json!(url))
            }
            "stream" => client.stream(argument).map(|url| json!(url)),
            _ => Err("Неизвестная операция".into()),
        }
    })();
    match result {
        Ok(data) => json!({"ok":true,"data":data}).to_string(),
        Err(error) => json!({"ok":false,"error":error}).to_string(),
    }
}

pub fn core_dispatch(operation: &str, argument: &str) -> String {
    let result: Result<Value> = (|| {
        if argument.len() > queue::MAX_BYTES {
            return Err("Запрос ядра слишком большой".into());
        }
        if operation == "queue_normalize" {
            return queue::normalize(argument).map(|s| json!(s));
        }
        let v: Value = serde_json::from_str(argument).map_err(|_| "Некорректный запрос ядра")?;
        match operation {
            "auth_start" => auth::start(&v),
            "auth_navigation" => auth::navigation(&v),
            "settings_defaults" => Ok(storage::defaults()),
            "queue_next" => queue::next(&v),
            "upgrade_validate" => updates::validate_upgrade(&v),
            "daily_check_due" => Ok(json!(updates::daily_due(
                v["now"].as_i64().unwrap_or(0),
                v["previous"].as_i64().unwrap_or(0)
            ))),
            "update_check" => update_transport::check(&v),
            "lyrics" => lyrics::fetch(&v),
            "version_compare" => Ok(json!(updates::compare(
                v["candidate"].as_str().unwrap_or(""),
                v["installed"].as_str().unwrap_or("")
            ))),
            "checksum" => updates::checksum(
                v["text"].as_str().unwrap_or(""),
                v["name"].as_str().unwrap_or(""),
            )
            .map(|s| json!(s)),
            "asset_select" => updates::asset(&v["assets"], &v["abis"]),
            _ => Err("Неизвестная операция ядра".into()),
        }
    })();
    match result {
        Ok(data) => json!({"ok":true,"data":data}).to_string(),
        Err(error) => json!({"ok":false,"error":error}).to_string(),
    }
}
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_org_luma_music_Native_core(
    mut env: jni::JNIEnv,
    _object: jni::objects::JObject,
    operation: jni::objects::JString,
    argument: jni::objects::JString,
) -> jni::sys::jstring {
    let value = (|| {
        let operation: String = env.get_string(&operation).ok()?.into();
        let argument: String = env.get_string(&argument).ok()?.into();
        Some(core_dispatch(&operation, &argument))
    })()
    .unwrap_or_else(|| json!({"ok":false,"error":"Ошибка JNI"}).to_string());
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_org_luma_music_Native_store(
    mut env: jni::JNIEnv,
    _object: jni::objects::JObject,
    path: jni::objects::JString,
    operation: jni::objects::JString,
    argument: jni::objects::JString,
) -> jni::sys::jstring {
    let result = (|| {
        let path: String = env.get_string(&path).map_err(|_| "Ошибка JNI")?.into();
        let operation: String = env.get_string(&operation).map_err(|_| "Ошибка JNI")?.into();
        let argument: String = env.get_string(&argument).map_err(|_| "Ошибка JNI")?.into();
        storage::dispatch(&path, &operation, &argument)
    })();
    let value = match result {
        Ok(data) => json!({"ok":true,"data":data}),
        Err(error) => json!({"ok":false,"error":error}),
    }
    .to_string();
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_org_luma_music_Native_download(
    mut env: jni::JNIEnv,
    _object: jni::objects::JObject,
    argument: jni::objects::JString,
    progress: jni::objects::JObject,
) -> jni::sys::jstring {
    let result: Result<String> = (|| {
        let raw: String = env.get_string(&argument).map_err(|_| "Ошибка JNI")?.into();
        if raw.len() > queue::MAX_BYTES {
            return Err("Запрос обновления слишком большой".into());
        }
        let data: Value =
            serde_json::from_str(&raw).map_err(|_| "Некорректный запрос обновления")?;
        update_transport::download(&data, |percent| {
            match env.call_method(
                &progress,
                "onProgress",
                "(I)Z",
                &[jni::objects::JValue::Int(percent)],
            ) {
                Ok(value) => value.z().unwrap_or(false),
                Err(_) => {
                    let _ = env.exception_clear();
                    false
                }
            }
        })
    })();
    let value = match result {
        Ok(data) => json!({"ok":true,"data":data}),
        Err(error) => json!({"ok":false,"error":error}),
    }
    .to_string();
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_org_luma_music_Native_request(
    mut env: jni::JNIEnv,
    _class: jni::objects::JObject,
    token: jni::objects::JString,
    operation: jni::objects::JString,
    argument: jni::objects::JString,
) -> jni::sys::jstring {
    let result = (|| {
        let token: String = env.get_string(&token).ok()?.into();
        let operation: String = env.get_string(&operation).ok()?.into();
        let argument: String = env.get_string(&argument).ok()?.into();
        Some(dispatch(&token, &operation, &argument))
    })()
    .unwrap_or_else(|| json!({"ok":false,"error":"Ошибка JNI"}).to_string());
    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn offline_dispatch_needs_no_credentials_and_reports_invalid_input() {
        let reply: Value = serde_json::from_str(&core_dispatch(
            "queue_normalize",
            r#"{"songs":[],"index":0,"position":0}"#,
        ))
        .unwrap();
        assert_eq!(reply["ok"], true);
        for (op, arg) in [("unknown", "{}"), ("queue_normalize", "bad-json")] {
            let error: Value = serde_json::from_str(&core_dispatch(op, arg)).unwrap();
            assert_eq!(error["ok"], false);
        }
    }
    #[test]
    fn parses_search_tracks_with_multiple_artists() {
        let result = track(
            &json!({"id":42,"title":"Трек","artists":[{"name":"А"},{"name":"Б"}],"available":true}),
        )
        .unwrap();
        assert_eq!(result.id, "42");
        assert_eq!(result.artists, "А, Б");
        assert!(result.available);
        assert!(track(&json!({"title":"Без ID"})).is_none());
    }
    #[test]
    fn rejects_credentials_and_external_audio_hosts() {
        for url in [
            "http://a.yandex.net/x",
            "https://evil.test/x",
            "https://yandex.net.evil.test/x",
            "https://user@a.yandex.net/x",
            "https://a.yandex.net:8080/x",
        ] {
            assert!(validate_download_url(url).is_err());
        }
        assert!(validate_download_url("https://storage.mds.yandex.net/x").is_ok());
    }
    #[test]
    fn signs_download_metadata() {
        let xml = "<download-info><host>test.yandex.net</host><path>/abc.mp3</path><ts>123</ts><s>salt</s></download-info>";
        let data: Download = quick_xml::de::from_str(xml).unwrap();
        let hash = format!("{:x}", md5::compute("XGRlBW9FXlekgbPrRHuSiAabc.mp3salt"));
        assert_eq!(
            data.signed_url().unwrap(),
            format!("https://test.yandex.net/get-mp3/{hash}/123/abc.mp3")
        );
    }
    #[test]
    fn invalid_token_returns_safe_error() {
        let result: Value = serde_json::from_str(&dispatch("bad\ntoken", "account", "")).unwrap();
        assert_eq!(result["ok"], false);
        assert!(!result.to_string().contains("bad"));
    }
    #[test]
    fn playlist_rejects_path_injection_before_network() {
        let client = MusicClient::new("test-token").unwrap();
        for invalid in ["", "1", "1:2:3", "../users:1", "1:2?x=1", "1:"] {
            assert_eq!(
                client.playlist(invalid).unwrap_err(),
                "Некорректный плейлист"
            );
        }
    }
    #[test]
    fn wave_keeps_batch_and_filters_unavailable_tracks() {
        let value = json!({"batchId":"batch-42", "sequence":[
            {"track":{"id":1,"title":"Available","available":true}},
            {"track":{"id":2,"available":false}}, {"track":{}}
        ]});
        let parsed = parse_wave(&value).unwrap();
        assert_eq!(parsed.as_array().unwrap().len(), 1);
        assert_eq!(parsed[0]["waveBatch"], "batch-42");
        assert!(parse_wave(&json!({"sequence":[]})).is_err());
        assert!(parse_wave(&json!({"batchId":"b","sequence":[]})).is_err());
    }
    #[test]
    fn wave_bounds_memory_and_rejects_invalid_inputs_without_network() {
        let items: Vec<Value> = (1..=100)
            .map(|id| json!({"track":{"id":id,"available":true}}))
            .collect();
        assert_eq!(
            parse_wave(&json!({"batchId":"b","sequence":items}))
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            30
        );
        let client = MusicClient::new("test-token").unwrap();
        assert!(client.wave("../escape").is_err());
        assert!(client.wave_feedback(r#"{"type":"unknown"}"#).is_err());
    }
    #[test]
    fn supports_optional_transport_and_string_bitrate_without_accepting_protected_streams() {
        let v = json!([{"codec":"MP3","bitrateInKbps":"192"}]);
        assert_eq!(download_choice(&v, 320).unwrap()["bitrateInKbps"], "192");
        assert!(download_choice(&v, 128).unwrap_err().contains("192"));
        assert!(download_choice(
            &json!([{"codec":"mp3","transport":"encrypted","bitrateInKbps":128}]),
            320
        )
        .is_err());
    }
    #[test]
    fn quality_limit_never_silently_exceeds_mobile_budget() {
        let items = json!([
            {"codec":"mp3","transport":"raw","bitrateInKbps":320},
            {"codec":"mp3","transport":"raw","bitrateInKbps":128},
            {"codec":"aac","transport":"raw","bitrateInKbps":192}
        ]);
        assert_eq!(download_choice(&items, 128).unwrap()["bitrateInKbps"], 128);
        assert_eq!(download_choice(&items, 192).unwrap()["bitrateInKbps"], 128);
        assert_eq!(download_choice(&items, 320).unwrap()["bitrateInKbps"], 320);
        assert!(download_choice(
            &json!([{"codec":"mp3","transport":"raw","bitrateInKbps":320}]),
            128
        )
        .is_err());
        assert!(download_choice(&items, 999).is_err());
    }
}

/// C ABI for native Apple UI. Returned memory must be released with luma_free.
#[no_mangle]
pub unsafe extern "C" fn luma_request(
    token: *const std::ffi::c_char,
    operation: *const std::ffi::c_char,
    argument: *const std::ffi::c_char,
) -> *mut std::ffi::c_char {
    if token.is_null() || operation.is_null() || argument.is_null() {
        return std::ptr::null_mut();
    }
    let read = |p| std::ffi::CStr::from_ptr(p).to_str().ok();
    let result = match (read(token), read(operation), read(argument)) {
        (Some(t), Some(o), Some(a)) => dispatch(t, o, a),
        _ => json!({"ok":false,"error":"Invalid UTF-8"}).to_string(),
    };
    std::ffi::CString::new(result)
        .map(|v| v.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub unsafe extern "C" fn luma_free(value: *mut std::ffi::c_char) {
    if !value.is_null() {
        drop(std::ffi::CString::from_raw(value));
    }
}

#[cfg(test)]
mod ffi_tests {
    #[test]
    fn apple_bridge_returns_owned_json_and_handles_null() {
        use std::ffi::{CStr, CString};
        let t = CString::new("invalid token").unwrap();
        let o = CString::new("account").unwrap();
        let a = CString::new("").unwrap();
        unsafe {
            let result = super::luma_request(t.as_ptr(), o.as_ptr(), a.as_ptr());
            assert!(!result.is_null());
            let reply: serde_json::Value =
                serde_json::from_slice(CStr::from_ptr(result).to_bytes()).unwrap();
            assert_eq!(reply["ok"], false);
            super::luma_free(result);
            assert!(super::luma_request(std::ptr::null(), o.as_ptr(), a.as_ptr()).is_null());
            super::luma_free(std::ptr::null_mut());
        }
    }
}

#[cfg(test)]
mod audio_metadata_tests {
    use super::*;
    #[test]
    fn xml_and_json_support_opaque_hex_and_decimal_timestamps() {
        for ts in ["65ab01cd", "1712345678"] {
            let xml=format!("<download-info><host>s1.storage.yandex.net</host><path>/track.mp3</path><ts>{ts}</ts><s>salt</s></download-info>");
            let from_xml = parse_download_metadata(&xml).unwrap().signed_url().unwrap();
            let from_json = parse_download_metadata(
                &json!({"host":"s1.storage.yandex.net","path":"/track.mp3","ts":ts,"s":"salt"})
                    .to_string(),
            )
            .unwrap()
            .signed_url()
            .unwrap();
            assert_eq!(from_xml, from_json);
            assert!(from_xml.ends_with(&format!("/{ts}/track.mp3")));
        }
        assert!(parse_download_metadata(
            r#"{"host":"s1.storage.yandex.net","path":"/track.mp3","ts":1712345678,"s":"salt"}"#
        )
        .unwrap()
        .signed_url()
        .is_ok());
    }
    #[test]
    fn rejects_url_injection_without_rejecting_valid_server_tokens() {
        for ts in ["", "../", "a/b", "x?y", "a#b", "a%2fb", "bad value"] {
            let info = Download {
                host: "s1.storage.yandex.net".into(),
                path: "/track.mp3".into(),
                ts: ts.into(),
                s: "salt".into(),
            };
            assert!(info.signed_url().is_err());
        }
        assert!(parse_download_metadata(&"x".repeat(256 * 1024 + 1)).is_err());
        let malformed: Value =
            serde_json::from_str(&dispatch("unit_token", "stream_quality", "{")).unwrap();
        assert_eq!(
            malformed["error"],
            "Не удалось прочитать запрос качества звука"
        );
    }
}
