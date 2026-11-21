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
    updates::semantic_version(tag).ok()
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
fn content_range_matches(raw: &str, start: u64, size: u64) -> bool {
    let Some(value) = raw.strip_prefix("bytes ") else {
        return false;
    };
    let Some((range, total)) = value.split_once('/') else {
        return false;
    };
    let Some((first, last)) = range.split_once('-') else {
        return false;
    };
    let parsed = (
        first.parse::<u64>(),
        last.parse::<u64>(),
        total.parse::<u64>(),
    );
    matches!(
        parsed,
        (Ok(first), Ok(last), Ok(total))
            if first == start && last >= first && total == size && last < total
    )
}

/// Validate a server response before opening the partial file for append.
/// Returns the byte offset that must be used for the output file.
fn validated_response_offset(
    status: reqwest::StatusCode,
    content_range: Option<&str>,
    offset: u64,
    size: u64,
) -> Result<u64> {
    if status == reqwest::StatusCode::PARTIAL_CONTENT {
        if content_range.is_some_and(|value| content_range_matches(value, offset, size)) {
            Ok(offset)
        } else {
            Err("GitHub вернул некорректный диапазон APK. Частичная загрузка сохранена.".into())
        }
    } else if status == reqwest::StatusCode::OK {
        // A server or mirror may ignore Range; restart instead of appending a full body.
        Ok(0)
    } else {
        Err(format!("GitHub вернул HTTP {} при загрузке APK", status.as_u16()))
    }
}

fn hash_file(path: &Path) -> Result<(u64, String)> {
    let mut file = fs::File::open(path).map_err(|_| "Не удалось прочитать сохранённый APK")?;
    let mut hash = Sha256::new();
    let mut bytes = 0u64;
    let mut buffer = [0u8; 32768];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| "Не удалось проверить сохранённый APK")?;
        if count == 0 {
            break;
        }
        bytes = bytes.saturating_add(count as u64);
        hash.update(&buffer[..count]);
    }
    Ok((bytes, format!("{:x}", hash.finalize())))
}

pub fn download(argument: &Value, mut progress: impl FnMut(i32) -> bool) -> Result<String> {
    let release = &argument["release"];
    let size = release["asset"]["size"].as_u64().ok_or("Нет размера APK")?;
    if !(1..=MAX_APK).contains(&size) {
        return Err("Размер APK вне допустимого диапазона".into());
    }
    let asset_id = release["asset"]["id"]
        .as_u64()
        .filter(|id| *id > 0)
        .ok_or("Некорректный ID файла GitHub")?;
    let name = release["asset"]["name"]
        .as_str()
        .filter(|name| !name.is_empty())
        .ok_or("Нет имени APK")?;
    let tag = release["tag"]
        .as_str()
        .filter(|tag| !tag.is_empty())
        .ok_or("Нет версии выпуска")?;
    let client = client(true)?;
    let expected = updates::checksum(
        &text(&client, &asset_url(&release["sums"])?, true, 128 * 1024)?,
        name,
    )?;

    // filesDir is app-private persistent storage. cacheDir is accepted only for old callers.
    let root_path = argument["filesDir"]
        .as_str()
        .or_else(|| argument["cacheDir"].as_str())
        .ok_or("Нет постоянного каталога приложения")?;
    let root = Path::new(root_path)
        .canonicalize()
        .map_err(|_| "Недоступен каталог обновлений")?;
    let folder = root.join("updates");
    fs::create_dir_all(&folder).map_err(|_| "Не удалось создать каталог обновлений")?;
    let canonical_folder = folder
        .canonicalize()
        .map_err(|_| "Некорректный каталог обновлений")?;
    if !canonical_folder.starts_with(&root) {
        return Err("Некорректный каталог обновлений".into());
    }

    let partial = folder.join("update.part");
    let ready = folder.join("update.apk");
    let metadata = folder.join("update.json");
    let identity = json!({
        "tag": tag,
        "assetId": asset_id,
        "name": name,
        "size": size,
        "sha256": expected
    });

    // A partial APK may only be resumed for the exact same immutable release asset.
    let saved_identity = fs::read_to_string(&metadata)
        .ok()
        .and_then(|raw| serde_json::from_str::<Value>(&raw).ok());
    if saved_identity.as_ref() != Some(&identity) {
        let _ = fs::remove_file(&partial);
        let _ = fs::remove_file(&ready);
        let temporary = folder.join("update.json.tmp");
        fs::write(&temporary, identity.to_string())
            .map_err(|_| "Не удалось сохранить сведения об обновлении")?;
        fs::rename(&temporary, &metadata).map_err(|_| "Не удалось обновить сведения о загрузке")?;
    }

    // A completed file survives process death and is reused only after a fresh hash check.
    if ready.is_file() {
        match hash_file(&ready) {
            Ok((actual_size, actual_hash)) if actual_size == size && actual_hash == expected => {
                progress(100);
                return Ok(ready.to_string_lossy().into_owned());
            }
            _ => {
                fs::remove_file(&ready).map_err(|_| "Не удалось заменить старый APK")?;
            }
        }
    }

    let mut offset = fs::metadata(&partial).map(|m| m.len()).unwrap_or(0);
    if offset > size {
        fs::remove_file(&partial).map_err(|_| "Не удалось сбросить повреждённую загрузку")?;
        offset = 0;
    }
    if offset == size {
        let (actual_size, actual_hash) = hash_file(&partial)?;
        if actual_size == size && actual_hash == expected {
            fs::rename(&partial, &ready).map_err(|_| "Не удалось подготовить APK")?;
            progress(100);
            return Ok(ready.to_string_lossy().into_owned());
        }
        fs::remove_file(&partial).map_err(|_| "Не удалось удалить повреждённый APK")?;
        offset = 0;
    }

    let url = asset_url(&release["asset"])?;
    let mut request = client
        .get(&url)
        .header("Accept", "application/octet-stream")
        .header("X-GitHub-Api-Version", "2022-11-28");
    if offset > 0 {
        request = request.header(reqwest::header::RANGE, format!("bytes={offset}-"));
    }
    let mut response = request
        .send()
        .map_err(|_| "Не удалось подключиться к GitHub. Частичная загрузка сохранена.")?;
    offset = validated_response_offset(
        response.status(),
        response
            .headers()
            .get(reqwest::header::CONTENT_RANGE)
            .and_then(|value| value.to_str().ok()),
        offset,
        size,
    )?;

    let mut options = fs::OpenOptions::new();
    options.create(true).write(true);
    if offset > 0 {
        options.append(true);
    } else {
        options.truncate(true);
    }
    let mut output = options
        .open(&partial)
        .map_err(|_| "Не удалось сохранить APK")?;

    let mut hash = Sha256::new();
    if offset > 0 {
        let mut existing =
            fs::File::open(&partial).map_err(|_| "Не удалось продолжить загрузку APK")?;
        let mut buffer = [0u8; 32768];
        let mut read = 0u64;
        loop {
            let count = existing
                .read(&mut buffer)
                .map_err(|_| "Не удалось проверить частичную загрузку")?;
            if count == 0 {
                break;
            }
            read += count as u64;
            hash.update(&buffer[..count]);
        }
        if read != offset {
            return Err("Размер частичной загрузки изменился".into());
        }
    }

    let mut buffer = [0u8; 32768];
    let mut count = offset;
    let mut last = -1;
    let initial_percent = (count * 100 / size) as i32;
    if initial_percent != last {
        last = initial_percent;
        if !progress(initial_percent) {
            return Err("Загрузка отменена. Её можно продолжить позже.".into());
        }
    }
    let mut synced = count;
    loop {
        let read = response
            .read(&mut buffer)
            .map_err(|_| "Загрузка APK прервана. Частичная загрузка сохранена для продолжения.")?;
        if read == 0 {
            break;
        }
        count = count.saturating_add(read as u64);
        if count > size || count > MAX_APK {
            let _ = fs::remove_file(&partial);
            return Err("Размер загрузки превышен".into());
        }
        output
            .write_all(&buffer[..read])
            .map_err(|_| "Ошибка записи APK. Частичная загрузка сохранена.")?;
        hash.update(&buffer[..read]);
        if count.saturating_sub(synced) >= 1024 * 1024 {
            output
                .sync_data()
                .map_err(|_| "Не удалось сохранить частичную загрузку")?;
            synced = count;
        }
        let percent = (count * 100 / size) as i32;
        if percent != last {
            last = percent;
            if !progress(percent) {
                output
                    .sync_all()
                    .map_err(|_| "Не удалось сохранить частичную загрузку")?;
                return Err("Загрузка отменена. Её можно продолжить позже.".into());
            }
        }
    }
    if count != size {
        output
            .sync_all()
            .map_err(|_| "Не удалось сохранить частичную загрузку")?;
        return Err("APK загружен не полностью. Повторите попытку, загрузка продолжится.".into());
    }
    if format!("{:x}", hash.finalize()) != expected {
        drop(output);
        let _ = fs::remove_file(&partial);
        return Err("SHA-256 APK не совпадает. Повреждённая загрузка удалена.".into());
    }
    output.sync_all().map_err(|_| "Не удалось сохранить APK")?;
    drop(output);
    if ready.exists() {
        fs::remove_file(&ready).map_err(|_| "Не удалось заменить предыдущий APK")?;
    }
    fs::rename(&partial, &ready).map_err(|_| "Не удалось подготовить APK")?;
    progress(100);
    Ok(ready.to_string_lossy().into_owned())
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
    fn canonical_release_tag_upgrades_dev_and_short_display_does_not_update_itself() {
        let list = json!([release("v1.0.0", false)]);
        assert_eq!(
            select_release(&list, "1.0.0-dev.1", false, &json!(["arm64-v8a"])).unwrap()["tag"],
            "v1.0.0"
        );
        assert_eq!(
            select_release(&list, "1.0", false, &json!(["arm64-v8a"])).unwrap(),
            Value::Null
        );
        assert_eq!(
            select_release(
                &json!([release("v1.0.1", false)]),
                "1.0",
                false,
                &json!(["arm64-v8a"])
            )
            .unwrap()["tag"],
            "v1.0.1"
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
    fn validates_content_range_before_appending_resumed_bytes() {
        assert!(content_range_matches("bytes 100-199/200", 100, 200));
        assert!(!content_range_matches("bytes 99-199/200", 100, 200));
        assert!(!content_range_matches("bytes 100-200/200", 100, 200));
        assert!(!content_range_matches("bytes 100-199/201", 100, 200));
        assert!(!content_range_matches("items 100-199/200", 100, 200));
    }

    #[test]
    fn response_status_controls_resume_or_safe_restart() {
        use reqwest::StatusCode;
        assert_eq!(
            validated_response_offset(StatusCode::PARTIAL_CONTENT, Some("bytes 100-199/200"), 100, 200).unwrap(),
            100
        );
        assert_eq!(
            validated_response_offset(StatusCode::OK, None, 100, 200).unwrap(),
            0
        );
        assert!(validated_response_offset(StatusCode::PARTIAL_CONTENT, Some("bytes 99-199/200"), 100, 200).is_err());
        assert!(validated_response_offset(StatusCode::PARTIAL_CONTENT, None, 100, 200).is_err());
        assert!(validated_response_offset(StatusCode::RANGE_NOT_SATISFIABLE, None, 100, 200).is_err());
    }

    #[test]
    fn rejects_zero_and_inconsistent_content_ranges() {
        for value in [
            "bytes 100-99/200",
            "bytes 100-199/*",
            "bytes 100-199/0",
            "bytes +100-199/200",
            "bytes 100-199/200 extra",
            "bytes 100-199",
        ] {
            assert!(!content_range_matches(value, 100, 200), "{value}");
        }
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
