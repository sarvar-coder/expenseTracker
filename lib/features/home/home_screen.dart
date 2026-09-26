import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../common/ui_utils.dart';
import '../settings/settings_screen.dart';
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
        const SizedBox(height: 16),
        _HeroCard(summary: s),
        const SizedBox(height: 24),
        Text(
          'Turkumlar bo\'yicha',
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w600,
            color: context.colors.text,
          ),
        ),
        const SizedBox(height: 10),
        if (s.byCategory.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 28),
            child: Center(
              child: Text(
                'Bu oy hali xarajat yo\'q',
                style: TextStyle(color: context.colors.muted),
              ),
            ),
          )
        else
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
              child: Column(
                children: [
                  for (final line in s.byCategory)
                    _CategoryRow(line: line, max: s.maxCatAmount),
                ],
              ),
            ),
          ),
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
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Jami sarflangan',
                style: TextStyle(fontSize: 12, color: context.colors.muted),
              ),
              Text(
                monthLabel,
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w600,
                  color: context.colors.text,
                ),
              ),
            ],
          ),
        ),
        IconButton(
          tooltip: 'Sozlamalar',
          icon: Icon(Icons.settings_outlined, color: context.colors.text),
          style: IconButton.styleFrom(
            backgroundColor: context.colors.card,
            minimumSize: const Size(48, 48),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(AppRadii.md),
              side: BorderSide(color: context.colors.border),
            ),
          ),
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const SettingsScreen()),
          ),
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
    final c = context.colors;
    final soft = c.onHero.withValues(alpha: 0.72);
    final over = summary.budget > 0 && summary.spent > summary.budget;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: c.hero,
        borderRadius: BorderRadius.circular(AppRadii.card),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Bu oy sarflangan', style: TextStyle(fontSize: 13, color: soft)),
          const SizedBox(height: 6),
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                formatMoney(summary.spent),
                style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.w600,
                  color: c.onHero,
                  letterSpacing: -0.5,
                ),
              ),
              const SizedBox(width: 6),
              Text('UZS', style: TextStyle(fontSize: 14, color: soft)),
            ],
          ),
          const SizedBox(height: 16),
          if (summary.budget <= 0)
            Text(
              'Sozlamalarda oylik byudjet belgilang',
              style: TextStyle(fontSize: 12, color: soft),
            )
          else ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(AppRadii.chip),
              child: LinearProgressIndicator(
                value: summary.progress,
                minHeight: 8,
                backgroundColor: c.onHero.withValues(alpha: 0.15),
                valueColor: AlwaysStoppedAnimation(
                  over ? c.danger : c.success,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${formatMoney(summary.budget)} dan ${summary.percent}%',
                  style: TextStyle(fontSize: 12, color: soft),
                ),
                Text(
                  '${formatMoney(summary.remaining)} qoldi',
                  style: TextStyle(fontSize: 12, color: soft),
                ),
              ],
            ),
          ],
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
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(AppRadii.md),
            ),
            child: Center(
              child: CircleAvatar(radius: 6, backgroundColor: color),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  line.category.name,
                  style: TextStyle(fontSize: 14, color: context.colors.text),
                ),
                const SizedBox(height: 6),
                ClipRRect(
                  borderRadius: BorderRadius.circular(AppRadii.chip),
                  child: LinearProgressIndicator(
                    value: frac,
                    minHeight: 5,
                    backgroundColor: context.colors.border,
                    valueColor: AlwaysStoppedAnimation(color),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Text(
            formatMoney(line.amount),
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
