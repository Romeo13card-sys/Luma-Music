use crate::app::{App, Screen};
use crate::player::draw_tracks;
use eframe::egui;

pub(crate) struct ScreenActions {
    pub(crate) request: Option<(String, String)>,
    pub(crate) selected_track: Option<usize>,
    pub(crate) play_queue: bool,
}

impl App {
    pub(crate) fn draw_screen(&mut self, ctx: &egui::Context) -> ScreenActions {
        let mut request: Option<(String, String)> = None;
        let mut selected_track: Option<usize> = None;
        let mut play_queue = false;

        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical()
                .auto_shrink([false, false])
                .show(ui, |ui| {
                    ui.add_space(12.0);
                    match self.screen {
                        Screen::Home => {
                            ui.label(
                                egui::RichText::new("ВАША МУЗЫКАЛЬНАЯ СРЕДА")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.add_space(4.0);
                            ui.heading(
                                egui::RichText::new("Что послушаем сегодня?")
                                    .size(30.0)
                                    .strong(),
                            );
                            ui.label(
                                "Подборки, любимые треки и музыка, которую хочется открыть заново.",
                            );
                            ui.add_space(18.0);
                            egui::Frame::group(ui.style())
                                .fill(ui.visuals().window_fill)
                                .stroke(egui::Stroke::new(
                                    1.0,
                                    ui.visuals().widgets.noninteractive.bg_stroke.color,
                                ))
                                .inner_margin(egui::Margin::same(20.0))
                                .show(ui, |ui| {
                                    ui.label(
                                        egui::RichText::new("LUMA · PERSONAL RADIO")
                                            .small()
                                            .strong()
                                            .color(egui::Color32::from_rgb(255, 190, 45)),
                                    );
                                    ui.add_space(4.0);
                                    ui.heading(
                                        egui::RichText::new("Моя волна").size(26.0).strong(),
                                    );
                                    ui.label("Персональная музыкальная подборка из Яндекс Музыки.");
                                    ui.add_space(10.0);
                                    if ui
                                        .add_enabled(
                                            self.connected && !self.jobs.busy(),
                                            egui::Button::new("Слушать волну"),
                                        )
                                        .clicked()
                                    {
                                        request = Some(("wave".into(), String::new()));
                                        self.screen = Screen::Wave;
                                    }
                                });
                            ui.add_space(14.0);
                            ui.heading("Быстрый доступ");
                            ui.horizontal_wrapped(|ui| {
                                if ui.button("♡   Избранное").clicked() {
                                    request = Some(("likes".into(), String::new()));
                                    self.screen = Screen::Library;
                                }
                                if ui.button("⌕   Найти музыку").clicked() {
                                    self.screen = Screen::Search;
                                }
                                if ui.button("≡   Очередь").clicked() {
                                    self.screen = Screen::Queue;
                                }
                            });
                            if !self.tracks.is_empty() {
                                ui.add_space(18.0);
                                ui.heading("Недавно открыто");
                                let current = self
                                    .queue
                                    .get(self.index.unwrap_or(usize::MAX))
                                    .and_then(|t| t["id"].as_str());
                                draw_tracks(
                                    ui,
                                    &self.tracks,
                                    current,
                                    !self.jobs.busy(),
                                    &mut selected_track,
                                );
                            }
                        }
                        Screen::Search => {
                            ui.label(
                                egui::RichText::new("КАТАЛОГ")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Поиск музыки").size(28.0).strong());
                            ui.label("Найдите трек или исполнителя по названию.");
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                let response = ui.add(
                                    egui::TextEdit::singleline(&mut self.query)
                                        .hint_text("Название трека или исполнитель")
                                        .desired_width(ui.available_width() - 110.0),
                                );
                                let enter = response.lost_focus()
                                    && ui.input(|i| i.key_pressed(egui::Key::Enter));
                                if ui
                                    .add_enabled(!self.jobs.busy(), egui::Button::new("Найти"))
                                    .clicked()
                                    || enter
                                {
                                    request = Some(("search".into(), self.query.clone()));
                                }
                            });
                            ui.add_space(12.0);
                            ui.heading("Результаты");
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.tracks,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                        }
                        Screen::Library => {
                            ui.label(
                                egui::RichText::new("ВАША МУЗЫКА")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Коллекция").size(28.0).strong());
                            ui.label("Избранные композиции вашего аккаунта.");
                            ui.add_space(10.0);
                            if ui
                                .add_enabled(
                                    self.connected && !self.jobs.busy(),
                                    egui::Button::new("Обновить избранное"),
                                )
                                .clicked()
                            {
                                request = Some(("likes".into(), String::new()));
                            }
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.tracks,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                        }
                        Screen::Wave => {
                            ui.label(
                                egui::RichText::new("ПЕРСОНАЛЬНАЯ ПОДБОРКА")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Моя волна").size(28.0).strong());
                            ui.label("Получите новую партию рекомендаций.");
                            ui.add_space(10.0);
                            if ui
                                .add_enabled(
                                    self.connected && !self.jobs.busy(),
                                    egui::Button::new("Обновить волну"),
                                )
                                .clicked()
                            {
                                request = Some(("wave".into(), String::new()));
                            }
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.tracks,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                        }
                        Screen::Queue => {
                            ui.label(
                                egui::RichText::new("ВОСПРОИЗВЕДЕНИЕ")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Очередь").size(28.0).strong());
                            ui.label(format!("Композиции в текущей сессии: {}", self.queue.len()));
                            let current = self
                                .queue
                                .get(self.index.unwrap_or(usize::MAX))
                                .and_then(|t| t["id"].as_str());
                            draw_tracks(
                                ui,
                                &self.queue,
                                current,
                                !self.jobs.busy(),
                                &mut selected_track,
                            );
                            play_queue = true;
                        }
                        Screen::Settings => {
                            ui.label(
                                egui::RichText::new("ПЕРСОНАЛИЗАЦИЯ")
                                    .small()
                                    .strong()
                                    .color(egui::Color32::from_rgb(255, 190, 45)),
                            );
                            ui.heading(egui::RichText::new("Настройки").size(28.0).strong());
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                ui.label("Качество следующего трека");
                                egui::ComboBox::from_id_source("quality")
                                    .selected_text(format!("{} кбит/с", self.quality))
                                    .show_ui(ui, |ui| {
                                        for quality in [128, 192, 320] {
                                            ui.selectable_value(
                                                &mut self.quality,
                                                quality,
                                                format!("{quality} кбит/с"),
                                            );
                                        }
                                    });
                            });
                            ui.add(
                                egui::Slider::new(&mut self.volume, 0.0..=1.0)
                                    .text("Громкость")
                                    .show_value(true),
                            );
                            if let Some(player) = &self.player {
                                player.sink.set_volume(self.volume);
                            }
                            ui.checkbox(
                                &mut self.autoplay,
                                "Автоматически включать следующий трек",
                            );
                            ui.checkbox(&mut self.repeat, "Повторять текущий трек");
                            if ui.checkbox(&mut self.dark, "Тёмная тема").changed() {
                                self.status = if self.dark {
                                    "Включена тёмная тема"
                                } else {
                                    "Включена светлая тема"
                                }
                                .into();
                            }
                            ui.add_space(12.0);
                            ui.hyperlink_to(
                                "GitHub Releases",
                                "https://github.com/Romeo13card-sys/Luma-Music/releases",
                            );
                            ui.hyperlink_to(
                                "Исходный код и лицензия GPL-3.0",
                                "https://github.com/Romeo13card-sys/Luma-Music",
                            );
                        }
                    }
                });
        });

        ScreenActions {
            request,
            selected_track,
            play_queue,
        }
    }
}
