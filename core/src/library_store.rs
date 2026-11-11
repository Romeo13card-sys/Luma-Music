//! Bounded, session-scoped local metadata. Backups contain validated settings and metadata only.
use crate::{json, Result, Value};
use rusqlite::{params, Connection, OptionalExtension};
use std::time::{SystemTime, UNIX_EPOCH};
fn error(_: rusqlite::Error) -> String {
    "Не удалось сохранить локальную библиотеку".into()
}
fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}
fn scope(v: &Value) -> Result<&str> {
    v["scope"]
        .as_str()
        .filter(|s| *s == "local" || (s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())))
        .ok_or_else(|| "Некорректная сессия библиотеки".into())
}
fn clean_song(v: &Value) -> Result<Value> {
    let raw = crate::queue::normalize(&json!({"songs":[v],"index":0,"position":0}).to_string())?;
    let mut value: Value = serde_json::from_str(&raw).map_err(|_| "Некорректный трек")?;
    Ok(value["songs"][0].take())
}
fn text(v: &Value, max: usize) -> String {
    v.as_str().unwrap_or("").chars().take(max).collect()
}
fn limit(kind: &str) -> usize {
    match kind {
        "history" => 100,
        "bookmark" => 200,
        "pin" => 64,
        "job" => 200,
        _ => 0,
    }
}
fn list(db: &Connection, scope: &str, kind: &str) -> Result<Vec<Value>> {
    let mut query=db.prepare("SELECT data,stamp,id FROM local_library WHERE scope=?1 AND kind=?2 ORDER BY stamp DESC,id LIMIT ?3").map_err(error)?;
    let rows = query
        .query_map(params![scope, kind, limit(kind) as i64], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, String>(2)?,
            ))
        })
        .map_err(error)?;
    let mut result = Vec::new();
    for row in rows {
        let (raw, stamp, id) = row.map_err(error)?;
        let mut value: Value =
            serde_json::from_str(&raw).map_err(|_| "Повреждена локальная библиотека")?;
        value["stamp"] = json!(stamp);
        value["key"] = json!(id);
        result.push(value)
    }
    Ok(result)
}
fn get(db: &Connection, scope: &str, kind: &str, id: &str) -> Result<Option<Value>> {
    let raw: Option<String> = db
        .query_row(
            "SELECT data FROM local_library WHERE scope=?1 AND kind=?2 AND id=?3",
            params![scope, kind, id],
            |r| r.get(0),
        )
        .optional()
        .map_err(error)?;
    raw.map(|r| serde_json::from_str(&r).map_err(|_| "Повреждена локальная библиотека".into()))
        .transpose()
}
fn put(
    db: &Connection,
    scope: &str,
    kind: &str,
    id: &str,
    value: &Value,
    stamp: i64,
) -> Result<()> {
    db.execute("INSERT INTO local_library(scope,kind,id,data,stamp) VALUES(?1,?2,?3,?4,?5) ON CONFLICT(scope,kind,id) DO UPDATE SET data=excluded.data,stamp=excluded.stamp",params![scope,kind,id,value.to_string(),stamp]).map_err(error)?;
    db.execute("DELETE FROM local_library WHERE scope=?1 AND kind=?2 AND id NOT IN (SELECT id FROM local_library WHERE scope=?1 AND kind=?2 ORDER BY stamp DESC,id LIMIT ?3)",params![scope,kind,limit(kind) as i64]).map_err(error)?;
    db.execute("DELETE FROM local_library WHERE kind=?1 AND rowid NOT IN (SELECT rowid FROM local_library WHERE kind=?1 ORDER BY stamp DESC,rowid DESC LIMIT ?2)",params![kind,(limit(kind)*4) as i64]).map_err(error)?;
    Ok(())
}
fn remove(db: &Connection, scope: &str, kind: &str, id: Option<&str>) -> Result<()> {
    if let Some(id) = id {
        db.execute(
            "DELETE FROM local_library WHERE scope=?1 AND kind=?2 AND id=?3",
            params![scope, kind, id],
        )
        .map_err(error)?;
    } else {
        db.execute(
            "DELETE FROM local_library WHERE scope=?1 AND kind=?2",
            params![scope, kind],
        )
        .map_err(error)?;
    }
    Ok(())
}
fn position(v: &Value) -> Result<u64> {
    v.as_u64()
        .filter(|n| *n <= 86_400_000)
        .ok_or_else(|| "Некорректная позиция трека".into())
}
fn number(v: &Value) -> Result<String> {
    v.as_str()
        .map(str::to_string)
        .or_else(|| v.as_u64().map(|n| n.to_string()))
        .filter(|s| !s.is_empty() && s.len() <= 64 && s.bytes().all(|b| b.is_ascii_digit()))
        .ok_or_else(|| "Некорректный идентификатор".into())
}
fn normalized(kind: &str, v: &Value) -> Result<(String, Value)> {
    if kind == "pin" {
        let entity = v["entity"]
            .as_str()
            .filter(|s| matches!(*s, "album" | "artist" | "playlist"))
            .ok_or("Некорректное закрепление")?;
        let id = number(&v["id"])?;
        let owner = if entity == "playlist" {
            number(&v["owner"])?
        } else {
            String::new()
        };
        return Ok((
            format!("{entity}:{owner}:{id}"),
            json!({"entity":entity,"id":id,"owner":owner,"title":text(&v["title"],256),"cover":v["cover"].as_str().and_then(crate::artwork::normalize)}),
        ));
    }
    let song = clean_song(&v["song"])?;
    let id = song["id"]
        .as_str()
        .ok_or("Нет идентификатора трека")?
        .to_string();
    let position = position(v.get("position").unwrap_or(&json!(0)))?;
    match kind {
        "history" => Ok((
            id,
            json!({"song":song,"position":position,"plays":v["plays"].as_u64().unwrap_or(1).clamp(1,1_000_000_000)}),
        )),
        "bookmark" => Ok((
            format!("{id}@{position}"),
            json!({"song":song,"position":position,"label":text(&v["label"],200)}),
        )),
        _ => Err("Неизвестная категория библиотеки".into()),
    }
}
fn backup(db: &Connection, v: &Value, scope: &str) -> Result<Value> {
    let settings = crate::storage::settings(db)?;
    let queue: Option<String> = db
        .query_row("SELECT snapshot FROM playback_state WHERE id=1", [], |r| {
            r.get(0)
        })
        .optional()
        .map_err(error)?;
    let queue = queue
        .map(|s| crate::queue::normalize(&s))
        .transpose()?
        .map(|s| serde_json::from_str::<Value>(&s).unwrap())
        .unwrap_or(json!({"songs":[],"index":0,"position":0}));
    let mut result = json!({"format":"luma-backup","schema":1,"created":now(),"settings":settings,"pins":list(db,scope,"pin")?,"bookmarks":list(db,scope,"bookmark")?});
    if v["include_queue"].as_bool() != Some(false) {
        result["queue"] = queue
    }
    if v["include_history"].as_bool() == Some(true) {
        result["history"] = json!(list(db, scope, "history")?)
    }
    if result.to_string().len() > crate::queue::MAX_BYTES {
        return Err("Резервная копия превышает 512 КБ. Отключите историю или очередь".into());
    }
    Ok(result)
}
fn restore(db: &Connection, v: &Value, scope: &str) -> Result<Value> {
    let b = &v["backup"];
    if b["format"] != "luma-backup" || b["schema"] != 1 {
        return Err("Неподдерживаемый формат резервной копии".into());
    }
    let settings = b["settings"]
        .as_object()
        .filter(|m| m.len() <= 96)
        .ok_or("Некорректные настройки в копии")?;
    if settings
        .iter()
        .any(|(k, v)| !crate::storage::valid_setting(k, v))
    {
        return Err("В копии есть недопустимые настройки".into());
    }
    let queue = b
        .get("queue")
        .map(|q| crate::queue::normalize(&q.to_string()))
        .transpose()?;
    let mut records = Vec::new();
    for (field, kind) in [
        ("history", "history"),
        ("bookmarks", "bookmark"),
        ("pins", "pin"),
    ] {
        if let Some(value) = b.get(field) {
            let entries = value
                .as_array()
                .filter(|a| a.len() <= limit(kind))
                .ok_or("Слишком много записей в копии")?;
            let mut clean = Vec::new();
            for entry in entries {
                let (id, data) = normalized(kind, entry)?;
                let stamp = entry["stamp"]
                    .as_i64()
                    .filter(|s| *s >= 0)
                    .unwrap_or_else(now);
                clean.push((id, data, stamp))
            }
            records.push((kind, clean));
        }
    }
    let mut values = crate::storage::defaults();
    for (k, v) in settings {
        values[k] = v.clone()
    }
    db.execute("DELETE FROM app_settings", []).map_err(error)?;
    for (k, v) in values.as_object().unwrap() {
        db.execute(
            "INSERT INTO app_settings(key,value) VALUES(?1,?2)",
            params![k, v.to_string()],
        )
        .map_err(error)?;
    }
    if let Some(queue) = queue {
        db.execute("INSERT INTO playback_state(id,snapshot) VALUES(1,?1) ON CONFLICT(id) DO UPDATE SET snapshot=excluded.snapshot",[queue]).map_err(error)?;
    }
    for (kind, entries) in records {
        remove(db, scope, kind, None)?;
        for (id, data, stamp) in entries {
            put(db, scope, kind, &id, &data, stamp)?
        }
    }
    Ok(json!({"settings":values,"restored":true}))
}
pub fn dispatch(db: &mut Connection, operation: &str, v: &Value) -> Result<Value> {
    let scope = scope(v)?;
    db.execute_batch("CREATE TABLE IF NOT EXISTS local_library(scope TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,data TEXT NOT NULL,stamp INTEGER NOT NULL,PRIMARY KEY(scope,kind,id));CREATE INDEX IF NOT EXISTS local_library_time ON local_library(scope,kind,stamp DESC);").map_err(error)?;
    let tx = db
        .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
        .map_err(error)?;
    let stamp = v["now"].as_i64().filter(|s| *s >= 0).unwrap_or_else(now);
    let value = match operation {
        "history_list" => json!(list(&tx, scope, "history")?),
        "bookmark_list" => json!(list(&tx, scope, "bookmark")?),
        "pin_list" => json!(list(&tx, scope, "pin")?),
        "history_add" | "bookmark_add" | "pin_set" => {
            let kind = match operation {
                "history_add" => "history",
                "bookmark_add" => "bookmark",
                _ => "pin",
            };
            let (id, mut value) = normalized(kind, v)?;
            if kind == "history" {
                value["plays"] = json!(get(&tx, scope, kind, &id)?
                    .and_then(|v| v["plays"].as_u64())
                    .unwrap_or(0)
                    .saturating_add(1)
                    .min(1_000_000_000));
            }
            put(&tx, scope, kind, &id, &value, stamp)?;
            json!(true)
        }
        "history_position" => {
            let id = v["id"].as_str().ok_or("Нет трека")?;
            let pos = position(&v["position"])?;
            if let Some(mut item) = get(&tx, scope, "history", id)? {
                item["position"] = json!(pos);
                tx.execute(
                    "UPDATE local_library SET data=?1 WHERE scope=?2 AND kind='history' AND id=?3",
                    params![item.to_string(), scope, id],
                )
                .map_err(error)?;
            }
            json!(true)
        }
        "history_stats" => {
            let items = list(&tx, scope, "history")?;
            json!({"tracks":items.len(),"starts":items.iter().map(|v|v["plays"].as_u64().unwrap_or(0)).sum::<u64>()})
        }
        "history_clear" | "bookmark_clear" | "pin_clear" => {
            let kind = operation.split('_').next().unwrap();
            remove(&tx, scope, kind, None)?;
            json!(true)
        }
        "bookmark_remove" | "pin_remove" => {
            let kind = operation.split('_').next().unwrap();
            let id = v["id"]
                .as_str()
                .filter(|s| s.len() <= 400)
                .ok_or("Нет записи")?;
            remove(&tx, scope, kind, Some(id))?;
            json!(true)
        }
        "backup_export" => backup(&tx, v, scope)?,
        "backup_import" => restore(&tx, v, scope)?,
        _ => return Err("Неизвестная операция библиотеки".into()),
    };
    tx.commit().map_err(error)?;
    Ok(value)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn db() -> Connection {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch("CREATE TABLE app_settings(key TEXT PRIMARY KEY,value TEXT);CREATE TABLE playback_state(id INTEGER PRIMARY KEY,snapshot TEXT);").unwrap();
        db
    }
    fn args() -> Value {
        json!({"scope":"local","song":{"id":"42","title":"Song","artists":"Artist","available":true,"token":"secret","waveSession":"expires"},"position":1200})
    }
    #[test]
    fn history_is_bounded_scoped_and_backups_drop_private_fields() {
        let mut db = db();
        let mut v = args();
        for n in 0..110 {
            v["song"]["id"] = json!(n.to_string());
            v["now"] = json!(n);
            dispatch(&mut db, "history_add", &v).unwrap();
        }
        let rows = dispatch(&mut db, "history_list", &v).unwrap();
        assert_eq!(rows.as_array().unwrap().len(), 100);
        assert_eq!(rows[0]["song"]["id"], "109");
        assert!(!rows.to_string().contains("secret"));
        v["scope"] = json!("a".repeat(64));
        assert_eq!(dispatch(&mut db, "history_list", &v).unwrap(), json!([]));
        v["scope"] = json!("local");
        dispatch(&mut db, "bookmark_add", &v).unwrap();
        let copy = dispatch(&mut db, "backup_export", &v).unwrap();
        assert!(copy.get("history").is_none());
        assert!(!copy.to_string().contains("waveSession"));
    }
    #[test]
    fn restore_rejects_secret_settings_atomically_and_restores_bookmarks() {
        let mut db = db();
        let v = args();
        dispatch(&mut db, "bookmark_add", &v).unwrap();
        let copy = dispatch(&mut db, "backup_export", &v).unwrap();
        let mut bad = copy.clone();
        bad["settings"] = json!({"theme_mode":"dark","token":"private"});
        assert!(dispatch(
            &mut db,
            "backup_import",
            &json!({"scope":"local","backup":bad})
        )
        .is_err());
        assert_eq!(crate::storage::settings(&db).unwrap(), json!({}));
        dispatch(&mut db, "bookmark_clear", &v).unwrap();
        dispatch(
            &mut db,
            "backup_import",
            &json!({"scope":"local","backup":copy}),
        )
        .unwrap();
        assert_eq!(
            dispatch(&mut db, "bookmark_list", &v)
                .unwrap()
                .as_array()
                .unwrap()
                .len(),
            1
        );
        assert!(!crate::storage::settings(&db)
            .unwrap()
            .as_object()
            .unwrap()
            .contains_key("token"));
    }
}
