import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/ui_utils.dart';
import '../common/widgets.dart';
import 'activity_filter.dart';

/// Tarix: search + category chips over every expense, grouped by day with
/// each day's total.
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
    final c = context.colors;
    final t = Theme.of(context).textTheme;
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
      padding: const EdgeInsets.fromLTRB(
        AppSpace.page,
        8,
        AppSpace.page,
        AppSpace.section + 72, // clear the floating add button
      ),
      children: [
        Text('Tarix', style: t.headlineMedium),
        const SizedBox(height: AppSpace.gap),
        TextField(
          onChanged: (v) => setState(() => _query = v),
          decoration: InputDecoration(
            hintText: 'Xarajatlarni qidirish',
            prefixIcon: Icon(Icons.search, color: c.muted),
          ),
        ),
        const SizedBox(height: 12),
        _ChipBar(
          categories: categories,
          selected: _categoryId,
          onSelect: (id) => setState(() => _categoryId = id),
        ),
        if (sections.isEmpty)
          expenses.isEmpty
              ? const EmptyState(
                  icon: Icons.receipt_long_outlined,
                  title: 'Hali xarajat yo\'q',
                  hint: 'Pastdagi tugma bilan birinchisini qo\'shing',
                )
              : const EmptyState(
                  icon: Icons.search_off,
                  title: 'Mos keladigani yo\'q',
                  hint: 'Boshqa so\'z yoki turkumni sinab ko\'ring',
                )
        else
          for (final section in sections) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 20, 4, 8),
              child: Row(
                children: [
                  Expanded(child: Text(section.label, style: t.titleMedium)),
                  Text(
                    formatMoney(
                      section.items.fold(0, (sum, e) => sum + e.amount),
                    ),
                    style: t.labelMedium,
                  ),
                ],
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

/// Horizontal chip row: `Hammasi` then each category with its color dot.
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
    Widget chip(String label, int? id, {Color? dot}) => Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        avatar: dot == null
            ? null
            : CircleAvatar(radius: 5, backgroundColor: dot),
        label: Text(label),
        selected: id == selected,
        onSelected: (_) => onSelect(id),
      ),
    );

    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          chip('Hammasi', null),
          for (final cat in categories)
            chip(cat.name, cat.id, dot: colorFromHex(cat.colorHex)),
        ],
      ),
    );
  }
}
