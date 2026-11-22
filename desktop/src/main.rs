#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
pub(crate) fn app_version() -> &'static str {
    let v = env!("CARGO_PKG_VERSION");
    if v.contains('-') {
        v
    } else {
        v.strip_suffix(".0").unwrap_or(v)
    }
}
mod app;
mod engine;
use app::{App, Screen};
use eframe::egui;
use serde_json::json;
use std::time::Duration;
mod player;
mod screens;
use player::Reply;

fn navigation_button(ui: &mut egui::Ui, selected: bool, label: &str) -> egui::Response {
    let accent = egui::Color32::from_rgb(255, 216, 77);
    let ink = egui::Color32::from_rgb(24, 27, 32);
    let text_color = if selected { ink } else { ui.visuals().text_color() };
    let fill = if selected { accent } else { egui::Color32::TRANSPARENT };
    let stroke = if selected {
        egui::Stroke::new(1.0, accent)
    } else {
        egui::Stroke::NONE
    };
    ui.add_sized(
        [ui.available_width(), 40.0],
        egui::Button::new(egui::RichText::new(label).color(text_color))
            .fill(fill)
            .stroke(stroke),
    )
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if let Some(deadline) = self.smoke_until {
            if std::time::Instant::now() >= deadline {
                ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                return;
            }
            ctx.request_repaint_after(Duration::from_millis(250));
        }

        self.consume();

        let mut visuals = if self.dark {
            egui::Visuals::dark()
        } else {
            egui::Visuals::light()
        };
        visuals.selection.bg_fill = egui::Color32::from_rgb(255, 216, 77);
        visuals.selection.stroke = egui::Stroke::new(1.0, egui::Color32::from_rgb(255, 216, 77));
        if self.dark {
            visuals.panel_fill = egui::Color32::from_rgb(11, 14, 18);
            visuals.window_fill = egui::Color32::from_rgb(20, 25, 33);
            visuals.extreme_bg_color = egui::Color32::from_rgb(8, 10, 14);
            visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(29, 35, 45);
            visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(42, 51, 64);
            visuals.widgets.active.bg_fill = egui::Color32::from_rgb(255, 216, 77);
        } else {
            visuals.panel_fill = egui::Color32::from_rgb(244, 246, 250);
            visuals.window_fill = egui::Color32::from_rgb(255, 255, 255);
            visuals.extreme_bg_color = egui::Color32::from_rgb(235, 239, 245);
            visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(232, 237, 244);
            visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(220, 227, 237);
            visuals.widgets.active.bg_fill = egui::Color32::from_rgb(255, 216, 77);
        }
        ctx.set_visuals(visuals);
        ctx.style_mut(|style| {
            style.spacing.item_spacing = egui::vec2(10.0, 10.0);
            style.spacing.button_padding = egui::vec2(14.0, 10.0);
            style.spacing.interact_size.y = 42.0;
            style.visuals.widgets.noninteractive.rounding = egui::Rounding::same(14.0);
            style.visuals.widgets.inactive.rounding = egui::Rounding::same(14.0);
            style.visuals.widgets.hovered.rounding = egui::Rounding::same(14.0);
            style.visuals.widgets.active.rounding = egui::Rounding::same(14.0);
        });

        let mut request: Option<(String, String)> = None;
        let mut selected_track: Option<usize> = None;
        let mut seek_to: Option<f64> = None;
        let mut play_queue = false;

        egui::TopBottomPanel::top("luma_status")
            .exact_height(58.0)
            .show(ctx, |ui| {
                ui.add_space(5.0);
                ui.horizontal(|ui| {
                    ui.label(
                        egui::RichText::new(if self.connected {
                            "● ONLINE"
                        } else {
                            "○ OFFLINE"
                        })
                        .small()
                        .strong()
                        .color(if self.connected {
                            egui::Color32::from_rgb(255, 216, 77)
                        } else {
                            ui.visuals().weak_text_color()
                        }),
                    );
                    ui.separator();
                    let search_width = ui.available_width().min(390.0);
                    let search = ui.add_sized(
                        [search_width, 34.0],
                        egui::TextEdit::singleline(&mut self.query)
                            .hint_text("Найти трек, альбом или исполнителя…"),
                    );
                    let enter = search.lost_focus()
                        && ui.input(|input| input.key_pressed(egui::Key::Enter));
                    if ui
                        .add_sized([76.0, 34.0], egui::Button::new("Найти"))
                        .clicked()
                        || enter
                    {
                        if !self.query.trim().is_empty() && !self.jobs.busy() {
                            self.screen = Screen::Search;
                            request = Some(("search".into(), self.query.trim().to_owned()));
                        }
                    }
                    ui.separator();
                    ui.label(egui::RichText::new(&self.status).small().weak());
                    if self.jobs.busy() {
                        ui.spinner();
                        if self.downloaded > 0 {
                            ui.label(format!("{:.1} МиБ", self.downloaded as f64 / 1048576.0));
                        }
                        if ui.small_button("Отменить").clicked() {
                            self.jobs.cancel();
                            self.status = "Отменяем операцию…".into();
                        }
                    }
                });
            });

        egui::SidePanel::left("luma_navigation")
            .resizable(false)
            .default_width(238.0)
            .show(ctx, |ui| {
                ui.add_space(16.0);
                ui.horizontal(|ui| {
                    ui.label(
                        egui::RichText::new("L")
                            .size(30.0)
                            .strong()
                            .color(egui::Color32::from_rgb(255, 216, 77)),
                    );
                    ui.vertical(|ui| {
                        ui.heading(egui::RichText::new("LUMA MUSIC").strong());
                        ui.label(egui::RichText::new("YOUR SOUND. YOUR FLOW.").small().weak());
                    });
                });
                ui.add_space(12.0);
                ui.separator();
                ui.add_space(8.0);

                ui.label(egui::RichText::new("МУЗЫКА").small().weak().strong());
                if navigation_button(ui, self.screen == Screen::Home, "⌂   Главная")
                    .clicked()
                {
                    self.screen = Screen::Home;
                }
                if navigation_button(ui, self.screen == Screen::Search, "⌕   Поиск")
                    .clicked()
                {
                    self.screen = Screen::Search;
                }
                if navigation_button(ui, self.screen == Screen::Library, "♡   Коллекция")
                    .clicked()
                {
                    self.screen = Screen::Library;
                    if self.connected && !self.jobs.busy() {
                        self.tracks.clear();
                        request = Some(("likes".into(), String::new()));
                    }
                }
                if navigation_button(ui, self.screen == Screen::Wave, "✦   Моя волна")
                    .clicked()
                {
                    self.screen = Screen::Wave;
                }
                if navigation_button(ui, self.screen == Screen::Queue, "≡   Очередь")
                    .clicked()
                {
                    self.screen = Screen::Queue;
                }

                ui.add_space(14.0);
                ui.separator();
                ui.add_space(10.0);
                ui.label(egui::RichText::new("АККАУНТ").small().weak().strong());
                ui.add(
                    egui::TextEdit::singleline(&mut self.token)
                        .password(true)
                        .hint_text("OAuth-токен Яндекса")
                        .desired_width(f32::INFINITY),
                );
                if ui
                    .add_enabled(
                        !self.jobs.busy() && !self.token.trim().is_empty(),
                        egui::Button::new("Подключить"),
                    )
                    .clicked()
                {
                    self.connected = false;
                    self.tracks.clear();
                    self.stop();
                    request = Some(("account".into(), String::new()));
                }
                if ui
                    .add_enabled(
                        !self.jobs.busy() && self.connected,
                        egui::Button::new("Выйти"),
                    )
                    .clicked()
                {
                    self.connected = false;
                    self.token.clear();
                    self.tracks.clear();
                    self.stop();
                    self.status = "Аккаунт отключён".into();
                }

                ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                    if navigation_button(ui, self.screen == Screen::Settings, "⚙   Настройки")
                        .clicked()
                    {
                        self.screen = Screen::Settings;
                    }
                    ui.label(
                        egui::RichText::new(format!("Luma Music · {}", app_version()))
                            .small()
                            .weak(),
                    );
                });
            });

        let screen_actions = self.draw_screen(ctx);
        if screen_actions.request.is_some() {
            request = screen_actions.request;
        }
        selected_track = screen_actions.selected_track;
        play_queue = screen_actions.play_queue;
        if screen_actions.check_updates {
            self.check_updates(ctx.clone());
        }
        if screen_actions.download_update {
            self.download_update(ctx.clone());
        }
        if screen_actions.install_update {
            match self.install_update() {
                Ok(()) => ctx.send_viewport_cmd(egui::ViewportCommand::Close),
                Err(error) => {
                    self.download_status = format!("Не удалось запустить установку: {error}")
                }
            }
        }

        egui::TopBottomPanel::bottom("luma_player")
            .resizable(false)
            .show(ctx, |ui| {
                egui::Frame::group(ui.style())
                    .fill(ui.visuals().window_fill)
                    .inner_margin(egui::Margin::same(10.0))
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            egui::Frame::group(ui.style())
                                .fill(egui::Color32::from_rgb(255, 216, 77))
                                .inner_margin(egui::Margin::same(9.0))
                                .show(ui, |ui| {
                                    ui.label(
                                        egui::RichText::new("♫")
                                            .size(25.0)
                                            .strong()
                                            .color(egui::Color32::from_rgb(24, 27, 32)),
                                    );
                                });
                            ui.vertical(|ui| {
                                ui.label(
                                    egui::RichText::new("NOW PLAYING")
                                        .small()
                                        .strong()
                                        .color(egui::Color32::from_rgb(255, 190, 45)),
                                );
                                ui.label(
                                    egui::RichText::new(if self.title.is_empty() {
                                        "Выберите музыку"
                                    } else {
                                        &self.title
                                    })
                                    .strong(),
                                );
                                if let Some(track) =
                                    self.queue.get(self.index.unwrap_or(usize::MAX))
                                {
                                    ui.label(
                                        egui::RichText::new(
                                            track["artists"].as_str().unwrap_or(""),
                                        )
                                        .small()
                                        .weak(),
                                    );
                                }
                            });
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    if ui.button("⏭").clicked() {
                                        self.adjacent(1);
                                    }
                                    let playing = self
                                        .player
                                        .as_ref()
                                        .is_some_and(|p| !p.sink.is_paused() && !p.sink.empty());
                                    if ui
                                        .add(
                                            egui::Button::new(
                                                egui::RichText::new(if playing {
                                                    "Ⅱ"
                                                } else {
                                                    "▶"
                                                })
                                                .size(19.0)
                                                .strong()
                                                .color(egui::Color32::from_rgb(24, 27, 32)),
                                            )
                                            .fill(egui::Color32::from_rgb(255, 216, 77)),
                                        )
                                        .clicked()
                                    {
                                        if let Some(player) = &self.player {
                                            if player.sink.is_paused() {
                                                player.sink.play();
                                            } else {
                                                player.sink.pause();
                                            }
                                        }
                                    }
                                    if ui.button("⏮").clicked() {
                                        self.adjacent(-1);
                                    }
                                    if ui.button("■").clicked() {
                                        self.jobs.cancel();
                                        self.stop();
                                    }
                                },
                            );
                        });
                        if let (Some(player), Some(duration)) =
                            (&self.player, self.duration.filter(|d| !d.is_zero()))
                        {
                            let mut seconds = player
                                .sink
                                .get_pos()
                                .as_secs_f64()
                                .min(duration.as_secs_f64());
                            let original_seconds = seconds;
                            let elapsed = seconds as u64;
                            let total = duration.as_secs();
                            ui.horizontal(|ui| {
                                ui.label(
                                    egui::RichText::new(format!(
                                        "{:02}:{:02}",
                                        elapsed / 60,
                                        elapsed % 60
                                    ))
                                    .small()
                                    .weak(),
                                );
                                ui.add(
                                    egui::Slider::new(&mut seconds, 0.0..=duration.as_secs_f64())
                                        .show_value(false)
                                        .text(""),
                                );
                                ui.label(
                                    egui::RichText::new(format!(
                                        "{:02}:{:02}",
                                        total / 60,
                                        total % 60
                                    ))
                                    .small()
                                    .weak(),
                                );
                            });
                            if seconds != original_seconds {
                                seek_to = Some(seconds);
                            }
                        }
                    });
            });

        if let Some((operation, argument)) = request {
            self.request(&operation, argument);
        }
        if let Some(index) = selected_track {
            if play_queue {
                self.play(index);
            } else {
                self.play_from(self.tracks.clone(), index);
            }
        }
        if let Some(seconds) = seek_to {
            if let Some(player) = &self.player {
                if player
                    .sink
                    .try_seek(Duration::from_secs_f64(seconds))
                    .is_err()
                {
                    self.status = "Перемотка этого файла недоступна".into();
                }
            }
        }

        if self.jobs.busy()
            || self.waiting_end
            || self
                .player
                .as_ref()
                .is_some_and(|p| !p.sink.is_paused() && !p.sink.empty())
        {
            ctx.request_repaint_after(Duration::from_millis(250));
        }
    }
}

fn main() -> eframe::Result {
    if std::env::args().any(|a| a == "--self-check") {
        println!(
            "{}",
            json!({"version":app_version(),"os":std::env::consts::OS,"arch":std::env::consts::ARCH,"legacy_windows":cfg!(all(windows,target_vendor="win7"))})
        );
        return Ok(());
    }
    let smoke = std::env::args().any(|arg| arg == "--ui-smoke");
    eframe::run_native(
        "Luma Music",
        eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default()
                .with_inner_size([1100.0, 760.0])
                .with_min_inner_size([680.0, 480.0])
                .with_icon(
                    eframe::icon_data::from_png_bytes(include_bytes!(
                        "../../assets/branding/luma-icon.png"
                    ))
                    .expect("Luma icon"),
                ),
            ..Default::default()
        },
        Box::new(move |cc| {
            cc.egui_ctx.set_visuals(egui::Visuals::dark());
            let mut app = App::new();
            if smoke {
                app.smoke_until = Some(std::time::Instant::now() + Duration::from_secs(3));
            }
            Ok(Box::new(app))
        }),
    )
}

#[cfg(test)]
mod state_tests {
    use super::*;
    #[test]
    fn browsing_keeps_playback_queue_and_stale_reply_cannot_unlock_current_job() {
        let mut app = App::new();
        app.queue = vec![json!({"id":"old","available":true})];
        app.index = Some(0);
        app.title = "Old".into();
        let first = app.jobs.begin().unwrap();
        app.tx
            .send(Reply::Data(
                first.id,
                "search".into(),
                Ok(json!([{"id":"new"}])),
            ))
            .unwrap();
        app.consume();
        assert_eq!(app.tracks[0]["id"], "new");
        assert_eq!(app.queue[0]["id"], "old");
        assert_eq!(app.title, "Old");
        let second = app.jobs.begin().unwrap();
        app.tx
            .send(Reply::Data(
                first.id,
                "account".into(),
                Ok(json!({"name":"stale"})),
            ))
            .unwrap();
        app.consume();
        assert!(!app.connected);
        assert!(app.jobs.matches(second.id));
        app.jobs.cancel();
        app.tx
            .send(Reply::Data(
                second.id,
                "account".into(),
                Ok(json!({"name":"cancelled"})),
            ))
            .unwrap();
        app.consume();
        assert!(!app.connected);
    }
}
