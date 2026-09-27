import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';

import 'tables.dart';

part 'database.g.dart';

/// Default categories seeded on first launch. AI can add more later.
const _defaultCategories = [
  (name: 'Food & dining', color: 'E08A5B'),
  (name: 'Groceries', color: '6FA86A'),
  (name: 'Shopping', color: 'C07FA6'),
  (name: 'Transport', color: '5B8DB8'),
  (name: 'Bills', color: 'D9A24E'),
];

@DriftDatabase(tables: [Categories, Expenses])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(driftDatabase(name: 'expense_tracker'));

  /// For tests: pass an in-memory executor.
  AppDatabase.forTesting(super.executor);

  @override
  int get schemaVersion => 2;

  @override
  MigrationStrategy get migration => MigrationStrategy(
        onCreate: (m) async {
          await m.createAll();
          await _seedCategories();
        },
        onUpgrade: (m, from, to) async {
          if (from < 2) await _toUuidIds(m);
        },
      );

  /// v1 -> v2: int ids become UUIDs (plus sync columns). SQLite can't change
  /// a primary key in place, so copy rows into fresh tables.
  Future<void> _toUuidIds(Migrator m) async {
    await customStatement('ALTER TABLE expenses RENAME TO expenses_v1');
    await customStatement('ALTER TABLE categories RENAME TO categories_v1');
    await m.createAll();
    final ids = <int, String>{};
    for (final r in await customSelect('SELECT * FROM categories_v1').get()) {
      final id = ids[r.read<int>('id')] = newId();
      await into(categories).insert(CategoriesCompanion.insert(
        id: Value(id),
        name: r.read<String>('name'),
        iconKey: Value(r.read<String>('icon_key')),
        colorHex: r.read<String>('color_hex'),
        isArchived: Value(r.read<bool>('is_archived')),
      ));
    }
    for (final r in await customSelect('SELECT * FROM expenses_v1').get()) {
      await into(expenses).insert(ExpensesCompanion.insert(
        description: r.read<String>('description'),
        amount: r.read<int>('amount'),
        categoryId: ids[r.read<int>('category_id')]!,
        date: r.read<DateTime>('date'),
        source: ExpenseSource.values.byName(r.read<String>('source')),
        rawInput: Value(r.read<String?>('raw_input')),
        createdAt: Value(r.read<DateTime>('created_at')),
      ));
    }
    await customStatement('DROP TABLE expenses_v1');
    await customStatement('DROP TABLE categories_v1');
  }

  Future<void> _seedCategories() async {
    await batch((b) {
      b.insertAll(
        categories,
        _defaultCategories
            .map((c) => CategoriesCompanion.insert(
                  name: c.name,
                  colorHex: c.color,
                ))
            .toList(),
      );
    });
  }

  /// Another account signed in on this device: drop the previous one's rows.
  Future<void> resetLocal() => transaction(() async {
        await delete(expenses).go();
        await delete(categories).go();
        await _seedCategories();
      });

  // --- Categories ---
  // Every read skips soft-deleted rows (deletedAt set, kept for sync).
  Future<List<Category>> getCategories() => (select(categories)
        ..where((c) => c.isArchived.equals(false) & c.deletedAt.isNull()))
      .get();

  Stream<List<Category>> watchCategories() => (select(categories)
        ..where((c) => c.isArchived.equals(false) & c.deletedAt.isNull()))
      .watch();

  Future<String> insertCategory(CategoriesCompanion entry) async =>
      (await into(categories).insertReturning(entry)).id;

  /// Rename or archive (flip [Category.isArchived]) — categories are never
  /// hard-deleted (Expenses.categoryId is onDelete: restrict).
  Future<bool> updateCategory(Category c) => update(categories)
      .replace(c.copyWith(updatedAt: DateTime.now(), dirty: true));

  /// Includes archived — for the Settings manage sheet (unarchive).
  Future<List<Category>> getAllCategories() =>
      (select(categories)..where((c) => c.deletedAt.isNull())).get();

  Stream<List<Category>> watchAllCategories() =>
      (select(categories)..where((c) => c.deletedAt.isNull())).watch();

  // --- Expenses ---
  Future<String> insertExpense(ExpensesCompanion entry) async =>
      (await into(expenses).insertReturning(entry)).id;

  Future<bool> updateExpense(Expense entry) => update(expenses)
      .replace(entry.copyWith(updatedAt: DateTime.now(), dirty: true));

  /// Soft delete: the row stays so sync can push the deletion.
  Future<int> deleteExpense(String id) =>
      (update(expenses)..where((e) => e.id.equals(id))).write(ExpensesCompanion(
        deletedAt: Value(DateTime.now()),
        updatedAt: Value(DateTime.now()),
        dirty: const Value(true),
      ));

  SimpleSelectStatement<$ExpensesTable, Expense> _liveExpenses() =>
      select(expenses)
        ..where((e) => e.deletedAt.isNull())
        ..orderBy([(e) => OrderingTerm.desc(e.date)]);

  Stream<List<Expense>> watchExpenses() => _liveExpenses().watch();

  /// One-shot fetch (date desc) for CSV export.
  Future<List<Expense>> getExpenses() => _liveExpenses().get();

  Expression<bool> _liveIn(DateTime start, DateTime end) =>
      expenses.deletedAt.isNull() &
      expenses.date.isBiggerOrEqualValue(start) &
      expenses.date.isSmallerThanValue(end);

  /// Sum of expenses per category between [start] (inclusive) and [end]
  /// (exclusive). Returns categoryId -> total amount.
  Future<Map<String, int>> categoryTotals(DateTime start, DateTime end) async {
    final sum = expenses.amount.sum();
    final query = selectOnly(expenses)
      ..addColumns([expenses.categoryId, sum])
      ..where(_liveIn(start, end))
      ..groupBy([expenses.categoryId]);
    final rows = await query.get();
    return {
      for (final r in rows)
        r.read(expenses.categoryId)!: r.read(sum) ?? 0,
    };
  }

  /// Total spent in [start, end).
  Future<int> totalSpent(DateTime start, DateTime end) async {
    final sum = expenses.amount.sum();
    final query = selectOnly(expenses)
      ..addColumns([sum])
      ..where(_liveIn(start, end));
    final row = await query.getSingle();
    return row.read(sum) ?? 0;
  }
}
