import 'dart:async';

/// A tiny reusable debouncer that collapses rapid successive triggers into
/// a single delayed action.
///
/// Two scheduling modes are supported:
///
/// * [run] — classic debounce: cancels any pending action and schedules
///   `action` after `delay`. Use for search-as-you-type, coalescing rapid
///   state pushes, etc.
/// * [runOnce] — single-shot: schedules `action` after `delay` only when
///   nothing is already pending (preserves `timer ??= Timer(...)` semantics).
///   Use for "show X only if the condition persists for N" delays where
///   re-triggering must NOT restart the countdown.
///
/// Call [cancel] to drop a pending action, or [dispose] for teardown.
class Debouncer {
  Debouncer();

  Timer? _timer;

  /// True while an action is scheduled (including the fire-to-callback window).
  bool get isPending => _timer != null;

  /// Cancels any pending action and schedules [action] after [delay].
  void run(void Function() action, Duration delay) {
    _timer?.cancel();
    _timer = Timer(delay, () {
      _timer = null;
      action();
    });
  }

  /// Schedules [action] after [delay] only if nothing is pending.
  /// Re-triggers while pending are ignored (the countdown is NOT restarted).
  void runOnce(void Function() action, Duration delay) {
    if (isPending) return;
    _timer = Timer(delay, () {
      _timer = null;
      action();
    });
  }

  /// Drops any pending action.
  void cancel() {
    _timer?.cancel();
    _timer = null;
  }

  /// Drops any pending action. Idempotent; safe to call more than once.
  void dispose() => cancel();
}
