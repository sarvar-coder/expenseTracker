import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/db/database.dart';
import '../data/settings_store.dart';
import '../services/ai_parser.dart';
import '../services/speech_service.dart';

/// Overridden in main() with the resolved instance so downstream reads are sync.
final sharedPrefsProvider = Provider<SharedPreferences>(
  (_) => throw UnimplementedError('sharedPrefsProvider must be overridden in main()'),
);

final databaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  ref.onDispose(db.close);
  return db;
});

final settingsStoreProvider = Provider<SettingsStore>((ref) => SettingsStore(
      ref.watch(sharedPrefsProvider),
      const FlutterSecureStorage(),
    ));

/// Reactive settings (currency / budget / locale). Home watches this so budget
/// changes update the dashboard immediately.
final settingsProvider =
    NotifierProvider<SettingsController, Settings>(SettingsController.new);

class SettingsController extends Notifier<Settings> {
  SettingsStore get _store => ref.read(settingsStoreProvider);

  @override
  Settings build() => _store.load();

  Future<void> setCurrency(String v) async {
    await _store.setCurrency(v);
    state = state.copyWith(currencyCode: v);
  }

  Future<void> setBudget(int v) async {
    await _store.setBudget(v);
    state = state.copyWith(monthlyBudget: v);
  }

  Future<void> setLocale(String v) async {
    await _store.setLocale(v);
    state = state.copyWith(sttLocale: v);
  }
}

/// Selected bottom-nav tab, so screens (e.g. Home "See all") can switch tabs.
final tabIndexProvider =
    NotifierProvider<TabIndexController, int>(TabIndexController.new);

class TabIndexController extends Notifier<int> {
  @override
  int build() => 0;
  void set(int i) => state = i;
}

final categoriesProvider = StreamProvider<List<Category>>(
  (ref) => ref.watch(databaseProvider).watchCategories(),
);

final expensesProvider = StreamProvider<List<Expense>>(
  (ref) => ref.watch(databaseProvider).watchExpenses(),
);

/// Builds a Gemini parser from the stored key (or `--dart-define=GEMINI_API_KEY`),
/// resolving to null when no key is configured. A plain Provider returning an
/// async factory — avoids `.future` and stays trivially overridable in tests.
final aiParserProvider = Provider<Future<AiParser?> Function()>((ref) {
  final store = ref.watch(settingsStoreProvider);
  return () async {
    final stored = await store.getApiKey();
    final key = (stored != null && stored.isNotEmpty)
        ? stored
        : const String.fromEnvironment('GEMINI_API_KEY');
    return key.isEmpty ? null : AiParser.gemini(key);
  };
});

/// On-device STT for Speak mode. Overridden in tests with a fake that drives
/// transcripts without a microphone.
final speechServiceProvider = Provider<SpeechService>((ref) => SpeechService());
