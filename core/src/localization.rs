//! Offline language catalogues. Parse only the selected pack, never all languages.
use serde_json::{json, Map, Value};
pub const LANGUAGES: &[(&str, &str, &str)] = &[
    ("ru", "Русский", "{}"),
    ("en", "English", include_str!("../../locales/en.json")),
    ("uk", "Українська", include_str!("../../locales/uk.json")),
    ("be", "Беларуская", include_str!("../../locales/be.json")),
    ("kk", "Қазақша", include_str!("../../locales/kk.json")),
    ("uz", "Oʻzbekcha", include_str!("../../locales/uz.json")),
    ("tr", "Türkçe", include_str!("../../locales/tr.json")),
    ("az", "Azərbaycanca", include_str!("../../locales/az.json")),
    ("de", "Deutsch", include_str!("../../locales/de.json")),
    ("fr", "Français", include_str!("../../locales/fr.json")),
    ("es", "Español", include_str!("../../locales/es.json")),
    ("it", "Italiano", include_str!("../../locales/it.json")),
    ("pt", "Português", include_str!("../../locales/pt.json")),
    ("pl", "Polski", include_str!("../../locales/pl.json")),
    ("cs", "Čeština", include_str!("../../locales/cs.json")),
    ("nl", "Nederlands", include_str!("../../locales/nl.json")),
    ("sv", "Svenska", include_str!("../../locales/sv.json")),
    ("fi", "Suomi", include_str!("../../locales/fi.json")),
    ("el", "Ελληνικά", include_str!("../../locales/el.json")),
    ("ro", "Română", include_str!("../../locales/ro.json")),
    ("hu", "Magyar", include_str!("../../locales/hu.json")),
    ("bg", "Български", include_str!("../../locales/bg.json")),
    ("sr", "Српски", include_str!("../../locales/sr.json")),
    ("ar", "العربية", include_str!("../../locales/ar.json")),
    ("he", "עברית", include_str!("../../locales/he.json")),
    ("fa", "فارسی", include_str!("../../locales/fa.json")),
    ("hi", "हिन्दी", include_str!("../../locales/hi.json")),
    ("bn", "বাংলা", include_str!("../../locales/bn.json")),
    (
        "id",
        "Bahasa Indonesia",
        include_str!("../../locales/id.json"),
    ),
    ("ms", "Bahasa Melayu", include_str!("../../locales/ms.json")),
    ("vi", "Tiếng Việt", include_str!("../../locales/vi.json")),
    ("th", "ไทย", include_str!("../../locales/th.json")),
    ("ja", "日本語", include_str!("../../locales/ja.json")),
    ("ko", "한국어", include_str!("../../locales/ko.json")),
    (
        "zh-Hans",
        "简体中文",
        include_str!("../../locales/zh-Hans.json"),
    ),
];
pub fn supported(code: &str) -> bool {
    LANGUAGES.iter().any(|(c, _, _)| *c == code)
}
fn canonical(tag: &str) -> Option<&'static str> {
    let tag = tag.to_ascii_lowercase().replace('_', "-");
    if tag == "zh" || tag == "zh-cn" || tag == "zh-sg" || tag.starts_with("zh-hans") {
        return Some("zh-Hans");
    }
    let short = tag.split('-').next()?;
    LANGUAGES
        .iter()
        .find(|(c, _, _)| *c == short)
        .map(|(c, _, _)| *c)
}
pub fn resolve(v: &Value) -> &'static str {
    let selected = v["selected"].as_str().unwrap_or("system");
    if selected != "system" {
        return canonical(selected).unwrap_or("en");
    }
    v["system"]
        .as_array()
        .and_then(|list| list.iter().filter_map(|s| s.as_str()).find_map(canonical))
        .unwrap_or("en")
}
pub fn info(v: &Value) -> Value {
    let code = resolve(v);
    json!({"code":code,"rtl":matches!(code,"ar"|"he"|"fa")})
}
pub fn list() -> Value {
    json!(LANGUAGES
        .iter()
        .map(
            |(code, name, _)| json!({"code":code,"name":name,"preview":!matches!(*code,"ru"|"en")})
        )
        .collect::<Vec<_>>())
}
pub fn catalog(v: &Value) -> Result<Value, String> {
    let code = resolve(v);
    let (_, name, raw) = LANGUAGES.iter().find(|(c, _, _)| *c == code).unwrap();
    let mut strings: Map<String, Value> =
        serde_json::from_str(LANGUAGES[1].2).map_err(|_| "Invalid language catalogue")?;
    let total = strings.len();
    let translated = if code == "ru" {
        for (key, value) in &mut strings {
            *value = json!(key)
        }
        total
    } else if code == "en" {
        total
    } else {
        let overlay: Map<String, Value> =
            serde_json::from_str(raw).map_err(|_| "Invalid language catalogue")?;
        let count = overlay.len();
        strings.extend(overlay);
        count
    };
    Ok(
        json!({"code":code,"name":name,"rtl":matches!(code,"ar"|"he"|"fa"),
        "preview":!matches!(code,"ru"|"en"),"translated":translated,"total":total,"strings":strings}),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn resolves_regions_preference_order_and_rtl() {
        assert_eq!(resolve(&json!({"system":["xx-YY","de-AT","en"]})), "de");
        assert_eq!(resolve(&json!({"selected":"zh-Hans-CN"})), "zh-Hans");
        assert_eq!(resolve(&json!({"system":["xx-YY"]})), "en");
        assert_eq!(resolve(&json!({"selected":"../secrets"})), "en");
        assert_eq!(info(&json!({"selected":"ar"}))["rtl"], true);
        assert_eq!(resolve(&json!({"selected":"uk","system":["en"]})), "uk");
        assert_eq!(LANGUAGES.len(), 35);
    }
    #[test]
    fn catalogues_have_known_keys_and_preserve_format_arguments() {
        let base: Map<String, Value> = serde_json::from_str(LANGUAGES[1].2).unwrap();
        let placeholders = regex::Regex::new(r"%\d+\$[sd]").unwrap();
        for (code, _, raw) in LANGUAGES.iter().skip(2) {
            let overlay: Map<String, Value> = serde_json::from_str(raw).unwrap();
            assert!(!overlay.is_empty(), "{code}");
            for (key, value) in overlay {
                assert!(base.contains_key(&key), "{code}: {key}");
                let expected: Vec<_> = placeholders.find_iter(&key).map(|m| m.as_str()).collect();
                let translated = value.as_str().unwrap();
                assert!(!translated.trim().is_empty(), "{code}: {key}");
                let actual: Vec<_> = placeholders
                    .find_iter(translated)
                    .map(|m| m.as_str())
                    .collect();
                let mut expected = expected;
                let mut actual = actual;
                expected.sort();
                actual.sort();
                assert_eq!(expected, actual, "{code}: {key}");
            }
            let result = catalog(&json!({"selected":code})).unwrap();
            assert_eq!(result["strings"].as_object().unwrap().len(), base.len());
        }
        assert_eq!(
            catalog(&json!({"selected":"ru"})).unwrap()["strings"]["Настройки"],
            "Настройки"
        );
        assert_eq!(
            catalog(&json!({"selected":"en"})).unwrap()["strings"]["Настройки"],
            "Settings"
        );
    }
}
