import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../data/db/database.dart';
import '../data/settings_store.dart';
import '../services/ai_parser.dart';
import '../services/family_service.dart';
import '../services/speech_service.dart';
import '../services/sync_service.dart';

/// Overridden in main() with the resolved instance so downstream reads are sync.
final sharedPrefsProvider = Provider<SharedPreferences>(
  (_) => throw UnimplementedError('sharedPrefsProvider must be overridden in main()'),
);

final authProvider = Provider<GoTrueClient>((_) => Supabase.instance.client.auth);

/// Signed-in user's email, null when signed out. Drives [AuthGate].
final sessionEmailProvider = StreamProvider<String?>((ref) async* {
  final auth = ref.watch(authProvider);
  yield auth.currentUser?.email;
  yield* auth.onAuthStateChange.map((s) => s.session?.user.email);
});

final databaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  ref.onDispose(db.close);
  return db;
});

/// Background Supabase sync; started in main(), read by sign-out to flush.
final syncProvider = Provider<SyncService>((ref) {
  final sync = SyncService(
    ref.watch(databaseProvider),
    Supabase.instance.client,
    ref.watch(sharedPrefsProvider),
  )..start();
  ref.onDispose(sync.dispose);
  return sync;
});

final familyServiceProvider = Provider<FamilyService>(
  (ref) => FamilyService(Supabase.instance.client, ref.watch(syncProvider)),
);

final settingsStoreProvider = Provider<SettingsStore>((ref) => SettingsStore(ref.watch(sharedPrefsProvider)));

/// Reactive settings (budget / locale). Home watches this so budget
/// changes update the dashboard immediately.
final settingsProvider =
    NotifierProvider<SettingsController, Settings>(SettingsController.new);

class SettingsController extends Notifier<Settings> {
  SettingsStore get _store => ref.read(settingsStoreProvider);

  @override
  Settings build() => _store.load();

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

/// Includes archived — Settings > Categories.
final allCategoriesProvider = StreamProvider<List<Category>>(
  (ref) => ref.watch(databaseProvider).watchAllCategories(),
);

final expensesProvider = StreamProvider<List<Expense>>(
  (ref) => ref.watch(databaseProvider).watchExpenses(),
);

/// AI parsing through the `parse-expense` Edge Function (signed-in only).
final aiParserProvider =
    Provider<AiParser>((_) => AiParser(Supabase.instance.client.functions));

/// On-device STT for Speak mode. Overridden in tests with a fake that drives
/// transcripts without a microphone.
final speechServiceProvider = Provider<SpeechService>((ref) => SpeechService());
