import 'package:flutter/material.dart' show DateTimeRange;

import '../../data/db/database.dart';
import '../common/ui_utils.dart';

/// A day's worth of expenses under a human label (Bugun / Kecha / "8 Iyul").
class DaySection {
  final String label;
  final List<Expense> items;
  const DaySection(this.label, this.items);
}

/// Tarix sheet filters. Empty/null fields mean "no constraint".
class ActivityFilter {
  final Set<String> categoryIds;
  final DateTimeRange? range; // whole days, end inclusive
  final int? minAmount;
  final int? maxAmount;
  final bool? isPrivate; // null = all, true = Shaxsiy, false = Umumiy

  const ActivityFilter({
    this.categoryIds = const {},
    this.range,
    this.minAmount,
    this.maxAmount,
    this.isPrivate,
  });

  /// Active filter groups — shown as the badge on the filter icon.
  int get count =>
      (categoryIds.isEmpty ? 0 : 1) +
      (range == null ? 0 : 1) +
      (minAmount == null && maxAmount == null ? 0 : 1) +
      (isPrivate == null ? 0 : 1);

  bool get isEmpty => count == 0;

  bool matches(Expense e) {
    if (categoryIds.isNotEmpty && !categoryIds.contains(e.categoryId)) {
      return false;
    }
    final r = range;
    if (r != null) {
      final start = DateTime(r.start.year, r.start.month, r.start.day);
      final end = DateTime(r.end.year, r.end.month, r.end.day + 1);
      if (e.date.isBefore(start) || !e.date.isBefore(end)) return false;
    }
    if (minAmount != null && e.amount < minAmount!) return false;
    if (maxAmount != null && e.amount > maxAmount!) return false;
    if (isPrivate != null && e.isPrivate != isPrivate) return false;
    return true;
  }
}

/// Filters [expenses] by description substring + [filter], then groups
/// the (already date-desc) list into day sections. Pure — directly
/// unit-testable, like `summarize`.
List<DaySection> groupExpenses(
  List<Expense> expenses, {
  String query = '',
  ActivityFilter filter = const ActivityFilter(),
  required DateTime now,
}) {
  final q = query.trim().toLowerCase();
  final today = DateTime(now.year, now.month, now.day);
  final yesterday = today.subtract(const Duration(days: 1));

  String labelFor(DateTime d) {
    final day = DateTime(d.year, d.month, d.day);
    if (day == today) return 'Bugun';
    if (day == yesterday) return 'Kecha';
    return uzDayMonth(d);
  }

  // Map literals preserve first-seen order; input is already date-desc.
  final groups = <String, List<Expense>>{};
  for (final e in expenses) {
    if (!filter.matches(e)) continue;
    if (q.isNotEmpty && !e.description.toLowerCase().contains(q)) continue;
    groups.putIfAbsent(labelFor(e.date), () => []).add(e);
  }
  return [
    for (final entry in groups.entries) DaySection(entry.key, entry.value),
  ];
}
