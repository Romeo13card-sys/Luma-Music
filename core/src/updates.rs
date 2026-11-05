//! Release metadata policy, independent of networking and Android installation APIs.
use serde_json::Value;
const MAX_APK: u64 = 100 * 1024 * 1024;
fn version(raw: &str) -> Option<[u64; 3]> {
    let raw = raw.strip_prefix('v').unwrap_or(raw);
    let base = raw.split(['-', '+']).next()?;
    let values: Vec<_> = base.split('.').collect();
    if values.len() != 3 {
        return None;
    }
    let mut result = [0; 3];
    for (i, value) in values.iter().enumerate() {
        if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        result[i] = value.parse().ok()?;
    }
    Some(result)
}
pub fn compare(candidate: &str, installed: &str) -> Option<i32> {
    Some(match version(candidate)?.cmp(&version(installed)?) {
        std::cmp::Ordering::Less => -1,
        std::cmp::Ordering::Equal => 0,
        std::cmp::Ordering::Greater => 1,
    })
}
pub fn checksum(text: &str, name: &str) -> Result<String, String> {
    if text.len() > 128 * 1024 {
        return Err("Список контрольных сумм слишком большой".into());
    }
    let mut found = None;
    for line in text.lines() {
        let line = line.trim();
        let Some(offset) = line.find(char::is_whitespace) else {
            continue;
        };
        let (hash, path) = line.split_at(offset);
        let path = path
            .trim()
            .trim_start_matches('*')
            .strip_prefix("./")
            .unwrap_or(path.trim().trim_start_matches('*'));
        if path == name {
            if found.is_some() || hash.len() != 64 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
                return Err("Отсутствует однозначная SHA-256 для APK".into());
            }
            found = Some(hash.to_ascii_lowercase());
        }
    }
    found.ok_or_else(|| "Отсутствует однозначная SHA-256 для APK".into())
}
pub fn asset(assets: &Value, abis: &Value) -> Result<Value, String> {
    let entries = assets.as_array().ok_or("Нет списка APK")?;
    if entries.len() > 200 {
        return Err("Слишком много файлов в выпуске".into());
    }
    let supported = ["armeabi-v7a", "arm64-v8a", "x86", "x86_64", "universal"];
    let order: Vec<_> = abis
        .as_array()
        .ok_or("Нет списка архитектур")?
        .iter()
        .filter_map(Value::as_str)
        .chain(std::iter::once("universal"))
        .collect();
    for abi in order {
        if !supported.contains(&abi) {
            continue;
        }
        let suffix = format!("-{abi}.apk");
        let matches: Vec<_> = entries
            .iter()
            .filter(|v| {
                v["name"]
                    .as_str()
                    .and_then(|n| n.strip_prefix("Luma-Music-"))
                    .and_then(|n| n.strip_suffix(&suffix))
                    .map(|v| {
                        !v.is_empty()
                            && v.bytes()
                                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
                    })
                    .unwrap_or(false)
            })
            .collect();
        if matches.len() == 1 {
            let value = matches[0];
            if !(1..=MAX_APK).contains(&value["size"].as_u64().unwrap_or(0)) {
                return Err("Размер APK вне допустимого диапазона".into());
            }
            return Ok(value.clone());
        }
    }
    Err("В выпуске нет APK для архитектуры устройства".into())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn compares_numeric_versions_and_rejects_malformed_versions() {
        assert_eq!(compare("v0.10.0-test.1", "0.4.0-test"), Some(1));
        assert_eq!(compare("v0.3.1-test.1", "0.4.0-test"), Some(-1));
        assert_eq!(compare("v0.4.0-test.1", "0.4.0-test"), Some(0));
        for raw in [
            "other",
            "1.2",
            "1.2.3.4",
            "1.2.bad",
            "99999999999999999999999999.1.1",
        ] {
            assert_eq!(compare(raw, "1.2.3"), None);
        }
    }
    #[test]
    fn checksum_requires_exact_unique_filename_and_valid_hash() {
        let hash = "a".repeat(64);
        assert_eq!(
            checksum(&format!("{hash}  ./test.apk\n"), "test.apk").unwrap(),
            hash
        );
        for text in [
            format!("{hash}  other.apk"),
            "bad test.apk".into(),
            format!("{hash}  test.apk\n{hash}  test.apk"),
        ] {
            assert!(checksum(&text, "test.apk").is_err());
        }
    }
    #[test]
    fn picks_abi_and_rejects_huge_files() {
        let assets = json!([{"name":"Luma-Music-0.4.2-test-universal.apk","size":10},{"name":"Luma-Music-0.4.2-test-arm64-v8a.apk","size":20}]);
        assert_eq!(
            asset(&assets, &json!(["arm64-v8a", "armeabi-v7a"])).unwrap()["size"],
            20
        );
        assert!(asset(
            &json!([{"name":"Luma-Music-0.4.2-test-x86.apk","size":MAX_APK+1}]),
            &json!(["x86"])
        )
        .is_err());
    }
}
