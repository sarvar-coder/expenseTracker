import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/services/csv_export.dart';

Expense _exp(int id, String desc, int amount, int catId, DateTime date) => Expense(
      id: id,
      description: desc,
      amount: amount,
      categoryId: catId,
      date: date,
      source: ExpenseSource.manual,
      createdAt: date,
    );

void main() {
  test('csv has header + rows and escapes commas/quotes', () {
    final csv = expensesCsv(
      [
        _exp(1, 'Coffee', 45000, 1, DateTime(2026, 7, 2)),
        _exp(2, 'Lunch, with "tip"', 90000, 2, DateTime(2026, 7, 3)),
      ],
      {1: 'Food', 2: 'Transport'},
    );
    final lines = const LineSplitter().split(csv).toList();

    expect(lines.first, 'date,description,category,amount,source');
    expect(lines[1], '2026-07-02,Coffee,Food,45000,manual');
    // csv package quotes the field and doubles inner quotes.
    expect(lines[2], '2026-07-03,"Lunch, with ""tip""",Transport,90000,manual');
  });

  test('unknown categoryId yields empty category cell', () {
    final csv = expensesCsv([_exp(1, 'X', 1000, 99, DateTime(2026, 1, 1))], const {});
    expect(csv.contains('2026-01-01,X,,1000,manual'), isTrue);
  });
}

/// Minimal splitter (avoid importing dart:convert just for the name clash).
class LineSplitter {
  const LineSplitter();
  Iterable<String> split(String s) => s.split(RegExp(r'\r\n|\n'));
}
