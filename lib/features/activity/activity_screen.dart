import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../data/db/tables.dart';
import '../../providers/providers.dart';
import '../add/add_screen.dart';
import '../common/ui_utils.dart';
import 'activity_filter.dart';

/// Transactions: search + category-chip filter over the full expense stream,
/// grouped by day, with swipe-to-delete.
class ActivityScreen extends ConsumerStatefulWidget {
  const ActivityScreen({super.key});

  @override
  ConsumerState<ActivityScreen> createState() => _ActivityScreenState();
}

class _ActivityScreenState extends ConsumerState<ActivityScreen> {
  String _query = '';
  int? _categoryId;

  @override
  Widget build(BuildContext context) {
    final expenses =
        ref.watch(expensesProvider).asData?.value ?? const <Expense>[];
    final categories =
        ref.watch(categoriesProvider).asData?.value ?? const <Category>[];
    final catById = {for (final c in categories) c.id: c};
    final sections = groupExpenses(
      expenses,
      query: _query,
      categoryId: _categoryId,
      now: DateTime.now(),
    );

    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
      children: [
        const Text(
          'Tranzaksiyalar',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            color: AppColors.text,
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          onChanged: (v) => setState(() => _query = v),
          decoration: InputDecoration(
            hintText: 'Xarajatlarni qidirish',
            prefixIcon: const Icon(
              Icons.search,
              size: 20,
              color: AppColors.muted,
            ),
            filled: true,
            fillColor: AppColors.card,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(13),
              borderSide: const BorderSide(color: AppColors.border),
            ),
          ),
        ),
        const SizedBox(height: 12),
        _ChipBar(
          categories: categories,
          selected: _categoryId,
          onSelect: (id) => setState(() => _categoryId = id),
        ),
        const SizedBox(height: 8),
        if (sections.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 40),
            child: Center(
              child: Text(
                expenses.isEmpty ? 'Hali xarajat yo\'q' : 'Mos keladigani yo\'q',
                style: const TextStyle(color: AppColors.muted),
              ),
            ),
          )
        else
          for (final section in sections) ...[
            Padding(
              padding: const EdgeInsets.only(top: 14, bottom: 4),
              child: Text(
                section.label,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  color: AppColors.muted,
                ),
              ),
            ),
            for (final e in section.items)
              _TxnRow(expense: e, category: catById[e.categoryId]),
          ],
      ],
    );
  }
}

class _ChipBar extends StatelessWidget {
  const _ChipBar({
    required this.categories,
    required this.selected,
    required this.onSelect,
  });
  final List<Category> categories;
  final int? selected;
  final ValueChanged<int?> onSelect;

  @override
  Widget build(BuildContext context) {
    Widget chip(String label, int? id) {
      final on = id == selected;
      return Padding(
        padding: const EdgeInsets.only(right: 8),
        child: GestureDetector(
          onTap: () => onSelect(id),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            decoration: BoxDecoration(
              color: on ? AppColors.accent : AppColors.card,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: on ? AppColors.accent : AppColors.border,
              ),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 13,
                color: on ? Colors.white : AppColors.text,
              ),
            ),
          ),
        ),
      );
    }

    return SizedBox(
      height: 38,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          chip('Hammasi', null),
          for (final c in categories) chip(c.name, c.id),
        ],
      ),
    );
  }
}

/// Source label + glyph for the row subtitle.
({String label, IconData icon}) _sourceMeta(ExpenseSource s) => switch (s) {
  ExpenseSource.typed => (label: 'Yozilgan', icon: Icons.edit_outlined),
  ExpenseSource.voice => (label: 'Ovozli', icon: Icons.mic_none),
  ExpenseSource.manual => (label: 'Qo\'lda', icon: Icons.list_alt),
};

class _TxnRow extends ConsumerWidget {
  const _TxnRow({required this.expense, required this.category});
  final Expense expense;
  final Category? category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final color = category != null
        ? colorFromHex(category!.colorHex)
        : AppColors.muted;
    final src = _sourceMeta(expense.source);
    return Dismissible(
      key: ValueKey(expense.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 20),
        color: Colors.redAccent,
        child: const Icon(Icons.delete, color: Colors.white),
      ),
      confirmDismiss: (_) async =>
          await showDialog<bool>(
            context: context,
            builder: (ctx) => AlertDialog(
              title: const Text('Xarajat o\'chirilsinmi?'),
              content: Text('“${expense.description}” o\'chiriladi.'),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('Bekor qilish'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('O\'chirish'),
                ),
              ],
            ),
          ) ??
          false,
      onDismissed: (_) async {
        await ref.read(databaseProvider).deleteExpense(expense.id);
        if (context.mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('Xarajat o\'chirildi')));
        }
      },
      child: InkWell(
        onTap: () => Navigator.of(
          context,
        ).push(MaterialPageRoute(builder: (_) => AddScreen(editing: expense))),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 7),
          child: Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Icon(
                  iconForKey(category?.iconKey ?? 'category'),
                  size: 18,
                  color: color,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      expense.description,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14,
                        color: AppColors.text,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        Text(
                          '${category?.name ?? 'Turkumsiz'} · ',
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.muted,
                          ),
                        ),
                        Icon(src.icon, size: 12, color: AppColors.muted),
                        const SizedBox(width: 3),
                        Text(
                          src.label,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.muted,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    formatMoney(expense.amount),
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      color: AppColors.text,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    DateFormat('HH:mm').format(expense.date),
                    style: const TextStyle(
                      fontSize: 11,
                      color: AppColors.muted,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
