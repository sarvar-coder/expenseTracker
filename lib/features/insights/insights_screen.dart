import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/ui_utils.dart';
import '../common/widgets.dart';
import 'insights_data.dart';

/// Tahlil: category-share donut with a center total + ranked legend, over a
/// Hafta/Oy/Yil window. Filtering/aggregation lives in the pure `insightsFor`.
class InsightsScreen extends ConsumerStatefulWidget {
  const InsightsScreen({super.key});

  @override
  ConsumerState<InsightsScreen> createState() => _InsightsScreenState();
}

class _InsightsScreenState extends ConsumerState<InsightsScreen> {
  InsightPeriod _period = InsightPeriod.month;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final expenses =
        ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final data = insightsFor(expenses, categories, _period, DateTime.now());

    return ListView(
      padding: const EdgeInsets.fromLTRB(
        AppSpace.page,
        8,
        AppSpace.page,
        AppSpace.section + 72, // clear the floating add button
      ),
      children: [
        Text('Tahlil', style: t.headlineMedium),
        const SizedBox(height: AppSpace.gap),
        SegmentedButton<InsightPeriod>(
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: InsightPeriod.week, label: Text('Hafta')),
            ButtonSegment(value: InsightPeriod.month, label: Text('Oy')),
            ButtonSegment(value: InsightPeriod.year, label: Text('Yil')),
          ],
          selected: {_period},
          onSelectionChanged: (s) => setState(() => _period = s.first),
        ),
        const SizedBox(height: AppSpace.gap),
        if (data.slices.isEmpty)
          const EmptyState(
            icon: Icons.donut_large_outlined,
            title: 'Bu davrda xarajat yo\'q',
            hint: 'Boshqa davrni tanlang yoki xarajat qo\'shing',
          )
        else ...[
          Card(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(AppRadii.hero),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 28),
              child: Semantics(
                label: 'Jami ${formatMoney(data.total)} UZS',
                excludeSemantics: true,
                child: SizedBox(
                  height: 232,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      PieChart(
                        PieChartData(
                          sectionsSpace: 3,
                          centerSpaceRadius: 78,
                          sections: [
                            for (final s in data.slices)
                              PieChartSectionData(
                                value: s.amount.toDouble(),
                                color: colorFromHex(s.category.colorHex),
                                radius: 30,
                                showTitle: false,
                              ),
                          ],
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 96),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text('Jami', style: t.labelMedium),
                            const SizedBox(height: 4),
                            FittedBox(
                              fit: BoxFit.scaleDown,
                              child: Text(
                                formatMoney(data.total),
                                style: t.displaySmall,
                              ),
                            ),
                            Text('UZS', style: t.labelSmall),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: AppSpace.section),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text('Turkumlar', style: t.titleMedium),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Column(
                children: [
                  for (final (i, s) in data.slices.indexed) ...[
                    if (i > 0)
                      Divider(indent: 74, endIndent: 16, color: c.border),
                    _LegendRow(slice: s, fraction: data.fraction(s.amount)),
                  ],
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}

/// Badge; name + amount over a share bar in the category color + percent.
class _LegendRow extends StatelessWidget {
  const _LegendRow({required this.slice, required this.fraction});
  final Slice slice;
  final double fraction;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          CategoryBadge(category: slice.category),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        slice.category.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: t.titleSmall,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Text(formatMoney(slice.amount), style: t.titleSmall),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(AppRadii.chip),
                        child: LinearProgressIndicator(
                          value: fraction,
                          minHeight: 6,
                          color: colorFromHex(slice.category.colorHex),
                        ),
                      ),
                    ),
                    SizedBox(
                      width: 48,
                      child: Text(
                        '${(fraction * 100).round()}%',
                        textAlign: TextAlign.end,
                        style: t.bodySmall,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
