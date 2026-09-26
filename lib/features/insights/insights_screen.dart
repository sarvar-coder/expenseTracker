import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/ui_utils.dart';
import 'insights_data.dart';

/// Category-share donut with a center total + legend, over a Week/Month/Year
/// window. Filtering/aggregation lives in the pure `insightsFor`.
class InsightsScreen extends ConsumerStatefulWidget {
  const InsightsScreen({super.key});

  @override
  ConsumerState<InsightsScreen> createState() => _InsightsScreenState();
}

class _InsightsScreenState extends ConsumerState<InsightsScreen> {
  InsightPeriod _period = InsightPeriod.month;

  @override
  Widget build(BuildContext context) {
    final expenses =
        ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final data = insightsFor(expenses, categories, _period, DateTime.now());

    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
      children: [
        Text(
          'Tahlil',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            color: context.colors.text,
          ),
        ),
        const SizedBox(height: 14),
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
        const SizedBox(height: 24),
        if (data.slices.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 60),
            child: Center(
              child: Text(
                'Bu davrda xarajat yo\'q',
                style: TextStyle(color: context.colors.muted),
              ),
            ),
          )
        else ...[
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 20),
              child: Semantics(
                label: 'Jami ${formatMoney(data.total)} UZS',
                excludeSemantics: true,
                child: SizedBox(
                  height: 220,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      PieChart(
                        PieChartData(
                          sectionsSpace: 2,
                          centerSpaceRadius: 70,
                          sections: [
                            for (final s in data.slices)
                              PieChartSectionData(
                                value: s.amount.toDouble(),
                                color: colorFromHex(s.category.colorHex),
                                radius: 26,
                                showTitle: false,
                              ),
                          ],
                        ),
                      ),
                      Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            'Jami',
                            style: TextStyle(
                              fontSize: 12,
                              color: context.colors.muted,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            formatMoney(data.total),
                            style: TextStyle(
                              fontSize: 22,
                              fontWeight: FontWeight.w600,
                              color: context.colors.text,
                            ),
                          ),
                          Text(
                            'UZS',
                            style: TextStyle(
                              fontSize: 11,
                              color: context.colors.muted,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Column(
                children: [
                  for (final s in data.slices)
                    _LegendRow(slice: s, fraction: data.fraction(s.amount)),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _LegendRow extends StatelessWidget {
  const _LegendRow({required this.slice, required this.fraction});
  final Slice slice;
  final double fraction;

  @override
  Widget build(BuildContext context) {
    final color = colorFromHex(slice.category.colorHex);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          CircleAvatar(radius: 6, backgroundColor: color),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              slice.category.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 14, color: context.colors.text),
            ),
          ),
          Text(
            '${(fraction * 100).round()}%',
            style: TextStyle(fontSize: 12, color: context.colors.muted),
          ),
          const SizedBox(width: 12),
          Text(
            formatMoney(slice.amount),
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: context.colors.text,
            ),
          ),
        ],
      ),
    );
  }
}
