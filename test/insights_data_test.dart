import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/insights/insights_data.dart';

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
  final cats = [_cat(1, 'Food', 'E08A5B'), _cat(2, 'Transport', '5B8DB8')];

  test('month period: totals + slices ranked, other months excluded, fractions', () {
    final now = DateTime(2026, 7, 15);
    final data = insightsFor(
      [
        _exp(1, 60000, 1, DateTime(2026, 7, 2)),
        _exp(2, 20000, 2, DateTime(2026, 7, 9)),
        _exp(3, 99000, 1, DateTime(2026, 6, 30)), // prior month, excluded
      ],
      cats,
      InsightPeriod.month,
      now,
    );

    expect(data.total, 80000);
    expect(data.slices.first.category.name, 'Food');
    expect(data.slices.first.amount, 60000);
    expect(data.slices[1].amount, 20000);
    expect(data.fraction(60000), closeTo(0.75, 1e-9));
  });

  test('week window is Mon..Sun containing now', () {
    // Wed 2026-07-15. Week = Mon 13 .. Sun 19.
    final now = DateTime(2026, 7, 15);
    final data = insightsFor(
      [
        _exp(1, 10000, 1, DateTime(2026, 7, 13)), // Monday, in
        _exp(2, 5000, 1, DateTime(2026, 7, 19, 23)), // Sunday, in
        _exp(3, 7000, 1, DateTime(2026, 7, 12)), // prev Sunday, out
        _exp(4, 8000, 1, DateTime(2026, 7, 20)), // next Monday, out
      ],
      cats,
      InsightPeriod.week,
      now,
    );
    expect(data.total, 15000);
  });

  test('empty period yields zero total and no slices', () {
    final data = insightsFor(const [], cats, InsightPeriod.year, DateTime(2026, 7, 15));
    expect(data.total, 0);
    expect(data.slices, isEmpty);
    expect(data.fraction(0), 0);
  });
}
