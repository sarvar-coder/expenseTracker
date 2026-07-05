import 'package:drift/native.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/providers/providers.dart';

Future<ProviderContainer> makeContainer() async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  return ProviderContainer(overrides: [
    sharedPrefsProvider.overrideWithValue(prefs),
    databaseProvider.overrideWith((ref) {
      final db = AppDatabase.forTesting(NativeDatabase.memory());
      ref.onDispose(db.close);
      return db;
    }),
  ]);
}

void main() {
  test('settings default to UZS / 4 000 000, then persist a change', () async {
    final c = await makeContainer();
    addTearDown(c.dispose);

    final s = c.read(settingsProvider);
    expect(s.currencyCode, 'UZS');
    expect(s.monthlyBudget, 4000000);

    await c.read(settingsProvider.notifier).setBudget(5000000);
    expect(c.read(settingsProvider).monthlyBudget, 5000000);

    // Written through to the underlying store.
    expect(c.read(settingsStoreProvider).load().monthlyBudget, 5000000);
  });

  test('databaseProvider is wired and returns the 5 seeded categories', () async {
    final c = await makeContainer();
    addTearDown(c.dispose);

    final cats = await c.read(databaseProvider).getCategories();
    expect(cats.length, 5);
  });
}
