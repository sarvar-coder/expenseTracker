import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../../services/csv_export.dart';
import '../common/ui_utils.dart';

const _currencies = ['UZS', 'USD', 'EUR'];
const _locales = {'en_US': 'Inglizcha', 'uz_UZ': 'O\'zbekcha', 'ru_RU': 'Ruscha'};

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  bool _hasKey = false;

  @override
  void initState() {
    super.initState();
    ref
        .read(settingsStoreProvider)
        .hasApiKey()
        .then((v) => mounted ? setState(() => _hasKey = v) : null);
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(settingsProvider);
    final ctrl = ref.read(settingsProvider.notifier);

    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 32),
      children: [
        const Text('Sozlamalar',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600, color: AppColors.text)),
        const SizedBox(height: 8),

        _sectionHeader('Umumiy'),
        _row(
          icon: Icons.payments_outlined,
          title: 'Valyuta',
          subtitle: s.currencyCode,
          onTap: () => _pickOption(
            title: 'Valyuta',
            current: s.currencyCode,
            options: {for (final c in _currencies) c: c},
            onPick: ctrl.setCurrency,
          ),
        ),
        _row(
          icon: Icons.account_balance_wallet_outlined,
          title: 'Oylik byudjet',
          subtitle: s.monthlyBudget > 0 ? '${formatMoney(s.monthlyBudget)} ${s.currencyCode}' : 'Belgilanmagan',
          onTap: () => _editBudget(s.monthlyBudget, ctrl.setBudget),
        ),
        _row(
          icon: Icons.category_outlined,
          title: 'Turkumlar',
          subtitle: 'Nomini o\'zgartirish yoki arxivlash',
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const _CategoriesScreen()),
          ),
        ),

        _sectionHeader('AI va ovoz'),
        _row(
          icon: Icons.key_outlined,
          title: 'Gemini API kaliti',
          subtitle: _hasKey ? 'Kalit o\'rnatilgan' : 'O\'rnatilmagan',
          onTap: _editApiKey,
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(52, 0, 4, 8),
          child: Text(
            'Bepul tarif so\'rovlari Google tomonidan modellarini yaxshilash uchun ishlatilishi mumkin.',
            style: TextStyle(fontSize: 11.5, color: AppColors.muted),
          ),
        ),
        _row(
          icon: Icons.mic_none,
          title: 'Voice language',
          subtitle: _locales[s.sttLocale] ?? s.sttLocale,
          onTap: () => _pickOption(
            title: 'Ovoz tili',
            current: s.sttLocale,
            options: _locales,
            onPick: ctrl.setLocale,
          ),
        ),

        _sectionHeader('Ma\'lumotlar'),
        _row(
          icon: Icons.ios_share,
          title: 'Ma\'lumotni eksport (CSV)',
          subtitle: 'Barcha xarajatlarni ulashish',
          onTap: _export,
        ),
      ],
    );
  }

  Future<void> _pickOption({
    required String title,
    required String current,
    required Map<String, String> options,
    required Future<void> Function(String) onPick,
  }) async {
    final picked = await showDialog<String>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text(title),
        children: [
          for (final e in options.entries)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(ctx, e.key),
              child: Row(
                children: [
                  Icon(
                    e.key == current ? Icons.check : null,
                    size: 18,
                    color: AppColors.accent,
                  ),
                  const SizedBox(width: 10),
                  Text(e.value),
                ],
              ),
            ),
        ],
      ),
    );
    if (picked != null) await onPick(picked);
  }

  Future<void> _editBudget(int current, Future<void> Function(int) onSave) async {
    final ctl = TextEditingController(text: current > 0 ? current.toString() : '');
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Oylik byudjet'),
        content: TextField(
          controller: ctl,
          autofocus: true,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: const InputDecoration(hintText: '4000000', suffixText: 'UZS'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Bekor qilish')),
          TextButton(onPressed: () => Navigator.pop(ctx, ctl.text), child: const Text('Saqlash')),
        ],
      ),
    );
    if (result == null) return;
    final amount = parseAmount(result);
    if (amount == null || amount <= 0) {
      _toast('To\'g\'ri summa kiriting');
      return;
    }
    await onSave(amount);
  }

  Future<void> _editApiKey() async {
    final ctl = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Gemini API kaliti'),
        content: TextField(
          controller: ctl,
          autofocus: true,
          obscureText: true,
          decoration: const InputDecoration(hintText: 'Kalitni joylashtiring'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Bekor qilish')),
          TextButton(onPressed: () => Navigator.pop(ctx, ctl.text), child: const Text('Saqlash')),
        ],
      ),
    );
    if (result == null) return;
    final key = result.trim();
    if (key.isEmpty) {
      _toast('Kalit bo\'sh');
      return;
    }
    await ref.read(settingsStoreProvider).setApiKey(key);
    if (mounted) setState(() => _hasKey = true);
  }

  Future<void> _export() async {
    final db = ref.read(databaseProvider);
    final rows = await db.getExpenses();
    if (rows.isEmpty) {
      _toast('Eksport uchun hech narsa yo\'q');
      return;
    }
    final names = {for (final c in await db.getAllCategories()) c.id: c.name};
    await shareExpensesCsv(expensesCsv(rows, names));
  }

  Widget _sectionHeader(String label) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 22, 4, 6),
        child: Text(label,
            style: const TextStyle(
                fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.muted)),
      );

  Widget _row({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) =>
      Container(
        margin: const EdgeInsets.only(bottom: 8),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(AppRadii.field),
          border: Border.all(color: AppColors.border),
        ),
        child: ListTile(
          onTap: onTap,
          leading: Icon(icon, color: AppColors.accent),
          title: Text(title, style: const TextStyle(fontSize: 15, color: AppColors.text)),
          subtitle: Text(subtitle, style: const TextStyle(fontSize: 12.5, color: AppColors.muted)),
          trailing: const Icon(Icons.chevron_right, color: AppColors.muted),
        ),
      );
}

/// Manage categories: rename + archive/unarchive. Shows archived (muted) so they
/// can be restored. No add — AI creates categories on the fly.
class _CategoriesScreen extends ConsumerStatefulWidget {
  const _CategoriesScreen();

  @override
  ConsumerState<_CategoriesScreen> createState() => _CategoriesScreenState();
}

class _CategoriesScreenState extends ConsumerState<_CategoriesScreen> {
  late Future<List<Category>> _future = _load();

  Future<List<Category>> _load() => ref.read(databaseProvider).getAllCategories();

  void _refresh() => setState(() => _future = _load());

  Future<void> _rename(Category c) async {
    final ctl = TextEditingController(text: c.name);
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Turkum nomini o\'zgartirish'),
        content: TextField(controller: ctl, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Bekor qilish')),
          TextButton(onPressed: () => Navigator.pop(ctx, ctl.text.trim()), child: const Text('Saqlash')),
        ],
      ),
    );
    if (name == null || name.isEmpty || name == c.name) return;
    await ref.read(databaseProvider).updateCategory(c.copyWith(name: name));
    _refresh();
  }

  Future<void> _toggleArchive(Category c) async {
    await ref.read(databaseProvider).updateCategory(c.copyWith(isArchived: !c.isArchived));
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Turkumlar')),
      body: FutureBuilder<List<Category>>(
        future: _future,
        builder: (context, snap) {
          final cats = snap.data;
          if (cats == null) return const Center(child: CircularProgressIndicator());
          return ListView(
            padding: const EdgeInsets.all(12),
            children: [
              for (final c in cats)
                ListTile(
                  leading: Icon(iconForKey(c.iconKey),
                      color: c.isArchived ? AppColors.muted : colorFromHex(c.colorHex)),
                  title: Text(
                    c.name,
                    style: TextStyle(color: c.isArchived ? AppColors.muted : AppColors.text),
                  ),
                  subtitle: c.isArchived ? const Text('Arxivlangan') : null,
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.edit_outlined, size: 20),
                        onPressed: () => _rename(c),
                      ),
                      IconButton(
                        icon: Icon(c.isArchived ? Icons.unarchive_outlined : Icons.archive_outlined,
                            size: 20),
                        onPressed: () => _toggleArchive(c),
                      ),
                    ],
                  ),
                ),
            ],
          );
        },
      ),
    );
  }
}
