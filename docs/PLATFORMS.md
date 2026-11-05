# Платформы RC5

Android остаётся основным клиентом. Новые платформы — отдельные предварительные
клиенты, не функциональная копия всех Android/YAYMA-экранов.

## Windows / Linux / macOS

`desktop/`: Rust/egui UI, общий Rust MusicClient, аккаунт, поиск, избранное,
получение партии волны, выбор трека, MP3-воспроизведение и пауза через rodio.
Аудио ограничено 100 МиБ и сначала скачивается во временный файл; это не
потоковая буферизация Android. Токен вводится вручную и хранится только в RAM
до выхода; автоматического входа, полной очереди/feedback волны, MediaSession,
офлайн-библиотеки и встроенного OTA desktop пока нет. Ссылка на Releases — ручная.

Сборка: `cargo build --release --locked -p luma-desktop`.
Linux: нужны libasound2-dev, libxkbcommon-dev, libwayland-dev, libgl1-mesa-dev;
при запуске нужны графическая сессия и ALSA/совместимый аудиосервер.
macOS/Windows: платформенные SDK и линкер. Отдельных подписей Windows/macOS
и notarization нет. Наличие исходников не означает подтверждение запуска.

GitHub Actions `Luma platform previews` собирает нативный исполняемый файл
на каждой ОС и кладёт его в Artifacts. Это не APK и не установщик.
Готовность каждого файла определяется результатом соответствующей job.

## iPhone (iOS 16+)

`ios/`: SwiftUI, AVPlayer, C ABI общего Rust-ядра, ручной токен только в памяти,
аккаунт, поиск/избранное/партия волны и воспроизведение выбранного MP3.
Swift вызывает Rust в фоновом потоке. Это исходный preview; оконный OAuth,
Keychain-персистентность, OTA/App Store, фоновая интеграция и полная очередь
пока не подключены. На реальном iPhone не проверено.

CI собирает Rust для aarch64-apple-ios-sim и unsigned .app для Apple Silicon
симулятора через XcodeGen/Xcode. **Это не IPA для установки на iPhone.**
Для установки на физический iPhone нужны macOS/Xcode, target aarch64-apple-ios,
изменённый путь к библиотеке, Apple team, signing identity и provisioning profile.
Автоматически создавать/использовать чужие Apple-сертификаты нельзя; без них
подписанный iPhone-билд и распространение через TestFlight не выполнены.

Все адаптации GPL-3.0, исходники и build workflow находятся в репозитории.
