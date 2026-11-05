# Offline UI catalogues

Rust owns locale selection, fallback and packs. Russian source messages act as
gettext-style identifiers in en.json; the ru locale returns those identifiers.
There are 35 selectable languages plus system preference. Region matching follows
the preferred system-language list; unknown languages fall back to English.
Only ar/he/fa are RTL.

English covers application messages. The other 33 JSON packs are **preview
translations**: navigation, playback controls, settings and About labels are
translated, with English fallback for untranslated descriptions and technical
errors. They are not complete or independently reviewed translations. The picker
and About explicitly identify their preview status. Server-provided errors and
music metadata can retain the source language.

The Android UI uses the system's shaping and font fallback. It keeps only the
active catalogue in memory; the app downloads no translation models or fonts.
Track titles, artist names, playlist names, lyrics and licence text bypass
message translation. Format arguments use indexed Java placeholders such as
%1$s; Kotlin source must escape the dollar sign. Rust tests validate catalogue
keys and placeholders.

To improve a translation, add the exact source key and translated value to the
locale JSON; missing keys inherit en.json. Do not translate user data or invent
synchronized lyrics. Desktop UI localization and RTL shaping are separate work;
these 35 UI language options currently apply to Android.
