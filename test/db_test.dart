import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';

void main() {
  late AppDatabase db;

  setUp(() => db = AppDatabase.forTesting(NativeDatabase.memory()));
  tearDown(() => db.close());

  test('seeds 5 default categories on first open', () async {
    final cats = await db.getCategories();
    expect(cats.length, 5);
    expect(cats.map((c) => c.name), contains('Food & dining'));
  });

  test('insert expense, read back, and aggregate totals', () async {
    final cats = await db.getCategories();
    final food = cats.firstWhere((c) => c.name == 'Food & dining');

    await db.insertExpense(ExpensesCompanion.insert(
      description: 'Coffee',
      amount: 45000,
      categoryId: food.id,
      date: DateTime(2026, 7, 5),
      source: ExpenseSource.typed,
      rawInput: const Value('Coffee, 45 000'),
    ));
    await db.insertExpense(ExpensesCompanion.insert(
      description: 'Lunch',
      amount: 30000,
      categoryId: food.id,
      date: DateTime(2026, 7, 6),
      source: ExpenseSource.manual,
    ));

    final all = await db.watchExpenses().first;
    expect(all.length, 2);

    final start = DateTime(2026, 7, 1);
    final end = DateTime(2026, 8, 1);
    expect(await db.totalSpent(start, end), 75000);

    final byCat = await db.categoryTotals(start, end);
    expect(byCat[food.id], 75000);

    // A July-only window excludes the August boundary.
    expect(await db.totalSpent(start, DateTime(2026, 7, 6)), 45000);
  });
}
