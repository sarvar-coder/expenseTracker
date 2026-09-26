import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/db/database.dart';
import '../../providers/providers.dart';

/// This month's spend against the budget, for Home's month line.
class HomeSummary {
  final int spent;
  final int budget;

  const HomeSummary({required this.spent, required this.budget});

  double get progress => budget <= 0 ? 0 : (spent / budget).clamp(0.0, 1.0);
  int get remaining => budget - spent;
}

/// Sums the calendar month containing [now] into a [HomeSummary].
/// Pure — no Riverpod/DB — so it is directly unit-testable.
HomeSummary summarize(List<Expense> expenses, int budget, DateTime now) {
  final start = DateTime(now.year, now.month, 1);
  final end = DateTime(now.year, now.month + 1, 1);
  var spent = 0;
  for (final e in expenses) {
    if (!e.date.isBefore(start) && e.date.isBefore(end)) spent += e.amount;
  }
  return HomeSummary(spent: spent, budget: budget);
}

/// Expenses on the local calendar day of [now], newest first.
List<Expense> todayExpenses(List<Expense> expenses, DateTime now) {
  final start = DateTime(now.year, now.month, now.day);
  final end = DateTime(now.year, now.month, now.day + 1);
  return [
    for (final e in expenses)
      if (!e.date.isBefore(start) && e.date.isBefore(end)) e,
  ]..sort((a, b) => b.date.compareTo(a.date));
}

/// Recomputes whenever an expense or the budget changes.
final homeSummaryProvider = Provider<HomeSummary>((ref) {
  final expenses =
      ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
  final budget = ref.watch(settingsProvider).monthlyBudget;
  return summarize(expenses, budget, DateTime.now());
});
