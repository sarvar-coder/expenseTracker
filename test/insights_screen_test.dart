import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/common/ui_utils.dart';
import 'package:expense_tracker/features/insights/insights_screen.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  testWidgets(
    'shows total and legend; month pills; year sheet; filter swaps pills for chips',
    (tester) async {
      final db = AppDatabase.forTesting(NativeDatabase.memory());
      late List<Category> cats;
      late List<Expense> expenses;
      await tester.runAsync(() async {
        cats = await db.getCategories();
        await db.insertExpense(
          ExpensesCompanion.insert(
            description: 'Coffee',
            amount: 45000,
            categoryId: cats.firstWhere((c) => c.name == 'Food & dining').id,
            date: DateTime.now(),
            source: ExpenseSource.manual,
          ),
        );
        expenses = await db.getExpenses();
        await db.close();
      });

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            categoriesProvider.overrideWith((ref) => Stream.value(cats)),
            expensesProvider.overrideWith((ref) => Stream.value(expenses)),
          ],
          child: const MaterialApp(home: Scaffold(body: InsightsScreen())),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.bySemanticsLabel('Jami 45 000 UZS'), findsOneWidget);
      expect(find.text('Food & dining'), findsOneWidget);
      expect(find.text('100%'), findsOneWidget);

      // Current month pill selected; future months hidden.
      final now = DateTime.now();
      final current = tester.widget<ChoiceChip>(
        find.widgetWithText(ChoiceChip, uzMonths[now.month - 1]),
      );
      expect(current.selected, isTrue);
      if (now.month < 12) {
        expect(
          find.widgetWithText(ChoiceChip, uzMonths[now.month]),
          findsNothing,
        );
      }

      // Year sheet lists years; picking last year shows all 12 months, no data.
      await tester.tap(find.text('${now.year}'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('${now.year - 1}'));
      await tester.pumpAndSettle();
      expect(find.text('${now.year - 1}'), findsOneWidget);
      expect(find.text('Bu davrda xarajat yo\'q'), findsOneWidget);
      expect(find.widgetWithText(ChoiceChip, 'Dekabr'), findsOneWidget);

      // No Maxsus pill any more — the filter's Sana replaces it.
      expect(find.widgetWithText(ChoiceChip, 'Maxsus'), findsNothing);

      // Filter by Ko'rinish: month pills give way to chips (period + filter).
      await tester.tap(find.byTooltip('Filtr'));
      await tester.pumpAndSettle();
      expect(find.text('Turkum'), findsOneWidget);
      await tester.tap(find.text('Ko\'rinish'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(RadioListTile<bool?>, 'Umumiy'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Qo\'llash'));
      await tester.pumpAndSettle();
      expect(find.byType(ChoiceChip), findsNothing);
      expect(
        find.text('${uzMonths[now.month - 1]} ${now.year - 1}'),
        findsOneWidget,
      );
      expect(find.widgetWithText(InputChip, 'Umumiy'), findsOneWidget);

      // Removing the last chip brings the month pills back.
      await tester.tap(find.byTooltip('Olib tashlash'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(ChoiceChip, 'Dekabr'), findsOneWidget);
    },
  );

  testWidgets('empty period shows empty state', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          categoriesProvider.overrideWith((ref) => Stream.value(const [])),
          expensesProvider.overrideWith((ref) => Stream.value(const [])),
        ],
        child: const MaterialApp(home: Scaffold(body: InsightsScreen())),
      ),
    );
    await tester.pump();

    expect(find.text('Bu davrda xarajat yo\'q'), findsOneWidget);
  });
}
