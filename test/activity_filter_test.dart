import 'package:flutter/material.dart' show DateTimeRange;
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/activity/activity_filter.dart';

Expense _exp(int id, String desc, int catId, DateTime date,
        {bool isPrivate = false}) =>
    Expense(
      id: '$id',
      description: desc,
      amount: 1000 * id,
      categoryId: '$catId',
      date: date,
      source: ExpenseSource.manual,
      rawInput: null,
      createdAt: date,
      updatedAt: date,
      isPrivate: isPrivate,
      frozen: false,
      dirty: false,
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

  List<String> names(ActivityFilter f, [List<Expense>? list]) => [
        for (final s in groupExpenses(list ?? expenses, filter: f, now: now))
          for (final e in s.items) e.description,
      ];

  test('categories: one or several', () {
    expect(names(const ActivityFilter(categoryIds: {'10'})),
        ['Bon Cafe', 'Korzinka']);
    expect(names(const ActivityFilter(categoryIds: {'10', '20'})),
        ['Bon Cafe', 'Yandex Go', 'Korzinka']);
  });

  test('date range includes the whole end day', () {
    final f = ActivityFilter(
        range: DateTimeRange(
            start: DateTime(2026, 7, 5), end: DateTime(2026, 7, 7)));
    expect(names(f), ['Yandex Go', 'Korzinka']);
    expect(f.count, 1);
  });

  test('amount min/max are inclusive', () {
    // amounts: 1000, 2000, 3000
    expect(names(const ActivityFilter(minAmount: 2000)),
        ['Yandex Go', 'Korzinka']);
    expect(names(const ActivityFilter(maxAmount: 2000)),
        ['Bon Cafe', 'Yandex Go']);
    expect(names(const ActivityFilter(minAmount: 2000, maxAmount: 2000)),
        ['Yandex Go']);
  });

  test('private / shared', () {
    final list = [
      _exp(1, 'Gift', 10, DateTime(2026, 7, 8, 9), isPrivate: true),
      _exp(2, 'Bread', 10, DateTime(2026, 7, 8, 8)),
    ];
    expect(names(const ActivityFilter(isPrivate: true), list), ['Gift']);
    expect(names(const ActivityFilter(isPrivate: false), list), ['Bread']);
    expect(names(const ActivityFilter(), list), ['Gift', 'Bread']);
  });

  test('count / isEmpty', () {
    expect(const ActivityFilter().isEmpty, isTrue);
    expect(
        const ActivityFilter(
                categoryIds: {'1', '2'}, minAmount: 1, maxAmount: 2,
                isPrivate: true)
            .count,
        3);
  });

  test('no match yields empty list', () {
    expect(groupExpenses(expenses, query: 'nope', now: now), isEmpty);
  });
}
