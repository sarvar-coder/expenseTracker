import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/insights/insights_screen.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  testWidgets('shows total and legend; switcher changes period', (tester) async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    late List<Category> cats;
    late List<Expense> expenses;
    await tester.runAsync(() async {
      cats = await db.getCategories();
      await db.insertExpense(ExpensesCompanion.insert(
        description: 'Coffee',
        amount: 45000,
        categoryId: cats.firstWhere((c) => c.name == 'Food & dining').id,
        date: DateTime.now(),
        source: ExpenseSource.manual,
      ));
      expenses = await db.getExpenses();
      await db.close();
    });

    await tester.pumpWidget(ProviderScope(
      overrides: [
        categoriesProvider.overrideWith((ref) => Stream.value(cats)),
        expensesProvider.overrideWith((ref) => Stream.value(expenses)),
      ],
      child: const MaterialApp(home: Scaffold(body: InsightsScreen())),
    ));
    await tester.pump();

    expect(find.bySemanticsLabel('Jami 45 000 UZS'), findsOneWidget);
    expect(find.text('Food & dining'), findsOneWidget);

    await tester.tap(find.text('Yil'));
    await tester.pump();
    expect(find.text('Food & dining'), findsOneWidget);
  });
}
