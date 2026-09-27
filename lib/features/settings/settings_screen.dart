import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../../services/category_matcher.dart';
import '../../services/csv_export.dart';
import '../../services/sync_service.dart' show canCreateCategories;
import '../common/ui_utils.dart';
import '../common/widgets.dart';

const _locales = {
  'en_US': 'Inglizcha',
  'uz_UZ': 'O\'zbekcha',
  'ru_RU': 'Ruscha',
};

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(settingsProvider);
    final ctrl = ref.read(settingsProvider.notifier);

    // Pushed from the Home gear, so it owns its Scaffold (back arrow).
    return Scaffold(
      appBar: AppBar(title: const Text('Sozlamalar')),
      body: ListView(
        padding: EdgeInsets.fromLTRB(
          AppSpace.page,
          0,
          AppSpace.page,
          AppSpace.bottom(context),
        ),
        children: [
          _section('Umumiy', [
            _row(
              icon: Icons.account_balance_wallet_outlined,
              title: 'Oylik byudjet',
              subtitle: s.monthlyBudget > 0
                  ? '${formatMoney(s.monthlyBudget)} UZS'
                  : 'Belgilanmagan',
              onTap: () => _editBudget(s.monthlyBudget, ctrl.setBudget),
            ),
            _row(
              icon: Icons.category_outlined,
              title: 'Turkumlar',
              subtitle: 'Qo\'shish, tahrirlash, arxivlash',
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const _CategoriesScreen()),
              ),
            ),
            _row(
              icon: Icons.visibility_off_outlined,
              title: 'Yangi xarajatlar maxfiy',
              subtitle: 'Oila maxfiy xarajatlarni ko\'rmaydi',
              onTap: () => ctrl.setDefaultPrivate(!s.defaultPrivate),
              trailing: Switch(
                value: s.defaultPrivate,
                onChanged: ctrl.setDefaultPrivate,
              ),
            ),
          ]),
          _section('AI va ovoz', [
            _row(
              icon: Icons.mic_none,
              title: 'Ovoz tili',
              subtitle: _locales[s.sttLocale] ?? s.sttLocale,
              onTap: () => _pickOption(
                title: 'Ovoz tili',
                current: s.sttLocale,
                options: _locales,
                onPick: ctrl.setLocale,
              ),
            ),
          ]),
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 10, 4, 0),
            child: Text(
              'Bepul tarif so\'rovlari Google tomonidan modellarini yaxshilash uchun ishlatilishi mumkin.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
          _section('Hisob', [
            _row(
              icon: Icons.logout,
              title: 'Chiqish',
              subtitle: ref.watch(sessionEmailProvider).value ?? '',
              onTap: _signOut,
            ),
          ]),
          _section('Ma\'lumotlar', [
            _row(
              icon: Icons.ios_share,
              title: 'Ma\'lumotni eksport (CSV)',
              subtitle: 'Barcha xarajatlarni ulashish',
              onTap: _export,
            ),
          ]),
        ],
      ),
    );
  }

  Future<void> _signOut() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Hisobdan chiqasizmi?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Bekor qilish'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Chiqish'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    // Flush unsynced edits: the next account to sign in here wipes local rows.
    await ref.read(syncProvider).run().timeout(const Duration(seconds: 5), onTimeout: () {});
    if (!mounted) return;
    // Pop back to the gate first; it then shows the sign-in screen.
    Navigator.of(context).popUntil((r) => r.isFirst);
    await ref.read(authProvider).signOut();
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
                    color: context.colors.accent,
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

  Future<void> _editBudget(
    int current,
    Future<void> Function(int) onSave,
  ) async {
    final ctl = TextEditingController(
      text: current > 0 ? current.toString() : '',
    );
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Oylik byudjet'),
        content: TextField(
          controller: ctl,
          autofocus: true,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: const InputDecoration(
            hintText: '4000000',
            suffixText: 'UZS',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Bekor qilish'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, ctl.text),
            child: const Text('Saqlash'),
          ),
        ],
      ),
    );
    if (result == null) return;
    // 0 or empty clears the budget ("Belgilanmagan").
    final amount = result.isEmpty ? 0 : parseAmount(result);
    if (amount == null) {
      _toast('To\'g\'ri summa kiriting');
      return;
    }
    await onSave(amount);
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

  /// Muted label over one card of rows, divided by hairlines.
  Widget _section(String label, List<Widget> rows) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(4, AppSpace.section, 4, 10),
        child: Text(label, style: Theme.of(context).textTheme.labelMedium),
      ),
      Card(
        child: Column(
          children: [
            for (final (i, r) in rows.indexed) ...[
              if (i > 0) const Divider(indent: 72),
              r,
            ],
          ],
        ),
      ),
    ],
  );

  Widget _row({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    Widget? trailing,
  }) {
    final c = context.colors;
    // Merged so a trailing Switch reads as one labelled toggle.
    return MergeSemantics(child: ListTile(
      onTap: onTap,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16),
      leading: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: c.accent.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(AppRadii.sm),
        ),
        child: Icon(icon, color: c.accent, size: 22),
      ),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: trailing ?? Icon(Icons.chevron_right, color: c.muted),
    ));
  }
}

/// Manage categories: add (name only, color auto-assigned), rename,
/// archive/unarchive. Shows archived (muted) so they can be restored.
class _CategoriesScreen extends ConsumerStatefulWidget {
  const _CategoriesScreen();

  @override
  ConsumerState<_CategoriesScreen> createState() => _CategoriesScreenState();
}

class _CategoriesScreenState extends ConsumerState<_CategoriesScreen> {
  /// Family categories are the admin's to manage; a member only views them.
  late final _canEdit = canCreateCategories(ref.read(sharedPrefsProvider));

  Future<void> _rename(Category c) async {
    final ctl = TextEditingController(text: c.name);
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Turkum nomini o\'zgartirish'),
        content: TextField(controller: ctl, autofocus: true),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Bekor qilish'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, ctl.text.trim()),
            child: const Text('Saqlash'),
          ),
        ],
      ),
    );
    if (name == null || name.isEmpty || name == c.name) return;
    await ref.read(databaseProvider).updateCategory(c.copyWith(name: name));
  }

  Future<void> _add() async {
    final ctl = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Yangi turkum'),
        content: TextField(
          controller: ctl,
          autofocus: true,
          decoration: const InputDecoration(hintText: 'Turkum nomi'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Bekor qilish'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, ctl.text.trim()),
            child: const Text('Qo\'shish'),
          ),
        ],
      ),
    );
    if (name == null || name.isEmpty) return;
    await matchOrCreateCategory(ref.read(databaseProvider), name);
  }

  Future<void> _toggleArchive(Category c) async {
    await ref
        .read(databaseProvider)
        .updateCategory(c.copyWith(isArchived: !c.isArchived));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Turkumlar'),
        actions: [
          if (_canEdit) IconButton(
            tooltip: 'Turkum qo\'shish',
            icon: const Icon(Icons.add),
            onPressed: _add,
          ),
        ],
      ),
      body: ref
          .watch(allCategoriesProvider)
          .when(
            // ponytail: local DB, resolves in a frame — blank beats a spinner flash.
            loading: () => const SizedBox.shrink(),
            error: (e, _) => Center(child: Text('Xatolik: $e')),
            data: (cats) {
              if (cats.isEmpty) {
                return const EmptyState(
                  icon: Icons.category_outlined,
                  title: 'Hali turkum yo\'q',
                  hint: 'Yuqoridagi + tugmasi bilan qo\'shing',
                );
              }
              final active = [for (final c in cats) if (!c.isArchived) c];
              final archived = [for (final c in cats) if (c.isArchived) c];
              return ListView(
                padding: EdgeInsets.fromLTRB(
                  AppSpace.page,
                  0,
                  AppSpace.page,
                  AppSpace.bottom(context),
                ),
                children: [
                  if (active.isNotEmpty) _group('Faol', active),
                  if (archived.isNotEmpty) _group('Arxivlangan', archived),
                ],
              );
            },
          ),
    );
  }

  Widget _group(String label, List<Category> cats) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 12, 4, 10),
        child: Text(label, style: Theme.of(context).textTheme.labelMedium),
      ),
      Card(
        child: Column(
          children: [
            for (final (i, c) in cats.indexed) ...[
              if (i > 0) const Divider(indent: 72),
              _categoryRow(c),
            ],
          ],
        ),
      ),
      const SizedBox(height: AppSpace.gap),
    ],
  );

  Widget _categoryRow(Category c) => ListTile(
    contentPadding: const EdgeInsets.only(left: 16, right: 4),
    leading: Opacity(
      opacity: c.isArchived ? 0.45 : 1,
      child: CategoryBadge(category: c, size: 40),
    ),
    title: Text(
      c.name,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: c.isArchived ? TextStyle(color: context.colors.muted) : null,
    ),
    trailing: !_canEdit ? null : Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          tooltip: 'Nomini o\'zgartirish',
          icon: const Icon(Icons.edit_outlined, size: 20),
          onPressed: () => _rename(c),
        ),
        IconButton(
          tooltip: c.isArchived ? 'Arxivdan chiqarish' : 'Arxivlash',
          icon: Icon(
            c.isArchived ? Icons.unarchive_outlined : Icons.archive_outlined,
            size: 20,
          ),
          onPressed: () => _toggleArchive(c),
        ),
      ],
    ),
  );
}
