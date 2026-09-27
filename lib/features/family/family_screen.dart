import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../providers/providers.dart';
import '../../services/family_service.dart';
import '../common/ui_utils.dart';
import '../common/widgets.dart';
import '../home/home_summary.dart';

/// Oila tab. Not in a family: pending invites + create. In one: this month's
/// family budget vs shared spending, members, the shared expense list, and
/// the admin's invites / category requests. Online only (server summary).
class FamilyScreen extends ConsumerWidget {
  const FamilyScreen({super.key});

  static const tab = 3;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen(tabIndexProvider, (_, i) {
      if (i == tab) _refresh(ref);
    });
    final t = Theme.of(context).textTheme;
    final async = ref.watch(familyOverviewProvider);
    return RefreshIndicator(
      onRefresh: () async {
        _refresh(ref);
        try {
          await ref.read(familyOverviewProvider.future);
        } catch (_) {} // shown by the error state
      },
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(
          AppSpace.page,
          8,
          AppSpace.page,
          AppSpace.section + 72, // clear the floating add button
        ),
        children: [
          Text(async.value?.name ?? 'Oila', style: t.headlineMedium),
          const SizedBox(height: AppSpace.gap),
          if (async.hasValue)
            async.requireValue == null
                ? const _NoFamily()
                : _InFamily(family: async.requireValue!)
          else if (async.hasError)
            const EmptyState(
              icon: Icons.wifi_off,
              title: 'Oila ma\'lumotini yuklab bo\'lmadi',
              hint: 'Internetni tekshirib, pastga torting',
            )
          else
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(child: CircularProgressIndicator()),
            ),
        ],
      ),
    );
  }
}

void _refresh(WidgetRef ref) {
  ref.invalidate(familyOverviewProvider);
  ref.invalidate(myInvitesProvider);
}

/// Runs a family action, shows its error (or [done]), then refetches.
Future<void> _run(
  BuildContext context,
  WidgetRef ref,
  Future<void> Function(FamilyService s) action, {
  String? done,
}) async {
  final messenger = ScaffoldMessenger.of(context);
  void toast(String m) => messenger.showSnackBar(SnackBar(content: Text(m)));
  try {
    await action(ref.read(familyServiceProvider));
    if (done != null) toast(done);
  } on FamilyException catch (e) {
    toast(e.message);
  }
  _refresh(ref);
}

Future<bool> _confirm(BuildContext context, String title, String body, String action) async =>
    await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Bekor qilish'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(action),
          ),
        ],
      ),
    ) ??
    false;

Future<String?> _askText(
  BuildContext context, {
  required String title,
  required String hint,
  required String action,
  TextInputType? keyboard,
}) async {
  final ctl = TextEditingController();
  final text = await showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: ctl,
        autofocus: true,
        keyboardType: keyboard,
        decoration: InputDecoration(hintText: hint),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Bekor qilish'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(ctx, ctl.text.trim()),
          child: Text(action),
        ),
      ],
    ),
  );
  // ponytail: no ctl.dispose() — the dialog's TextField is still mounted during
  // its exit animation, and disposing then trips '_dependents.isEmpty'. GC frees it.
  return text == null || text.isEmpty ? null : text;
}

/// Joining or creating: share past expenses too? Null = cancelled.
Future<bool?> _askHistory(BuildContext context) => showDialog<bool>(
  context: context,
  builder: (ctx) => AlertDialog(
    title: const Text('Avvalgi xarajatlar'),
    content: const Text(
      'Oldingi xarajatlaringiz ham oilaga ko\'rsatilsinmi? '
      'Maxfiy xarajatlar baribir yashirin qoladi.',
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(ctx, false),
        child: const Text('Yo\'q'),
      ),
      TextButton(
        onPressed: () => Navigator.pop(ctx, true),
        child: const Text('Ha, ulashish'),
      ),
    ],
  ),
);

class _NoFamily extends ConsumerWidget {
  const _NoFamily();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = Theme.of(context).textTheme;
    final invites = ref.watch(myInvitesProvider).value ?? const <FamilyInvite>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const EmptyState(
          icon: Icons.family_restroom,
          title: 'Siz hali oilada emassiz',
          hint: 'Oila yarating yoki taklifni qabul qiling. '
              'Umumiy xarajatlar va byudjet shu yerda ko\'rinadi.',
        ),
        for (final i in invites)
          Card(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 12, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('“${i.familyName}” oilasiga taklif', style: t.titleMedium),
                  Text('${i.invitedBy} taklif qildi', style: t.bodySmall),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(
                        onPressed: () => _run(
                          context,
                          ref,
                          (s) => s.deleteInvite(i.id),
                        ),
                        child: const Text('Rad etish'),
                      ),
                      FilledButton(
                        onPressed: () async {
                          final history = await _askHistory(context);
                          if (history == null || !context.mounted) return;
                          await _run(
                            context,
                            ref,
                            (s) => s.accept(i.id, includeHistory: history),
                            done: 'Oilaga qo\'shildingiz',
                          );
                        },
                        child: const Text('Qabul qilish'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        const SizedBox(height: AppSpace.gap),
        FilledButton.icon(
          onPressed: () => _create(context, ref),
          icon: const Icon(Icons.add),
          label: const Text('Oila yaratish'),
        ),
      ],
    );
  }

  Future<void> _create(BuildContext context, WidgetRef ref) async {
    final name = await _askText(
      context,
      title: 'Yangi oila',
      hint: 'Oila nomi',
      action: 'Davom etish',
    );
    if (name == null || !context.mounted) return;
    final history = await _askHistory(context);
    if (history == null || !context.mounted) return;
    await _run(
      context,
      ref,
      (s) => s.create(name, includeHistory: history),
      done: 'Oila yaratildi',
    );
  }
}

class _InFamily extends ConsumerWidget {
  const _InFamily({required this.family});
  final FamilyOverview family;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final f = family;
    final c = context.colors;
    final now = DateTime.now();
    final from = DateTime(now.year, now.month);
    final me = f.members.where((m) => m.userId == f.myId).firstOrNull;
    // Own shared rows come from Drift (live, offline edits included); the
    // server only sends the others'.
    final mine = [
      for (final e in ref.watch(expensesProvider).value ?? const <Expense>[])
        if (e.familyId == f.id && !e.isPrivate && !e.date.isBefore(from))
          (
            id: e.id,
            ownerName: me?.name ?? 'Siz',
            categoryId: e.categoryId,
            description: e.description,
            amount: e.amount,
            date: e.date,
          ),
    ];
    final list = [...mine, ...f.others]..sort((a, b) => b.date.compareTo(a.date));
    final cats = {
      for (final k in ref.watch(allCategoriesProvider).value ?? const <Category>[]) k.id: k,
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _BudgetCard(summary: HomeSummary(spent: f.spent, budget: f.budget)),
        _label(context, 'A\'zolar'),
        Card(
          child: Column(
            children: [
              for (final (i, m) in f.members.indexed) ...[
                if (i > 0) const Divider(indent: 16),
                _memberRow(context, ref, m),
              ],
            ],
          ),
        ),
        if (f.isAdmin) ..._adminSection(context, ref),
        _label(context, 'Bu oygi umumiy xarajatlar'),
        if (list.isEmpty)
          const EmptyState(
            icon: Icons.receipt_long_outlined,
            title: 'Bu oy umumiy xarajat yo\'q',
          )
        else
          Card(
            child: Column(
              children: [
                for (final (i, e) in list.indexed) ...[
                  if (i > 0) const Divider(indent: 72),
                  ListTile(
                    leading: CategoryBadge(category: cats[e.categoryId]),
                    title: Text(e.description, maxLines: 1, overflow: TextOverflow.ellipsis),
                    subtitle: Text(
                      '${e.ownerName} · ${uzDayMonth(e.date)} ${DateFormat('HH:mm').format(e.date)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: Money(e.amount),
                  ),
                ],
              ],
            ),
          ),
        const SizedBox(height: AppSpace.section),
        OutlinedButton.icon(
          onPressed: () async {
            if (!await _confirm(
              context,
              'Oiladan chiqasizmi?',
              'Umumiy xarajatlaringiz oila tarixida faqat o\'qish uchun qoladi.',
              'Chiqish',
            )) {
              return;
            }
            if (context.mounted) await _run(context, ref, (s) => s.leave());
          },
          style: OutlinedButton.styleFrom(
            foregroundColor: c.danger,
            side: BorderSide(color: c.danger, width: 1.5),
          ),
          icon: const Icon(Icons.logout),
          label: const Text('Oiladan chiqish'),
        ),
        if (f.isAdmin)
          TextButton(
            onPressed: () async {
              if (!await _confirm(
                context,
                'Oila o\'chirilsinmi?',
                'Barcha a\'zolar chiqariladi. Xarajatlar har kimning o\'zida qoladi.',
                'O\'chirish',
              )) {
                return;
              }
              if (context.mounted) await _run(context, ref, (s) => s.deleteFamily());
            },
            style: TextButton.styleFrom(foregroundColor: c.danger),
            child: const Text('Oilani o\'chirish'),
          ),
      ],
    );
  }

  Widget _memberRow(BuildContext context, WidgetRef ref, FamilyMember m) {
    final self = m.userId == family.myId;
    return ListTile(
      title: Text(
        [m.name, if (self) '(siz)', if (m.isAdmin) '· admin'].join(' '),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text('Byudjetga hissa: ${formatMoney(m.contribution)} UZS'),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Money(m.shared),
          if (family.isAdmin && !self)
            PopupMenuButton<String>(
              tooltip: 'Amallar',
              onSelected: (a) async {
                final (title, body, action) = a == 'admin'
                    ? ('${m.name} admin qilinsinmi?', 'Siz oddiy a\'zo bo\'lasiz.', 'Admin qilish')
                    : (
                        '${m.name} oiladan chiqarilsinmi?',
                        'Umumiy xarajatlari oila tarixida faqat o\'qish uchun qoladi.',
                        'Chiqarish',
                      );
                if (!await _confirm(context, title, body, action) || !context.mounted) return;
                await _run(
                  context,
                  ref,
                  (s) => a == 'admin' ? s.transferAdmin(m.userId) : s.removeMember(m.userId),
                );
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'admin', child: Text('Admin qilish')),
                PopupMenuItem(value: 'remove', child: Text('Oiladan chiqarish')),
              ],
            ),
        ],
      ),
    );
  }

  List<Widget> _adminSection(BuildContext context, WidgetRef ref) {
    final f = family;
    return [
      _label(context, 'Takliflar'),
      Card(
        child: Column(
          children: [
            for (final i in f.invites)
              ListTile(
                leading: const Icon(Icons.mail_outline),
                title: Text(i.email),
                subtitle: const Text('Javob kutilmoqda'),
                trailing: IconButton(
                  tooltip: 'Taklifni bekor qilish',
                  icon: const Icon(Icons.close),
                  onPressed: () => _run(context, ref, (s) => s.deleteInvite(i.id)),
                ),
              ),
            ListTile(
              leading: const Icon(Icons.person_add_alt),
              title: const Text('A\'zo taklif qilish'),
              onTap: () async {
                final email = await _askText(
                  context,
                  title: 'A\'zo taklif qilish',
                  hint: 'email@misol.uz',
                  action: 'Taklif qilish',
                  keyboard: TextInputType.emailAddress,
                );
                if (email == null || !context.mounted) return;
                await _run(
                  context,
                  ref,
                  (s) => s.invite(f.id, email),
                  done: 'Taklif yuborildi. U kirganda ko\'radi.',
                );
              },
            ),
          ],
        ),
      ),
      _label(context, 'Turkum so\'rovlari', badge: f.requests.length),
      if (f.requests.isEmpty)
        Padding(
          padding: const EdgeInsets.only(left: 4),
          child: Text('Yangi so\'rov yo\'q', style: Theme.of(context).textTheme.bodySmall),
        )
      else
        Card(
          child: Column(
            children: [
              for (final r in f.requests)
                ListTile(
                  title: Text(r.name),
                  subtitle: const Text('Hozircha "Boshqa"da'),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        tooltip: 'Rad etish',
                        icon: const Icon(Icons.close),
                        onPressed: () => _run(context, ref, (s) => s.rejectRequest(r.id)),
                      ),
                      IconButton(
                        tooltip: 'Tasdiqlash',
                        icon: Icon(Icons.check, color: context.colors.accent),
                        onPressed: () => _run(
                          context,
                          ref,
                          (s) => s.approveRequest(r.id),
                          done: '“${r.name}” turkumi yaratildi',
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
    ];
  }

  Widget _label(BuildContext context, String text, {int badge = 0}) => Padding(
    padding: const EdgeInsets.fromLTRB(4, AppSpace.section, 4, 10),
    child: Row(
      children: [
        Text(text, style: Theme.of(context).textTheme.labelMedium),
        if (badge > 0) ...[
          const SizedBox(width: 8),
          Badge(label: Text('$badge')),
        ],
      ],
    ),
  );
}

/// Family budget (sum of contributions) vs shared spending this month.
class _BudgetCard extends StatelessWidget {
  const _BudgetCard({required this.summary});
  final HomeSummary summary;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final soft = c.onHero.withValues(alpha: 0.72);
    final over = summary.budget > 0 && summary.spent > summary.budget;
    return Container(
      padding: const EdgeInsets.fromLTRB(22, 22, 22, 18),
      decoration: BoxDecoration(
        color: c.hero,
        borderRadius: BorderRadius.circular(AppRadii.hero),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Oila bu oy sarfladi', style: t.labelMedium!.copyWith(color: soft)),
          const SizedBox(height: 6),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Money(summary.spent, style: t.displayMedium, color: c.onHero),
          ),
          const SizedBox(height: 16),
          if (summary.budget <= 0)
            Text(
              'Oila byudjeti yo\'q — a\'zolar Sozlamalarda byudjet belgilaydi',
              style: t.labelSmall!.copyWith(color: soft),
            )
          else ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(AppRadii.chip),
              child: LinearProgressIndicator(
                value: summary.progress,
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
                    'Byudjet: ${formatMoney(summary.budget)}',
                    style: t.labelSmall!.copyWith(color: soft),
                  ),
                ),
                Text(
                  over
                      ? '${formatMoney(-summary.remaining)} oshdi'
                      : '${formatMoney(summary.remaining)} qoldi',
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
