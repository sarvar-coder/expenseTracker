import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';

void main() {
  test('editing an expense updates fields in place, keeps id/source/createdAt', () async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    final cats = await db.getCategories();
    final food = cats.first;
    final other = cats[1];

    final id = await db.insertExpense(ExpensesCompanion.insert(
      description: 'Coffee',
      amount: 45000,
      categoryId: food.id,
      date: DateTime(2026, 7, 1),
      source: ExpenseSource.manual,
    ));

    final original = (await db.watchExpenses().first).single;
    expect(original.id, id);

    // Same edit the Manual form performs: copyWith + updateExpense.
    await db.updateExpense(original.copyWith(
      description: 'Lunch',
      amount: 90000,
      categoryId: other.id,
      date: DateTime(2026, 7, 5),
    ));

    final edited = (await db.watchExpenses().first).single;
    expect(edited.id, id, reason: 'no new row created');
    expect(edited.description, 'Lunch');
    expect(edited.amount, 90000);
    expect(edited.categoryId, other.id);
    expect(edited.date, DateTime(2026, 7, 5));
    expect(edited.source, original.source, reason: 'source preserved');
    expect(edited.createdAt, original.createdAt, reason: 'createdAt preserved');
  });
}
