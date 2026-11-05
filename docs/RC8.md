# Luma Music 0.5.0-rc.8

Android versionCode 17, org.luma.music, Android 11+ / API 30–36. Same persistent
family signing identity as RC7. Candidate APKs keep the Luma-Music version/ABI names.

## Lyrics

Separate native screen with active-line and start-tag-based word highlighting.
Plain lyrics remain plain. ListView recycles rows; a Rust cursor returns only
the selected line/word counts. One bounded native document, maximum 256 KiB of
source, 1000 lines, 4096 bytes per line and 10000 timed words. No proportional
allocation based on device RAM.

Tap a timed line to seek when duration is available. Dragging the list disables
following; Current line restores it. Track changes clear the previous document,
requests/results carry generation and track identity, and rotation reuses the
current Rust document. Background UI polling stops; normal/economy sync intervals
are 250/500 ms, with a 1-second track check for plain text. Only one shared worker
and one pending task are allowed. Closing skips requests that have not started;
an in-flight network request can finish within its 10-second timeout.

Explicit LrcLib consent covers titles/artists of tracks while that screen is
open; OAuth credentials are never sent. Actual availability/timings depend on
LrcLib. UTF-8 LRC/TXT import reads a user-selected file without contacting LrcLib;
no lyrics or file URI is stored in SQLite. Basic/Enhanced LRC is supported,
including integer-second tags. Invalid word ordering falls back to line sync.
Advanced offset/multiple-prefix LRC metadata is not claimed.

## Languages

35 language choices and system preference, stored by Rust/SQLite. Android contexts
apply the selected locale and RTL for Arabic, Hebrew and Persian. English is the
fallback; Russian source messages remain the default for Russian devices.

The other 33 language packs are **preliminary and partial**, clearly marked in the
picker and About. They translate navigation, player controls, settings and About
labels; some descriptions/technical errors remain English. Music metadata and
lyrics are not translated. See locales/README.md. These 35 UI language options
currently apply to Android; the desktop UI retains its existing language.

## About

App version/build/package, selected and effective theme, language, phone model,
Android/API/ABI, physical RAM and the selected interface profile. Links to releases,
issues, source, platform notes, GPL, Telegram and donations. The author credit
remains made by Romeo13card; YAYMA/YaMusic credits and GPL are retained.

The copy-diagnostics action passes a fixed field allowlist to Rust. It excludes
arbitrary logs, account identifiers, tokens, audio URLs and file paths.

## Validation

Native unit tests cover timeline seeking/word bounds/stale handles, catalogue
fallback/format arguments, locale persistence and diagnostic filtering. Android
integration uses a fresh Android 11 emulator and a signed RC7 → RC8 upgrade. The
instrumentation suite exercises file import, actual MediaPlayer seeking, cached
rotation, clearing old track text, background timer removal, 35 catalogue/font
smokes, RTL and About. Successful completion is required before reporting that
suite as passed; source presence alone is not a test result.

LrcLib/account playback and physical-device coverage remain separate from
offline fixtures/emulator checks. A Windows/Linux/macOS build does not establish
new Android-feature parity or verified legacy-OS support.
