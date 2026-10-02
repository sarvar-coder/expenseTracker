import '../../data/db/database.dart';
import '../activity/activity_filter.dart';

class Slice {
  final Category category;
  final int amount;
  const Slice(this.category, this.amount);
}

class InsightsData {
  final int total;
  final List<Slice> slices; // sorted desc by amount
  const InsightsData(this.total, this.slices);

  double fraction(int amount) => total <= 0 ? 0 : amount / total;
}

/// Aggregates spend-by-category over the half-open window [start, end),
/// keeping only expenses that pass [filter]. Pure — directly unit-testable, like `summarize`.
InsightsData insightsFor(
  List<Expense> expenses,
  List<Category> categories,
  DateTime start,
  DateTime end, {
  ActivityFilter filter = const ActivityFilter(),
}) {
  final catById = {for (final c in categories) c.id: c};
  final sums = <String, int>{};
  var total = 0;
  for (final e in expenses) {
    if (e.date.isBefore(start) || !e.date.isBefore(end)) continue;
    if (!filter.matches(e)) continue;
    total += e.amount;
    sums[e.categoryId] = (sums[e.categoryId] ?? 0) + e.amount;
  }

  final slices = <Slice>[];
  sums.forEach((catId, amount) {
    final cat = catById[catId];
    if (cat != null) slices.add(Slice(cat, amount));
  });
  slices.sort((a, b) => b.amount.compareTo(a.amount));
  return InsightsData(total, slices);
}
