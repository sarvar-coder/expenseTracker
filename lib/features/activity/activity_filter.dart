import '../../data/db/database.dart';
import '../common/ui_utils.dart';

/// A day's worth of expenses under a human label (Bugun / Kecha / "8 Iyul").
class DaySection {
  final String label;
  final List<Expense> items;
  const DaySection(this.label, this.items);
}

/// Filters [expenses] by description substring + optional category, then groups
/// the (already date-desc) list into day sections. Pure — directly
/// unit-testable, like `summarize`.
List<DaySection> groupExpenses(
  List<Expense> expenses, {
  String query = '',
  int? categoryId,
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
    if (categoryId != null && e.categoryId != categoryId) continue;
    if (q.isNotEmpty && !e.description.toLowerCase().contains(q)) continue;
    groups.putIfAbsent(labelFor(e.date), () => []).add(e);
  }
  return [for (final entry in groups.entries) DaySection(entry.key, entry.value)];
}
