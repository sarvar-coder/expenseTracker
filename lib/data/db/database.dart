import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';

import 'tables.dart';

part 'database.g.dart';

/// Default categories seeded on first launch. AI can add more later.
const _defaultCategories = [
  (name: 'Food & dining', icon: 'restaurant', color: 'E08A5B'),
  (name: 'Groceries', icon: 'shopping_cart', color: '6FA86A'),
  (name: 'Shopping', icon: 'shopping_bag', color: 'C07FA6'),
  (name: 'Transport', icon: 'directions_car', color: '5B8DB8'),
  (name: 'Bills', icon: 'receipt_long', color: 'D9A24E'),
];

@DriftDatabase(tables: [Categories, Expenses])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(driftDatabase(name: 'expense_tracker'));

  /// For tests: pass an in-memory executor.
  AppDatabase.forTesting(super.executor);

  @override
  int get schemaVersion => 1;

  @override
  MigrationStrategy get migration => MigrationStrategy(
        onCreate: (m) async {
          await m.createAll();
          await _seedCategories();
        },
      );

  Future<void> _seedCategories() async {
    await batch((b) {
      b.insertAll(
        categories,
        _defaultCategories
            .map((c) => CategoriesCompanion.insert(
                  name: c.name,
                  iconKey: Value(c.icon),
                  colorHex: c.color,
                ))
            .toList(),
      );
    });
  }

  // --- Categories ---
  Future<List<Category>> getCategories() =>
      (select(categories)..where((c) => c.isArchived.equals(false))).get();

  Stream<List<Category>> watchCategories() =>
      (select(categories)..where((c) => c.isArchived.equals(false))).watch();

  Future<int> insertCategory(CategoriesCompanion entry) =>
      into(categories).insert(entry);

  // --- Expenses ---
  Future<int> insertExpense(ExpensesCompanion entry) =>
      into(expenses).insert(entry);

  Future<bool> updateExpense(Expense entry) => update(expenses).replace(entry);

  Future<int> deleteExpense(int id) =>
      (delete(expenses)..where((e) => e.id.equals(id))).go();

  Stream<List<Expense>> watchExpenses() =>
      (select(expenses)..orderBy([(e) => OrderingTerm.desc(e.date)])).watch();

  /// Sum of expenses per category between [start] (inclusive) and [end]
  /// (exclusive). Returns categoryId -> total amount.
  Future<Map<int, int>> categoryTotals(DateTime start, DateTime end) async {
    final sum = expenses.amount.sum();
    final query = selectOnly(expenses)
      ..addColumns([expenses.categoryId, sum])
      ..where(expenses.date.isBiggerOrEqualValue(start) &
          expenses.date.isSmallerThanValue(end))
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
      ..where(expenses.date.isBiggerOrEqualValue(start) &
          expenses.date.isSmallerThanValue(end));
    final row = await query.getSingle();
    return row.read(sum) ?? 0;
  }
}
