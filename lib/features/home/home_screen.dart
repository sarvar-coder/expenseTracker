import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../common/ui_utils.dart';
import 'home_summary.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(homeSummaryProvider);

    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
      children: [
        _Header(monthLabel: s.monthLabel),
        const SizedBox(height: 12),
        _HeroCard(summary: s),
        const SizedBox(height: 20),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text('By category',
                style: TextStyle(fontWeight: FontWeight.w500, color: AppColors.text)),
            Text('See all', style: TextStyle(color: AppColors.accent, fontSize: 13)),
          ],
        ),
        const SizedBox(height: 8),
        if (s.byCategory.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 28),
            child: Center(
              child: Text('No expenses yet this month',
                  style: TextStyle(color: AppColors.muted)),
            ),
          )
        else
          ...s.byCategory.map((line) => _CategoryRow(line: line, max: s.maxCatAmount)),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.monthLabel});
  final String monthLabel;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Total spent', style: TextStyle(fontSize: 11, color: AppColors.muted)),
            Text(monthLabel,
                style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w500, color: AppColors.text)),
          ],
        ),
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(11),
            border: Border.all(color: AppColors.border),
          ),
          child: const Icon(Icons.notifications_none, size: 18, color: Color(0xFF57544E)),
        ),
      ],
    );
  }
}

class _HeroCard extends StatelessWidget {
  const _HeroCard({required this.summary});
  final HomeSummary summary;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
      decoration: BoxDecoration(
        color: AppColors.hero,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Spent this month',
              style: TextStyle(fontSize: 12, color: Color(0xFF9EC1B4))),
          const SizedBox(height: 8),
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(formatMoney(summary.spent),
                  style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w500, color: Colors.white, letterSpacing: -0.5)),
              const SizedBox(width: 4),
              const Text('UZS', style: TextStyle(fontSize: 14, color: Color(0xFF7FB3A0))),
            ],
          ),
          const SizedBox(height: 14),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: summary.progress,
              minHeight: 7,
              backgroundColor: Colors.white.withValues(alpha: 0.15),
              valueColor: const AlwaysStoppedAnimation(Color(0xFF4FC79A)),
            ),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('${summary.percent}% of ${formatMoney(summary.budget)}',
                  style: const TextStyle(fontSize: 11, color: Color(0xFFA7C9BC))),
              Text('${formatMoney(summary.remaining)} left',
                  style: const TextStyle(fontSize: 11, color: Color(0xFFA7C9BC))),
            ],
          ),
        ],
      ),
    );
  }
}

class _CategoryRow extends StatelessWidget {
  const _CategoryRow({required this.line, required this.max});
  final CatLine line;
  final int max;

  @override
  Widget build(BuildContext context) {
    final color = colorFromHex(line.category.colorHex);
    final frac = max <= 0 ? 0.0 : (line.amount / max).clamp(0.0, 1.0);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(11),
            ),
            child: Icon(iconForKey(line.category.iconKey), size: 18, color: color),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.category.name,
                    style: const TextStyle(fontSize: 13, color: AppColors.text)),
                const SizedBox(height: 5),
                ClipRRect(
                  borderRadius: BorderRadius.circular(3),
                  child: LinearProgressIndicator(
                    value: frac,
                    minHeight: 5,
                    backgroundColor: const Color(0xFFEAE7E0),
                    valueColor: AlwaysStoppedAnimation(color),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Text(formatMoney(line.amount),
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.text)),
        ],
      ),
    );
  }
}
