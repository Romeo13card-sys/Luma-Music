//! YAYMA-style Yandex login navigation, adapted to a native Android WebView.
//! Public OAuth client identifier from the pinned GPL-3.0 YAYMA source; no client secret.
use serde_json::{json, Value};
const CLIENT: &str = "97fe03033fa34407ac9bcf91d5afed5b";
fn state(v: &Value) -> Result<&str, String> {
    v["state"]
        .as_str()
        .filter(|s| {
            s.len() >= 16
                && s.len() <= 128
                && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
        .ok_or_else(|| "Начните вход заново".into())
}
pub fn start(v: &Value) -> Result<Value, String> {
    let state = state(v)?;
    let mut url = reqwest::Url::parse("https://oauth.yandex.ru/authorize").unwrap();
    url.query_pairs_mut()
        .append_pair("response_type", "token")
        .append_pair("client_id", CLIENT)
        .append_pair("state", state);
    Ok(
        json!({"loginUrl":"https://passport.yandex.ru/pwl-yandex/auth/","authorizeUrl":url.as_str()}),
    )
}
pub fn navigation(v: &Value) -> Result<Value, String> {
    let expected = state(v)?;
    let age = v["ageMs"].as_u64().unwrap_or(u64::MAX);
    if age > 600_000 {
        return Ok(json!({"action":"error","message":"Время входа истекло. Начните заново."}));
    }
    let Some(raw) = v["url"].as_str().filter(|s| s.len() <= 16384) else {
        return Ok(json!({"action":"deny"}));
    };
    let Ok(url) = reqwest::Url::parse(raw) else {
        return Ok(json!({"action":"deny"}));
    };
    let host = url.host_str().unwrap_or("");
    let trusted = url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && ["yandex.ru", "yandex.com", "yandex.net"]
            .iter()
            .any(|h| host == *h || host.ends_with(&format!(".{h}")));
    if !trusted {
        return Ok(json!({"action":"deny"}));
    }
    let query = reqwest::Url::parse(&format!(
        "https://callback.invalid/?{}",
        url.fragment().unwrap_or("")
    ))
    .map_err(|_| "Некорректный ответ входа")?;
    let params: Vec<_> = query.query_pairs().collect();
    let tokens: Vec<_> = params.iter().filter(|(k, _)| k == "access_token").collect();
    if !tokens.is_empty() {
        let states: Vec<_> = params.iter().filter(|(k, _)| k == "state").collect();
        let oauth_origin = matches!(
            host,
            "oauth.yandex.ru" | "oauth.yandex.com" | "music.yandex.ru" | "music.yandex.com"
        );
        if !oauth_origin
            || v["authorizing"].as_bool() != Some(true)
            || states.len() != 1
            || states[0].1 != expected
            || tokens.len() != 1
        {
            return Ok(
                json!({"action":"error","message":"Ответ входа не соответствует текущему запросу. Начните заново."}),
            );
        }
        let token = tokens[0].1.as_ref();
        if !(16..=4096).contains(&token.len())
            || !token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        {
            return Ok(json!({"action":"error","message":"Яндекс вернул некорректный токен"}));
        }
        return Ok(json!({"action":"token","token":token}));
    }
    if params.iter().any(|(k, _)| k == "error") {
        return Ok(
            json!({"action":"error","message":"Яндекс не разрешил вход. Повторите попытку."}),
        );
    }
    let profile = matches!(host, "id.yandex.ru" | "id.yandex.com")
        || (matches!(host, "passport.yandex.ru" | "passport.yandex.com")
            && (url.path() == "/profile" || url.path().starts_with("/profile/")));
    Ok(
        json!({"action":if profile && v["authorizing"].as_bool()!=Some(true){"authorize"}else{"allow"}}),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    fn request(url: &str) -> Value {
        json!({"url":url,"state":"0123456789abcdef0123456789abcdef","ageMs":1000,"authorizing":true})
    }
    #[test]
    fn accepts_only_current_oauth_response() {
        let good="https://oauth.yandex.ru/verification_code#access_token=y0_abcdefghijklmnop&state=0123456789abcdef0123456789abcdef";
        assert_eq!(navigation(&request(good)).unwrap()["action"], "token");
        for url in [
            good.replace("oauth.yandex.ru", "evil.test"),
            good.replace("state=0123456789abcdef0123456789abcdef", "state=wrong"),
            format!("{good}&access_token=other"),
            good.replace("https:", "http:"),
        ] {
            assert_ne!(navigation(&request(&url)).unwrap()["action"], "token");
        }
        let mut v = request(good);
        v["ageMs"] = json!(600001);
        assert_eq!(navigation(&v).unwrap()["action"], "error");
        v = request(good);
        v["authorizing"] = json!(false);
        assert_eq!(navigation(&v).unwrap()["action"], "error");
    }
    #[test]
    fn profile_requires_single_oauth_transition_and_never_accepts_passwords() {
        let mut v = request("https://id.yandex.ru");
        v["authorizing"] = json!(false);
        assert_eq!(navigation(&v).unwrap()["action"], "authorize");
        v["authorizing"] = json!(true);
        assert_eq!(navigation(&v).unwrap()["action"], "allow");
        assert_eq!(
            navigation(&request("https://passport.yandex.ru.evil.test/")).unwrap()["action"],
            "deny"
        );
        let auth = start(&v).unwrap();
        assert!(auth["authorizeUrl"].as_str().unwrap().contains("state="));
        assert!(!auth.to_string().contains("client_secret"));
    }
}
