import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/ui_utils.dart';
import '../common/widgets.dart';
import 'activity_filter.dart';
import 'activity_filter_page.dart';

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
    final f = await openActivityFilter(
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
          ActiveFilters(
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
