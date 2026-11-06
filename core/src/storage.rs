//! Luma's bounded SQLite adapter, following YAYMA's Rust-owned persistence model.
//! Auth secrets remain encrypted through Android Keystore; they never enter this database.
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Map, Value};
use std::{path::Path, time::Duration};
type Result<T> = std::result::Result<T, String>;

fn valid_setting(key: &str, value: &Value) -> bool {
    match key {
        "language" => value
            .as_str()
            .is_some_and(|s| s == "system" || crate::localization::supported(s)),
        "theme_mode" => matches!(value.as_str(), Some("system" | "dark" | "light" | "oled")),
        "quality_wifi" | "quality_mobile" => matches!(value.as_i64(), Some(128 | 192 | 320)),
        "reduce_effects" | "unmetered_only" | "haptics" | "shuffle" | "repeat" | "test_updates"
        | "auto_update_check" => value.is_boolean(),
        "font_size" => matches!(value.as_i64(), Some(90 | 100 | 115 | 130)),
        "pause_on_unplug" => value.is_boolean(),
        "last_update_check" => value.as_i64().is_some_and(|n| n >= 0),
        _ => false,
    }
}
pub fn defaults() -> Value {
    json!({"language":"system","theme_mode":"system","quality_wifi":320,"quality_mobile":128,
        "reduce_effects":false,"unmetered_only":false,"haptics":true,"shuffle":false,
        "repeat":false,"test_updates":false,"auto_update_check":true,"last_update_check":0,
        "font_size":100,"pause_on_unplug":true})
}
fn open(path: &Path) -> Result<Connection> {
    let db = Connection::open(path).map_err(|_| "Не удалось открыть базу Luma")?;
    db.busy_timeout(Duration::from_secs(3))
        .map_err(|_| "База Luma занята")?;
    let version: i64 = db
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(|_| "Ошибка версии базы")?;
    if version > 1 {
        return Err("База создана более новой версией Luma".into());
    }
    db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA cache_size=-512; PRAGMA mmap_size=0;
        CREATE TABLE IF NOT EXISTS app_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS playback_state(id INTEGER PRIMARY KEY CHECK(id=1), snapshot TEXT NOT NULL);
        PRAGMA user_version=1;").map_err(|_| "Не удалось подготовить базу Luma")?;
    Ok(db)
}
fn settings(db: &Connection) -> Result<Value> {
    let mut query = db
        .prepare("SELECT key,value FROM app_settings LIMIT 32")
        .map_err(|_| "Ошибка чтения настроек")?;
    let rows = query
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .map_err(|_| "Ошибка чтения настроек")?;
    let mut result = Map::new();
    for row in rows {
        let (key, raw) = row.map_err(|_| "Ошибка чтения настроек")?;
        if let Ok(v) = serde_json::from_str::<Value>(&raw) {
            if valid_setting(&key, &v) {
                result.insert(key, v);
            }
        }
    }
    Ok(Value::Object(result))
}
pub fn dispatch(path: &str, operation: &str, raw: &str) -> Result<Value> {
    if raw.len() > crate::queue::MAX_BYTES {
        return Err("Запрос базы слишком большой".into());
    }
    let mut db = open(Path::new(path))?;
    match operation {
        "settings_load" => settings(&db),
        "settings_reset" => {
            let values = defaults();
            let tx = db
                .transaction()
                .map_err(|_| "Не удалось сбросить настройки")?;
            tx.execute("DELETE FROM app_settings", [])
                .map_err(|_| "Не удалось сбросить настройки")?;
            for (key, value) in values.as_object().unwrap() {
                tx.execute(
                    "INSERT INTO app_settings(key,value) VALUES(?1,?2)",
                    params![key, value.to_string()],
                )
                .map_err(|_| "Не удалось сбросить настройки")?;
            }
            tx.commit().map_err(|_| "Не удалось сохранить сброс")?;
            Ok(values)
        }
        "settings_import" | "settings_patch" => {
            let input: Value = serde_json::from_str(raw).map_err(|_| "Некорректные настройки")?;
            let values = input
                .as_object()
                .filter(|v| v.len() <= 32)
                .ok_or("Некорректные настройки")?;
            // Import tolerates unrelated legacy keys, but normal writes reject bad data atomically.
            if operation == "settings_patch" && values.iter().any(|(k, v)| !valid_setting(k, v)) {
                return Err("Недопустимые настройки".into());
            }
            let tx = db.transaction().map_err(|_| "Ошибка транзакции настроек")?;
            for (key, value) in values.iter().filter(|(k, v)| valid_setting(k, v)) {
                let sql = if operation == "settings_import" {
                    "INSERT OR IGNORE INTO app_settings(key,value) VALUES(?1,?2)"
                } else {
                    "INSERT INTO app_settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value"
                };
                tx.execute(sql, params![key, value.to_string()])
                    .map_err(|_| "Не удалось сохранить настройки")?;
            }
            tx.commit().map_err(|_| "Не удалось сохранить настройки")?;
            settings(&db)
        }
        "queue_load" => {
            let raw: Option<String> = db
                .query_row("SELECT snapshot FROM playback_state WHERE id=1", [], |r| {
                    r.get(0)
                })
                .optional()
                .map_err(|_| "Ошибка чтения очереди")?;
            raw.map(|s| crate::queue::normalize(&s).map(Value::String))
                .transpose()
                .map(|v| v.unwrap_or(Value::Null))
        }
        "queue_save" | "queue_import" => {
            let normalized = crate::queue::normalize(raw)?;
            let sql = if operation == "queue_import" {
                "INSERT OR IGNORE INTO playback_state(id,snapshot) VALUES(1,?1)"
            } else {
                "INSERT INTO playback_state(id,snapshot) VALUES(1,?1) ON CONFLICT(id) DO UPDATE SET snapshot=excluded.snapshot"
            };
            db.execute(sql, [normalized])
                .map_err(|_| "Не удалось сохранить очередь")?;
            Ok(json!(true))
        }
        // A saved empty queue also marks an intentional clear, preventing a legacy import resurrection.
        "queue_clear" => {
            db.execute("INSERT INTO playback_state(id,snapshot) VALUES(1,?1) ON CONFLICT(id) DO UPDATE SET snapshot=excluded.snapshot",[r#"{"songs":[],"index":0,"position":0}"#]).map_err(|_| "Не удалось очистить очередь")?;
            Ok(json!(true))
        }
        _ => Err("Неизвестная операция базы".into()),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Temp(std::path::PathBuf);
    impl Temp {
        fn new() -> Self {
            let p = std::env::temp_dir().join(format!(
                "luma-db-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&p).unwrap();
            Self(p)
        }
        fn path(&self) -> String {
            self.0.join("luma.db").to_string_lossy().into_owned()
        }
    }
    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    #[test]
    fn migration_is_idempotent_and_rejects_secrets() {
        let tmp = Temp::new();
        let p = tmp.path();
        dispatch(
            &p,
            "settings_import",
            r#"{"theme_mode":"oled","token":"private","quality_mobile":128}"#,
        )
        .unwrap();
        dispatch(&p, "settings_patch", r#"{"theme_mode":"light"}"#).unwrap();
        let v = dispatch(&p, "settings_import", r#"{"theme_mode":"dark"}"#).unwrap();
        assert_eq!(v["theme_mode"], "light");
        assert!(v.get("token").is_none());
        assert!(dispatch(
            &p,
            "settings_patch",
            r#"{"theme_mode":"dark","quality_mobile":999}"#
        )
        .is_err());
        assert_eq!(
            dispatch(&p, "settings_load", "{}").unwrap()["theme_mode"],
            "light"
        );
    }
    #[test]
    fn queue_survives_reopen_and_clear_cannot_resurrect_legacy_data() {
        let tmp = Temp::new();
        let p = tmp.path();
        let raw = r#"{"songs":[{"id":"42","title":"Song","artists":"A","available":true,"token":"private"}],"position":1200,"index":0}"#;
        dispatch(&p, "queue_import", raw).unwrap();
        let value = dispatch(&p, "queue_load", "{}").unwrap();
        assert!(!value.as_str().unwrap().contains("private"));
        assert!(value.as_str().unwrap().contains("1200"));
        dispatch(&p, "queue_clear", "{}").unwrap();
        dispatch(&p, "queue_import", raw).unwrap();
        let v: Value =
            serde_json::from_str(dispatch(&p, "queue_load", "{}").unwrap().as_str().unwrap())
                .unwrap();
        assert!(v["songs"].as_array().unwrap().is_empty());
    }
    #[test]
    fn language_survives_reopen_and_invalid_patch_is_atomic() {
        let tmp = Temp::new();
        let path = tmp.path();
        dispatch(
            &path,
            "settings_patch",
            r#"{"language":"ar","theme_mode":"oled"}"#,
        )
        .unwrap();
        assert_eq!(
            dispatch(&path, "settings_load", "{}").unwrap()["language"],
            "ar"
        );
        assert!(dispatch(
            &path,
            "settings_patch",
            r#"{"language":"../../secret","theme_mode":"light"}"#
        )
        .is_err());
        assert_eq!(
            dispatch(&path, "settings_load", "{}").unwrap()["theme_mode"],
            "oled"
        );
        assert_eq!(
            dispatch(&path, "settings_reset", "{}").unwrap()["language"],
            "system"
        );
    }
    #[test]
    fn newer_database_is_never_downgraded() {
        let tmp = Temp::new();
        let p = tmp.path();
        let db = Connection::open(&p).unwrap();
        db.pragma_update(None, "user_version", 42).unwrap();
        drop(db);
        assert!(dispatch(&p, "settings_load", "{}").is_err());
        assert_eq!(
            Connection::open(&p)
                .unwrap()
                .pragma_query_value(None, "user_version", |r| r.get::<_, i64>(0))
                .unwrap(),
            42
        );
    }
    #[test]
    fn stable_is_default_but_existing_preview_choice_survives_reopen() {
        let tmp = Temp::new();
        let path = tmp.path();
        assert_eq!(
            dispatch(&path, "settings_load", "{}").unwrap()["test_updates"],
            false
        );
        dispatch(&path, "settings_patch", r#"{"test_updates":true}"#).unwrap();
        assert_eq!(
            dispatch(&path, "settings_load", "{}").unwrap()["test_updates"],
            true
        );
    }
}

#[cfg(test)]
mod reset_tests {
    #[test]
    fn reset_preserves_queue_and_cannot_reimport_old_settings() {
        let path = std::env::temp_dir().join(format!("luma-reset-{}.db", std::process::id()));
        let p = path.to_str().unwrap();
        super::dispatch(
            p,
            "settings_patch",
            r#"{"theme_mode":"oled","font_size":130,"pause_on_unplug":false}"#,
        )
        .unwrap();
        super::dispatch(p, "queue_save", r#"{"songs":[],"position":42,"index":0}"#).unwrap();
        let before = super::dispatch(p, "queue_load", "{}").unwrap();
        assert_eq!(
            super::dispatch(p, "settings_reset", "{}").unwrap(),
            super::defaults()
        );
        assert_eq!(
            super::dispatch(
                p,
                "settings_import",
                r#"{"theme_mode":"oled","font_size":130}"#
            )
            .unwrap(),
            super::defaults()
        );
        assert_eq!(before, super::dispatch(p, "queue_load", "{}").unwrap());
        assert!(super::dispatch(p, "settings_patch", r#"{"font_size":999}"#).is_err());
        let _ = std::fs::remove_file(path);
    }
}
