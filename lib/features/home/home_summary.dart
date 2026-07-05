import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../data/db/database.dart';
import '../../providers/providers.dart';

class CatLine {
  final Category category;
  final int amount;
  const CatLine(this.category, this.amount);
}

class HomeSummary {
  final String monthLabel; // "July 2026"
  final int spent;
  final int budget;
  final List<CatLine> byCategory; // sorted desc by amount

  const HomeSummary({
    required this.monthLabel,
    required this.spent,
    required this.budget,
    required this.byCategory,
  });

  double get progress => budget <= 0 ? 0 : (spent / budget).clamp(0.0, 1.0);
  int get remaining => budget - spent;
  int get percent => budget <= 0 ? 0 : (spent / budget * 100).round();
  int get maxCatAmount => byCategory.isEmpty ? 0 : byCategory.first.amount;
}

/// Aggregates the current calendar month from the watched expense/category
/// streams. Recomputes whenever an expense or the budget changes.
final homeSummaryProvider = Provider<HomeSummary>((ref) {
  final expenses = ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
  final categories = ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
  final budget = ref.watch(settingsProvider).monthlyBudget;

  final now = DateTime.now();
  final start = DateTime(now.year, now.month, 1);
  final end = DateTime(now.year, now.month + 1, 1);

  final catById = {for (final c in categories) c.id: c};
  final sums = <int, int>{};
  var spent = 0;
  for (final e in expenses) {
    if (e.date.isBefore(start) || !e.date.isBefore(end)) continue;
    spent += e.amount;
    sums[e.categoryId] = (sums[e.categoryId] ?? 0) + e.amount;
  }

  final lines = <CatLine>[];
  sums.forEach((catId, amount) {
    final cat = catById[catId];
    if (cat != null) lines.add(CatLine(cat, amount));
  });
  lines.sort((a, b) => b.amount.compareTo(a.amount));

  return HomeSummary(
    monthLabel: DateFormat('MMMM yyyy').format(now),
    spent: spent,
    budget: budget,
    byCategory: lines,
  );
});
