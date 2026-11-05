import 'package:signals_flutter/signals_flutter.dart';
import 'package:yayma/src/app/session.dart';
import 'package:yayma/src/features/core/services/debouncer.dart';
import 'package:yayma/src/rust/api/content.dart';
import 'package:yayma/src/rust/api/models.dart';

final FlutterSignal<String> searchQuerySignal = signal<String>('');

/// Search results signal (automatically updates when searchQuerySignal changes)
final FutureSignal<SearchResultsDto?> searchResultsSignal =
    futureSignal<SearchResultsDto?>(() async {
      final query = searchQuerySignal.value;
      if (query.trim().isEmpty) return null;

      final ctx = appContextSignal.value;
      if (ctx == null) return null;

      return await search(ctx: ctx, query: query);
    });

final Debouncer _searchDebouncer = Debouncer();

void cancelSearchDebounce() {
  _searchDebouncer.cancel();
}

void setSearchQuery(String query) {
  _searchDebouncer.run(() {
    searchQuerySignal.value = query;
  }, const Duration(milliseconds: 300));
}

Future<void> disposeSearch() async {
  _searchDebouncer.dispose();
  searchQuerySignal.value = '';
}
