//! Spotify OAuth PKCE and bounded metadata requests, independent from Yandex credentials.
use crate::{json, Result, Value};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use sha2::{Digest, Sha256};
use std::{io::Read, time::Duration};
const REDIRECT: &str = "org.luma.music://spotify-callback";
fn client_id(v: &Value) -> Result<&str> {
    v["client_id"]
        .as_str()
        .filter(|s| s.len() == 32 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or_else(|| "Укажите Client ID приложения Spotify (32 символа)".into())
}
fn opaque<'a>(v: &'a Value, key: &str, min: usize, max: usize) -> Result<&'a str> {
    v[key]
        .as_str()
        .filter(|s| {
            (min..=max).contains(&s.len())
                && s.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-._~".contains(&b))
        })
        .ok_or_else(|| "Некорректные параметры входа Spotify".into())
}
fn http() -> Result<reqwest::blocking::Client> {
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .pool_max_idle_per_host(1)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| "Не удалось подключить Spotify".into())
}
fn read(response: reqwest::blocking::Response) -> Result<Value> {
    let status = response.status().as_u16();
    if status == 429 {
        return Err("Лимит запросов Spotify. Повторите позже".into());
    }
    if status == 401 {
        return Err("Сессия Spotify истекла. Войдите снова".into());
    }
    if status == 403 {
        return Err(
            "Spotify отклонил доступ: проверьте права и доступ аккаунта к developer-приложению"
                .into(),
        );
    }
    if !(200..300).contains(&status) {
        return Err(format!("Spotify вернул HTTP {status}"));
    }
    let mut data = Vec::new();
    response
        .take(1024 * 1024 + 1)
        .read_to_end(&mut data)
        .map_err(|_| "Не удалось прочитать Spotify")?;
    if data.len() > 1024 * 1024 {
        return Err("Ответ Spotify слишком велик".into());
    }
    serde_json::from_slice(&data).map_err(|_| "Неизвестный ответ Spotify".into())
}
pub fn dispatch(op: &str, v: &Value) -> Result<Value> {
    match op {
        "spotify_start" => {
            let id = client_id(v)?;
            let verifier = opaque(v, "verifier", 43, 128)?;
            let state = opaque(v, "state", 32, 128)?;
            let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
            let mut url = reqwest::Url::parse("https://accounts.spotify.com/authorize").unwrap();
            url.query_pairs_mut().extend_pairs([("response_type","code"),("client_id",id),("redirect_uri",REDIRECT),
                ("code_challenge_method","S256"),("code_challenge",&challenge),("state",state),
                ("scope","user-read-private playlist-read-private playlist-read-collaborative user-library-read")]);
            Ok(json!({"url":url.as_str(),"redirect_uri":REDIRECT}))
        }
        "spotify_callback" => {
            let url = reqwest::Url::parse(v["url"].as_str().ok_or("Нет ответа Spotify")?)
                .map_err(|_| "Неверный ответ Spotify")?;
            if url.scheme() != "org.luma.music"
                || url.host_str() != Some("spotify-callback")
                || !url.path().is_empty()
                || url.port().is_some()
                || !url.username().is_empty()
                || url.password().is_some()
                || url.fragment().is_some()
            {
                return Err("Ответ пришёл не на callback Spotify".into());
            }
            let now = v["now"].as_u64().ok_or("Нет времени сессии")?;
            let started = v["started"].as_u64().ok_or("Нет времени входа")?;
            if now < started || now - started > 600 {
                return Err("Вход Spotify устарел. Начните заново".into());
            }
            let pairs = url.query_pairs().collect::<Vec<_>>();
            if pairs
                .iter()
                .any(|(k, _)| pairs.iter().filter(|(other, _)| other == k).count() > 1)
            {
                return Err("Повторяющиеся параметры Spotify".into());
            }
            let get = |key: &str| {
                pairs
                    .iter()
                    .find(|(k, _)| k == key)
                    .map(|(_, s)| s.as_ref())
            };
            if get("state") != Some(opaque(v, "state", 32, 128)?) {
                return Err("Неверный state Spotify. Начните вход заново".into());
            }
            if get("error").is_some() {
                return Err("Вход Spotify отменён или отклонён".into());
            }
            let code = get("code")
                .filter(|s| {
                    !s.is_empty() && s.len() <= 4096 && s.bytes().all(|b| b.is_ascii_graphic())
                })
                .ok_or("Spotify не вернул код входа")?;
            Ok(json!({"code":code}))
        }
        "spotify_exchange" | "spotify_refresh" => {
            let id = client_id(v)?;
            let mut form = vec![("client_id", id)];
            if op == "spotify_exchange" {
                form.extend([
                    ("grant_type", "authorization_code"),
                    ("redirect_uri", REDIRECT),
                    ("code_verifier", opaque(v, "verifier", 43, 128)?),
                ]);
                form.push((
                    "code",
                    v["code"]
                        .as_str()
                        .filter(|s| !s.is_empty() && s.len() <= 4096)
                        .ok_or("Нет кода Spotify")?,
                ));
            } else {
                form.extend([
                    ("grant_type", "refresh_token"),
                    (
                        "refresh_token",
                        v["refresh_token"]
                            .as_str()
                            .filter(|s| !s.is_empty() && s.len() <= 8192)
                            .ok_or("Нет refresh token Spotify")?,
                    ),
                ]);
            }
            let reply = read(
                http()?
                    .post("https://accounts.spotify.com/api/token")
                    .form(&form)
                    .send()
                    .map_err(|_| "Ошибка сети при входе Spotify")?,
            )?;
            let token = reply["access_token"]
                .as_str()
                .filter(|s| {
                    !s.is_empty() && s.len() <= 8192 && s.bytes().all(|b| b.is_ascii_graphic())
                })
                .ok_or("Spotify не предоставил токен")?;
            let seconds = reply["expires_in"]
                .as_u64()
                .filter(|n| (1..=86400).contains(n))
                .ok_or("Нет срока токена Spotify")?;
            Ok(
                json!({"access_token":token,"refresh_token":reply["refresh_token"].as_str().or(v["refresh_token"].as_str()).unwrap_or(""),
                "expires_in":seconds,"client_id":id}),
            )
        }
        "spotify_profile" | "spotify_playlists" | "spotify_likes" => {
            let token = v["access_token"]
                .as_str()
                .filter(|s| {
                    !s.is_empty() && s.len() <= 8192 && s.bytes().all(|b| b.is_ascii_graphic())
                })
                .ok_or("Войдите в Spotify")?;
            let offset = v["offset"].as_u64().unwrap_or(0);
            if offset > 10000 {
                return Err("Достигнут предел просмотра Spotify".into());
            }
            let url = if op == "spotify_profile" {
                "https://api.spotify.com/v1/me".to_string()
            } else {
                format!(
                    "https://api.spotify.com/v1/me/{}?limit=20&offset={offset}",
                    if op == "spotify_likes" {
                        "tracks"
                    } else {
                        "playlists"
                    }
                )
            };
            let data = read(
                http()?
                    .get(url)
                    .bearer_auth(token)
                    .send()
                    .map_err(|_| "Ошибка сети Spotify")?,
            )?;
            if op == "spotify_profile" {
                return Ok(
                    json!({"name":data["display_name"].as_str().unwrap_or("Spotify"),"id":data["id"]}),
                );
            }
            library_page(&data, op == "spotify_likes")
        }
        _ => Err("Неизвестная операция Spotify".into()),
    }
}
fn library_page(data: &Value, likes: bool) -> Result<Value> {
    let items=data["items"].as_array().ok_or("Spotify не вернул библиотеку")?.iter().take(20).filter_map(|raw|{
        let p=if likes {&raw["track"]} else {raw};
        let id=p["id"].as_str().filter(|s|!s.is_empty() && s.len()<=64 && s.bytes().all(|b|b.is_ascii_alphanumeric()))?;
        Some(json!({"id":id,"name":p["name"].as_str().unwrap_or("Spotify"),"uri":format!("spotify:{}:{id}",if likes {"track"} else {"playlist"})}))
    }).collect::<Vec<_>>();
    Ok(json!({"items":items,"has_more":!data["next"].is_null()}))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn playlists_and_likes_keep_correct_source_uris_and_filter_invalid_ids() {
        let p = library_page(
            &json!({"items":[{"id":"abc12","name":"Назад"},{"id":"x:../bad"}],"next":null}),
            false,
        )
        .unwrap();
        assert_eq!(p["items"].as_array().unwrap().len(), 1);
        assert_eq!(p["items"][0]["name"], "Назад");
        assert_eq!(p["items"][0]["uri"], "spotify:playlist:abc12");
        let t=library_page(&json!({"items":[{"track":{"id":"song123","name":"Song"}},{"track":null}],"next":"next-page"}),true).unwrap();
        assert_eq!(t["items"][0]["uri"], "spotify:track:song123");
        assert_eq!(t["has_more"], true);
    }
    #[test]
    fn pkce_matches_rfc_7636_and_has_no_secret() {
        let v = json!({"client_id":"0123456789abcdef0123456789abcdef","verifier":"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk","state":"a".repeat(43)});
        let r = dispatch("spotify_start", &v).unwrap();
        let u = reqwest::Url::parse(r["url"].as_str().unwrap()).unwrap();
        assert_eq!(
            u.query_pairs()
                .find(|(k, _)| k == "code_challenge")
                .unwrap()
                .1,
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
        assert!(!r.to_string().contains("client_secret"));
    }
    #[test]
    fn callback_rejects_wrong_origins_duplicates_expired_and_wrong_state() {
        let state = "a".repeat(43);
        let good = format!("{REDIRECT}?code=one-time-code&state={state}");
        let mut v = json!({"url":good,"state":state,"started":100,"now":150});
        assert_eq!(
            dispatch("spotify_callback", &v).unwrap()["code"],
            "one-time-code"
        );
        for url in [
            format!("{good}&code=other"),
            good.replace("org.luma.music:", "https:"),
            good.replace("state=a", "state=b"),
            format!("{good}#token=private"),
        ] {
            v["url"] = json!(url);
            assert!(dispatch("spotify_callback", &v).is_err());
        }
        v["url"] = json!(good);
        v["now"] = json!(701);
        assert!(dispatch("spotify_callback", &v).is_err());
        assert!(dispatch("spotify_start", &json!({})).is_err());
    }
}
