//! Public GitHub OTA transport. Android owns certificate inspection and installation consent.
use crate::updates;
use reqwest::blocking::Client;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::Path,
    time::Duration,
};
const API: &str = "https://api.github.com/repos/Romeo13card-sys/Luma-Music";
const MAX_APK: u64 = 100 * 1024 * 1024;
type Result<T> = std::result::Result<T, String>;
fn trusted(raw: &str) -> bool {
    reqwest::Url::parse(raw)
        .map(|u| {
            u.scheme() == "https"
                && u.username().is_empty()
                && u.password().is_none()
                && u.port().is_none()
                && matches!(
                    u.host_str(),
                    Some(
                        "api.github.com"
                            | "github.com"
                            | "release-assets.githubusercontent.com"
                            | "objects.githubusercontent.com"
                            | "github-releases.githubusercontent.com"
                            | "github-cloud.s3.amazonaws.com"
                    )
                )
        })
        .unwrap_or(false)
}
fn client(download: bool) -> Result<Client> {
    Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(if download { 300 } else { 30 }))
        .pool_max_idle_per_host(1)
        .user_agent("Luma-Music-Updater")
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 6 || !trusted(attempt.url().as_str()) {
                attempt.error("Untrusted update redirect")
            } else {
                attempt.follow()
            }
        }))
        .build()
        .map_err(|_| "Не удалось создать клиент обновлений".into())
}
fn get(client: &Client, url: &str, binary: bool) -> Result<reqwest::blocking::Response> {
    if !trusted(url) {
        return Err("Недоверенный адрес обновления".into());
    }
    let response = client
        .get(url)
        .header(
            "Accept",
            if binary {
                "application/octet-stream"
            } else {
                "application/vnd.github+json"
            },
        )
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send()
        .map_err(|_| "Не удалось подключиться к GitHub")?;
    if !response.status().is_success() {
        return Err(format!(
            "GitHub вернул HTTP {}. Проверьте сеть или попробуйте позже.",
            response.status().as_u16()
        ));
    }
    Ok(response)
}
fn text(client: &Client, url: &str, binary: bool, limit: usize) -> Result<String> {
    let mut bytes = Vec::new();
    get(client, url, binary)?
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| "Ошибка чтения GitHub")?;
    if bytes.len() > limit {
        return Err("Ответ GitHub слишком большой".into());
    }
    String::from_utf8(bytes).map_err(|_| "Некорректный текст ответа GitHub".into())
}
fn asset_url(asset: &Value) -> Result<String> {
    let id = asset["id"]
        .as_u64()
        .filter(|id| *id > 0)
        .ok_or("Некорректный ID файла GitHub")?;
    Ok(format!("{API}/releases/assets/{id}"))
}
fn version(tag: &str) -> Option<semver::Version> {
    semver::Version::parse(tag.strip_prefix('v').unwrap_or(tag)).ok()
}
pub fn select_release(
    list: &Value,
    current: &str,
    include_test: bool,
    abis: &Value,
) -> Result<Value> {
    let current = version(current).ok_or("Некорректная установленная версия")?;
    let mut best: Option<(semver::Version, Value)> = None;
    for release in list
        .as_array()
        .ok_or("Некорректный каталог выпусков")?
        .iter()
        .take(20)
    {
        if release["draft"].as_bool().unwrap_or(true)
            || (!include_test && release["prerelease"].as_bool().unwrap_or(false))
        {
            continue;
        }
        let tag = release["tag_name"].as_str().unwrap_or("");
        let Some(candidate) = version(tag) else {
            continue;
        };
        if !include_test && !candidate.pre.is_empty() {
            continue;
        }
        if candidate.cmp_precedence(&current) != std::cmp::Ordering::Greater {
            continue;
        }
        if best
            .as_ref()
            .map(|(v, _)| candidate.cmp_precedence(v) != std::cmp::Ordering::Greater)
            .unwrap_or(false)
        {
            continue;
        }
        let Ok(apk) = updates::asset(&release["assets"], abis) else {
            continue;
        };
        let sums: Vec<_> = release["assets"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|a| a["name"] == "SHA256SUMS.txt")
            .collect();
        if sums.len() != 1 {
            continue;
        }
        let value = json!({"tag":tag,"notes":release["body"].as_str().unwrap_or("").chars().take(3000).collect::<String>(),"asset":apk,"sums":sums[0]});
        best = Some((candidate, value));
    }
    Ok(best.map(|(_, v)| v).unwrap_or(Value::Null))
}
pub fn check(argument: &Value) -> Result<Value> {
    let client = client(false)?;
    let body = text(
        &client,
        &format!("{API}/releases?per_page=20"),
        false,
        2 * 1024 * 1024,
    )?;
    let list: Value =
        serde_json::from_str(&body).map_err(|_| "Неизвестный формат каталога GitHub")?;
    select_release(
        &list,
        argument["current"].as_str().unwrap_or(""),
        argument["includeTest"].as_bool().unwrap_or(false),
        &argument["abis"],
    )
}
pub fn download(argument: &Value, mut progress: impl FnMut(i32) -> bool) -> Result<String> {
    let release = &argument["release"];
    let size = release["asset"]["size"].as_u64().ok_or("Нет размера APK")?;
    if !(1..=MAX_APK).contains(&size) {
        return Err("Размер APK вне допустимого диапазона".into());
    }
    let client = client(true)?;
    let expected = updates::checksum(
        &text(&client, &asset_url(&release["sums"])?, true, 128 * 1024)?,
        release["asset"]["name"].as_str().ok_or("Нет имени APK")?,
    )?;
    let root = Path::new(argument["cacheDir"].as_str().ok_or("Нет каталога кэша")?)
        .canonicalize()
        .map_err(|_| "Недоступен каталог кэша")?;
    let folder = root.join("updates");
    fs::create_dir_all(&folder).map_err(|_| "Не удалось создать каталог обновлений")?;
    if !folder
        .canonicalize()
        .map_err(|_| "Некорректный каталог обновлений")?
        .starts_with(&root)
    {
        return Err("Некорректный каталог обновлений".into());
    }
    let partial = folder.join("update.part");
    let ready = folder.join("update.apk");
    if partial.exists() {
        fs::remove_file(&partial).map_err(|_| "Не удалось очистить незавершённый APK")?;
    }
    let result = (|| {
        let mut output = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&partial)
            .map_err(|_| "Не удалось сохранить APK")?;
        let mut response = get(&client, &asset_url(&release["asset"])?, true)?;
        let mut hash = Sha256::new();
        let mut buffer = [0u8; 32768];
        let mut count = 0u64;
        let mut last = -1;
        loop {
            let n = response
                .read(&mut buffer)
                .map_err(|_| "Загрузка APK прервана")?;
            if n == 0 {
                break;
            }
            count += n as u64;
            if count > size || count > MAX_APK {
                return Err("Размер загрузки превышен".into());
            }
            output
                .write_all(&buffer[..n])
                .map_err(|_| "Ошибка записи APK")?;
            hash.update(&buffer[..n]);
            let percent = (count * 100 / size) as i32;
            if percent != last {
                last = percent;
                if !progress(percent) {
                    return Err("Загрузка отменена".into());
                }
            }
        }
        if count != size {
            return Err("APK загружен не полностью".into());
        }
        if format!("{:x}", hash.finalize()) != expected {
            return Err("SHA-256 APK не совпадает".into());
        }
        output.sync_all().map_err(|_| "Не удалось сохранить APK")?;
        drop(output);
        fs::rename(&partial, &ready).map_err(|_| "Не удалось подготовить APK")?;
        Ok(ready.to_string_lossy().into_owned())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&partial);
    }
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    fn release(tag: &str, pre: bool) -> Value {
        json!({"tag_name":tag,"draft":false,"prerelease":pre,"assets":[{"name":"Luma-Music-0.5.0-rc.1-arm64-v8a.apk","size":100,"id":1},{"name":"SHA256SUMS.txt","id":2}]})
    }
    #[test]
    fn selects_semantic_latest_rc_and_ignores_drafts_and_missing_assets() {
        let mut draft = release("v9.0.0", false);
        draft["draft"] = json!(true);
        let list = json!([release("v0.5.0-rc.2",true),release("v0.5.0-rc.10",true),draft,{"tag_name":"v8.0.0","draft":false,"assets":[]}]);
        assert_eq!(
            select_release(&list, "0.5.0-rc.1", true, &json!(["arm64-v8a"])).unwrap()["tag"],
            "v0.5.0-rc.10"
        );
        assert_eq!(
            select_release(&list, "0.5.0-rc.1", false, &json!(["arm64-v8a"])).unwrap(),
            Value::Null
        );
        assert_eq!(
            select_release(
                &json!([release("v0.5.0", false)]),
                "0.5.0-rc.10",
                false,
                &json!(["arm64-v8a"])
            )
            .unwrap()["tag"],
            "v0.5.0"
        );
    }
    #[test]
    fn rejects_unsafe_hosts_and_asset_identifiers() {
        for url in [
            "http://github.com/a",
            "https://github.com.evil.test/a",
            "https://user@api.github.com/a",
            "file:///a",
        ] {
            assert!(!trusted(url));
        }
        assert!(trusted("https://release-assets.githubusercontent.com/a"));
        assert!(asset_url(&json!({"id":-1})).is_err());
    }
    #[test]
    fn stable_channel_rejects_semantic_preview_even_if_github_flag_is_wrong() {
        let list = json!([release("v0.6.0-rc.1", false), release("v0.5.0", false)]);
        assert_eq!(
            select_release(&list, "0.5.0-rc.8", false, &json!(["arm64-v8a"])).unwrap()["tag"],
            "v0.5.0"
        );
        assert_eq!(
            select_release(&list, "0.5.0-rc.8", true, &json!(["arm64-v8a"])).unwrap()["tag"],
            "v0.6.0-rc.1"
        );
    }
}
