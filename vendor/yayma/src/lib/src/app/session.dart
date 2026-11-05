import 'package:signals_flutter/signals_flutter.dart';
import 'package:yayma/src/rust/api/models.dart';
import 'package:yayma/src/rust/app/context.dart';

/// Session/auth state signals.
///
/// Lives in `app/` (not under `features/auth/`) so `app/init.dart` can own
/// them without a dependency cycle: `session.dart` imports only signals +
/// Rust DTO types, never `init.dart`.
final FlutterSignal<AsyncState<bool>> authSignal = signal<AsyncState<bool>>(
  const AsyncLoading(),
);
final FlutterSignal<UserAccountDto?> accountSignal = signal<UserAccountDto?>(
  null,
);
final FlutterSignal<AppContext?> appContextSignal = signal<AppContext?>(null);
