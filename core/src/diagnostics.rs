use serde_json::{json, Value};
pub fn report(v: &Value) -> Result<Value, String> {
    let mut lines = vec!["Luma Music diagnostics".to_owned()];
    // No free-form logs, URLs, paths, track data, identifiers or credentials.
    for key in [
        "version",
        "build",
        "android",
        "api",
        "device",
        "abis",
        "theme",
        "language",
        "economy",
        "channel",
        "quality_wifi",
        "quality_mobile",
        "metered",
    ] {
        let raw = match &v[key] {
            Value::String(s) => s.clone(),
            Value::Number(n) => n.to_string(),
            Value::Bool(b) => b.to_string(),
            _ => continue,
        };
        let clean: String = raw.chars().filter(|c| !c.is_control()).take(160).collect();
        if clean.contains("://") || clean.contains('/') || clean.contains('\\') {
            continue;
        }
        lines.push(format!("{key}: {clean}"));
    }
    Ok(json!(lines.join("\n")))
}
#[cfg(test)]
mod tests {
    #[test]
    fn excludes_credentials_paths_and_arbitrary_logs() {
        let result = super::report(&serde_json::json!({"version":"0.5.0-rc.8","token":"secret",
            "logs":"private","device":"https://private.invalid/token","build":"/private/path",
            "language":"ar","api":33,"channel":"stable","quality_mobile":128,"metered":true}))
        .unwrap();
        let text = result.as_str().unwrap();
        assert!(text.contains("language: ar") && text.contains("api: 33"));
        assert!(
            text.contains("channel: stable")
                && text.contains("quality_mobile: 128")
                && text.contains("metered: true")
        );
        for private in ["secret", "private", "token", "logs"] {
            assert!(!text.contains(private))
        }
    }
}
