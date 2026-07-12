import '../../data/db/database.dart';

enum InsightPeriod { week, month, year }

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

/// [start, end) window for [period] containing [now]. Week starts Monday.
(DateTime, DateTime) periodRange(InsightPeriod period, DateTime now) {
  final today = DateTime(now.year, now.month, now.day);
  switch (period) {
    case InsightPeriod.week:
      final start = today.subtract(Duration(days: now.weekday - 1));
      return (start, start.add(const Duration(days: 7)));
    case InsightPeriod.month:
      return (DateTime(now.year, now.month, 1), DateTime(now.year, now.month + 1, 1));
    case InsightPeriod.year:
      return (DateTime(now.year, 1, 1), DateTime(now.year + 1, 1, 1));
  }
}

/// Aggregates spend-by-category over [period]. Pure — directly unit-testable,
/// like `summarize`.
InsightsData insightsFor(
  List<Expense> expenses,
  List<Category> categories,
  InsightPeriod period,
  DateTime now,
) {
  final (start, end) = periodRange(period, now);
  final catById = {for (final c in categories) c.id: c};
  final sums = <int, int>{};
  var total = 0;
  for (final e in expenses) {
    if (e.date.isBefore(start) || !e.date.isBefore(end)) continue;
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
