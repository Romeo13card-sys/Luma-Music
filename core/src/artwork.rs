//! Public Yandex artwork: bounded transfers/cache, no account token or UI dependencies.
use crate::{json, Result, Value};
use reqwest::blocking::Client;
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::Duration,
};

const MAX_IMAGE: u64 = 1024 * 1024;
const MAX_CACHE: u64 = 24 * 1024 * 1024;
const MAX_FILES: usize = 128;
static WRITER: Mutex<()> = Mutex::new(());
static HTTP: OnceLock<Client> = OnceLock::new();

/// Keep only public music CDN paths; never persist arbitrary remote/private URLs.
pub fn normalize(raw: &str) -> Option<String> {
    if raw.len() > 2048 || raw.bytes().any(|b| b.is_ascii_whitespace() || b < 32) {
        return None;
    }
    let raw = if raw.starts_with("https://") {
        raw.to_owned()
    } else if raw.starts_with("//") {
        format!("https:{raw}")
    } else {
        format!("https://{raw}")
    };
    let u = reqwest::Url::parse(&raw).ok()?;
    if u.scheme() != "https"
        || !matches!(
            u.host_str(),
            Some("avatars.yandex.net" | "avatars.mds.yandex.net")
        )
        || !u.username().is_empty()
        || u.password().is_some()
        || u.port().is_some()
        || u.query().is_some()
        || u.fragment().is_some()
    {
        return None;
    }
    let path = u.path();
    if !path.starts_with("/get-music-content/")
        || path.contains("..")
        || !path
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"/_-.%".contains(&b))
    {
        return None;
    }
    let (base, size) = path.rsplit_once('/')?;
    let known_size = matches!(size, "%%" | "orig")
        || size.split_once('x').is_some_and(|(w, h)| {
            [w, h].iter().all(|n| {
                !n.is_empty()
                    && n.bytes().all(|b| b.is_ascii_digit())
                    && n.parse::<u32>().is_ok_and(|n| (1..=4096).contains(&n))
            })
        });
    if base.len() <= "/get-music-content/".len() || !known_size {
        return None;
    }
    Some(format!("https://{}{base}/%%", u.host_str()?))
}
fn identity(v: &Value) -> Result<(String, String)> {
    let cover = normalize(v["cover"].as_str().unwrap_or("")).ok_or("Нет доступной обложки")?;
    let size = v["size"]
        .as_u64()
        .filter(|n| matches!(n, 100 | 400))
        .ok_or("Некорректный размер обложки")?;
    let url = cover.replace("%%", &format!("{size}x{size}"));
    let name = format!("{:x}.img", Sha256::digest(url.as_bytes()));
    Ok((url, name))
}
fn directory(v: &Value) -> Result<PathBuf> {
    let path = PathBuf::from(v["root"].as_str().ok_or("Нет каталога обложек")?);
    if !path.is_absolute() || fs::symlink_metadata(&path).is_ok_and(|m| m.file_type().is_symlink())
    {
        return Err("Некорректный каталог обложек".into());
    }
    fs::create_dir_all(&path).map_err(|_| "Не удалось открыть кэш обложек")?;
    Ok(path)
}
fn image_header(b: &[u8]) -> bool {
    b.starts_with(&[0xff, 0xd8, 0xff])
        || b.starts_with(b"\x89PNG\r\n\x1a\n")
        || (b.starts_with(b"RIFF") && b.get(8..12) == Some(b"WEBP"))
}
fn read_image(input: impl Read, expected: Option<u64>) -> Result<Vec<u8>> {
    if expected.is_some_and(|n| n == 0 || n > MAX_IMAGE) {
        return Err("Обложка слишком большая".into());
    }
    let mut bytes = Vec::new();
    input
        .take(MAX_IMAGE + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Не удалось прочитать обложку")?;
    if bytes.len() as u64 > MAX_IMAGE
        || expected.is_some_and(|n| n != bytes.len() as u64)
        || !image_header(&bytes)
    {
        return Err("Некорректное изображение обложки".into());
    }
    Ok(bytes)
}
fn valid_file(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok_and(|m| m.is_file() && m.len() > 0 && m.len() <= MAX_IMAGE)
        && fs::File::open(path).is_ok_and(|mut f| {
            let mut b = [0; 12];
            f.read(&mut b).is_ok_and(|n| image_header(&b[..n]))
        })
}
fn files(dir: &Path) -> Vec<(PathBuf, u64, std::time::SystemTime)> {
    fs::read_dir(dir)
        .into_iter()
        .flatten()
        .filter_map(|e| e.ok())
        .filter_map(|e| {
            let name = e.file_name();
            let name = name.to_str()?;
            if name.len() != 68
                || !name.ends_with(".img")
                || !name[..64].bytes().all(|b| b.is_ascii_hexdigit())
            {
                return None;
            }
            let m = e.metadata().ok()?;
            if !e.file_type().ok()?.is_file() {
                return None;
            }
            Some((
                e.path(),
                m.len(),
                m.modified().unwrap_or(std::time::UNIX_EPOCH),
            ))
        })
        .collect()
}
fn prune(dir: &Path, incoming: u64) -> Result<()> {
    let mut items = files(dir);
    items.sort_by_key(|i| i.2);
    let mut bytes: u64 = items.iter().map(|i| i.1).sum();
    let mut count = items.len();
    for (path, size, _) in items {
        if bytes + incoming <= MAX_CACHE && count < MAX_FILES {
            break;
        }
        fs::remove_file(path).map_err(|_| "Не удалось ограничить кэш обложек")?;
        bytes = bytes.saturating_sub(size);
        count -= 1;
    }
    Ok(())
}
fn http() -> Result<&'static Client> {
    if let Some(client) = HTTP.get() {
        return Ok(client);
    }
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(8))
        .pool_max_idle_per_host(1)
        .user_agent("Luma-Music-Artwork")
        .redirect(reqwest::redirect::Policy::custom(|a| {
            if a.previous().len() < 3 && normalize(a.url().as_str()).is_some() {
                a.follow()
            } else {
                a.error("Untrusted artwork redirect")
            }
        }))
        .build()
        .map_err(|_| "Не удалось создать загрузчик обложек")?;
    let _ = HTTP.set(client);
    HTTP.get()
        .ok_or_else(|| "Не удалось создать загрузчик обложек".into())
}
pub fn dispatch(operation: &str, v: &Value) -> Result<Value> {
    if operation == "cover_info" {
        let (url, name) = identity(v)?;
        return Ok(json!({"url":url,"file":name}));
    }
    let _guard = WRITER.lock().map_err(|_| "Кэш обложек занят")?;
    let dir = directory(v)?;
    // A killed process can leave its staging file; no other writer runs under this lock.
    for entry in fs::read_dir(&dir)
        .into_iter()
        .flatten()
        .filter_map(|e| e.ok())
    {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.len() == 69
            && name.ends_with(".part")
            && name[..64].bytes().all(|b| b.is_ascii_hexdigit())
        {
            let _ = fs::remove_file(entry.path());
        }
    }
    if operation == "cover_clear" {
        for (p, _, _) in files(&dir) {
            fs::remove_file(p).map_err(|_| "Не удалось очистить обложки")?;
        }
        return Ok(json!(true));
    }
    let (url, name) = identity(v)?;
    let path = dir.join(name);
    if operation == "cover_forget" {
        let _ = fs::remove_file(path);
        return Ok(json!(true));
    }
    if valid_file(&path) {
        return Ok(json!(path));
    }
    let _ = fs::remove_file(&path);
    if v["network"].as_bool() == Some(false) {
        return Ok(Value::Null);
    }
    let response = http()?
        .get(url)
        .header("Accept", "image/jpeg,image/png,image/webp")
        .send()
        .map_err(|_| "Обложка недоступна в этой сети")?;
    if !response.status().is_success() {
        return Err("Сервис не предоставил обложку".into());
    }
    let length = response.content_length();
    let bytes = read_image(response, length)?;
    prune(&dir, bytes.len() as u64)?;
    let part = path.with_extension("part");
    let result = (|| {
        let mut out = fs::File::create(&part).map_err(|_| "Не удалось сохранить обложку")?;
        out.write_all(&bytes)
            .map_err(|_| "Не удалось сохранить обложку")?;
        drop(out);
        fs::rename(&part, &path).map_err(|_| "Не удалось завершить сохранение обложки")?;
        Ok(json!(path))
    })();
    if result.is_err() {
        let _ = fs::remove_file(part);
    }
    result
}

fn luminance(c: [f64; 3]) -> f64 {
    let linear = c.map(|n| {
        if n <= 0.04045 {
            n / 12.92
        } else {
            ((n + 0.055) / 1.055).powf(2.4)
        }
    });
    linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
}
fn color(c: [f64; 3]) -> u32 {
    0xff000000
        | ((c[0] * 255.0).round() as u32) << 16
        | ((c[1] * 255.0).round() as u32) << 8
        | (c[2] * 255.0).round() as u32
}
/// 64 platform-decoded pixels are enough; no full bitmap crosses JNI.
pub fn tones(v: &Value) -> Result<Value> {
    let pixels = v["pixels"]
        .as_array()
        .filter(|a| !a.is_empty() && a.len() <= 64)
        .ok_or("Некорректные цвета обложки")?;
    let mut sum = [0.0; 3];
    let mut weights = 0.0;
    for p in pixels {
        let c = p.as_i64().ok_or("Некорректный цвет")? as u32;
        if c >> 24 < 128 {
            continue;
        }
        let rgb = [
            ((c >> 16) & 255) as f64 / 255.0,
            ((c >> 8) & 255) as f64 / 255.0,
            (c & 255) as f64 / 255.0,
        ];
        let max = rgb.iter().copied().fold(0.0, f64::max);
        let min = rgb.iter().copied().fold(1.0, f64::min);
        let weight = 1.0 + (max - min) * 3.0;
        for i in 0..3 {
            sum[i] += rgb[i] * weight;
        }
        weights += weight;
    }
    let base = if weights == 0.0 {
        [1.0, 0.88, 0.29]
    } else {
        sum.map(|n| n / weights)
    };
    let mut result = json!({});
    for (name, bg) in [
        ("light", [250.0 / 255.0; 3]),
        ("dark", [18.0 / 255.0; 3]),
        ("oled", [0.0; 3]),
    ] {
        let surface = if name == "oled" {
            bg
        } else {
            std::array::from_fn(|i| bg[i] * 0.88 + base[i] * 0.12)
        };
        let mut accent = base;
        for _ in 0..40 {
            let a = luminance(accent);
            let b = luminance(surface);
            if (a.max(b) + 0.05) / (a.min(b) + 0.05) >= 3.1 {
                break;
            }
            accent = accent.map(|n| {
                if name == "light" {
                    n * 0.85
                } else {
                    n * 0.85 + 0.15
                }
            });
        }
        let l = luminance(accent);
        result[name] = json!({"surface":color(surface),"accent":color(accent),"onAccent":if (l+0.05)/0.05 >= 1.05/(l+0.05) {0xff000000u32} else {0xffffffffu32}});
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    const COVER: &str = "avatars.yandex.net/get-music-content/42/album/%%";
    #[test]
    fn accepts_yandex_templates_and_rejects_other_destinations() {
        assert_eq!(normalize(COVER).unwrap(), format!("https://{COVER}"));
        assert!(
            normalize("https://avatars.mds.yandex.net/get-music-content/42/a/400x400")
                .unwrap()
                .ends_with("/%%")
        );
        for bad in [
            "http://avatars.yandex.net/get-music-content/42/a/%%",
            "https://avatars.yandex.net.evil.test/get-music-content/a/%%",
            "https://user@avatars.yandex.net/get-music-content/a/%%",
            "https://avatars.yandex.net/other/%%",
            "https://avatars.yandex.net/get-music-content/a/%%?token=secret",
            "https://127.0.0.1/get-music-content/a/%%",
        ] {
            assert!(normalize(bad).is_none(), "{bad}");
        }
        assert!(identity(&json!({"cover":COVER,"size":4000})).is_err());
    }
    #[test]
    fn rejects_html_truncation_and_oversized_images() {
        assert!(read_image(&b"<html>error</html>"[..], None).is_err());
        assert!(read_image(&b"\xff\xd8\xffimage"[..], Some(500)).is_err());
        assert!(read_image(std::io::repeat(0xff), None).is_err());
        assert!(read_image(&b"\xff\xd8\xffimage"[..], Some(MAX_IMAGE + 1)).is_err());
    }
    #[test]
    fn disk_cache_works_without_network_and_evicts_old_entries() {
        let dir = std::env::temp_dir().join(format!("luma-cover-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let v = json!({"root":dir,"cover":COVER,"size":100,"network":false});
        assert!(dispatch("cover_load", &v).unwrap().is_null());
        let (_, name) = identity(&v).unwrap();
        let file = dir.join(name);
        fs::write(&file, b"\xff\xd8\xfffixture-123").unwrap();
        assert_eq!(dispatch("cover_load", &v).unwrap(), json!(file));
        for n in 0..MAX_FILES {
            fs::write(dir.join(format!("{n:064x}.img")), b"\xff\xd8\xfftest").unwrap();
        }
        prune(&dir, MAX_IMAGE).unwrap();
        assert!(files(&dir).len() < MAX_FILES);
        dispatch("cover_clear", &v).unwrap();
        assert!(files(&dir).is_empty());
        let _ = fs::remove_dir_all(dir);
    }
    #[test]
    fn tones_have_contrast_and_keep_oled_black() {
        for rgb in [0xff000000u32, 0xffffffff, 0xff44cc88, 0xfff0d090] {
            let v = tones(&json!({"pixels":[rgb]})).unwrap();
            assert_eq!(v["oled"]["surface"], 0xff000000u32);
            for name in ["light", "dark", "oled"] {
                let unpack = |n: u64| {
                    [
                        ((n >> 16) & 255) as f64 / 255.0,
                        ((n >> 8) & 255) as f64 / 255.0,
                        (n & 255) as f64 / 255.0,
                    ]
                };
                let a = luminance(unpack(v[name]["accent"].as_u64().unwrap()));
                let b = luminance(unpack(v[name]["surface"].as_u64().unwrap()));
                assert!((a.max(b) + 0.05) / (a.min(b) + 0.05) >= 3.0);
                let t = luminance(unpack(v[name]["onAccent"].as_u64().unwrap()));
                assert!((a.max(t) + 0.05) / (a.min(t) + 0.05) >= 4.5);
            }
        }
        assert!(tones(&json!({"pixels":vec![0;65]})).is_err());
    }
}
