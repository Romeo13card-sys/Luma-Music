//! Shared settings navigation and native control descriptions. Values are validated by storage.
use crate::{json, Value};
pub fn schema() -> Value {
    json!({"categories":[
        {"id":"appearance","title":"Оформление","summary":"Темы, язык, размер текста и вибрация","keywords":"theme oled font language интерфейс шрифт"},
        {"id":"sound","title":"Качество музыки","summary":"Отдельное качество для Wi-Fi и мобильной сети","keywords":"128 192 320 mp3 bitrate"},
        {"id":"audio","title":"Звук","summary":"Баланс каналов и системный эквалайзер","keywords":"equalizer баланс левый правый наушники"},
        {"id":"network","title":"Сеть и предзагрузка","summary":"Быстрое переключение и расход трафика","keywords":"preload wifi internet подготовка буфер мобильная"},
        {"id":"player","title":"Воспроизведение","summary":"Повтор, перемешивание, наушники и таймер сна","keywords":"bluetooth shuffle repeat сон 15 30 60 90"},
        {"id":"downloads","title":"Офлайн-загрузки","summary":"Качество скачивания, сеть и лимит места","keywords":"offline download скачать память квота"},
        {"id":"wave","title":"Моя волна","summary":"Подборки и настройки рекомендаций","keywords":"rotor настроение жанр язык"},
        {"id":"lyrics","title":"Тексты песен","summary":"Размер текста и подсветка слов","keywords":"lrc ttml lyrics караоке"},
        {"id":"data","title":"История и данные","summary":"История, закладки и резервная копия","keywords":"backup restore bookmarks данные экспорт импорт"},
    {"id":"storage","title":"Хранилище","summary":"Кэш обложек, свободное место и файлы обновлений","keywords":"cache apk удалить очистить"},
        {"id":"updates","title":"Обновления","summary":"GitHub OTA, канал выпусков и установка APK","keywords":"rc release версия stable"},
        {"id":"account","title":"Аккаунты","summary":"Яндекс Музыка и отдельное подключение Spotify","keywords":"oauth login токен авторизация"},
        {"id":"about","title":"О приложении","summary":"Версия, телефон, автор и поддержка","keywords":"telegram донат android api ram озу"}
       ],"groups":{
        "data":[{"title":"Локальная история","fields":[{"key":"history_enabled","type":"bool","title":"Сохранять историю прослушивания","summary":"Последние 100 треков для текущего входа. Новые записи можно отключить, а список очистить отдельно.","default":true}]}],
    "network":[{"title":"Быстрое переключение","fields":[
         {"key":"preload_next","type":"bool","title":"Подготавливать следующий трек","summary":"Один следующий трек готовится заранее. Использует дополнительный трафик и память.","default":true},
         {"key":"preload_wifi_only","type":"bool","title":"Предзагрузка только без лимита","summary":"Отключать предзагрузку в тарифицируемой сети.","default":false}]},
        {"title":"Расход трафика","fields":[{"key":"unmetered_only","type":"bool","title":"Только сеть без лимита","summary":"Отключать сетевое аудио в тарифицируемой сети. Локальная музыка остаётся доступна.","default":false}]}],
        "downloads":[{"title":"Музыка на устройстве","fields":[
        {"key":"download_wifi_only","type":"bool","title":"Скачивать только по Wi-Fi","summary":"Требуется Wi-Fi без тарификации.","default":true},
        {"key":"download_quality","type":"int","title":"Качество скачивания","summary":"Применяется к новым заданиям.","default":192,"values":[128,192,320],"labels":["128 кбит/с","192 кбит/с","320 кбит/с"]},
        {"key":"download_limit_mb","type":"int","title":"Лимит офлайн-библиотеки","summary":"Загрузчик учитывает готовые и незавершённые аудиофайлы.","default":1024,"values":[256,512,1024,2048,4096,8192,16384],"labels":["256 МиБ","512 МиБ","1 ГиБ","2 ГиБ","4 ГиБ","8 ГиБ","16 ГиБ"]}]}],
        "audio":[{"title":"Каналы","fields":[{"key":"balance","type":"int","title":"Баланс звука","summary":"Ослабление левого или правого канала.","default":0,"values":[-100,-50,0,50,100],"labels":["Только левый","Левее","По центру","Правее","Только правый"]}]}],
        "lyrics":[{"title":"Чтение текста","fields":[
        {"key":"lyrics_scale","type":"int","title":"Размер текста песни","summary":"Дополнительно к системному размеру шрифта.","default":100,"values":[90,100,115,130,150],"labels":["90%","100%","115%","130%","150%"]},
        {"key":"lyrics_words","type":"bool","title":"Подсвечивать слова","summary":"Для текста с отметками начала слов. Подсветка текущей строки сохраняется.","default":true}]}]
       }})
}
pub fn levels(v: &Value) -> Result<Value, String> {
    let balance = v["balance"]
        .as_i64()
        .filter(|n| (-100..=100).contains(n))
        .ok_or("Некорректный баланс")? as f64
        / 100.0;
    Ok(json!({"left":1.0-balance.max(0.0),"right":1.0+balance.min(0.0)}))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn navigation_and_channels_have_bounded_values() {
        let schema = schema();
        let ids = schema["categories"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| c["id"].as_str().unwrap())
            .collect::<std::collections::HashSet<_>>();
        assert_eq!(ids.len(), schema["categories"].as_array().unwrap().len());
        assert_eq!(
            levels(&json!({"balance":-100})).unwrap(),
            json!({"left":1.0,"right":0.0})
        );
        assert!(levels(&json!({"balance":101})).is_err());
        for groups in schema["groups"].as_object().unwrap().values() {
            for group in groups.as_array().unwrap() {
                for field in group["fields"].as_array().unwrap() {
                    assert!(crate::storage::valid_setting(
                        field["key"].as_str().unwrap(),
                        &field["default"]
                    ));
                    if let Some(values) = field["values"].as_array() {
                        for value in values {
                            assert!(crate::storage::valid_setting(
                                field["key"].as_str().unwrap(),
                                value
                            ));
                        }
                    }
                }
            }
        }
    }
}
