# Instant playback path

## Android

A visible track list warms stream URLs for the first two available remote tracks.
The warmup runs off the UI thread and uses the same account token and quality
setting as playback. The Rust core remains the owner of the short-lived,
account-scoped URL cache; signed URLs are not written to disk.

When a track is selected, the playback service waits for an already-running
warmup for that exact track/account/quality, then asks the core for the stream
URL. If warmup failed, was skipped, or expired, the normal playback request
runs as the fallback. Offline files bypass stream warmup entirely.

The same warmup is scheduled for queue transitions, restored queues, and
radio/wave transitions. The existing next-track MediaPlayer preparation
continues to handle gapless-feeling skips when its candidate is ready.

## Device verification

Use a real Android device and the same account for all measurements:

1. Open Search or Library on a stable Wi-Fi connection and wait until the first
   two rows are visible.
2. Tap the first row and measure from tap to audible audio, not to the player
   animation or notification.
3. Repeat after leaving the list open for more than 45 seconds; this checks
   expiry and refresh of signed URLs.
4. Tap the second row, then use Next and Previous while audio is playing.
5. Repeat on mobile data with the configured mobile quality.
6. Repeat with an offline-downloaded track and with the network disconnected.
7. Change account and quality settings, then confirm the previous account's
   warmup is never reused.

A successful CI build verifies compilation, unit tests, APK packaging, and
alignment; it cannot measure audible start latency. Record device model,
Android version, network type, and tap-to-audio timings before setting a
numeric startup target.
