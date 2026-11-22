import SwiftUI
import AVFoundation

@main
struct LumaApp: App {
    var body: some Scene {
        WindowGroup {
            MusicView()
        }
    }
}

private enum LumaTab: String, CaseIterable {
    case home = "Главная"
    case search = "Поиск"
    case library = "Коллекция"
    case wave = "Моя волна"

    var symbol: String {
        switch self {
        case .home: return "waveform"
        case .search: return "magnifyingglass"
        case .library: return "square.stack"
        case .wave: return "waveform.path.ecg"
        }
    }
}

struct Song: Identifiable {
    let id: String
    let title: String
    let artist: String
}

struct MusicView: View {
    @State private var token = ""
    @State private var query = ""
    @State private var message = "Войдите, чтобы открыть свою музыку"
    @State private var songs: [Song] = []
    @State private var busy = false
    @State private var connected = false
    @State private var player: AVPlayer?
    @State private var currentSong: Song?
    @State private var isPlaying = false
    @State private var selectedTab: LumaTab = .home
    @State private var showingAccount = false

    private let accent = Color(red: 1.0, green: 0.85, blue: 0.30)
    private let ink = Color(red: 0.10, green: 0.11, blue: 0.13)
    private let background = Color(red: 0.035, green: 0.045, blue: 0.06)
    private let surface = Color(red: 0.085, green: 0.10, blue: 0.13)

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    switch selectedTab {
                    case .home:
                        homeContent
                    case .search:
                        searchContent
                    case .library:
                        libraryContent
                    case .wave:
                        waveContent
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 22)
                .frame(maxWidth: 760, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            if let currentSong {
                playerDock(for: currentSong)
            }
            tabBar
        }
        .background(background.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showingAccount) {
            accountSheet
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 13).fill(accent)
                Image(systemName: "waveform")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(ink)
            }
            .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 2) {
                Text("LUMA MUSIC")
                    .font(.system(size: 15, weight: .heavy, design: .rounded))
                    .tracking(1.1)
                Text("YOUR SOUND. YOUR FLOW.")
                    .font(.system(size: 9, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                showingAccount = true
            } label: {
                Image(systemName: connected ? "person.crop.circle.fill" : "person.crop.circle")
                    .font(.system(size: 24))
                    .foregroundStyle(connected ? accent : .white.opacity(0.8))
                    .frame(width: 46, height: 46)
                    .background(surface, in: Circle())
            }
            .accessibilityLabel("Аккаунт")
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }

    private var homeContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            eyebrow("LUMA FLOW  /  PERSONAL RADIO")
            VStack(alignment: .leading, spacing: 14) {
                Text("ТВОЯ МУЗЫКА")
                    .font(.system(size: 11, weight: .heavy))
                    .tracking(1.6)
                    .foregroundStyle(ink.opacity(0.75))
                Text("Твой ритм.\nТвоя волна.")
                    .font(.system(size: 36, weight: .black, design: .rounded))
                    .tracking(-1.2)
                    .lineSpacing(-2)
                    .foregroundStyle(ink)
                Text("Персональные рекомендации и любимые треки — в одном пространстве.")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(ink.opacity(0.72))
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    catalog("wave", "")
                } label: {
                    Label("Включить Мою волну", systemImage: "play.fill")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18)
                        .frame(minHeight: 48)
                        .background(ink, in: Capsule())
                }
                .disabled(!connected || busy)
                .opacity(connected && !busy ? 1 : 0.55)
            }
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(accent, in: RoundedRectangle(cornerRadius: 28, style: .continuous))

            HStack {
                Text("БЫСТРЫЙ ДОСТУП")
                    .font(.system(size: 10, weight: .heavy))
                    .tracking(1.5)
                    .foregroundStyle(accent)
                Spacer()
            }
            HStack(spacing: 10) {
                shortcut("Поиск", detail: "Найти музыку", symbol: "magnifyingglass") {
                    selectedTab = .search
                }
                shortcut("Коллекция", detail: "Избранное", symbol: "heart.fill") {
                    selectedTab = .library
                    catalog("likes", "")
                }
            }
            if let currentSong {
                VStack(alignment: .leading, spacing: 10) {
                    eyebrow("СЕЙЧАС ИГРАЕТ")
                    songRow(currentSong)
                }
            }
            statusLine
        }
    }

    private var waveContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            eyebrow("LUMA FLOW  /  PERSONAL RADIO")
            Text("Моя волна")
                .font(.system(size: 32, weight: .black, design: .rounded))
                .tracking(-0.8)
            Text("Персональная подборка, которая помогает открыть музыку под настроение.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button {
                catalog("wave", "")
            } label: {
                Label("Обновить волну", systemImage: "waveform.path.ecg")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(ink)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(accent, in: RoundedRectangle(cornerRadius: 16))
            }
            .disabled(!connected || busy)
            .opacity(connected && !busy ? 1 : 0.55)
            songResults
            statusLine
        }
    }

    private var searchContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            eyebrow("DISCOVER  /  LUMA CATALOG")
            Text("Найди свой звук")
                .font(.system(size: 32, weight: .black, design: .rounded))
                .tracking(-0.8)
            Text("Ищи треки и открывай новую музыку.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("Трек или исполнитель", text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit { catalog("search", query) }
                Button {
                    catalog("search", query)
                } label: {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ink)
                        .frame(width: 38, height: 38)
                        .background(accent, in: Circle())
                }
                .disabled(busy || query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(10)
            .background(surface, in: RoundedRectangle(cornerRadius: 18))
            songResults
            statusLine
        }
    }

    private var libraryContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            eyebrow("YOUR SPACE  /  LIBRARY")
            Text("Твоя коллекция")
                .font(.system(size: 32, weight: .black, design: .rounded))
                .tracking(-0.8)
            Text("Всё, что хочется сохранить и переслушивать.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button {
                catalog("likes", "")
            } label: {
                Label("Обновить избранное", systemImage: "heart.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(ink)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(accent, in: RoundedRectangle(cornerRadius: 16))
            }
            .disabled(!connected || busy)
            songResults
            statusLine
        }
    }

    private var songResults: some View {
        VStack(spacing: 8) {
            ForEach(songs) { song in
                songRow(song)
            }
        }
    }

    private func songRow(_ song: Song) -> some View {
        Button {
            play(song)
        } label: {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 12)
                    .fill(accent.opacity(0.16))
                    .overlay {
                        Image(systemName: "music.note")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(accent)
                    }
                    .frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 4) {
                    Text(song.title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(song.artist)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: currentSong?.id == song.id && isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(accent)
            }
            .padding(10)
            .background(surface, in: RoundedRectangle(cornerRadius: 17))
            .contentShape(RoundedRectangle(cornerRadius: 17))
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }

    private func playerDock(for song: Song) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "waveform")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(ink)
                .frame(width: 42, height: 42)
                .background(accent, in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 3) {
                Text(song.title)
                    .font(.system(size: 13, weight: .bold))
                    .lineLimit(1)
                Text(song.artist)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                if isPlaying { player?.pause() } else { player?.play() }
                isPlaying.toggle()
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(ink)
                    .frame(width: 46, height: 46)
                    .background(accent, in: Circle())
            }
            Button {
                player?.pause()
                player = nil
                currentSong = nil
                isPlaying = false
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 38, height: 42)
            }
            .accessibilityLabel("Остановить")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(surface)
    }

    private var tabBar: some View {
        HStack(spacing: 8) {
            ForEach(LumaTab.allCases, id: \.self) { tab in
                Button {
                    selectedTab = tab
                    if tab == .library && connected && songs.isEmpty {
                        catalog("likes", "")
                    } else if tab == .wave && connected {
                        catalog("wave", "")
                    }
                } label: {
                    VStack(spacing: 5) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 17, weight: .semibold))
                        Text(tab.rawValue)
                            .font(.system(size: 10, weight: .bold))
                    }
                    .foregroundStyle(selectedTab == tab ? ink : .secondary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 58)
                    .background(
                        selectedTab == tab ? accent : Color.clear,
                        in: RoundedRectangle(cornerRadius: 17)
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .background(background)
    }

    private var accountSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            eyebrow("ACCOUNT  /  YANDEX MUSIC")
            Text(connected ? "Аккаунт подключён" : "Подключи аккаунт")
                .font(.system(size: 28, weight: .black, design: .rounded))
            Text("OAuth-токен хранится только в памяти текущего сеанса.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            SecureField("OAuth-токен", text: $token)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .background(surface, in: RoundedRectangle(cornerRadius: 14))
                .disabled(busy)
            Button {
                player?.pause()
                player = nil
                songs = []
                connected = false
                request("account", "") { value in
                    connected = true
                    message = "Аккаунт: \(value["name"] as? String ?? "Яндекс")"
                }
            } label: {
                Text(connected ? "Проверить подключение" : "Подключить")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(ink)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(accent, in: RoundedRectangle(cornerRadius: 16))
            }
            .disabled(busy || token.isEmpty)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(background.ignoresSafeArea())
    }

    private var statusLine: some View {
        HStack(spacing: 8) {
            if busy { ProgressView().tint(accent) }
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    private func eyebrow(_ value: String) -> some View {
        Text(value)
            .font(.system(size: 10, weight: .heavy))
            .tracking(1.5)
            .foregroundStyle(accent)
    }

    private func shortcut(
        _ title: String,
        detail: String,
        symbol: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(accent)
                Text(title).font(.system(size: 15, weight: .bold))
                Text(detail).font(.system(size: 11)).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 92, alignment: .leading)
            .padding(14)
            .background(surface, in: RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
    }

    private func catalog(_ operation: String, _ argument: String) {
        request(operation, argument) { data in
            songs = (data["tracks"] as? [[String: Any]] ?? []).compactMap { row in
                guard row["available"] as? Bool == true, let id = row["id"] as? String else {
                    return nil
                }
                return Song(
                    id: id,
                    title: row["title"] as? String ?? "Трек",
                    artist: row["artists"] as? String ?? ""
                )
            }
            message = "Треков: \(songs.count)"
        }
    }

    private func play(_ song: Song) {
        request("stream", song.id) { data in
            guard let raw = data["url"] as? String, let url = URL(string: raw) else {
                message = "Не удалось получить аудиопоток"
                return
            }
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback)
                try AVAudioSession.sharedInstance().setActive(true)
            } catch {
                message = "Аудиосессия недоступна"
                return
            }
            player?.pause()
            let nextPlayer = AVPlayer(url: url)
            player = nextPlayer
            currentSong = song
            nextPlayer.play()
            isPlaying = true
            message = "Воспроизведение"
        }
    }

    private func request(
        _ operation: String,
        _ argument: String,
        success: @escaping ([String: Any]) -> Void
    ) {
        guard !busy else { return }
        busy = true
        message = "Загрузка…"
        let credential = token
        DispatchQueue.global(qos: .userInitiated).async {
            let raw: String? = credential.withCString { tokenPointer in
                operation.withCString { operationPointer in
                    argument.withCString { argumentPointer in
                        guard let pointer = luma_request(tokenPointer, operationPointer, argumentPointer) else {
                            return nil
                        }
                        defer { luma_free(pointer) }
                        return String(cString: pointer)
                    }
                }
            }
            let reply = raw
                .flatMap { $0.data(using: .utf8) }
                .flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
            DispatchQueue.main.async {
                busy = false
                guard reply?["ok"] as? Bool == true else {
                    message = reply?["error"] as? String ?? "Ошибка ядра"
                    return
                }
                let value = reply?["data"]
                if let rows = value as? [[String: Any]] {
                    success(["tracks": rows])
                } else if let url = value as? String {
                    success(["url": url])
                } else {
                    success(value as? [String: Any] ?? [:])
                }
            }
        }
    }
}
