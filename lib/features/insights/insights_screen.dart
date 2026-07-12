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
    final expenses = ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
    final categories = ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final data = insightsFor(expenses, categories, _period, DateTime.now());

    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
      children: [
        const Text('Tahlil',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600, color: AppColors.text)),
        const SizedBox(height: 14),
        _PeriodSegment(period: _period, onChanged: (p) => setState(() => _period = p)),
        const SizedBox(height: 24),
        if (data.slices.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 60),
            child: Center(child: Text('Bu davrda xarajat yo\'q', style: TextStyle(color: AppColors.muted))),
          )
        else ...[
          SizedBox(
            height: 220,
            child: Stack(
              alignment: Alignment.center,
              children: [
                PieChart(PieChartData(
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
                )),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('Jami', style: TextStyle(fontSize: 12, color: AppColors.muted)),
                    const SizedBox(height: 2),
                    Text(formatMoney(data.total),
                        style: const TextStyle(
                            fontSize: 22, fontWeight: FontWeight.w600, color: AppColors.text)),
                    const Text('UZS', style: TextStyle(fontSize: 11, color: AppColors.muted)),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          for (final s in data.slices) _LegendRow(slice: s, fraction: data.fraction(s.amount)),
        ],
      ],
    );
  }
}

class _PeriodSegment extends StatelessWidget {
  const _PeriodSegment({required this.period, required this.onChanged});
  final InsightPeriod period;
  final ValueChanged<InsightPeriod> onChanged;

  @override
  Widget build(BuildContext context) {
    Widget btn(InsightPeriod p, String label) {
      final on = p == period;
      return Expanded(
        child: GestureDetector(
          onTap: () => onChanged(p),
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(
              color: on ? AppColors.card : Colors.transparent,
              borderRadius: BorderRadius.circular(9),
              boxShadow: on ? [const BoxShadow(color: Colors.black12, blurRadius: 3)] : null,
            ),
            child: Text(label,
                textAlign: TextAlign.center,
                style: TextStyle(
                    fontSize: 12.5,
                    color: on ? AppColors.text : AppColors.muted,
                    fontWeight: on ? FontWeight.w500 : FontWeight.normal)),
          ),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: const Color(0xFFEAE7E0),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          btn(InsightPeriod.week, 'Hafta'),
          btn(InsightPeriod.month, 'Oy'),
          btn(InsightPeriod.year, 'Yil'),
        ],
      ),
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
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        children: [
          Container(
            width: 12,
            height: 12,
            decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(3)),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(slice.category.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 14, color: AppColors.text)),
          ),
          Text('${(fraction * 100).round()}%',
              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          const SizedBox(width: 12),
          Text(formatMoney(slice.amount),
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.text)),
        ],
      ),
    );
  }
}
