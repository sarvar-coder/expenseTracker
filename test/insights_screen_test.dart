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
    'shows total and legend; month pills; year sheet; custom picker',
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

      // Custom pill opens the range picker.
      await tester.ensureVisible(find.widgetWithText(ChoiceChip, 'Maxsus'));
      await tester.tap(find.widgetWithText(ChoiceChip, 'Maxsus'));
      await tester.pumpAndSettle();
      expect(find.byType(DateRangePickerDialog), findsOneWidget);
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
