import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../common/ui_utils.dart';
import 'activity_filter.dart';

/// "5 Iyul" or "5 Iyul – 9 Iyul". Shared by the page and the active chips.
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

/// Tarix filter page. Returns the new filter on Qo'llash, null on back.
Future<ActivityFilter?> openActivityFilter(
  BuildContext context, {
  required ActivityFilter current,
  required List<Category> categories,
}) => Navigator.of(context).push<ActivityFilter>(
  MaterialPageRoute(
    builder: (_) => _FilterPage(current: current, categories: categories),
  ),
);

class _FilterPage extends StatefulWidget {
  const _FilterPage({required this.current, required this.categories});
  final ActivityFilter current;
  final List<Category> categories;

  @override
  State<_FilterPage> createState() => _FilterPageState();
}

class _FilterPageState extends State<_FilterPage> {
  late Set<String> _cats = {...widget.current.categoryIds};
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

  void _clear() => setState(() {
    _cats = {};
    _range = null;
    _private = null;
    _min.clear();
    _max.clear();
  });

  Future<void> _pickCategories() async {
    final picked = await showModalBottomSheet<Set<String>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      constraints: BoxConstraints(
        maxHeight: MediaQuery.sizeOf(context).height * 0.7,
      ),
      builder: (_) =>
          _CategorySheet(categories: widget.categories, selected: _cats),
    );
    if (picked != null) setState(() => _cats = picked);
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

  String get _catLabel {
    if (_cats.isEmpty) return 'Hammasi';
    if (_cats.length == 1) {
      final id = _cats.single;
      return widget.categories.where((c) => c.id == id).firstOrNull?.name ??
          '1 ta turkum';
    }
    return '${_cats.length} ta turkum';
  }

  String get _amountLabel {
    final min = parseAmount(_min.text);
    final max = parseAmount(_max.text);
    return min == null && max == null ? 'Istalgan' : amountLabel(min, max);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final digits = [FilteringTextInputFormatter.digitsOnly];
    // Strip ExpansionTile's default dividers so all four rows look alike.
    const noBorder = Border();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Filtr'),
        actions: [TextButton(onPressed: _clear, child: const Text('Tozalash'))],
      ),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          ListTile(
            leading: Icon(Icons.category_outlined, color: c.muted),
            title: const Text('Turkum'),
            subtitle: Text(_catLabel),
            trailing: const Icon(Icons.chevron_right),
            onTap: _pickCategories,
          ),
          ListTile(
            leading: Icon(Icons.date_range, color: c.muted),
            title: const Text('Sana'),
            subtitle: Text(_range == null ? 'Istalgan' : rangeLabel(_range!)),
            onTap: _pickRange,
            trailing: _range == null
                ? const Icon(Icons.chevron_right)
                : IconButton(
                    tooltip: 'Sanani tozalash',
                    icon: const Icon(Icons.close),
                    onPressed: () => setState(() => _range = null),
                  ),
          ),
          ExpansionTile(
            shape: noBorder,
            collapsedShape: noBorder,
            leading: Icon(Icons.payments_outlined, color: c.muted),
            title: const Text('Summa'),
            subtitle: Text(_amountLabel),
            childrenPadding: const EdgeInsets.fromLTRB(
              AppSpace.page,
              0,
              AppSpace.page,
              AppSpace.gap,
            ),
            children: [
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _min,
                      keyboardType: TextInputType.number,
                      inputFormatters: digits,
                      onChanged: (_) => setState(() {}),
                      decoration: const InputDecoration(hintText: 'dan'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _max,
                      keyboardType: TextInputType.number,
                      inputFormatters: digits,
                      onChanged: (_) => setState(() {}),
                      decoration: const InputDecoration(hintText: 'gacha'),
                    ),
                  ),
                ],
              ),
            ],
          ),
          ExpansionTile(
            shape: noBorder,
            collapsedShape: noBorder,
            leading: Icon(Icons.visibility_outlined, color: c.muted),
            title: const Text('Ko\'rinish'),
            subtitle: Text(switch (_private) {
              null => 'Hammasi',
              false => 'Umumiy',
              true => 'Shaxsiy',
            }),
            children: [
              RadioGroup<bool?>(
                groupValue: _private,
                onChanged: (v) => setState(() => _private = v),
                child: const Column(
                  children: [
                    RadioListTile<bool?>(value: null, title: Text('Hammasi')),
                    RadioListTile<bool?>(value: false, title: Text('Umumiy')),
                    RadioListTile<bool?>(value: true, title: Text('Shaxsiy')),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpace.page,
            8,
            AppSpace.page,
            AppSpace.gap,
          ),
          child: FilledButton(
            onPressed: _apply,
            child: const Text('Qo\'llash'),
          ),
        ),
      ),
    );
  }
}

/// Multi-select category list; "Tayyor" returns the picked ids.
class _CategorySheet extends StatefulWidget {
  const _CategorySheet({required this.categories, required this.selected});
  final List<Category> categories;
  final Set<String> selected;

  @override
  State<_CategorySheet> createState() => _CategorySheetState();
}

class _CategorySheetState extends State<_CategorySheet> {
  late final Set<String> _picked = {...widget.selected};

  @override
  Widget build(BuildContext context) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      Flexible(
        child: ListView(
          shrinkWrap: true,
          children: [
            for (final cat in widget.categories)
              CheckboxListTile(
                secondary: CircleAvatar(
                  radius: 8,
                  backgroundColor: colorFromHex(cat.colorHex),
                ),
                title: Text(cat.name),
                value: _picked.contains(cat.id),
                onChanged: (on) => setState(
                  () => on! ? _picked.add(cat.id) : _picked.remove(cat.id),
                ),
              ),
          ],
        ),
      ),
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpace.page,
            8,
            AppSpace.page,
            AppSpace.gap,
          ),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => setState(_picked.clear),
                  child: const Text('Hammasi'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton(
                  onPressed: () => Navigator.pop(context, _picked),
                  child: const Text('Tayyor'),
                ),
              ),
            ],
          ),
        ),
      ),
    ],
  );
}

/// Removable chips for each active filter part; tapping × drops just that part.
class ActiveFilters extends StatelessWidget {
  const ActiveFilters({
    super.key,
    required this.filter,
    required this.catById,
    required this.onChanged,
    this.leading,
  });
  final ActivityFilter filter;
  final Map<String, Category> catById;
  final ValueChanged<ActivityFilter> onChanged;
  final Widget? leading; // non-removable chip shown first (Tahlil's period)

  @override
  Widget build(BuildContext context) {
    final f = filter;
    Widget chip(String label, ActivityFilter without, {Color? dot}) =>
        InputChip(
          avatar: dot == null
              ? null
              : CircleAvatar(radius: 5, backgroundColor: dot),
          label: Text(label),
          onDeleted: () => onChanged(without),
          deleteButtonTooltipMessage: 'Olib tashlash',
        );

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        ?leading,
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
