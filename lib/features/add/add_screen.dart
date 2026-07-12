import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../data/db/tables.dart';
import '../../providers/providers.dart';
import '../../services/ai_parser.dart';
import '../../services/category_matcher.dart';
import '../common/ui_utils.dart';

enum AddMode { type, speak, manual }

/// Pushed from the shell FAB (new) or an Activity row (edit an existing
/// expense). Segmented Type / Speak / Manual; editing forces Manual.
class AddScreen extends ConsumerStatefulWidget {
  const AddScreen({super.key, this.editing});

  /// When set, the Manual form edits this expense in place instead of inserting.
  final Expense? editing;

  @override
  ConsumerState<AddScreen> createState() => _AddScreenState();
}

typedef _Prefill = ({String amount, String desc, int? categoryId});

class _AddScreenState extends ConsumerState<AddScreen> {
  late AddMode _mode = AddMode.manual;
  late _Prefill? _prefill = widget.editing == null
      ? null
      : (
          amount: widget.editing!.amount.toString(),
          desc: widget.editing!.description,
          categoryId: widget.editing!.categoryId,
        );

  /// Drop into the Manual form with fields prefilled (from Type "Edit" or a
  /// parse failure). A fresh ValueKey rebuilds the form state with the values.
  void _toManual({String amount = '', String desc = '', int? categoryId}) {
    setState(() {
      _prefill = (amount: amount, desc: desc, categoryId: categoryId);
      _mode = AddMode.manual;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.editing == null ? 'Xarajat qo\'shish' : 'Xarajatni tahrirlash'),
        actions: [
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: () => Navigator.of(context).maybePop(),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(18),
        children: [
          _ModeSegment(
            mode: _mode,
            onChanged: (m) => setState(() => _mode = m),
          ),
          const SizedBox(height: 16),
          switch (_mode) {
            AddMode.manual => _ManualForm(
              key: ValueKey(_prefill),
              editing: widget.editing,
              initialAmount: _prefill?.amount ?? '',
              initialDescription: _prefill?.desc ?? '',
              initialCategoryId: _prefill?.categoryId,
            ),
            AddMode.type => _TypeForm(onEdit: _toManual),
            AddMode.speak => _TypeForm(onEdit: _toManual, voice: true),
          },
        ],
      ),
    );
  }
}

class _ModeSegment extends StatelessWidget {
  const _ModeSegment({required this.mode, required this.onChanged});
  final AddMode mode;
  final ValueChanged<AddMode> onChanged;

  @override
  Widget build(BuildContext context) {
    Widget btn(AddMode m, IconData icon, String label) {
      final on = m == mode;
      return Expanded(
        child: GestureDetector(
          onTap: () => onChanged(m),
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(
              color: on ? AppColors.card : Colors.transparent,
              borderRadius: BorderRadius.circular(9),
              boxShadow: on
                  ? [const BoxShadow(color: Colors.black12, blurRadius: 3)]
                  : null,
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size: 16,
                  color: on ? AppColors.text : AppColors.muted,
                ),
                const SizedBox(width: 5),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 12.5,
                    color: on ? AppColors.text : AppColors.muted,
                    fontWeight: on ? FontWeight.w500 : FontWeight.normal,
                  ),
                ),
              ],
            ),
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
          btn(AddMode.type, Icons.edit_outlined, 'Yozish'),
          btn(AddMode.speak, Icons.mic_none, 'Aytish'),
          btn(AddMode.manual, Icons.list_alt, 'Qo\'lda'),
        ],
      ),
    );
  }
}

class _ManualForm extends ConsumerStatefulWidget {
  const _ManualForm({
    super.key,
    this.editing,
    this.initialAmount = '',
    this.initialDescription = '',
    this.initialCategoryId,
  });

  final Expense? editing;
  final String initialAmount;
  final String initialDescription;
  final int? initialCategoryId;

  @override
  ConsumerState<_ManualForm> createState() => _ManualFormState();
}

class _ManualFormState extends ConsumerState<_ManualForm> {
  late final _amount = TextEditingController(text: widget.initialAmount);
  late final _desc = TextEditingController(text: widget.initialDescription);
  late int? _categoryId = widget.initialCategoryId;
  late DateTime _date = widget.editing?.date ?? DateTime.now();
  bool _saving = false;

  @override
  void dispose() {
    _amount.dispose();
    _desc.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final amount = parseAmount(_amount.text);
    final desc = _desc.text.trim();
    if (amount == null || amount <= 0) {
      _toast('To\'g\'ri summa kiriting');
      return;
    }
    if (desc.isEmpty) {
      _toast('Tavsif kiriting');
      return;
    }
    if (_categoryId == null) {
      _toast('Turkum tanlang');
      return;
    }
    setState(() => _saving = true);
    final db = ref.read(databaseProvider);
    final editing = widget.editing;
    if (editing != null) {
      await db.updateExpense(
        editing.copyWith(
          description: desc,
          amount: amount,
          categoryId: _categoryId!,
          date: _date,
        ),
      );
    } else {
      await db.insertExpense(
        ExpensesCompanion.insert(
          description: desc,
          amount: amount,
          categoryId: _categoryId!,
          date: _date,
          source: ExpenseSource.manual,
          rawInput: const Value(null),
        ),
      );
    }
    if (!mounted) return;
    Navigator.of(context).maybePop();
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
    );
    if (picked != null) setState(() => _date = picked);
  }

  @override
  Widget build(BuildContext context) {
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _label('Summa'),
        TextField(
          controller: _amount,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: const InputDecoration(
            hintText: '45000',
            suffixText: 'UZS',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        _label('Tavsif'),
        TextField(
          controller: _desc,
          decoration: const InputDecoration(
            hintText: 'Kofe va kruassan',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        _label('Turkum'),
        DropdownButtonFormField<int>(
          initialValue: _categoryId,
          decoration: const InputDecoration(border: OutlineInputBorder()),
          hint: const Text('Turkum tanlang'),
          items: [
            for (final c in categories)
              DropdownMenuItem(
                value: c.id,
                child: Row(
                  children: [
                    Icon(
                      iconForKey(c.iconKey),
                      size: 18,
                      color: colorFromHex(c.colorHex),
                    ),
                    const SizedBox(width: 8),
                    Text(c.name),
                  ],
                ),
              ),
          ],
          onChanged: (v) => setState(() => _categoryId = v),
        ),
        const SizedBox(height: 12),
        _label('Sana'),
        InkWell(
          onTap: _pickDate,
          child: InputDecorator(
            decoration: const InputDecoration(border: OutlineInputBorder()),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('${uzDayMonth(_date)} ${_date.year}'),
                const Icon(
                  Icons.calendar_today,
                  size: 18,
                  color: AppColors.muted,
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        FilledButton.icon(
          onPressed: _saving ? null : _save,
          icon: const Icon(Icons.check),
          label: const Text('Saqlash'),
        ),
      ],
    );
  }

  Widget _label(String t) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Text(
      t,
      style: const TextStyle(fontSize: 12, color: AppColors.muted),
    ),
  );
}

/// Type / Speak mode: text (typed or transcribed) → Gemini → {item, amount,
/// category} preview → Save/Edit. When [voice] is set, a mic toggle streams
/// on-device STT into the text field; otherwise the field is typed by hand.
/// [onEdit] drops the (optionally prefilled) values into the Manual form.
class _TypeForm extends ConsumerStatefulWidget {
  const _TypeForm({required this.onEdit, this.voice = false});
  final void Function({String amount, String desc, int? categoryId}) onEdit;
  final bool voice;

  @override
  ConsumerState<_TypeForm> createState() => _TypeFormState();
}

class _TypeFormState extends ConsumerState<_TypeForm> {
  final _input = TextEditingController();
  bool _busy = false;
  bool _listening = false;
  ParsedExpense? _parsed;
  int? _parsedCategoryId;

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  Future<void> _toggleMic() async {
    final speech = ref.read(speechServiceProvider);
    if (_listening) {
      await speech.stop();
      if (mounted) setState(() => _listening = false);
      return;
    }
    if (!await speech.init()) {
      if (mounted) _toast('Mikrofon ishlamayapti — ruxsatlarni tekshiring');
      return;
    }
    await speech.listen(
      localeId: ref.read(settingsProvider).sttLocale,
      onResult: (words) {
        if (!mounted) return;
        _input.text = words;
        _input.selection = TextSelection.collapsed(offset: words.length);
      },
    );
    if (mounted) setState(() => _listening = true);
  }

  Future<void> _parse() async {
    final raw = _input.text.trim();
    if (raw.isEmpty) {
      _toast('Nima olganingiz va qanchaligini yozing');
      return;
    }
    setState(() => _busy = true);
    final parser = await ref.read(aiParserProvider)();
    if (parser == null) {
      if (!mounted) return;
      setState(() => _busy = false);
      _toast('Sozlamalarda Gemini kalitini qo\'shing — qo\'lda to\'ldirilmoqda');
      widget.onEdit(desc: raw);
      return;
    }
    final p = await parser.parse(raw);
    if (!mounted) return;
    if (p == null) {
      setState(() => _busy = false);
      _toast('Tahlil qilib bo\'lmadi — qo\'lda to\'ldiring');
      widget.onEdit(desc: raw);
      return;
    }
    final catId = await matchOrCreateCategory(
      ref.read(databaseProvider),
      p.category,
    );
    if (!mounted) return;
    setState(() {
      _busy = false;
      _parsed = p;
      _parsedCategoryId = catId;
    });
  }

  Future<void> _save() async {
    final p = _parsed!;
    setState(() => _busy = true);
    await ref
        .read(databaseProvider)
        .insertExpense(
          ExpensesCompanion.insert(
            description: p.item,
            amount: p.amount,
            categoryId: _parsedCategoryId!,
            date: DateTime.now(),
            source: widget.voice ? ExpenseSource.voice : ExpenseSource.typed,
            rawInput: Value(_input.text.trim()),
          ),
        );
    if (!mounted) return;
    Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    final p = _parsed;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (widget.voice) ...[
          Center(
            child: Column(
              children: [
                IconButton.filled(
                  onPressed: _busy ? null : _toggleMic,
                  iconSize: 32,
                  padding: const EdgeInsets.all(18),
                  style: IconButton.styleFrom(
                    backgroundColor: _listening
                        ? Colors.redAccent
                        : AppColors.accent,
                  ),
                  icon: Icon(_listening ? Icons.stop : Icons.mic),
                ),
                const SizedBox(height: 6),
                Text(
                  _listening
                      ? 'Tinglanmoqda… to\'xtatish uchun bosing'
                      : 'Mikrofonni bosing va nima olganingizni ayting',
                  style: const TextStyle(
                    fontSize: 12.5,
                    color: AppColors.muted,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
        ],
        TextField(
          controller: _input,
          minLines: 2,
          maxLines: 4,
          textInputAction: TextInputAction.done,
          decoration: InputDecoration(
            hintText: widget.voice
                ? 'Matn shu yerda chiqadi — kerak bo\'lsa tahrirlang'
                : 'masalan: kofe va kruassan 45000',
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 14),
        FilledButton.icon(
          onPressed: _busy ? null : _parse,
          icon: _busy
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.auto_awesome),
          label: const Text('AI bilan tahlil qilish'),
        ),
        if (p != null) ...[
          const SizedBox(height: 20),
          _ParsedCard(parsed: p, categoryId: _parsedCategoryId!),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _busy
                      ? null
                      : () => widget.onEdit(
                          amount: p.amount.toString(),
                          desc: p.item,
                          categoryId: _parsedCategoryId,
                        ),
                  icon: const Icon(Icons.edit_outlined),
                  label: const Text('Tahrirlash'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  onPressed: _busy ? null : _save,
                  icon: const Icon(Icons.check),
                  label: const Text('Saqlash'),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }
}

/// AI result preview: item, amount, category chip + "AI" tag.
class _ParsedCard extends ConsumerWidget {
  const _ParsedCard({required this.parsed, required this.categoryId});
  final ParsedExpense parsed;
  final int categoryId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cats =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final cat = cats.where((c) => c.id == categoryId).firstOrNull;
    final color = cat != null ? colorFromHex(cat.colorHex) : AppColors.accent;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  parsed.item,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                    color: AppColors.text,
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: AppColors.accent.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Text(
                  'AI',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accent,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                formatMoney(parsed.amount),
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w600,
                  color: AppColors.text,
                ),
              ),
              const SizedBox(width: 4),
              const Text(
                'UZS',
                style: TextStyle(fontSize: 13, color: AppColors.muted),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Icon(
                iconForKey(cat?.iconKey ?? 'category'),
                size: 16,
                color: color,
              ),
              const SizedBox(width: 6),
              Text(
                cat?.name ?? parsed.category,
                style: TextStyle(
                  fontSize: 13,
                  color: color,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
