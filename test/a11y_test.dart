import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/main.dart';
import 'package:expense_tracker/providers/providers.dart';

/// Every screen, light and dark: 48dp targets, labeled targets, AA text contrast.
void main() {
  late List<Category> cats;
  late List<Expense> expenses;

  setUpAll(() async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    cats = await db.getCategories();
    for (final (d, i, amt) in [('Coffee', 0, 45000), ('Taxi', 3, 30000)]) {
      await db.insertExpense(ExpensesCompanion.insert(
        description: d,
        amount: amt,
        categoryId: cats[i].id,
        date: DateTime.now(),
        source: ExpenseSource.manual,
      ));
    }
    expenses = await db.getExpenses();
    await db.close();
  });

  // Each screen is reached from the shell the way a user would.
  final screens = <String, Future<void> Function(WidgetTester)>{
    'Home': (_) async {},
    'Activity': (t) => t.tap(find.text('Tarix')),
    'Insights': (t) => t.tap(find.text('Tahlil')),
    'Add type': (t) => t.tap(find.byIcon(Icons.add)),
    'Add speak': (t) async {
      await t.tap(find.byIcon(Icons.add));
      await t.pumpAndSettle();
      await t.tap(find.text('Aytish'));
    },
    'Add manual': (t) async {
      await t.tap(find.byIcon(Icons.add));
      await t.pumpAndSettle();
      await t.tap(find.text('Qo\'lda'));
    },
    'Settings': (t) => t.tap(find.byTooltip('Sozlamalar')),
    'Categories': (t) async {
      await t.tap(find.byTooltip('Sozlamalar'));
      await t.pumpAndSettle();
      await t.tap(find.text('Turkumlar'));
    },
  };

  for (final brightness in Brightness.values) {
    for (final MapEntry(key: name, value: open) in screens.entries) {
      testWidgets('$name meets a11y guidelines (${brightness.name})', (tester) async {
        tester.platformDispatcher.platformBrightnessTestValue = brightness;
        addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
        SharedPreferences.setMockInitialValues({});
        final prefs = await SharedPreferences.getInstance();
        final handle = tester.ensureSemantics();

        await tester.pumpWidget(ProviderScope(
          overrides: [
            sharedPrefsProvider.overrideWithValue(prefs),
        sessionEmailProvider.overrideWith((ref) => Stream.value('me@oila.uz')),
            categoriesProvider.overrideWith((ref) => Stream.value(cats)),
            allCategoriesProvider.overrideWith((ref) => Stream.value(cats)),
            expensesProvider.overrideWith((ref) => Stream.value(expenses)),
          ],
          child: const ExpenseTrackerApp(),
        ));
        await tester.pumpAndSettle();
        await open(tester);
        await tester.pumpAndSettle();

        await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
        await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
        await expectLater(tester, meetsGuideline(textContrastGuideline));
        handle.dispose();
      });
    }
  }
}
