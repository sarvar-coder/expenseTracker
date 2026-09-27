import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';

void main() {
  test('v1 -> v2 keeps rows, gives UUID ids, keeps category links', () async {
    final db = AppDatabase.forTesting(NativeDatabase.memory(setup: (raw) {
      // v1 schema as drift created it (int autoincrement ids).
      raw.execute('''
        CREATE TABLE categories (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL, icon_key TEXT NOT NULL DEFAULT 'category',
          color_hex TEXT NOT NULL, is_archived INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE expenses (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          description TEXT NOT NULL, amount INTEGER NOT NULL,
          category_id INTEGER NOT NULL REFERENCES categories (id),
          date INTEGER NOT NULL, source TEXT NOT NULL, raw_input TEXT,
          created_at INTEGER NOT NULL);
        INSERT INTO categories (id, name, color_hex, is_archived)
          VALUES (1, 'Food', 'E08A5B', 0), (2, 'Old', '5B8DB8', 1);
        INSERT INTO expenses VALUES
          (1, 'Coffee', 45000, 2, 1780000000, 'voice', 'kofe 45 ming', 1780000001);
        PRAGMA user_version = 1;
      ''');
    }));
    addTearDown(db.close);

    final cats = await db.getAllCategories();
    expect(cats.map((c) => c.name), unorderedEquals(['Food', 'Old']));
    expect(cats.firstWhere((c) => c.name == 'Old').isArchived, isTrue);
    expect(cats.first.id, hasLength(36));

    final [e] = await db.getExpenses();
    expect(e.id, hasLength(36));
    expect(e.categoryId, cats.firstWhere((c) => c.name == 'Old').id);
    expect(e.amount, 45000);
    expect(e.rawInput, 'kofe 45 ming');
    expect(e.date, DateTime.fromMillisecondsSinceEpoch(1780000000 * 1000));
    expect(e.dirty, isTrue, reason: 'pushed on first sync');
    expect(e.deletedAt, isNull);
  });

  test('deleteExpense is soft: hidden from reads, row kept for sync', () async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    final food = (await db.getCategories()).first;
    final id = await db.insertExpense(ExpensesCompanion.insert(
      description: 'x', amount: 5, categoryId: food.id,
      date: DateTime(2026, 9), source: ExpenseSource.manual,
    ));
    await db.deleteExpense(id);
    expect(await db.getExpenses(), isEmpty);
    expect(await db.totalSpent(DateTime(2026), DateTime(2027)), 0);
    final row = await db.select(db.expenses).getSingle();
    expect(row.deletedAt, isNotNull);
    expect(row.dirty, isTrue);
  });
}
