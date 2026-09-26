import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/widgets.dart';
import 'activity_filter.dart';

/// Transactions: search + category-chip filter over the full expense stream,
/// grouped by day, with swipe-to-delete.
class ActivityScreen extends ConsumerStatefulWidget {
  const ActivityScreen({super.key});

  @override
  ConsumerState<ActivityScreen> createState() => _ActivityScreenState();
}

class _ActivityScreenState extends ConsumerState<ActivityScreen> {
  String _query = '';
  int? _categoryId;

  @override
  Widget build(BuildContext context) {
    final expenses =
        ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final catById = {for (final c in categories) c.id: c};
    final sections = groupExpenses(
      expenses,
      query: _query,
      categoryId: _categoryId,
      now: DateTime.now(),
    );

    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
      children: [
        Text(
          'Tranzaksiyalar',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            color: context.colors.text,
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          onChanged: (v) => setState(() => _query = v),
          decoration: InputDecoration(
            hintText: 'Xarajatlarni qidirish',
            prefixIcon: Icon(
              Icons.search,
              size: 20,
              color: context.colors.muted,
            ),
          ),
        ),
        const SizedBox(height: 12),
        _ChipBar(
          categories: categories,
          selected: _categoryId,
          onSelect: (id) => setState(() => _categoryId = id),
        ),
        const SizedBox(height: 8),
        if (sections.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 40),
            child: Center(
              child: Text(
                expenses.isEmpty
                    ? 'Hali xarajat yo\'q'
                    : 'Mos keladigani yo\'q',
                style: TextStyle(color: context.colors.muted),
              ),
            ),
          )
        else
          for (final section in sections) ...[
            Padding(
              padding: const EdgeInsets.only(top: 16, bottom: 6, left: 4),
              child: Text(
                section.label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: context.colors.muted,
                ),
              ),
            ),
            Card(
              margin: EdgeInsets.zero,
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  for (final e in section.items)
                    ExpenseTile(expense: e, category: catById[e.categoryId]),
                ],
              ),
            ),
          ],
      ],
    );
  }
}

class _ChipBar extends StatelessWidget {
  const _ChipBar({
    required this.categories,
    required this.selected,
    required this.onSelect,
  });
  final List<Category> categories;
  final int? selected;
  final ValueChanged<int?> onSelect;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    Widget chip(String label, int? id) {
      final on = id == selected;
      return Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          label: Text(label),
          selected: on,
          showCheckmark: false,
          onSelected: (_) => onSelect(id),
          backgroundColor: c.card,
          selectedColor: c.accent,
          labelStyle: TextStyle(fontSize: 13, color: on ? c.onAccent : c.text),
          side: BorderSide(color: on ? c.accent : c.border),
          shape: const StadiumBorder(),
        ),
      );
    }

    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          chip('Hammasi', null),
          for (final c in categories) chip(c.name, c.id),
        ],
      ),
    );
  }
}
