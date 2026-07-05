import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/db/database.dart';
import '../data/settings_store.dart';

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

final categoriesProvider = StreamProvider<List<Category>>(
  (ref) => ref.watch(databaseProvider).watchCategories(),
);

final expensesProvider = StreamProvider<List<Expense>>(
  (ref) => ref.watch(databaseProvider).watchExpenses(),
);
