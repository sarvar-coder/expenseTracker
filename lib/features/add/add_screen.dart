import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../data/db/tables.dart';
import '../../providers/providers.dart';
import '../../services/category_matcher.dart';
import '../../services/sync_service.dart' show canCreateCategories;
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

typedef _Prefill = ({
  String amount,
  String desc,
  String? categoryId,
  bool private,
});

class _AddScreenState extends ConsumerState<AddScreen> {
  // Editing forces Manual; otherwise reopen in the last picked mode.
  late AddMode _mode = widget.editing != null
      ? AddMode.manual
      : AddMode.values.asNameMap()[ref.read(settingsStoreProvider).lastAddMode] ??
            AddMode.type;
  late _Prefill? _prefill = widget.editing == null
      ? null
      : (
          amount: widget.editing!.amount.toString(),
          desc: widget.editing!.description,
          categoryId: widget.editing!.categoryId,
          private: widget.editing!.isPrivate,
        );

  /// Drop into the Manual form with fields prefilled (from Type "Edit" or a
  /// parse failure). A fresh ValueKey rebuilds the form state with the values.
  void _toManual({
    String amount = '',
    String desc = '',
    String? categoryId,
    bool? private,
  }) {
    setState(() {
      _prefill = (
        amount: amount,
        desc: desc,
        categoryId: categoryId,
        private: private ?? ref.read(settingsProvider).defaultPrivate,
      );
      _mode = AddMode.manual;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.editing == null ? 'Xarajat qo\'shish' : 'Xarajatni tahrirlash',
        ),
      ),
      body: ListView(
        padding: EdgeInsets.fromLTRB(
          AppSpace.page,
          8,
          AppSpace.page,
          AppSpace.bottom(context),
        ),
        children: [
          SegmentedButton<AddMode>(
            showSelectedIcon: false,
            // Labels only: the Speak form has its own mic, one mic on screen.
            segments: const [
              ButtonSegment(value: AddMode.type, label: Text('Yozish')),
              ButtonSegment(value: AddMode.speak, label: Text('Aytish')),
              ButtonSegment(value: AddMode.manual, label: Text('Qo\'lda')),
            ],
            selected: {_mode},
            onSelectionChanged: (s) {
              setState(() => _mode = s.first);
              ref.read(settingsStoreProvider).setLastAddMode(s.first.name);
            },
          ),
          const SizedBox(height: AppSpace.gap + 4),
          switch (_mode) {
            AddMode.manual => _ManualForm(
              key: ValueKey(_prefill),
              editing: widget.editing,
              initialAmount: _prefill?.amount ?? '',
              initialDescription: _prefill?.desc ?? '',
              initialCategoryId: _prefill?.categoryId,
              initialPrivate:
                  _prefill?.private ?? ref.read(settingsProvider).defaultPrivate,
            ),
            AddMode.type => _TypeForm(onEdit: _toManual),
            AddMode.speak => _TypeForm(onEdit: _toManual, voice: true),
          },
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
    this.initialPrivate = false,
  });

  final Expense? editing;
  final String initialAmount;
  final String initialDescription;
  final String? initialCategoryId;
  final bool initialPrivate;

  @override
  ConsumerState<_ManualForm> createState() => _ManualFormState();
}

class _ManualFormState extends ConsumerState<_ManualForm> {
  late final _amount = TextEditingController(text: widget.initialAmount);
  late final _desc = TextEditingController(text: widget.initialDescription);
  late String? _categoryId = widget.initialCategoryId;
  late DateTime _date = widget.editing?.date ?? DateTime.now();
  late bool _private = widget.initialPrivate;
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
          isPrivate: _private,
          // picked another category: no longer waiting for the requested one
          pendingCategory: _categoryId == editing.categoryId
              ? Value(editing.pendingCategory)
              : const Value(null),
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
          isPrivate: Value(_private),
        ),
      );
    }
    if (!mounted) return;
    Navigator.of(context).maybePop();
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  Future<void> _delete() async {
    final editing = widget.editing!;
    if (!await confirmDeleteExpense(context, editing.description)) return;
    await ref.read(databaseProvider).deleteExpense(editing.id);
    if (!mounted) return;
    _toast('Xarajat o\'chirildi');
    Navigator.of(context).maybePop();
  }

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
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final now = DateTime.now();
    final isToday = DateUtils.isSameDay(_date, now);
    final isYesterday =
        DateUtils.isSameDay(_date, now.subtract(const Duration(days: 1)));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Summa', style: t.labelMedium),
                TextField(
                  controller: _amount,
                  keyboardType: TextInputType.number,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  style: t.displaySmall,
                  decoration: InputDecoration(
                    hintText: '0',
                    hintStyle: t.displaySmall!.copyWith(color: c.border),
                    suffixText: 'UZS',
                    suffixStyle: t.titleMedium!.copyWith(color: c.muted),
                    filled: false,
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    contentPadding: const EdgeInsets.symmetric(vertical: 8),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: AppSpace.gap),
        _label('Tavsif'),
        TextField(
          controller: _desc,
          textCapitalization: TextCapitalization.sentences,
          decoration: const InputDecoration(hintText: 'Kofe va kruassan'),
        ),
        const SizedBox(height: AppSpace.gap),
        _label('Turkum'),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final cat in categories)
              ChoiceChip(
                avatar: CircleAvatar(
                  radius: 5,
                  backgroundColor: colorFromHex(cat.colorHex),
                ),
                label: Text(cat.name),
                selected: _categoryId == cat.id,
                onSelected: (_) => setState(() => _categoryId = cat.id),
              ),
          ],
        ),
        const SizedBox(height: AppSpace.gap),
        _label('Sana'),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            ChoiceChip(
              label: const Text('Bugun'),
              selected: isToday,
              onSelected: (_) => setState(() => _date = now),
            ),
            ChoiceChip(
              label: const Text('Kecha'),
              selected: isYesterday,
              onSelected: (_) => setState(
                () => _date = now.subtract(const Duration(days: 1)),
              ),
            ),
            ChoiceChip(
              avatar: const Icon(Icons.calendar_today, size: 16),
              label: Text(
                isToday || isYesterday
                    ? 'Boshqa sana'
                    : '${uzDayMonth(_date)} ${_date.year}',
              ),
              selected: !isToday && !isYesterday,
              onSelected: (_) => _pickDate(),
            ),
          ],
        ),
        const SizedBox(height: AppSpace.gap),
        _PrivateSwitch(
          value: _private,
          onChanged: (v) => setState(() => _private = v),
        ),
        const SizedBox(height: AppSpace.section),
        FilledButton.icon(
          onPressed: _saving ? null : _save,
          icon: const Icon(Icons.check),
          label: const Text('Saqlash'),
        ),
        if (widget.editing != null) ...[
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: _saving ? null : _delete,
            style: OutlinedButton.styleFrom(
              foregroundColor: c.danger,
              side: BorderSide(color: c.danger, width: 1.5),
            ),
            icon: const Icon(Icons.delete_outline),
            label: const Text('O\'chirish'),
          ),
        ],
      ],
    );
  }

  Widget _label(String text) => Padding(
    padding: const EdgeInsets.only(left: 4, bottom: 8),
    child: Text(text, style: Theme.of(context).textTheme.labelMedium),
  );
}

/// Type / Speak mode: text (typed or transcribed) → Gemini → {item, amount,
/// category, date} → saved straight away. When [voice] is set, a mic toggle
/// streams on-device STT into the text field; otherwise the field is typed by
/// hand. [onEdit] drops the raw text into the Manual form when parsing fails.
class _TypeForm extends ConsumerStatefulWidget {
  const _TypeForm({required this.onEdit, this.voice = false});
  final void Function({
    String amount,
    String desc,
    String? categoryId,
    bool? private,
  })
  onEdit;
  final bool voice;

  @override
  ConsumerState<_TypeForm> createState() => _TypeFormState();
}

class _TypeFormState extends ConsumerState<_TypeForm> {
  final _input = TextEditingController();
  bool _busy = false;
  bool _listening = false;

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

  /// Text → AI → saved expense (amount, category, date from the AI). Pops with
  /// an Undo snackbar; on parse failure drops into Manual with the raw text.
  Future<void> _parse() async {
    final raw = _input.text.trim();
    if (raw.isEmpty) {
      _toast('Nima olganingiz va qanchaligini yozing');
      return;
    }
    if (_listening) await _toggleMic();
    if (!mounted) return;
    setState(() => _busy = true);
    final db = ref.read(databaseProvider);
    final names = [for (final c in await db.getCategories()) c.name];
    final p = await ref.read(aiParserProvider).parse(raw, categories: names);
    if (!mounted) return;
    if (p == null) {
      setState(() => _busy = false);
      _toast('Tahlil qilib bo\'lmadi — qo\'lda to\'ldiring');
      widget.onEdit(desc: raw);
      return;
    }
    final cat = await resolveCategory(
      db,
      p.category,
      canCreate: canCreateCategories(ref.read(sharedPrefsProvider)),
    );
    final id = await db.insertExpense(
      ExpensesCompanion.insert(
        description: p.item,
        amount: p.amount,
        categoryId: cat.id,
        date: p.date,
        source: widget.voice ? ExpenseSource.voice : ExpenseSource.typed,
        rawInput: Value(raw),
        pendingCategory: Value(cat.pending),
        isPrivate: Value(ref.read(settingsProvider).defaultPrivate),
      ),
    );
    if (!mounted) return;
    // Captured before pop: the shell's messenger outlives this screen.
    final messenger = ScaffoldMessenger.of(context);
    Navigator.of(context).maybePop();
    messenger.showSnackBar(
      SnackBar(
        content: Text('Qo\'shildi: ${p.item} — ${formatMoney(p.amount)} UZS'),
        action: SnackBarAction(
          label: 'Bekor qilish',
          onPressed: () => db.deleteExpense(id),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.voice) ...[
          const SizedBox(height: 8),
          Center(
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 250),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: (_listening ? c.danger : c.accent).withValues(
                  alpha: _listening ? 0.18 : 0.10,
                ),
              ),
              child: IconButton.filled(
                onPressed: _busy ? null : _toggleMic,
                iconSize: 36,
                padding: const EdgeInsets.all(24),
                style: IconButton.styleFrom(
                  backgroundColor: _listening ? c.danger : c.accent,
                  foregroundColor: c.onAccent,
                ),
                tooltip: _listening ? 'To\'xtatish' : 'Gapirish',
                icon: Icon(_listening ? Icons.stop : Icons.mic),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            _listening
                ? 'Tinglanmoqda… to\'xtatish uchun bosing'
                : 'Mikrofonni bosing va nima olganingizni ayting',
            style: t.bodySmall,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: AppSpace.gap),
        ],
        TextField(
          controller: _input,
          minLines: 3,
          maxLines: 5,
          style: t.bodyLarge,
          textCapitalization: TextCapitalization.sentences,
          textInputAction: TextInputAction.done,
          decoration: InputDecoration(
            hintText: widget.voice
                ? 'Matn shu yerda chiqadi — kerak bo\'lsa tahrirlang'
                : 'masalan: kofe va kruassan 45000',
          ),
        ),
        const SizedBox(height: AppSpace.gap),
        FilledButton.icon(
          onPressed: _busy ? null : _parse,
          icon: _busy
              ? SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: c.muted,
                  ),
                )
              : const Icon(Icons.auto_awesome),
          label: const Text('AI bilan qo\'shish'),
        ),
      ],
    );
  }
}

/// Per-expense "hide from family" toggle; starts from the Settings default.
class _PrivateSwitch extends StatelessWidget {
  const _PrivateSwitch({required this.value, required this.onChanged});
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) => Card(
    child: SwitchListTile(
      value: value,
      onChanged: onChanged,
      secondary: const Icon(Icons.visibility_off_outlined),
      title: const Text('Maxfiy'),
      subtitle: const Text('Oila bu xarajatni ko\'rmaydi'),
    ),
  );
}
