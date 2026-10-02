import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/ui_utils.dart';
import '../common/widgets.dart';
import 'activity_filter.dart';
import 'activity_filter_sheet.dart';

/// Tarix: search + filter sheet over every expense, grouped by day with
/// each day's total.
class ActivityScreen extends ConsumerStatefulWidget {
  const ActivityScreen({super.key});

  @override
  ConsumerState<ActivityScreen> createState() => _ActivityScreenState();
}

class _ActivityScreenState extends ConsumerState<ActivityScreen> {
  String _query = '';
  ActivityFilter _filter = const ActivityFilter();

  Future<void> _openFilter(List<Category> categories) async {
    final f = await showActivityFilterSheet(
      context,
      current: _filter,
      categories: categories,
    );
    if (f != null) setState(() => _filter = f);
  }

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
      filter: _filter,
      now: DateTime.now(),
    );

    return ListView(
      padding: EdgeInsets.fromLTRB(
        AppSpace.page,
        8,
        AppSpace.page,
        AppSpace.bottom(context, extra: 72), // clear the floating add button
      ),
      children: [
        Row(
          children: [
            Expanded(child: Text('Tarix', style: t.headlineMedium)),
            IconButton(
              tooltip: 'Filtr',
              onPressed: () => _openFilter(categories),
              icon: Badge(
                label: Text('${_filter.count}'),
                isLabelVisible: !_filter.isEmpty,
                child: const Icon(Icons.tune),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpace.gap),
        TextField(
          onChanged: (v) => setState(() => _query = v),
          decoration: InputDecoration(
            hintText: 'Xarajatlarni qidirish',
            prefixIcon: Icon(Icons.search, color: c.muted),
          ),
        ),
        if (!_filter.isEmpty) ...[
          const SizedBox(height: 12),
          _ActiveFilters(
            filter: _filter,
            catById: catById,
            onChanged: (f) => setState(() => _filter = f),
          ),
        ],
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
                  hint: 'Boshqa so\'z yoki filtrni sinab ko\'ring',
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

/// Removable chips for each active filter part; tapping × drops just that part.
class _ActiveFilters extends StatelessWidget {
  const _ActiveFilters({
    required this.filter,
    required this.catById,
    required this.onChanged,
  });
  final ActivityFilter filter;
  final Map<String, Category> catById;
  final ValueChanged<ActivityFilter> onChanged;

  @override
  Widget build(BuildContext context) {
    final f = filter;
    Widget chip(String label, ActivityFilter without, {Color? dot}) => InputChip(
      avatar: dot == null ? null : CircleAvatar(radius: 5, backgroundColor: dot),
      label: Text(label),
      onDeleted: () => onChanged(without),
      deleteButtonTooltipMessage: 'Olib tashlash',
    );

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final id in f.categoryIds)
          if (catById[id] case final cat?)
            chip(
              cat.name,
              ActivityFilter(
                categoryIds: {...f.categoryIds}..remove(id),
                range: f.range,
                minAmount: f.minAmount,
                maxAmount: f.maxAmount,
                isPrivate: f.isPrivate,
              ),
              dot: colorFromHex(cat.colorHex),
            ),
        if (f.range case final r?)
          chip(
            rangeLabel(r),
            ActivityFilter(
              categoryIds: f.categoryIds,
              minAmount: f.minAmount,
              maxAmount: f.maxAmount,
              isPrivate: f.isPrivate,
            ),
          ),
        if (f.minAmount != null || f.maxAmount != null)
          chip(
            amountLabel(f.minAmount, f.maxAmount),
            ActivityFilter(
              categoryIds: f.categoryIds,
              range: f.range,
              isPrivate: f.isPrivate,
            ),
          ),
        if (f.isPrivate case final p?)
          chip(
            p ? 'Shaxsiy' : 'Umumiy',
            ActivityFilter(
              categoryIds: f.categoryIds,
              range: f.range,
              minAmount: f.minAmount,
              maxAmount: f.maxAmount,
            ),
          ),
      ],
    );
  }
}
