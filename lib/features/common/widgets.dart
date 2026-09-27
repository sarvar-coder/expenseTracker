import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../data/db/database.dart';
import '../../data/db/tables.dart';
import '../../providers/providers.dart';
import '../add/add_screen.dart';
import 'ui_utils.dart';

/// Amount with a smaller "UZS" after it. [style] sets the number; the unit
/// takes 45% of its size, same color at lower weight.
class Money extends StatelessWidget {
  const Money(this.amount, {super.key, this.style, this.color});
  final int amount;
  final TextStyle? style;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final base = (style ?? Theme.of(context).textTheme.titleSmall!).copyWith(
      color: color,
    );
    return Text.rich(
      TextSpan(
        children: [
          TextSpan(text: formatMoney(amount)),
          TextSpan(
            text: ' UZS',
            style: TextStyle(
              fontSize: math.max(11, (base.fontSize ?? 15) * 0.45),
              fontWeight: FontWeight.w700,
              letterSpacing: 0.2,
            ),
          ),
        ],
      ),
      style: base,
      maxLines: 1,
      softWrap: false,
    );
  }
}

/// Rounded square tinted with the category color, holding its initial.
/// The name always sits next to it, so the badge is decorative.
class CategoryBadge extends StatelessWidget {
  const CategoryBadge({super.key, required this.category, this.size = 44});
  final Category? category;
  final double size;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final color = category != null ? colorFromHex(category!.colorHex) : c.muted;
    final name = category?.name.trim() ?? '';
    return ExcludeSemantics(
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.22),
          borderRadius: BorderRadius.circular(size * 0.32),
        ),
        child: Text(
          name.isEmpty ? '?' : name.characters.first.toUpperCase(),
          style: TextStyle(
            fontSize: size * 0.42,
            fontWeight: FontWeight.w800,
            color: c.text,
          ),
        ),
      ),
    );
  }
}

/// Empty screen/section: says what's missing and what to do next.
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.hint,
  });
  final IconData icon;
  final String title;
  final String? hint;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    final c = context.colors;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 40, horizontal: 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              color: c.accent.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(AppRadii.card),
            ),
            child: Icon(icon, color: c.accent, size: 30),
          ),
          const SizedBox(height: 16),
          Text(title, style: t.titleMedium, textAlign: TextAlign.center),
          if (hint != null) ...[
            const SizedBox(height: 6),
            Text(hint!, style: t.bodySmall, textAlign: TextAlign.center),
          ],
        ],
      ),
    );
  }
}

/// Source glyph + label shown under the description.
({String label, IconData icon}) sourceMeta(ExpenseSource s) => switch (s) {
  ExpenseSource.typed => (label: 'Yozilgan', icon: Icons.edit_outlined),
  ExpenseSource.voice => (label: 'Ovozli', icon: Icons.mic_none),
  ExpenseSource.manual => (label: 'Qo\'lda', icon: Icons.list_alt),
};

/// One expense row. Tap opens edit; swipe left deletes after confirm.
/// Frozen rows (shared history of a family you left) are read-only.
/// Used by Home (today) and Tarix.
class ExpenseTile extends ConsumerWidget {
  const ExpenseTile({super.key, required this.expense, required this.category});
  final Expense expense;
  final Category? category;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final src = sourceMeta(expense.source);
    return Dismissible(
      key: ValueKey(expense.id),
      direction: expense.frozen ? DismissDirection.none : DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        color: c.danger,
        child: Icon(Icons.delete_outline, color: Theme.of(context).colorScheme.onError),
      ),
      confirmDismiss: (_) => confirmDeleteExpense(context, expense.description),
      onDismissed: (_) async {
        await ref.read(databaseProvider).deleteExpense(expense.id);
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Xarajat o\'chirildi')),
          );
        }
      },
      child: InkWell(
        onTap: expense.frozen
            ? null
            : () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => AddScreen(editing: expense)),
                ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              CategoryBadge(category: category),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      expense.description,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: t.titleSmall,
                    ),
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            category?.name ?? 'Turkumsiz',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: t.bodySmall,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Icon(src.icon, size: 13, color: c.muted, semanticLabel: src.label),
                        if (expense.isPrivate) ...[
                          const SizedBox(width: 4),
                          Icon(Icons.visibility_off_outlined, size: 13,
                              color: c.muted, semanticLabel: 'Maxfiy'),
                        ],
                        if (expense.frozen) ...[
                          const SizedBox(width: 4),
                          Icon(Icons.lock_outline, size: 13, color: c.muted,
                              semanticLabel: 'Faqat o\'qish uchun'),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(formatMoney(expense.amount), style: t.titleSmall),
                  const SizedBox(height: 2),
                  Text(DateFormat('HH:mm').format(expense.date), style: t.bodySmall),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
