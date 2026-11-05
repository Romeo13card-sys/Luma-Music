import SwiftUI
import AVFoundation

@main struct LumaApp: App {
    var body: some Scene { WindowGroup { MusicView() } }
}
struct Song: Identifiable { let id: String; let title: String; let artist: String }
struct MusicView: View {
    @State private var token = ""
    @State private var query = ""
    @State private var message = "iPhone preview · токен только в памяти сеанса"
    @State private var songs: [Song] = []
    @State private var busy = false
    @State private var connected = false
    @State private var player: AVPlayer?
    var body: some View {
        NavigationStack {
            VStack {
                SecureField("OAuth-токен", text: $token).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(busy).onChange(of: token) { _ in connected = false; songs = []; player?.pause() }
                Button("Войти") {
                    player?.pause(); player = nil; songs = []; connected = false
                    request("account", "") { value in connected = true; message = "Аккаунт: \(value["name"] as? String ?? "Яндекс")" }
                }.disabled(busy || token.isEmpty)
                if connected {
                    TextField("Трек или исполнитель", text: $query)
                    HStack {
                        Button("Поиск") { catalog("search", query) }
                        Button("Избранное") { catalog("likes", "") }
                        Button("Волна · партия") { catalog("wave", "") }
                    }.disabled(busy)
                }
                Text(message).font(.caption)
                if let player { HStack { Button("Пауза") { player.pause() }; Button("Продолжить") { player.play() } } }
                List(songs) { song in
                    Button { request("stream", song.id) { data in
                        guard let raw = data["url"] as? String, let url = URL(string: raw) else { return }
                        do { try AVAudioSession.sharedInstance().setCategory(.playback); try AVAudioSession.sharedInstance().setActive(true) }
                        catch { message = "Аудиосессия недоступна"; return }
                        player?.pause(); player = AVPlayer(url: url); player?.play(); message = song.title
                    } } label: { VStack(alignment: .leading) { Text(song.title); Text(song.artist).font(.caption).foregroundStyle(.secondary) } }.disabled(busy)
                }
                Text("made by Romeo13card").font(.caption)
            }.padding().navigationTitle("Luma Music")
        }
    }
    private func catalog(_ op: String, _ argument: String) {
        request(op, argument) { data in
            songs = (data["tracks"] as? [[String: Any]] ?? []).compactMap { row in
                guard row["available"] as? Bool == true, let id = row["id"] as? String else { return nil }
                return Song(id: id, title: row["title"] as? String ?? "Трек", artist: row["artists"] as? String ?? "")
            }
            message = "Треков: \(songs.count)"
        }
    }
    private func request(_ operation: String, _ argument: String, success: @escaping ([String: Any]) -> Void) {
        guard !busy else { return }; busy = true; message = "Загрузка…"
        let credential = token
        DispatchQueue.global(qos: .userInitiated).async {
            let raw: String? = credential.withCString { t in operation.withCString { o in argument.withCString { a in
                guard let p = luma_request(t,o,a) else { return nil }; defer { luma_free(p) }; return String(cString: p)
            } } }
            let reply = raw.flatMap { $0.data(using: .utf8) }.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
            DispatchQueue.main.async {
                busy = false
                guard reply?["ok"] as? Bool == true else { message = reply?["error"] as? String ?? "Ошибка ядра"; return }
                let value = reply?["data"]
                if let rows = value as? [[String: Any]] { success(["tracks": rows]) }
                else if let url = value as? String { success(["url": url]) }
                else { success(value as? [String: Any] ?? [:]) }
            }
        }
    }
}
