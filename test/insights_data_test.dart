import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/activity/activity_filter.dart';
import 'package:expense_tracker/features/insights/insights_data.dart';

Category _cat(int id, String name, String color) => Category(
  id: '$id',
  name: name,
  iconKey: 'category',
  colorHex: color,
  isArchived: false,
  updatedAt: DateTime(2026),
  dirty: false,
);

Expense _exp(int id, int amount, int catId, DateTime date) => Expense(
  id: '$id',
  description: 'x',
  amount: amount,
  categoryId: '$catId',
  date: date,
  source: ExpenseSource.manual,
  createdAt: date,
  updatedAt: date,
  isPrivate: false,
  frozen: false,
  dirty: false,
);

void main() {
  final cats = [_cat(1, 'Food', 'E08A5B'), _cat(2, 'Transport', '5B8DB8')];

  test(
    'month window: totals + slices ranked, other months excluded, fractions',
    () {
      final data = insightsFor(
        [
          _exp(1, 60000, 1, DateTime(2026, 7, 2)),
          _exp(2, 20000, 2, DateTime(2026, 7, 9)),
          _exp(3, 99000, 1, DateTime(2026, 6, 30)), // prior month, excluded
        ],
        cats,
        DateTime(2026, 7),
        DateTime(2026, 8),
      );

      expect(data.total, 80000);
      expect(data.slices.first.category.name, 'Food');
      expect(data.slices.first.amount, 60000);
      expect(data.slices[1].amount, 20000);
      expect(data.fraction(60000), closeTo(0.75, 1e-9));
    },
  );

  test('custom window: end bound is exclusive', () {
    // Screen passes end = last picked day + 1, so 13..19 Jul inclusive.
    final data = insightsFor(
      [
        _exp(1, 10000, 1, DateTime(2026, 7, 13)), // first day, in
        _exp(2, 5000, 1, DateTime(2026, 7, 19, 23)), // last day late, in
        _exp(3, 7000, 1, DateTime(2026, 7, 12, 23)), // day before, out
        _exp(4, 8000, 1, DateTime(2026, 7, 20)), // day after, out
      ],
      cats,
      DateTime(2026, 7, 13),
      DateTime(2026, 7, 20),
    );
    expect(data.total, 15000);
  });

  test('filter narrows total and slices', () {
    final data = insightsFor(
      [
        _exp(1, 10000, 1, DateTime(2026, 7, 2)),
        _exp(2, 50000, 1, DateTime(2026, 7, 3)), // over max, out
        _exp(3, 20000, 2, DateTime(2026, 7, 4)), // other category, out
      ],
      cats,
      DateTime(2026, 7),
      DateTime(2026, 8),
      filter: const ActivityFilter(categoryIds: {'1'}, maxAmount: 40000),
    );
    expect(data.total, 10000);
    expect(data.slices.single.category.name, 'Food');
  });

  test('empty period yields zero total and no slices', () {
    final data = insightsFor(const [], cats, DateTime(2026), DateTime(2027));
    expect(data.total, 0);
    expect(data.slices, isEmpty);
    expect(data.fraction(0), 0);
  });
}
