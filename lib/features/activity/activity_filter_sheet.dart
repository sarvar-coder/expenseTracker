import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../common/ui_utils.dart';
import 'activity_filter.dart';

/// "5 Iyul" or "5 Iyul – 9 Iyul". Shared by the sheet and the active chips.
String rangeLabel(DateTimeRange r) => DateUtils.isSameDay(r.start, r.end)
    ? uzDayMonth(r.start)
    : '${uzDayMonth(r.start)} – ${uzDayMonth(r.end)}';

/// "10 000 – 50 000", "10 000 dan", "50 000 gacha".
String amountLabel(int? min, int? max) {
  if (min != null && max != null) {
    return '${formatMoney(min)} – ${formatMoney(max)}';
  }
  return min != null ? '${formatMoney(min)} dan' : '${formatMoney(max!)} gacha';
}

/// Tarix filter sheet. Returns the new filter on Qo'llash / Tozalash, null when
/// dismissed.
Future<ActivityFilter?> showActivityFilterSheet(
  BuildContext context, {
  required ActivityFilter current,
  required List<Category> categories,
}) => showModalBottomSheet<ActivityFilter>(
  context: context,
  isScrollControlled: true,
  showDragHandle: true,
  constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.85),
  builder: (_) => _FilterSheet(current: current, categories: categories),
);

class _FilterSheet extends StatefulWidget {
  const _FilterSheet({required this.current, required this.categories});
  final ActivityFilter current;
  final List<Category> categories;

  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  late final Set<String> _cats = {...widget.current.categoryIds};
  late DateTimeRange? _range = widget.current.range;
  late bool? _private = widget.current.isPrivate;
  late final _min = TextEditingController(
    text: widget.current.minAmount?.toString() ?? '',
  );
  late final _max = TextEditingController(
    text: widget.current.maxAmount?.toString() ?? '',
  );

  @override
  void dispose() {
    _min.dispose();
    _max.dispose();
    super.dispose();
  }

  Future<void> _pickRange() async {
    final now = DateTime.now();
    final r = await showDateRangePicker(
      context: context,
      firstDate: DateTime(now.year - 5),
      lastDate: DateTime(now.year, now.month, now.day),
      initialDateRange: _range,
      helpText: 'Bitta kun uchun kunni ikki marta bosing',
    );
    if (r != null) setState(() => _range = r);
  }

  void _apply() {
    var min = parseAmount(_min.text);
    var max = parseAmount(_max.text);
    if (min != null && max != null && min > max) (min, max) = (max, min);
    Navigator.pop(
      context,
      ActivityFilter(
        categoryIds: _cats,
        range: _range,
        minAmount: min,
        maxAmount: max,
        isPrivate: _private,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    Widget heading(String s) => Padding(
      padding: const EdgeInsets.only(top: 20, bottom: 8),
      child: Text(s, style: t.titleMedium),
    );
    final digits = [FilteringTextInputFormatter.digitsOnly];

    return Padding(
      // Lift above the keyboard while typing amounts.
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: ListView(
              shrinkWrap: true,
              padding: const EdgeInsets.symmetric(horizontal: AppSpace.page),
              children: [
                Text('Filtr', style: t.headlineSmall),
                heading('Turkum'),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final cat in widget.categories)
                      FilterChip(
                        avatar: CircleAvatar(
                          radius: 5,
                          backgroundColor: colorFromHex(cat.colorHex),
                        ),
                        label: Text(cat.name),
                        selected: _cats.contains(cat.id),
                        onSelected: (on) => setState(
                          () => on ? _cats.add(cat.id) : _cats.remove(cat.id),
                        ),
                      ),
                  ],
                ),
                heading('Sana'),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.date_range, color: c.muted),
                  title: Text(_range == null ? 'Istalgan sana' : rangeLabel(_range!)),
                  onTap: _pickRange,
                  trailing: _range == null
                      ? null
                      : IconButton(
                          tooltip: 'Sanani tozalash',
                          icon: const Icon(Icons.close),
                          onPressed: () => setState(() => _range = null),
                        ),
                ),
                heading('Summa'),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _min,
                        keyboardType: TextInputType.number,
                        inputFormatters: digits,
                        decoration: const InputDecoration(hintText: 'dan'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextField(
                        controller: _max,
                        keyboardType: TextInputType.number,
                        inputFormatters: digits,
                        decoration: const InputDecoration(hintText: 'gacha'),
                      ),
                    ),
                  ],
                ),
                heading('Ko\'rinish'),
                SegmentedButton<bool?>(
                  showSelectedIcon: false,
                  segments: const [
                    ButtonSegment(value: null, label: Text('Hammasi')),
                    ButtonSegment(value: false, label: Text('Umumiy')),
                    ButtonSegment(value: true, label: Text('Shaxsiy')),
                  ],
                  selected: {_private},
                  onSelectionChanged: (s) => setState(() => _private = s.first),
                ),
              ],
            ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                AppSpace.page,
                AppSpace.gap,
                AppSpace.page,
                AppSpace.gap,
              ),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () =>
                          Navigator.pop(context, const ActivityFilter()),
                      child: const Text('Tozalash'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: _apply,
                      child: const Text('Qo\'llash'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
