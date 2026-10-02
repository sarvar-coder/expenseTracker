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
/// month of the chosen year or a custom day range within it. Filtering and
/// aggregation live in the pure `insightsFor`.
class InsightsScreen extends ConsumerStatefulWidget {
  const InsightsScreen({super.key});

  @override
  ConsumerState<InsightsScreen> createState() => _InsightsScreenState();
}

class _InsightsScreenState extends ConsumerState<InsightsScreen> {
  static const _firstYear = 2020; // same floor as the add screen's date picker
  final _selectedKey = GlobalKey();
  int _year = DateTime.now().year;
  int? _month = DateTime.now().month; // null => _custom
  DateTimeRange? _custom;

  @override
  void initState() {
    super.initState();
    _scrollToSelected();
  }

  /// Last visible month of [year]: future months of the current year are hidden.
  int _lastMonth(int year) {
    final now = DateTime.now();
    return year == now.year ? now.month : 12;
  }

  void _scrollToSelected() => WidgetsBinding.instance.addPostFrameCallback((_) {
    final ctx = _selectedKey.currentContext;
    if (ctx != null) Scrollable.ensureVisible(ctx, alignment: 0.5);
  });

  Future<void> _pickYear() async {
    final now = DateTime.now();
    final year = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            for (var y = now.year; y >= _firstYear; y--)
              ListTile(
                title: Text('$y'),
                trailing: y == _year ? const Icon(Icons.check) : null,
                onTap: () => Navigator.pop(context, y),
              ),
          ],
        ),
      ),
    );
    if (year == null || year == _year) return;
    setState(() {
      final last = _lastMonth(year);
      _month = _month == null || _month! > last ? last : _month;
      _year = year;
      _custom = null; // custom range belonged to the old year
    });
    _scrollToSelected();
  }

  Future<void> _pickCustom() async {
    final now = DateTime.now();
    final last = _year == now.year
        ? DateTime(now.year, now.month, now.day)
        : DateTime(_year, 12, 31);
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime(_year),
      lastDate: last,
      initialDateRange: _custom,
      helpText: 'Bitta kun uchun kunni ikki marta bosing',
    );
    if (range == null) return;
    setState(() {
      _custom = range;
      _month = null;
    });
  }

  String get _customLabel {
    final r = _custom;
    if (r == null) return 'Maxsus';
    if (DateUtils.isSameDay(r.start, r.end)) return uzDayMonth(r.start);
    return '${uzDayMonth(r.start)} – ${uzDayMonth(r.end)}';
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final expenses =
        ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final custom = _custom;
    final (start, end) = custom == null
        ? (DateTime(_year, _month!), DateTime(_year, _month! + 1))
        : (
            custom.start,
            DateUtils.addDaysToDate(custom.end, 1),
          ); // end day inclusive
    final data = insightsFor(expenses, categories, start, end);

    Widget pill(String label, bool selected, VoidCallback onTap) => Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        key: selected ? _selectedKey : null,
        label: Text(label),
        selected: selected,
        onSelected: (_) => onTap(),
      ),
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
            Expanded(child: Text('Tahlil', style: t.headlineMedium)),
            TextButton.icon(
              onPressed: _pickYear,
              iconAlignment: IconAlignment.end,
              icon: const Icon(Icons.expand_more),
              label: Text('$_year'),
            ),
          ],
        ),
        const SizedBox(height: AppSpace.gap),
        SizedBox(
          height: 48,
          // Not a lazy ListView: the selected chip must be built for
          // ensureVisible even when it starts off-screen.
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (var m = 1; m <= _lastMonth(_year); m++)
                  pill(uzMonths[m - 1], _month == m, () {
                    setState(() {
                      _month = m;
                      _custom = null;
                    });
                  }),
                pill(_customLabel, _month == null, _pickCustom),
              ],
            ),
          ),
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
