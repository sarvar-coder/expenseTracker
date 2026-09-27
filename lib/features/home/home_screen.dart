import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../common/ui_utils.dart';
import '../common/widgets.dart';
import '../settings/settings_screen.dart';
import 'home_summary.dart';

/// Today: big total, slim month-budget line, today's expenses.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final month = ref.watch(homeSummaryProvider);
    final now = DateTime.now();
    final today = todayExpenses(
      ref.watch(expensesProvider).asData?.value ?? const <Expense>[],
      now,
    );
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final catById = {for (final c in categories) c.id: c};
    final total = today.fold(0, (sum, e) => sum + e.amount);
    final t = Theme.of(context).textTheme;

    return ListView(
      padding: EdgeInsets.fromLTRB(
        AppSpace.page,
        8,
        AppSpace.page,
        AppSpace.bottom(context, extra: 72), // clear the floating add button
      ),
      children: [
        _Header(date: now),
        const SizedBox(height: AppSpace.gap),
        _TodayHero(total: total, count: today.length, month: month),
        const SizedBox(height: AppSpace.section),
        Text('Bugungi xarajatlar', style: t.titleMedium),
        const SizedBox(height: 10),
        if (today.isEmpty)
          const EmptyState(
            icon: Icons.receipt_long_outlined,
            title: 'Bugun hali xarajat yo\'q',
            hint: 'Pastdagi tugma bilan birinchisini qo\'shing',
          )
        else
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                for (final e in today)
                  ExpenseTile(expense: e, category: catById[e.categoryId]),
              ],
            ),
          ),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.date});
  final DateTime date;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(uzDayMonth(date), style: t.labelMedium),
              Text('Bugun', style: t.headlineMedium),
            ],
          ),
        ),
        IconButton(
          tooltip: 'Sozlamalar',
          icon: Icon(Icons.settings_outlined, color: c.text),
          style: IconButton.styleFrom(
            backgroundColor: c.card,
            minimumSize: const Size(48, 48),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(AppRadii.md),
              side: BorderSide(color: c.border),
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

class _TodayHero extends StatelessWidget {
  const _TodayHero({
    required this.total,
    required this.count,
    required this.month,
  });
  final int total;
  final int count;
  final HomeSummary month;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final soft = c.onHero.withValues(alpha: 0.72);
    final over = month.budget > 0 && month.spent > month.budget;
    return Container(
      padding: const EdgeInsets.fromLTRB(22, 22, 22, 18),
      decoration: BoxDecoration(
        color: c.hero,
        borderRadius: BorderRadius.circular(AppRadii.hero),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            count == 0 ? 'Bugun sarflangan' : 'Bugun sarflangan · $count ta',
            style: t.labelMedium!.copyWith(color: soft),
          ),
          const SizedBox(height: 6),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Money(total, style: t.displayLarge, color: c.onHero),
          ),
          const SizedBox(height: 20),
          if (month.budget <= 0)
            Text(
              'Oy: ${formatMoney(month.spent)} UZS · Sozlamalarda byudjet belgilang',
              style: t.labelSmall!.copyWith(color: soft),
            )
          else ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(AppRadii.chip),
              child: LinearProgressIndicator(
                value: month.progress,
                minHeight: 6,
                backgroundColor: c.onHero.withValues(alpha: 0.15),
                valueColor: AlwaysStoppedAnimation(over ? c.danger : c.accent),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Oy: ${formatMoney(month.spent)} / ${formatMoney(month.budget)}',
                    style: t.labelSmall!.copyWith(color: soft),
                  ),
                ),
                Text(
                  over
                      ? '${formatMoney(-month.remaining)} oshdi'
                      : '${formatMoney(month.remaining)} qoldi',
                  style: t.labelSmall!.copyWith(
                    color: c.onHero,
                    fontWeight: over ? FontWeight.w800 : null,
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
