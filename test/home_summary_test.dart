import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart' show Expense;
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/home/home_summary.dart';

Expense _exp(int id, int amount, int catId, DateTime date) => Expense(
      id: id,
      description: 'x',
      amount: amount,
      categoryId: catId,
      date: date,
      source: ExpenseSource.manual,
      createdAt: date,
    );

void main() {
  test('summarize sums current month against budget', () {
    final now = DateTime.now();
    final thisMonth = DateTime(now.year, now.month, 10);
    final lastMonth = DateTime(now.year, now.month - 1, 15);

    final s = summarize(
      [
        _exp(1, 45000, 1, thisMonth),
        _exp(2, 30000, 1, thisMonth),
        _exp(3, 20000, 2, thisMonth),
        _exp(4, 99000, 1, lastMonth), // excluded: prior month
      ],
      4000000,
      now,
    );

    expect(s.spent, 95000); // 45k+30k+20k, last month excluded
    expect(s.budget, 4000000);
    expect(s.remaining, 3905000);
  });

  test('todayExpenses keeps local today only, newest first', () {
    final now = DateTime(2026, 9, 26, 15);
    final t = todayExpenses([
      _exp(1, 1, 1, DateTime(2026, 9, 26, 0, 0)), // midnight: in
      _exp(2, 1, 1, DateTime(2026, 9, 25, 23, 59)), // yesterday: out
      _exp(3, 1, 1, DateTime(2026, 9, 26, 12)),
      _exp(4, 1, 1, DateTime(2026, 9, 27, 0, 0)), // tomorrow: out
    ], now);
    expect(t.map((e) => e.id), [3, 1]);
  });
}
