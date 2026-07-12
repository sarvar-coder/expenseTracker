import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/activity/activity_filter.dart';

Expense _exp(int id, String desc, int catId, DateTime date) => Expense(
      id: id,
      description: desc,
      amount: 1000 * id,
      categoryId: catId,
      date: date,
      source: ExpenseSource.manual,
      rawInput: null,
      createdAt: date,
    );

void main() {
  final now = DateTime(2026, 7, 8, 12);
  // Input is date-desc, as the stream delivers it.
  final expenses = [
    _exp(1, 'Bon Cafe', 10, DateTime(2026, 7, 8, 9)), // today
    _exp(2, 'Yandex Go', 20, DateTime(2026, 7, 7, 18)), // yesterday
    _exp(3, 'Korzinka', 10, DateTime(2026, 7, 5, 10)), // older
  ];

  test('groups into Today / Yesterday / dated sections in order', () {
    final sections = groupExpenses(expenses, now: now);
    expect(sections.map((s) => s.label), ['Bugun', 'Kecha', '5 Iyul']);
    expect(sections.first.items.single.description, 'Bon Cafe');
  });

  test('query narrows by description substring (case-insensitive)', () {
    final sections = groupExpenses(expenses, query: 'kOrz', now: now);
    expect(sections.length, 1);
    expect(sections.single.label, '5 Iyul');
    expect(sections.single.items.single.description, 'Korzinka');
  });

  test('categoryId narrows to one category', () {
    final sections = groupExpenses(expenses, categoryId: 10, now: now);
    final all = [for (final s in sections) ...s.items];
    expect(all.map((e) => e.description), ['Bon Cafe', 'Korzinka']);
  });

  test('no match yields empty list', () {
    expect(groupExpenses(expenses, query: 'nope', now: now), isEmpty);
  });
}
