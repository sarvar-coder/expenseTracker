import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/home/home_summary.dart';

Category _cat(int id, String name, String color) =>
    Category(id: id, name: name, iconKey: 'category', colorHex: color, isArchived: false);

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
  test('summarize sums current month, ranks categories, uses budget', () {
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
      [_cat(1, 'Food & dining', 'E08A5B'), _cat(2, 'Transport', '5B8DB8')],
      4000000,
      now,
    );

    expect(s.spent, 95000); // 45k+30k+20k, last month excluded
    expect(s.budget, 4000000);
    expect(s.remaining, 3905000);
    expect(s.byCategory.first.category.name, 'Food & dining'); // 75k ranks first
    expect(s.byCategory.first.amount, 75000);
    expect(s.byCategory[1].amount, 20000);
  });
}
