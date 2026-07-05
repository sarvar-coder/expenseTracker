import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/app/theme.dart';
import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/home/home_screen.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  testWidgets('Home shows month spent, budget, and category totals', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);

    final cats = await db.getCategories();
    final food = cats.firstWhere((c) => c.name == 'Food & dining');
    final now = DateTime.now();
    await db.insertExpense(ExpensesCompanion.insert(
      description: 'Coffee', amount: 45000, categoryId: food.id,
      date: DateTime(now.year, now.month, 5), source: ExpenseSource.typed,
    ));
    await db.insertExpense(ExpensesCompanion.insert(
      description: 'Lunch', amount: 30000, categoryId: food.id,
      date: DateTime(now.year, now.month, 6), source: ExpenseSource.manual,
      rawInput: const Value(null),
    ));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        databaseProvider.overrideWithValue(db),
      ],
      child: MaterialApp(theme: buildTheme(), home: const Scaffold(body: HomeScreen())),
    ));
    // Let the drift streams emit; avoid pumpAndSettle (progress bars animate).
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 60));
    }

    expect(find.text('75 000'), findsNWidgets(2)); // hero total + Food category row
    expect(find.textContaining('4 000 000'), findsWidgets); // default budget in hero footer
    expect(find.text('Food & dining'), findsOneWidget);

    // Dispose the tree, then pump once so drift's stream-cleanup timer fires.
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
}
