import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/home/home_screen.dart';
import 'package:expense_tracker/providers/providers.dart';

Expense _exp(int id, String d, int amount, DateTime date) => Expense(
      id: id,
      description: d,
      amount: amount,
      categoryId: 1,
      date: date,
      source: ExpenseSource.manual,
      createdAt: date,
    );

void main() {
  Future<void> pump(WidgetTester tester, List<Expense> expenses,
      {int budget = 0}) async {
    SharedPreferences.setMockInitialValues(
        {if (budget > 0) 'monthlyBudget': budget});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        categoriesProvider.overrideWith((ref) => Stream.value(const [
              Category(id: 1, name: 'Food', iconKey: 'x', colorHex: 'E08A5B', isArchived: false),
            ])),
        expensesProvider.overrideWith((ref) => Stream.value(expenses)),
      ],
      child: const MaterialApp(home: Scaffold(body: HomeScreen())),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('empty day shows empty state and budget hint', (tester) async {
    await pump(tester, const []);
    expect(find.text('Bugun hali xarajat yo\'q'), findsOneWidget);
    expect(find.textContaining('byudjet belgilang'), findsOneWidget);
    expect(find.byTooltip('Sozlamalar'), findsOneWidget);
  });

  testWidgets('lists only today, shows month line', (tester) async {
    final now = DateTime.now();
    await pump(tester, [
      _exp(1, 'Coffee', 45000, now),
      _exp(2, 'Old taxi', 30000, now.subtract(const Duration(days: 40))),
    ], budget: 1000000);
    expect(find.text('Coffee'), findsOneWidget);
    expect(find.text('Old taxi'), findsNothing);
    expect(find.text('Bugun hali xarajat yo\'q'), findsNothing);
    expect(find.textContaining('Oy: 45 000 / 1 000 000'), findsOneWidget);
  });
}
