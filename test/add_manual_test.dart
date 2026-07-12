import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/common/ui_utils.dart';

void main() {
  test('parseAmount strips separators, rejects empty', () {
    expect(parseAmount('45 000'), 45000);
    expect(parseAmount('128,000 UZS'), 128000);
    expect(parseAmount(''), isNull);
    expect(parseAmount('abc'), isNull);
  });

  test('manual expense persists with source=manual and shows in stream', () async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    final food = (await db.getCategories()).first;

    await db.insertExpense(ExpensesCompanion.insert(
      description: 'Coffee',
      amount: parseAmount('45 000')!,
      categoryId: food.id,
      date: DateTime.now(),
      source: ExpenseSource.manual,
    ));

    final list = await db.watchExpenses().first;
    expect(list.length, 1);
    expect(list.first.source, ExpenseSource.manual);
    expect(list.first.amount, 45000);
  });
}
